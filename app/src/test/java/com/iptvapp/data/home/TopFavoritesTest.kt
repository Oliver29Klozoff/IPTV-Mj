package com.iptvapp.data.home

import com.iptvapp.data.local.entities.WatchLogEntity
import org.junit.Assert.*
import org.junit.Test

class TopFavoritesTest {
    private val a = TopFavorites.ref(-1, 10)
    private val b = TopFavorites.ref(-1, 20)
    private val c = TopFavorites.ref(0, 10) // same stream id, another provider: a different channel
    private val d = TopFavorites.ref(1, 5)

    private fun state(vararg refs: HomeLayout.ItemRef) = TopFavorites.State(refs.toList())

    @Test fun `add keeps order, ignores repeats and stops at the limit`() {
        var s = TopFavorites.State()
        s = TopFavorites.add(s, a)
        s = TopFavorites.add(s, c)
        s = TopFavorites.add(s, a)
        assertEquals(listOf(a, c), s.items)
        var full = TopFavorites.State()
        repeat(TopFavorites.MAX) { full = TopFavorites.add(full, TopFavorites.ref(-1, it)) }
        assertEquals(TopFavorites.MAX, full.items.size)
        assertSame(full, TopFavorites.add(full, TopFavorites.ref(-1, 9999)))
        // Not a channel: not taken.
        assertEquals(s, TopFavorites.add(s, HomeLayout.ItemRef(HomeLayout.Content.MOVIES, -1, 1)))
    }

    @Test fun `remove takes out only that channel`() {
        assertEquals(listOf(a, d), TopFavorites.remove(state(a, c, d), c).items)
    }

    @Test fun `manual moves stop at the ends`() {
        val s = state(a, b, c)
        assertEquals(listOf(b, a, c), TopFavorites.move(s, a, 1).items)
        assertEquals(listOf(a, b, c), TopFavorites.move(s, a, -1).items)
        assertEquals(listOf(c, a, b), TopFavorites.moveTo(s, c, 0).items)
        assertEquals(listOf(a, b, c), TopFavorites.moveTo(s, c, 99).items)
    }

    @Test fun `move left and right skip entries the row doesn't show`() {
        // b is hidden or gone, so on Home d sits right after a.
        val s = state(a, b, d)
        assertEquals(listOf(d, a, b), TopFavorites.moveNextTo(s, d, a, after = false).items)
        assertEquals(listOf(b, d, a), TopFavorites.moveNextTo(s, a, d, after = true).items)
        assertSame(s, TopFavorites.moveNextTo(s, a, c, after = true))
    }

    @Test fun `stored list round-trips and survives damage`() {
        val s = TopFavorites.State(listOf(c, a, d), TopFavorites.Sort.MOST_WATCHED)
        assertEquals(s, TopFavorites.parse(TopFavorites.toJson(s)))
        assertEquals(TopFavorites.State(), TopFavorites.parse(null))
        assertEquals(TopFavorites.State(), TopFavorites.parse("not json"))
        val messy = """{"sort":"sideways","items":["CHANNELS:-1:10","CHANNELS:-1:10","MOVIES:-1:3","CHANNELS:-5:1","CHANNELS:x:1",7,"CHANNELS:0:10"]}"""
        assertEquals(TopFavorites.State(listOf(a, c), TopFavorites.Sort.MANUAL), TopFavorites.parse(messy))
    }

    @Test fun `channels follow their provider or go with it`() {
        val s = state(a, c, d)
        // Provider 0 removed: provider 1 becomes 0.
        assertEquals(listOf(a, TopFavorites.ref(0, 5)), TopFavorites.reindexProviders(s, mapOf(1 to 0), false).items)
        // Another main login: its channels are not this account's.
        assertEquals(listOf(c, d), TopFavorites.reindexProviders(s, null, true).items)
        assertNull(TopFavorites.reindexJson(null, mapOf(1 to 0), false))
        val json = TopFavorites.toJson(s)
        assertSame(json, TopFavorites.reindexJson(json, null, false))
    }

    private fun log(day: String, server: Int, id: Int, minutes: Float) = WatchLogEntity(day, server, id, minutes = minutes)

    @Test fun `most watched counts real viewing, not zapping`() {
        val minutes = TopFavorites.watchedMinutes(listOf(
            log("2026-10-01", -1, 10, 30f),
            log("2026-10-02", -1, 10, 15f),
            // Flicked past b every day: under the threshold each time, so it counts for nothing.
            log("2026-10-01", -1, 20, 1.5f), log("2026-10-02", -1, 20, 1.9f), log("2026-10-03", -1, 20, 0.2f),
            log("2026-10-01", 1, 5, 60f),
        ))
        assertEquals(45f, minutes[-1 to 10]!!, 0.01f)
        assertNull(minutes[-1 to 20])
        // d most, then a; b and c unwatched keep their manual order.
        assertEquals(listOf(d, a, b, c), TopFavorites.rank(listOf(b, c, a, d), minutes))
        assertEquals(listOf(d, a, c, b), TopFavorites.rank(listOf(c, b, a, d), minutes))
    }

    @Test fun `a smart channel group shares its viewing time`() {
        // c is the backup copy of b; most of b's viewing happened on c.
        val minutes = mapOf((-1 to 10) to 40f, (-1 to 20) to 10f, (0 to 10) to 50f)
        val groups = mapOf((-1 to 20) to "g", (0 to 10) to "g")
        assertEquals(listOf(b, a), TopFavorites.rank(listOf(a, b), minutes, groups))
        assertEquals(listOf(a, b), TopFavorites.rank(listOf(a, b), minutes))
    }

    @Test fun `sorting never changes what was picked or its saved order`() {
        val s = state(a, b, c)
        val sorted = TopFavorites.setSort(s, TopFavorites.Sort.MOST_WATCHED)
        assertEquals(s.items, sorted.items)
        assertEquals(s, TopFavorites.setSort(sorted, TopFavorites.Sort.MANUAL))
    }
}
