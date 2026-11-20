package com.iptvapp.ui.home

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.R
import com.iptvapp.data.home.TvSidebar
import com.iptvapp.data.home.TvSidebar.Entry
import com.iptvapp.data.home.TvSidebar.SidebarItem
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ActivityTvHomeBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Customize Sidebar: what is saved (TvSidebar, PreferencesManager) and the TV sidebar laid out from it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvSidebarCustomizationTest {

    private val ids get() = Entry.entries.map { it.id }

    @Test
    fun theDefaultIsMktvsOwnOrderAllShown() {
        val d = TvSidebar.default()
        assertEquals(
            listOf("home", "favorites", "providers", "live", "categories", "movies", "series", "guide", "sports", "search", "voice", "recordings"),
            d.map { it.id },
        )
        assertTrue(d.all { it.visible })
        // Nothing saved, or something unreadable: the default.
        assertEquals(d, TvSidebar.parse(null))
        assertEquals(d, TvSidebar.parse(""))
        assertEquals(d, TvSidebar.parse("{not json"))
    }

    @Test
    fun aCustomSidebarSurvivesSavingAndReading() {
        var l = TvSidebar.default()
        l = TvSidebar.moveTo(l, "guide", 0)
        for (id in listOf("movies", "series", "recordings")) l = TvSidebar.setVisible(l, id, false)
        val back = TvSidebar.parse(TvSidebar.toJson(l))
        assertEquals(l, back)
        assertEquals("guide", back.first().id)
        assertEquals(listOf("movies", "series", "recordings"), back.filter { !it.visible }.map { it.id })
    }

    @Test
    fun anOldOrOddSaveIsTidiedUp() {
        // Unknown and repeated ids go; entries the save doesn't name come back, shown, in MKTV's place.
        val json = """[{"id":"guide","visible":true},{"id":"grid","visible":false},{"id":"guide","visible":false},
            {"id":"live","visible":false},{"id":"home"}]"""
        val l = TvSidebar.parse(json)
        assertEquals(ids.toSet(), l.map { it.id }.toSet())
        assertEquals(ids.size, l.size)
        // The saved three keep their order; each missing one follows the last entry before it in MKTV's order.
        assertEquals(
            listOf("guide", "live", "home", "favorites", "providers", "categories", "movies", "series", "sports", "search", "voice", "recordings"),
            l.map { it.id },
        )
        assertFalse(TvSidebar.isShown(l, "live"))
        assertTrue(TvSidebar.isShown(l, "home"))
        assertTrue(TvSidebar.isShown(l, "guide"))
    }

    @Test
    fun theLastOfHomeFavoritesGuideAndLiveCantBeHidden() {
        var l = TvSidebar.default()
        for (id in listOf("home", "favorites", "guide")) l = TvSidebar.setVisible(l, id, false)
        assertFalse(TvSidebar.canHide(l, "live"))
        assertEquals(l, TvSidebar.setVisible(l, "live", false))
        // Others still hide freely.
        assertTrue(TvSidebar.canHide(l, "sports"))
        assertFalse(TvSidebar.isShown(TvSidebar.setVisible(l, "sports", false), "sports"))
        // Home's state comes from Customize Home's HOME TAB.
        val withHome = TvSidebar.withHome(l, homeShown = true)
        assertTrue(TvSidebar.canHide(withHome, "live"))
    }

    @Test
    fun movesStopAtTheEnds() {
        val d = TvSidebar.default()
        assertEquals(d, TvSidebar.move(d, "home", -1))
        assertEquals(d, TvSidebar.move(d, "recordings", 1))
        assertEquals(listOf("favorites", "home"), TvSidebar.move(d, "home", 1).take(2).map { it.id })
    }

    @Test
    fun preferencesKeepTheSidebarAcrossARestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = PreferencesManager(context)
        prefs.setTvSidebarConfig(null)
        assertEquals(TvSidebar.default(), prefs.tvSidebarConfig.first())

        val custom = TvSidebar.setVisible(TvSidebar.moveTo(TvSidebar.default(), "guide", 0), "movies", false)
        prefs.setTvSidebarConfig(custom)
        // A new PreferencesManager reads what the old one saved, also synchronously (first frame).
        val again = PreferencesManager(context)
        assertEquals(custom, again.tvSidebarConfig.first())
        assertEquals(custom, again.tvSidebarConfigNow())

        prefs.setTvSidebarConfig(null)
        assertEquals(TvSidebar.default(), again.tvSidebarConfig.first())
    }

    // ── The TV sidebar itself ───────────────────────────────────────────

    private var controller: ActivityController<AppCompatActivity>? = null

    @After
    fun close() { controller?.pause()?.stop()?.destroy() }

    private fun sidebar(): ActivityTvHomeBinding {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.setTheme(R.style.Theme_IptvApp)
        val c = Robolectric.buildActivity(AppCompatActivity::class.java).setup().also { controller = it }
        val activity = c.get()
        activity.setTheme(R.style.Theme_IptvApp)
        val b = ActivityTvHomeBinding.inflate(activity.layoutInflater)
        activity.setContentView(b.root)
        shadowOf(Looper.getMainLooper()).idle()
        return b
    }

    private fun ActivityTvHomeBinding.byId(): Map<String, View> = mapOf(
        "home" to btnTvHome, "favorites" to btnTvFavorites, "providers" to btnTvProviders, "live" to btnTvLive,
        "categories" to btnTvCategories, "movies" to btnTvMovies, "series" to btnTvSeries, "guide" to btnTvGuide,
        "sports" to btnTvSports, "search" to btnTvSearch, "voice" to btnTvVoice, "recordings" to btnTvRecordings,
    )

    @Test
    fun theSidebarFollowsTheSavedOrderAndHidesEntries() {
        val b = sidebar()
        val buttons = b.byId()
        var l = TvSidebar.moveTo(TvSidebar.default(), "guide", 0)
        for (id in listOf("movies", "series", "recordings")) l = TvSidebar.setVisible(l, id, false)
        // Providers is shown here but off in Settings: it stays hidden.
        TvSidebarLayout.apply(b.tvSidebarButtons, buttons, l) { it != "providers" }
        shadowOf(Looper.getMainLooper()).idle()

        val container = b.tvSidebarButtons
        val order = (0 until container.childCount).map { container.getChildAt(it) }
        // Every entry in the saved order, the old Grid placeholder left after them.
        assertEquals(l.map { buttons.getValue(it.id) }, order.take(l.size))
        assertSame(b.btnTvGrid, order.last())
        for (id in listOf("movies", "series", "recordings", "providers")) {
            assertEquals(id, View.GONE, buttons.getValue(id).visibility)
        }
        assertEquals(
            listOf(b.btnTvGuide, b.btnTvHome, b.btnTvFavorites, b.btnTvLive, b.btnTvCategories, b.btnTvSports, b.btnTvSearch, b.btnTvVoice),
            TvSidebarLayout.shown(container),
        )
        // The remote starts at the new top entry.
        assertTrue(b.tvSidebar.requestFocus())
        assertTrue("focus went to ${b.root.findFocus()}", b.btnTvGuide.isFocused)
    }

    @Test
    fun hidingTheFocusedEntryMovesFocusToTheNearestShownOne() {
        val b = sidebar()
        val buttons = b.byId()
        TvSidebarLayout.apply(b.tvSidebarButtons, buttons, TvSidebar.default()) { true }
        assertTrue(b.btnTvMovies.requestFocus())

        TvSidebarLayout.apply(b.tvSidebarButtons, buttons, TvSidebar.setVisible(TvSidebar.default(), "movies", false)) { true }
        shadowOf(Looper.getMainLooper()).idle()
        // The entry under it.
        assertTrue("focus went to ${b.root.findFocus()}", b.btnTvSeries.isFocused)

        // At the bottom, the one above it.
        var l = TvSidebar.default()
        assertTrue(b.btnTvRecordings.requestFocus())
        l = TvSidebar.setVisible(l, "recordings", false)
        TvSidebarLayout.apply(b.tvSidebarButtons, buttons, l) { true }
        assertTrue("focus went to ${b.root.findFocus()}", b.btnTvVoice.isFocused)

        // A moved (still shown) entry keeps its focus.
        assertTrue(b.btnTvGuide.requestFocus())
        TvSidebarLayout.apply(b.tvSidebarButtons, buttons, TvSidebar.moveTo(l, "guide", 0)) { true }
        assertTrue(b.btnTvGuide.isFocused)
        assertSame(b.btnTvGuide, b.tvSidebarButtons.getChildAt(0))
    }
}
