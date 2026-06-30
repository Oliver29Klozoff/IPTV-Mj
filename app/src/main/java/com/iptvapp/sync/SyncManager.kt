package com.iptvapp.sync

import android.content.Context
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.iptvapp.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager,
    private val db: IptvDatabase
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private suspend fun resolveToken(): String =
        prefs.githubToken.first().takeIf { it.isNotBlank() } ?: BuildConfig.GH_TOKEN_B64

    suspend fun syncUp(): String = withContext(Dispatchers.IO) {
        try {
            val token = resolveToken()
            if (token.isEmpty()) return@withContext "No GitHub token — add GH_TOKEN_B64 to local.properties and rebuild"

            val favChannelIds = db.channelDao().getFavoriteChannelIds()
            val recentIds = db.channelDao().getRecentChannels().first()
                .take(50).map { it.streamId }
            val favCategoryIds = prefs.favoriteLiveCategoryIds.first().toList()

            val payload = JSONObject().apply {
                put("version", 1)
                put("syncedAt", System.currentTimeMillis())
                put("device", android.os.Build.MODEL)
                put("favoriteChannelIds", JSONArray(favChannelIds))
                put("favoriteCategoryIds", JSONArray(favCategoryIds))
                put("recentlyWatchedIds", JSONArray(recentIds))
            }

            var gistId = prefs.getSyncGistId()
            if (gistId.isEmpty()) gistId = discoverGistId(token).also { if (it.isNotEmpty()) prefs.setSyncGistId(it) }
            val isNew = gistId.isEmpty()

            val bodyJson = if (isNew) {
                JSONObject().apply {
                    put("public", false)
                    put("description", "MKTV App Sync Data")
                    put("files", JSONObject().apply {
                        put("mktv_sync.json", JSONObject().apply {
                            put("content", payload.toString(2))
                        })
                    })
                }
            } else {
                JSONObject().apply {
                    put("files", JSONObject().apply {
                        put("mktv_sync.json", JSONObject().apply {
                            put("content", payload.toString(2))
                        })
                    })
                }
            }

            val url = if (isNew) "https://api.github.com/gists" else "https://api.github.com/gists/$gistId"
            val method = if (isNew) "POST" else "PATCH"

            val request = Request.Builder()
                .url(url)
                .method(method, bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "token $token")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext "Upload failed: HTTP ${response.code}"

            if (isNew) {
                val newId = JSONObject(response.body?.string() ?: "{}").optString("id", "")
                if (newId.isNotEmpty()) prefs.setSyncGistId(newId)
            }

            prefs.setLastSyncTime(System.currentTimeMillis())
            "Synced ${favChannelIds.size} favorites to cloud"
        } catch (e: Exception) {
            "Sync failed: ${e.message}"
        }
    }

    private suspend fun discoverGistId(token: String): String {
        val req = Request.Builder()
            .url("https://api.github.com/gists?per_page=100")
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github.v3+json")
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return ""
        val arr = org.json.JSONArray(resp.body?.string() ?: "[]")
        for (i in 0 until arr.length()) {
            val gist = arr.getJSONObject(i)
            if (gist.getJSONObject("files").has("mktv_sync.json")) {
                return gist.getString("id")
            }
        }
        return ""
    }

    suspend fun syncDown(): String = withContext(Dispatchers.IO) {
        try {
            val token = resolveToken()
            if (token.isEmpty()) return@withContext "No GitHub token — add GH_TOKEN_B64 to local.properties and rebuild"

            var gistId = prefs.getSyncGistId()
            if (gistId.isEmpty()) {
                gistId = discoverGistId(token)
                if (gistId.isEmpty()) return@withContext "No sync data found — push from another device first"
                prefs.setSyncGistId(gistId)
            }

            val request = Request.Builder()
                .url("https://api.github.com/gists/$gistId")
                .header("Authorization", "token $token")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext "Download failed: HTTP ${response.code}"

            val gistJson = JSONObject(response.body?.string() ?: "{}")
            val content = gistJson.getJSONObject("files")
                .getJSONObject("mktv_sync.json")
                .getString("content")

            val data = JSONObject(content)
            val remoteFavIds = data.getJSONArray("favoriteChannelIds")
                .let { arr -> (0 until arr.length()).map { arr.getInt(it) } }
            val remoteCatIds = data.getJSONArray("favoriteCategoryIds")
                .let { arr -> (0 until arr.length()).map { arr.getString(it) } }

            val localFavIds = db.channelDao().getFavoriteChannelIds().toSet()
            val merged = (remoteFavIds + localFavIds).distinct()
            merged.forEach { db.channelDao().setFavorite(it, true) }

            val localCatIds = prefs.favoriteLiveCategoryIds.first()
            prefs.setFavoriteLiveCategoryIds(localCatIds + remoteCatIds.toSet())

            val syncedAt = data.optLong("syncedAt", 0L)
            val syncDevice = data.optString("device", "Unknown")
            val dateStr = if (syncedAt > 0) {
                SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(syncedAt))
            } else "Unknown time"

            prefs.setLastSyncTime(System.currentTimeMillis())
            "Pulled ${merged.size} favorites (last pushed from $syncDevice at $dateStr)"
        } catch (e: Exception) {
            "Sync failed: ${e.message}"
        }
    }

    suspend fun getLastSyncSummary(): String {
        val time = prefs.lastSyncTime.first()
        if (time == 0L) return "Never synced"
        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(time))
        val gistId = prefs.getSyncGistId()
        val gistShort = if (gistId.length > 8) gistId.take(8) + "..." else gistId
        return "Last synced: $fmt\nGist: $gistShort"
    }
}
