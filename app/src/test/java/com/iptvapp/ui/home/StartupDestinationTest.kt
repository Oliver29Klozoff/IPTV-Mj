package com.iptvapp.ui.home

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.R
import com.iptvapp.data.home.HomeLayout
import com.iptvapp.data.home.HomeLayout.StartScreen
import com.iptvapp.databinding.ActivityTvHomeBinding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Customize Home's START ON (now with the TV Guide and the last channel full screen) and its
 * HOME TAB switch: what is saved, and the TV sidebar without Home.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class StartupDestinationTest {

    @Test
    fun theNewStartScreensReadBackFromTheirIds() {
        assertEquals(StartScreen.FULLSCREEN_LIVE, StartScreen.of("fullscreen_live"))
        assertEquals(StartScreen.GUIDE, StartScreen.of("guide"))
        // Anything unknown (a newer or broken value) opens Home, as before.
        assertEquals(StartScreen.HOME, StartScreen.of("cable_box"))
        assertEquals(StartScreen.HOME, StartScreen.of(null))
    }

    @Test
    fun aHiddenHomeTabAndItsStartAreSavedWithTheRows() {
        val custom = HomeLayout.addCustom(HomeLayout.default(), "abc", "Sports", HomeLayout.Content.CHANNELS)
        val hidden = HomeLayout.setStart(HomeLayout.setShowHomeTab(custom, false), StartScreen.FULLSCREEN_LIVE)
        val back = HomeLayout.parse(HomeLayout.toJson(hidden))
        assertEquals(hidden, back)
        assertFalse(back.showHomeTab)
        assertEquals(StartScreen.FULLSCREEN_LIVE, back.start)
        // The rows, the custom one included, are untouched by the switch.
        assertEquals(custom.sections, back.sections)
    }

    @Test
    fun aLayoutSavedBeforeTheSwitchKeepsHomeShowing() {
        val old = """{"schema":1,"start":"guide","sections":[]}"""
        val l = HomeLayout.parse(old)
        assertTrue(l.showHomeTab)
        assertEquals(StartScreen.GUIDE, l.opensOn)
    }

    @Test
    fun hidingHomeMovesAHomeStartToFavoritesAndKeepsAnyOther() {
        val home = HomeLayout.default()
        assertEquals(StartScreen.HOME, home.start)
        assertEquals(StartScreen.FAVORITES, HomeLayout.setShowHomeTab(home, false).start)

        val guide = HomeLayout.setStart(home, StartScreen.GUIDE)
        assertEquals(StartScreen.GUIDE, HomeLayout.setShowHomeTab(guide, false).start)

        // Home can't be picked while its tab is hidden; an older saved pair still opens Favorites.
        val hidden = HomeLayout.setShowHomeTab(home, false)
        assertEquals(StartScreen.FAVORITES, HomeLayout.setStart(hidden, StartScreen.HOME).start)
        assertEquals(StartScreen.FAVORITES, home.copy(showHomeTab = false).opensOn)

        // Showing it again leaves the start where it is; Reset Home keeps both.
        assertEquals(StartScreen.FAVORITES, HomeLayout.setShowHomeTab(hidden, true).start)
        val reset = HomeLayout.reset(HomeLayout.setStart(hidden, StartScreen.LIVE))
        assertFalse(reset.showHomeTab)
        assertEquals(StartScreen.LIVE, reset.start)
    }

    private var controller: ActivityController<AppCompatActivity>? = null

    @After
    fun close() { controller?.pause()?.stop()?.destroy() }

    @Test
    fun withHomeHiddenTheSidebarStartsOnFavorites() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.setTheme(R.style.Theme_IptvApp)
        val c = Robolectric.buildActivity(AppCompatActivity::class.java).setup().also { controller = it }
        val activity = c.get()
        activity.setTheme(R.style.Theme_IptvApp)
        val b = ActivityTvHomeBinding.inflate(activity.layoutInflater)
        activity.setContentView(b.root)
        // What TvHomeActivity.applyHomeTab(false) does to the sidebar.
        b.btnTvHome.visibility = View.GONE
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(b.btnTvHome.isShown)
        assertTrue(b.tvSidebar.requestFocus())
        assertTrue("sidebar focus went to ${activity.currentFocus}", b.btnTvFavorites.isFocused)
        // Nothing above Favorites to land on: Up never reaches the hidden Home.
        assertNotSame(b.btnTvHome, b.btnTvFavorites.focusSearch(View.FOCUS_UP))
    }
}
