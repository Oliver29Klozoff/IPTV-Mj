package com.iptvapp.reliability

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.PrefsSnapshot
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.prefsCorruptionHandler
import com.iptvapp.ui.SplashGate
import com.iptvapp.util.AccentColors
import com.iptvapp.util.CrashLog
import com.iptvapp.widget.ChannelWidgetFactory
import com.iptvapp.worker.BackgroundWork
import com.iptvapp.worker.ContinueWatchingCleanupWorker
import com.iptvapp.worker.EpgRefreshWorker
import com.iptvapp.worker.RecordingCleanupWorker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prompt 11: widget database, settings file safety, worker scheduling, guide "missing only",
 * splash timing, crash log ordering and accent parsing. Nothing here sleeps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Prompt11ReliabilityTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            // A stand-in worker: these tests check scheduling only, and the real (Hilt-built)
            // workers can't be created without Hilt's factory.
            context, Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(object : androidx.work.WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: androidx.work.WorkerParameters) =
                        object : androidx.work.Worker(appContext, workerParameters) {
                            override fun doWork() = Result.success()
                        }
                })
                .build()
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        CrashLog.file(context).delete()
    }

    // ── Widget: the app's database, never a new one per refresh ──────────────────────────

    @Test
    fun repeatedWidgetRefreshesUseOneDatabaseAndLeaveItOpen() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        db.channelDao().upsertChannels(listOf(channel(1, "One", favorite = true), channel(2, "Two", favorite = true)))
        val handedOut = mutableSetOf<IptvDatabase>()
        var calls = 0
        val factory = ChannelWidgetFactory(context, 42) { calls++; db.also { handedOut += it } }
        factory.onCreate()
        assertEquals("onCreate does no loading", 0, calls)
        repeat(5) { factory.onDataSetChanged() }
        assertEquals(5, calls)
        assertEquals("one database object, however many refreshes", 1, handedOut.size)
        assertTrue("the shared database is never closed by the widget", db.isOpen)
        assertEquals(2, factory.count)
        db.close()
    }

    // ── Settings file (DataStore) ────────────────────────────────────────────────────────

    @Test
    fun aCorruptedSettingsFileRecoversToDefaults() = runBlocking {
        val file = File(context.filesDir, "corrupt_${System.nanoTime()}.preferences_pb").apply {
            writeBytes(byteArrayOf(0x0a, 0x7f, 0x13, 0x00, 0x55, 0x2b)) // not a valid preferences protobuf
        }
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = prefsCorruptionHandler,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file }
        )
        assertTrue("defaults, not an exception", store.data.first().asMap().isEmpty())
        val key = stringPreferencesKey("k")
        store.edit { it[key] = "v" }
        assertEquals("usable again after the reset", "v", store.data.first()[key])
    }

    @Test
    fun normalSettingsSurviveAndTheSnapshotMatchesTheFlows() = runBlocking {
        val prefs = PreferencesManager(context)
        prefs.setSubtitlesEnabled(false)
        prefs.setPreferredAudioLanguage("spa")
        withTimeout(5_000) {
            PrefsSnapshot.awaitForTest { it.asMap().values.contains("spa") }
        }
        // The synchronous reads the player builders now use (no runBlocking on the main thread).
        assertFalse(prefs.subtitlesEnabledNow())
        assertEquals("spa", prefs.preferredAudioLanguageNow())
        assertEquals(prefs.subtitlesEnabled.first(), prefs.subtitlesEnabledNow())
        // Untouched settings keep the same defaults as their flows.
        assertEquals(prefs.extraBufferingEnabled.first(), prefs.extraBufferingEnabledNow())
        assertEquals(prefs.pipEnabled.first(), prefs.pipEnabledNow())
    }

    // ── Worker scheduling ────────────────────────────────────────────────────────────────

    @Test
    fun settingsRestoredAfterStartupAreScheduledWithoutARestart() = runBlocking {
        val prefs = PreferencesManager(context)
        prefs.setEpgAutoRefreshHours(0)
        prefs.setAutoDeleteRecordingsDays(0)
        BackgroundWork.syncAll(context, prefs) // app start on a fresh install: nothing to run
        assertTrue(live(BackgroundWork.AUTO_EPG_WORK_NAME).isEmpty())
        prefs.setEpgAutoRefreshHours(12) // a backup restored from the login screen or Settings
        prefs.setAutoDeleteRecordingsDays(7)
        BackgroundWork.syncAll(context, prefs) // what both restore paths now finish with
        assertEquals(1, live(BackgroundWork.AUTO_EPG_WORK_NAME).size)
        assertEquals(1, live(RecordingCleanupWorker.WORK_NAME).size)
    }

    @Test
    fun schedulingAgainAndAgainLeavesOneJobEach() {
        repeat(4) { // app start, Settings, and so on
            BackgroundWork.applyAutoEpgRefresh(context, 6)
            BackgroundWork.applyRecordingCleanup(context, 30)
            BackgroundWork.applyContinueWatchingCleanup(context, 14)
        }
        for (name in listOf(BackgroundWork.AUTO_EPG_WORK_NAME, RecordingCleanupWorker.WORK_NAME, ContinueWatchingCleanupWorker.WORK_NAME)) {
            assertEquals(name + " " + workManager.getWorkInfosForUniqueWork(name).get().map { it.state }, 1, live(name).size)
        }
    }

    @Test
    fun turningAFeatureOffCancelsItsJob() {
        BackgroundWork.applyAutoEpgRefresh(context, 6)
        BackgroundWork.applyRecordingCleanup(context, 30)
        BackgroundWork.applyAutoEpgRefresh(context, 0)
        BackgroundWork.applyRecordingCleanup(context, 0)
        assertTrue(live(BackgroundWork.AUTO_EPG_WORK_NAME).isEmpty())
        assertTrue(live(RecordingCleanupWorker.WORK_NAME).isEmpty())
    }

    @Test
    fun changingTheIntervalUpdatesTheOneJob() {
        BackgroundWork.applyAutoEpgRefresh(context, 6)
        val id = live(BackgroundWork.AUTO_EPG_WORK_NAME).single().id
        BackgroundWork.applyAutoEpgRefresh(context, 12)
        val after = live(BackgroundWork.AUTO_EPG_WORK_NAME).single()
        assertEquals("updated in place, not a second job", id, after.id)
        assertEquals(12 * 3_600_000L, after.periodicityInfo!!.repeatIntervalMillis)
    }

    // ── Guide "missing only" ─────────────────────────────────────────────────────────────

    @Test
    fun theAutomaticRefreshFollowsTheSavedSettingAndManualKeepsItsOwn() = runBlocking {
        val auto = workDataOf(EpgRefreshWorker.KEY_USE_SAVED_SETTING to true)
        assertTrue(EpgRefreshWorker.resolveMissingOnly(auto) { true })
        assertFalse("setting off: full refresh, as the UI says", EpgRefreshWorker.resolveMissingOnly(auto) { false })
        val manual = workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to true)
        assertTrue(EpgRefreshWorker.resolveMissingOnly(manual) { false })
    }

    @Test
    fun missingOnlyRefreshesJustTheChannelsWithoutGuide() = runBlocking {
        val favs = listOf(channel(1, "A"), channel(2, "B"), channel(3, "C"))
        assertEquals(listOf(2), EpgRefreshWorker.selectChannels(favs, true) { setOf(1, 3) }.map { it.streamId })
        assertEquals("all have guide: nothing", emptyList<Int>(), EpgRefreshWorker.selectChannels(favs, true) { setOf(1, 2, 3) }.map { it.streamId })
        var asked = false
        assertEquals("off: every favorite, without even looking", listOf(1, 2, 3),
            EpgRefreshWorker.selectChannels(favs, false) { asked = true; emptySet() }.map { it.streamId })
        assertFalse(asked)
    }

    // ── Splash ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aReadyAppLeavesTheSplashAfterTheShortMinimumNotFourSeconds() = runTest {
        val creds = SplashGate.awaitReady(SplashGate.MIN_SHOW_MS) { "ready" }
        assertEquals("ready", creds)
        assertEquals(SplashGate.MIN_SHOW_MS, currentTime)
        assertTrue(currentTime < 4_000)
    }

    @Test
    fun aSlowStartStillWaitsForWhatItNeeds() = runTest {
        SplashGate.awaitReady(SplashGate.MIN_SHOW_MS) { kotlinx.coroutines.delay(2_500); "late" }
        assertEquals(2_500L, currentTime)
    }

    // ── Crash log ────────────────────────────────────────────────────────────────────────

    @Test
    fun eventsAreWrittenInOrderAndACrashFlushesThemFirst() {
        CrashLog.file(context).delete()
        CrashLog.append(context, "[t1] one\n")
        CrashLog.append(context, "[t2] two\n")
        CrashLog.appendNow(context, "=== CRASH t3 ===\n")
        CrashLog.drainForTest()
        val text = CrashLog.file(context).readText()
        val one = text.indexOf("one"); val two = text.indexOf("two"); val crash = text.indexOf("CRASH")
        assertTrue(text, one in 0 until two && two < crash)
        assertEquals("each line once", 1, Regex("one").findAll(text).count())
    }

    @Test
    fun aClearRemovesOnlyWhatCameBeforeIt() {
        CrashLog.file(context).delete()
        CrashLog.append(context, "[t1] before\n")
        CrashLog.clear(context)
        CrashLog.append(context, "[t2] after\n")
        CrashLog.appendNow(context, "=== CRASH t3 ===\n") // the crash path applies the queued clear first
        CrashLog.drainForTest()
        val text = CrashLog.file(context).readText()
        assertFalse(text, text.contains("before"))
        assertTrue(text, text.contains("after") && text.contains("CRASH"))
    }

    @Test
    fun theLogStaysTrimmed() {
        CrashLog.file(context).writeText("x".repeat(60_000))
        CrashLog.append(context, "[t] tail\n")
        CrashLog.drainForTest()
        val text = CrashLog.file(context).readText()
        assertTrue(text.length <= 40_000 + 20)
        assertTrue(text.endsWith("[t] tail\n"))
    }

    // ── Accent colour ────────────────────────────────────────────────────────────────────

    @Test
    fun aBadSavedAccentFallsBackAndAGoodOneIsKept() {
        assertEquals(0xFF06B6D4.toInt(), AccentColors.parseOrNull("#06B6D4"))
        assertEquals(0x8006B6D4.toInt(), AccentColors.parseOrNull("#8006B6D4"))
        assertEquals(android.graphics.Color.RED, AccentColors.parseOrNull("red"))
        for (bad in listOf("", "   ", null, "#12", "garbage", "#GGGGGG", "#")) {
            assertNull(bad.toString(), AccentColors.parseOrNull(bad))
        }
        assertEquals(android.graphics.Color.parseColor(AccentColors.DEFAULT_HEX), AccentColors.startOrDefault("nope"))
        assertEquals("#123456", AccentColors.validOr("#123456", AccentColors.DEFAULT_HEX))
        assertEquals(AccentColors.DEFAULT_HEX, AccentColors.validOr("#xyz", AccentColors.DEFAULT_HEX))
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private fun live(name: String) = workManager.getWorkInfosForUniqueWork(name).get()
        .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    private fun channel(id: Int, name: String, favorite: Boolean = false) = ChannelEntity(
        streamId = id, name = name, streamIcon = null, categoryId = "c", epgChannelId = null,
        tvArchive = 0, tvArchiveDuration = 0, num = id, isFavorite = favorite
    )
}
