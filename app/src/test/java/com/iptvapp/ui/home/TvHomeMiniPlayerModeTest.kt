package com.iptvapp.ui.home

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.R
import com.iptvapp.data.home.TvHomeMiniMode
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ActivityTvHomeBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Customize Home's Home Mini Player Display: the saved choice, and what each mode does to the TV
 * home's right column (the real activity_tv_home layout, at TV size).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvHomeMiniPlayerModeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun theModeIsSavedAndReadBack() = runBlocking {
        val prefs = PreferencesManager(context)
        for (mode in listOf("off", "compact", "hero")) {
            prefs.setTvHomeMiniPlayerMode(mode)
            // A second manager, as after a restart.
            assertEquals(mode, PreferencesManager(context).tvHomeMiniPlayerMode.first())
        }
    }

    @Test
    fun anUnknownModeReadsAsHero() = runBlocking {
        val prefs = PreferencesManager(context)
        prefs.setTvHomeMiniPlayerMode("giant")
        assertEquals("hero", prefs.tvHomeMiniPlayerMode.first())
        assertEquals(TvHomeMiniMode.HERO, TvHomeMiniMode.of(null))
        assertEquals(TvHomeMiniMode.OFF, TvHomeMiniMode.of("off"))
    }

    private val opened = mutableListOf<org.robolectric.android.controller.ActivityController<AppCompatActivity>>()

    // Each screen is closed, so its window can't upset the next test's.
    @org.junit.After
    fun closeScreens() { opened.forEach { it.pause().stop().destroy() } }

    /** The TV home in a window, with a focusable card in the rows, laid out for [mode]. */
    private fun tvHome(mode: TvHomeMiniMode, homeShowing: Boolean = true): ActivityTvHomeBinding {
        context.setTheme(R.style.Theme_IptvApp)
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).setup().also { opened += it }
        val activity = controller.get()
        activity.setTheme(R.style.Theme_IptvApp)
        val binding = ActivityTvHomeBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        binding.tvRvHome.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                TextView(parent.context).apply {
                    id = R.id.btnHomeCustomize
                    text = "Customize Home"
                    isFocusable = true
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
                }
            ) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        binding.tvRvHome.visibility = if (homeShowing) View.VISIBLE else View.GONE
        TvHomeMiniLayout.apply(
            mode, homeShowing, binding.tvMiniPlayerSection, binding.tvRvHome,
            context.resources.getDimensionPixelSize(R.dimen.tv_home_row_slot),
            player = binding.tvMiniPlayerContainer,
        )
        // The window lays it out at the TV size in the qualifiers.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(binding.root.height > 0)
        return binding
    }

    /** The right column's height: the rows' parent. */
    private fun columnHeight(b: ActivityTvHomeBinding) = (b.tvRvHome.parent as View).height

    @Test
    fun offHidesThePlayerAndTheRowsTakeTheWholeColumn() {
        val b = tvHome(TvHomeMiniMode.OFF)
        assertEquals(View.GONE, b.tvMiniPlayerSection.visibility)
        // The guide progress bar goes with the player.
        assertFalse(b.tvEpgProgress.isShown)
        assertEquals(columnHeight(b), b.tvRvHome.height)
        assertTrue(b.tvRvHome.height > 0)
    }

    @Test
    fun offLeavesTheSidebarNothingButTheRowsToTheRight() {
        val b = tvHome(TvHomeMiniMode.OFF)
        // The hidden player can't take focus, so Right from HOME lands in the rows.
        assertFalse(b.tvMiniPlayerContainer.requestFocus())
        assertTrue(b.btnTvHome.requestFocus())
        val right = b.btnTvHome.focusSearch(View.FOCUS_RIGHT)
        assertTrue("Right from HOME went to $right", right != null && isInside(right, b.tvRvHome))
        assertTrue(right!!.requestFocus())
        assertTrue(b.tvRvHome.hasFocus())
    }

    private fun isInside(view: View, parent: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    @Test
    fun heroKeepsTheOneRowSlotUnderTheLargePicture() {
        val b = tvHome(TvHomeMiniMode.HERO)
        val slot = context.resources.getDimensionPixelSize(R.dimen.tv_home_row_slot)
        assertEquals(View.VISIBLE, b.tvMiniPlayerSection.visibility)
        assertEquals(slot, b.tvRvHome.height)
        assertTrue(b.tvMiniPlayerSection.height > 2 * b.tvRvHome.height)
        assertTrue(TvHomeMiniLayout.rowsFillSlot(TvHomeMiniMode.HERO))
    }

    @Test
    fun compactSplitsTheColumnEvenly() {
        val b = tvHome(TvHomeMiniMode.COMPACT)
        assertEquals(View.VISIBLE, b.tvMiniPlayerSection.visibility)
        assertEquals(b.tvMiniPlayerSection.height.toFloat(), b.tvRvHome.height.toFloat(), 2f)
        assertFalse(TvHomeMiniLayout.rowsFillSlot(TvHomeMiniMode.COMPACT))
    }

    @Test
    fun awayFromHomeThePlayerHasTheColumnInEveryMode() {
        for (mode in TvHomeMiniMode.entries) {
            val b = tvHome(mode, homeShowing = false)
            assertEquals(View.VISIBLE, b.tvMiniPlayerSection.visibility)
            assertTrue(TvHomeMiniLayout.playerShown(mode, homeShowing = false))
            // Rows hidden: the player section fills the column (What's On Now is empty here).
            assertEquals(columnHeight(b), b.tvMiniPlayerSection.height)
        }
    }

    @Test
    fun switchingBackFromOffRestoresHero() {
        val b = tvHome(TvHomeMiniMode.OFF)
        val slot = context.resources.getDimensionPixelSize(R.dimen.tv_home_row_slot)
        TvHomeMiniLayout.apply(TvHomeMiniMode.HERO, true, b.tvMiniPlayerSection, b.tvRvHome, slot, player = b.tvMiniPlayerContainer)
        assertTrue(b.tvMiniPlayerContainer.isFocusable)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, b.tvMiniPlayerSection.visibility)
        assertEquals(slot, b.tvRvHome.height)
    }
}
