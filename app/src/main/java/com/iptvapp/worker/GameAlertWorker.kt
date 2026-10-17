package com.iptvapp.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.util.GameAlerts
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Runs at a favorite team's game start and announces it; see [GameAlerts]. */
@HiltWorker
class GameAlertWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val key = inputData.getString(GameAlerts.KEY_GAME) ?: return Result.success()
        try {
            GameAlerts.announce(applicationContext, db, prefs, key)
        } catch (e: Exception) {
            android.util.Log.w("GameAlertWorker", "announce failed: ${e.message}")
        }
        return Result.success()
    }
}
