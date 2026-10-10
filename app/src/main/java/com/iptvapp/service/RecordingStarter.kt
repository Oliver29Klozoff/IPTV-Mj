package com.iptvapp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.ui.recordings.RecordingAlarmReceiver
import com.iptvapp.util.rethrowIfCancelled
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** What the broadcast receivers need from Hilt (receivers here aren't Hilt-injected). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecordingDeps {
    fun database(): IptvDatabase
    fun repository(): XtreamRepository
}

/**
 * Starts a recording by its id, and only by its id: the schedule is read from the database, the
 * stream URL is built from the current login, and the time left is worked out from the original
 * window. The alarm, Start now, a notification tap and reboot recovery all come through here.
 */
object RecordingStarter {

    enum class Outcome { STARTED, RESCHEDULED, IGNORED, MISSED, CHANNEL_GONE, NOT_ALLOWED }

    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Start now, from a recordings screen. Not tied to the screen, so backing out of it straight
     * away doesn't cancel the start; if Android still refuses, the notification offers a tap. */
    fun startFromScreen(context: Context, db: IptvDatabase, repository: XtreamRepository, recordingId: Int, resume: Boolean = false) {
        val app = context.applicationContext
        appScope.launch {
            if (start(app, db, repository, recordingId, resume) == Outcome.NOT_ALLOWED) {
                db.recordingDao().getById(recordingId)?.let { RecordingNotifications.postTapToStart(app, it.id, it.channelName, resume) }
            }
        }
    }

    suspend fun start(
        context: Context,
        db: IptvDatabase,
        repository: XtreamRepository,
        recordingId: Int,
        resume: Boolean = false
    ): Outcome = start(
        context, db, recordingId, resume, System.currentTimeMillis(),
        resolveUrl = { rec ->
            // The stream it was scheduled for; schedules from older builds build it from the login.
            RecordingUrls.get(context, rec.id)
                ?: if (rec.serverIndex == -1) repository.getLiveStreamUrlForRecording(rec.streamId)
                else repository.getMergedLiveStreamUrlForRecording(rec.serverIndex, rec.streamId)
        },
        launch = { intent -> ContextCompat.startForegroundService(context, intent) }
    )

    internal suspend fun start(
        context: Context,
        db: IptvDatabase,
        recordingId: Int,
        resume: Boolean,
        nowMs: Long,
        resolveUrl: suspend (RecordingEntity) -> String?,
        launch: (Intent) -> Unit
    ): Outcome {
        val dao = db.recordingDao()
        // A deleted row is a cancelled recording.
        val rec = dao.getById(recordingId) ?: return Outcome.IGNORED
        return when (val action = RecordingPlan.decide(rec.status, rec.scheduledStartMs, rec.durationMs, nowMs)) {
            // Fired early (the clock was moved back): wait for the real start.
            is RecordingPlan.Alarm -> {
                RecordingAlarms.schedule(context, rec.id, action.atMs)
                Outcome.RESCHEDULED
            }
            RecordingPlan.Missed -> if (markMissed(context, db, rec)) Outcome.MISSED else Outcome.IGNORED
            RecordingPlan.Leave -> Outcome.IGNORED
            is RecordingPlan.StartNow -> {
                // An interrupted capture is picked up only when recovery asked for it. A stray or
                // duplicate alarm for a recording that is already running does nothing.
                if (action.resume && !resume) return Outcome.IGNORED
                // Before the claim, so a cancelled or failed lookup leaves the schedule untouched.
                val url = try {
                    resolveUrl(rec)
                } catch (e: Exception) {
                    rethrowIfCancelled(e)
                    null
                }
                if (url.isNullOrBlank()) {
                    dao.updateStatusWithReason(rec.id, "FAILED", "Channel no longer available")
                    return Outcome.CHANNEL_GONE
                }
                if (!action.resume && dao.claimScheduled(rec.id) == 0) return Outcome.IGNORED
                val intent = Intent(context, RecordingService::class.java).apply {
                    putExtra(RecordingService.EXTRA_RECORDING_ID, rec.id)
                    putExtra(RecordingService.EXTRA_STREAM_URL, url)
                    putExtra(RecordingService.EXTRA_CHANNEL_NAME, rec.channelName)
                    putExtra(RecordingService.EXTRA_DURATION_MS, action.remainingMs)
                    putExtra(RecordingService.EXTRA_OUTPUT_PATH, rec.outputPath)
                    putExtra(RecordingService.EXTRA_RESUME, action.resume)
                }
                try {
                    launch(intent)
                    Outcome.STARTED
                } catch (e: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException (Android 12+, no exemption: an
                    // inexact alarm, or a background start on Android 15's boot restrictions).
                    if (!action.resume) dao.unclaim(rec.id)
                    Outcome.NOT_ALLOWED
                }
            }
        }
    }

    /** The window passed before the recording could start: record why, and drop the empty file
     * placeholder that was created when it was scheduled. Returns false (and touches nothing) when
     * the row is no longer SCHEDULED — another trigger claimed it first. */
    internal suspend fun markMissed(context: Context, db: IptvDatabase, rec: RecordingEntity): Boolean {
        if (db.recordingDao().markMissedIfScheduled(rec.id, RecordingPlan.MISSED_REASON) == 0) return false
        runCatching {
            if (rec.outputPath.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(rec.outputPath), null, null)
            } else {
                File(rec.outputPath).delete()
            }
        }
        return true
    }
}

