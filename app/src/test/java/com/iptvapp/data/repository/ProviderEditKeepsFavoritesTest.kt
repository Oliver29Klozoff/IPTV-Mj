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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Giving a provider a new address (or password) must keep its favorites and history. Before, any
 * main-provider edit deleted its channels, and every provider's All Providers favorites with them,
 * so Home and Favorites came back empty after the provider moved to a new URL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProviderEditKeepsFavoritesTest {
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
        prefs.saveCredentials("http://old.test", "u", "p")
        prefs.saveExtraServersWithNick(listOf(listOf("http://extra.test", "x", "p", "Extra", "", "true", "xtream", "")))
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

        // A favorite and a recent channel on the main provider, and a favorite on each All Providers slot.
        repository.fetchLiveStreams()
        db.channelDao().setFavorite(101, true)
        db.channelDao().updateLastWatched(101, 1_000L)
        repository.refreshMergedChannels()
        db.mergedChannelDao().setFavorite(-1, 101, true)
        db.mergedChannelDao().setFavorite(0, 101, true)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun mergedFavorites() = db.mergedChannelDao().getAllFavorites().first().map { it.serverIndex }.toSet()

    @Test
    fun aNewAddressForTheMainProviderKeepsFavoritesAndHistory() = runBlocking {
        assertTrue(repository.applyPrimaryProviderEdit("http://moved.test", "u", "p"))
        repository.reloadAfterProviderEdit(primaryChanged = true)

        assertEquals("http://moved.test", prefs.credentials.first().serverUrl)
        val channel = db.channelDao().getFavoriteChannels().first().single()
        assertEquals(101, channel.streamId)
        assertEquals(1_000L, channel.lastWatched)
        assertEquals(setOf(-1, 0), mergedFavorites())
    }

    @Test
    fun aNewPasswordNeedsNoReloadAndKeepsFavorites() = runBlocking {
        assertFalse(repository.applyPrimaryProviderEdit("http://old.test", "u", "p2"))
        assertEquals(1, db.channelDao().getFavoriteChannels().first().size)
        assertEquals(setOf(-1, 0), mergedFavorites())
    }

    @Test
    fun aDifferentMainAccountClearsOnlyItsOwnLists() = runBlocking {
        assertTrue(repository.applyPrimaryProviderEdit("http://other.test", "u2", "p"))
        assertEquals(0, repository.getChannelCount())
        // The extra provider's favorite is not the main account's to clear.
        assertEquals(setOf(0), mergedFavorites())
    }

    @Test
    fun anExtraProviderEditKeepsItsSettingsAndOtherFavorites() = runBlocking {
        prefs.saveExtraServersWithNick(listOf(listOf("http://extra.test", "x", "p", "Extra", "", "false", "xtream", "")))
        repository.applyExtraProviderEdit(0, "http://extra2.test", "x", "p", "Extra", "")
        assertEquals(listOf("http://extra2.test", "x", "p", "Extra", "", "false", "xtream", ""), prefs.getExtraServersWithNick()[0])
        assertEquals(setOf(-1, 0), mergedFavorites())

        repository.applyExtraProviderEdit(0, "http://extra2.test", "y", "p", "Extra", "")
        assertEquals(setOf(-1), mergedFavorites())
    }
}
