package com.iptvapp.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Editing a provider clears its lists. They must be downloaded again straight away: before, the
 * "last downloaded" times stayed recent, and Live, categories and home stayed empty for hours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProviderEditReloadTest {
    private lateinit var db: IptvDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: XtreamRepository

    // Runs while the categories download, to switch the login part way.
    private var duringCategories: () -> Unit = {}

    private val answers = mapOf(
        "get_vod_categories" to """[{"category_id":"5","category_name":"Movies","parent_id":0}]""",
        "get_series_categories" to """[{"category_id":"6","category_name":"Shows","parent_id":0}]""",
        "get_vod_streams" to """[{"num":1,"name":"Movie 1","stream_type":"movie","stream_id":1,"stream_icon":"","rating":"7","added":"1","category_id":"1","container_extension":"mkv"}]""",
        "get_series" to """[{"num":1,"name":"Show 1","series_id":1,"cover":"","plot":"","genre":"Drama","rating":"7","category_id":"1"}]""",
        "get_live_categories" to """[{"category_id":"1","category_name":"US| SPORTS","parent_id":0}]""",
        "get_live_streams" to """[{"num":1,"name":"US| NFL REDZONE","stream_type":"live","stream_id":101,"stream_icon":"","epg_channel_id":"","added":"1","category_id":"1","tv_archive":0}]""",
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        prefs = PreferencesManager(context)
        prefs.saveCredentials("http://new.test", "u", "p")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.queryParameter("action") == "get_live_categories") duringCategories()
            val body = answers[request.url.queryParameter("action").orEmpty()] ?: "[]"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        repository = XtreamRepository(api, db, prefs, client)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aSwitchedMainProviderGetsItsChannelsAndCategoriesBack() = runBlocking {
        // The lists were "downloaded" moments ago, then the switch cleared them.
        prefs.setLastChannelsFetchTime(System.currentTimeMillis())
        repository.clearPrimaryProviderData()
        assertEquals(0, repository.getLiveCategoryCount())

        repository.reloadAfterProviderEdit(primaryChanged = true)

        assertEquals(1, repository.getLiveCategoryCount())
        assertEquals(1, repository.getChannelCount())
        assertEquals(1, repository.getVodCount())
        assertEquals(1, repository.getSeriesCount())
        assertTrue(prefs.lastMergedChannelsRefresh.first() > 0L)
    }

    @Test
    fun anOtherProviderEditAlsoFinishesMissingMainLists() = runBlocking {
        // An earlier switch's reload was stopped part way: the main lists are still empty.
        repository.reloadAfterProviderEdit(primaryChanged = false)
        assertEquals(1, repository.getChannelCount())
        assertEquals(1, repository.getLiveCategoryCount())
        assertEquals(1, repository.getVodCount())
        assertEquals(1, db.categoryDao().countByType("vod"))
        assertEquals(1, db.categoryDao().countByType("series"))
        assertTrue(prefs.lastMergedChannelsRefresh.first() > 0L)
    }

    @Test
    fun categoriesDownloadedForTheOldLoginAreNotSaved() = runBlocking {
        duringCategories = { runBlocking { prefs.saveCredentials("http://other.test", "u2", "p") } }
        val result = repository.fetchLiveCategories()
        assertTrue(result is com.iptvapp.util.Resource.Error)
        assertEquals(0, repository.getLiveCategoryCount())
    }

    @Test
    fun aNewEditStopsTheEarlierReloadFirst() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        duringCategories = { started.countDown(); release.await(30, java.util.concurrent.TimeUnit.SECONDS) }
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class) val reload = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            repository.reloadAfterProviderEdit(primaryChanged = true)
        }
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        kotlinx.coroutines.withTimeout(10_000) { repository.stopProviderEditReload() }
        kotlinx.coroutines.withTimeout(10_000) { reload.join() }
        assertTrue(reload.isCancelled)
        release.countDown()
        Unit
    }
}
