package com.iptvapp.data.repository

import com.iptvapp.util.XmltvChannel
import com.iptvapp.util.XmltvProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validation and channel matching. No database: these decide whether a download may replace a guide. */
class XmltvGuideValidationTest {

    private val start = System.currentTimeMillis() / 1000 + 3_600
    private val stop = start + 3_600

    @Test
    fun usableRequiresChannelTitleAndARealWindow() {
        val good = XmltvProgram("news.us", "Nightly", "d", start, stop)
        assertTrue(XmltvEpgRefresh.usable(good))
        assertFalse(XmltvEpgRefresh.usable(good.copy(channelId = " ")))
        assertFalse(XmltvEpgRefresh.usable(good.copy(title = "")))
        assertFalse(XmltvEpgRefresh.usable(good.copy(startSec = 0)))
        assertFalse(XmltvEpgRefresh.usable(good.copy(stopSec = start)))
        assertFalse(XmltvEpgRefresh.usable(good.copy(stopSec = start - 1)))
    }

    @Test
    fun normalizeStripsQualityAndRegionWords() {
        assertEquals("news", XmltvEpgRefresh.normalizeForMatch("The US News HD"))
        assertEquals("sports", XmltvEpgRefresh.normalizeForMatch("Sports East"))
    }

    @Test
    fun primaryMatchPrefersEpgIdAndSkipsAmbiguousNames() {
        val channels = listOf(
            XmltvLocalChannel(7, "News HD", "news.us"),
            XmltvLocalChannel(8, "ESPN", null),
            XmltvLocalChannel(9, "ESPN", null)
        )
        val (byId, byName) = XmltvEpgRefresh.primaryLookups(channels)
        val programs = listOf(
            XmltvProgram("news.us", "Nightly", "", start, stop),
            XmltvProgram("espn.1", "Game", "", start, stop)
        )
        val channelsXml = listOf(
            XmltvChannel("news.us", "Something Else"),
            XmltvChannel("espn.1", "ESPN")
        )
        val rows = XmltvEpgRefresh.matchPrimary(channelsXml, programs, byId, byName)
        assertEquals(listOf(7), rows.map { it.streamId }.distinct())
        assertEquals("Nightly", rows.single().title)
        assertEquals(-1, rows.single().serverIndex)
        assertFalse(rows.single().id.contains("_7_"))
    }

    @Test
    fun primaryNameFallbackClaimsAUniqueChannel() {
        val channels = listOf(XmltvLocalChannel(8, "Sports Net HD", null))
        val (byId, byName) = XmltvEpgRefresh.primaryLookups(channels)
        val rows = XmltvEpgRefresh.matchPrimary(
            listOf(XmltvChannel("sport.1", "Sports Net")),
            listOf(XmltvProgram("sport.1", "Live", "", start, stop)),
            byId,
            byName
        )
        assertEquals(8, rows.single().streamId)
    }

    @Test
    fun programsThatAlreadyEndedDoNotClaimAChannel() {
        val channels = listOf(XmltvLocalChannel(7, "News", "news.us"))
        val (byId, byName) = XmltvEpgRefresh.primaryLookups(channels)
        val pastStop = System.currentTimeMillis() / 1000 - 60
        val rows = XmltvEpgRefresh.matchPrimary(
            listOf(XmltvChannel("news.us", "News")),
            listOf(XmltvProgram("news.us", "Yesterday", "", pastStop - 60, pastStop)),
            byId,
            byName
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun mergedMatchCanAttachOneFeedChannelToSeveralStreams() {
        val channels = listOf(
            XmltvLocalChannel(1, "News", "news.us"),
            XmltvLocalChannel(2, "News Plus", "news.us")
        )
        val (byId, byName) = XmltvEpgRefresh.mergedLookups(channels)
        val rows = XmltvEpgRefresh.matchMerged(
            3,
            listOf(XmltvChannel("news.us", "News")),
            listOf(XmltvProgram("news.us", "Nightly", "", start, stop)),
            byId,
            byName
        )
        assertEquals(setOf(1, 2), rows.map { it.streamId }.toSet())
        assertTrue(rows.all { it.serverIndex == 3 })
        assertTrue(rows.all { it.id.contains("_${it.streamId}_") })
    }
}
