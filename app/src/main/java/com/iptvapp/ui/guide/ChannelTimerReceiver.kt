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
import com.iptvapp.ui.home.TvHomeActivity
import com.iptvapp.util.isLargeScreenDevice
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

        // Phone home, or the Shield/car-box home. SINGLE_TOP delivers the channel to the
        // screen that's already open instead of leaving it on whatever was playing.
        val home = if (context.isLargeScreenDevice()) TvHomeActivity::class.java else HomeActivity::class.java
        val tapIntent = Intent(context, home).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (streamId >= 0) putExtra(HomeActivity.EXTRA_JUMP_TO_STREAM_ID, streamId)
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

    // The alarm itself can't be queried, so each scheduled reminder's start time is also kept here
    // (one per channel, same as the alarm — its PendingIntent is keyed by streamId) for the guide's
    // bell icon and "Reminder set" state (v6.91).
    private const val REMINDERS = "channel_reminders"

    // Stored as "startMs|bootCount": a reboot clears every alarm, so an entry written in an earlier boot
    // no longer counts as scheduled. Android's boot counter, not a clock-derived boot time, so a
    // clock correction doesn't make a live reminder look unset.
    private fun bootCount(context: Context) =
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)

    fun isScheduled(context: Context, streamId: Int, startMs: Long): Boolean {
        if (startMs <= System.currentTimeMillis()) return false
        // A test build stored a Long here; getString on it throws, so treat that as "not scheduled".
        val v = try {
            context.getSharedPreferences(REMINDERS, Context.MODE_PRIVATE).getString(streamId.toString(), null)
        } catch (_: ClassCastException) { null } ?: return false
        val parts = v.split("|")
        val boot = parts.getOrNull(1)?.toLongOrNull() ?: return false
        return parts[0].toLongOrNull() == startMs && boot == bootCount(context).toLong()
    }

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
        context.getSharedPreferences(REMINDERS, Context.MODE_PRIVATE).edit().putString(streamId.toString(), "$startMs|${bootCount(context)}").apply()
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
        context.getSharedPreferences(REMINDERS, Context.MODE_PRIVATE).edit().remove(streamId.toString()).apply()
    }
}
