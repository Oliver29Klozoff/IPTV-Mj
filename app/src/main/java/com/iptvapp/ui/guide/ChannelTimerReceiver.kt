package com.iptvapp.ui.guide

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.iptvapp.R
import com.iptvapp.data.local.dataStore
import com.iptvapp.ui.home.HomeActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ChannelTimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val channelName = intent.getStringExtra("channel_name") ?: return
        val programTitle = intent.getStringExtra("program_title") ?: return
        val streamId = intent.getIntExtra("stream_id", -1)
        val leadMinutes = intent.getIntExtra("lead_minutes", 0)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val tapIntent = Intent(context, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_stream_id", streamId)
        }
        val tapPi = PendingIntent.getActivity(
            context, streamId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (leadMinutes > 0) "$programTitle starts in $leadMinutes min" else "$programTitle is starting now"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(channelName)
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(streamId, notification)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Channel Reminders", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_ID = "channel_timers"
    }
}

object ChannelTimerScheduler {

    // Fires the notification `reminderLeadMinutes` before the program's actual start time
    // (default 5 min, configurable in Settings) instead of exactly at startMs — previously the
    // alarm was set for startMs itself, so the notification read "X is starting now" at the exact
    // moment the show had already begun, giving zero time to actually switch over. Reading the
    // preference here (rather than at each of the 4 call sites) keeps this a one-line change for
    // GuideAdapter/EpgTimelineActivity/HomeActivity/TvHomeActivity's existing "Remind Me" calls.
    fun schedule(context: Context, streamId: Int, channelName: String, programTitle: String, startMs: Long) {
        val leadMinutes = runBlocking {
            context.dataStore.data.first()[com.iptvapp.data.local.REMINDER_LEAD_MINUTES_KEY] ?: 5
        }
        val fireAtMs = (startMs - leadMinutes * 60_000L).coerceAtLeast(System.currentTimeMillis() + 1000L)
        val intent = Intent(context, ChannelTimerReceiver::class.java).apply {
            putExtra("stream_id", streamId)
            putExtra("channel_name", channelName)
            putExtra("program_title", programTitle)
            putExtra("lead_minutes", if (fireAtMs < startMs) leadMinutes else 0)
        }
        val pi = PendingIntent.getBroadcast(
            context, streamId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.set(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pi)
        }
    }

    fun cancel(context: Context, streamId: Int) {
        val intent = Intent(context, ChannelTimerReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, streamId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
    }
}
