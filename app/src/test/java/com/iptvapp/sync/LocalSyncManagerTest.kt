package com.iptvapp.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iptvapp.data.api.XtreamApiService
import com.iptvapp.data.home.TopFavorites
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.handoff.AppHandoffCatalog
import com.iptvapp.handoff.HandoffCrypto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
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

/** Wi-Fi favorites sync: no logins on the wire, and provider-specific ids only between the same provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalSyncManagerTest {
    private lateinit var db: IptvDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var sync: LocalSyncManager
    private val token = "a".repeat(64)

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, IptvDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient()
        val api = Retrofit.Builder().baseUrl("http://localhost/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(XtreamApiService::class.java)
        prefs = PreferencesManager(context)
        runBlocking {
            prefs.saveCredentials("http://provider.test", "user1", "secret-pass")
            prefs.saveExtraServersWithNick(listOf(listOf("http://backup.test", "user2", "backup-pass", "Backup", "", "true", "xtream", "")))
            db.channelDao().upsertChannels(listOf(channel(10, "CNN"), channel(11, "ESPN"), channel(12, "HBO")))
            // The DataStore outlives each test; start every test from the same settings.
            prefs.setFavoriteLiveCategoryIds(emptySet())
            prefs.setTopFavoritesJson("")
        }
        val repo = XtreamRepository(api, db, prefs, client)
        sync = LocalSyncManager(db, prefs, repo, com.iptvapp.data.smart.SmartChannelStore(db, repo))
    }

    @After
    fun tearDown() = db.close()

    private fun channel(id: Int, name: String, favorite: Boolean = false) = ChannelEntity(
        streamId = id, name = name, streamIcon = null, categoryId = "1", epgChannelId = null, tvArchive = 0,
        num = id, isFavorite = favorite
    )

    private fun tag(url: String, user: String) = HandoffCrypto.providerTag(token, AppHandoffCatalog.identity(url, user))

    private fun payload(primary: String, extra: String? = null, favorites: Map<Int, String>) = JSONObject().apply {
        put("device", "Pixel")
        put("providers", JSONObject().put("-1", primary).apply { if (extra != null) put("0", extra) })
        put("favoriteChannelIds", JSONArray(favorites.keys.toList()))
        put("channelNames", JSONObject(favorites.mapKeys { it.key.toString() }))
        put("favoriteCategoryIds", JSONArray(listOf("77")))
        // Slot 3 is a provider disabled on the sender: no tag, so it can't be trusted here.
        put("topFavorites", TopFavorites.toJson(TopFavorites.State(listOf(TopFavorites.ref(-1, 10), TopFavorites.ref(0, 500), TopFavorites.ref(3, 77)))))
    }

    @Test
    fun payloadCarriesNoLoginOrServerAddress() = runBlocking {
        db.channelDao().setFavorite(10, true)
        val text = sync.buildSyncPayload(token).toString()
        for (secret in listOf("secret-pass", "backup-pass", "user1", "user2", "provider.test", "backup.test")) {
            assertFalse("payload leaks $secret", text.contains(secret))
        }
        val providers = JSONObject(text).getJSONObject("providers")
        assertEquals(tag("http://provider.test", "user1"), providers.getString("-1"))
        assertEquals(tag("http://backup.test", "user2"), providers.getString("0"))
    }

    @Test
    fun sameProviderAppliesById() = runBlocking {
        val p = payload(tag("http://provider.test", "user1"), tag("http://backup.test", "user2"), mapOf(11 to "ESPN"))
        val result = sync.applySyncPayload(p, token)
        assertTrue(result.success)
        assertEquals(listOf(11), db.channelDao().getFavoriteChannelIds())
        assertTrue("77" in prefs.favoriteLiveCategoryIds.first())
        // Top Favorites on verified providers only: the disabled slot 3 entry is dropped.
        assertEquals(listOf(TopFavorites.ref(-1, 10), TopFavorites.ref(0, 500)), TopFavorites.parse(prefs.topFavoritesJson.first()).items)
    }

    @Test
    fun differentProviderMatchesByExactNameOnly() = runBlocking {
        // Id 10 is CNN here but "Fox" on the sender: it must not become a favorite. HBO's id is
        // unknown here, so it's found by name; "ESP" is no exact name and is skipped.
        val p = payload("someone-elses-tag", favorites = mapOf(10 to "Fox", 999 to "HBO", 998 to "ESP"))
        val result = sync.applySyncPayload(p, token)
        assertTrue(result.success)
        assertEquals(listOf(12), db.channelDao().getFavoriteChannelIds())
        assertFalse("77" in prefs.favoriteLiveCategoryIds.first())
        assertEquals("", prefs.topFavoritesJson.first())
    }

    private fun merged(id: Int, name: String, favorite: Boolean = false) = com.iptvapp.data.local.entities.MergedChannelEntity(
        serverIndex = 0, streamId = id, name = name, streamIcon = null, num = id, serverNickname = "Backup",
        categoryId = "1", categoryName = "News", isFavorite = favorite
    )

    @Test
    fun otherProvidersFavoritesTravelByProviderTag() = runBlocking {
        db.mergedChannelDao().upsertAll(listOf(merged(500, "BBC", favorite = true), merged(501, "Sky")))
        val sent = sync.buildSyncPayload(token)
        val extras = sent.getJSONArray("extraFavorites")
        assertEquals(1, extras.length())
        assertEquals(tag("http://backup.test", "user2"), extras.getJSONObject(0).getString("p"))
        assertFalse(sent.toString().contains("backup-pass"))

        // Arrives on a device with the same backup provider: favorited by id there.
        db.mergedChannelDao().setFavorite(0, 500, false)
        val p = payload(tag("http://provider.test", "user1"), tag("http://backup.test", "user2"), emptyMap())
            .put("extraFavorites", JSONArray()
                .put(JSONObject().put("p", tag("http://backup.test", "user2")).put("i", 501).put("n", "Sky"))
                .put(JSONObject().put("p", "unknown-provider").put("i", 500).put("n", "BBC")))
        assertTrue(sync.applySyncPayload(p, token).success)
        assertEquals(listOf(501), db.mergedChannelDao().getAllFavorites().first().map { it.streamId })
    }

    @Test
    fun extraFavoriteLandsOnThisDevicesMainProviderWhenTagsMatch() = runBlocking {
        // On the sender this provider is an extra; here it's the main one.
        val p = payload("someone-elses-tag", favorites = emptyMap())
            .put("extraFavorites", JSONArray().put(JSONObject().put("p", tag("http://provider.test", "user1")).put("i", 11).put("n", "ESPN")))
        assertTrue(sync.applySyncPayload(p, token).success)
        assertEquals(listOf(11), db.channelDao().getFavoriteChannelIds())
    }

    @Test
    fun sendersMainProviderThatIsAnExtraHereGoesThereById() = runBlocking {
        // The sender's main provider is this device's backup. Its "CNN" (id 500) must land on the
        // backup's id 500, not on this device's main-provider CNN (id 10) by name.
        db.mergedChannelDao().upsertAll(listOf(merged(500, "CNN")))
        val p = payload(tag("http://backup.test", "user2"), favorites = mapOf(500 to "CNN"))
        val result = sync.applySyncPayload(p, token)
        assertTrue(result.success)
        assertEquals(emptyList<Int>(), db.channelDao().getFavoriteChannelIds())
        assertEquals(listOf(500), db.mergedChannelDao().getAllFavorites().first().map { it.streamId })
        assertFalse(result.message.contains("matched by name"))
    }

    @Test
    fun playOnTvResolvesTheExactChannelOnlyOnTheSameProvider() = runBlocking {
        db.mergedChannelDao().upsertAll(listOf(merged(500, "CNN")))
        // Main and extra provider, both by tag.
        assertEquals(-1 to 10, sync.resolveTaggedChannel(token, tag("http://provider.test", "user1"), 10, "CNN")?.let { it.serverIndex to it.streamId })
        assertEquals(0 to 500, sync.resolveTaggedChannel(token, tag("http://backup.test", "user2"), 500, "CNN")?.let { it.serverIndex to it.streamId })
        // A provider this device doesn't have, or a channel it doesn't have: found by name instead.
        assertEquals(null, sync.resolveTaggedChannel(token, "unknown", 10, "CNN"))
        assertEquals(null, sync.resolveTaggedChannel(token, tag("http://provider.test", "user1"), 999, "CNN"))
        // The phone side names its own provider the same way.
        assertEquals(tag("http://backup.test", "user2"), sync.providerTag(token, 0))
    }

    @Test
    fun interleavedMainAndExtraFavoritesKeepTheirOrder() = runBlocking {
        // Sender: main CNN (0), extra BBC (1), main ESPN (2).
        db.channelDao().setFavorite(10, true); db.channelDao().updateFavOrder(10, 0)
        db.channelDao().setFavorite(11, true); db.channelDao().updateFavOrder(11, 2)
        db.mergedChannelDao().upsertAll(listOf(merged(500, "BBC", favorite = true).copy(favOrder = 1)))
        val sent = sync.buildSyncPayload(token)
        // Arrives on an identical device with nothing favorited yet.
        db.channelDao().setFavorite(10, false); db.channelDao().setFavorite(11, false)
        db.channelDao().updateFavOrder(10, 9); db.channelDao().updateFavOrder(11, 9)
        db.mergedChannelDao().setFavorite(0, 500, false); db.mergedChannelDao().updateFavOrder(0, 500, 9)
        assertTrue(sync.applySyncPayload(sent, token).success)
        assertEquals(0, db.channelDao().getChannelById(10)!!.favOrder)
        assertEquals(2, db.channelDao().getChannelById(11)!!.favOrder)
        assertEquals(1, db.mergedChannelDao().getByIndexAndId(0, 500)!!.favOrder)
    }

    @Test
    fun noPreferenceLanguagesSyncToo() = runBlocking {
        prefs.setPreferredAudioLanguage("es")
        prefs.setPreferredSubtitleLanguage("en")
        val p = payload("someone-elses-tag", favorites = emptyMap())
            .put("preferences", JSONObject().put("preferredAudioLanguage", "").put("preferredSubtitleLanguage", ""))
        assertTrue(sync.applySyncPayload(p, token).success)
        assertEquals("", prefs.preferredAudioLanguage.first())
        assertEquals("", prefs.preferredSubtitleLanguage.first())
    }

    @Test
    fun pullReplyMustBeSignedForThisRequest() {
        val body = JSONObject().put("device", "TV")
        val reply = LocalSyncManager.sealPullReply(token, "req-sig", body)
        assertEquals("TV", LocalSyncManager.openPullReply(token, "req-sig", reply)?.optString("device"))
        // Another request, another token, or an edited payload: refused.
        assertEquals(null, LocalSyncManager.openPullReply(token, "other-sig", reply))
        assertEquals(null, LocalSyncManager.openPullReply("b".repeat(64), "req-sig", reply))
        val forged = JSONObject(reply.toString()).put("payload", JSONObject().put("device", "Evil").toString())
        assertEquals(null, LocalSyncManager.openPullReply(token, "req-sig", forged))
        assertEquals(null, LocalSyncManager.openPullReply(token, "req-sig", body))
    }

    @Test
    fun loginsFromOlderBuildsAreIgnored() = runBlocking {
        val p = payload(tag("http://provider.test", "user1"), favorites = emptyMap()).put("credentials", JSONObject()
            .put("serverUrl", "http://evil.test").put("username", "x").put("password", "y")
            .put("extraServers", JSONArray().put(JSONObject().put("url", "http://evil.test").put("user", "x").put("pass", "y"))))
        assertTrue(sync.applySyncPayload(p, token).success)
        assertEquals("http://provider.test", prefs.credentials.first().serverUrl)
        assertEquals(listOf("http://backup.test"), prefs.getExtraServersWithNick().map { it[0] })
    }

    private suspend fun smartGroup(id: String, favorite: Boolean, vararg members: Pair<Int, Int>) {
        db.smartChannelDao().upsertGroup(com.iptvapp.data.local.entities.SmartChannelGroupEntity(
            groupId = id, displayName = "CNN", preferredServerIndex = -1, preferredStreamId = -1, hasPreferred = false,
            failoverEnabled = true, origin = "MANUAL", reviewState = com.iptvapp.util.SmartChannels.MANUAL, reason = "",
            groupFavorite = favorite, updatedAt = 0L))
        db.smartChannelDao().upsertMembers(members.map { (s, i) -> com.iptvapp.data.local.entities.SmartChannelMemberEntity(
            groupId = id, serverIndex = s, streamId = i, epgChannelId = "", nameFingerprint = "cnn", displayName = "CNN",
            eligible = true, userPriority = 0) })
    }

    private fun groupFavorites(vararg members: JSONObject) =
        JSONArray().put(JSONObject().put("m", JSONArray(members.toList())))

    @Test
    fun smartChannelGroupFavoritesTravelAndStarTheLocalGroup() = runBlocking {
        val main = tag("http://provider.test", "user1")
        // Sender side: a starred group goes out as its members, by provider tag.
        smartGroup("g1", favorite = true, -1 to 10, -1 to 11)
        val sent = sync.buildSyncPayload(token).getJSONArray("groupFavorites")
        assertEquals(1, sent.length())
        assertEquals(main, sent.getJSONObject(0).getJSONArray("m").getJSONObject(0).getString("p"))

        // Receiver side: the group holding that channel gets the star, not the channel row.
        db.smartChannelDao().upsertGroup(db.smartChannelDao().group("g1")!!.copy(groupFavorite = false))
        val p = payload(main, favorites = emptyMap())
            .put("groupFavorites", groupFavorites(JSONObject().put("p", main).put("i", 10).put("n", "CNN")))
        assertTrue(sync.applySyncPayload(p, token).success)
        assertTrue(db.smartChannelDao().group("g1")!!.groupFavorite)
        assertFalse(db.channelDao().getChannelById(10)!!.isFavorite)
    }

    @Test
    fun smartChannelFavoriteWithoutALocalGroupStarsTheChannel() = runBlocking {
        // A different provider: matched by exact name on this device's main provider.
        val p = payload("someone-elses-tag", favorites = emptyMap())
            .put("groupFavorites", groupFavorites(JSONObject().put("p", "someone-elses-tag").put("i", 999).put("n", "ESPN")))
        assertTrue(sync.applySyncPayload(p, token).success)
        assertTrue(db.channelDao().getChannelById(11)!!.isFavorite)
        assertFalse(db.channelDao().getChannelById(10)!!.isFavorite)
    }
}
