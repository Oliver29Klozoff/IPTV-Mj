package com.iptvapp.worker

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.iptvapp.IptvApplication
import com.iptvapp.util.AdBreakDetector
import com.iptvapp.util.CommercialMarkers
import com.iptvapp.util.CommercialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Reads one finished recording for likely commercial breaks. It does not write
 * the recording. Suggestions never replace a confirmed, rejected, or manual marker.
 * Queued when a recording finishes only if automatic analysis is on, and when
 * the viewer asks to analyze one recording.
 */
class AdBreakWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getInt(KEY_ID, -1)
        val uriString = inputData.getString(KEY_URI) ?: return@withContext Result.failure()
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (id < 0) return@withContext Result.failure()
        val mode = CommercialStore.detectionMode(applicationContext)
        if (mode == CommercialMarkers.DetectionMode.OFF) return@withContext Result.success()
        if (!force && mode != CommercialMarkers.DetectionMode.AUTOMATIC) return@withContext Result.success()
        val db = (applicationContext as IptvApplication).db
        val recording = db.recordingDao().getById(id) ?: return@withContext Result.failure()
        if (recording.status != "DONE") return@withContext Result.success()
        val stamp = CommercialStore.stamp(applicationContext, uriString)
        if (!stamp.known()) {
            CommercialStore.putNote(applicationContext, id, note(CommercialStore.UNSUPPORTED, stamp))
            return@withContext Result.success()
        }
        if (!force && CommercialStore.alreadyAnalyzed(applicationContext, id, stamp)) {
            return@withContext Result.success()
        }
        if (CommercialStore.storageTight(applicationContext)) {
            CommercialStore.putNote(applicationContext, id, note(CommercialStore.FAILED, stamp))
            return@withContext Result.failure()
        }
        val previous = CommercialStore.note(applicationContext, id)
        val attempt = System.nanoTime()
        CommercialStore.putNote(applicationContext, id, note(CommercialStore.ANALYZING, stamp).copy(attempt = attempt))
        val breaks = try {
            // The library watches this progress update. The note write alone does not move the WorkInfo.
            setProgress(workDataOf("phase" to CommercialStore.ANALYZING))
            AdBreakDetector.detect(applicationContext, Uri.parse(uriString)) { isStopped }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // The coroutine is already cancelled. Room must still run, or the note stays on Analyzing.
            withContext(NonCancellable) { restore(id, previous, attempt) }
            return@withContext Result.success()
        }
        if (isStopped) {
            withContext(NonCancellable) { restore(id, previous, attempt) }
            return@withContext Result.success()
        }
        if (breaks == null) {
            // A viewer who edited markers while this was reading keeps that note.
            if (CommercialStore.note(applicationContext, id)?.attempt == attempt) {
                CommercialStore.putNote(applicationContext, id, note(CommercialStore.FAILED, stamp))
            }
            return@withContext Result.failure()
        }
        val now = System.currentTimeMillis()
        val ranged = breaks.map {
            CommercialMarkers.Segment(
                startMs = it.first,
                endMs = it.last + 1,
                origin = CommercialMarkers.Origin.AUTOMATIC,
                review = CommercialMarkers.Review.SUGGESTED,
                evidence = "silence_rhythm",
                fileSize = stamp.size,
                fileModifiedMs = stamp.modifiedMs,
                updatedAtMs = now
            )
        }
        val merged = try {
            CommercialStore.mergeAnalysis(applicationContext, id, ranged, stamp) { path ->
                uriForPath(path) == uriString
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Room can be cancelled after detect() returns. The note must not stay on Analyzing.
            withContext(NonCancellable) { restore(id, previous, attempt) }
            return@withContext Result.success()
        }
        if (merged == null) {
            // Deleted, still recording, or replaced by a trim while this read was in progress.
            withContext(NonCancellable) { restore(id, previous, attempt) }
            return@withContext Result.success()
        }
        AdBreakDetector.forget(applicationContext, id)
        try {
            if (db.recordingDao().getById(id) == null) {
                CommercialStore.onRecordingDeleted(applicationContext, id)
                return@withContext Result.success()
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // The rows and note are already stored. Do not put that note back.
            return@withContext Result.success()
        }
        com.iptvapp.IptvApplication.logPlaybackEvent(applicationContext, "AD BREAKS: recordingId=$id found=${ranged.size}")
        Result.success()
    }

    private fun note(status: String, stamp: CommercialMarkers.FileStamp): CommercialStore.AnalysisNote {
        val previous = CommercialStore.note(applicationContext, inputData.getInt(KEY_ID, -1))
        val analyzed = status == CommercialStore.REVIEW || status == CommercialStore.FOUND ||
            status == CommercialStore.NONE || status == CommercialStore.UNSUPPORTED
        return CommercialStore.AnalysisNote(
            status,
            stamp.size,
            stamp.modifiedMs,
            previous?.skipOverride,
            analyzed = analyzed,
            revision = System.currentTimeMillis()
        )
    }

    private suspend fun restore(id: Int, previous: CommercialStore.AnalysisNote?, attempt: Long) {
        val current = CommercialStore.note(applicationContext, id) ?: return
        if (current.attempt != attempt) return
        val rows = if (previous == null || previous.status == CommercialStore.ANALYZING) {
            CommercialStore.load(applicationContext, id)
        } else {
            emptyList()
        }
        val status = CommercialMarkers.statusAfterStoppedAnalysis(previous?.status, CommercialStore.ANALYZING, rows)
        if (status == null) {
            if (current.skipOverride != null) {
                CommercialStore.putNote(
                    applicationContext,
                    id,
                    CommercialStore.AnalysisNote(
                        "",
                        previous?.size ?: current.size,
                        previous?.modifiedMs ?: current.modifiedMs,
                        current.skipOverride,
                        analyzed = false,
                        revision = System.currentTimeMillis()
                    )
                )
            } else {
                CommercialStore.clearNote(applicationContext, id)
            }
            return
        }
        CommercialStore.putNote(
            applicationContext,
            id,
            CommercialStore.AnalysisNote(
                status,
                previous?.size ?: current.size,
                previous?.modifiedMs ?: current.modifiedMs,
                current.skipOverride,
                analyzed = previous?.analyzed == true && previous.status != CommercialStore.ANALYZING,
                revision = System.currentTimeMillis()
            )
        )
    }

    companion object {
        /** A recording's stored path as something MediaExtractor can open from the app itself. */
        fun uriForPath(path: String): String =
            if (path.startsWith("content://")) path else Uri.fromFile(java.io.File(path)).toString()

        const val ANALYSIS_TAG = "commercial-analysis"
        private const val KEY_ID = "recording_id"
        private const val KEY_URI = "uri"
        private const val KEY_FORCE = "force"

        /**
         * [force] is a viewer's Analyze request. Without it, work is queued only
         * when automatic analysis is on, and it waits until the device is charging
         * and storage is not low.
         */
        fun enqueue(context: Context, recordingId: Int, uri: String, replace: Boolean = false, force: Boolean = false) {
            val mode = CommercialStore.detectionMode(context)
            if (!force && mode != CommercialMarkers.DetectionMode.AUTOMATIC) return
            val constraints = Constraints.Builder()
                .setRequiresStorageNotLow(true)
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .apply { if (!force) setRequiresCharging(true) }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                CommercialMarkers.analysisWorkName(recordingId),
                if (replace || force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AdBreakWorker>()
                    .setInputData(workDataOf(KEY_ID to recordingId, KEY_URI to uri, KEY_FORCE to force))
                    .setConstraints(constraints)
                    .addTag(ANALYSIS_TAG)
                    .build()
            )
        }
    }
}
