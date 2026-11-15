package com.iptvapp.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Editing the primary provider's URL while the old server is stuck sending the channel list: the
 * save must not wait for that download (it used to hold the channel lock until the server gave up).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProviderSwitchDuringDownloadTest {
    private lateinit var db: IptvDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: XtreamRepository
    private val downloadStarted = CountDownLatch(1)
    private val releaseServer = CountDownLatch(1)

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        prefs = PreferencesManager(context)
        prefs.saveCredentials("http://old.test", "u", "p")
        // The old provider never finishes the channel list; everything else answers at once.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.queryParameter("action") == "get_live_streams") {
                downloadStarted.countDown()
                releaseServer.await(30, TimeUnit.SECONDS)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("[]".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        repository = XtreamRepository(api, db, prefs, client)
    }

    @After
    fun tearDown() {
        releaseServer.countDown()
        db.close()
    }

    @Test
    fun aNewUrlSavesWhileTheOldServerIsStillSendingChannels() = runBlocking {
        val download = async(Dispatchers.Default) { runCatching { repository.fetchLiveStreams() } }
        assertTrue("download started", downloadStarted.await(10, TimeUnit.SECONDS))
        withTimeout(10_000) {
            repository.clearPrimaryProviderData { prefs.saveCredentials("http://new.test", "u", "p") }
        }
        assertEquals("http://new.test", prefs.credentials.first().serverUrl)
        withTimeout(10_000) { download.await() }
        Unit
    }
}
