package com.iptvapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "MKTV_backup_$timestamp.json"
            val body = json.toString(2)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToDownloadsMediaStore(fileName, body)
                pruneOldBackupsMediaStore()
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MKTV")
                dir.mkdirs()
                File(dir, fileName).writeText(body)
                dir.listFiles { f -> f.name.startsWith("MKTV_backup_") && f.name.endsWith(".json") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(5)
                    ?.forEach { it.delete() }
            }

            notifyComplete()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun saveToDownloadsMediaStore(fileName: String, body: String) {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MKTV")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed for $fileName")
        resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
            ?: throw IllegalStateException("Could not open output stream for $fileName")
    }

    private fun pruneOldBackupsMediaStore() {
        val resolver = appContext.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("MKTV_backup_%.json")
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"

        val ids = mutableListOf<Long>()
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathCol) ?: ""
                if (path.contains("MKTV")) ids.add(cursor.getLong(idCol))
            }
        }
        ids.drop(5).forEach { id ->
            resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null)
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
