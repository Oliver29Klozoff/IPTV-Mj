package com.iptvapp.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.FavoriteFolderEntity
import com.iptvapp.remote.RemoteControlHooks
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
 * top favorites, preferences, and optionally provider accounts over the local home Wi-Fi.
 * Authenticated using the HMAC-SHA256 pairing token from RemoteControlServer.
 */
@Singleton
class LocalSyncManager @Inject constructor(
    private val db: IptvDatabase,
    private val prefs: PreferencesManager
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
    }

    suspend fun buildSyncPayload(includeCredentials: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val creds = prefs.credentials.first()
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
            put("version", 1)
            put("syncedAt", System.currentTimeMillis())
            put("device", Build.MODEL)
            put("favoriteChannelIds", JSONArray(favChannelIds))
            put("favoriteCategoryIds", JSONArray(favCategoryIds))
            put("channelNames", JSONObject(channelNames))
            put("favoriteFolders", JSONArray(folders.map { it.name }))
            put("channelFolders", JSONObject(channelFolders))
            put("favoriteOrder", JSONArray(favChannels.map { it.streamId }))

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

            // Credentials (optional, included by default for phone->TV fast setup)
            if (includeCredentials && creds.isLoggedIn && creds.serverUrl.isNotBlank()) {
                put("credentials", JSONObject().apply {
                    put("serverUrl", creds.serverUrl)
                    put("username", creds.username)
                    put("password", creds.password)
                    put("epgUrl", prefs.epgUrl.first())
                    val extraServersList = prefs.getExtraServersWithNick()
                    put("extraServers", JSONArray(extraServersList.map { s ->
                        JSONObject().apply {
                            put("url", s[0]); put("user", s[1]); put("pass", s[2])
                            put("nick", s.getOrElse(3) { "" }); put("epg", s.getOrElse(4) { "" })
                            put("enabled", s.getOrElse(5) { "true" })
                        }
                    }))
                })
            }
        }
    }

    suspend fun applySyncPayload(json: JSONObject): LocalSyncResult = withContext(Dispatchers.IO) {
        var favCount = 0
        var catCount = 0
        val fromDevice = json.optString("device", "paired device")

        try {
            // 1. Credentials (if present and target device is unconfigured or extra servers exist)
            val credsJson = json.optJSONObject("credentials")
            if (credsJson != null) {
                val serverUrl = credsJson.optString("serverUrl", "")
                val username = credsJson.optString("username", "")
                val password = credsJson.optString("password", "")
                val current = prefs.credentials.first()
                if (!current.isLoggedIn && serverUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()) {
                    prefs.saveCredentials(serverUrl, username, password)
                    credsJson.optString("epgUrl", "").takeIf { it.isNotEmpty() }?.let { prefs.setEpgUrl(it) }
                }
                val extra = credsJson.optJSONArray("extraServers")
                if (extra != null && extra.length() > 0) {
                    val list = mutableListOf<List<String>>()
                    for (i in 0 until extra.length()) {
                        val o = extra.optJSONObject(i) ?: continue
                        list.add(listOf(
                            o.optString("url", ""), o.optString("user", ""), o.optString("pass", ""),
                            o.optString("nick", ""), o.optString("epg", ""), o.optString("enabled", "true")
                        ))
                    }
                    if (list.isNotEmpty()) prefs.saveExtraServersWithNick(list)
                }
            }

            // 2. Favorite Categories
            val favCats = json.optJSONArray("favoriteCategoryIds")
            if (favCats != null) {
                val remoteCats = (0 until favCats.length()).map { favCats.getString(it) }.toSet()
                val localCats = prefs.favoriteLiveCategoryIds.first()
                val mergedCats = localCats + remoteCats
                prefs.setFavoriteLiveCategoryIds(mergedCats)
                catCount = remoteCats.size
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
                        val streamId = streamIdStr.toIntOrNull()
                        if (streamId != null && folderName.isNotEmpty()) {
                            idByName[folderName]?.let { folderId ->
                                db.channelDao().setFavoriteFolder(streamId, folderId)
                            }
                        }
                    }
                }
            }

            // 4. Favorite Channels
            val favChannels = json.optJSONArray("favoriteChannelIds")
            val channelNames = json.optJSONObject("channelNames")
            if (favChannels != null) {
                val remoteIds = (0 until favChannels.length()).map { favChannels.getInt(it) }
                val existingIds = db.channelDao().getAllChannelIds().toSet()

                // Apply direct ID matches
                remoteIds.filter { it in existingIds }.forEach {
                    db.channelDao().setFavorite(it, true)
                    favCount++
                }

                // If some IDs weren't found locally, fallback to matching by channel name
                val missingIds = remoteIds.filter { it !in existingIds }
                if (missingIds.isNotEmpty() && channelNames != null) {
                    for (id in missingIds) {
                        val name = channelNames.optString(id.toString(), "")
                        if (name.isNotBlank()) {
                            val matches = db.channelDao().findByNameLike("%${name.trim()}%")
                            val exact = matches.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) } ?: matches.firstOrNull()
                            if (exact != null && !exact.isFavorite) {
                                db.channelDao().setFavorite(exact.streamId, true)
                                favCount++
                            }
                        }
                    }
                }
            }

            // 5. Favorite Order
            val favOrder = json.optJSONArray("favoriteOrder")
            if (favOrder != null) {
                val localFavs = db.channelDao().getFavoriteChannelIds().toSet()
                for (i in 0 until favOrder.length()) {
                    val streamId = favOrder.getInt(i)
                    if (streamId in localFavs) {
                        db.channelDao().updateFavOrder(streamId, i)
                    }
                }
            }

            // 6. Top Favorites
            val topFav = json.optString("topFavorites", "")
            if (topFav.isNotEmpty()) {
                prefs.setTopFavoritesJson(topFav)
            }

            // 7. Preferences
            val prefJson = json.optJSONObject("preferences")
            if (prefJson != null) {
                prefJson.optString("preferredFormat", "").takeIf { it.isNotEmpty() }?.let { prefs.setPreferredFormat(it) }
                prefJson.optString("preferredAudioLanguage", "").takeIf { it.isNotEmpty() }?.let { prefs.setPreferredAudioLanguage(it) }
                prefJson.optString("preferredSubtitleLanguage", "").takeIf { it.isNotEmpty() }?.let { prefs.setPreferredSubtitleLanguage(it) }
                if (prefJson.has("usaOnlyChannels")) prefs.setUsaOnlyChannels(prefJson.optBoolean("usaOnlyChannels", true))
                if (prefJson.has("showMovies")) prefs.setShowMovies(prefJson.optBoolean("showMovies", true))
                if (prefJson.has("showSeries")) prefs.setShowSeries(prefJson.optBoolean("showSeries", true))
                if (prefJson.has("combineDuplicates")) prefs.setCombineDuplicates(prefJson.optBoolean("combineDuplicates", true))
            }

            // 8. VOD Progress
            val vodProgress = json.optJSONArray("vodProgress")
            if (vodProgress != null) {
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
                message = "Synced $favCount favorites and $catCount categories from $fromDevice",
                favoritesCount = favCount,
                categoriesCount = catCount,
                targetDevice = fromDevice
            )
        } catch (e: Exception) {
            Log.e(TAG, "applySyncPayload error", e)
            LocalSyncResult(success = false, message = "Sync apply error: ${e.message}")
        }
    }

    suspend fun pushToTv(
        host: String,
        port: Int = RemoteControlServer.PORT,
        token: String,
        includeCredentials: Boolean = true
    ): LocalSyncResult = withContext(Dispatchers.IO) {
        try {
            val payload = buildSyncPayload(includeCredentials)
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
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val resJson = JSONObject(responseText)
                val favs = resJson.optInt("favoritesMerged", payload.optJSONArray("favoriteChannelIds")?.length() ?: 0)
                val cats = resJson.optInt("categoriesMerged", payload.optJSONArray("favoriteCategoryIds")?.length() ?: 0)
                prefs.setLastSyncTime(System.currentTimeMillis())
                LocalSyncResult(
                    success = true,
                    message = "Synced $favs favorites & settings to TV",
                    favoritesCount = favs,
                    categoriesCount = cats
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
                val payload = JSONObject(responseText)
                applySyncPayload(payload)
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
