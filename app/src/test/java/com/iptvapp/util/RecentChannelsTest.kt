package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentChannelsTest {

    private fun ch(id: Int, server: Int = -1, name: String = "Ch $id") = RecentChannel(server, id, name, "http://logo/$id.png")

    private fun pushAll(vararg c: RecentChannel) = c.fold(emptyList<RecentChannel>()) { acc, x -> RecentChannels.push(acc, x) }

    @Test fun newestFirstAndRepeatsMoveToTheFront() {
        val list = pushAll(ch(1), ch(2), ch(3), ch(1))
        assertEquals(listOf(1, 3, 2), list.map { it.streamId })
    }

    @Test fun keepsAtMostSix() {
        val list = pushAll(*(1..9).map { ch(it) }.toTypedArray())
        assertEquals(RecentChannels.CAPACITY, list.size)
        assertEquals(listOf(9, 8, 7, 6, 5, 4), list.map { it.streamId })
    }

    @Test fun sameIdOnAnotherProviderIsAnotherChannel() {
        val list = pushAll(ch(5, server = -1), ch(5, server = 2))
        assertEquals(2, list.size)
        assertEquals(listOf(2, -1), list.map { it.serverIndex })
    }

    @Test fun theChannelOnNowIsLeftOut() {
        // Watched A, then C, now on B: the drawer shows the others, newest first.
        val list = pushAll(ch(1), ch(3), ch(2))
        assertEquals(listOf(3, 1), RecentChannels.available(list, -1, 2).map { it.streamId })
    }

    @Test fun leavingOutUsesTheProviderToo() {
        val list = pushAll(ch(7, server = 1), ch(7, server = -1))
        assertEquals(listOf(1), RecentChannels.available(list, -1, 7).map { it.serverIndex })
    }

    @Test fun showsAtMostFive() {
        val list = pushAll(*(1..6).map { ch(it) }.toTypedArray())
        assertEquals(5, RecentChannels.available(list, -1, 999).size)
        assertEquals(5, RecentChannels.available(list, -1, 6).size)
    }

    @Test fun removeDropsOnlyThatProvidersCopy() {
        val list = pushAll(ch(4, server = -1), ch(4, server = 3))
        assertEquals(listOf(-1), RecentChannels.remove(list, 3, 4).map { it.serverIndex })
    }

    @Test fun jsonRoundTrip() {
        val list = listOf(RecentChannel(2, 44, "News · Backup", null, "Backup"), ch(1))
        assertEquals(list, RecentChannels.fromJson(RecentChannels.toJson(list)))
    }

    @Test fun jsonHoldsOnlyIdsNamesAndLogos() {
        val json = RecentChannels.toJson(listOf(RecentChannel(1, 2, "Two", "http://logo/2.png", "Backup")))
        val keys = com.google.gson.JsonParser.parseString(json).asJsonArray[0].asJsonObject.keySet()
        assertEquals(setOf("serverIndex", "streamId", "name", "iconUrl", "serverNickname"), keys)
    }

    @Test fun damagedJsonReadsAsEmptyOrSkipsBadEntries() {
        assertEquals(emptyList<RecentChannel>(), RecentChannels.fromJson(null))
        assertEquals(emptyList<RecentChannel>(), RecentChannels.fromJson("not json"))
        assertEquals(emptyList<RecentChannel>(), RecentChannels.fromJson("{\"a\":1}"))
        val mixed = """[{"serverIndex":-1,"streamId":3,"name":"Three"},{"name":"no ids"},5]"""
        assertEquals(listOf(3), RecentChannels.fromJson(mixed).map { it.streamId })
    }

    @Test fun initialsForCardsWithoutALogo() {
        assertEquals("E2", RecentChannels.initials("ESPN 2"))
        assertEquals("FN", RecentChannels.initials("fox news"))
        assertEquals("TV", RecentChannels.initials("  "))
    }
}
