/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.morideobfuscator.ytdlp

enum class YtDlpRuntimeStatus {
    READY,
    CHECKING,
    RESTART_REQUIRED,
    FAILED,
}

data class YtDlpRuntimeSnapshot(
    val status: YtDlpRuntimeStatus,
    val activeVersion: String,
    val bundledVersion: String,
    val pendingVersion: String?,
    val lastCheckedAtMillis: Long?,
    val lastUpdatedAtMillis: Long?,
    val lastFailure: String?,
)

data class YtDlpUpdateResult(
    val installedVersion: String?,
    val checkedAtMillis: Long,
)

const val YT_DLP_UPDATE_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
