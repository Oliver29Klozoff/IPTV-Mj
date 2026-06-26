package com.iptvapp.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.service.RecordingService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RecordingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val database: IptvDatabase,
    private val repository: XtreamRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
        const val KEY_STREAM_ID = "stream_id"
        const val KEY_CHANNEL_NAME = "channel_name"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_OUTPUT_PATH = "output_path"
    }

    override suspend fun doWork(): Result {
        val recordingId = inputData.getInt(KEY_RECORDING_ID, -1)
        val streamId = inputData.getInt(KEY_STREAM_ID, -1)
        val channelName = inputData.getString(KEY_CHANNEL_NAME) ?: return Result.failure()
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0L)
        val outputPath = inputData.getString(KEY_OUTPUT_PATH) ?: return Result.failure()

        val streamUrl = try {
            repository.getLiveStreamUrl(streamId)
        } catch (e: Exception) {
            if (recordingId != -1) database.recordingDao().updateStatus(recordingId, "FAILED")
            return Result.failure()
        }

        val intent = Intent(applicationContext, RecordingService::class.java).apply {
            putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
            putExtra(RecordingService.EXTRA_STREAM_URL, streamUrl)
            putExtra(RecordingService.EXTRA_CHANNEL_NAME, channelName)
            putExtra(RecordingService.EXTRA_DURATION_MS, durationMs)
            putExtra(RecordingService.EXTRA_OUTPUT_PATH, outputPath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
        return Result.success()
    }
}
