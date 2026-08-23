/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.morideobfuscator.ytdlp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.annotation.Keep
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Keep
object YtDlpJavaScriptRuntime {
    @Volatile
    private var appContext: Context? = null

    private val dispatcher =
        Executors
            .newFixedThreadPool(2) { runnable ->
                Thread(runnable, "ArchiveTune-QuickJS").apply { isDaemon = true }
            }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun evaluate(source: String): String {
        check(Looper.myLooper() != Looper.getMainLooper())
        val quickJsFailure =
            try {
                return validateOutput(evaluateWithQuickJs(source), "QuickJS")
            } catch (error: Exception) {
                error
            }

        return try {
            validateOutput(evaluateWithWebView(source), "WebView")
        } catch (webViewFailure: Exception) {
            throw IllegalStateException(
                "JavaScript challenge execution failed in QuickJS and WebView",
                webViewFailure,
            ).apply {
                addSuppressed(quickJsFailure)
            }
        }
    }

    private fun evaluateWithQuickJs(source: String): String {
        val result = CompletableFuture<String>()
        val job =
            scope.launch {
                var runtime: QuickJs? = null
                try {
                    val quickJs = QuickJs.create(dispatcher)
                    runtime = quickJs
                    quickJs.memoryLimit = JAVASCRIPT_MEMORY_LIMIT_BYTES
                    quickJs.maxStackSize = JAVASCRIPT_STACK_LIMIT_BYTES
                    quickJs.evaluationTimeoutMillis = JAVASCRIPT_TIMEOUT_MS
                    result.complete(
                        quickJs.evaluate<String>(wrapSource(source), "yt-dlp-ejs.js"),
                    )
                } catch (throwable: Throwable) {
                    result.completeExceptionally(throwable)
                } finally {
                    runtime?.close()
                }
            }
        return try {
            result.get(JAVASCRIPT_TIMEOUT_MS + COMPLETION_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            job.cancel()
            Thread.currentThread().interrupt()
            throw IllegalStateException("QuickJS challenge execution interrupted", interrupted)
        } catch (throwable: Throwable) {
            job.cancel()
            val cause = throwable.cause ?: throwable
            val detail = cause.message ?: cause.javaClass.simpleName
            throw IllegalStateException("QuickJS challenge execution failed: $detail", cause)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun evaluateWithWebView(source: String): String {
        val context =
            appContext
                ?: throw IllegalStateException("YtDlpJavaScriptRuntime.initialize() was not called")
        val result = CompletableFuture<String>()
        val webViewReference = AtomicReference<WebView?>()
        val mainHandler = Handler(Looper.getMainLooper())
        val posted =
            mainHandler.post {
                if (result.isDone) {
                    return@post
                }
                try {
                    val webView = WebView(context)
                    webViewReference.set(webView)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.blockNetworkLoads = true
                    webView.evaluateJavascript(wrapSource(source)) { encodedResult ->
                        try {
                            result.complete(decodeWebViewResult(encodedResult))
                        } catch (error: Exception) {
                            result.completeExceptionally(error)
                        } finally {
                            webViewReference.getAndSet(null)?.destroy()
                        }
                    }
                } catch (error: Throwable) {
                    webViewReference.getAndSet(null)?.destroy()
                    result.completeExceptionally(error)
                }
            }
        if (!posted) {
            throw IllegalStateException("Unable to schedule WebView challenge execution")
        }

        return try {
            result.get(JAVASCRIPT_TIMEOUT_MS + COMPLETION_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            result.completeExceptionally(interrupted)
            mainHandler.post {
                webViewReference.getAndSet(null)?.destroy()
            }
            Thread.currentThread().interrupt()
            throw IllegalStateException("WebView challenge execution interrupted", interrupted)
        } catch (throwable: Throwable) {
            result.completeExceptionally(throwable)
            mainHandler.post {
                webViewReference.getAndSet(null)?.destroy()
            }
            val cause = throwable.cause ?: throwable
            val detail = cause.message ?: cause.javaClass.simpleName
            throw IllegalStateException("WebView challenge execution failed: $detail", cause)
        }
    }

    private fun wrapSource(source: String): String =
        """
        let __archiveTuneOutput = "";
        globalThis.console = {
            log: (...values) => { __archiveTuneOutput = values.join(" "); },
            debug: () => {},
            info: () => {},
            warn: () => {},
            error: () => {}
        };
        $source
        __archiveTuneOutput;
        """.trimIndent()

    private fun decodeWebViewResult(encodedResult: String?): String {
        val parsed =
            encodedResult
                ?.let { JSONTokener(it).nextValue() }
                ?: throw IllegalStateException("WebView returned no JavaScript result")
        return parsed as? String
            ?: throw IllegalStateException("WebView returned a non-string JavaScript result")
    }

    private fun validateOutput(
        output: String,
        runtimeName: String,
    ): String {
        val normalized = output.trim()
        if (normalized.isEmpty()) {
            throw IllegalStateException("$runtimeName returned an empty JavaScript result")
        }
        val parsed =
            try {
                JSONTokener(normalized).nextValue()
            } catch (error: Exception) {
                throw IllegalStateException("$runtimeName returned malformed JSON", error)
            }
        if (parsed !is JSONObject) {
            throw IllegalStateException("$runtimeName returned a non-object JSON result")
        }
        return normalized
    }

    private const val JAVASCRIPT_TIMEOUT_MS = 60_000L
    private const val COMPLETION_GRACE_MS = 2_000L
    private const val JAVASCRIPT_MEMORY_LIMIT_BYTES = 128L * 1024L * 1024L
    private const val JAVASCRIPT_STACK_LIMIT_BYTES = 2L * 1024L * 1024L
}
