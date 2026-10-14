package com.iptvapp.data.home

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.data.local.entities.EpisodeWatchedEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.data.local.entities.SeriesEntity
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.data.search.SearchHit
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** The Smart Home rows against a real (in-memory) database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SmartHomeTest {
    private lateinit var db: IptvDatabase
    private lateinit var home: SmartHome
    private val now = 1_800_000_000_000L
    private val nowSec = now / 1000

    // Episodes the provider lists: S1E1 (watched below), S1E2 added after the last watch, S1E3 older.
    private var seriesInfo = """{"info":{"name":"Show"},"episodes":{"1":[
        {"id":"1","episode_num":1,"title":"Pilot","container_extension":"mkv","season":1,"added":"${nowSec - 900_000}"},
        {"id":"2","episode_num":2,"title":"Second","container_extension":"mkv","season":1,"added":"${nowSec - 3_600}"},
        {"id":"3","episode_num":3,"title":"Third","container_extension":"mkv","season":1,"added":"${nowSec - 1_800}"}]}}"""

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.queryParameter("action") == "get_series_info") seriesInfo else "[]"
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        val prefs = PreferencesManager(context)
        runBlocking { prefs.saveCredentials("http://provider.test", "u", "p") }
        home = SmartHome(XtreamRepository(api, db, prefs, client), db, prefs)
    }

    @After
    fun tearDown() = db.close()

    private fun channel(id: Int, name: String, favorite: Boolean = false, lastWatched: Long? = null) = ChannelEntity(
        streamId = id, name = name, streamIcon = null, categoryId = "1", epgChannelId = null, tvArchive = 0,
        num = id, isFavorite = favorite, lastWatched = lastWatched
    )

    private var epgId = 0
    private fun program(streamId: Int, title: String, startMin: Long, lengthMin: Long, serverIndex: Int = -1) = EpgEntity(
        serverIndex = serverIndex, id = "e${epgId++}", streamId = streamId, title = title, description = "",
        startTimestamp = nowSec + startMin * 60, stopTimestamp = nowSec + (startMin + lengthMin) * 60, nowPlaying = 0, hasArchive = 0
    )

    private fun row(rows: List<HomeRow>, kind: HomeRow.Kind) = rows.firstOrNull { it.kind == kind }
    private fun titles(row: HomeRow?) = row?.cards?.map { it.hit.title }

    @Test
    fun rowsShowWhatsOnYourChannelsNowAndSoonAndHideWhenEmpty() = runBlocking {
        db.channelDao().upsertChannels(listOf(
            channel(1, "News", favorite = true), channel(2, "Sports", favorite = true),
            channel(3, "Movies HD", lastWatched = now - 60_000), channel(4, "Unwatched")
        ))
        db.mergedChannelDao().upsertAll(listOf(MergedChannelEntity(1, 9, "UK News", null, 9, "Backup", categoryId = "1", categoryName = "News", isFavorite = true)))
        db.epgDao().upsertEpg(listOf(
            program(1, "Evening News", startMin = -10, lengthMin = 40),
            program(1, "Late News", startMin = 30, lengthMin = 30),
            program(1, "Night News", startMin = 60, lengthMin = 30),     // not the next one: left out
            program(2, "Big Match", startMin = 200, lengthMin = 90),     // beyond Starting Soon
            program(3, "Film", startMin = -5, lengthMin = 120),
            program(4, "Nobody's Show", startMin = -5, lengthMin = 30),  // not a channel of yours
            program(9, "UK Bulletin", startMin = -1, lengthMin = 15, serverIndex = 1)
        ))

        val rows = home.rows(now)
        assertEquals(setOf("Evening News", "Film", "UK Bulletin"), titles(row(rows, HomeRow.Kind.ON_NOW))?.toSet())
        assertEquals(listOf("Late News"), titles(row(rows, HomeRow.Kind.STARTING_SOON)))
        val favorites = row(rows, HomeRow.Kind.FAVORITES)!!
        assertEquals(listOf("News", "Sports", "UK News"), titles(favorites))
        assertEquals("Evening News", favorites.cards.first().line)
        assertEquals(listOf("Movies HD"), titles(row(rows, HomeRow.Kind.RECENT)))
        val onNow = row(rows, HomeRow.Kind.ON_NOW)!!.cards.first { it.hit.title == "Evening News" }
        assertEquals(25, onNow.progress) // 10 of 40 minutes
        assertNull(row(rows, HomeRow.Kind.CONTINUE))
        assertNull(row(rows, HomeRow.Kind.RECORDINGS))
    }

    @Test
    fun continueWatchingMixesMoviesAndSeriesMostRecentFirst() = runBlocking {
        db.vodDao().upsertVod(listOf(
            VodEntity(10, "Older Movie", null, "1", null, "mkv", null, watchedMs = 600_000, durationMs = 6_000_000, lastWatchedAt = now - 86_400_000),
            VodEntity(11, "Finished Movie", null, "1", null, "mkv", null, watchedMs = 6_000_000, durationMs = 6_000_000, lastWatchedAt = now)
        ))
        db.seriesDao().upsertSeries(listOf(SeriesEntity(20, "A Show", null, null, null, null, "1", lastWatchedAt = now - 3_600_000)))
        db.episodeWatchedDao().upsert(EpisodeWatchedEntity(20, 2, 5, watchedAt = 0, watchedMs = 600_000, durationMs = 2_400_000))

        val cont = row(home.rows(now), HomeRow.Kind.CONTINUE)!!
        assertEquals(listOf("A Show", "Older Movie"), cont.cards.map { (it.hit as? SearchHit.Episode)?.series?.title ?: it.hit.title })
        val episode = cont.cards.first().hit as SearchHit.Episode
        assertEquals(2 to 5, episode.season to episode.episode)
        assertEquals("S2E5", cont.cards.first().line)
        assertEquals(25, cont.cards.first().progress)
        assertEquals(10, cont.cards.last().progress)
    }

    @Test
    fun recordingsPutWhatsRecordingFirst() = runBlocking {
        fun rec(title: String, status: String, start: Long) = RecordingEntity(streamId = 1, channelName = "News",
            scheduledStartMs = start, durationMs = 1, outputPath = "/x.ts", status = status, programTitle = title)
        db.recordingDao().insert(rec("Old", "DONE", now - 2 * 86_400_000))
        db.recordingDao().insert(rec("Newer", "DONE", now - 86_400_000))
        db.recordingDao().insert(rec("Later", "SCHEDULED", now + 86_400_000))
        db.recordingDao().insert(rec("Now", "RECORDING", now - 60_000))
        db.recordingDao().insert(rec("Broken", "FAILED", now - 60_000))
        assertEquals(listOf("Now", "Later", "Newer", "Old"), titles(row(home.rows(now), HomeRow.Kind.RECORDINGS)))
    }

    @Test
    fun recommendedComesFromTheCategoriesYouWatch() = runBlocking {
        db.vodDao().upsertVod(listOf(
            VodEntity(1, "Watched Action", null, "act", null, "mkv", null, watchedMs = 100, durationMs = 1000),
            VodEntity(2, "Watched Action 2", null, "act", null, "mkv", null, watchedMs = 1000, durationMs = 1000),
            VodEntity(3, "Good Action", null, "act", "8", "mkv", null),
            VodEntity(4, "Great Action", null, "act", "9", "mkv", null),
            VodEntity(5, "Romance", null, "rom", "9", "mkv", null)
        ))
        assertEquals(listOf("Great Action", "Good Action"), titles(row(home.rows(now), HomeRow.Kind.RECOMMENDED)))
    }

    @Test
    fun newEpisodesAreTheFirstUnseenOneAddedSinceYouLastWatched() = runBlocking {
        db.seriesDao().upsertSeries(listOf(SeriesEntity(20, "A Show", "cover", null, null, null, "1", lastWatchedAt = now - 7_200_000)))
        db.episodeWatchedDao().upsert(EpisodeWatchedEntity(20, 1, 1, watchedAt = 1))

        val row = home.newEpisodes(now)!!
        val card = row.cards.single()
        val episode = card.hit as SearchHit.Episode
        assertEquals("Second", episode.episodeTitle)
        assertEquals("S1E2 · 2 new", card.line)

        // Nothing added since: no row.
        db.seriesDao().upsertSeries(listOf(SeriesEntity(21, "Quiet Show", null, null, null, null, "1", lastWatchedAt = now)))
        db.episodeWatchedDao().upsert(EpisodeWatchedEntity(21, 1, 3, watchedAt = 1))
        val again = home.newEpisodes(now)!!
        assertTrue(again.cards.none { (it.hit as SearchHit.Episode).series.title == "Quiet Show" })
    }
}
