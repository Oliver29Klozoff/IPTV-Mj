package com.iptvapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.iptvapp.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val channelName = inputData.getString(KEY_CHANNEL_NAME) ?: "Unknown Channel"
        val programTitle = inputData.getString(KEY_PROGRAM_TITLE) ?: "Program starting soon"
        val streamId = inputData.getInt(KEY_STREAM_ID, 0)

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EPG Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Alerts for upcoming TV programs" }
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Starting soon on $channelName")
            .setContentText(programTitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "\"$programTitle\" starts in about 5 minutes on $channelName"
            ))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(NOTIFICATION_BASE_ID + streamId, notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "epg_reminders"
        const val KEY_CHANNEL_NAME = "channel_name"
        const val KEY_PROGRAM_TITLE = "program_title"
        const val KEY_STREAM_ID = "stream_id"
        private const val NOTIFICATION_BASE_ID = 9000

        fun workTag(streamId: Int, startTimestamp: Long) = "reminder_${streamId}_$startTimestamp"
    }
}