/**
 * Re-arms recordings after anything that clears or invalidates alarms: a reboot, an app update,
 * a force-stop (caught at the next app start) or exact-alarm permission changing. Safe to run any
 * number of times — every alarm is keyed by recording id, so running it again replaces alarms
 * instead of adding them, and the claim in RecordingStarter stops a second start.
 */
object RecordingRecovery {
    /** Due recordings start through an alarm a moment from now rather than directly: an alarm is
     * what lets Android start a recording from the background (and on Android 15, a boot receiver
     * may not start a dataSync service at all). */
    const val START_DELAY_MS = 2_000L

    data class Summary(val scheduled: Int, val startingNow: Int, val missed: Int)

    private val mutex = Mutex()

    suspend fun run(context: Context, db: IptvDatabase, nowMs: Long = System.currentTimeMillis()): Summary = mutex.withLock {
        var scheduled = 0
        var starting = 0
        var missed = 0
        RecordingUrls.prune(context, db)
        for (rec in db.recordingDao().getScheduledOrRecording()) {
            when (val action = RecordingPlan.decide(rec.status, rec.scheduledStartMs, rec.durationMs, nowMs)) {
                is RecordingPlan.Alarm -> {
                    // A v7.22 alarm still set for this recording (an app update, no reboot) carries the
                    // stream it was scheduled for; setting a new one would replace it. Leave it to fire:
                    // the receiver keeps that stream (RecordingUrls).
                    val legacyAlarm = RecordingUrls.get(context, rec.id) == null && RecordingAlarms.isArmed(context, rec.id)
                    if (!legacyAlarm) RecordingAlarms.schedule(context, rec.id, action.atMs)
                    scheduled++
                }
                is RecordingPlan.StartNow -> {
                    RecordingAlarms.schedule(context, rec.id, nowMs + START_DELAY_MS, resume = action.resume)
                    starting++
                }
                RecordingPlan.Missed -> if (RecordingStarter.markMissed(context, db, rec)) {
                    RecordingNotifications.postMissed(context, rec)
                    missed++
                }
                RecordingPlan.Leave -> Unit
            }
        }
        Summary(scheduled, starting, missed)
    }
}

object RecordingNotifications {
    // On RecordingSchedulerActivity: start this recording (from the "Recording not started" notification).
    const val EXTRA_START_RECORDING_ID = "start_recording_id"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(RecordingService.CHANNEL_ID, "Recordings", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(RecordingService.FAILURE_CHANNEL_ID, "Recording Failures", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    /**
     * Android wouldn't let the recording start on its own: no exact-alarm permission on Android
     * 12+ (the alarm carried no right to start it from the background), or Android 15's daily
     * background-recording time is used up. Tapping opens MKTV's recordings screen, which starts
     * it from the foreground — allowed in both cases (opening the app also resets Android 15's
     * daily time) — and records whatever is left of the window.
     */
    fun postTapToStart(context: Context, recordingId: Int, channelName: String, resume: Boolean) {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, recordingId,
            Intent(context, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_START_RECORDING_ID, recordingId)
                putExtra(RecordingAlarms.EXTRA_RESUME, resume)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "Android didn't let MKTV start this recording in the background. Tap to open MKTV and " +
            "record the rest now. To avoid this, allow MKTV to set alarms (Settings → Apps → MKTV → Alarms & reminders)."
        val notification = NotificationCompat.Builder(context, RecordingService.FAILURE_CHANNEL_ID)
            .setSmallIcon(com.iptvapp.R.drawable.ic_notification)
            .setContentTitle("Recording not started: $channelName")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Start recording", open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notify(context, recordingId, notification)
    }


    fun postMissed(context: Context, rec: RecordingEntity) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, RecordingService.FAILURE_CHANNEL_ID)
            .setSmallIcon(com.iptvapp.R.drawable.ic_notification)
            .setContentTitle("Recording missed: ${rec.channelName}")
            .setContentText(RecordingPlan.MISSED_REASON)
            .setStyle(NotificationCompat.BigTextStyle().bigText(RecordingPlan.MISSED_REASON))
            .setAutoCancel(true)
            .build()
        notify(context, rec.id, notification)
    }

    fun cancel(context: Context, recordingId: Int) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(RecordingService.NOTIF_ID_FAILURE_BASE + recordingId)
    }

    private fun notify(context: Context, recordingId: Int, notification: android.app.Notification) {
        // Missing POST_NOTIFICATIONS just means nothing is shown.
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(RecordingService.NOTIF_ID_FAILURE_BASE + recordingId, notification)
        }
    }
}
