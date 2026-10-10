package com.iptvapp.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.iptvapp.ui.recordings.RecordingAlarmReceiver

/**
 * What a recording row needs right now. One rule for every path that looks at a schedule:
 * the alarm firing, recovery after a reboot / app update / force-stop, and Start now.
 * Times are epoch milliseconds, so time zone and daylight-saving changes don't move them.
 */
internal object RecordingPlan {
    /** Less than this left of the window isn't worth starting a recording for. */
    const val MIN_USEFUL_MS = 60_000L

    const val MISSED_REASON = "Missed: the device was off, restarting or asleep for the whole recording time"

    sealed interface Action
    /** Not due yet: trigger at [atMs]. */
    data class Alarm(val atMs: Long) : Action
    /** Due: record the [remainingMs] left of the original window. [resume] continues a capture
     * that was interrupted (the service appends instead of starting the file over). */
    data class StartNow(val remainingMs: Long, val resume: Boolean) : Action
    /** The whole window passed without the recording starting. */
    object Missed : Action
    /** Finished, failed, compressing, or a capture whose window already ended (the recordings
     * screens' existing clean-up owns those). Nothing to schedule. */
    object Leave : Action

    fun decide(status: String, startMs: Long, durationMs: Long, nowMs: Long): Action {
        val endMs = startMs + durationMs
        val remaining = endMs - nowMs
        return when (status) {
            "SCHEDULED" -> when {
                remaining < MIN_USEFUL_MS -> Missed
                startMs > nowMs -> Alarm(startMs)
                else -> StartNow(remaining, resume = false)
            }
            // A capture this process isn't running (it was killed, or the device restarted
            // mid-recording): carry on for what's left, into the same file.
            "RECORDING" -> if (remaining >= MIN_USEFUL_MS) StartNow(remaining, resume = true) else Leave
            else -> Leave
        }
    }
}

/**
 * The one place recording alarms are set and cancelled. An alarm carries only the recording's
 * id (never the stream URL or login); the receiver looks everything up when it fires. The
 * PendingIntent is keyed by that id, so setting it again replaces the earlier alarm instead of
 * adding a second one, and alarms set by v7.22 and earlier (same component and id) are replaced
 * or cancelled the same way.
 */
object RecordingAlarms {
    const val EXTRA_RESUME = "recording_resume"

    /** Returns true when the alarm is exact. Inexact alarms may fire late, and on Android 12+
     * can't start the recording by themselves (see RecordingAlarmReceiver). */
    fun schedule(context: Context, recordingId: Int, atMs: Long, resume: Boolean = false): Boolean {
        val pi = pendingIntent(context, recordingId, resume)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (canScheduleExact(am)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                return true
            } catch (_: SecurityException) {
                // Permission revoked between the check and the call: fall through.
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        return false
    }

    fun cancel(context: Context, recordingId: Int) {
        val intent = Intent(context, RecordingAlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, recordingId, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }

    fun canScheduleExact(context: Context): Boolean =
        canScheduleExact(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    private fun canScheduleExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun pendingIntent(context: Context, recordingId: Int, resume: Boolean): PendingIntent {
        val intent = Intent(context, RecordingAlarmReceiver::class.java).apply {
            putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
            putExtra(EXTRA_RESUME, resume)
        }
        return PendingIntent.getBroadcast(
            context, recordingId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
