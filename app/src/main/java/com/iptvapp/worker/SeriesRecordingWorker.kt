package com.iptvapp.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.iptvapp.service.SeriesRecordingEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Reconciles guide rows into the existing RecordingAlarms scheduler; never captures media. */
@HiltWorker
class SeriesRecordingWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val engine: SeriesRecordingEngine
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        engine.refresh()
        engine.applyRetention()
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }

    companion object {
        fun refresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("series_recording_scan", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SeriesRecordingWorker>().build())
        }
        fun register(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("series_recording_reconcile",
                ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<SeriesRecordingWorker>(30, TimeUnit.MINUTES).build())
            refresh(context)
        }
    }
}
