package com.iptvapp.data.search

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.data.local.entities.MergedVodEntity
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.data.local.entities.SeriesEntity
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.data.repository.XtreamRepository
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

/** Universal search against a real (in-memory) database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UniversalSearchTest {
    private lateinit var db: IptvDatabase
    private lateinit var search: UniversalSearch
    private lateinit var prefs: PreferencesManager
    private val now = 1_800_000_000_000L // a fixed "now", in ms
    private val nowSec = now / 1000

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        // The provider answers a series' episodes; nothing else is asked of it.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.queryParameter("action") == "get_series_info") seriesInfo else "[]"
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        prefs = PreferencesManager(context)
        runBlocking {
            prefs.saveCredentials("http://provider.test", "u", "p")
            prefs.setEnglishOnlyMovies(false)
        }
        search = UniversalSearch(XtreamRepository(api, db, prefs, client), db, prefs)
    }

    private val seriesInfo = """{"info":{"name":"Show"},"episodes":{
        "1":[{"id":"1","episode_num":1,"title":"Pilot","container_extension":"mkv","season":1},
             {"id":"2","episode_num":2,"title":"The Dinner Party","container_extension":"mkv","season":1}],
        "2":[{"id":"3","episode_num":3,"title":"Finale","container_extension":"mkv","season":2}]}}"""


    @After
    fun tearDown() = db.close()

    private fun channel(id: Int, name: String, archive: Boolean = false, days: Int = 0, favorite: Boolean = false) = ChannelEntity(
        streamId = id, name = name, streamIcon = null, categoryId = "1", epgChannelId = null,
        tvArchive = if (archive) 1 else 0, tvArchiveDuration = days, num = id, isFavorite = favorite
    )

    private fun other(id: Int, name: String) = MergedChannelEntity(
        serverIndex = 1, streamId = id, name = name, streamIcon = null, num = id, serverNickname = "Backup",
        categoryId = "9", categoryName = "Sports"
    )

    private var epgId = 0
    private fun program(streamId: Int, title: String, startMin: Long, lengthMin: Long, serverIndex: Int = -1) = EpgEntity(
        serverIndex = serverIndex, id = "e${epgId++}", streamId = streamId, title = title, description = "",
        startTimestamp = nowSec + startMin * 60, stopTimestamp = nowSec + (startMin + lengthMin) * 60,
        nowPlaying = 0, hasArchive = 0
    )

    private fun names(hits: List<SearchHit>) = hits.map { "${it.kind}:${it.title}" }

    @Test
    fun searchesEveryKindAndPutsTheExactTitleFirst() = runBlocking {
        db.channelDao().upsertChannels(listOf(channel(1, "Alien Channel HD")))
        db.vodDao().upsertVod(listOf(VodEntity(10, "EN - Alien (1979)", null, "1", null, "mkv", null)))
        db.mergedVodDao().upsertAll(listOf(MergedVodEntity(1, 11, "Aliens", null, "Backup", "9", "EN | Movies", null, "mkv", null)))
        db.seriesDao().upsertSeries(listOf(SeriesEntity(20, "Alien Nation", null, null, null, null, "1")))
        db.recordingDao().insert(RecordingEntity(streamId = 1, channelName = "Alien Channel HD", scheduledStartMs = now - 86_400_000,
            durationMs = 3_600_000, outputPath = "/x.ts", status = "DONE", programTitle = "Alien Planet"))

        val hits = search.search("alien", now)
        assertEquals("MOVIE:EN - Alien (1979)", names(hits).first())
        assertEquals(
            setOf("CHANNEL:Alien Channel HD", "MOVIE:EN - Alien (1979)", "MOVIE:Aliens", "SERIES:Alien Nation", "RECORDING:Alien Planet"),
            names(hits).toSet()
        )
        assertEquals("Backup", hits.first { it.title == "Aliens" }.provider)
    }

    @Test
    fun theGuideGivesOnNowUpcomingAndOnlyReplayableCatchUp() = runBlocking {
        db.channelDao().upsertChannels(listOf(
            channel(1, "News One", archive = true, days = 3),
            channel(2, "News Two"), // no archive
            channel(3, "News Three", archive = true, days = 1)
        ))
        db.mergedChannelDao().upsertAll(listOf(other(5, "Backup News")))
        db.epgDao().upsertEpg(listOf(
            program(1, "Evening News", startMin = -10, lengthMin = 30),          // on now
            program(1, "Evening News", startMin = 60 * 23, lengthMin = 30),      // also later: on now wins
            program(2, "Evening News", startMin = 120, lengthMin = 30),          // upcoming
            program(2, "Morning News", startMin = -600, lengthMin = 30),         // past, no archive: dropped
            program(3, "Morning News", startMin = -600, lengthMin = 30),         // past, archive: catch-up
            program(3, "Morning News", startMin = -60 * 48, lengthMin = 30),     // older than 1 day: dropped
            program(5, "Morning News", startMin = -600, lengthMin = 30, serverIndex = 1), // other provider's past: dropped
            program(5, "Evening News", startMin = 30, lengthMin = 30, serverIndex = 1)    // other provider's upcoming
        ))

        val programs = search.search("news", now).filterIsInstance<SearchHit.Program>()
            .map { "${it.kind}:${it.channelName}:${it.programTitle}" }.toSet()
        assertEquals(setOf(
            "ON_NOW:News One:Evening News",
            "UPCOMING:News Two:Evening News",
            "CATCH_UP:News Three:Morning News",
            "UPCOMING:Backup News:Evening News"
        ), programs)
    }

    @Test
    fun favoritesBreakTiesAndKindsOrderTheRest() = runBlocking {
        db.channelDao().upsertChannels(listOf(channel(1, "Sports"), channel(2, "Sports", favorite = true)))
        val hits = search.search("sports", now).filterIsInstance<SearchHit.Channel>()
        assertEquals(2, hits.first().channel?.streamId)
    }

    @Test
    fun oneLetterSearchesNothing() = runBlocking {
        db.channelDao().upsertChannels(listOf(channel(1, "A")))
        assertTrue(search.search("a", now).isEmpty())
    }

    @Test
    fun anEpisodeCodeIsReadFromTheEndOfTheQuery() {
        val ask = UniversalSearch.parseEpisodeAsk("Breaking Bad s5e14")!!
        assertEquals("Breaking Bad", ask.seriesPart)
        assertEquals(5, ask.season)
        assertEquals(14, ask.episode)
        val other = UniversalSearch.parseEpisodeAsk("the office 2x03")!!
        assertEquals("the office", other.seriesPart)
        assertEquals(2, other.season)
        assertEquals(3, other.episode)
        assertNull(UniversalSearch.parseEpisodeAsk("s5e14"))
        assertNull(UniversalSearch.parseEpisodeAsk("breaking bad"))
    }

    @Test
    fun episodesAreFoundByCodeOrByTitleWordsAndComeFirst() = runBlocking {
        db.seriesDao().upsertSeries(listOf(SeriesEntity(20, "World of Wonders", null, null, null, null, "1")))

        val byCode = search.search("world of wonders s2e3", now)
        val codeHits = UniversalSearch.rank(byCode + search.episodes("world of wonders s2e3", byCode), "world of wonders s2e3")
        assertEquals("EPISODE:Finale", names(codeHits).first())
        val finale = codeHits.first() as SearchHit.Episode
        assertEquals(2, finale.season)
        assertEquals(3, finale.episode)

        // The whole query isn't a series name, so the series is found by its leading words.
        val byTitle = search.search("world of wonders dinner", now)
        val titleHits = search.episodes("world of wonders dinner", byTitle)
        assertEquals(listOf("EPISODE:The Dinner Party"), names(titleHits))
        assertEquals(20, titleHits.single().series.series?.seriesId)

        assertTrue(search.episodes("world of wonders nothing like it", search.search("world of wonders nothing like it", now)).isEmpty())
    }

    // A long-running show's history mustn't crowd what's on now out of the guide lookup's cap.
    @Test
    fun manyPastAiringsDontHideWhatsOnNow() = runBlocking {
        db.channelDao().upsertChannels(listOf(channel(1, "News One", archive = true, days = 7), channel(2, "News Two")))
        db.epgDao().upsertEpg((1..700).map { program(2, "News Hour", startMin = -60L * it - 60, lengthMin = 30) } +
            program(1, "News Hour", startMin = -5, lengthMin = 30))
        val programs = search.search("news hour", now).filterIsInstance<SearchHit.Program>()
        assertEquals(listOf("ON_NOW:News One"), programs.map { "${it.kind}:${it.channelName}" })
    }

    @Test
    fun englishOnlyAlsoLimitsTheSeriesAnEpisodeIsLookedUpIn() = runBlocking {
        db.categoryDao().upsertCategories(listOf(
            com.iptvapp.data.local.entities.CategoryEntity("1", "EN | Drama", 0, "series"),
            com.iptvapp.data.local.entities.CategoryEntity("2", "FR | Drame", 0, "series")
        ))
        db.seriesDao().upsertSeries(listOf(SeriesEntity(20, "World of Wonders", null, null, null, null, "2")))
        prefs.setEnglishOnlyMovies(true)
        val q = "world of wonders dinner"
        assertTrue(search.episodes(q, search.search(q, now)).isEmpty())
        prefs.setEnglishOnlyMovies(false)
        assertEquals(1, search.episodes(q, search.search(q, now)).size)
    }

    @Test
    fun aSeriesIsFoundByTheNameBeforeItsEpisodeCode() = runBlocking {
        db.seriesDao().upsertSeries(listOf(SeriesEntity(20, "Breaking Bad", null, null, null, null, "1")))
        assertEquals(listOf("SERIES:Breaking Bad"), names(search.search("breaking bad s5e14", now)))
    }
}
