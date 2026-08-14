package com.iptvapp.ui.player

import android.content.Context
import android.widget.Toast
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.util.RecordingFileUtils
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Feature C: Bandwidth Budget Mode — WARN-ONLY, never touches playback quality/bitrate.
 *
 * Per-provider (serverIndex, -1 = primary), matching BandwidthUsageEntity's own per-provider,
 * per-month granularity — each configured provider gets its own optional cap in GB, set via
 * SettingsActivity/TvSettingsActivity's "Monthly Data Cap" dialog (per-provider picker) and
 * stored under PreferencesManager's `bandwidth_cap_gb_<serverIndex>` key scheme (see
 * Keys.bandwidthCapGbKey).
 *
 * Checked from BandwidthTracker.flush() (see PlayerActivity's call site) so it re-evaluates
 * roughly every ~7s of active playback for whichever provider is currently streaming — enough
 * for a warn-only feature; no separate poll loop is needed.
 *
 * Warn-once-per-threshold: PreferencesManager.hasWarnedThreshold/markWarnedThreshold key off
 * "bandwidth_warned_<80|100>_<serverIndex>_<yyyy-MM>" so crossing 80% warns exactly once per
 * provider, then crossing 100% warns exactly once more — re-checking after either point (e.g.
 * the next flush 7s later) does NOT re-toast. A new calendar month gets a fresh key automatically
 * since yearMonth is part of the key.
 */
class BandwidthBudgetManager(
    private val db: IptvDatabase,
    private val prefs: PreferencesManager
) {
    companion object {
        private const val WARN_THRESHOLD_PERCENT = 80
        private const val OVER_THRESHOLD_PERCENT = 100
    }

    private fun currentYearMonth(): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    /** Feature A calls this before starting a focus-preview for the given provider, and skips
     * the autoplay (shows the static poster only) when true. */
    suspend fun isNearOrOverBudget(serverIndex: Int): Boolean {
        val capGb = prefs.getBandwidthCapGb(serverIndex)
        if (capGb <= 0) return false // no cap configured for this provider
        val yearMonth = currentYearMonth()
        val usedBytes = db.bandwidthUsageDao().getUsageForMonth(yearMonth)
            .firstOrNull { it.serverIndex == serverIndex }?.bytesTransferred ?: 0L
        val capBytes = capGb.toLong() * 1024L * 1024L * 1024L
        if (capBytes <= 0) return false
        val percent = (usedBytes * 100L / capBytes).toInt()
        return percent >= WARN_THRESHOLD_PERCENT
    }

    /** Call after each BandwidthTracker flush for that provider (or once per app session at
     * minimum). Toasts at most once per threshold per provider per month — see class kdoc.
     * No-op if no cap is set for this provider. */
    suspend fun checkAndWarn(context: Context, serverIndex: Int) {
        val capGb = prefs.getBandwidthCapGb(serverIndex)
        if (capGb <= 0) return
        val yearMonth = currentYearMonth()
        val usedBytes = db.bandwidthUsageDao().getUsageForMonth(yearMonth)
            .firstOrNull { it.serverIndex == serverIndex }?.bytesTransferred ?: 0L
        val capBytes = capGb.toLong() * 1024L * 1024L * 1024L
        if (capBytes <= 0) return
        val percent = (usedBytes * 100L / capBytes).toInt()

        if (percent >= OVER_THRESHOLD_PERCENT && !prefs.hasWarnedThreshold(serverIndex, yearMonth, OVER_THRESHOLD_PERCENT)) {
            prefs.markWarnedThreshold(serverIndex, yearMonth, OVER_THRESHOLD_PERCENT)
            toast(context, "This provider has exceeded its ${capGb}GB monthly data budget (${RecordingFileUtils.formatBytes(usedBytes)} used).")
        } else if (percent >= WARN_THRESHOLD_PERCENT && !prefs.hasWarnedThreshold(serverIndex, yearMonth, WARN_THRESHOLD_PERCENT)) {
            prefs.markWarnedThreshold(serverIndex, yearMonth, WARN_THRESHOLD_PERCENT)
            toast(context, "This provider has used ${percent}% of its ${capGb}GB monthly data budget.")
        }
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
