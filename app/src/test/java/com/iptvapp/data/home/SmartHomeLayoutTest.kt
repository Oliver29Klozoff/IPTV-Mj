package com.iptvapp.data.home

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.home.HomeLayout.Content
import com.iptvapp.data.home.HomeLayout.ItemRef
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.data.local.entities.SeriesEntity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.atomic.AtomicInteger

/** Home rows built from a customized layout (HomeLayout), against a real (in-memory) database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmartHomeLayoutTest {
    private lateinit var db: IptvDatabase
    private lateinit var home: SmartHome
    private lateinit var prefs: PreferencesManager
    private val requests = AtomicInteger()
    private val now = 1_800_000_000_000L
    private val nowSec = now / 1000

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("[]".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        prefs = PreferencesManager(context)
        runBlocking {
            prefs.saveCredentials("http://provider.test", "u", "p")
            // Two other providers, both on.
            prefs.saveExtraServersWithNick(listOf(
                listOf("http://a.test", "a", "pa", "Backup A", "", "true", "xtream", ""),
                listOf("http://b.test", "b", "pb", "Backup B", "", "true", "xtream", "")
            ))
        }
        home = SmartHome(XtreamRepository(api, db, prefs, client), db, prefs)
    }

    @After
    fun tearDown() = db.close()

    private fun channel(id: Int, name: String, category: String = "1", favorite: Boolean = false, hidden: Boolean = false) = ChannelEntity(
        streamId = id, name = name, streamIcon = null, categoryId = category, epgChannelId = null, tvArchive = 0,
        num = id, isFavorite = favorite, isHidden = hidden
    )

    private fun merged(server: Int, id: Int, name: String, category: String? = "x") =
        MergedChannelEntity(server, id, name, null, id, if (server == 0) "Backup A" else "Backup B", categoryId = category, categoryName = "Cat")

    private fun movie(id: Int, name: String, category: String = "m", added: String = "1", favorite: Boolean = false, watchedMs: Long = 0) =
        VodEntity(id, name, null, category, null, "mkv", added, isFavorite = favorite, watchedMs = watchedMs, durationMs = if (watchedMs > 0) 6_000_000 else 0)

    private fun ids(rows: List<HomeRow>) = rows.map { it.id }
    private fun titles(rows: List<HomeRow>, id: String) = rows.first { it.id == id }.cards.map { it.hit.title }

    @Test
    fun rowsFollowTheLayoutOrderSkipHiddenOnesAndEndWithCustomize() = runBlocking {
        db.channelDao().upsertChannels(listOf(channel(1, "News", favorite = true), channel(2, "Recent")))
        db.recordingDao().insert(com.iptvapp.data.local.entities.RecordingEntity(streamId = 1, channelName = "News",
            scheduledStartMs = now - 3_600_000, durationMs = 60_000, outputPath = "/r.ts", status = "DONE"))
        var l = HomeLayout.moveTo(HomeLayout.default(), "recordings", 0)
        l = HomeLayout.setVisible(l, "favorites", false)
        val rows = home.rows(now, l)
        assertEquals(listOf("recordings", SmartHome.CUSTOMIZE_ID), ids(rows))
        assertTrue(rows.last().customize)
        // Showing Favorites again puts it back, in its place.
        val shown = home.rows(now, HomeLayout.setVisible(l, "favorites", true))
        assertEquals(listOf("recordings", "favorites", SmartHome.CUSTOMIZE_ID), ids(shown))
        // Refreshing again gives the same rows, once each.
        assertEquals(ids(shown), ids(home.rows(now, HomeLayout.setVisible(l, "favorites", true))))
    }

    @Test
    fun emptyRowsAreLeftOutUnlessKeptWithAHint() = runBlocking {
        val kept = HomeLayout.setShowWhenEmpty(HomeLayout.default(), "favorites", true)
        val rows = home.rows(now, kept)
        val fav = rows.first { it.id == "favorites" }
        assertTrue(fav.cards.isEmpty())
        assertEquals("Add channels to Favorites to see them here.", fav.emptyText)
        assertFalse(ids(home.rows(now)).contains("favorites"))
        // A new picked row starts kept, with its own hint.
        val custom = home.rows(now, HomeLayout.addCustom(HomeLayout.default(), "x", "Mine", Content.CHANNELS))
        assertEquals("Add content to this collection.", custom.first { it.id == "custom:x" }.emptyText)
    }

    @Test
    fun aPickedRowKeepsItsOrderAcrossProvidersAndSkipsWhatIsGone() = runBlocking {
        db.channelDao().upsertChannels(listOf(channel(5, "ESPN"), channel(6, "Hidden", hidden = true), channel(7, "NFL")))
        db.mergedChannelDao().upsertAll(listOf(merged(0, 5, "FS1 on A"), merged(1, 5, "Five on B")))
        db.epgDao().upsertEpg(listOf(
            EpgEntity(0, "p1", 5, "Match on A", "", nowSec - 60, nowSec + 3600, 0, 0),
            EpgEntity(-1, "p2", 5, "SportsCenter", "", nowSec - 60, nowSec + 3600, 0, 0)
        ))
        var l = HomeLayout.addCustom(HomeLayout.default(), "s", "My Sports", Content.CHANNELS)
        // Stream id 5 on three providers is three channels; 99 was deleted; 6 is hidden.
        for (ref in listOf(ItemRef(Content.CHANNELS, 0, 5), ItemRef(Content.CHANNELS, -1, 99), ItemRef(Content.CHANNELS, -1, 7),
            ItemRef(Content.CHANNELS, -1, 5), ItemRef(Content.CHANNELS, -1, 6), ItemRef(Content.CHANNELS, 1, 5))) {
            l = HomeLayout.addItem(l, "custom:s", ref)
        }
        val row = home.rows(now, l).first { it.id == "custom:s" }
        assertEquals(listOf("FS1 on A", "NFL", "ESPN", "Five on B"), row.cards.map { it.hit.title })
        assertEquals("My Sports", row.title)
        // Each card shows its own provider's current show, not another's with the same id.
        assertEquals(listOf("Match on A", null, "SportsCenter", null), row.cards.map { it.line })
        // Reordering the row moves nothing in the catalog.
        val moved = HomeLayout.moveItem(l, "custom:s", ItemRef(Content.CHANNELS, 1, 5), 0)
        assertEquals("Five on B", home.rows(now, moved).first { it.id == "custom:s" }.cards.first().hit.title)
        assertEquals(listOf(5, 7), db.channelDao().getAllChannels().first().map { it.streamId })
        // A provider switched off: its items don't show.
        prefs.saveExtraServersWithNick(listOf(
            listOf("http://a.test", "a", "pa", "Backup A", "", "false", "xtream", ""),
            listOf("http://b.test", "b", "pb", "Backup B", "", "true", "xtream", "")
        ))
        assertEquals(listOf("NFL", "ESPN", "Five on B"), titles(home.rows(now, l), "custom:s"))
    }

    @Test
    fun categoryRowsFollowTheCatalogAndStayBounded() = runBlocking {
        db.channelDao().upsertChannels((1..60).map { channel(it, "Sport $it", category = "sports") } + channel(100, "News", category = "news"))
        db.vodDao().upsertVod(listOf(movie(1, "Old Action", "act", added = "100"), movie(2, "New Action", "act", added = "200"), movie(3, "Comedy", "com")))
        var l = HomeLayout.addCustom(HomeLayout.default(), "c", "Sports", Content.CHANNELS, HomeLayout.Filter(-1, "sports", "Sports"))
        l = HomeLayout.addCustom(l, "m", "Action", Content.MOVIES, HomeLayout.Filter(-1, "act", "Action"))
        val rows = home.rows(now, l)
        assertEquals(SmartHome.ROW_LIMIT, rows.first { it.id == "custom:c" }.cards.size)
        assertEquals(listOf("New Action", "Old Action"), titles(rows, "custom:m"))
        // The catalog refreshes: the row follows it, the layout is untouched.
        db.vodDao().upsertVod(listOf(movie(4, "Newest Action", "act", added = "300")))
        assertEquals(listOf("Newest Action", "New Action", "Old Action"), titles(home.rows(now, l), "custom:m"))
    }

    @Test
    fun newRowsForFilmsAndSeriesShowWhenTurnedOn() = runBlocking {
        db.vodDao().upsertVod(listOf(movie(1, "Fav Film", favorite = true, added = "5"), movie(2, "Newest", added = "9")))
        db.seriesDao().upsertSeries(listOf(SeriesEntity(3, "Fav Show", null, null, null, null, "s", isFavorite = true)))
        assertFalse(ids(home.rows(now)).any { it in setOf("recent_movies", "favorite_movies", "favorite_series") })
        var l = HomeLayout.default()
        for (id in listOf("recent_movies", "favorite_movies", "favorite_series")) l = HomeLayout.setVisible(l, id, true)
        val rows = home.rows(now, l)
        assertEquals(listOf("Newest", "Fav Film"), titles(rows, "recent_movies"))
        assertEquals(listOf("Fav Film"), titles(rows, "favorite_movies"))
        assertEquals(listOf("Fav Show"), titles(rows, "favorite_series"))
    }

    @Test
    fun aHiddenNewEpisodesRowAsksNoProvider() = runBlocking {
        db.seriesDao().upsertSeries(listOf(SeriesEntity(3, "Followed", null, null, null, null, "s", isFavorite = true, lastWatchedAt = now)))
        val before = requests.get()
        assertNull(home.newEpisodes(now, HomeLayout.setVisible(HomeLayout.default(), "new_episodes", false)))
        assertEquals(before, requests.get())
    }

    @Test
    fun tvAndPhoneLayoutsAreSeparateAndSurviveARestart() = runBlocking {
        HomeLayoutStore.update(prefs, tv = true) { HomeLayout.setVisible(it, "on_now", false) }
        HomeLayoutStore.update(prefs, tv = false) { HomeLayout.addCustom(it, "p", "Phone Row", Content.MOVIES) }
        // A new PreferencesManager reads what the old one saved (a restart).
        val again = PreferencesManager(ApplicationProvider.getApplicationContext())
        val tv = HomeLayoutStore.load(again, tv = true)
        val phone = HomeLayoutStore.load(again, tv = false)
        assertFalse(tv.section("on_now")!!.visible)
        assertNull(tv.section("custom:p"))
        assertTrue(phone.section("on_now")!!.visible)
        assertEquals("Phone Row", phone.section("custom:p")!!.displayTitle)
    }

    @Test
    fun arrangingHomeNeverTouchesFavoritesOrProgress() = runBlocking {
        db.channelDao().upsertChannels(listOf(channel(1, "Fav", favorite = true), channel(2, "Other")))
        db.vodDao().upsertVod(listOf(movie(1, "Half Watched", watchedMs = 3_000_000)))
        HomeLayoutStore.update(prefs, tv = true) { l ->
            var x = HomeLayout.addCustom(l, "a", "Mine", Content.CHANNELS)
            x = HomeLayout.addItem(x, "custom:a", ItemRef(Content.CHANNELS, -1, 2))
            x = HomeLayout.setVisible(HomeLayout.move(x, "favorites", -2), "continue", false)
            HomeLayout.delete(x, "custom:a")
        }
        HomeLayoutStore.update(prefs, tv = true) { HomeLayout.reset(it) }
        assertEquals(listOf(1), db.channelDao().getFavoriteChannels().first().map { it.streamId })
        assertEquals(3_000_000L, db.vodDao().getWatchedMs(1))
        assertEquals(listOf("Half Watched"), titles(home.rows(now, HomeLayoutStore.load(prefs, true)), "continue"))
    }

    @Test
    fun removingAProviderMovesSavedRowsWithIt() = runBlocking {
        HomeLayoutStore.update(prefs, tv = false) { l ->
            var x = HomeLayout.addCustom(l, "s", "Mix", Content.CHANNELS)
            x = HomeLayout.addItem(x, "custom:s", ItemRef(Content.CHANNELS, 0, 5))
            HomeLayout.addItem(x, "custom:s", ItemRef(Content.CHANNELS, 1, 6))
        }
        // Settings removes the first other provider: the second becomes index 0.
        prefs.saveExtraServersWithNick(listOf(listOf("http://b.test", "b", "pb", "Backup B", "", "true", "xtream", "")), removedIndex = 0)
        assertEquals(listOf(ItemRef(Content.CHANNELS, 0, 6)), HomeLayoutStore.load(prefs, false).section("custom:s")!!.items)
        // A different main login: none of its old channels stay.
        HomeLayoutStore.update(prefs, tv = false) { HomeLayout.addItem(it, "custom:s", ItemRef(Content.CHANNELS, -1, 1)) }
        prefs.saveCredentials("http://other.test", "someone", "p")
        assertEquals(listOf(ItemRef(Content.CHANNELS, 0, 6)), HomeLayoutStore.load(prefs, false).section("custom:s")!!.items)
    }

    @Test
    fun aLargeCatalogBuildsBoundedRowsQuickly() = runBlocking {
        db.channelDao().upsertChannels((1..30_000).map { channel(it, "Channel $it", category = "c${it % 300}", favorite = it % 1000 == 0) })
        db.vodDao().upsertVod((1..50_000).map { movie(it, "Movie $it", category = "m${it % 500}", added = (1_700_000_000 + it).toString(), favorite = it % 997 == 0) })
        var l = HomeLayout.default()
        for (id in listOf("recent_movies", "favorite_movies")) l = HomeLayout.setVisible(l, id, true)
        l = HomeLayout.addCustom(l, "c", "Cat", Content.CHANNELS, HomeLayout.Filter(-1, "c7", "C7"))
        l = HomeLayout.addCustom(l, "m", "Films", Content.MOVIES, HomeLayout.Filter(-1, "m7", "M7"))
        val started = System.nanoTime()
        val rows = home.rows(now, l)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(rows.all { it.cards.size <= SmartHome.ROW_LIMIT })
        assertEquals(SmartHome.ROW_LIMIT, rows.first { it.id == "custom:m" }.cards.size)
        assertEquals("Movie 50000", rows.first { it.id == "recent_movies" }.cards.first().hit.title)
        assertTrue("rows took $ms ms", ms < 5_000)
    }
}
