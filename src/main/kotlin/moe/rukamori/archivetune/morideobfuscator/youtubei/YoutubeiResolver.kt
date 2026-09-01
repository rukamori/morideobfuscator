/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.morideobfuscator.youtubei

import android.content.ComponentCallbacks2
import android.content.Context
import android.net.Uri
import com.dokar.quickjs.QuickJsInterruptedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

class YoutubeiResolver(
    context: Context,
    networkConfigurationProvider: () -> YoutubeiNetworkConfiguration,
) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = YoutubeiHttpClient(networkConfigurationProvider)
    private val diskCache = YoutubeiDiskCache(context.applicationContext)
    private val worker =
        YoutubeiQuickJsWorker(
            context = context,
            name = "Primary",
            httpClient = httpClient,
            diskCache = diskCache,
        )
    private val backgroundPermit = Semaphore(1)

    suspend fun preWarm() {
        worker.preWarm()
    }

    suspend fun resolve(
        request: YoutubeiStreamRequest,
        priority: YoutubeiResolutionPriority,
        videoPoTokenProvider: suspend (String) -> String? = { null },
    ): YoutubeiResolvedStream =
        when (priority) {
            YoutubeiResolutionPriority.FOREGROUND ->
                resolveWithWorker(
                    request = request,
                    videoPoTokenProvider = videoPoTokenProvider,
                )
            YoutubeiResolutionPriority.BACKGROUND ->
                backgroundPermit.withPermit {
                    resolveWithWorker(
                        request = request,
                        videoPoTokenProvider = videoPoTokenProvider,
                    )
                }
        }

    suspend fun invalidateSessions() {
        worker.closeRuntime()
    }

    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            applicationScope.launch { invalidateSessions() }
        }
    }

    private suspend fun resolveWithWorker(
        request: YoutubeiStreamRequest,
        videoPoTokenProvider: suspend (String) -> String?,
    ): YoutubeiResolvedStream {
        val requestJson = request.toJson().toString()
        val response =
            try {
                withTimeout(RESOLUTION_TIMEOUT_MS) {
                    worker.resolve(
                        requestJson = requestJson,
                        videoPoTokenProvider = videoPoTokenProvider,
                    )
                }
            } catch (timeout: TimeoutCancellationException) {
                throw YoutubeiException(
                    kind = YoutubeiFailureKind.TIMEOUT,
                    message = "youtubei.js resolution timed out",
                    cause = timeout,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (timeout: QuickJsInterruptedException) {
                throw YoutubeiException(
                    kind = YoutubeiFailureKind.TIMEOUT,
                    message = "youtubei.js execution timed out",
                    cause = timeout,
                )
            } catch (timeout: SocketTimeoutException) {
                throw YoutubeiException(
                    kind = YoutubeiFailureKind.TIMEOUT,
                    message = timeout.message ?: "youtubei.js network request timed out",
                    cause = timeout,
                )
            } catch (network: IOException) {
                throw YoutubeiException(
                    kind = YoutubeiFailureKind.NETWORK,
                    message = network.message ?: "youtubei.js network request failed",
                    cause = network,
                )
            } catch (throwable: Throwable) {
                throw YoutubeiException(
                    kind = YoutubeiFailureKind.INTERNAL,
                    message = throwable.message ?: "youtubei.js execution failed",
                    cause = throwable,
                )
            }
        return parseResponse(response)
    }

    private fun parseResponse(response: String): YoutubeiResolvedStream {
        val json =
            runCatching { JSONObject(response) }.getOrElse { throwable ->
                throw YoutubeiException(
                    kind = YoutubeiFailureKind.INVALID_RESPONSE,
                    message = "youtubei.js returned malformed JSON",
                    cause = throwable,
                )
            }
        if (!json.optBoolean("ok")) {
            val error = json.optJSONObject("error")
            val kind =
                runCatching {
                    YoutubeiFailureKind.valueOf(error?.optString("kind").orEmpty())
                }.getOrDefault(YoutubeiFailureKind.INTERNAL)
            throw YoutubeiException(
                kind = kind,
                message = error?.optString("message")?.takeIf(String::isNotBlank)
                    ?: "youtubei.js resolution failed",
                httpStatus = error?.optInt("httpStatus")?.takeIf { it > 0 },
            )
        }
        val value = json.optJSONObject("value")
            ?: throw YoutubeiException(
                kind = YoutubeiFailureKind.INVALID_RESPONSE,
                message = "youtubei.js returned no stream payload",
            )
        val url = value.optString("url").trim()
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme !in HTTP_SCHEMES) {
            throw YoutubeiException(
                kind = YoutubeiFailureKind.INVALID_RESPONSE,
                message = "youtubei.js returned an unsupported stream URL",
            )
        }
        val headers =
            buildMap {
                value.optJSONObject("headers")?.let { values ->
                    values.keys().forEach { name ->
                        val headerValue = values.optString(name)
                        if (isSafeHeader(name, headerValue)) put(name, headerValue)
                    }
                }
            }
        val now = System.currentTimeMillis()
        return YoutubeiResolvedStream(
            url = url,
            requestHeaders = headers,
            formatId = value.optInt("formatId", -1),
            mimeType = value.optString("mimeType", "audio/webm"),
            codecs = value.optString("codecs"),
            bitrate = value.optInt("bitrate").coerceAtLeast(0),
            sampleRate = value.optInt("sampleRate").takeIf { it > 0 },
            contentLength = value.optLong("contentLength").coerceAtLeast(0L),
            expiresAtMs = value.optLong("expiresAtMs").takeIf { it > now } ?: now + DEFAULT_STREAM_LIFETIME_MS,
            runtimeVersion = value.optString("runtimeVersion", YOUTUBEI_VERSION),
            title = value.optionalString("title"),
            durationSeconds = value.optInt("durationSeconds").takeIf { it > 0 },
            thumbnailUrl = value.optionalString("thumbnailUrl"),
            loudnessDb = value.optionalDouble("loudnessDb"),
            perceptualLoudnessDb = value.optionalDouble("perceptualLoudnessDb"),
            playbackTrackingUrl = value.optionalString("playbackTrackingUrl"),
        )
    }

    private fun YoutubeiStreamRequest.toJson(): JSONObject =
        JSONObject()
            .put("mediaId", mediaId)
            .put("quality", quality.name)
            .put("networkMetered", networkMetered)
            .put("authFingerprint", authFingerprint)
            .put("pinnedItag", pinnedItag)
            .put("requiresSongMetadata", requiresSongMetadata)
            .put("cookie", cookie)
            .put("visitorData", visitorData)
            .put("dataSyncId", dataSyncId)
            .put("sessionPoToken", sessionPoToken)
            .put("videoPoToken", videoPoToken)
            .put("language", language)
            .put("location", location)
            .put("timezone", timezone)

    private fun isSafeHeader(
        name: String,
        value: String,
    ): Boolean =
        name.isNotBlank() &&
            value.isNotBlank() &&
            name.none { it == '\r' || it == '\n' } &&
            value.none { it == '\r' || it == '\n' }

    private fun JSONObject.optionalString(name: String): String? =
        takeUnless { isNull(name) }
            ?.optString(name)
            ?.takeIf(String::isNotBlank)

    private fun JSONObject.optionalDouble(name: String): Double? =
        takeUnless { isNull(name) }
            ?.optDouble(name, Double.NaN)
            ?.takeIf(Double::isFinite)

    private companion object {
        const val YOUTUBEI_VERSION = "18.0.0"
        const val RESOLUTION_TIMEOUT_MS = 35_000L
        const val DEFAULT_STREAM_LIFETIME_MS = 5L * 60L * 1000L
        val HTTP_SCHEMES = setOf("http", "https")
    }
}
