package com.iptvapp.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "iptv_prefs")

data class ServerCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
    val isLoggedIn: Boolean
)

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val PREFERRED_FORMAT = stringPreferencesKey("preferred_format")
        val EPG_URL = stringPreferencesKey("epg_url")
        val LAST_EPG_REFRESH_TIME = longPreferencesKey("last_epg_refresh_time")
        val EPG_AUTO_REFRESH_HOURS = intPreferencesKey("epg_auto_refresh_hours")
        val EPG_REFRESH_MISSING_ONLY = booleanPreferencesKey("epg_refresh_missing_only")
        val USA_ONLY_CHANNELS = booleanPreferencesKey("usa_only_channels")
        val SHOW_MOVIES = booleanPreferencesKey("show_movies")
        val SHOW_SERIES = booleanPreferencesKey("show_series")
        val FAVORITE_LIVE_CATEGORY_IDS = stringSetPreferencesKey("favorite_live_category_ids")
        val PENDING_FAV_CHANNEL_IDS = stringSetPreferencesKey("pending_fav_channel_ids")
        val EXTRA_SERVERS = stringPreferencesKey("extra_servers")
    }

    val credentials: Flow<ServerCredentials> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            ServerCredentials(
                serverUrl = prefs[Keys.SERVER_URL] ?: "",
                username = prefs[Keys.USERNAME] ?: "",
                password = prefs[Keys.PASSWORD] ?: "",
                isLoggedIn = prefs[Keys.IS_LOGGED_IN] ?: false
            )
        }

    val preferredFormat: Flow<String> = context.dataStore.data
        .map { it[Keys.PREFERRED_FORMAT] ?: "m3u8" }

    val epgUrl: Flow<String> = context.dataStore.data
        .map { it[Keys.EPG_URL] ?: "" }

    val lastEpgRefreshTime: Flow<Long> = context.dataStore.data
        .map { it[Keys.LAST_EPG_REFRESH_TIME] ?: 0L }

    val epgAutoRefreshHours: Flow<Int> = context.dataStore.data
        .map { it[Keys.EPG_AUTO_REFRESH_HOURS] ?: 0 }

    val epgRefreshMissingOnly: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.EPG_REFRESH_MISSING_ONLY] ?: false }

    val usaOnlyChannels: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.USA_ONLY_CHANNELS] ?: true }

    val favoriteLiveCategoryIds: Flow<Set<String>> = context.dataStore.data
        .map { it[Keys.FAVORITE_LIVE_CATEGORY_IDS] ?: emptySet() }

    suspend fun saveCredentials(serverUrl: String, username: String, password: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = serverUrl
            prefs[Keys.USERNAME] = username
            prefs[Keys.PASSWORD] = password
            prefs[Keys.IS_LOGGED_IN] = true
        }
    }

    suspend fun clearCredentials() {
        context.dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = ""
            prefs[Keys.USERNAME] = ""
            prefs[Keys.PASSWORD] = ""
            prefs[Keys.IS_LOGGED_IN] = false
        }
    }

    suspend fun setPreferredFormat(format: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PREFERRED_FORMAT] = format
        }
    }

    suspend fun setEpgUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EPG_URL] = url
        }
    }

    suspend fun setLastEpgRefreshTime(timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_EPG_REFRESH_TIME] = timeMillis
        }
    }

    suspend fun setEpgAutoRefreshHours(hours: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EPG_AUTO_REFRESH_HOURS] = hours
        }
    }

    suspend fun setEpgRefreshMissingOnly(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EPG_REFRESH_MISSING_ONLY] = enabled
        }
    }

    suspend fun setUsaOnlyChannels(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.USA_ONLY_CHANNELS] = enabled
        }
    }

        val showMovies: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_MOVIES] ?: true }
    val showSeries: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_SERIES] ?: true }

    suspend fun setShowMovies(enabled: Boolean) { context.dataStore.edit { it[Keys.SHOW_MOVIES] = enabled } }
    suspend fun setShowSeries(enabled: Boolean) { context.dataStore.edit { it[Keys.SHOW_SERIES] = enabled } }

    suspend fun addFavoriteLiveCategoryId(categoryId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_LIVE_CATEGORY_IDS] ?: emptySet()
            prefs[Keys.FAVORITE_LIVE_CATEGORY_IDS] = current + categoryId
        }
    }

    suspend fun getExtraServers(): List<Triple<String,String,String>> {
        val json = context.dataStore.data.first()[Keys.EXTRA_SERVERS] ?: "[]"
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            Triple(obj.getString("url"), obj.getString("user"), obj.getString("pass"))
        }
    }

    suspend fun saveExtraServers(servers: List<Triple<String,String,String>>) {
        val arr = org.json.JSONArray()
        servers.forEach { (url, user, pass) ->
            arr.put(org.json.JSONObject().apply {
                put("url", url); put("user", user); put("pass", pass)
            })
        }
        context.dataStore.edit { it[Keys.EXTRA_SERVERS] = arr.toString() }
    }

    val pendingFavoriteChannelIds: Flow<Set<String>> = context.dataStore.data.map { it[Keys.PENDING_FAV_CHANNEL_IDS] ?: emptySet() }

    suspend fun setPendingFavoriteChannelIds(ids: Set<Int>) {
        context.dataStore.edit { it[Keys.PENDING_FAV_CHANNEL_IDS] = ids.map { id -> id.toString() }.toSet() }
    }

    suspend fun clearPendingFavoriteChannelIds() {
        context.dataStore.edit { it[Keys.PENDING_FAV_CHANNEL_IDS] = emptySet() }
    }

    suspend fun setFavoriteLiveCategoryIds(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FAVORITE_LIVE_CATEGORY_IDS] = ids
        }
    }

    suspend fun removeFavoriteLiveCategoryId(categoryId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_LIVE_CATEGORY_IDS] ?: emptySet()
            prefs[Keys.FAVORITE_LIVE_CATEGORY_IDS] = current - categoryId
        }
    }
}
