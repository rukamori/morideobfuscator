/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.morideobfuscator

import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

object MoriCipherRuntime : MoriCipherResolver {
    private val mutableSnapshot = MutableStateFlow(CipherSnapshot())
    override val snapshot: StateFlow<CipherSnapshot> = mutableSnapshot.asStateFlow()

    private val initializationMutex = Mutex()
    private val refreshMutex = Mutex()
    private val executionMutex = Mutex()

    @Volatile
    private var engine: Engine? = null
    private var activeRefresh: CompletableDeferred<Result<CipherRefreshResult>>? = null

    fun initialize(config: MoriCipherConfig) {
        if (engine != null) return
        synchronized(this) {
            if (engine != null) return
            val store = CipherArtifactStore(config.cacheDirectory)
            engine =
                Engine(
                    config = config,
                    client = PlayerScriptClient(config.proxyProvider),
                    compiler = JavaScriptPlanCompiler(),
                    executor = RhinoTransformExecutor(),
                    store = store,
                    artifact = null,
                )
            mutableSnapshot.value = CipherSnapshot()
        }
    }

    override suspend fun refresh(
        force: Boolean,
        videoId: String?,
    ): Result<CipherRefreshResult> {
        val currentEngine = requireEngine()
        ensureCacheLoaded(currentEngine)
        val (shared, ownsRefresh) =
            refreshMutex.withLock {
                activeRefresh?.takeIf { !it.isCompleted }?.let { return@withLock it to false }
                CompletableDeferred<Result<CipherRefreshResult>>()
                    .also { activeRefresh = it } to true
            }
        if (!ownsRefresh) return shared.await()

        val result =
            try {
            runCatching {
                val current = currentEngine.artifact
                val now = System.currentTimeMillis()
                if (
                    !force &&
                    current != null &&
                    now - current.plan.createdAtMillis < currentEngine.config.refreshIntervalMillis
                ) {
                    return@runCatching CipherRefreshResult(
                        playerId = current.plan.playerId,
                        refreshedAtMillis = current.plan.createdAtMillis,
                        changed = false,
                    )
                }

                mutableSnapshot.value =
                    mutableSnapshot.value.copy(
                        status = CipherRuntimeStatus.REFRESHING,
                        lastFailure = null,
                    )
                val script = currentEngine.client.fetch(videoId)
                val plan = currentEngine.compiler.compile(script, now)
                executionMutex.withLock {
                    validatePlan(currentEngine, plan)
                }
                val artifact = CachedTransformArtifact(plan, script.source)
                withContext(Dispatchers.IO) { currentEngine.store.write(artifact) }
                currentEngine.artifact = artifact
                mutableSnapshot.value =
                    CipherSnapshot(
                        status = CipherRuntimeStatus.READY,
                        playerId = plan.playerId,
                        lastSuccessfulRefreshMillis = now,
                        nextRefreshAtMillis = now + currentEngine.config.refreshIntervalMillis,
                    )
                CipherRefreshResult(
                    playerId = plan.playerId,
                    refreshedAtMillis = now,
                    changed = current?.plan?.sourceSha256 != plan.sourceSha256,
                )
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                val cached = currentEngine.artifact?.plan
                mutableSnapshot.value =
                    CipherSnapshot(
                        status = if (cached == null) CipherRuntimeStatus.UNINITIALIZED else CipherRuntimeStatus.DEGRADED,
                        playerId = cached?.playerId,
                        lastSuccessfulRefreshMillis = cached?.createdAtMillis,
                        nextRefreshAtMillis = cached?.createdAtMillis?.plus(currentEngine.config.refreshIntervalMillis),
                        lastFailure = failure.message,
                    )
            }
        } catch (cancellation: CancellationException) {
            shared.cancel(cancellation)
            refreshMutex.withLock {
                if (activeRefresh === shared) activeRefresh = null
            }
            throw cancellation
        }
        shared.complete(result)
        refreshMutex.withLock {
            if (activeRefresh === shared) activeRefresh = null
        }
        return result
    }

    override suspend fun signatureTimestamp(videoId: String): Result<Int?> =
        withSelfHealingPlan(videoId) { plan -> plan.signatureTimestamp }

