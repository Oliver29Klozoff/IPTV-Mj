package com.iptvapp.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iptvapp.sync.SyncManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: SyncManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val result = syncManager.syncUp()
        return if (result.startsWith("Sync failed")) {
            Log.e(TAG, result)
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "auto_cloud_sync_work"
        private const val TAG = "SyncWorker"
    }
}
