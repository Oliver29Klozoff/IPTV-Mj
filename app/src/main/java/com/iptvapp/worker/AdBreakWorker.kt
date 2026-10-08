package com.iptvapp.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.iptvapp.util.AdBreakDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds the commercial breaks in one finished recording (util/AdBreakDetector) in the
 * background, so the player can offer Skip break. Queued when a recording finishes, and for an
 * older recording the first time it's played. */
class AdBreakWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getInt(KEY_ID, -1)
        val uriString = inputData.getString(KEY_URI) ?: return@withContext Result.failure()
        if (id < 0) return@withContext Result.failure()
        // Stops (and saves nothing) if WorkManager stops this worker partway.
        val breaks = AdBreakDetector.detect(applicationContext, android.net.Uri.parse(uriString)) { isStopped }
        if (isStopped) return@withContext Result.retry()
        // Couldn't read the audio: save nothing, so the next playback tries again.
        if (breaks == null) return@withContext Result.failure()
        // Saved even when empty, so a recording with no breaks found isn't analyzed again.
        AdBreakDetector.save(applicationContext, id, breaks)
        com.iptvapp.IptvApplication.logPlaybackEvent(applicationContext, "AD BREAKS: recordingId=$id found=${breaks.size}")
        Result.success()
    }

    companion object {
        /** A recording's stored path as something MediaExtractor can open from the app itself. */
        fun uriForPath(path: String): String =
            if (path.startsWith("content://")) path else android.net.Uri.fromFile(java.io.File(path)).toString()

        private const val KEY_ID = "recording_id"
        private const val KEY_URI = "uri"

        /** [uri]: content:// (MediaStore, FileProvider) or file:// — see uriForPath. */
        // [replace]: the file changed (trimmed) — cancel an analysis of the old one instead of keeping it.
        fun enqueue(context: Context, recordingId: Int, uri: String, replace: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "ad-breaks-$recordingId",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AdBreakWorker>()
                    .setInputData(workDataOf(KEY_ID to recordingId, KEY_URI to uri))
                    .build()
            )
        }
    }
}
