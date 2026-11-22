package com.iptvapp.data.profile

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.home.CardDensity
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.dataStore
import com.iptvapp.data.local.entities.CategoryEntity
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.util.KidsFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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
import java.io.File

/** Viewing profiles: the list, Kids filtering, and switching that keeps each profile's own data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: IptvDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: XtreamRepository
    private lateinit var switcher: ProfileSwitcher

    @Before
    fun setUp(): Unit = runBlocking {
        context.dataStore.edit { it.clear() }
        File(context.filesDir, "profiles").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("[]".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        prefs = PreferencesManager(context)
        prefs.saveCredentials("http://main.test", "u", "p")
        repository = XtreamRepository(api, db, prefs, client)
        switcher = ProfileSwitcher(context, db, prefs, repository)
        db.categoryDao().upsertCategories(listOf(
            CategoryEntity("1", "US| SPORTS", 0, "live"), CategoryEntity("2", "XXX ADULT 18+", 0, "live"),
            CategoryEntity("9", "Family Movies", 0, "vod"),
        ))
        db.channelDao().upsertChannels(listOf(channel(1, "1"), channel(2, "1"), channel(3, "2")))
        db.vodDao().upsertVod(listOf(VodEntity(10, "Toy Story", null, "9", null, "mkv", "1")))
    }

    @After
    fun tearDown() = db.close()

    private fun channel(id: Int, cat: String) = ChannelEntity(
        streamId = id, name = "Channel $id", streamIcon = null, categoryId = cat, epgChannelId = null, tvArchive = 0, num = id,
    )

    private suspend fun favorites() = db.channelDao().getFavoriteChannels().first().map { it.streamId }.toSet()

    private suspend fun add(name: String, kids: Boolean = false): Profile =
        (ProfileStore.save(prefs, Profile(name = name, isKids = kids)) as ProfileStore.SaveResult.Saved).profile

    @Test
    fun primaryIsThereFromTheStart() = runBlocking {
        val list = ProfileStore.profilesSaved(prefs)
        assertEquals(listOf(Profile.PRIMARY_ID), list.map { it.id })
        assertEquals(Profile.PRIMARY_ID, ProfileStore.active(prefs).first().id)
        assertFalse(prefs.kidsSafeMode.first())
        // Garbage saved, or Primary missing from what's saved: Primary is still first.
        assertEquals(Profile.PRIMARY_ID, Profile.listFromJson("not json").single().id)
        assertEquals(listOf(Profile.PRIMARY_ID, "x"), Profile.listFromJson("""[{"id":"x","name":"Kid"}]""").map { it.id })
    }

    @Test
    fun addEditAndRemoveProfiles() = runBlocking {
        val kid = add("Kids", kids = true)
        assertTrue(kid.isKids)
        assertEquals(ProfileStore.SaveResult.NoName, ProfileStore.save(prefs, Profile(name = "  ")))
        ProfileStore.save(prefs, kid.copy(name = "Little ones"))
        assertEquals("Little ones", ProfileStore.profilesSaved(prefs).first { it.id == kid.id }.name)
        repeat(4) { add("P$it") }
        assertEquals(Profile.MAX_PROFILES, ProfileStore.profilesSaved(prefs).size)
        assertEquals(ProfileStore.SaveResult.Full, ProfileStore.save(prefs, Profile(name = "Seventh")))
        // Primary can't be removed, nor made a Kids profile.
        assertFalse(ProfileStore.remove(prefs, Profile.PRIMARY_ID))
        ProfileStore.save(prefs, Profile.primary().copy(isKids = true))
        assertFalse(ProfileStore.profilesSaved(prefs).first().isKids)
        assertTrue(ProfileStore.remove(prefs, kid.id))
        assertEquals(Profile.MAX_PROFILES - 1, ProfileStore.profilesSaved(prefs).size)
    }

    @Test
    fun theActiveProfileCantBeRemoved() = runBlocking {
        val dad = add("Dad")
        switcher.switchTo(dad.id)
        assertFalse(ProfileStore.remove(prefs, dad.id))
    }

    @Test
    fun askOnStartOnlyWithTwoOrMoreProfiles() {
        assertFalse(ProfileStore.shouldAskOnStart(null, 1))
        assertTrue(ProfileStore.shouldAskOnStart(null, 2))
        assertFalse(ProfileStore.shouldAskOnStart(false, 3))
        assertFalse(ProfileStore.shouldAskOnStart(true, 1))
    }

    @Test
    fun kidsFilterBlocksAdultCategoriesOnly() {
        listOf("Adult 18+", "XXX Movies", "US| PORN", "18+ Late Night", "NSFW", "Playboy TV").forEach {
            assertFalse(it, KidsFilter.isCategoryAllowed(it, isKidsProfile = true))
        }
        listOf("Family", "Kids Cartoons", "Live Sports", "Essex News", "US| ESPN", "Sussex FM").forEach {
            assertTrue(it, KidsFilter.isCategoryAllowed(it, isKidsProfile = true))
        }
        assertTrue(KidsFilter.isCategoryAllowed("XXX Movies", isKidsProfile = false))
    }

    @Test
    fun eachProfileKeepsItsOwnFavoritesHistoryAndHome() = runBlocking {
        // Primary as it was before profiles: a favorite, a movie half watched, Expanded cards.
        db.channelDao().setFavorite(1, true)
        db.vodDao().updateWatchProgress(10, 600_000L, 1_200_000L)
        prefs.setCardDensity(CardDensity.EXPANDED)
        val kid = add("Kids", kids = true)

        assertTrue(switcher.switchTo(kid.id))
        assertEquals(kid.id, prefs.activeProfileId.first())
        assertTrue(prefs.kidsSafeMode.first())
        assertEquals(emptySet<Int>(), favorites())
        assertEquals(0L, db.vodDao().getVodByStreamId(10)?.watchedMs)
        assertEquals(CardDensity.STANDARD, prefs.cardDensity.first())
        db.channelDao().setFavorite(2, true)

        assertTrue(switcher.switchTo(Profile.PRIMARY_ID))
        assertFalse(prefs.kidsSafeMode.first())
        assertEquals(setOf(1), favorites())
        assertEquals(600_000L, db.vodDao().getVodByStreamId(10)?.watchedMs)
        assertEquals(CardDensity.EXPANDED, prefs.cardDensity.first())

        assertTrue(switcher.switchTo(kid.id))
        assertEquals(setOf(2), favorites())
    }

    @Test
    fun aKidsProfileHidesAdultCategoriesAndTheirChannels() = runBlocking {
        val kid = add("Kids", kids = true)
        assertEquals(setOf("1", "2"), repository.getLiveCategories().first().map { it.categoryId }.toSet())
        switcher.switchTo(kid.id)
        assertEquals(listOf("1"), repository.getLiveCategories().first().map { it.categoryId })
        assertEquals(setOf(1, 2), repository.getAllChannels().first().map { it.streamId }.toSet())
        // Movies outside adult categories stay.
        assertEquals(listOf(10), repository.getAllVod().first().map { it.streamId })
    }

    @Test
    fun savedFavoritesStayWithTheLoginTheyCameFrom() = runBlocking {
        db.channelDao().setFavorite(1, true)
        val dad = add("Dad")
        switcher.switchTo(dad.id)
        // The main provider is now another account, whose streamId 1 is another channel.
        prefs.saveCredentials("http://other.test", "u2", "p")
        switcher.switchTo(Profile.PRIMARY_ID)
        assertEquals(emptySet<Int>(), favorites())
    }

    @Test
    fun anotherProfilesFavoritesNeverChangeTheHouseholdChannelNumbers() = runBlocking {
        repository.assignCustomChannelNumber(1, 206)
        val kid = add("Kids", kids = true)
        switcher.switchTo(kid.id)
        repository.toggleChannelFavorite(1) // on
        repository.toggleChannelFavorite(1) // off: Primary's number stays
        assertEquals(206, db.channelDao().getChannelById(1)?.customNum)
    }

    @Test
    fun aSwitchCutShortIsFinishedAtNextStart() = runBlocking {
        db.channelDao().setFavorite(1, true)
        val kid = add("Kids", kids = true)
        switcher.switchTo(kid.id)
        // Stopped half way back to Primary: Primary's data saved, the marker written, nothing applied.
        File(File(context.filesDir, "profiles"), "switching.json").writeText("""{"to":"${Profile.PRIMARY_ID}"}""")
        switcher.recoverInterruptedSwitch()
        assertEquals(Profile.PRIMARY_ID, prefs.activeProfileId.first())
        assertEquals(setOf(1), favorites())
        assertFalse(File(File(context.filesDir, "profiles"), "switching.json").exists())
    }
}
