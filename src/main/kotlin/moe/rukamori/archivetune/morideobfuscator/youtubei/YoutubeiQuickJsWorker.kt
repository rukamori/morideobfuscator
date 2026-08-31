/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.morideobfuscator.youtubei

import android.content.Context
import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

internal class YoutubeiQuickJsWorker(
    context: Context,
    name: String,
    private val httpClient: YoutubeiHttpClient,
    private val diskCache: YoutubeiDiskCache,
) {
    private val applicationContext = context.applicationContext
    private val dispatcher =
        Executors
            .newSingleThreadExecutor { runnable ->
                Thread(runnable, "ArchiveTune-Youtubei-$name").apply { isDaemon = true }
            }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private val secureRandom = SecureRandom()

    @Volatile
    private var initialized = false

    private var quickJs: QuickJs? = null

    suspend fun preWarm() {
        mutex.withLock {
            withContext(dispatcher) {
                ensureInitialized()
            }
        }
    }

    suspend fun resolve(requestJson: String): String =
        mutex.withLock {
            withContext(dispatcher) {
                val runtime = ensureInitialized()
                try {
                    val preparation =
                        runtime.evaluate<String>(
                            code =
                                "await globalThis.ArchiveTuneYoutubei.prepare(" +
                                    JSONObject.quote(requestJson) +
                                    ");",
                            filename = "archivetune-prepare.js",
                        )
                    runtime.gc()
                    if (!JSONObject(preparation).optBoolean("ok")) {
                        return@withContext preparation
                    }
                    val response =
                        runtime.evaluate<String>(
                            code =
                                "await globalThis.ArchiveTuneYoutubei.resolvePrepared(" +
                                    JSONObject.quote(requestJson) +
                                    ");",
                            filename = "archivetune-resolve.js",
                        )
                    runtime.gc()
                    response
                } catch (throwable: Throwable) {
                    if (throwable.isQuickJsOutOfMemory()) {
                        discardRuntime(runtime, throwable)
                    } else {
                        try {
                            runtime.gc()
                        } catch (gcFailure: Throwable) {
                            throwable.addSuppressed(gcFailure)
                        }
                    }
                    throw throwable
                }
            }
        }

    suspend fun closeRuntime() {
        mutex.withLock {
            withContext(dispatcher) {
                quickJs?.close()
                quickJs = null
                initialized = false
            }
        }
    }

    private suspend fun ensureInitialized(): QuickJs {
        quickJs?.takeIf { initialized }?.let { return it }
        quickJs?.close()
        val runtime = QuickJs.create(dispatcher)
        try {
            runtime.memoryLimit = JAVASCRIPT_MEMORY_LIMIT_BYTES
            runtime.maxStackSize = JAVASCRIPT_STACK_LIMIT_BYTES
            runtime.evaluationTimeoutMillis = JAVASCRIPT_TIMEOUT_MS
            runtime.asyncFunction<String, String>("__archiveTuneHttp") { request ->
                httpClient.execute(request)
            }
            runtime.asyncFunction<String, String>("__archiveTunePlayerSource") { request ->
                httpClient.executePlayerScript(request)
            }
            runtime.asyncFunction<String, String?>("__archiveTuneCacheRead") { key ->
                diskCache.read(key)
            }
            runtime.asyncFunction<String, Unit>("__archiveTuneCacheWrite") { request ->
                diskCache.write(request)
            }
            runtime.asyncFunction<String, Unit>("__archiveTuneCacheRemove") { key ->
                diskCache.remove(key)
            }
            runtime.asyncFunction<String, String>("__archiveTuneSha1") { value ->
                MessageDigest
                    .getInstance("SHA-1")
                    .digest(value.toByteArray(Charsets.UTF_8))
                    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
            runtime.function("__archiveTuneUuid") { UUID.randomUUID().toString() }
            runtime.function("__archiveTuneRandom") { arguments ->
                val size = (arguments.firstOrNull() as? Number)?.toInt()?.coerceIn(0, MAX_RANDOM_BYTES) ?: 0
                ByteArray(size)
                    .also(secureRandom::nextBytes)
                    .let { Base64.encodeToString(it, Base64.NO_WRAP) }
            }
            val source =
                applicationContext.assets
                    .open(BUNDLE_ASSET)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            runtime.evaluate<Unit>(source, BUNDLE_ASSET)
            val version = runtime.evaluate<String>("globalThis.ArchiveTuneYoutubei.version;")
            check(version == YOUTUBEI_VERSION)
            quickJs = runtime
            initialized = true
            return runtime
        } catch (throwable: Throwable) {
            runtime.close()
            throw throwable
        }
    }

    private fun discardRuntime(
        runtime: QuickJs,
        failure: Throwable,
    ) {
        if (quickJs === runtime) {
            quickJs = null
            initialized = false
        }
        try {
            runtime.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }

    private fun Throwable.isQuickJsOutOfMemory(): Boolean =
        this is QuickJsException && message.orEmpty().contains("out of memory", ignoreCase = true)

    private companion object {
        const val YOUTUBEI_VERSION = "18.0.0"
        const val BUNDLE_ASSET = "youtubei/youtubei.bundle.js"
        const val JAVASCRIPT_TIMEOUT_MS = 30_000L
        const val JAVASCRIPT_MEMORY_LIMIT_BYTES = 256L * 1024L * 1024L
        const val JAVASCRIPT_STACK_LIMIT_BYTES = 2L * 1024L * 1024L
        const val MAX_RANDOM_BYTES = 4096
    }
}
