package com.iptvapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.util.Resource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import androidx.work.ForegroundInfo
import java.io.IOException

@HiltWorker
class EpgRefreshWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: XtreamRepository,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = androidx.core.app.NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Refreshing EPG")
            .setContentText("Starting...")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        createNotificationChannel()

        val missingOnly = inputData.getBoolean(KEY_MISSING_ONLY, false)
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        return try {
            db.epgDao().deleteExpiredEpg()

            setProgress(
                Data.Builder()
                    .putInt(KEY_PROGRESS, 0)
                    .putString(KEY_STATUS, "Loading channel list...")
                    .build()
            )

            val allChannels = db.channelDao().getFavoriteChannels().first()

            val channelsToRefresh = if (missingOnly) {
                val idsWithEpg = db.epgDao().getStreamIdsWithEpg().toSet()
                allChannels.filter { it.streamId !in idsWithEpg }
            } else {
                allChannels
            }

            val total = channelsToRefresh.size

            if (total == 0) {
                updateProgress(notificationManager, 100, "EPG already up to date")
                prefs.setLastEpgRefreshTime(System.currentTimeMillis())
                return Result.success(
                    Data.Builder()
                        .putInt(KEY_PROGRESS, 100)
                        .putString(KEY_STATUS, "EPG already up to date")
                        .build()
                )
            }

            channelsToRefresh.forEachIndexed { index, channel ->
                if (isStopped) {
                    updateProgress(notificationManager, 0, "EPG refresh canceled")
                    return Result.failure()
                }

                val beforePercent = (index * 100) / total
                val beforeStatus = "Refreshing ${channel.name}"

                setProgress(
                    Data.Builder()
                        .putInt(KEY_PROGRESS, beforePercent)
                        .putString(KEY_STATUS, beforeStatus)
                        .build()
                )

                val result = repository.fetchEpg(channel.streamId)
                if (result is Resource.Error) {
                    val msg = result.message ?: "Unknown error"
                    Log.w("EpgRefreshWorker", "Skipping ${channel.name}: $msg")
                }

                val done = index + 1
                val percent = (done * 100) / total
                val status = "Refreshing EPG $done of $total ($percent%)"

                setProgress(
                    Data.Builder()
                        .putInt(KEY_PROGRESS, percent)
                        .putString(KEY_STATUS, status)
                        .build()
                )

                updateProgress(notificationManager, percent, status)
            }

            prefs.setLastEpgRefreshTime(System.currentTimeMillis())

            setProgress(
                Data.Builder()
                    .putInt(KEY_PROGRESS, 100)
                    .putString(KEY_STATUS, "EPG refresh complete")
                    .build()
            )

            updateProgress(notificationManager, 100, "EPG refresh complete")

            Result.success(
                Data.Builder()
                    .putInt(KEY_PROGRESS, 100)
                    .putString(KEY_STATUS, "EPG refresh complete")
                    .build()
            )
        } catch (e: IOException) {
            val errorText = "EPG network error: ${e.message ?: "Unknown"}"
            Log.w("EpgRefreshWorker", errorText, e)
            updateProgress(notificationManager, 0, errorText)
            Result.retry()
        } catch (e: Exception) {
            val errorText = "EPG refresh failed: ${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}"
            Log.e("EpgRefreshWorker", errorText, e)

            setProgress(
                Data.Builder()
                    .putInt(KEY_PROGRESS, 0)
                    .putString(KEY_STATUS, errorText)
                    .build()
            )

            updateProgress(notificationManager, 0, errorText)

            Result.failure(
                Data.Builder()
                    .putInt(KEY_PROGRESS, 0)
                    .putString(KEY_STATUS, errorText)
                    .build()
            )
        }
    }

    private fun updateProgress(
        notificationManager: NotificationManager,
        progress: Int,
        status: String
    ) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Refreshing EPG")
            .setContentText(status)
            .setOnlyAlertOnce(true)
            .setOngoing(progress in 1..99)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ notification permission may be denied.
        } catch (_: Exception) {
            // Keep worker running even if notification display fails.
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EPG Refresh",
                NotificationManager.IMPORTANCE_LOW
            )

            val notificationManager =
                appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "epg_refresh_work"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_MISSING_ONLY = "missing_only"

        private const val CHANNEL_ID = "epg_refresh_channel"
        private const val NOTIFICATION_ID = 3001
    }
}