package com.iptvapp.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.FavoriteFolderEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.handoff.AppHandoffCatalog
import com.iptvapp.handoff.HandoffCrypto
import com.iptvapp.remote.RemoteControlServer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class LocalSyncResult(
    val success: Boolean,
    val message: String,
    val favoritesCount: Int = 0,
    val categoriesCount: Int = 0,
    val targetDevice: String = ""
)

/**
 * Direct Local Network (LAN) Sync between paired MKTV devices.
 * Zero cloud dependency: transfers and merges favorites, custom folders, channel order,
 * top favorites and preferences over the local home Wi-Fi.
 * Authenticated using the HMAC-SHA256 pairing token from RemoteControlServer.
 *
 * The request is signed, not encrypted, so the payload never carries provider logins, server
 * addresses or stream URLs. Providers are named only by HandoffCrypto.providerTag, keyed by the
 * pairing token, so the other side can tell "same account" without learning the account.
 * Stream ids, categories and watch progress belong to one provider: they're applied by id only
 * when both sides' main provider tags match; otherwise favorites are matched by exact name and
 * the rest is skipped.
 */
@Singleton
class LocalSyncManager @Inject constructor(
    private val db: IptvDatabase,
    private val prefs: PreferencesManager,
    private val repository: XtreamRepository
) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LocalSyncEntryPoint {
        fun localSyncManager(): LocalSyncManager
    }

    companion object {
        private const val TAG = "LocalSyncManager"

        fun get(context: Context): LocalSyncManager =
            EntryPointAccessors.fromApplication(context.applicationContext, LocalSyncEntryPoint::class.java).localSyncManager()

        /**
         * The TV's answer to /sync/pull, signed with the pairing token and tied to the phone's
         * request signature, so nobody else on the Wi-Fi can swap in their own favorites.
         */
        fun sealPullReply(token: String, requestSig: String, payload: JSONObject): JSONObject {
            val text = payload.toString()
            return JSONObject().put("payload", text).put("sig", replySig(token, requestSig, text))
        }

        /** The payload inside a [sealPullReply] answer, or null when it isn't signed by the paired TV for this request. */
        fun openPullReply(token: String, requestSig: String, reply: JSONObject): JSONObject? {
            val text = reply.optString("payload", "")
            val sig = reply.optString("sig", "")
            if (text.isEmpty() || !HandoffCrypto.constantTimeEquals(sig, replySig(token, requestSig, text))) return null
            return try { JSONObject(text) } catch (_: Exception) { null }
        }

        private fun replySig(token: String, requestSig: String, text: String) =
            RemoteControlServer.sign(token, "REPLY", "/sync/pull", 0L, "$requestSig\n$text")
    }

    /** serverIndex → provider tag for every enabled provider, keyed by the pairing [token]. */
    private suspend fun providerTags(token: String): Map<String, String> =
        AppHandoffCatalog(db, repository, prefs).providers()
            .associate { it.serverIndex.toString() to HandoffCrypto.providerTag(token, it.identity) }

    /** This device's tag for provider [serverIndex] under the pairing [token]; null when it isn't enabled. */
    suspend fun providerTag(token: String, serverIndex: Int): String? = providerTags(token)[serverIndex.toString()]

    /**
     * A channel a paired device named by provider tag + stream id, on this device: only on the
     * provider with that tag, and only when that channel is there. Null means "find it by name".
     */
    suspend fun resolveTaggedChannel(token: String, tag: String, streamId: Int, name: String): com.iptvapp.util.VoiceTuner.Target? =
        withContext(Dispatchers.IO) {
            val serverIndex = providerTags(token).entries
                .firstOrNull { HandoffCrypto.constantTimeEquals(it.value, tag) }?.key?.toIntOrNull()
                ?: return@withContext null
            val exists = if (serverIndex == -1) db.channelDao().getChannelById(streamId) != null
                else db.mergedChannelDao().getByIndexAndId(serverIndex, streamId) != null
            if (exists) com.iptvapp.util.VoiceTuner.Target(serverIndex, streamId, name, null) else null
        }

    suspend fun buildSyncPayload(token: String): JSONObject = withContext(Dispatchers.IO) {
        val favChannelIds = db.channelDao().getFavoriteChannelIds()
        val favChannels = db.channelDao().getFavoriteChannelsBlocking()
        val favCategoryIds = prefs.favoriteLiveCategoryIds.first().toList()
        val folders = db.favoriteFolderDao().getAll().first()
        val folderNameById = folders.associate { it.id to it.name }
        val channelFolders = favChannels
            .mapNotNull { ch -> ch.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { name -> ch.streamId.toString() to name } } }
            .toMap()

        val channelNames = favChannels.associate { it.streamId.toString() to it.name }

        JSONObject().apply {
            put("version", 2)
            put("syncedAt", System.currentTimeMillis())
            put("device", Build.MODEL)
            put("providers", JSONObject(providerTags(token)))
            put("favoriteChannelIds", JSONArray(favChannelIds))
            put("favoriteCategoryIds", JSONArray(favCategoryIds))
            put("channelNames", JSONObject(channelNames))
            put("favoriteFolders", JSONArray(folders.map { it.name }))
            put("channelFolders", JSONObject(channelFolders))
            put("favoriteOrder", JSONArray(favChannels.map { it.streamId }))

            // Favorites from the other providers, named by provider tag (never a login).
            val tags = providerTags(token)
            put("extraFavorites", JSONArray(db.mergedChannelDao().getAllFavorites().first().mapNotNull { ch ->
                val tag = tags[ch.serverIndex.toString()] ?: return@mapNotNull null
                JSONObject().put("p", tag).put("i", ch.streamId).put("n", ch.name).put("o", ch.favOrder)
                    .apply { ch.favoriteFolderId?.let { folderNameById[it] }?.let { put("f", it) } }
            }))

            // Top favorites if set
            val topFavJson = prefs.topFavoritesJson.first()
            if (!topFavJson.isNullOrBlank()) {
                put("topFavorites", topFavJson)
            }

            // Core user preferences
            put("preferences", JSONObject().apply {
                put("preferredFormat", prefs.preferredFormat.first())
                put("preferredAudioLanguage", prefs.preferredAudioLanguage.first())
                put("preferredSubtitleLanguage", prefs.preferredSubtitleLanguage.first())
                put("usaOnlyChannels", prefs.usaOnlyChannels.first())
                put("showMovies", prefs.showMovies.first())
                put("showSeries", prefs.showSeries.first())
                put("combineDuplicates", prefs.combineDuplicates.first())
            })

            // Watch progress (VOD & Series)
            val vodWatched = db.vodDao().getUserData().filter { it.watchedMs > 0 }
                .map { JSONObject().apply { put("streamId", it.streamId); put("watchedMs", it.watchedMs); put("durationMs", it.durationMs) } }
            put("vodProgress", JSONArray(vodWatched))
        }
    }

    suspend fun applySyncPayload(json: JSONObject, token: String): LocalSyncResult = withContext(Dispatchers.IO) {
        var favCount = 0
        var catCount = 0
        val fromDevice = json.optString("device", "paired device")

        try {
            // Older builds sent provider logins here; they are never applied.
            val localTags = providerTags(token)
            val remoteTags = json.optJSONObject("providers")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }.orEmpty()
            val samePrimary = localTags["-1"]?.let { HandoffCrypto.constantTimeEquals(it, remoteTags["-1"].orEmpty()) } == true
            val sameProviders = localTags.isNotEmpty() && localTags.keys == remoteTags.keys &&
                localTags.all { (k, v) -> HandoffCrypto.constantTimeEquals(v, remoteTags[k].orEmpty()) }

            // The sender's main provider may be one of this device's extra providers: then its
            // favorites go there by id (4a), not to a namesake on this device's main provider.
            val primaryAsExtra = if (samePrimary) null else remoteTags["-1"]?.takeIf { it.isNotEmpty() }?.let { rt ->
                localTags.entries.firstOrNull { it.key != "-1" && HandoffCrypto.constantTimeEquals(it.value, rt) }?.key?.toIntOrNull()
            }

            // Remote stream id → local main-provider stream id: the same id on the same provider,
            // otherwise one channel with exactly that name.
            val channelNames = json.optJSONObject("channelNames")
            val existingIds = if (samePrimary) db.channelDao().getAllChannelIds().toSet() else emptySet()
            val idMap = HashMap<Int, Int?>()
            suspend fun localId(remoteId: Int): Int? = idMap.getOrPut(remoteId) {
                if (samePrimary) return@getOrPut remoteId.takeIf { it in existingIds }
                if (primaryAsExtra != null) return@getOrPut null
                val name = channelNames?.optString(remoteId.toString(), "")?.trim().orEmpty()
                if (name.isBlank()) return@getOrPut null
                db.channelDao().findByNameLike(name).firstOrNull { it.name.trim().equals(name, ignoreCase = true) }?.streamId
            }

            // 1. Favorite Categories (category ids belong to one provider)
            val favCats = json.optJSONArray("favoriteCategoryIds")
            if (favCats != null && samePrimary) {
                val remoteCats = (0 until favCats.length()).map { favCats.getString(it) }.toSet()
                val localCats = prefs.favoriteLiveCategoryIds.first()
                val mergedCats = localCats + remoteCats
                prefs.setFavoriteLiveCategoryIds(mergedCats)
                catCount = remoteCats.size
            }

            // 2. Favorite Channels
            val favChannels = json.optJSONArray("favoriteChannelIds")
            if (favChannels != null) {
                for (i in 0 until favChannels.length()) {
                    val id = localId(favChannels.optInt(i, -1)) ?: continue
                    db.channelDao().setFavorite(id, true)
                    favCount++
                }
            }

            // 3. Folders
            val folders = json.optJSONArray("favoriteFolders")
            val channelFolders = json.optJSONObject("channelFolders")
            if (folders != null) {
                val existingFolders = db.favoriteFolderDao().getAll().first()
                val idByName = existingFolders.associate { it.name to it.id }.toMutableMap()
                var nextOrder = existingFolders.size
                for (i in 0 until folders.length()) {
                    val name = folders.getString(i)
                    if (name !in idByName) {
                        val newId = db.favoriteFolderDao().insert(
                            FavoriteFolderEntity(name = name, sortOrder = nextOrder++)
                        ).toInt()
                        idByName[name] = newId
                    }
                }
                if (channelFolders != null) {
                    val keys = channelFolders.keys()
                    while (keys.hasNext()) {
                        val streamIdStr = keys.next()
                        val folderName = channelFolders.optString(streamIdStr)
                        val streamId = streamIdStr.toIntOrNull()?.let { localId(it) }
                        if (streamId != null && folderName.isNotEmpty()) {
                            idByName[folderName]?.let { folderId ->
                                db.channelDao().setFavoriteFolder(streamId, folderId)
                            }
                        }
                    }
                }
            }

            // 4. Favorite Order
            val favOrder = json.optJSONArray("favoriteOrder")
            if (favOrder != null) {
                val localFavs = db.channelDao().getFavoriteChannelIds().toSet()
                for (i in 0 until favOrder.length()) {
                    val streamId = localId(favOrder.optInt(i, -1)) ?: continue
                    if (streamId in localFavs) {
                        db.channelDao().updateFavOrder(streamId, i)
                    }
                }
            }

            // 4a. The sender's main provider is an extra one here: its favorites, folders and order by id there.
            if (primaryAsExtra != null && favChannels != null) {
                val folderIds = db.favoriteFolderDao().getAll().first().associate { it.name to it.id }
                val position = json.optJSONArray("favoriteOrder")
                    ?.let { a -> (0 until a.length()).associate { a.optInt(it) to it } }.orEmpty()
                for (i in 0 until favChannels.length()) {
                    val sid = favChannels.optInt(i, -1)
                    if (db.mergedChannelDao().getByIndexAndId(primaryAsExtra, sid) == null) continue
                    val folderId = channelFolders?.optString(sid.toString(), "")?.takeIf { it.isNotEmpty() }?.let { folderIds[it] }
                    if (folderId != null) db.mergedChannelDao().setFavoriteFolder(primaryAsExtra, sid, folderId)
                    else db.mergedChannelDao().setFavorite(primaryAsExtra, sid, true)
                    position[sid]?.let { db.mergedChannelDao().updateFavOrder(primaryAsExtra, sid, it) }
                    favCount++
                }
            }

            // 4b. Other providers' favorites: by id, only on the local provider with the same tag —
            // which may be this device's main provider (then they go in the main channel list).
            val extras = json.optJSONArray("extraFavorites")
            if (extras != null && extras.length() > 0) {
                val indexByTag = localTags.entries.associate { (k, v) -> v to k.toInt() }
                val folderIds = db.favoriteFolderDao().getAll().first().associate { it.name to it.id }
                for (i in 0 until extras.length()) {
                    val item = extras.optJSONObject(i) ?: continue
                    val serverIndex = indexByTag[item.optString("p")] ?: continue
                    val streamId = item.optInt("i", -1)
                    val folderId = item.optString("f", "").takeIf { it.isNotEmpty() }?.let { folderIds[it] }
                    if (serverIndex == -1) {
                        if (db.channelDao().getChannelById(streamId) == null) continue
                        if (folderId != null) db.channelDao().setFavoriteFolder(streamId, folderId)
                        db.channelDao().setFavorite(streamId, true)
                        if (item.has("o")) db.channelDao().updateFavOrder(streamId, item.optInt("o"))
                    } else {
                        if (db.mergedChannelDao().getByIndexAndId(serverIndex, streamId) == null) continue
                        if (folderId != null) db.mergedChannelDao().setFavoriteFolder(serverIndex, streamId, folderId)
                        else db.mergedChannelDao().setFavorite(serverIndex, streamId, true)
                        if (item.has("o")) db.mergedChannelDao().updateFavOrder(serverIndex, streamId, item.optInt("o"))
                    }
                    favCount++
                }
            }

            // 5. Top Favorites: its items name provider slots, so only between identical setups
            val topFav = json.optString("topFavorites", "")
            if (topFav.isNotEmpty() && sameProviders) {
                prefs.setTopFavoritesJson(topFav)
            }

            // 6. Preferences
            val prefJson = json.optJSONObject("preferences")
            if (prefJson != null) {
                prefJson.optString("preferredFormat", "").takeIf { it.isNotEmpty() }?.let { prefs.setPreferredFormat(it) }
                // "" is a real choice here: No preference.
                if (prefJson.has("preferredAudioLanguage")) prefs.setPreferredAudioLanguage(prefJson.optString("preferredAudioLanguage", ""))
                if (prefJson.has("preferredSubtitleLanguage")) prefs.setPreferredSubtitleLanguage(prefJson.optString("preferredSubtitleLanguage", ""))
                if (prefJson.has("usaOnlyChannels")) prefs.setUsaOnlyChannels(prefJson.optBoolean("usaOnlyChannels", true))
                if (prefJson.has("showMovies")) prefs.setShowMovies(prefJson.optBoolean("showMovies", true))
                if (prefJson.has("showSeries")) prefs.setShowSeries(prefJson.optBoolean("showSeries", true))
                if (prefJson.has("combineDuplicates")) prefs.setCombineDuplicates(prefJson.optBoolean("combineDuplicates", true))
            }

            // 7. VOD Progress (movie ids belong to one provider)
            val vodProgress = json.optJSONArray("vodProgress")
            if (vodProgress != null && samePrimary) {
                for (i in 0 until vodProgress.length()) {
                    val item = vodProgress.optJSONObject(i) ?: continue
                    val sid = item.optInt("streamId", -1)
                    val watched = item.optLong("watchedMs", 0L)
                    val dur = item.optLong("durationMs", 0L)
                    if (sid > 0 && watched > 0) {
                        val current = db.vodDao().getWatchedMs(sid) ?: 0L
                        if (watched > current) db.vodDao().updateWatchProgress(sid, watched, dur)
                    }
                }
            }

            prefs.setLastSyncTime(System.currentTimeMillis())
            LocalSyncResult(
                success = true,
                message = "Synced $favCount favorites from $fromDevice" +
                    if (samePrimary || primaryAsExtra != null) "" else " (different provider: matched by name)",
                favoritesCount = favCount,
                categoriesCount = catCount,
                targetDevice = fromDevice
            )
        } catch (e: Exception) {
            Log.e(TAG, "applySyncPayload error", e)
            LocalSyncResult(success = false, message = "Sync from $fromDevice failed")
        }
    }

    suspend fun pushToTv(
        host: String,
        port: Int = RemoteControlServer.PORT,
        token: String
    ): LocalSyncResult = withContext(Dispatchers.IO) {
        try {
            val payload = buildSyncPayload(token)
            val bodyStr = payload.toString()
            val now = System.currentTimeMillis()
            val sig = RemoteControlServer.sign(token, "POST", "/sync/push", now, bodyStr)

            val url = URL("http://$host:$port/sync/push")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-MKTV-Time", now.toString())
                setRequestProperty("X-MKTV-Sig", sig)
            }

            conn.outputStream.use { it.write(bodyStr.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
                prefs.setLastSyncTime(System.currentTimeMillis())
                // The TV applies it in the background and shows its own "Synced" message.
                LocalSyncResult(
                    success = true,
                    message = "Sent ${payload.optJSONArray("favoriteChannelIds")?.length() ?: 0} favorites and settings to the TV. It shows a message when they're in.",
                    favoritesCount = payload.optJSONArray("favoriteChannelIds")?.length() ?: 0
                )
            } else {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val errJson = try { JSONObject(errText) } catch (_: Exception) { null }
                val errMsg = errJson?.optString("error") ?: "HTTP $code"
                LocalSyncResult(success = false, message = "TV returned: $errMsg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "pushToTv failed", e)
            LocalSyncResult(success = false, message = "Could not reach TV: ${e.message}")
        }
    }

    suspend fun pullFromTv(
        host: String,
        port: Int = RemoteControlServer.PORT,
        token: String
    ): LocalSyncResult = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val sig = RemoteControlServer.sign(token, "GET", "/sync/pull", now, "")

            val url = URL("http://$host:$port/sync/pull")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 20000
                setRequestProperty("X-MKTV-Time", now.toString())
                setRequestProperty("X-MKTV-Sig", sig)
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val payload = openPullReply(token, sig, JSONObject(responseText))
                    ?: return@withContext LocalSyncResult(success = false, message = "The TV's answer wasn't signed by your paired TV, so nothing was changed")
                applySyncPayload(payload, token)
            } else {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val errJson = try { JSONObject(errText) } catch (_: Exception) { null }
                val errMsg = errJson?.optString("error") ?: "HTTP $code"
                LocalSyncResult(success = false, message = "TV returned: $errMsg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "pullFromTv failed", e)
            LocalSyncResult(success = false, message = "Could not reach TV: ${e.message}")
        }
    }
}
