package com.iptvapp.data.home

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.home.HomeLayout.Builtin
import com.iptvapp.data.home.HomeLayout.Content
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.data.repository.XtreamRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Customize Home → Smart Rows: group Show/Hide all, the empty-row and finished-item rules, and cards per row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmartRowsTest {
    private lateinit var db: IptvDatabase
    private lateinit var home: SmartHome
    private lateinit var prefs: PreferencesManager
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("[]".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        prefs = PreferencesManager(context)
        runBlocking {
            prefs.saveCredentials("http://provider.test", "u", "p")
            prefs.setSmartRows(SmartRows.Settings())
        }
        home = SmartHome(XtreamRepository(api, db, prefs, client), db, prefs)
    }

    @After
    fun tearDown() = db.close()

    private fun channel(id: Int, favorite: Boolean = false) = ChannelEntity(
        streamId = id, name = "Channel $id", streamIcon = null, categoryId = "1", epgChannelId = null, tvArchive = 0,
        num = id, isFavorite = favorite,
    )

    /** A movie watched [percent] percent of the way, last watched at [at]. */
    private fun movie(id: Int, percent: Int = 0, at: Long = 0L, added: String = "$id") = VodEntity(
        id, "Movie $id", null, "m", null, "mkv", added,
        watchedMs = 60_000L * percent, durationMs = if (percent > 0) 6_000_000L else 0L, lastWatchedAt = at,
    )

    private fun row(rows: List<HomeRow>, id: String) = rows.first { it.id == id }

    // ── Groups ──────────────────────────────────────────────────────────

    @Test
    fun theTwoGroupsCoverEveryAutomaticRowOnce() {
        assertTrue((HomeLayout.LIVE_BUILTINS intersect HomeLayout.VOD_BUILTINS).isEmpty())
        assertEquals(Builtin.entries.toSet(), HomeLayout.LIVE_BUILTINS + HomeLayout.VOD_BUILTINS)
    }

    @Test
    fun aGroupChangesOnlyItsRowsShownOrHidden() {
        var l = HomeLayout.addCustom(HomeLayout.default(), "mine", "Mine", Content.CHANNELS)
        l = HomeLayout.moveTo(l, "recordings", 0)
        l = HomeLayout.setStyle(l, "continue", HomeLayout.Style.WIDE)
        val hidden = HomeLayout.setGroupVisibility(l, HomeLayout.VOD_BUILTINS, false)
        // Same rows, same order, same looks; only the movie and series rows hidden.
        assertEquals(l.sections.map { it.id }, hidden.sections.map { it.id })
        assertEquals(l.sections.map { it.style }, hidden.sections.map { it.style })
        for (s in hidden.sections) {
            val b = s.builtin
            val expected = if (b != null && b in HomeLayout.VOD_BUILTINS) false else l.section(s.id)!!.visible
            assertEquals(s.id, expected, s.visible)
        }
        assertEquals(l.section("custom:mine"), hidden.section("custom:mine"))
        assertEquals(l.start, hidden.start)
        assertEquals(l.showHomeTab, hidden.showHomeTab)
        // Show all turns every live row on, including ones that start hidden.
        val live = HomeLayout.setGroupVisibility(HomeLayout.setVisible(l, "on_now", false), HomeLayout.LIVE_BUILTINS, true)
        assertTrue(HomeLayout.LIVE_BUILTINS.all { live.section(it.id)!!.visible })
        // The movie rows that start hidden stay hidden: the live group doesn't touch them.
        assertFalse(live.section(Builtin.RECENT_MOVIES.id)!!.visible)
    }

    // ── Rules ───────────────────────────────────────────────────────────

    @Test
    fun theDefaultsAndTheirLimits() = runBlocking {
        assertEquals(SmartRows.Settings(20, autoCollapseEmpty = true, hideCompleted = true), SmartRows.Settings())
        assertEquals(20, SmartRows.normalizeLimit(null))
        assertEquals(20, SmartRows.normalizeLimit(25))
        assertEquals(10, SmartRows.normalizeLimit(10))
        assertEquals(30, SmartRows.normalizeLimit(30))
        assertFalse(SmartRows.isFinished(5_390_000, 6_000_000))
        assertTrue(SmartRows.isFinished(5_400_000, 6_000_000))
        assertFalse(SmartRows.isFinished(5_400_000, 0))
        // Saved and read back together, also by a new PreferencesManager.
        val chosen = SmartRows.Settings(30, autoCollapseEmpty = false, hideCompleted = false)
        prefs.setSmartRows(chosen)
        assertEquals(chosen, PreferencesManager(ApplicationProvider.getApplicationContext()).smartRows.first())
        prefs.setSmartRows(SmartRows.Settings())
        assertEquals(SmartRows.Settings(), prefs.smartRows.first())
    }

    @Test
    fun continueWatchingLeavesOutFinishedItemsUnlessAskedNotTo() = runBlocking {
        db.vodDao().upsertVod(listOf(movie(1, percent = 50, at = 3), movie(2, percent = 92, at = 2), movie(3, percent = 89, at = 1)))
        val titles = row(home.rows(now), "continue").cards.map { it.hit.title }
        assertEquals(listOf("Movie 1", "Movie 3"), titles)
        prefs.setSmartRows(SmartRows.Settings(hideCompleted = false))
        assertEquals(listOf("Movie 1", "Movie 2", "Movie 3"), row(home.rows(now), "continue").cards.map { it.hit.title })
    }

    @Test
    fun finishedItemsDontLeaveTheRowShort() = runBlocking {
        // The ten most recent are all finished; the row still fills with the next ten.
        db.vodDao().upsertVod((1..10).map { movie(it, percent = 93, at = 100L + it) } + (11..30).map { movie(it, percent = 40, at = it.toLong()) })
        prefs.setSmartRows(SmartRows.Settings(limit = 10))
        val cards = row(home.rows(now), "continue").cards
        assertEquals(10, cards.size)
        assertTrue(cards.all { (it.progress ?: 0) < 90 })
    }

    @Test
    fun emptyRowsCollapseEvenWhenSetToShowAHint() = runBlocking {
        val layout = HomeLayout.addCustom(HomeLayout.setShowWhenEmpty(HomeLayout.default(), "favorites", true), "mine", "Mine", Content.CHANNELS)
        // Auto-collapse on (the default): neither empty row shows; Customize Home is still there.
        val rows = home.rows(now, layout)
        assertFalse(rows.any { it.id == "favorites" || it.id == "custom:mine" })
        assertTrue(rows.last().customize)
        // Off: each row's own choice again.
        prefs.setSmartRows(SmartRows.Settings(autoCollapseEmpty = false))
        val kept = home.rows(now, layout)
        assertTrue(kept.any { it.id == "favorites" && it.cards.isEmpty() && it.emptyText != null })
        assertTrue(kept.any { it.id == "custom:mine" })
    }

    @Test
    fun everythingHiddenStillLeavesCustomizeHome() = runBlocking {
        db.channelDao().upsertChannels(listOf(channel(1, favorite = true)))
        var l = HomeLayout.setGroupVisibility(HomeLayout.default(), HomeLayout.LIVE_BUILTINS, false)
        l = HomeLayout.setGroupVisibility(l, HomeLayout.VOD_BUILTINS, false)
        val rows = home.rows(now, l)
        assertEquals(listOf(SmartHome.CUSTOMIZE_ID), rows.map { it.id })
    }

    @Test
    fun cardsPerRowFollowTheChosenLength() = runBlocking {
        db.channelDao().upsertChannels((1..40).map { channel(it, favorite = true) })
        db.vodDao().upsertVod((1..40).map { movie(it) })
        val layout = HomeLayout.setVisible(HomeLayout.default(), "recent_movies", true)
        for (limit in SmartRows.LIMITS) {
            prefs.setSmartRows(SmartRows.Settings(limit = limit))
            val rows = home.rows(now, layout)
            assertEquals("favorites at $limit", limit, row(rows, "favorites").cards.size)
            assertEquals("recent movies at $limit", limit, row(rows, "recent_movies").cards.size)
        }
    }
}
