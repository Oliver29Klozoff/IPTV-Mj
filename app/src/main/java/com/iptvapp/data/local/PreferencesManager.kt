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

// Top-level (not in the private Keys object below) so ChannelTimerScheduler — a plain object with
// no Hilt injection, used from BroadcastReceiver-adjacent code that schedules AlarmManager
// reminders — can read it directly via the same Context.dataStore extension, without needing a
// full PreferencesManager instance.
val REMINDER_LEAD_MINUTES_KEY = intPreferencesKey("reminder_lead_minutes")

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
        val EPG_URLS = stringPreferencesKey("epg_urls")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val PREFERRED_FORMAT = stringPreferencesKey("preferred_format")
        // ISO 639-2 codes (e.g. "eng", "spa"), empty string = no preference/auto.
        val PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("preferred_audio_language")
        val PREFERRED_SUBTITLE_LANGUAGE = stringPreferencesKey("preferred_subtitle_language")
        // Folder name under Environment.DIRECTORY_MOVIES that recordings are saved to via
        // MediaStore (e.g. "MKTV" -> Movies/MKTV) — see RecordingFolderName kdoc.
        val RECORDING_FOLDER_NAME = stringPreferencesKey("recording_folder_name")
        // 0 = disabled. Otherwise recordings older than this many days are auto-deleted
        // (file + DB row) by RecordingCleanupWorker.
        val AUTO_DELETE_RECORDINGS_DAYS = intPreferencesKey("auto_delete_recordings_days")
        // 0 = disabled. Otherwise in-progress Continue Watching entries (movies/series) whose
        // last watch activity is older than this many days are auto-cleared (dismissed, same as
        // a manual long-press-to-remove) by ContinueWatchingCleanupWorker.
        val AUTO_CLEAR_CONTINUE_WATCHING_DAYS = intPreferencesKey("auto_clear_continue_watching_days")
        val CRASH_REPORTING_ENABLED = booleanPreferencesKey("crash_reporting_enabled")
        val EPG_URL = stringPreferencesKey("epg_url")
        val LAST_EPG_REFRESH_TIME = longPreferencesKey("last_epg_refresh_time")
        val EPG_AUTO_REFRESH_HOURS = intPreferencesKey("epg_auto_refresh_hours")
        val EPG_REFRESH_MISSING_ONLY = booleanPreferencesKey("epg_refresh_missing_only")
        val USA_ONLY_CHANNELS = booleanPreferencesKey("usa_only_channels")
        val SHOW_MOVIES = booleanPreferencesKey("show_movies")
        val SHOW_SERIES = booleanPreferencesKey("show_series")
        val SHOW_WATCHING = booleanPreferencesKey("show_watching")
        val FAVORITE_LIVE_CATEGORY_IDS = stringSetPreferencesKey("favorite_live_category_ids")
        // Keyed "$serverIndex:$categoryId" — plain categoryId isn't unique across servers,
        // two different providers can reuse the same category id string.
        val FAVORITE_MERGED_CATEGORY_IDS = stringSetPreferencesKey("favorite_merged_category_ids")
        val PENDING_FAV_CHANNEL_IDS = stringSetPreferencesKey("pending_fav_channel_ids")
        // Restored from a backup before that provider's channels have ever been fetched, so
        // there's nothing to mark favorite yet — applied opportunistically the next time that
        // server's channels are refreshed. Keyed "$serverUrl|$streamId" (server URL, not
        // serverIndex — a restore can land on a device where providers are configured in a
        // different order, same reasoning as SyncManager's cross-device provider matching).
        val PENDING_MERGED_FAVORITES = stringSetPreferencesKey("pending_merged_favorites")
        // Keyed "$serverUrl|$categoryId"
        val PENDING_MERGED_FAVORITE_CATEGORIES = stringSetPreferencesKey("pending_merged_favorite_categories")
        // Keyed "$serverUrl|$streamId|$folderName"
        val PENDING_MERGED_CHANNEL_FOLDERS = stringSetPreferencesKey("pending_merged_channel_folders")
        // VOD/Series equivalents of the two keys above — no category-equivalent exists for
        // either (MergedVodDao/MergedSeriesDao have no per-category favorite concept).
        // Keyed "$serverUrl|$streamId"
        val PENDING_MERGED_VOD_FAVORITES = stringSetPreferencesKey("pending_merged_vod_favorites")
        // Keyed "$serverUrl|$streamId|$folderName"
        val PENDING_MERGED_VOD_FOLDERS = stringSetPreferencesKey("pending_merged_vod_folders")
        // Keyed "$serverUrl|$seriesId"
        val PENDING_MERGED_SERIES_FAVORITES = stringSetPreferencesKey("pending_merged_series_favorites")
        // Keyed "$serverUrl|$seriesId|$folderName"
        val PENDING_MERGED_SERIES_FOLDERS = stringSetPreferencesKey("pending_merged_series_folders")
        // Hidden categories in Providers > Movies/Series — a genuinely separate concept from
        // FAVORITE_MERGED_CATEGORY_IDS (pinning reorders to the top; hiding removes from the
        // list entirely). Independent per mode, keyed "$serverIndex:$categoryId" for local use
        // exactly like FAVORITE_MERGED_CATEGORY_IDS.
        val HIDDEN_MERGED_VOD_CATEGORY_IDS = stringSetPreferencesKey("hidden_merged_vod_category_ids")
        val HIDDEN_MERGED_SERIES_CATEGORY_IDS = stringSetPreferencesKey("hidden_merged_series_category_ids")
        // Survives a true cold boot (process death), unlike HomeViewModel's savedMiniPlayerState
        // which only survives a rotation-triggered recreation — so onCreate can route back to
        // wherever the last-played LIVE channel actually lives (Favorites vs. Providers' server/
        // category) even after the app was fully killed and relaunched. -1/-1 = primary channel
        // (same sentinel convention as everywhere else), matching MiniPlayerState's shape.
        val LAST_PLAYED_SERVER_INDEX = intPreferencesKey("last_played_server_index")
        val LAST_PLAYED_STREAM_ID = intPreferencesKey("last_played_stream_id")
        // Unlike LAST_PLAYED_SERVER_INDEX/STREAM_ID above (which persist as a "most recent
        // channel" marker and are never cleared), this reflects whether a LIVE channel is
        // actually playing right now — set when PlayerActivity starts live playback, cleared in
        // onDestroy (not onStop, so it survives rotation/PiP). Used to warn when scheduling a
        // recording against the same provider a live stream is actively using.
        val LIVE_PLAYBACK_ACTIVE_SERVER_INDEX = intPreferencesKey("live_playback_active_server_index")
        // Restore/sync-down pending equivalents, keyed "$serverUrl|$categoryId" like
        // PENDING_MERGED_FAVORITE_CATEGORIES — applied once the URL resolves to a local
        // serverIndex (doesn't need that server's categories to actually be fetched yet, unlike
        // the favorites pending keys which need a local row to mark isFavorite on).
        val PENDING_HIDDEN_MERGED_VOD_CATEGORIES = stringSetPreferencesKey("pending_hidden_merged_vod_categories")
        val PENDING_HIDDEN_MERGED_SERIES_CATEGORIES = stringSetPreferencesKey("pending_hidden_merged_series_categories")
        // Applied once the new PRIMARY provider's channels are fetched, when switching TO a
        // provider that already had secondary-provider favorites recorded (see
        // XtreamRepository's Switch handling in SettingsActivity/TvSettingsActivity) — those
        // don't automatically become primary favorites just because the provider's role
        // changed, so this carries the folder name across the same way restore does.
        // Keyed "$streamId|$folderName" (no server URL prefix needed — this only ever applies
        // to the CURRENT primary once switched).
        val PENDING_PRIMARY_CHANNEL_FOLDERS = stringSetPreferencesKey("pending_primary_channel_folders")
        val EXTRA_SERVERS = stringPreferencesKey("extra_servers")
        val SERVER_NICKNAME = stringPreferencesKey("server_nickname")
    
        val ACTIVE_SERVER_INDEX = intPreferencesKey("active_server_index")
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val SYNC_GIST_ID = stringPreferencesKey("sync_gist_id")
        val SAVED_PAIRING_CODES = stringPreferencesKey("saved_pairing_codes")
        val LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        val EXTERNAL_PLAYER = stringPreferencesKey("external_player")
        val DOH_ENABLED = booleanPreferencesKey("doh_enabled")
        val PIP_ENABLED = booleanPreferencesKey("pip_enabled")
        val DOH_PROVIDER = stringPreferencesKey("doh_provider")
        val CHANNEL_SORT_MODE = intPreferencesKey("channel_sort_mode")
        val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val LAST_CHANNELS_FETCH_TIME = longPreferencesKey("last_channels_fetch_time")
        val GITHUB_TOKEN = stringPreferencesKey("github_token")
        val PRE_WARM_ON_FOCUS = booleanPreferencesKey("pre_warm_on_focus")
        val LAST_MERGED_CHANNELS_REFRESH = longPreferencesKey("last_merged_channels_refresh")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        // Empty = no gradient, accentColor is used as a plain solid color everywhere (unchanged
        // legacy behavior). Set only by the named gradient presets (Sunset/Ocean/etc.) — the
        // gradient is only ever rendered on surfaces that can actually show one (focus rings,
        // tab indicator); everywhere else (text color, progress tints) still just uses
        // accentColor's single value as the solid fallback, since those can't render a gradient.
        val ACCENT_COLOR_END = stringPreferencesKey("accent_color_end")
        val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
        val TRAKT_ACCESS_TOKEN = stringPreferencesKey("trakt_access_token")
        val TRAKT_REFRESH_TOKEN = stringPreferencesKey("trakt_refresh_token")
        val TRAKT_TOKEN_EXPIRES_AT = longPreferencesKey("trakt_token_expires_at")
        val SUBTITLE_SIZE_SCALE = floatPreferencesKey("subtitle_size_scale")
        val SUBTITLE_VERTICAL_OFFSET_DP = intPreferencesKey("subtitle_vertical_offset_dp")
        val SUBTITLE_BOLD = booleanPreferencesKey("subtitle_bold")
        val SUBTITLE_TEXT_COLOR = intPreferencesKey("subtitle_text_color")
        val SUBTITLE_BACKGROUND_COLOR = intPreferencesKey("subtitle_background_color")
        val SUBTITLE_OUTLINE_ENABLED = booleanPreferencesKey("subtitle_outline_enabled")
        val SUBTITLE_OUTLINE_COLOR = intPreferencesKey("subtitle_outline_color")
        val SUBTITLES_ENABLED = booleanPreferencesKey("subtitles_enabled")
        val TUNNELED_PLAYBACK_ENABLED = booleanPreferencesKey("tunneled_playback_enabled")
        val DV7_FALLBACK_ENABLED = booleanPreferencesKey("dv7_fallback_enabled")
        val AUDIO_PASSTHROUGH_FALLBACK_ENABLED = booleanPreferencesKey("audio_passthrough_fallback_enabled")
        val AUTOPLAY_NEXT_EPISODE_ENABLED = booleanPreferencesKey("autoplay_next_episode_enabled")
        val CHANNEL_ZAP_DEBOUNCE_MS = intPreferencesKey("channel_zap_debounce_ms")
        val LIVE_RECONNECT_SPEED = stringPreferencesKey("live_reconnect_speed")
        val SILENT_SELF_UPDATE_ENABLED = booleanPreferencesKey("silent_self_update_enabled")
        val EXTRA_BUFFERING_ENABLED = booleanPreferencesKey("extra_buffering_enabled")
        val ENGLISH_ONLY_MOVIES = booleanPreferencesKey("english_only_movies")
    }

    val silentSelfUpdateEnabled: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.SILENT_SELF_UPDATE_ENABLED] ?: false }
    suspend fun setSilentSelfUpdateEnabled(v: Boolean) = context.dataStore.edit { it[Keys.SILENT_SELF_UPDATE_ENABLED] = v }

    val tunneledPlaybackEnabled: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.TUNNELED_PLAYBACK_ENABLED] ?: false }
    suspend fun setTunneledPlaybackEnabled(v: Boolean) = context.dataStore.edit { it[Keys.TUNNELED_PLAYBACK_ENABLED] = v }

    val dv7FallbackEnabled: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.DV7_FALLBACK_ENABLED] ?: false }
    suspend fun setDv7FallbackEnabled(v: Boolean) = context.dataStore.edit { it[Keys.DV7_FALLBACK_ENABLED] = v }

    // Off by default — most setups (phone speaker, TV's own speakers, or an actual connected
    // AVR) handle passthrough fine, and forcing PCM means losing surround sound on a device
    // that DID have a receiver. Only turn on if E-AC3/DTS streams produce no sound at all.
    val audioPassthroughFallbackEnabled: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.AUDIO_PASSTHROUGH_FALLBACK_ENABLED] ?: false }
    suspend fun setAudioPassthroughFallbackEnabled(v: Boolean) = context.dataStore.edit { it[Keys.AUDIO_PASSTHROUGH_FALLBACK_ENABLED] = v }

    // On by default — auto-advancing to the next episode with a cancelable 10s countdown is
    // the expected behavior for a binge-watching feature; a user who dislikes it can turn it off.
    val autoplayNextEpisodeEnabled: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.AUTOPLAY_NEXT_EPISODE_ENABLED] ?: true }
    suspend fun setAutoplayNextEpisodeEnabled(v: Boolean) = context.dataStore.edit { it[Keys.AUTOPLAY_NEXT_EPISODE_ENABLED] = v }

    // 0 = instant/off (every D-pad press switches immediately, the original behavior). A
    // non-zero value waits that many ms after the LAST press before actually resolving/loading
    // the new stream, so rapid-fire channel-up/down mashing settles on one final channel switch
    // instead of potentially firing several real network resolves/player reloads in a row.
    val channelZapDebounceMs: Flow<Int> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.CHANNEL_ZAP_DEBOUNCE_MS] ?: 0 }
    suspend fun setChannelZapDebounceMs(ms: Int) = context.dataStore.edit { it[Keys.CHANNEL_ZAP_DEBOUNCE_MS] = ms }

    // Controls how fast/how long live playback backs off and keeps retrying on a stalled/dropped
    // connection — live never gives up entirely (unlike VOD, which stops after maxRetries and
    // tries a format fallback), it only ever changes how the backoff ramps and where it holds.
    // "normal" (default) preserves the original hardcoded behavior (2s steps, 30s ceiling) exactly.
    val liveReconnectSpeed: Flow<String> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.LIVE_RECONNECT_SPEED] ?: "normal" }
    suspend fun setLiveReconnectSpeed(speed: String) = context.dataStore.edit { it[Keys.LIVE_RECONNECT_SPEED] = speed }

    // How many minutes before a program starts a "Remind Me" notification fires — previously
    // hardcoded to 0 (fired exactly at start time, so the notification read "X is starting now"
    // but the show had already begun by the time you saw it and switched channels).
    val reminderLeadMinutes: Flow<Int> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[REMINDER_LEAD_MINUTES_KEY] ?: 5 }
    suspend fun setReminderLeadMinutes(minutes: Int) = context.dataStore.edit { it[REMINDER_LEAD_MINUTES_KEY] = minutes }

    // Global, applies to every server — not a per-server setting. Defaults on since slower/
    // less reliable IPTV providers are the norm here, and the bigger buffer directly trades
    // a few extra seconds of initial load for fewer mid-playback stalls/freezes.
    val extraBufferingEnabled: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.EXTRA_BUFFERING_ENABLED] ?: true }
    suspend fun setExtraBufferingEnabled(v: Boolean) = context.dataStore.edit { it[Keys.EXTRA_BUFFERING_ENABLED] = v }

    data class SubtitleStyle(
        val sizeScale: Float,
        val verticalOffsetDp: Int,
        val bold: Boolean,
        val textColor: Int,
        val backgroundColor: Int,
        val outlineEnabled: Boolean,
        val outlineColor: Int
    )

    val subtitleStyle: Flow<SubtitleStyle> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            SubtitleStyle(
                sizeScale = p[Keys.SUBTITLE_SIZE_SCALE] ?: 1.0f,
                verticalOffsetDp = p[Keys.SUBTITLE_VERTICAL_OFFSET_DP] ?: 0,
                bold = p[Keys.SUBTITLE_BOLD] ?: false,
                textColor = p[Keys.SUBTITLE_TEXT_COLOR] ?: 0xFFFFFFFF.toInt(),
                backgroundColor = p[Keys.SUBTITLE_BACKGROUND_COLOR] ?: 0x00000000,
                outlineEnabled = p[Keys.SUBTITLE_OUTLINE_ENABLED] ?: true,
                outlineColor = p[Keys.SUBTITLE_OUTLINE_COLOR] ?: 0xFF000000.toInt()
            )
        }

    suspend fun setSubtitleSizeScale(v: Float) = context.dataStore.edit { it[Keys.SUBTITLE_SIZE_SCALE] = v }
    suspend fun setSubtitleVerticalOffsetDp(v: Int) = context.dataStore.edit { it[Keys.SUBTITLE_VERTICAL_OFFSET_DP] = v }
    suspend fun setSubtitleBold(v: Boolean) = context.dataStore.edit { it[Keys.SUBTITLE_BOLD] = v }
    suspend fun setSubtitleTextColor(v: Int) = context.dataStore.edit { it[Keys.SUBTITLE_TEXT_COLOR] = v }
    suspend fun setSubtitleBackgroundColor(v: Int) = context.dataStore.edit { it[Keys.SUBTITLE_BACKGROUND_COLOR] = v }
    suspend fun setSubtitleOutlineEnabled(v: Boolean) = context.dataStore.edit { it[Keys.SUBTITLE_OUTLINE_ENABLED] = v }
    suspend fun setSubtitleOutlineColor(v: Int) = context.dataStore.edit { it[Keys.SUBTITLE_OUTLINE_COLOR] = v }

    // Subtitles used to require picking a track manually every single session (never
    // persisted, never auto-selected) — default true so whatever track is available just
    // shows up on its own, with the existing CC dialog's "Off" option now actually sticking.
    val subtitlesEnabled: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.SUBTITLES_ENABLED] ?: true }
    suspend fun setSubtitlesEnabled(v: Boolean) = context.dataStore.edit { it[Keys.SUBTITLES_ENABLED] = v }

    val traktAccessToken: Flow<String?> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.TRAKT_ACCESS_TOKEN] }

    val traktConnected: Flow<Boolean> = traktAccessToken.map { !it.isNullOrBlank() }

    suspend fun traktAccessTokenBlocking(): String? = traktAccessToken.first()

    suspend fun saveTraktTokens(accessToken: String, refreshToken: String, expiresAt: Long) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TRAKT_ACCESS_TOKEN] = accessToken
            prefs[Keys.TRAKT_REFRESH_TOKEN] = refreshToken
            prefs[Keys.TRAKT_TOKEN_EXPIRES_AT] = expiresAt
        }
    }

    suspend fun getTraktRefreshToken(): String? =
        context.dataStore.data.map { it[Keys.TRAKT_REFRESH_TOKEN] }.first()

    suspend fun getTraktTokenExpiresAt(): Long =
        context.dataStore.data.map { it[Keys.TRAKT_TOKEN_EXPIRES_AT] ?: 0L }.first()

    suspend fun clearTraktTokens() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.TRAKT_ACCESS_TOKEN)
            prefs.remove(Keys.TRAKT_REFRESH_TOKEN)
            prefs.remove(Keys.TRAKT_TOKEN_EXPIRES_AT)
        }
    }

    val amoledBlack: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.AMOLED_BLACK] ?: false }

    suspend fun setAmoledBlack(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AMOLED_BLACK] = enabled }
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

    val preferredAudioLanguage: Flow<String> = context.dataStore.data
        .map { it[Keys.PREFERRED_AUDIO_LANGUAGE] ?: "" }

    val preferredSubtitleLanguage: Flow<String> = context.dataStore.data
        .map { it[Keys.PREFERRED_SUBTITLE_LANGUAGE] ?: "" }

    val recordingFolderName: Flow<String> = context.dataStore.data
        .map { it[Keys.RECORDING_FOLDER_NAME] ?: "MKTV" }

    val autoDeleteRecordingsDays: Flow<Int> = context.dataStore.data
        .map { it[Keys.AUTO_DELETE_RECORDINGS_DAYS] ?: 0 }
    val autoClearContinueWatchingDays: Flow<Int> = context.dataStore.data
        .map { it[Keys.AUTO_CLEAR_CONTINUE_WATCHING_DAYS] ?: 0 }
    val crashReportingEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.CRASH_REPORTING_ENABLED] ?: true }

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

    // Experimental — off by default since it depends entirely on the provider actually
    // tagging category names with an "EN" language prefix, which isn't guaranteed.
    val englishOnlyMovies: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.ENGLISH_ONLY_MOVIES] ?: false }
    suspend fun setEnglishOnlyMovies(v: Boolean) = context.dataStore.edit { it[Keys.ENGLISH_ONLY_MOVIES] = v }

    val favoriteLiveCategoryIds: Flow<Set<String>> = context.dataStore.data
        .map { it[Keys.FAVORITE_LIVE_CATEGORY_IDS] ?: emptySet() }

    val favoriteMergedCategoryIds: Flow<Set<String>> = context.dataStore.data
        .map { it[Keys.FAVORITE_MERGED_CATEGORY_IDS] ?: emptySet() }

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

    suspend fun setPreferredAudioLanguage(code: String) {
        context.dataStore.edit { prefs -> prefs[Keys.PREFERRED_AUDIO_LANGUAGE] = code }
    }

    suspend fun setPreferredSubtitleLanguage(code: String) {
        context.dataStore.edit { prefs -> prefs[Keys.PREFERRED_SUBTITLE_LANGUAGE] = code }
    }

    suspend fun setRecordingFolderName(name: String) {
        context.dataStore.edit { prefs -> prefs[Keys.RECORDING_FOLDER_NAME] = name }
    }

    suspend fun setAutoDeleteRecordingsDays(days: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTO_DELETE_RECORDINGS_DAYS] = days }
    }

    suspend fun setAutoClearContinueWatchingDays(days: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.AUTO_CLEAR_CONTINUE_WATCHING_DAYS] = days }
    }

    suspend fun setCrashReportingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.CRASH_REPORTING_ENABLED] = enabled }
    }

    suspend fun setEpgUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EPG_URL] = url
        }
    }

    suspend fun getEpgUrls(): List<String> {
        val data = context.dataStore.data.first()
        val json = data[Keys.EPG_URLS] ?: "[]"
        val arr = org.json.JSONArray(json)
        val urls = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        if (urls.isEmpty()) {
            val primary = data[Keys.EPG_URL] ?: ""
            if (primary.isNotEmpty()) urls.add(primary)
        }
        return urls
    }

    suspend fun saveEpgUrls(urls: List<String>) {
        val arr = org.json.JSONArray()
        urls.forEach { arr.put(it) }
        context.dataStore.edit {
            it[Keys.EPG_URLS] = arr.toString()
            it[Keys.EPG_URL] = urls.firstOrNull() ?: ""
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
    val showWatching: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_WATCHING] ?: true }

    suspend fun setShowMovies(enabled: Boolean) { context.dataStore.edit { it[Keys.SHOW_MOVIES] = enabled } }
    suspend fun setShowSeries(enabled: Boolean) { context.dataStore.edit { it[Keys.SHOW_SERIES] = enabled } }
    suspend fun setShowWatching(enabled: Boolean) { context.dataStore.edit { it[Keys.SHOW_WATCHING] = enabled } }

    // "internal" | "vlc" | "mxplayer" | "system"
    val externalPlayer: Flow<String> = context.dataStore.data.map { it[Keys.EXTERNAL_PLAYER] ?: "internal" }
    suspend fun setExternalPlayer(player: String) { context.dataStore.edit { it[Keys.EXTERNAL_PLAYER] = player } }

    val dohEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.DOH_ENABLED] ?: false }
    suspend fun setDohEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.DOH_ENABLED] = enabled } }

    val pipEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.PIP_ENABLED] ?: true }
    suspend fun setPipEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.PIP_ENABLED] = enabled } }
    val dohProvider: Flow<String> = context.dataStore.data.map { it[Keys.DOH_PROVIDER] ?: "cloudflare" }
    suspend fun setDohProvider(provider: String) { context.dataStore.edit { it[Keys.DOH_PROVIDER] = provider } }
    val channelSortMode: Flow<Int> = context.dataStore.data.map { it[Keys.CHANNEL_SORT_MODE] ?: 0 }
    suspend fun setChannelSortMode(mode: Int) { context.dataStore.edit { it[Keys.CHANNEL_SORT_MODE] = mode } }

    val autoBackupEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_BACKUP_ENABLED] ?: false }
    suspend fun setAutoBackupEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.AUTO_BACKUP_ENABLED] = enabled } }

    val lastChannelsFetchTime: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_CHANNELS_FETCH_TIME] ?: 0L }
    suspend fun setLastChannelsFetchTime(timeMs: Long) { context.dataStore.edit { it[Keys.LAST_CHANNELS_FETCH_TIME] = timeMs } }

    val lastPlayedServerIndex: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_PLAYED_SERVER_INDEX] ?: -1 }
    val lastPlayedStreamId: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_PLAYED_STREAM_ID] ?: -1 }
    suspend fun setLastPlayedChannel(serverIndex: Int, streamId: Int) {
        context.dataStore.edit {
            it[Keys.LAST_PLAYED_SERVER_INDEX] = serverIndex
            it[Keys.LAST_PLAYED_STREAM_ID] = streamId
        }
    }

    val livePlaybackActiveServerIndex: Flow<Int?> = context.dataStore.data.map { it[Keys.LIVE_PLAYBACK_ACTIVE_SERVER_INDEX] }
    suspend fun setLivePlaybackActive(serverIndex: Int) {
        context.dataStore.edit { it[Keys.LIVE_PLAYBACK_ACTIVE_SERVER_INDEX] = serverIndex }
    }
    suspend fun clearLivePlaybackActive() {
        context.dataStore.edit { it.remove(Keys.LIVE_PLAYBACK_ACTIVE_SERVER_INDEX) }
    }

    val githubToken: Flow<String> = context.dataStore.data.map { it[Keys.GITHUB_TOKEN] ?: "" }
    suspend fun setGithubToken(token: String) { context.dataStore.edit { it[Keys.GITHUB_TOKEN] = token } }

    // Gates HomeActivity's cold-start auto-refresh of merged/secondary providers' live channels
    // — without this, every single app launch re-hit every configured provider's API regardless
    // of how recently it last succeeded, which is wasted network traffic (and, for providers with
    // strict connection limits, actively counterproductive).
    val lastMergedChannelsRefresh: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_MERGED_CHANNELS_REFRESH] ?: 0L }
    suspend fun setLastMergedChannelsRefresh(timestamp: Long) {
        context.dataStore.edit { it[Keys.LAST_MERGED_CHANNELS_REFRESH] = timestamp }
    }

    val preWarmOnFocus: Flow<Boolean> = context.dataStore.data.map { it[Keys.PRE_WARM_ON_FOCUS] ?: true }
    suspend fun setPreWarmOnFocus(enabled: Boolean) { context.dataStore.edit { it[Keys.PRE_WARM_ON_FOCUS] = enabled } }

    val accentColor: Flow<String> = context.dataStore.data.map { it[Keys.ACCENT_COLOR] ?: "#008CFF" }
    val accentColorEnd: Flow<String> = context.dataStore.data.map { it[Keys.ACCENT_COLOR_END] ?: "" }
    suspend fun setAccentColor(color: String) {
        // A plain solid pick (the existing preset swatches / custom hue picker) always clears
        // any previously-set gradient end color — otherwise switching from a gradient preset
        // back to a flat color would leave the old gradient's second color lingering and still
        // applied on focus rings/tab indicator.
        context.dataStore.edit {
            it[Keys.ACCENT_COLOR] = color
            it[Keys.ACCENT_COLOR_END] = ""
        }
    }
    suspend fun setAccentGradient(startColor: String, endColor: String) {
        context.dataStore.edit {
            it[Keys.ACCENT_COLOR] = startColor
            it[Keys.ACCENT_COLOR_END] = endColor
        }
    }

    suspend fun addFavoriteLiveCategoryId(categoryId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_LIVE_CATEGORY_IDS] ?: emptySet()
            prefs[Keys.FAVORITE_LIVE_CATEGORY_IDS] = current + categoryId
        }
    }

    suspend fun addFavoriteMergedCategoryId(key: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_MERGED_CATEGORY_IDS] ?: emptySet()
            prefs[Keys.FAVORITE_MERGED_CATEGORY_IDS] = current + key
        }
    }

    suspend fun removeFavoriteMergedCategoryId(key: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_MERGED_CATEGORY_IDS] ?: emptySet()
            prefs[Keys.FAVORITE_MERGED_CATEGORY_IDS] = current - key
        }
    }

    val hiddenMergedVodCategoryIds: Flow<Set<String>> = context.dataStore.data
        .map { it[Keys.HIDDEN_MERGED_VOD_CATEGORY_IDS] ?: emptySet() }

    suspend fun addHiddenMergedVodCategoryIds(keys: Set<String>) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_MERGED_VOD_CATEGORY_IDS] ?: emptySet()
            prefs[Keys.HIDDEN_MERGED_VOD_CATEGORY_IDS] = current + keys
        }
    }

    suspend fun removeHiddenMergedVodCategoryId(key: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_MERGED_VOD_CATEGORY_IDS] ?: emptySet()
            prefs[Keys.HIDDEN_MERGED_VOD_CATEGORY_IDS] = current - key
        }
    }

    val hiddenMergedSeriesCategoryIds: Flow<Set<String>> = context.dataStore.data
        .map { it[Keys.HIDDEN_MERGED_SERIES_CATEGORY_IDS] ?: emptySet() }

    suspend fun addHiddenMergedSeriesCategoryIds(keys: Set<String>) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_MERGED_SERIES_CATEGORY_IDS] ?: emptySet()
            prefs[Keys.HIDDEN_MERGED_SERIES_CATEGORY_IDS] = current + keys
        }
    }

    suspend fun removeHiddenMergedSeriesCategoryId(key: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_MERGED_SERIES_CATEGORY_IDS] ?: emptySet()
            prefs[Keys.HIDDEN_MERGED_SERIES_CATEGORY_IDS] = current - key
        }
    }

    val pendingHiddenMergedVodCategories: Flow<Set<String>> = context.dataStore.data
        .map { it[Keys.PENDING_HIDDEN_MERGED_VOD_CATEGORIES] ?: emptySet() }
    suspend fun setPendingHiddenMergedVodCategories(keys: Set<String>) {
        context.dataStore.edit { it[Keys.PENDING_HIDDEN_MERGED_VOD_CATEGORIES] = keys }
    }

    val pendingHiddenMergedSeriesCategories: Flow<Set<String>> = context.dataStore.data
        .map { it[Keys.PENDING_HIDDEN_MERGED_SERIES_CATEGORIES] ?: emptySet() }
    suspend fun setPendingHiddenMergedSeriesCategories(keys: Set<String>) {
        context.dataStore.edit { it[Keys.PENDING_HIDDEN_MERGED_SERIES_CATEGORIES] = keys }
    }

    suspend fun getExtraServers(): List<Triple<String,String,String>> {
        val json = context.dataStore.data.first()[Keys.EXTRA_SERVERS] ?: "[]"
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            Triple(obj.getString("url"), obj.getString("user"), obj.getString("pass"))
        }
    }

        val activeServerIndex: Flow<Int> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[Keys.ACTIVE_SERVER_INDEX] ?: -1 }

    suspend fun setActiveServerIndex(index: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.ACTIVE_SERVER_INDEX] = index }
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

    suspend fun getExtraServersWithNick(): List<List<String>> {
        val json = context.dataStore.data.first()[Keys.EXTRA_SERVERS] ?: "[]"
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            // "epg" (index 4) used to be silently dropped here even though the Add/Edit
            // Provider dialog always sent it — the per-provider EPG URL field looked like it
            // never saved, when really it was being read back as if it didn't exist.
            listOf(
                obj.getString("url"), obj.getString("user"), obj.getString("pass"),
                obj.optString("nick", ""), obj.optString("epg", ""),
                // "enabled" (index 5) — optString default "true" means every existing install's
                // already-saved providers stay enabled with zero migration needed.
                obj.optString("enabled", "true")
            )
        }
    }

    suspend fun saveExtraServersWithNick(servers: List<List<String>>) {
        val arr = org.json.JSONArray()
        servers.forEach { s ->
            arr.put(org.json.JSONObject().apply {
                put("url", s[0]); put("user", s[1]); put("pass", s[2])
                put("nick", s.getOrElse(3) { "" }); put("epg", s.getOrElse(4) { "" })
                put("enabled", s.getOrElse(5) { "true" })
            })
        }
        context.dataStore.edit { it[Keys.EXTRA_SERVERS] = arr.toString() }
    }

        val serverNickname: Flow<String> = context.dataStore.data
        .map { it[Keys.SERVER_NICKNAME] ?: "" }

    suspend fun setServerNickname(nickname: String) {
        context.dataStore.edit { it[Keys.SERVER_NICKNAME] = nickname }
    }

    val pendingFavoriteChannelIds: Flow<Set<String>> = context.dataStore.data.map { it[Keys.PENDING_FAV_CHANNEL_IDS] ?: emptySet() }

    suspend fun setPendingFavoriteChannelIds(ids: Set<Int>) {
        context.dataStore.edit { it[Keys.PENDING_FAV_CHANNEL_IDS] = ids.map { id -> id.toString() }.toSet() }
    }

    suspend fun clearPendingFavoriteChannelIds() {
        context.dataStore.edit { it[Keys.PENDING_FAV_CHANNEL_IDS] = emptySet() }
    }

    val pendingPrimaryChannelFolders: Flow<Set<String>> = context.dataStore.data.map { it[Keys.PENDING_PRIMARY_CHANNEL_FOLDERS] ?: emptySet() }
    suspend fun setPendingPrimaryChannelFolders(keys: Set<String>) {
        context.dataStore.edit { it[Keys.PENDING_PRIMARY_CHANNEL_FOLDERS] = keys }
    }

    val pendingMergedFavorites: Flow<Set<String>> = context.dataStore.data.map { it[Keys.PENDING_MERGED_FAVORITES] ?: emptySet() }
    suspend fun setPendingMergedFavorites(keys: Set<String>) {
        context.dataStore.edit { it[Keys.PENDING_MERGED_FAVORITES] = keys }
    }

    val pendingMergedFavoriteCategories: Flow<Set<String>> = context.dataStore.data.map { it[Keys.PENDING_MERGED_FAVORITE_CATEGORIES] ?: emptySet() }
    suspend fun setPendingMergedFavoriteCategories(keys: Set<String>) {
        context.dataStore.edit { it[Keys.PENDING_MERGED_FAVORITE_CATEGORIES] = keys }
    }

    val pendingMergedChannelFolders: Flow<Set<String>> = context.dataStore.data.map { it[Keys.PENDING_MERGED_CHANNEL_FOLDERS] ?: emptySet() }
    suspend fun setPendingMergedChannelFolders(keys: Set<String>) {
        context.dataStore.edit { it[Keys.PENDING_MERGED_CHANNEL_FOLDERS] = keys }
    }

    val pendingMergedVodFavorites: Flow<Set<String>> = context.dataStore.data.map { it[Keys.PENDING_MERGED_VOD_FAVORITES] ?: emptySet() }
    suspend fun setPendingMergedVodFavorites(keys: Set<String>) {
        context.dataStore.edit { it[Keys.PENDING_MERGED_VOD_FAVORITES] = keys }
    }

    val pendingMergedVodFolders: Flow<Set<String>> = context.dataStore.data.map { it[Keys.PENDING_MERGED_VOD_FOLDERS] ?: emptySet() }
    suspend fun setPendingMergedVodFolders(keys: Set<String>) {
        context.dataStore.edit { it[Keys.PENDING_MERGED_VOD_FOLDERS] = keys }
    }

    val pendingMergedSeriesFavorites: Flow<Set<String>> = context.dataStore.data.map { it[Keys.PENDING_MERGED_SERIES_FAVORITES] ?: emptySet() }
    suspend fun setPendingMergedSeriesFavorites(keys: Set<String>) {
        context.dataStore.edit { it[Keys.PENDING_MERGED_SERIES_FAVORITES] = keys }
    }

    val pendingMergedSeriesFolders: Flow<Set<String>> = context.dataStore.data.map { it[Keys.PENDING_MERGED_SERIES_FOLDERS] ?: emptySet() }
    suspend fun setPendingMergedSeriesFolders(keys: Set<String>) {
        context.dataStore.edit { it[Keys.PENDING_MERGED_SERIES_FOLDERS] = keys }
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

    // ─── Cross-device sync ───────────────────────────────────────────────────

    val syncEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.SYNC_ENABLED] ?: false }
    val lastSyncTime: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_SYNC_TIME] ?: 0L }

    suspend fun setSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SYNC_ENABLED] = enabled }
    }

    suspend fun getSyncGistId(): String {
        return context.dataStore.data.first()[Keys.SYNC_GIST_ID] ?: ""
    }

    suspend fun setSyncGistId(id: String) {
        context.dataStore.edit { it[Keys.SYNC_GIST_ID] = id }
    }

    // Every code the user successfully pairs with is remembered here (most-recent first, capped
    // at 10) so re-pairing with a device they've already used doesn't mean re-typing the code.
    suspend fun getSavedPairingCodes(): List<String> {
        val json = context.dataStore.data.first()[Keys.SAVED_PAIRING_CODES] ?: "[]"
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    suspend fun addSavedPairingCode(code: String) {
        if (code.isBlank()) return
        val existing = getSavedPairingCodes().filter { it != code }
        val updated = (listOf(code) + existing).take(10)
        val arr = org.json.JSONArray()
        updated.forEach { arr.put(it) }
        context.dataStore.edit { it[Keys.SAVED_PAIRING_CODES] = arr.toString() }
    }

    suspend fun removeSavedPairingCode(code: String) {
        val updated = getSavedPairingCodes().filter { it != code }
        val arr = org.json.JSONArray()
        updated.forEach { arr.put(it) }
        context.dataStore.edit { it[Keys.SAVED_PAIRING_CODES] = arr.toString() }
    }

    suspend fun setLastSyncTime(timeMillis: Long) {
        context.dataStore.edit { it[Keys.LAST_SYNC_TIME] = timeMillis }
    }
}