    override suspend fun resolveStreamUrl(
        videoId: String,
        signatureCipher: String,
    ): Result<String> =
        withSelfHealingPlan(videoId) { plan ->
            executionMutex.withLock {
                val cipherParameters = parseQueryString(signatureCipher)
                val sourceUrl =
                    cipherParameters["url"]
                        ?: throw MoriCipherException("Cipher URL parameter was missing")
                val signature =
                    cipherParameters["s"]
                        ?: throw MoriCipherException("Cipher signature parameter was missing")
                val signatureParameter =
                    cipherParameters["sp"]?.takeIf { it.isNotBlank() }
                        ?: "signature"
                val deciphered = requireEngine().executor.executeSignature(plan, signature)
                URLBuilder(sourceUrl).apply {
                    parameters[signatureParameter] = deciphered
                }.buildString()
            }
        }.mapCatching { transformNParameter(videoId, it).getOrElse { _ -> it } }

    override suspend fun transformNParameter(
        videoId: String,
        url: String,
    ): Result<String> {
        val builder =
            runCatching { URLBuilder(url) }
                .getOrElse { return Result.failure(MoriCipherException("Stream URL was invalid", it)) }
        val nValue = builder.parameters["n"] ?: return Result.success(url)
        return withSelfHealingPlan(videoId) { plan ->
            executionMutex.withLock {
                val transformed = requireEngine().executor.executeN(plan, nValue)
                builder.parameters["n"] = transformed
                builder.buildString()
            }
        }
    }

    private suspend fun <T> withSelfHealingPlan(
        videoId: String,
        operation: suspend (TransformPlan) -> T,
    ): Result<T> {
        val currentEngine = requireEngine()
        ensureCacheLoaded(currentEngine)
        val firstPlan =
            currentEngine.artifact?.plan
                ?: refresh(force = true, videoId = videoId).getOrElse { return Result.failure(it) }
                    .let { currentEngine.artifact?.plan }
                ?: return Result.failure(MoriCipherException("Cipher plan was unavailable"))
        return try {
            Result.success(operation(firstPlan))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (firstFailure: Exception) {
            refresh(force = true, videoId = videoId).getOrElse {
                return Result.failure(firstFailure)
            }
            val refreshed =
                currentEngine.artifact?.plan
                    ?: return Result.failure(firstFailure)
            runCatching { operation(refreshed) }
        }
    }

    private fun validatePlan(
        engine: Engine,
        plan: TransformPlan,
    ) {
        plan.signatureProgram?.let {
            val sample = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val result = engine.executor.executeSignature(plan, sample)
            if (result.isBlank() || result == sample) {
                throw MoriCipherException("Signature transform validation failed")
            }
        }
        plan.nProgram?.let {
            val sample = "abcdefghijklmnopqrstuvwxyz0123456789_-"
            val result = engine.executor.executeN(plan, sample)
            if (result.isBlank() || result == sample) {
                throw MoriCipherException("Throttle transform validation failed")
            }
        }
    }

    private suspend fun requireEngine(): Engine {
        engine?.let { return it }
        initializationMutex.withLock {
            engine?.let { return it }
            throw MoriCipherException("Mori cipher runtime was not initialized")
        }
    }

    private suspend fun ensureCacheLoaded(engine: Engine) {
        if (engine.cacheLoaded.get()) return
        engine.cacheLoadMutex.withLock {
            if (engine.cacheLoaded.get()) return
            val cached = withContext(Dispatchers.IO) { engine.store.read() }
            if (cached != null) {
                engine.artifact = cached
                val plan = cached.plan
                mutableSnapshot.value =
                    CipherSnapshot(
                        status = CipherRuntimeStatus.READY,
                        playerId = plan.playerId,
                        lastSuccessfulRefreshMillis = plan.createdAtMillis,
                        nextRefreshAtMillis = plan.createdAtMillis + engine.config.refreshIntervalMillis,
                    )
            }
            engine.cacheLoaded.set(true)
        }
    }

    private class Engine(
        val config: MoriCipherConfig,
        val client: PlayerScriptClient,
        val compiler: JavaScriptPlanCompiler,
        val executor: RhinoTransformExecutor,
        val store: CipherArtifactStore,
        @Volatile var artifact: CachedTransformArtifact?,
    ) {
        val cacheLoaded = AtomicBoolean(false)
        val cacheLoadMutex = Mutex()
    }
}
