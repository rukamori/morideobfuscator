/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.morideobfuscator.ytdlp

import android.os.Looper
import androidx.annotation.Keep
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Keep
object YtDlpJavaScriptRuntime {
    private val dispatcher =
        Executors
            .newFixedThreadPool(2) { runnable ->
                Thread(runnable, "ArchiveTune-QuickJS").apply { isDaemon = true }
            }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @JvmStatic
    fun evaluate(source: String): String {
        check(Looper.myLooper() != Looper.getMainLooper())
        val result = CompletableFuture<String>()
        val job =
            scope.launch {
                var runtime: QuickJs? = null
                try {
                    val quickJs = QuickJs.create(dispatcher)
                    runtime = quickJs
                    quickJs.memoryLimit = JAVASCRIPT_MEMORY_LIMIT_BYTES
                    quickJs.maxStackSize = JAVASCRIPT_STACK_LIMIT_BYTES
                    val wrappedSource =
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
                    result.complete(
                        withTimeout(JAVASCRIPT_TIMEOUT_MS) {
                            quickJs.evaluate<String>(wrappedSource, "yt-dlp-ejs.js")
                        },
                    )
                } catch (throwable: Throwable) {
                    result.completeExceptionally(throwable)
                } finally {
                    runtime?.close()
                }
            }
        return try {
            result.get(JAVASCRIPT_TIMEOUT_MS + COMPLETION_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (throwable: Throwable) {
            job.cancel()
            throw IllegalStateException("JavaScript challenge execution failed", throwable)
        }
    }

    private const val JAVASCRIPT_TIMEOUT_MS = 20_000L
    private const val COMPLETION_GRACE_MS = 2_000L
    private const val JAVASCRIPT_MEMORY_LIMIT_BYTES = 64L * 1024L * 1024L
    private const val JAVASCRIPT_STACK_LIMIT_BYTES = 2L * 1024L * 1024L
}
