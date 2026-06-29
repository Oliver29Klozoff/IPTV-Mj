package com.iptvapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val creds = prefs.credentials.first()
            val json = JSONObject().apply {
                put("serverUrl", creds.serverUrl)
                put("username", creds.username)
                put("password", creds.password)
                put("epgUrl", prefs.epgUrl.first())
                put("preferredFormat", prefs.preferredFormat.first())
                put("epgAutoRefreshHours", prefs.epgAutoRefreshHours.first())
                put("epgRefreshMissingOnly", prefs.epgRefreshMissingOnly.first())
                put("usaOnlyChannels", prefs.usaOnlyChannels.first())
                put("showMovies", prefs.showMovies.first())
                put("showSeries", prefs.showSeries.first())
                put("showWatching", prefs.showWatching.first())
                put("favoriteCategoryIds", JSONArray(prefs.favoriteLiveCategoryIds.first().toList()))
                put("favoriteChannelIds", JSONArray(db.channelDao().getFavoriteChannelIds()))
            }

            val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            dir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            File(dir, "MKTV_backup_$timestamp.json").writeText(json.toString(2))

            // Keep only the 5 most recent backups
            dir.listFiles { f -> f.name.startsWith("MKTV_backup_") && f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(5)
                ?.forEach { it.delete() }

            notifyComplete()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun notifyComplete() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Backup", NotificationManager.IMPORTANCE_LOW)
            )
        }
        try {
            nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("MKTV backup saved")
                .setContentText("Your settings and favorites have been backed up.")
                .setAutoCancel(true)
                .build())
        } catch (_: SecurityException) {}
    }

    companion object {
        const val WORK_NAME = "auto_backup_work"
        private const val CHANNEL_ID = "backup_channel"
        private const val NOTIFICATION_ID = 4001
        private const val TAG = "AutoBackupWorker"
    }
}
