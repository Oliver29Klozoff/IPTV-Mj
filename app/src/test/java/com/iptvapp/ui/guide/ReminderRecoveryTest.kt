package com.iptvapp.ui.guide

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.ui.recordings.RecordingAlarmReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** Guide reminders survive a reboot, once each, and never turn into recordings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReminderRecoveryTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        setBoot(5)
    }

    @Test
    fun aReminderIsSetAgainAfterAReboot() {
        val start = System.currentTimeMillis() + hour
        ChannelTimerScheduler.schedule(context, 7, "News", "Nightly", start, serverIndex = 2)
        assertTrue(ChannelTimerScheduler.isScheduled(context, 7, start))

        reboot()
        assertFalse("a reboot cleared it", ChannelTimerScheduler.isScheduled(context, 7, start))
        ChannelTimerScheduler.recover(context)

        assertTrue(ChannelTimerScheduler.isScheduled(context, 7, start))
        val alarm = alarms().single()
        val intent = shadowOf(alarm.operation).savedIntent
        assertEquals(ChannelTimerReceiver::class.java.name, intent.component!!.className)
        assertEquals("Nightly", intent.getStringExtra("program_title"))
        assertEquals("News", intent.getStringExtra("channel_name"))
        assertEquals(2, intent.getIntExtra("server_index", -1))
    }

    @Test
    fun recoveringAgainDoesNotAddAlarms() {
        val start = System.currentTimeMillis() + hour
        ChannelTimerScheduler.schedule(context, 7, "News", "Nightly", start)
        ChannelTimerScheduler.schedule(context, 8, "Sports", "Game", start)
        repeat(3) { ChannelTimerScheduler.recover(context) }
        assertEquals(2, alarms().size)
    }

    @Test
    fun aReminderThatAlreadyWentOffIsNotSentAgain() {
        val start = System.currentTimeMillis() + 3 * 60_000L
        ChannelTimerScheduler.schedule(context, 7, "News", "Nightly", start)
        ChannelTimerScheduler.markFired(context, 7)
        reboot()
        ChannelTimerScheduler.recover(context)
        assertTrue(alarms().isEmpty())
    }

    @Test
    fun aShowThatAlreadyStartedIsDropped() {
        val start = System.currentTimeMillis() + hour
        ChannelTimerScheduler.schedule(context, 7, "News", "Nightly", start)
        reboot()
        ChannelTimerScheduler.recover(context, nowMs = start + 1)
        assertTrue(alarms().isEmpty())
        assertTrue(prefs().all.isEmpty())
    }

    @Test
    fun aNoticeTimeThatPassedWhileOffGoesOffNowWithTheRealTimeLeft() {
        // 5-minute lead; the device comes back 2 minutes before the show.
        val start = System.currentTimeMillis() + 2 * 60_000L + 30_000L
        ChannelTimerScheduler.schedule(context, 7, "News", "Nightly", start)
        reboot()
        ChannelTimerScheduler.recover(context)
        val alarm = alarms().single()
        assertTrue(alarm.triggerAtMs < start - 2 * 60_000L)
        assertEquals(2, shadowOf(alarm.operation).savedIntent.getIntExtra("lead_minutes", -1))
    }

    @Test
    fun anOldFormatEntryFromAnEarlierBootIsDroppedNotGuessed() {
        val start = System.currentTimeMillis() + hour
        prefs().edit().putString("7", "$start|4").commit()
        ChannelTimerScheduler.recover(context)
        assertTrue(alarms().isEmpty())
        assertTrue(prefs().all.isEmpty())
    }

    @Test
    fun reminderRecoveryNeverSetsARecordingAlarm() {
        ChannelTimerScheduler.schedule(context, 7, "News", "Nightly", System.currentTimeMillis() + hour)
        reboot()
        ChannelTimerScheduler.recover(context)
        assertTrue(alarms().none {
            shadowOf(it.operation).savedIntent.component!!.className == RecordingAlarmReceiver::class.java.name
        })
    }

    private fun reboot() {
        // A reboot clears the app's alarms and moves the boot counter on.
        for (alarm in alarms().toList()) alarm.operation?.let { alarmManager.cancel(it) }
        setBoot(Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) + 1)
    }

    private fun setBoot(n: Int) {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, n)
    }

    private fun prefs() = context.getSharedPreferences("channel_reminders", Context.MODE_PRIVATE)

    private fun alarms() = shadowOf(alarmManager).scheduledAlarms
}
