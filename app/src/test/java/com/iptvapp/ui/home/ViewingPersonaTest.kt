package com.iptvapp.ui.home

import android.app.Application
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.R
import com.iptvapp.data.home.HomeLayout
import com.iptvapp.data.home.HomeLayout.Builtin
import com.iptvapp.data.home.HomeLayout.StartScreen
import com.iptvapp.data.home.HomeLayoutStore
import com.iptvapp.data.home.PersonaPresets
import com.iptvapp.data.home.TvHomeMiniMode
import com.iptvapp.data.home.TvSidebar
import com.iptvapp.data.home.ViewingPersona
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ItemPersonaCardBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Viewing personas: what each preset sets, how it is saved, and the fall back to Custom on a change by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ViewingPersonaTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var prefs: PreferencesManager

    @Before
    fun clean(): Unit = runBlocking {
        prefs = PreferencesManager(context)
        for (tv in listOf(true, false)) {
            prefs.setHomeLayoutJson(tv, null)
            prefs.clearViewingPersona(tv)
        }
        prefs.setTvSidebarConfig(null)
        prefs.setTvHomeMiniPlayerMode(TvHomeMiniMode.HERO.key)
        prefs.clearViewingPersona(true)
    }

    private val presets = ViewingPersona.entries.mapNotNull { PersonaPresets.get(it) }

    @Test
    fun customIsTheDefaultAndSetsNothing() {
        assertEquals(ViewingPersona.CUSTOM, ViewingPersona.fromId(null))
        assertEquals(ViewingPersona.CUSTOM, ViewingPersona.fromId("multiview_wall"))
        assertEquals(ViewingPersona.SPORTS_BAR, ViewingPersona.fromId("SPORTS_BAR"))
        assertNull(PersonaPresets.get(ViewingPersona.CUSTOM))
        assertEquals(ViewingPersona.CUSTOM, runBlocking { prefs.viewingPersona(true).first() })
        assertEquals(ViewingPersona.CUSTOM, runBlocking { prefs.viewingPersona(false).first() })
    }

    @Test
    fun presetsNameOnlyRealSidebarEntriesAndRows() {
        assertEquals(3, presets.size)
        for (p in presets) {
            for (id in p.sidebarFirst + p.sidebarHidden) assertTrue("${p.persona}: $id", TvSidebar.Entry.of(id) != null)
            for (b in p.rowsFirst) assertFalse("${p.persona} hides a row it leads with: $b", b in p.rowsHidden)
            val sidebar = PersonaPresets.sidebar(p)
            // Every entry once, the preset's first, and Home, Favorites, Guide or Live still shown.
            assertEquals(TvSidebar.Entry.entries.map { it.id }.toSet(), sidebar.map { it.id }.toSet())
            assertEquals(TvSidebar.Entry.entries.size, sidebar.size)
            assertEquals(p.sidebarFirst, sidebar.take(p.sidebarFirst.size).map { it.id })
            assertTrue(sidebar.any { it.visible && it.id in TvSidebar.PRIMARY && it.id != "home" })
            assertEquals(p.showHomeTab, TvSidebar.isShown(sidebar, "home"))
            // The TV can start on full-screen live; the phone can't.
            assertTrue(p.phoneStart != StartScreen.FULLSCREEN_LIVE)
            // A hidden Home tab never leaves Home as the start.
            if (!p.showHomeTab) assertTrue(p.tvStart != StartScreen.HOME)
        }
    }

    @Test
    fun classicCableBootsToLiveWithoutHomeOrMiniPlayer(): Unit = runBlocking {
        val layout = HomeLayoutStore.applyPersona(prefs, tv = true, ViewingPersona.CLASSIC_CABLE)
        assertEquals(StartScreen.FULLSCREEN_LIVE, layout.start)
        assertFalse(layout.showHomeTab)
        val saved = HomeLayout.parse(prefs.homeLayoutJson(true).first())
        assertEquals(layout, saved)
        assertEquals(TvHomeMiniMode.OFF.key, prefs.tvHomeMiniPlayerMode.first())
        assertEquals(listOf("live", "guide", "favorites"), prefs.tvSidebarConfig.first().take(3).map { it.id })
        assertEquals(ViewingPersona.CLASSIC_CABLE, prefs.viewingPersona(true).first())
        assertEquals(ViewingPersona.CLASSIC_CABLE, prefs.viewingPersona(true).first())
        // Home's rows lead with live TV; movie rows are hidden.
        assertEquals(listOf("on_now", "recent", "favorites"), saved.sections.take(3).map { it.id })
        assertFalse(saved.section(Builtin.RECENT_MOVIES.id)!!.visible)
        // The phone is a separate layout and keeps its own persona.
        assertEquals(ViewingPersona.CUSTOM, prefs.viewingPersona(false).first())
        assertNull(prefs.homeLayoutJson(false).first())
    }

    @Test
    fun sportsBarUsesTheHeroPlayerAndHidesMoviesAndSeries(): Unit = runBlocking {
        val layout = HomeLayoutStore.applyPersona(prefs, tv = true, ViewingPersona.SPORTS_BAR)
        assertEquals(TvHomeMiniMode.HERO.key, prefs.tvHomeMiniPlayerMode.first())
        assertEquals(StartScreen.HOME, layout.start)
        assertTrue(layout.showHomeTab)
        val sidebar = prefs.tvSidebarConfig.first()
        assertEquals(listOf("home", "live", "sports"), sidebar.take(3).map { it.id })
        assertFalse(TvSidebar.isShown(sidebar, "movies"))
        assertFalse(TvSidebar.isShown(sidebar, "series"))
        assertTrue(TvSidebar.isShown(sidebar, "recordings"))
    }

    @Test
    fun switchingPresetsBackAndForthSettlesOnTheLastOne(): Unit = runBlocking {
        HomeLayoutStore.applyPersona(prefs, true, ViewingPersona.CINEMA_HUB)
        HomeLayoutStore.applyPersona(prefs, true, ViewingPersona.CLASSIC_CABLE)
        val layout = HomeLayoutStore.applyPersona(prefs, true, ViewingPersona.CINEMA_HUB)
        assertEquals(StartScreen.HOME, layout.start)
        assertTrue(layout.showHomeTab)
        assertEquals(TvHomeMiniMode.OFF.key, prefs.tvHomeMiniPlayerMode.first())
        assertEquals(listOf("home", "movies", "series"), prefs.tvSidebarConfig.first().take(3).map { it.id })
        assertEquals(listOf("continue", "recent_movies", "new_episodes"), layout.sections.take(3).map { it.id })
        assertTrue(layout.section(Builtin.RECENT_MOVIES.id)!!.visible)
        assertFalse(layout.section(Builtin.ON_NOW.id)!!.visible)
        assertEquals(ViewingPersona.CINEMA_HUB, prefs.viewingPersona(true).first())
    }

    @Test
    fun rowsTheViewerMadeStayAndKeepTheirOrder(): Unit = runBlocking {
        HomeLayoutStore.update(prefs, true) { HomeLayout.addCustom(it, "a", "Mine A", HomeLayout.Content.CHANNELS) }
        HomeLayoutStore.update(prefs, true) { HomeLayout.addCustom(it, "b", "Mine B", HomeLayout.Content.MOVIES) }
        HomeLayoutStore.update(prefs, true) { HomeLayout.setVisible(it, "custom:a", false) }
        val layout = HomeLayoutStore.applyPersona(prefs, true, ViewingPersona.SPORTS_BAR)
        val ids = layout.sections.map { it.id }
        assertEquals(HomeLayout.default().sections.size + 2, ids.size)
        // The preset's rows first, then the rest as they were: B (added last, so on top) before A.
        assertEquals(PersonaPresets.SPORTS_BAR.rowsFirst.map { it.id }, ids.take(5))
        assertTrue(ids.indexOf("custom:b") < ids.indexOf("custom:a"))
        assertFalse(layout.section("custom:a")!!.visible)
        assertTrue(layout.section("custom:b")!!.visible)
    }

    @Test
    fun aChangeByHandTurnsThePresetIntoCustom(): Unit = runBlocking {
        // A Home row moved.
        HomeLayoutStore.applyPersona(prefs, true, ViewingPersona.SPORTS_BAR)
        HomeLayoutStore.update(prefs, true) { HomeLayout.move(it, "recent", -1) }
        assertEquals(ViewingPersona.CUSTOM, prefs.viewingPersona(true).first())

        // A move that changes nothing (the top row up) keeps the preset.
        HomeLayoutStore.applyPersona(prefs, true, ViewingPersona.SPORTS_BAR)
        HomeLayoutStore.update(prefs, true) { HomeLayout.move(it, "on_now", -1) }
        assertEquals(ViewingPersona.SPORTS_BAR, prefs.viewingPersona(true).first())

        // A sidebar entry hidden.
        prefs.setTvSidebarConfig(TvSidebar.setVisible(prefs.tvSidebarConfig.first(), "search", false))
        assertEquals(ViewingPersona.CUSTOM, prefs.viewingPersona(true).first())

        // The mini player: the same mode keeps the preset, another one doesn't.
        HomeLayoutStore.applyPersona(prefs, true, ViewingPersona.SPORTS_BAR)
        prefs.setTvHomeMiniPlayerMode(TvHomeMiniMode.HERO.key)
        assertEquals(ViewingPersona.SPORTS_BAR, prefs.viewingPersona(true).first())
        prefs.setTvHomeMiniPlayerMode(TvHomeMiniMode.COMPACT.key)
        assertEquals(ViewingPersona.CUSTOM, prefs.viewingPersona(true).first())

        // The start screen.
        HomeLayoutStore.applyPersona(prefs, true, ViewingPersona.SPORTS_BAR)
        HomeLayoutStore.update(prefs, true) { HomeLayout.setStart(it, StartScreen.GUIDE) }
        assertEquals(ViewingPersona.CUSTOM, prefs.viewingPersona(true).first())

        // Picking Custom keeps every setting.
        HomeLayoutStore.applyPersona(prefs, true, ViewingPersona.CLASSIC_CABLE)
        val before = prefs.homeLayoutJson(true).first()
        HomeLayoutStore.applyPersona(prefs, true, ViewingPersona.CUSTOM)
        assertEquals(ViewingPersona.CUSTOM, prefs.viewingPersona(true).first())
        assertEquals(before, prefs.homeLayoutJson(true).first())
        assertEquals(TvHomeMiniMode.OFF.key, prefs.tvHomeMiniPlayerMode.first())
    }

    @Test
    fun onAPhoneOnlyRowsAndTheStartChange(): Unit = runBlocking {
        val tvSidebar = prefs.tvSidebarConfig.first()
        val layout = HomeLayoutStore.applyPersona(prefs, tv = false, ViewingPersona.CLASSIC_CABLE)
        assertEquals(StartScreen.LIVE, layout.start)
        // The phone has no sidebar Home tab; it stays as it was.
        assertTrue(layout.showHomeTab)
        assertEquals(ViewingPersona.CLASSIC_CABLE, prefs.viewingPersona(false).first())
        // The TV's settings are untouched.
        assertEquals(ViewingPersona.CUSTOM, prefs.viewingPersona(true).first())
        assertEquals(tvSidebar, prefs.tvSidebarConfig.first())
        assertEquals(TvHomeMiniMode.HERO.key, prefs.tvHomeMiniPlayerMode.first())
        assertNull(prefs.homeLayoutJson(true).first())
    }

    @Test
    fun aPersonaCardIsOneFocusableButton() {
        context.setTheme(R.style.Theme_IptvApp)
        val card = ItemPersonaCardBinding.inflate(LayoutInflater.from(context))
        assertTrue(card.root.isFocusable)
        assertTrue(card.root.isClickable)
        assertEquals(context.getString(R.string.persona_active), card.tvPersonaActive.text.toString())
        for (p in ViewingPersona.entries) {
            assertTrue(context.getString(p.titleRes).isNotBlank())
            assertTrue(context.getString(p.tvDescRes).isNotBlank())
            assertTrue(context.getString(p.phoneDescRes).isNotBlank())
        }
    }
}
