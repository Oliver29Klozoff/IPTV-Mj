package com.iptvapp.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.MergedVodEntity
import com.iptvapp.data.local.entities.VodEntity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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

/** The TV Movies search against a real (in-memory) database: every provider, joined words, filters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MovieSearchEverywhereTest {
    private lateinit var db: IptvDatabase
    private lateinit var repository: XtreamRepository
    private val all = XtreamRepository.MovieSearchFilter()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        repository = XtreamRepository(api, db, PreferencesManager(context), client)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun movie(id: Int, name: String, category: String = "1", favorite: Boolean = false) = VodEntity(
        streamId = id, name = name, streamIcon = null, categoryId = category, rating = null,
        containerExtension = "mkv", added = null, isFavorite = favorite
    )

    private fun other(id: Int, name: String, category: String = "EN | Movies", hidden: Boolean = false) = MergedVodEntity(
        serverIndex = 1, streamId = id, name = name, streamIcon = null, serverNickname = "Backup",
        categoryId = "9", categoryName = category, rating = null, containerExtension = "mkv", added = null,
        isHidden = hidden
    )

    private fun names(found: Pair<List<VodEntity>, List<MergedVodEntity>>) =
        (found.first.map { it.name } + found.second.map { it.name }).toSet()

    @Test
    fun findsTheMainProviderAndEveryOtherOneButNotHiddenMovies() = runBlocking {
        db.vodDao().upsertVod(listOf(movie(1, "EN - Alien (1979)"), movie(2, "Batman Begins")))
        db.mergedVodDao().upsertAll(listOf(other(1, "Aliens"), other(2, "Alien Romulus", hidden = true)))
        assertEquals(setOf("EN - Alien (1979)", "Aliens"), names(repository.searchMoviesEverywhere("alien", all)))
    }

    @Test
    fun findsTitlesTypedWithTheirWordsJoined() = runBlocking {
        db.vodDao().upsertVod(listOf(
            movie(1, "Spider-Man: No Way Home"), movie(2, "EN - Ant-Man (2015)"),
            movie(3, "Ip Man"), movie(4, "X-Men"), movie(5, "A Beautiful Mind"), movie(6, "Manhunt")
        ))
        db.mergedVodDao().upsertAll(listOf(other(1, "The Amazing Spider-Man")))
        assertEquals(setOf("Spider-Man: No Way Home", "The Amazing Spider-Man"), names(repository.searchMoviesEverywhere("spiderman", all)))
        assertEquals(setOf("EN - Ant-Man (2015)"), names(repository.searchMoviesEverywhere("antman", all)))
        assertEquals(setOf("Ip Man"), names(repository.searchMoviesEverywhere("ipman", all)))
        assertEquals(setOf("X-Men"), names(repository.searchMoviesEverywhere("xmen", all)))
        assertEquals(setOf("A Beautiful Mind"), names(repository.searchMoviesEverywhere("abeautifulmind", all)))
    }

    // More matches than the search returns: the filters must pick from all of them, not from the
    // ones that made the cut.
    @Test
    fun filtersApplyBeforeTheCap() = runBlocking {
        db.vodDao().upsertVod((1..6_000).map { movie(it, "The $it", category = "2") } + listOf(
            movie(10_001, "The Story Of A Very Long Favorite Title", category = "2", favorite = true),
            movie(10_002, "The Story Of A Very Long Title In Category One", category = "1")
        ))
        val favorites = repository.searchMoviesEverywhere("the", all.copy(favoritesOnly = true))
        assertEquals(setOf("The Story Of A Very Long Favorite Title"), names(favorites))
        val categoryOne = repository.searchMoviesEverywhere("the", all.copy(categoryIds = listOf("1")))
        assertEquals(setOf("The Story Of A Very Long Title In Category One"), names(categoryOne))
        val picked = repository.searchMoviesEverywhere("the", all.copy(streamIds = listOf(17, 5_999)))
        assertEquals(setOf("The 17", "The 5999"), names(picked))
    }

    @Test
    fun otherProvidersCanBeLeftOutOrLimitedToCategories() = runBlocking {
        db.vodDao().upsertVod(listOf(movie(1, "Alien")))
        db.mergedVodDao().upsertAll(listOf(other(1, "Aliens"), other(2, "Alien le passager", category = "FR | Films")))
        assertEquals(setOf("Alien"), names(repository.searchMoviesEverywhere("alien", all.copy(includeOtherProviders = false))))
        val english = repository.searchMoviesEverywhere("alien", all.copy(otherCategoryNames = listOf("EN | Movies")))
        assertEquals(setOf("Alien", "Aliens"), names(english))
    }

    @Test
    fun aQueryOfOnlyPunctuationFindsNothing() = runBlocking {
        db.vodDao().upsertVod(listOf(movie(1, "Alien")))
        val found = repository.searchMoviesEverywhere("--", all)
        assertTrue(found.first.isEmpty() && found.second.isEmpty())
    }
}
