package com.iptvapp.data.search

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.dataStore
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.data.local.entities.MergedSeriesEntity
import com.iptvapp.data.local.entities.MergedVodEntity
import com.iptvapp.data.local.entities.SeriesEntity
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.data.repository.XtreamRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Favoriting from search results: which favorite a result is, and that the star is saved where the lists read it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchFavoritesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: IptvDatabase
    private lateinit var repository: XtreamRepository

    private val espn = ChannelEntity(streamId = 7, name = "ESPN", streamIcon = null, categoryId = "1", epgChannelId = null, tvArchive = 0, num = 7)
    private val espnOther = MergedChannelEntity(2, 7, "ESPN", null, 7, "Backup", categoryId = "1", categoryName = "Sports")
    private val movie = VodEntity(10, "Toy Story", null, "9", null, "mkv", "1")
    private val movieOther = MergedVodEntity(2, 10, "Toy Story", null, "Backup", "9", "Family", null, "mkv", null)
    private val show = SeriesEntity(20, "Bluey", null, null, null, null, "5")
    private val showOther = MergedSeriesEntity(2, 20, "Bluey", null, null, null, null, "Backup", "5", "Kids")

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
        repository = XtreamRepository(api, db, PreferencesManager(context), client)
        db.channelDao().upsertChannels(listOf(espn))
        db.mergedChannelDao().upsertAll(listOf(espnOther))
        db.vodDao().upsertVod(listOf(movie))
        db.mergedVodDao().upsertAll(listOf(movieOther))
        db.seriesDao().upsertSeries(listOf(show))
        db.mergedSeriesDao().upsertAll(listOf(showOther))
    }

    @After
    fun tearDown() = db.close()

    private fun program(channel: ChannelEntity?, merged: MergedChannelEntity?) =
        SearchHit.Program(channel, merged, "SportsCenter", 0L, 1L, SearchHit.Kind.ON_NOW, "Primary")

    @Test
    fun eachCopyIsItsOwnFavoriteAndAProgrammeIsItsChannel() {
        val primary = SearchFavorites.keyOf(SearchHit.Channel(espn, null, "Primary"))
        val other = SearchFavorites.keyOf(SearchHit.Channel(null, espnOther, "Backup"))
        assertEquals("channel:-1:7", primary)
        assertEquals("channel:2:7", other)
        // The same programme on the same channel: one star for both rows.
        assertEquals(primary, SearchFavorites.keyOf(program(espn, null)))
        assertEquals(other, SearchFavorites.keyOf(program(null, espnOther)))
        // Same id, different kinds or providers: never the same favorite.
        val keys = listOf(
            primary, other,
            SearchFavorites.keyOf(SearchHit.Movie(movie, null, "Primary")),
            SearchFavorites.keyOf(SearchHit.Movie(null, movieOther, "Backup")),
            SearchFavorites.keyOf(SearchHit.Series(show, null, "Primary")),
            SearchFavorites.keyOf(SearchHit.Series(null, showOther, "Backup")),
        )
        assertEquals(keys.size, keys.toSet().size)
        assertNotEquals(SearchFavorites.keyOf(SearchHit.Movie(movie, null, "Primary")), SearchFavorites.keyOf(SearchHit.Series(show, null, "Primary")))
    }

    @Test
    fun episodesHaveNoStar() {
        val episode = SearchHit.Episode(SearchHit.Series(show, null, "Primary"), 1, 2, "Hammerbarn")
        assertNull(SearchFavorites.keyOf(episode))
        assertFalse(SearchFavorites.canFavorite(episode))
        assertFalse(SearchFavorites.isFavorite(episode))
    }

    @Test
    fun aChangeOnTheScreenWinsOverWhatTheSearchFound() {
        val hit = SearchHit.Channel(espn.copy(isFavorite = true), null, "Primary")
        assertTrue(SearchFavorites.isFavorite(hit))
        assertFalse(SearchFavorites.isFavorite(hit, mapOf("channel:-1:7" to false)))
        // A change on another channel leaves this one as found.
        assertTrue(SearchFavorites.isFavorite(hit, mapOf("channel:2:7" to false)))
        assertEquals("ESPN", SearchFavorites.nameOf(program(espn, null)))
    }

    @Test
    fun aMainProviderChannelIsStarredAndUnstarred() = runBlocking {
        val hit = SearchHit.Channel(espn, null, "Primary")
        assertTrue(SearchFavorites.set(repository, hit, true))
        assertTrue(db.channelDao().getChannelById(7)!!.isFavorite)
        // Asked twice: still a favorite (the underlying toggle isn't flipped back).
        SearchFavorites.set(repository, hit, true)
        assertTrue(db.channelDao().getChannelById(7)!!.isFavorite)
        SearchFavorites.set(repository, hit, false)
        assertFalse(db.channelDao().getChannelById(7)!!.isFavorite)
    }

    @Test
    fun aProgrammeStarsItsChannel() = runBlocking {
        SearchFavorites.set(repository, program(null, espnOther), true)
        assertTrue(db.mergedChannelDao().getByIndexAndId(2, 7)!!.isFavorite)
        // The main provider's channel with the same id is another channel.
        assertFalse(db.channelDao().getChannelById(7)!!.isFavorite)
    }

    @Test
    fun filmsAndSeriesOnAnyProviderAreSaved() = runBlocking {
        SearchFavorites.set(repository, SearchHit.Movie(movie, null, "Primary"), true)
        SearchFavorites.set(repository, SearchHit.Movie(null, movieOther, "Backup"), true)
        SearchFavorites.set(repository, SearchHit.Series(show, null, "Primary"), true)
        SearchFavorites.set(repository, SearchHit.Series(null, showOther, "Backup"), true)
        assertTrue(db.vodDao().getVodByStreamId(10)!!.isFavorite)
        assertTrue(db.mergedVodDao().getByIndexAndId(2, 10)!!.isFavorite)
        assertTrue(db.seriesDao().getSeriesById(20)!!.isFavorite)
        assertTrue(db.mergedSeriesDao().getByIndexAndId(2, 20)!!.isFavorite)

        SearchFavorites.set(repository, SearchHit.Series(null, showOther, "Backup"), false)
        assertFalse(db.mergedSeriesDao().getByIndexAndId(2, 20)!!.isFavorite)
        assertTrue(db.seriesDao().getSeriesById(20)!!.isFavorite)
    }
}
