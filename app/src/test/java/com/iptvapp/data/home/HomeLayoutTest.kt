package com.iptvapp.data.home

import com.iptvapp.data.home.HomeLayout.Builtin
import com.iptvapp.data.home.HomeLayout.Content
import com.iptvapp.data.home.HomeLayout.ItemRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    private fun ids(l: HomeLayout.Layout) = l.sections.map { it.id }
    private val shipped = listOf("continue", "on_now", "favorites", "recent", "starting_soon", "new_episodes", "recordings", "recommended")

    @Test
    fun firstUseIsHomeAsItShipped() {
        val l = HomeLayout.parse(null)
        assertEquals(HomeLayout.default(), l)
        assertEquals(shipped + listOf("recent_movies", "favorite_movies", "favorite_series"), ids(l))
        // The rows Home always had are shown, and only they: an upgrade looks the same.
        assertEquals(shipped, l.sections.filter { it.visible }.map { it.id })
        assertTrue(l.sections.none { it.showWhenEmpty })
        assertEquals(HomeLayout.StartScreen.HOME, l.start)
    }

    @Test
    fun aLayoutSavedBeforeNewRowsExistedKeepsItsOrderAndGetsThemHidden() {
        val old = """{"schema":1,"start":"live","sections":[
            {"id":"recordings","visible":true,"style":"WIDE"},
            {"id":"favorites","visible":false,"style":"COMPACT"},
            {"id":"continue","visible":true,"style":"POSTER"}]}"""
        val l = HomeLayout.parse(old)
        assertEquals(listOf("recordings", "favorites", "continue"), ids(l).take(3))
        assertEquals(HomeLayout.Style.COMPACT, l.section("favorites")!!.style)
        assertFalse(l.section("favorites")!!.visible)
        // Rows it didn't know: after its own, as shipped (old ones shown, new ones hidden).
        assertTrue(l.section("on_now")!!.visible)
        assertFalse(l.section("recent_movies")!!.visible)
        assertEquals(Builtin.entries.size, l.sections.size)
        assertEquals(HomeLayout.StartScreen.LIVE, l.start)
    }

    @Test
    fun movingRowsChangesOnlyTheOrderAndStopsAtTheEnds() {
        var l = HomeLayout.default()
        l = HomeLayout.move(l, "recordings", -1)
        assertEquals(listOf("recordings", "new_episodes"), ids(l).subList(5, 7))
        l = HomeLayout.move(HomeLayout.default(), "continue", -1)
        assertEquals("continue", ids(l).first())
        l = HomeLayout.move(l, "favorite_series", 5)
        assertEquals("favorite_series", ids(l).last())
        l = HomeLayout.moveTo(HomeLayout.default(), "recommended", 0)
        assertEquals("recommended", ids(l).first())
        assertEquals(HomeLayout.default().sections.toSet(), l.sections.toSet())
        assertEquals(l, HomeLayout.move(l, "no-such-row", 1))
    }

    @Test
    fun hiddenRowsCanBeShownAgain() {
        var l = HomeLayout.setVisible(HomeLayout.default(), "on_now", false)
        assertFalse(l.section("on_now")!!.visible)
        l = HomeLayout.setVisible(l, "on_now", true)
        assertTrue(l.section("on_now")!!.visible)
        assertEquals(HomeLayout.default(), l)
    }

    @Test
    fun rowsPeopleMakeCanBeRenamedAndDeletedButBuiltInRowsOnlyHidden() {
        var l = HomeLayout.addCustom(HomeLayout.default(), "a", "  My   Sports ", Content.CHANNELS)
        val s = l.sections.first()
        assertEquals("custom:a", s.id)
        assertEquals("My Sports", s.displayTitle)
        assertTrue(s.visible && s.showWhenEmpty)
        assertEquals(HomeLayout.Style.WIDE, s.style)
        l = HomeLayout.rename(l, "custom:a", "Game Day")
        assertEquals("Game Day", l.section("custom:a")!!.displayTitle)
        assertEquals(l, HomeLayout.rename(l, "custom:a", "   "))
        assertEquals(l, HomeLayout.rename(l, "favorites", "Mine"))
        assertEquals("x".repeat(HomeLayout.MAX_TITLE), HomeLayout.rename(l, "custom:a", "x".repeat(99)).section("custom:a")!!.title)
        assertEquals(l, HomeLayout.delete(l, "favorites"))
        l = HomeLayout.delete(l, "custom:a")
        assertNull(l.section("custom:a"))
        assertEquals(HomeLayout.default(), l)
    }

    @Test
    fun pickedItemsKeepTheirOwnOrderAndTypeAndTheSameIdOnTwoProvidersIsTwoItems() {
        val main5 = ItemRef(Content.CHANNELS, -1, 5)
        val other5 = ItemRef(Content.CHANNELS, 1, 5)
        val movie = ItemRef(Content.MOVIES, -1, 5)
        var l = HomeLayout.addCustom(HomeLayout.default(), "s", "Sports", Content.CHANNELS)
        l = HomeLayout.addItem(l, "custom:s", main5)
        l = HomeLayout.addItem(l, "custom:s", other5)
        l = HomeLayout.addItem(l, "custom:s", main5)   // already there
        l = HomeLayout.addItem(l, "custom:s", movie)   // a film in a channel row
        l = HomeLayout.addItem(l, "favorites", main5)  // not a picked row
        assertEquals(listOf(main5, other5), l.section("custom:s")!!.items)
        assertTrue(l.section("favorites")!!.items.isEmpty())
        l = HomeLayout.moveItem(l, "custom:s", other5, 0)
        assertEquals(listOf(other5, main5), l.section("custom:s")!!.items)
        l = HomeLayout.removeItem(l, "custom:s", other5)
        assertEquals(listOf(main5), l.section("custom:s")!!.items)
        var full = HomeLayout.addCustom(HomeLayout.default(), "f", "Full", Content.MOVIES)
        repeat(HomeLayout.MAX_ITEMS + 5) { full = HomeLayout.addItem(full, "custom:f", ItemRef(Content.MOVIES, -1, it)) }
        assertEquals(HomeLayout.MAX_ITEMS, full.section("custom:f")!!.items.size)
    }

    @Test
    fun aLayoutSurvivesBeingSavedAndReadBack() {
        var l = HomeLayout.addCustom(HomeLayout.default(), "s", "Sports", Content.CHANNELS)
        l = HomeLayout.addItem(l, "custom:s", ItemRef(Content.CHANNELS, 2, 77))
        l = HomeLayout.addItem(l, "custom:s", ItemRef(Content.CHANNELS, -1, 77))
        l = HomeLayout.addCustom(l, "m", "Action", Content.MOVIES, HomeLayout.Filter(-1, "12", "Action"))
        l = HomeLayout.addCustom(l, "u", "Uncategorised", Content.CHANNELS, HomeLayout.Filter(0, null, "Other"))
        l = HomeLayout.setStyle(l, "custom:m", HomeLayout.Style.WIDE)
        l = HomeLayout.setVisible(l, "recordings", false)
        l = HomeLayout.setShowWhenEmpty(l, "favorites", true)
        l = HomeLayout.move(l, "continue", 3)
        l = HomeLayout.setStart(l, HomeLayout.StartScreen.MOVIES)
        val json = HomeLayout.toJson(l)
        assertEquals(l, HomeLayout.parse(json))
        // Arrangement only: no names, addresses or credentials.
        assertFalse(json.contains("http") || json.contains("password") || json.contains("username"))
    }

    @Test
    fun aBrokenSavedLayoutKeepsWhatItCanAndNeverThrows() {
        for (bad in listOf("", "not json", "[]", "{}", "{\"sections\":7}", "{\"sections\":[1,2,\"x\"]}")) {
            assertEquals(bad, HomeLayout.default().sections, HomeLayout.parse(bad).sections)
        }
        val messy = """{"start":"nowhere","sections":[
            {"id":"favorites","visible":"yes","style":"POSTER"},
            {"id":"favorites","visible":false},
            {"id":"from_the_future","visible":true},
            {"id":"custom:","content":"CHANNELS"},
            {"id":"custom:ok","title":"Mine","content":"CHANNELS","items":["CHANNELS:-1:4","CHANNELS:x:1","MOVIES:-1:2","CHANNELS:-5:3",7,"CHANNELS:1:4","CHANNELS:-1:4"]},
            {"id":"custom:films","content":"MOVIES","items":["MOVIES:-1:9","MOVIES:3:9"]},
            {"id":"custom:weird","content":"RECORDINGS"},
            {"id":"custom:cat","content":"SERIES","filter":{"serverIndex":2,"categoryName":"Drama"}},
            {"id":"recent","visible":false,"style":"POSTER"}]}"""
        val l = HomeLayout.parse(messy)
        assertEquals(HomeLayout.StartScreen.HOME, l.start)
        // The first copy of a row wins; a style a row can't use falls back to its own.
        val fav = l.section("favorites")!!
        assertTrue(fav.visible)
        assertEquals(HomeLayout.Style.WIDE, fav.style)
        assertNull(l.section("from_the_future"))
        assertNull(l.section("custom:"))
        assertNull(l.section("custom:weird"))
        assertEquals(listOf(ItemRef(Content.CHANNELS, -1, 4), ItemRef(Content.CHANNELS, 1, 4)), l.section("custom:ok")!!.items)
        // Films come from the main provider only.
        assertEquals(listOf(ItemRef(Content.MOVIES, -1, 9)), l.section("custom:films")!!.items)
        // A series category row on another provider isn't something MKTV makes: kept as a picked row.
        assertNull(l.section("custom:cat")!!.filter)
        assertFalse(l.section("recent")!!.visible)
        assertEquals(HomeLayout.Style.WIDE, l.section("recent")!!.style)
        assertEquals(Builtin.entries.size + 3, l.sections.size)
    }

    @Test
    fun resetPutsHomeBackAndDropsOnlyTheRowsPeopleMade() {
        var l = HomeLayout.addCustom(HomeLayout.default(), "s", "Sports", Content.CHANNELS)
        l = HomeLayout.setVisible(HomeLayout.move(l, "recordings", -4), "on_now", false)
        l = HomeLayout.setStart(l, HomeLayout.StartScreen.GUIDE)
        val reset = HomeLayout.reset(l)
        assertEquals(HomeLayout.default().sections, reset.sections)
        assertEquals(HomeLayout.StartScreen.GUIDE, reset.start)
    }

    @Test
    fun onlyStylesThatSuitARowAreOffered() {
        assertEquals(listOf(HomeLayout.Style.WIDE, HomeLayout.Style.COMPACT), HomeLayout.styles(Content.CHANNELS))
        assertEquals(listOf(HomeLayout.Style.POSTER, HomeLayout.Style.WIDE), HomeLayout.styles(Content.MOVIES))
        val l = HomeLayout.setStyle(HomeLayout.default(), "favorites", HomeLayout.Style.POSTER)
        assertEquals(HomeLayout.Style.WIDE, l.section("favorites")!!.style)
        assertEquals(HomeLayout.Style.COMPACT, HomeLayout.setStyle(l, "favorites", HomeLayout.Style.COMPACT).section("favorites")!!.style)
    }

    @Test
    fun removingAProviderMovesItsLaterNeighboursItemsAndDropsItsOwn() {
        var l = HomeLayout.addCustom(HomeLayout.default(), "s", "Sports", Content.CHANNELS)
        for (server in listOf(-1, 0, 1, 2)) l = HomeLayout.addItem(l, "custom:s", ItemRef(Content.CHANNELS, server, 5))
        l = HomeLayout.addCustom(l, "c0", "Cat on 1", Content.CHANNELS, HomeLayout.Filter(1, "9", "News"))
        l = HomeLayout.addCustom(l, "c2", "Cat on 2", Content.CHANNELS, HomeLayout.Filter(2, "9", "News"))
        // Provider 1 removed: 0 stays, 2 becomes 1.
        val moved = mapOf(0 to 0, 2 to 1)
        val after = HomeLayout.reindexProviders(l, moved, primaryChanged = false)
        assertEquals(listOf(-1, 0, 1), after.section("custom:s")!!.items.map { it.serverIndex })
        assertTrue(after.section("custom:c0")!!.filter!!.gone)
        assertEquals(1, after.section("custom:c2")!!.filter!!.serverIndex)
        assertTrue(after.section("custom:c0")!!.emptyText.contains("provider was removed"))
        // A different main login: its channels go, the other providers' stay.
        val login = HomeLayout.reindexProviders(l, null, primaryChanged = true)
        assertEquals(listOf(0, 1, 2), login.section("custom:s")!!.items.map { it.serverIndex })
        // Still gone after another change, and survives saving.
        val again = HomeLayout.parse(HomeLayout.toJson(HomeLayout.reindexProviders(after, mapOf(0 to 0, 1 to 1), false)))
        assertTrue(again.section("custom:c0")!!.filter!!.gone)
        // Never customized: nothing to write.
        assertNull(HomeLayout.reindexJson(null, moved, false))
        val json = HomeLayout.toJson(HomeLayout.default())
        assertTrue(json === HomeLayout.reindexJson(json, moved, true))
    }
}
