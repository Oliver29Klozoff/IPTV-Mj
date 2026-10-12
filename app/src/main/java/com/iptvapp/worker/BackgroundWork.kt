package com.iptvapp.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * The one place MKTV's settings-driven periodic work is scheduled: automatic guide refresh,
 * recording clean-up and Continue Watching clean-up. Each used to be scheduled by hand from two to
 * four screens with diverging details (one copy hard-coded the guide worker to "missing only").
 *
 * Every call is idempotent. [ExistingPeriodicWorkPolicy.UPDATE] keeps a job's place in its period
 * when nothing changed and swaps in a new interval or input when something did, so app start can
 * call [syncAll] every time without resetting timers or stacking duplicate jobs. A value of 0 (off)
 * cancels the job.
 */
object BackgroundWork {
    const val AUTO_EPG_WORK_NAME = "auto_epg_refresh_work"

    /** App start: bring every job in line with the saved settings (re-arms anything an update cleared). */
    suspend fun syncAll(context: Context, prefs: PreferencesManager) {
        applyAutoEpgRefresh(context, prefs.epgAutoRefreshHours.first())
        applyRecordingCleanup(context, prefs.autoDeleteRecordingsDays.first())
        applyContinueWatchingCleanup(context, prefs.autoClearContinueWatchingDays.first())
    }

    /** Automatic guide refresh every [hours] (0 = off). Whether it refreshes only channels missing
     * guide data is read from the setting each time it runs, so changing that setting needs no
     * reschedule. */
    fun applyAutoEpgRefresh(context: Context, hours: Int) {
        val wm = WorkManager.getInstance(context)
        if (hours <= 0) {
            wm.cancelUniqueWork(AUTO_EPG_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setInputData(workDataOf(EpgRefreshWorker.KEY_USE_SAVED_SETTING to true))
            .build()
        wm.enqueueUniquePeriodicWork(AUTO_EPG_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Daily recording clean-up while auto-delete is set ([days] > 0). */
    fun applyRecordingCleanup(context: Context, days: Int) =
        daily<RecordingCleanupWorker>(context, RecordingCleanupWorker.WORK_NAME, enabled = days > 0)

    /** Daily Continue Watching clean-up while auto-clear is set ([days] > 0). */
    fun applyContinueWatchingCleanup(context: Context, days: Int) =
        daily<ContinueWatchingCleanupWorker>(context, ContinueWatchingCleanupWorker.WORK_NAME, enabled = days > 0)

    private inline fun <reified W : androidx.work.ListenableWorker> daily(context: Context, name: String, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(name)
            return
        }
        wm.enqueueUniquePeriodicWork(
            name, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<W>(1, TimeUnit.DAYS).build()
        )
    }
}
