package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsHubTest {
    private fun ch(server: Int, id: Int, name: String, fav: Boolean = false, score: Int? = null) =
        SportsFinder.Channel(server, id, name, fav, score)

    @Test
    fun `the preferred network for the league comes first, on any provider`() {
        val feeds = listOf(
            ch(-1, 1, "TNT", fav = true, score = 95),
            ch(0, 2, "ESPN HD · T-Rex", score = 40),
        )
        val ranked = SportsFinder.rankFeeds("NBA", feeds, mapOf("NBA" to "ESPN"))
        assertEquals(listOf(2, 1), ranked.map { it.streamId })
        // Another league's preference changes nothing here.
        assertEquals(listOf(1, 2), SportsFinder.rankFeeds("NBA", feeds, mapOf("NFL" to "ESPN")).map { it.streamId })
    }

    @Test
    fun `without a preference the copy that plays best wins, then favorites, then the main provider`() {
        val feeds = listOf(
            ch(-1, 1, "ESPN", fav = true, score = 30),
            ch(0, 2, "ESPN · Backup", score = 90),
            ch(1, 3, "ESPN · Other"),
        )
        assertEquals(listOf(2, 3, 1), SportsFinder.rankFeeds("NBA", feeds, emptyMap()).map { it.streamId })
        val unrated = listOf(ch(0, 2, "ESPN · Backup"), ch(-1, 1, "ESPN"), ch(1, 3, "ESPN · Fav", fav = true))
        assertEquals(listOf(3, 1, 2), SportsFinder.rankFeeds("NBA", unrated, emptyMap()).map { it.streamId })
    }

    @Test
    fun `a network is the channel name without its provider`() {
        assertEquals("ESPN HD", ch(0, 2, "ESPN HD · T-Rex").network)
        assertEquals("ESPN", ch(-1, 1, "ESPN").network)
    }

    @Test
    fun `preferred networks survive a round trip and ignore bad lines`() {
        val map = mapOf("NBA" to "ESPN", "College football" to "ABC")
        assertEquals(map, SportsFinder.decodePreferred(SportsFinder.encodePreferred(map)))
        assertEquals(mapOf("NFL" to "FOX"), SportsFinder.decodePreferred("NFL\tFOX\nbroken line\n\tnobody\nNHL\t "))
        assertEquals(emptyMap<String, String>(), SportsFinder.decodePreferred(null))
    }
}
