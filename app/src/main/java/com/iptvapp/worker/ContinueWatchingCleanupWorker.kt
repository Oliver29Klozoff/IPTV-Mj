package com.iptvapp.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

// Mirrors RecordingCleanupWorker's age-based retention pattern, applied to Continue Watching
// instead of recordings — an abandoned in-progress movie/show otherwise sits in the Continue
// Watching row indefinitely with no way to auto-clear it (only one-at-a-time manual dismissal
// existed before this). "Dismiss" here means the same thing manual long-press-to-remove already
// does — clears it from Continue Watching without touching favorites/the catalog row itself, and
// it reappears automatically the moment the user actually resumes it (same as manual dismissal).
@HiltWorker
class ContinueWatchingCleanupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val days = prefs.autoClearContinueWatchingDays.first()
            if (days <= 0) return Result.success()

            val cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
            db.vodDao().dismissStaleContinueWatching(cutoffMs)
            db.seriesDao().dismissStaleContinueWatching(cutoffMs)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Continue Watching cleanup failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "continue_watching_cleanup_work"
        private const val TAG = "ContinueWatchingCleanupWorker"
    }
}
