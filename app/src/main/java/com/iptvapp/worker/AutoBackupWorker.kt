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
                put("watchHistory", JSONArray(db.channelDao().getWatchHistoryForBackup().map {
                    JSONObject().apply {
                        put("streamId", it.streamId)
                        put("lastWatched", it.lastWatched)
                        put("viewCount", it.viewCount)
                    }
                }))
                // Folder ids are local autoincrement values (not portable across a restore
                // onto a different/reset device), so folders are saved by NAME — same
                // approach SyncManager already uses. Previously omitted entirely, so
                // restoring a backup brought favorites back but dumped every one of them
                // into Unsorted, silently losing folder organization.
                val folders = db.favoriteFolderDao().getAll().first()
                val folderNameById = folders.associate { it.id to it.name }
                val channelFolders = db.channelDao().getFavoriteChannelsBlocking()
                    .mapNotNull { ch -> ch.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { name -> ch.streamId.toString() to name } } }
                    .toMap()
                put("favoriteFolders", JSONArray(folders.map { it.name }))
                put("channelFolders", JSONObject(channelFolders))
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "MKTV_backup_$timestamp.json"
            val body = json.toString(2)

            // This file contains the account's plaintext username/password (needed so a
            // restore can log back in) — it must live in app-private storage, never a public
            // Downloads/MediaStore location that any other app with storage/media
            // permissions could read.
            val dir = File(appContext.getExternalFilesDir(null), "backups").apply { mkdirs() }
            File(dir, fileName).writeText(body)
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
