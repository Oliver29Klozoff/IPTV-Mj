package com.iptvapp.data.repository

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.dataStore
import kotlinx.coroutines.flow.first
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
 * Your own channel numbers (Edit Channel Number, Auto-Number Favorites) survive a channel refresh,
 * and numbers from a backup wait for their channels, for the login they were restored for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChannelNumberRefreshTest {
    private lateinit var db: IptvDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: XtreamRepository

    private val answers = mapOf(
        "get_live_categories" to """[{"category_id":"1","category_name":"US| SPORTS","parent_id":0},{"category_id":"2","category_name":"UK| NEWS","parent_id":0}]""",
        "get_live_streams" to """[
            {"num":1,"name":"US| NFL REDZONE","stream_type":"live","stream_id":101,"stream_icon":"","epg_channel_id":"","added":"1","category_id":"1","tv_archive":0},
            {"num":2,"name":"UK| BBC NEWS","stream_type":"live","stream_id":202,"stream_icon":"","epg_channel_id":"","added":"1","category_id":"2","tv_archive":0}
        ]""",
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.dataStore.edit { it.clear() }
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        prefs = PreferencesManager(context)
        prefs.saveCredentials("http://main.test", "u", "p")
        prefs.setUsaOnlyChannels(true)
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
    fun aRefreshKeepsYourNumber() = runBlocking {
        repository.fetchLiveStreams()
        repository.assignCustomChannelNumber(101, 206)
        repository.fetchLiveStreams()
        assertEquals(206, db.channelDao().getChannelById(101)?.customNum)
        assertEquals(101, repository.getChannelByNumber(206)?.streamId)
    }

    @Test
    fun aRestoredNumberWaitsForItsChannelEvenOutsideUsaOnly() = runBlocking {
        // Restored before the first channel list: the UK channel would be left out by USA Only.
        repository.setPendingCustomChannelNumbers(listOf(202 to 7))
        repository.fetchLiveStreams()
        assertEquals(7, db.channelDao().getChannelById(202)?.customNum)
        assertTrue(prefs.pendingCustomChannelNumbers.first().none { !it.startsWith("owner:") })
    }

    @Test
    fun anotherLoginsRestoredNumbersAreDropped() = runBlocking {
        repository.setPendingCustomChannelNumbers(listOf(101 to 9))
        prefs.saveCredentials("http://other.test", "u2", "p")
        repository.fetchLiveStreams()
        assertNull(db.channelDao().getChannelById(101)?.customNum)
        assertTrue(prefs.pendingCustomChannelNumbers.first().isEmpty())
    }
}
