package com.iptvapp.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.SeriesEntity
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.util.Resource
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
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

/**
 * The routine movie/series refresh through Retrofit, with the provider faked at the HTTP layer: a successful but empty list must not
 * empty a stored catalog, while every other outcome behaves as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EmptyCatalogGuardTest {
    private lateinit var context: Context
    private lateinit var db: IptvDatabase
    private lateinit var repository: XtreamRepository

    // What the fake provider answers for each action: HTTP status and body.
    private val answers = mutableMapOf<String, Pair<Int, String>>()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        val prefs = PreferencesManager(context)
        prefs.saveCredentials("http://provider.test", "u", "p")
        // The provider: answers each request from [answers] without touching the network.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = answers[request.url.queryParameter("action").orEmpty()] ?: (200 to "[]")
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        repository = XtreamRepository(api, db, prefs, client)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun movieJson(ids: IntRange) = ids.joinToString(",", "[", "]") {
        """{"num":$it,"name":"Movie $it","stream_type":"movie","stream_id":$it,"stream_icon":"","rating":"7","added":"$it","category_id":"1","container_extension":"mkv"}"""
    }

    private fun showJson(ids: IntRange) = ids.joinToString(",", "[", "]") {
        """{"num":$it,"name":"Show $it","series_id":$it,"cover":"","plot":"","genre":"Drama","rating":"7","category_id":"1"}"""
    }

    private suspend fun storeMovies(count: Int) =
        (1..count).chunked(5_000).forEach { chunk ->
            db.vodDao().upsertVod(chunk.map { VodEntity(it, "Movie $it", null, "1", null, "mkv", "$it") })
        }

    private suspend fun storeShows(count: Int) =
        db.seriesDao().upsertSeries((1..count).map { SeriesEntity(it, "Show $it", null, null, null, null, "1") })

    @Test
    fun aHundredThousandMoviesSurviveAnEmptyResponse() = runBlocking {
        storeMovies(100_000)
        db.vodDao().upsertVod(listOf(VodEntity(7, "Movie 7", null, "1", null, "mkv", "7", isFavorite = true)))
        answers["get_vod_streams"] = 200 to "[]"
        val result = repository.fetchVodStreams()
        assertTrue(result is Resource.Error && result.cause is EmptyCatalogKept)
        assertEquals(100_000, db.vodDao().getCount())
        assertTrue(db.vodDao().getVodByStreamId(7)!!.isFavorite)
        assertTrue(result.keptCatalogMessage()!!.contains("100000 movies were kept"))
    }

    @Test
    fun storedSeriesSurviveAnEmptyResponse() = runBlocking {
        storeShows(500)
        answers["get_series"] = 200 to "[]"
        val result = repository.fetchSeries()
        assertTrue(result is Resource.Error && result.cause is EmptyCatalogKept)
        assertEquals(500, db.seriesDao().getCount())
    }

    @Test
    fun aNewProviderWithNothingStoredCanStillBeEmpty() = runBlocking {
        answers["get_vod_streams"] = 200 to "[]"
        answers["get_series"] = 200 to "[]"
        val movies = repository.fetchVodStreams()
        val shows = repository.fetchSeries()
        assertTrue(movies is Resource.Success && movies.data!!.isEmpty())
        assertTrue(shows is Resource.Success && shows.data!!.isEmpty())
        assertNull(movies.keptCatalogMessage())
        assertEquals(0, db.vodDao().getCount())
        assertEquals(0, db.seriesDao().getCount())
    }

    @Test
    fun aNullBodyOrServerErrorStillFailsAndKeepsTheCatalog() = runBlocking {
        storeMovies(50)
        answers["get_vod_streams"] = 200 to "null"
        val nullBody = repository.fetchVodStreams()
        assertTrue(nullBody is Resource.Error && nullBody.cause !is EmptyCatalogKept)
        answers["get_vod_streams"] = 500 to "oops"
        val serverError = repository.fetchVodStreams()
        assertTrue(serverError is Resource.Error && serverError.cause !is EmptyCatalogKept)
        assertNull(serverError.keptCatalogMessage())
        assertEquals(50, db.vodDao().getCount())
    }

    @Test
    fun removingTheProviderStillClearsTheCatalog() = runBlocking {
        storeMovies(50)
        storeShows(20)
        repository.clearPrimaryProviderData()
        assertEquals(0, db.vodDao().getCount())
        assertEquals(0, db.seriesDao().getCount())
    }

    @Test
    fun aNormalRefreshStillDropsTitlesTheProviderNoLongerLists() = runBlocking {
        storeMovies(10)
        storeShows(10)
        answers["get_vod_streams"] = 200 to movieJson(1..6)
        answers["get_series"] = 200 to showJson(3..10)
        assertTrue(repository.fetchVodStreams() is Resource.Success)
        assertTrue(repository.fetchSeries() is Resource.Success)
        assertEquals((1..6).toList(), db.vodDao().getUserData().map { it.streamId }.sorted())
        assertEquals((3..10).toList(), db.seriesDao().getUserData().map { it.seriesId }.sorted())
    }
}
