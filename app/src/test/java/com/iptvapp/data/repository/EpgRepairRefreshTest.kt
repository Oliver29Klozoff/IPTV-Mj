package com.iptvapp.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.XmltvGuideWriter
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.data.local.entities.EpgOverrideEntity
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.util.XmltvChannel
import com.iptvapp.util.XmltvProgram
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * EPG corrections in the real refresh path: the guide rows every screen reads are the corrected
 * ones, a refresh keeps them, a failed one destroys nothing, and recordings are left alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EpgRepairRefreshTest {

    private val start = System.currentTimeMillis() / 1000 + 600
    private lateinit var db: IptvDatabase
    private lateinit var writer: XmltvGuideWriter
    private lateinit var refresh: XmltvEpgRefresh

    private val provider = "http://provider/xmltv.php"
    private val custom = "http://custom/guide.xml"
    private val keyOf = { url: String -> if (url == provider) "kProv" else if (url == custom) "kCustom" else "kOther" }

    // The provider guide has "AMC" (East) only; the custom guide has both feeds.
    private val providerFeed = feed(listOf("amc" to "AMC", "news" to "News"))
    private val customFeed = feed(listOf("amc.east" to "AMC East", "amc.west" to "AMC West"))

    private val amc = XmltvLocalChannel(7, "AMC HD", null)
    private val news = XmltvLocalChannel(8, "News", "news")

    private fun feed(channels: List<Pair<String, String>>): Pair<List<XmltvChannel>, List<XmltvProgram>> =
        channels.map { XmltvChannel(it.first, it.second) } to channels.flatMap { (id, name) ->
            (0 until 3).map { i -> XmltvProgram(id, "$name show $i", "", start + i * 3600L, start + (i + 1) * 3600L) }
        }

    private fun rulesFor(vararg o: EpgRefreshRules.Rule) = EpgRefreshRules(o.toList(), keyOf)
    private fun mapTo(stream: Int, source: String, id: String, offset: Int = 0) = EpgRefreshRules.Rule(stream, true, source, id, offset)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IptvDatabase::class.java)
            .allowMainThreadQueries().build()
        writer = XmltvGuideWriter(db)
        refresh = XmltvEpgRefresh(writer)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun primary(
        rules: EpgRefreshRules = EpgRefreshRules.NONE,
        fetch: suspend (String) -> Pair<List<XmltvChannel>, List<XmltvProgram>> = { url -> if (url == provider) providerFeed else customFeed },
        sources: List<String> = listOf(provider),
    ) = refresh.loadPrimary(sources, backupUrl = null, channels = listOf(amc, news), fetch = fetch, stillCurrent = { true }, rules = rules)

    private suspend fun titles(stream: Int, server: Int = -1) =
        db.epgDao().getEpgForStream(stream, server).first().map { it.title }

    // 14 + 26–28: a correction beats automatic matching, in what every screen reads.
    @Test
    fun aCorrectionBeatsAutomaticMatchingEverywhereTheGuideIsRead() = runBlocking {
        primary()
        assertEquals(listOf("AMC show 0", "AMC show 1", "AMC show 2"), titles(7)) // automatic: by name
        // The user picks AMC West from a guide this refresh doesn't read (the stand-in, used only
        // when the others fail): it is fetched afterwards, for that channel alone.
        refresh.loadPrimary(listOf(provider), backupUrl = custom, channels = listOf(amc, news), fetch = { url -> if (url == provider) providerFeed else customFeed }, stillCurrent = { true }, rules = rulesFor(mapTo(7, "kCustom", "AMC.WEST")))
        assertEquals(listOf("AMC West show 0", "AMC West show 1", "AMC West show 2"), titles(7))
        // Full TV guide / Quick Peek read several channels at once; the player reads one.
        val many = db.epgDao().getEpgForStreams(listOf(7, 8), -1).first()
        assertTrue(many.filter { it.streamId == 7 }.all { it.title.startsWith("AMC West") })
        val between = db.epgDao().getProgramsBetween(listOf(7), -1, start * 1000, (start + 7200) * 1000)
        assertTrue(between.isNotEmpty() && between.all { it.title.startsWith("AMC West") })
        // Another channel's automatic match is untouched.
        assertEquals(3, titles(8).size)
    }

    // 15: without the correction, the next refresh matches automatically again.
    @Test
    fun removingTheCorrectionRestoresAutomaticMatching() = runBlocking {
        primary(rulesFor(mapTo(7, "kCustom", "amc.west")), sources = listOf(provider, custom))
        assertTrue(titles(7).all { it.startsWith("AMC West") })
        primary(sources = listOf(provider, custom))
        // Automatic matching again (by name, from each guide); the chosen West feed is gone.
        val after = titles(7)
        assertTrue(after.contains("AMC show 0"))
        assertTrue(after.none { it.startsWith("AMC West") })
    }

    // 21: a refresh keeps (and re-applies) corrections, including an offset.
    @Test
    fun aRefreshKeepsCorrectionsAndOffsets() = runBlocking {
        val rule = mapTo(7, "kCustom", "amc.west", offset = 60)
        primary(rulesFor(rule), sources = listOf(provider, custom))
        primary(rulesFor(rule), sources = listOf(provider, custom))
        val rows = db.epgDao().getEpgForStream(7, -1).first()
        assertEquals(3, rows.size)
        assertEquals(start + 3600, rows.first().startTimestamp)
        // The row id keeps the source's own start, so an offset never duplicates a listing.
        assertTrue(rows.first().id.endsWith("_7_$start"))
    }

    // 22: a failed source destroys nothing, corrected channel included.
    @Test
    fun aFailedRefreshKeepsTheGuideAndTheCorrectedChannel() = runBlocking {
        primary(rulesFor(mapTo(7, "kCustom", "amc.west")), sources = listOf(provider, custom))
        val before = db.epgDao().getEpgForStreams(listOf(7, 8), -1).first().map { it.id }.sorted()
        // The custom guide fails: the provider guide still replaces what it matched, and AMC keeps its West listings.
        primary(rulesFor(mapTo(7, "kCustom", "amc.west")), fetch = { url -> if (url == custom) throw IOException("down") else providerFeed }, sources = listOf(provider, custom))
        assertTrue(titles(7).all { it.startsWith("AMC West") })
        // Every source fails: nothing changes at all.
        primary(rulesFor(mapTo(7, "kCustom", "amc.west")), fetch = { throw IOException("down") }, sources = listOf(provider, custom))
        assertEquals(before, db.epgDao().getEpgForStreams(listOf(7, 8), -1).first().map { it.id }.sorted())
    }

    @Test
    fun aCorrectionFromASourceNotReadThisTimeKeepsItsRowsWhenThatSourceFails() = runBlocking {
        primary(rulesFor(mapTo(7, "kCustom", "amc.west")), sources = listOf(provider, custom))
        // The refresh reads only the provider guide; the correction's custom guide fails when fetched for it.
        refresh.loadPrimary(listOf(provider), backupUrl = custom, channels = listOf(amc, news), fetch = { url -> if (url == custom) throw IOException("down") else providerFeed }, stillCurrent = { true }, rules = rulesFor(mapTo(7, "kCustom", "amc.west")))
        assertTrue(titles(7).isNotEmpty() && titles(7).all { it.startsWith("AMC West") })
    }

    // 7: the same stream id on another provider is another channel.
    @Test
    fun aCorrectionOnOneProviderLeavesTheSameStreamIdOnAnotherAlone() = runBlocking {
        primary()
        refresh.loadMerged(
            0, listOf(custom), null, false, listOf(XmltvLocalChannel(7, "AMC West", null)),
            fetch = { customFeed }, stillCurrent = { true }, rules = rulesFor(mapTo(7, "kCustom", "amc.east"))
        )
        assertTrue(titles(7, server = 0).all { it.startsWith("AMC East") })
        assertTrue(titles(7, server = -1).all { it.startsWith("AMC show") })
    }

    // 23: corrections loaded while a full refresh runs: both land, nothing duplicated.
    @Test
    fun correctionsAndAFullRefreshAtOnceStayConsistent() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val rules = rulesFor(mapTo(7, "kCustom", "amc.west"))
        val full = async {
            refresh.loadPrimary(listOf(provider), null, listOf(amc, news), fetch = { gate.await(); providerFeed }, stillCurrent = { true }, rules = rules)
        }
        val one = async {
            refresh.loadCorrections(-1, null, rules, emptySet(), listOf(provider, custom), fetch = { customFeed }, stillCurrent = { true })
        }
        gate.complete(Unit)
        listOf(full, one).awaitAll()
        val rows = db.epgDao().getEpgForStream(7, -1).first()
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.title.startsWith("AMC West") })
        assertEquals(3, titles(8).size)
    }

    // A guide that matches only corrected channels is not the provider's guide.
    @Test
    fun aGuideMatchingOnlyACorrectedChannelReplacesNothingElse() = runBlocking {
        primary(sources = listOf(provider))
        assertEquals(3, titles(8).size)
        val westOnly = feed(listOf("amc.west" to "AMC West"))
        refresh.loadPrimary(
            listOf(custom), backupUrl = null, channels = listOf(amc, news), fetch = { westOnly }, stillCurrent = { true },
            rules = rulesFor(mapTo(7, "kCustom", "amc.west"))
        )
        assertTrue(titles(7).isNotEmpty() && titles(7).all { it.startsWith("AMC West") })
        // News kept its listings: the correction-only guide did not replace the slot.
        assertEquals(3, titles(8).size)
    }

    @Test
    fun anotherProvidersCorrectionOnlySourceDoesNotStopItsRealGuide() = runBlocking {
        val westOnly = feed(listOf("amc.west" to "AMC West"))
        val wrote = refresh.loadMerged(
            0, listOf(custom, provider), null, false, listOf(amc, news),
            fetch = { url -> if (url == custom) westOnly else providerFeed }, stillCurrent = { true },
            rules = rulesFor(mapTo(7, "kCustom", "amc.west"))
        )
        assertTrue(wrote > 0)
        assertTrue(titles(7, server = 0).isNotEmpty() && titles(7, server = 0).all { it.startsWith("AMC West") })
        // From the provider guide, tried after the correction-only one, which didn't stop the search.
        assertEquals(3, titles(8, server = 0).size)
    }

    // A refresh that started with the old corrections must not overwrite a newer one.
    @Test
    fun aRefreshStartedBeforeACorrectionCannotOverwriteIt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val old = async {
            refresh.loadPrimary(listOf(provider), null, listOf(amc, news), fetch = { started.complete(Unit); gate.await(); providerFeed }, stillCurrent = { true })
        }
        started.await()
        // Meanwhile the user saves a correction: the slot's refresh is invalidated, then the correction loads.
        writer.begin(-1)
        refresh.loadCorrections(-1, null, rulesFor(mapTo(7, "kCustom", "amc.west")), emptySet(), listOf(custom), fetch = { customFeed }, stillCurrent = { true })
        gate.complete(Unit)
        old.await()
        assertTrue(titles(7).isNotEmpty() && titles(7).all { it.startsWith("AMC West") })
    }

    // Offsets move existing rows now, with the "now playing" flag.
    @Test
    fun anOffsetMovesExistingListings() = runBlocking {
        primary()
        val moved = db.epgRepairDao().shiftXmltvRows(-1, 7, 3600, nowSec = start + 3600 + 1)
        assertEquals(3, moved)
        val rows = db.epgDao().getEpgForStream(7, -1).first()
        assertEquals(start + 3600, rows.first().startTimestamp)
        assertEquals(1, rows.first().nowPlaying)
        // The provider's own short-guide rows are not XMLTV and don't move.
        db.epgDao().upsertEpg(listOf(EpgEntity(-1, "12345", 9, "Short", "", start, start + 60, 0, 0)))
        db.epgRepairDao().shiftXmltvRows(-1, 9, 3600)
        assertEquals(start, db.epgDao().getEpgForStream(9, -1).first().single().startTimestamp)
    }

    // A corrected channel loses the provider's own short-guide rows (its old mapping); its XMLTV rows stay.
    @Test
    fun aCorrectedChannelsShortGuideRowsGo() = runBlocking {
        primary()
        db.epgDao().upsertEpg(listOf(EpgEntity(-1, "98765", 7, "Old feed", "", start, start + 60, 0, 0)))
        assertEquals(1, db.epgRepairDao().deleteShortGuideRows(-1, 7))
        assertTrue(titles(7).isNotEmpty() && titles(7).none { it == "Old feed" })
    }

    // 29: corrections and offsets never touch scheduled recordings.
    @Test
    fun recordingsAreNotChanged() = runBlocking {
        val rec = RecordingEntity(streamId = 7, channelName = "AMC HD", scheduledStartMs = (start + 7200) * 1000, durationMs = 3_600_000, outputPath = "/r.ts", serverIndex = -1)
        db.recordingDao().insert(rec)
        primary(rulesFor(mapTo(7, "kCustom", "amc.west", offset = -30)), sources = listOf(provider, custom))
        db.epgRepairDao().shiftXmltvRows(-1, 7, -1800)
        val after = db.recordingDao().getAll().first().single()
        assertEquals(rec.scheduledStartMs, after.scheduledStartMs)
        assertEquals(rec.durationMs, after.durationMs)
        assertEquals(7, after.streamId)
        assertEquals(1, db.epgRepairDao().futureRecordings(-1, 7, System.currentTimeMillis()))
    }

    // 13: corrections are rows in the database, read back as saved.
    @Test
    fun correctionsAreStoredAndReplaced() = runBlocking {
        val dao = db.epgRepairDao()
        val o = EpgOverrideEntity(-1, 7, "p", "MAP", "kCustom", "amc.west", "AMC HD", "AMC West", 30, 1, 1)
        dao.upsertOverride(o)
        dao.upsertOverride(o.copy(serverIndex = 0)) // same stream id, other provider
        assertEquals(o, dao.getOverride(-1, 7))
        dao.upsertOverride(o.copy(offsetMinutes = 0, updatedAt = 2))
        assertEquals(0, dao.getOverride(-1, 7)!!.offsetMinutes)
        dao.deleteOverride(-1, 7)
        assertEquals(null, dao.getOverride(-1, 7))
        assertEquals(1, dao.getOverrides().size)
    }

    @Test
    fun rowIdsNameTheirGuideChannel() {
        assertEquals("amc.east", XmltvEpgRefresh.guideChannelOfRow("x_amc.east_1700000000", 7))
        assertEquals("amc.west", XmltvEpgRefresh.guideChannelOfRow("x_amc.west_7_1700000000", 7))
        assertEquals("ch_with_underscores", XmltvEpgRefresh.guideChannelOfRow("x_ch_with_underscores_1700000000", 7))
        assertEquals(null, XmltvEpgRefresh.guideChannelOfRow("12345", 7))
    }
}
