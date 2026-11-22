package com.iptvapp.data.channel

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.dataStore
import com.iptvapp.data.local.entities.ChannelEntity
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

/** Settings → Channel Numbers: numbering modes, number entry, Edit Channel Number and Auto-Number Favorites. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChannelNumberingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: IptvDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: XtreamRepository

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
        prefs.saveCredentials("http://provider.test", "u", "p")
        repository = XtreamRepository(api, db, prefs, client)
    }

    @After
    fun tearDown() = db.close()

    private fun channel(
        id: Int, num: Int, customNum: Int? = null, favorite: Boolean = false, favOrder: Int = 0, name: String = "Channel $id",
    ) = ChannelEntity(
        streamId = id, name = name, streamIcon = null, categoryId = "1", epgChannelId = null, tvArchive = 0,
        num = num, isFavorite = favorite, favOrder = favOrder, customNum = customNum,
    )

    @Test
    fun fromIdFallsBackToCustomFirst() {
        assertEquals(LcnMode.CUSTOM_FIRST, LcnMode.fromId(null))
        assertEquals(LcnMode.CUSTOM_FIRST, LcnMode.fromId(""))
        assertEquals(LcnMode.CUSTOM_FIRST, LcnMode.fromId("cable_box"))
        assertEquals(LcnMode.SEQUENTIAL, LcnMode.fromId("SEQUENTIAL"))
        LcnMode.entries.forEach { assertEquals(it, LcnMode.fromId(it.id)) }
    }

    @Test
    fun eachModeShowsItsOwnNumber() {
        val pinned = channel(1, num = 45_503, customNum = 206)
        val plain = channel(2, num = 7)
        val unnumbered = channel(3, num = 0)
        assertEquals(206, ChannelNumbering.displayNumber(LcnMode.CUSTOM_FIRST, pinned, 4))
        assertEquals(7, ChannelNumbering.displayNumber(LcnMode.CUSTOM_FIRST, plain, 4))
        assertNull(ChannelNumbering.displayNumber(LcnMode.CUSTOM_FIRST, unnumbered, 4))
        assertEquals(5, ChannelNumbering.displayNumber(LcnMode.SEQUENTIAL, pinned, 4))
        assertEquals(1, ChannelNumbering.displayNumber(LcnMode.SEQUENTIAL, plain, 0))
        assertEquals(45_503, ChannelNumbering.displayNumber(LcnMode.PROVIDER_RAW, pinned, 4))
        assertNull(ChannelNumbering.displayNumber(LcnMode.PROVIDER_RAW, unnumbered, 4))
        assertNull(ChannelNumbering.displayNumber(LcnMode.OFF, pinned, 4))
    }

    @Test
    fun remoteDigitsTypeUpToFiveDigits() {
        assertEquals('0', ChannelNumbering.digitOf(KeyEvent.KEYCODE_0))
        assertEquals('9', ChannelNumbering.digitOf(KeyEvent.KEYCODE_9))
        assertEquals('0', ChannelNumbering.digitOf(KeyEvent.KEYCODE_NUMPAD_0))
        assertEquals('7', ChannelNumbering.digitOf(KeyEvent.KEYCODE_NUMPAD_7))
        assertNull(ChannelNumbering.digitOf(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(ChannelNumbering.digitOf(KeyEvent.KEYCODE_STAR))
        var buffer = ""
        "1234567".forEach { buffer = ChannelNumbering.append(buffer, it) }
        assertEquals("12345", buffer)
        assertEquals(ChannelNumbering.MAX_DIGITS, buffer.length)
        assertTrue(ChannelNumbering.TUNE_DELAY_MS in 1_200L..1_500L)
    }

    @Test
    fun sequentialPicksThatPlaceInTheList() {
        val list = listOf("a", "b", "c")
        assertEquals("a", ChannelNumbering.sequentialPick(list, 1))
        assertEquals("c", ChannelNumbering.sequentialPick(list, 3))
        assertNull(ChannelNumbering.sequentialPick(list, 0))
        assertNull(ChannelNumbering.sequentialPick(list, 4))
    }

    @Test
    fun yourNumberWinsOverTheProvidersUnlessNumberingIsProvider(): Unit = runBlocking {
        db.channelDao().upsertChannels(listOf(channel(10, num = 206), channel(11, num = 45_503, customNum = 206)))
        assertEquals(11, repository.getChannelByNumber(206)?.streamId)
        assertEquals(11, repository.findChannelByNumber(206, LcnMode.CUSTOM_FIRST)?.streamId)
        assertEquals(11, repository.findChannelByNumber(206, LcnMode.OFF)?.streamId)
        assertEquals(10, repository.findChannelByNumber(206, LcnMode.PROVIDER_RAW)?.streamId)
        assertEquals(11, repository.findChannelByNumber(45_503, LcnMode.PROVIDER_RAW)?.streamId)
        assertNull(repository.findChannelByNumber(9_999, LcnMode.CUSTOM_FIRST))
    }

    @Test
    fun aNumberBelongsToOneChannel(): Unit = runBlocking {
        db.channelDao().upsertChannels(listOf(channel(20, num = 1, customNum = 206, name = "ESPN"), channel(21, num = 2, name = "CNN")))
        val displaced = repository.assignCustomChannelNumber(21, 206)
        assertEquals(listOf(20), displaced.map { it.streamId })
        assertNull(db.channelDao().getChannelById(20)?.customNum)
        assertEquals(206, db.channelDao().getChannelById(21)?.customNum)
        assertEquals(21, repository.getChannelByNumber(206)?.streamId)
        // Reset to Provider.
        assertTrue(repository.assignCustomChannelNumber(21, null).isEmpty())
        assertNull(db.channelDao().getChannelById(21)?.customNum)
    }

    @Test
    fun autoNumberFavoritesGivesOneToNInFavoritesOrder(): Unit = runBlocking {
        db.channelDao().upsertChannels(listOf(
            channel(30, num = 500, favorite = true, favOrder = 2, customNum = 205),
            channel(31, num = 501, favorite = true, favOrder = 0),
            channel(32, num = 502, favorite = true, favOrder = 1, customNum = 4),
            channel(33, num = 503, customNum = 2), // not a favorite, but holds one of the new numbers
            channel(34, num = 504, customNum = 900), // outside 1..N: kept
        ))
        assertEquals(3, repository.backfillSequentialFavoriteNumbers())
        assertEquals(1, db.channelDao().getChannelById(31)?.customNum)
        assertEquals(2, db.channelDao().getChannelById(32)?.customNum)
        assertEquals(3, db.channelDao().getChannelById(30)?.customNum)
        assertNull(db.channelDao().getChannelById(33)?.customNum)
        assertEquals(900, db.channelDao().getChannelById(34)?.customNum)
        assertEquals(31, repository.getChannelByNumber(1)?.streamId)
        assertEquals(32, repository.getChannelByNumber(2)?.streamId)
    }

    @Test
    fun autoNumberWithNoFavoritesChangesNothing(): Unit = runBlocking {
        db.channelDao().upsertChannels(listOf(channel(40, num = 1, customNum = 1)))
        assertEquals(0, repository.backfillSequentialFavoriteNumbers())
        assertEquals(1, db.channelDao().getChannelById(40)?.customNum)
    }

    @Test
    fun settingsDefaultToCustomFirstAndTuningOn(): Unit = runBlocking {
        assertEquals(LcnMode.CUSTOM_FIRST, prefs.lcnMode.first())
        assertTrue(prefs.directNumberTuning.first())
        prefs.setLcnMode(LcnMode.SEQUENTIAL)
        prefs.setDirectNumberTuning(false)
        assertEquals(LcnMode.SEQUENTIAL, prefs.lcnMode.first())
        assertFalse(prefs.directNumberTuning.first())
    }
}
