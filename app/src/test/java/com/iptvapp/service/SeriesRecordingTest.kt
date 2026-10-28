package com.iptvapp.service

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.*
import com.iptvapp.data.repository.XtreamRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SeriesRecordingTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: IptvDatabase
    private lateinit var engine: SeriesRecordingEngine
    private val now = System.currentTimeMillis()
    private var available = 2
    private var identity = "provider-a"
    private val access = object : RecordingProviderAccess {
        override suspend fun key(server: Int) = identity
        override suspend fun limits(server: Int) = XtreamRepository.AccountLimits("Active", available, 0, now)
        override suspend fun url(server: Int, stream: Int) = "http://127.0.0.1/fixture.ts"
    }
    private fun rule(mode: String = "ALL", priority: Int = 50) = SeriesRecordingRule(title = "Show", seriesKey = "series-1",
        providerKey = "provider-a", serverIndex = -1, streamId = 7, channelName = "Channel", mode = mode, priority = priority, stopLowStorage = false)
    private fun epg(id: String = "a", offset: Long = 3_600_000, episode: String? = "episode-1", fresh: Boolean? = true) = EpgEntity(
        id = id, streamId = 7, title = "Show", description = "", startTimestamp = now + offset, stopTimestamp = now + offset + 1_800_000,
        nowPlaying = 0, hasArchive = 0, seriesKey = "series-1", episodeKey = episode, isNewEpisode = fresh)

    @Before fun setup() {
        ShadowEnvironment.setExternalStorageState(android.os.Environment.MEDIA_MOUNTED)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        engine = SeriesRecordingEngine(context, db, PreferencesManager(context), access)
    }
    @After fun close() { db.close() }

    @Test fun `single episode schedules only the selected airing`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected, epg("b", 7_200_000, "episode-2")))
        engine.create(rule("SINGLE"), selected); engine.refresh(); engine.refresh()
        assertEquals(1, db.recordingDao().getAll().first().size)
        assertEquals("Show", db.recordingDao().getAll().first().single().programTitle)
    }
    @Test fun `all episodes omit duplicate rerun jobs after refresh and recovery`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected, epg("repeat", 7_200_000), epg("next", 10_800_000, "episode-2")))
        engine.create(rule(), selected); repeat(3) { engine.refresh(); RecordingRecovery.run(context, db) }
        assertEquals(2, db.recordingDao().getAll().first().size)
        assertEquals(2, db.seriesRecordingDao().episodes().size)
    }
    @Test fun `new episodes only ignores known reruns and flags unknown metadata`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected, epg("rerun", 7_200_000, "episode-2", false), epg("unknown", 10_800_000, "episode-3", null)))
        engine.create(rule("NEW"), selected); engine.refresh()
        assertEquals(1, db.recordingDao().getAll().first().size)
        assertEquals("UNCERTAIN", db.seriesRecordingDao().episodes().last().status)
    }
    @Test fun `missing identity requires explicit per airing confirmation`() = runBlocking {
        val selected = epg(episode = null); db.epgDao().upsertEpg(listOf(selected))
        engine.create(rule(), selected); engine.refresh()
        assertTrue(db.recordingDao().getAll().first().isEmpty())
        val candidate = db.seriesRecordingDao().episodes().single()
        assertEquals("UNCERTAIN", candidate.status)
        engine.confirm(candidate.identity); engine.refresh()
        assertEquals(1, db.recordingDao().getAll().first().size)
    }
    @Test fun `stable metadata follows a rename and shifted airing without another job`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected)); engine.create(rule(), selected); engine.refresh()
        val before = db.recordingDao().getAll().first().single()
        db.epgDao().deleteAllForServer(-1)
        val moved = epg("moved", 7_200_000).copy(title = "Renamed show")
        db.epgDao().upsertEpg(listOf(moved)); engine.refresh()
        val after = db.recordingDao().getAll().first().single()
        assertEquals(before.id, after.id)
        assertEquals(moved.startTimestamp, after.scheduledStartMs)
    }
    @Test fun `guide cancellation preserves existing reservation for review`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected)); engine.create(rule(), selected); engine.refresh()
        db.epgDao().deleteAllForServer(-1); engine.refresh()
        assertEquals("SCHEDULED", db.recordingDao().getAll().first().single().status)
        assertEquals("UNCERTAIN", db.seriesRecordingDao().episodes().single().status)
    }
    @Test fun `a booked airing the guide dropped holds its start until confirmed`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected)); engine.create(rule(), selected); engine.refresh()
        val rec = db.recordingDao().getAll().first().single()
        db.epgDao().deleteAllForServer(-1); engine.refresh()
        assertNotNull(engine.startRefusal(rec))
        assertEquals("UNCERTAIN", db.seriesRecordingDao().episodes().single().status)
        assertEquals("SCHEDULED", db.recordingDao().getById(rec.id)!!.status)
    }
    @Test fun `retrying a cancelled episode books a new recording`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected)); engine.create(rule(), selected); engine.refresh()
        val episode = db.seriesRecordingDao().episodes().single()
        engine.cancelEpisode(episode.identity)
        engine.confirm(episode.identity); engine.refresh()
        assertEquals("SCHEDULED", db.seriesRecordingDao().episodes().single().status)
        assertEquals(1, db.recordingDao().getAll().first().count { it.status == "SCHEDULED" })
    }
    @Test fun `changing the preferred channel rebooks pending episodes on it`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected, selected.copy(id = "other", streamId = 8)))
        val id = engine.create(rule(), selected); engine.refresh()
        val old = db.seriesRecordingDao().rule(id)!!
        engine.changeSource(old, old.copy(streamId = 8, channelName = "Other")); engine.refresh()
        val live = db.recordingDao().getAll().first().filter { it.status == "SCHEDULED" }
        assertEquals(listOf(8), live.map { it.streamId })
    }
    @Test fun `back-to-back recordings do not stack against the connection count`() {
        val h = 3_600_000L
        fun rec(start: Long, len: Long) = RecordingEntity(streamId = 1, channelName = "c", scheduledStartMs = start, durationMs = len, outputPath = "x")
        val sequential = listOf(rec(0, h), rec(h, h))
        assertEquals(1, SeriesRecordingPolicy.peak(0, 2 * h, sequential))
        assertTrue(SeriesRecordingPolicy.conflict(0, 2 * h, -1, sequential, 2).isEmpty())
        assertEquals(2, SeriesRecordingPolicy.peak(0, 2 * h, listOf(rec(0, 2 * h), rec(h / 2, h))))
    }
    @Test fun `existing reservation survives a higher priority conflict`() = runBlocking {
        available = 1
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected))
        val existing = db.recordingDao().insert(RecordingEntity(streamId = 9, channelName = "Manual", scheduledStartMs = selected.startTimestamp, durationMs = 1_800_000, outputPath = "/tmp/keep.ts")).toInt()
        engine.create(rule(priority = 90), selected); engine.refresh()
        assertEquals("SCHEDULED", db.recordingDao().getById(existing)!!.status)
        assertEquals("CONFLICT", db.seriesRecordingDao().episodes().single().status)
        assertEquals(1, db.recordingDao().getAll().first().size)
    }
    @Test fun `preferred provider change refuses start without failing the reservation`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected)); engine.create(rule(), selected); engine.refresh()
        val rec = db.recordingDao().getAll().first().single(); identity = "different-account"
        assertNotNull(engine.startRefusal(rec))
        assertEquals("SCHEDULED", db.recordingDao().getById(rec.id)!!.status)
    }
    @Test fun `cancelling rule stops future jobs but preserves completed files and history`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected, epg("b", 7_200_000, "episode-2")))
        val id = engine.create(rule(), selected); engine.refresh()
        val records = db.recordingDao().getAll().first()
        db.recordingDao().updateStatus(records.first().id, "DONE")
        engine.setRule(db.seriesRecordingDao().rule(id)!!.copy(cancelled = true)); engine.refresh()
        assertEquals("DONE", db.recordingDao().getById(records.first().id)!!.status)
        assertEquals("CANCELLED", db.recordingDao().getById(records.last().id)!!.status)
        assertEquals(2, db.seriesRecordingDao().episodes().size)
    }
    @Test fun `paused rule resumes once without duplicate jobs`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected)); val id = engine.create(rule(), selected); engine.refresh()
        val r = db.seriesRecordingDao().rule(id)!!
        engine.setRule(r.copy(paused = true)); engine.refresh()
        assertTrue(db.recordingDao().getScheduledOrRecording().isEmpty())
        engine.setRule(r); repeat(2) { engine.refresh() }
        assertEquals(1, db.recordingDao().getScheduledOrRecording().size)
    }
    @Test fun `epoch windows and identifiers survive DST and timezone changes`() {
        val selected = epg(); val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            val first = SeriesRecordingPolicy.window(rule(), selected)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London"))
            assertEquals(first, SeriesRecordingPolicy.window(rule(), selected))
            assertEquals(SeriesRecordingPolicy.identity("a", selected), SeriesRecordingPolicy.identity("a", selected.copy(startTimestamp = selected.startTimestamp + 3_600_000)))
            assertNotEquals(SeriesRecordingPolicy.identity("a", selected), SeriesRecordingPolicy.identity("b", selected))
        } finally { java.util.TimeZone.setDefault(original) }
    }
    @Test fun `retention only selects eligible completed unprotected episodes`() {
        val records = (1..8).map { RecordingEntity(id = it, streamId = 7, channelName = "Show", scheduledStartMs = it.toLong(), durationMs = 1,
            outputPath = "/tmp/$it.ts", status = if (it == 8) "RECORDING" else "DONE", isProtected = it == 1) }
        assertEquals(listOf(4, 3, 2), SeriesRecordingPolicy.retention(records, 3).map { it.id })
        assertTrue(SeriesRecordingPolicy.retention(records, 0).isEmpty())
    }
    @Test fun `padding limits storage estimates and provider capacity are bounded`() {
        val selected = epg(); val (start, end) = SeriesRecordingPolicy.window(rule().copy(startPaddingMinutes = 99, endPaddingMinutes = 99), selected)
        assertEquals(selected.startTimestamp - 15 * 60_000, start)
        assertEquals(selected.stopTimestamp + 60 * 60_000, end)
        assertEquals(0, SeriesRecordingPolicy.capacity(1, 1))
        assertEquals(1, SeriesRecordingPolicy.capacity(null, null))
        assertTrue(SeriesRecordingPolicy.requiredBytes(3_600_000) > 3_600_000_000)
    }
    @Test fun `xmltv identifiers and zero based numbering are preserved without title guesses`() {
        val dd = SeriesRecordingPolicy.xmlNumbers("dd_progid", "EP00012345.0007")
        assertEquals("dd:EP00012345", dd.series)
        assertEquals("dd:EP00012345.0007", dd.episode)
        val ns = SeriesRecordingPolicy.xmlNumbers("xmltv_ns", "1 . 4 .")
        assertEquals(2, ns.season); assertEquals(5, ns.number)
        val onscreen = SeriesRecordingPolicy.xmlNumbers("onscreen", "S2 E5")
        assertNull(onscreen.series)
        assertEquals(2, onscreen.season); assertEquals(5, onscreen.number)
        assertEquals("title:the show", SeriesRecordingPolicy.numberedSeries(" The  Show", 2, 5))
        assertNull(SeriesRecordingPolicy.numberedSeries("The Show", 2, null))
    }
    @Test fun `two account connections let overlapping recordings both schedule`() = runBlocking {
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected))
        db.recordingDao().insert(RecordingEntity(streamId = 9, channelName = "Manual", scheduledStartMs = selected.startTimestamp, durationMs = 1_800_000, outputPath = "/tmp/keep.ts"))
        engine.create(rule(), selected); engine.refresh()
        assertEquals("SCHEDULED", db.seriesRecordingDao().episodes().single().status)
        assertEquals(2, db.recordingDao().getAll().first().size)
    }
    @Test fun `a held start is re-armed once the conflict clears`() = runBlocking {
        available = 1
        val selected = epg(); db.epgDao().upsertEpg(listOf(selected)); engine.create(rule(), selected); engine.refresh()
        val rec = db.recordingDao().getAll().first().single()
        val manual = db.recordingDao().insert(RecordingEntity(streamId = 9, channelName = "Manual", scheduledStartMs = selected.startTimestamp, durationMs = 1_800_000, outputPath = "/tmp/keep.ts")).toInt()
        assertNotNull(engine.startRefusal(rec))
        assertEquals("CONFLICT", db.seriesRecordingDao().episodes().single().status)
        db.recordingDao().cancelScheduled(manual)
        val alarms = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        alarms.scheduledAlarms.toList().forEach { alarms.scheduledAlarms.remove(it) }
        engine.refresh()
        assertEquals("SCHEDULED", db.seriesRecordingDao().episodes().single().status)
        assertEquals("SCHEDULED", db.recordingDao().getById(rec.id)!!.status)
        assertTrue(alarms.scheduledAlarms.isNotEmpty())
    }
}
