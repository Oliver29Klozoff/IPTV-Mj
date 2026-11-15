package com.iptvapp.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.flow.first
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

    private val answers = mapOf(
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
        assertTrue(prefs.lastMergedChannelsRefresh.first() > 0L)
    }

    @Test
    fun anOtherProviderEditLeavesTheMainListsAlone() = runBlocking {
        repository.reloadAfterProviderEdit(primaryChanged = false)
        assertEquals(0, repository.getChannelCount())
        assertTrue(prefs.lastMergedChannelsRefresh.first() > 0L)
    }
}
