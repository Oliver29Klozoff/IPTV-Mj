package com.iptvapp.service

import android.app.AlarmManager
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.ui.recordings.RecordingRecoveryReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Re-arming after a reboot / update / force-stop, and starting a recording by its id. Android 14
 * (API 34) so exact-alarm permission and the Android 12+ background-start rule apply. Alarms are
 * inspected, not waited for; nothing sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingRecoveryTest {

    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L

    private lateinit var context: Context
    private lateinit var db: IptvDatabase
    private lateinit var alarmManager: AlarmManager
    private val launched = mutableListOf<Intent>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Reboot recovery ─────────────────────────────────────────────────────────────────

    @Test
    fun aFutureRecordingIsArmedAgainAtItsOriginalTime() = runBlocking {
        val id = insert("SCHEDULED", now + hour)
        RecordingRecovery.run(context, db, now)
        val alarm = alarms().single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type) // absolute epoch time: zone/DST changes don't move it
        assertEquals(now + hour, alarm.triggerAtMs)
        assertEquals(id, recordingIdOf(alarm))
        assertTrue("exact when allowed", isExact(alarm))
    }

    @Test
    fun finishedFailedCompressingAndCancelledRecordingsAreNotArmed() = runBlocking {
        insert("DONE", now + hour)
        insert("FAILED", now + hour)
        insert("COMPRESSING", now - 10 * 60_000L)
        val cancelled = insert("SCHEDULED", now + hour)
        db.recordingDao().delete(db.recordingDao().getById(cancelled)!!)
        RecordingRecovery.run(context, db, now)
        assertTrue(alarms().isEmpty())
    }

    @Test
    fun runningRecoveryAgainDoesNotAddAlarms() = runBlocking {
        insert("SCHEDULED", now + hour)
        insert("SCHEDULED", now + 2 * hour)
        insert("SCHEDULED", now - 2 * 60_000L)
        repeat(3) { RecordingRecovery.run(context, db, now) } // boot, app start, update
        assertEquals(3, alarms().size)
        assertEquals(3, alarms().map { recordingIdOf(it) }.toSet().size)
    }

    @Test
    fun oneThatShouldHaveStartedTwoMinutesAgoStartsForWhatIsLeft() = runBlocking {
        val id = insert("SCHEDULED", now - 2 * 60_000L, durationMs = hour)
        val summary = RecordingRecovery.run(context, db, now)
        assertEquals(1, summary.startingNow)
        val alarm = alarms().single()
        assertEquals("through an alarm, not directly from the boot receiver", now + RecordingRecovery.START_DELAY_MS, alarm.triggerAtMs)

        val firedAt = alarm.triggerAtMs
        assertEquals(RecordingStarter.Outcome.STARTED, start(id, nowMs = firedAt))
        val started = launched.single()
        assertEquals(now - 2 * 60_000L + hour - firedAt, started.getLongExtra(RecordingService.EXTRA_DURATION_MS, 0))
        assertFalse(started.getBooleanExtra(RecordingService.EXTRA_RESUME, true))
        assertEquals("RECORDING", db.recordingDao().getById(id)!!.status)
    }

    @Test
    fun oneThatEndedBeforeTheDeviceCameBackIsMarkedMissedAndNotStarted() = runBlocking {
        val id = insert("SCHEDULED", now - 3 * hour, durationMs = hour)
        val summary = RecordingRecovery.run(context, db, now)
        assertEquals(1, summary.missed)
        assertTrue(alarms().isEmpty())
        val rec = db.recordingDao().getById(id)!!
        assertEquals("FAILED", rec.status)
        assertEquals(RecordingPlan.MISSED_REASON, rec.failureReason)
        assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
    }

    @Test
    fun aCaptureCutOffByTheRestartResumesIntoTheSameFile() = runBlocking {
        val id = insert("RECORDING", now - 10 * 60_000L, durationMs = hour)
        RecordingRecovery.run(context, db, now)
        val alarm = alarms().single()
        assertTrue(shadowOf(alarm.operation).savedIntent.getBooleanExtra(RecordingAlarms.EXTRA_RESUME, false))

        assertEquals(RecordingStarter.Outcome.STARTED, start(id, resume = true))
        assertTrue(launched.single().getBooleanExtra(RecordingService.EXTRA_RESUME, false))
    }

    // ── Duplicates ──────────────────────────────────────────────────────────────────────

    @Test
    fun twoTriggersForOneRecordingStartItOnce() = runBlocking {
        val id = insert("SCHEDULED", now - 60_000L)
        assertEquals(RecordingStarter.Outcome.STARTED, start(id))
        // The duplicate (a second alarm, recovery racing the alarm): the claim is already taken.
        assertEquals(RecordingStarter.Outcome.IGNORED, start(id))
        assertEquals(1, launched.size)
    }

    @Test
    fun aCaptureThatJustStartedIsNeverMarkedMissedOrDeleted() = runBlocking {
        // Recovery read the row as SCHEDULED near the end of its window; the alarm claimed it first.
        val file = java.io.File.createTempFile("mktv-capture", ".ts").apply { writeText("captured") }
        val id = db.recordingDao().insert(
            RecordingEntity(
                streamId = 1, channelName = "News", scheduledStartMs = now - hour, durationMs = hour + 30_000L,
                outputPath = file.absolutePath, status = "SCHEDULED"
            )
        ).toInt()
        val staleRead = db.recordingDao().getById(id)!!
        assertEquals(1, db.recordingDao().claimScheduled(id))

        assertFalse(RecordingStarter.markMissed(context, db, staleRead))
        assertEquals("RECORDING", db.recordingDao().getById(id)!!.status)
        assertTrue("the capture's file is untouched", file.exists())
        file.delete()
        Unit
    }

    @Test
    fun aStrayAlarmForARunningCaptureDoesNothing() = runBlocking {
        val id = insert("RECORDING", now - 60_000L)
        assertEquals(RecordingStarter.Outcome.IGNORED, start(id, resume = false))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun reschedulingReplacesTheEarlierAlarmAndCancelRemovesIt() {
        RecordingAlarms.schedule(context, 7, now + hour)
        RecordingAlarms.schedule(context, 7, now + 2 * hour)
        assertEquals(listOf(now + 2 * hour), alarms().map { it.triggerAtMs })
        RecordingAlarms.cancel(context, 7)
        assertTrue(alarms().isEmpty())
    }

    @Test
    fun aRecordingDeletedBeforeItsAlarmIsNotStarted() = runBlocking {
        assertEquals(RecordingStarter.Outcome.IGNORED, start(4242))
        assertTrue(launched.isEmpty())
    }

    // ── Android 12+: no exact alarms, background start refused ─────────────────────────

    @Test
    fun withoutExactAlarmsTheRecordingStillGetsAnAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(RecordingAlarms.schedule(context, 9, now + hour))
        val alarm = alarms().single()
        assertFalse(isExact(alarm))
        assertEquals(now + hour, alarm.triggerAtMs)
    }

    @Test
    fun whenAndroidRefusesTheStartTheScheduleIsKeptAndATapCanStartIt() = runBlocking {
        val id = insert("SCHEDULED", now - 60_000L)
        val outcome = RecordingStarter.start(
            context, db, id, resume = false, nowMs = now,
            resolveUrl = { "http://provider.example/live/u/p/1.ts" },
            launch = { throw ForegroundServiceStartNotAllowedException("background start") }
        )
        assertEquals(RecordingStarter.Outcome.NOT_ALLOWED, outcome)
        assertEquals("not marked failed: it can still start", "SCHEDULED", db.recordingDao().getById(id)!!.status)

        RecordingNotifications.postTapToStart(context, id, "News", resume = false)
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        // The tap opens MKTV's recordings screen, which starts it from the foreground (allowed on
        // Android 12+, and it resets Android 15's daily background time).
        val tap = shadowOf(notification.actions.single().actionIntent)
        assertTrue(tap.isActivityIntent)
        assertEquals(com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java.name, tap.savedIntent.component!!.className)
        assertEquals(id, tap.savedIntent.getIntExtra(RecordingNotifications.EXTRA_START_RECORDING_ID, -1))
    }

    @Test
    fun theStreamSavedAtSchedulingIsUsedNotOneBuiltFromTheCurrentSlot() = runBlocking {
        // An extra provider's slot can be handed to a different provider after scheduling.
        val id = insert("SCHEDULED", now - 60_000L)
        RecordingUrls.put(context, id, "http://scheduled-provider.example/live/a/b/5.ts")
        RecordingStarter.start(
            context, db, id, resume = false, nowMs = now,
            resolveUrl = { RecordingUrls.get(context, it.id) ?: "http://other-provider.example/live/x/y/5.ts" },
            launch = { launched += it }
        )
        assertEquals("http://scheduled-provider.example/live/a/b/5.ts", launched.single().getStringExtra(RecordingService.EXTRA_STREAM_URL))
        // Finished recordings' saved streams are dropped by recovery.
        db.recordingDao().updateStatus(id, "DONE")
        RecordingRecovery.run(context, db, now)
        assertNull(RecordingUrls.get(context, id))
    }

    @Test
    fun anUpdateKeepsAV722AlarmsStream() = legacyAlarmKeepsItsStream(startMs = now + hour, expectedAt = now + hour)

    @Test
    fun anUpdateKeepsAnOverdueV722AlarmsStream() =
        legacyAlarmKeepsItsStream(startMs = now - 2 * 60_000L, expectedAt = now + RecordingRecovery.START_DELAY_MS)

    @Test
    fun grantingExactAlarmsMakesAnInexactV722AlarmExactAndKeepsItsStream() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false) // v7.22 set it inexact
        legacyAlarmKeepsItsStream(startMs = now + hour, expectedAt = now + hour, grantBeforeRecovery = true)
        assertTrue(isExact(alarms().single()))
    }

    private fun legacyAlarmKeepsItsStream(startMs: Long, expectedAt: Long, grantBeforeRecovery: Boolean = false) = runBlocking {
        // v7.22 set the alarm with the stream URL in it; no saved stream exists yet. The provider
        // in that slot may have changed since, so only that URL records the right channel.
        val id = insert("SCHEDULED", startMs)
        val legacy = android.app.PendingIntent.getBroadcast(
            context, id,
            Intent(context, com.iptvapp.ui.recordings.RecordingAlarmReceiver::class.java).apply {
                putExtra(RecordingService.EXTRA_RECORDING_ID, id)
                putExtra(RecordingService.EXTRA_STREAM_URL, "http://scheduled-provider.example/live/a/b/5.ts")
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        if (RecordingAlarms.canScheduleExact(context)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMs + 7, legacy)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMs + 7, legacy)
        }
        if (grantBeforeRecovery) ShadowAlarmManager.setCanScheduleExactAlarms(true)

        RecordingRecovery.run(context, db, now) // MY_PACKAGE_REPLACED, or the permission broadcast
        val alarm = alarms().single()
        assertEquals(expectedAt, alarm.triggerAtMs)
        val carried = shadowOf(alarm.operation).savedIntent
        assertEquals(
            "the same alarm, still carrying the stream it was scheduled with",
            "http://scheduled-provider.example/live/a/b/5.ts",
            carried.getStringExtra(RecordingService.EXTRA_STREAM_URL)
        )
        assertFalse("not replaced by a new id-only alarm", carried.hasExtra(RecordingAlarms.EXTRA_RESUME))
    }

    @Test
    fun pruningKeepsALiveScheduleAndDropsADeletedOne() = runBlocking {
        val live = insert("SCHEDULED", now + hour)
        RecordingUrls.put(context, live, "http://p.example/live/a/b/1.ts")
        RecordingUrls.put(context, 9999, "http://p.example/live/a/b/2.ts") // row deleted (cancelled)
        RecordingUrls.prune(context, db)
        assertEquals("http://p.example/live/a/b/1.ts", RecordingUrls.get(context, live))
        assertNull(RecordingUrls.get(context, 9999))
    }

    @Test
    fun cancellingTheStartIsNotAFailure() = runBlocking {
        val id = insert("SCHEDULED", now - 60_000L)
        val resolving = CompletableDeferred<Unit>()
        val job = launch {
            RecordingStarter.start(
                context, db, id, resume = false, nowMs = now,
                resolveUrl = { resolving.complete(Unit); awaitCancellation() },
                launch = { launched += it }
            )
        }
        resolving.await()
        job.cancelAndJoin()
        val rec = db.recordingDao().getById(id)!!
        assertEquals("still scheduled, not claimed or failed", "SCHEDULED", rec.status)
        assertNull(rec.failureReason)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun theAlarmCarriesOnlyTheRecordingId() {
        RecordingAlarms.schedule(context, 11, now + hour)
        val extras = shadowOf(alarms().single().operation).savedIntent.extras!!
        assertEquals(setOf(RecordingService.EXTRA_RECORDING_ID, RecordingAlarms.EXTRA_RESUME), extras.keySet())
    }

    // ── Receivers, service type ────────────────────────────────────────────────────────

    @Test
    fun recoveryRunsOnBootUpdateAndAlarmPermissionChangesOnly() {
        assertEquals(
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
            ),
            RecordingRecoveryReceiver.ACTIONS
        )
        // Anything else is ignored before touching the database or alarms.
        RecordingRecoveryReceiver().onReceive(context, Intent("com.example.START_ANYTHING"))
        assertTrue(alarms().isEmpty())
    }

    @Test
    fun theRecordingServiceIsDataSyncAndHandlesAndroid15sTimeLimit() {
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, RecordingService::class.java), 0
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertFalse(info.exported)
        // Overridden, so Android 15's time limit stops the service cleanly instead of crashing it.
        RecordingService::class.java.getDeclaredMethod("onTimeout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    private suspend fun start(id: Int, resume: Boolean = false, nowMs: Long = now) = RecordingStarter.start(
        context, db, id, resume, nowMs,
        resolveUrl = { "http://provider.example/live/u/p/1.ts" },
        launch = { launched += it }
    )

    private suspend fun insert(status: String, startMs: Long, durationMs: Long = hour): Int =
        db.recordingDao().insert(
            RecordingEntity(
                streamId = 1, channelName = "News", scheduledStartMs = startMs, durationMs = durationMs,
                outputPath = "/tmp/mktv-test-${System.nanoTime()}.ts", status = status
            )
        ).toInt()

    private fun alarms() = shadowOf(alarmManager).scheduledAlarms

    private fun recordingIdOf(alarm: ShadowAlarmManager.ScheduledAlarm) =
        shadowOf(alarm.operation).savedIntent.getIntExtra(RecordingService.EXTRA_RECORDING_ID, -1)

    // setExactAndAllowWhileIdle leaves no window; setAndAllowWhileIdle lets Android pick one.
    private fun isExact(alarm: ShadowAlarmManager.ScheduledAlarm) = alarm.windowLengthMs == 0L
}
