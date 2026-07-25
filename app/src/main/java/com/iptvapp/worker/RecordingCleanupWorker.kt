package com.iptvapp.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.util.RecordingFileUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

// Runs once a day regardless of the configured retention window — the window itself
// (autoDeleteRecordingsDays) is read fresh from PreferencesManager each run, so changing it
// in Settings takes effect on the very next daily pass without re-scheduling this worker.
@HiltWorker
class RecordingCleanupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val days = prefs.autoDeleteRecordingsDays.first()
            if (days <= 0) return Result.success()

            val cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
            val stale = db.recordingDao().getOlderThan(cutoffMs)
            stale.forEach { rec ->
                RecordingFileUtils.deleteFile(appContext, rec.outputPath)
                db.recordingDao().delete(rec)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Recording cleanup failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "recording_cleanup_work"
        private const val TAG = "RecordingCleanupWorker"
    }
}
