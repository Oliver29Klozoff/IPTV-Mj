package com.iptvapp.data.search

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.channel.ChannelNumbering
import com.iptvapp.data.channel.LcnMode
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.dataStore
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
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

/** Finding channels by the number their row shows ("206"), in search and the channel lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChannelNumberSearchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: IptvDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: XtreamRepository

    private fun channel(id: Int, name: String, num: Int, customNum: Int? = null) =
        ChannelEntity(streamId = id, name = name, streamIcon = null, categoryId = "1", epgChannelId = null, tvArchive = 0, num = num)
            .copy(customNum = customNum)

    // ESPN is 206 by its own number though the provider calls it 15; Fox is the provider's 2061.
    private val espn = channel(1, "ESPN", num = 15, customNum = 206)
    private val fox = channel(2, "Fox", num = 2061)
    private val news = channel(3, "206 News", num = 50)
    private val hidden = channel(4, "Hidden", num = 2062).copy(isHidden = true)
    private val cnn = channel(5, "CNN", num = 0)

    @Before
    fun setUp(): Unit = runBlocking {
        context.dataStore.edit { it.clear() }
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("[]".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        prefs = PreferencesManager(context)
        repository = XtreamRepository(api, db, prefs, client)
        db.channelDao().upsertChannels(listOf(espn, fox, news, hidden, cnn))
    }

    @After
    fun tearDown() = db.close()

    private fun search(q: String) = runBlocking { repository.searchChannels(q).first().map { it.name } }

    @Test
    fun digitsFindTheChannelWithThatNumberFirst() {
        // Exact number, then other numbers starting 206, then names; never a hidden channel.
        assertEquals(listOf("ESPN", "Fox", "206 News"), search("206"))
        // As you type: "2" already finds them, lowest number first.
        assertEquals(listOf("ESPN", "Fox"), search("2").take(2))
        // A number the custom one replaced no longer finds the channel.
        assertEquals(emptyList<String>(), search("15"))
    }

    @Test
    fun wordsStillSearchNames() {
        assertEquals(listOf("ESPN"), search("ESPN"))
        assertEquals(listOf("ESPN"), search("esp"))
    }

    @Test
    fun theNumberSettingDecidesWhichNumberIsSearched() = runBlocking {
        prefs.setLcnMode(LcnMode.PROVIDER_RAW)
        assertEquals(listOf("ESPN"), search("15"))
        assertEquals(listOf("206 News"), search("206").filter { it != "Fox" })
        assertEquals(listOf("Fox", "206 News"), search("206"))
        // Off and 1, 2, 3 numbering: nothing found by number, names still found.
        prefs.setLcnMode(LcnMode.OFF)
        assertEquals(listOf("206 News"), search("206"))
        prefs.setLcnMode(LcnMode.SEQUENTIAL)
        assertEquals(listOf("206 News"), search("206"))
    }

    @Test
    fun whatCountsAsANumber() {
        assertEquals("206", ChannelNumbering.numberQuery(" 206 "))
        assertEquals("7", ChannelNumbering.numberQuery("007"))
        assertNull(ChannelNumbering.numberQuery("0"))
        assertNull(ChannelNumbering.numberQuery("123456"))
        assertNull(ChannelNumbering.numberQuery("99999999999999999999"))
        assertNull(ChannelNumbering.numberQuery("2a"))
        assertNull(ChannelNumbering.numberQuery(""))
        assertTrue(ChannelNumbering.matchesNumber(LcnMode.CUSTOM_FIRST, espn, "20"))
        // A provider number of 0 is no number.
        assertNull(ChannelNumbering.searchableNumber(LcnMode.CUSTOM_FIRST, cnn))
        assertFalse(ChannelNumbering.matchesNumber(LcnMode.OFF, espn, "206"))
    }

    @Test
    fun universalSearchPutsTheExactNumberFirst() {
        val other = MergedChannelEntity(2, 206, "206 Backup", null, 206, "Backup", categoryId = "1", categoryName = "Sports")
        val hits = listOf(
            SearchHit.Movie(VodEntity(9, "206", null, "9", null, "mkv", "1"), null, "Primary"),
            SearchHit.Channel(null, other, "Backup"),
            SearchHit.Channel(fox, null, "Primary"),
            SearchHit.Channel(news, null, "Primary"),
            SearchHit.Channel(espn, null, "Primary"),
        )
        val ranked = UniversalSearch.rank(hits, "206", LcnMode.CUSTOM_FIRST).map { it.title }
        // ESPN is numbered 206; a film titled exactly "206" comes next; Fox (2061) ranks with
        // names starting "206". Another provider's channel is never found by its own number.
        assertEquals(listOf("ESPN", "206", "Fox", "206 News", "206 Backup"), ranked)
        // Without numbers on, the film titled "206" wins.
        assertEquals("206", UniversalSearch.rank(hits, "206", LcnMode.OFF).first().title)
        assertEquals(206, UniversalSearch.channelNumberOf(SearchHit.Channel(espn, null, "Primary"), LcnMode.CUSTOM_FIRST))
        assertNull(UniversalSearch.channelNumberOf(SearchHit.Channel(null, other, "Backup"), LcnMode.CUSTOM_FIRST))
    }

    @Test
    fun oneDigitIsEnoughOnlyWhenNumbersAreSearched() {
        assertTrue(UniversalSearch.canSearch("7", LcnMode.CUSTOM_FIRST))
        assertTrue(UniversalSearch.canSearch("7", LcnMode.PROVIDER_RAW))
        assertFalse(UniversalSearch.canSearch("7", LcnMode.OFF))
        assertFalse(UniversalSearch.canSearch("a", LcnMode.CUSTOM_FIRST))
        assertFalse(UniversalSearch.canSearch("0", LcnMode.CUSTOM_FIRST))
        assertTrue(UniversalSearch.canSearch("es", LcnMode.OFF))
    }
}
