/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.morideobfuscator.ytdlp

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.TimeUnit

class YtDlpUpdateWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        try {
            YtDlpRuntimeUpdater(applicationContext).update()
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Timber.tag(TAG).w(throwable, "Signed yt-dlp runtime update failed")
            Result.retry()
        }

    private companion object {
        const val TAG = "YtDlpUpdate"
    }
}

object YtDlpUpdateScheduler {
    private const val PERIODIC_WORK_NAME = "yt_dlp_stable_update"
    private const val INITIAL_WORK_NAME = "yt_dlp_initial_update"
    private const val LEGACY_PERIODIC_WORK_NAME = "mori_cipher_player_refresh"
    private const val LEGACY_INITIAL_WORK_NAME = "mori_cipher_player_initial_refresh"

    fun schedule(context: Context) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(LEGACY_INITIAL_WORK_NAME)
        workManager.enqueueUniqueWork(
            INITIAL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<YtDlpUpdateWorker>()
                .setConstraints(constraints)
                .build(),
        )
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<YtDlpUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build(),
        )
    }
}
