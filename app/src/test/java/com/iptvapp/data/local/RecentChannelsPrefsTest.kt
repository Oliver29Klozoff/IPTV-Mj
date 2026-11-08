package com.iptvapp.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.util.RecentChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecentChannelsPrefsTest {

    @Test
    fun recentsAreSavedAndReadBackNewestFirst() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = PreferencesManager(context)
        prefs.removeRecentChannels(prefs.getRecentChannels().map { it.serverIndex to it.streamId })
        prefs.pushRecentChannel(RecentChannel(-1, 1, "One", null))
        prefs.pushRecentChannel(RecentChannel(2, 9, "Nine", "http://logo/9.png", "Backup"))
        prefs.pushRecentChannel(RecentChannel(-1, 1, "One", null))

        // A second manager reads the same saved list, as after a restart.
        val again = PreferencesManager(context).getRecentChannels()
        assertEquals(listOf(-1 to 1, 2 to 9), again.map { it.serverIndex to it.streamId })
        assertEquals("Backup", again[1].serverNickname)

        prefs.removeRecentChannels(listOf(-1 to 1))
        assertEquals(listOf(9), prefs.getRecentChannels().map { it.streamId })
    }
}
