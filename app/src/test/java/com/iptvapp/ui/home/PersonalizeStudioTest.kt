package com.iptvapp.ui.home

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.R
import com.iptvapp.databinding.ActivityHomeCustomizeBinding
import com.iptvapp.databinding.ActivitySettingsBinding
import com.iptvapp.ui.SplashActivity
import com.iptvapp.ui.profile.ProfileUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Personalize MKTV's tabs, how it is opened, and the five Settings tabs it sits in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonalizeStudioTest {

    private fun activity(): AppCompatActivity {
        ApplicationProvider.getApplicationContext<Application>().setTheme(R.style.Theme_IptvApp)
        return Robolectric.buildActivity(AppCompatActivity::class.java).setup().get().also { it.setTheme(R.style.Theme_IptvApp) }
    }

    @Test
    fun theAskedForTabOpensAndAnythingElseOpensTheFirst() {
        assertEquals(PersonalizeStudio.Tab.LAYOUT, PersonalizeStudio.Tab.of(0))
        assertEquals(PersonalizeStudio.Tab.LOOK, PersonalizeStudio.Tab.of(1))
        assertEquals(PersonalizeStudio.Tab.REMOTE, PersonalizeStudio.Tab.of(2))
        assertEquals(PersonalizeStudio.Tab.ACCESS_PROFILES, PersonalizeStudio.Tab.of(3))
        assertEquals(PersonalizeStudio.Tab.LAYOUT, PersonalizeStudio.Tab.of(-1))
        assertEquals(PersonalizeStudio.Tab.LAYOUT, PersonalizeStudio.Tab.of(9))
    }

    @Test
    fun openingOnATabPutsItInTheIntent() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val intent: Intent = HomeCustomizeActivity.intent(context, tv = true, initialTab = 2)
        assertEquals(2, intent.getIntExtra(HomeCustomizeActivity.EXTRA_INITIAL_TAB, -1))
        assertTrue(intent.getBooleanExtra(HomeCustomizeActivity.EXTRA_TV, false))
        // The old callers (Home's last row, the TV sidebar) open on Layout.
        assertEquals(0, HomeCustomizeActivity.intent(context, tv = false).getIntExtra(HomeCustomizeActivity.EXTRA_INITIAL_TAB, -1))
    }

    @Test
    fun eachTabShowsOnlyItsOwnPanel() {
        val b = ActivityHomeCustomizeBinding.inflate(activity().layoutInflater)
        for (tab in PersonalizeStudio.Tab.entries) {
            PersonalizeStudio.show(b, tab)
            PersonalizeStudio.panels(b).forEachIndexed { i, panel ->
                assertEquals("$tab panel $i", if (i == tab.ordinal) View.VISIBLE else View.GONE, panel.visibility)
            }
            PersonalizeStudio.tabs(b).forEachIndexed { i, t -> assertEquals("$tab tab $i", i == tab.ordinal, t.isActivated) }
        }
        // Every tab is a stop for the remote.
        assertTrue(PersonalizeStudio.tabs(b).all { it.isFocusable && it.isClickable })
    }

    @Test
    fun eachSettingLivesOnTheRightTab() {
        val b = ActivityHomeCustomizeBinding.inflate(activity().layoutInflater)
        fun inPanel(panel: View, id: Int) = assertNotNull("${panel.resources.getResourceEntryName(id)} in ${panel.resources.getResourceEntryName(panel.id)}", panel.findViewById<View>(id))
        inPanel(b.panelLayout, R.id.personaCards)
        inPanel(b.panelLayout, R.id.rvCustomizeSections)
        inPanel(b.panelLayout, R.id.btnCustomizeMiniPlayer)
        inPanel(b.panelLayout, R.id.btnCustomizeSmartRows)
        inPanel(b.panelAppearance, R.id.livePreviewContainer)
        inPanel(b.panelAppearance, R.id.studioThemes)
        inPanel(b.panelAppearance, R.id.btnDensityExpanded)
        inPanel(b.panelRemote, R.id.lcnModeOptions)
        inPanel(b.panelRemote, R.id.btnDirectTuning)
        inPanel(b.panelAccessibilityProfiles, R.id.btnSeniorMode)
        inPanel(b.panelAccessibilityProfiles, R.id.btnStudioSwitchProfile)
    }

    @Test
    fun theLineUnderTheTitleSkipsWhatIsntKnown() {
        assertEquals("TV · Emjay · Classic Cable · OLED Black", PersonalizeStudio.summary("TV", "Emjay", "Classic Cable", "OLED Black"))
        assertEquals("Phone and tablet · Custom", PersonalizeStudio.summary("Phone and tablet", null, "Custom", " "))
    }

    @Test
    fun whoIsWatchingOnlyShowsOnceASecondProfileIsAdded() {
        assertFalse(com.iptvapp.data.profile.ProfileStore.showsWhoIsWatching(1))
        assertTrue(com.iptvapp.data.profile.ProfileStore.showsWhoIsWatching(2))
        assertTrue(com.iptvapp.data.profile.ProfileStore.showsWhoIsWatching(5))
        // The studio hides all three parts of it together.
        val b = ActivityHomeCustomizeBinding.inflate(activity().layoutInflater)
        listOf(b.tvStudioProfilesHeading, b.studioProfileRow, b.btnStudioSwitchProfile)
            .forEach { assertNotNull(b.panelAccessibilityProfiles.findViewById<View>(it.id)) }
    }

    @Test
    fun aProfileSwitchAwayFromHomeRestartsTheApp() {
        // From the studio or Settings, restarting that screen alone would leave no Home behind it.
        assertEquals(SplashActivity::class.java, ProfileUi.restartTarget(activity()))
    }

    @Test
    fun settingsHasFivePanelsWithTheStudioCardFirst() {
        val b = ActivitySettingsBinding.inflate(activity().layoutInflater)
        val panels = listOf(b.sectionPersonalize, b.sectionPlayback, b.sectionProviders, b.sectionDevices, b.sectionBackup)
        // Exactly these five share the panel frame.
        assertEquals(panels.toSet<View>(), (0 until (b.sectionPersonalize.parent as ViewGroup).childCount)
            .map { (b.sectionPersonalize.parent as ViewGroup).getChildAt(it) }.toSet())
        assertEquals(View.VISIBLE, b.sectionPersonalize.visibility)
        fun where(id: Int) = panels.indexOfFirst { it.findViewById<View>(id) != null }
        assertEquals(0, where(R.id.cardPersonalizeStudio))
        assertEquals(0, where(R.id.switchSeniorMode))
        assertEquals(0, where(R.id.rowLcnMode))
        assertEquals(1, where(R.id.rgFormat))
        assertEquals(1, where(R.id.switchTunneledPlayback))
        assertEquals(1, where(R.id.switchNewEpisodeNotifications))
        assertEquals(2, where(R.id.llServers))
        assertEquals(2, where(R.id.btnRefreshEpg))
        assertEquals(2, where(R.id.rowHiddenChannels))
        assertEquals(3, where(R.id.switchSyncEnabled))
        assertEquals(4, where(R.id.btnCheckUpdate))
        assertEquals(4, where(R.id.btnSendDebugReport))
        assertFalse(b.cardPersonalizeStudio.findViewById<View>(R.id.btnOpenStudio) == null)
        // The card's first row is the studio's button, so the remote reaches it first.
        assertEquals(0, (b.cardPersonalizeStudio.parent as ViewGroup).indexOfChild(b.cardPersonalizeStudio))
    }
}
