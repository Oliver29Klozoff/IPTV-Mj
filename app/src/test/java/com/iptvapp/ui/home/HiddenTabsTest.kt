package com.iptvapp.ui.home

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import com.iptvapp.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone's portrait tabs: one switched off in Settings stays hidden when the accent recolors the tabs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HiddenTabsTest {

    private fun tabs(): TabLayout {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_IptvApp)
        return TabLayout(context).apply { listOf("HOME", "FAVORITES", "PROVIDERS", "LIVE").forEach { addTab(newTab().setText(it)) } }
    }

    @Test
    fun recoloringTheTabsShowsAHiddenOneAgainWithoutHelp() {
        val tabs = tabs()
        tabs.getTabAt(2)!!.view.visibility = View.GONE
        tabs.setTabTextColors(Color.GRAY, Color.CYAN)
        assertEquals(View.VISIBLE, tabs.getTabAt(2)!!.view.visibility)
    }

    @Test
    fun aHiddenTabStaysHiddenAfterRecoloring() {
        val tabs = tabs()
        val hidden = HiddenTabs(tabs)
        hidden.set(2, false)
        tabs.setTabTextColors(Color.GRAY, Color.CYAN)
        hidden.reapply()
        assertEquals(View.GONE, tabs.getTabAt(2)!!.view.visibility)
        assertEquals(View.VISIBLE, tabs.getTabAt(3)!!.view.visibility)
        hidden.set(2, true)
        tabs.setTabTextColors(Color.GRAY, Color.CYAN)
        hidden.reapply()
        assertEquals(View.VISIBLE, tabs.getTabAt(2)!!.view.visibility)
    }
}
