package com.iptvapp.data.profile

import android.content.Context
import android.database.Cursor
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.withTransaction
import com.iptvapp.data.home.HomeLayoutStore
import com.iptvapp.data.home.ViewingPersona
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves profiles' own data in and out. The rest of MKTV reads favorites, history, progress and
 * Home settings where they always were (the tables and settings); those always hold the active
 * profile's. A switch saves the leaving profile's into its file (files/profiles/<id>.json) and
 * puts the arriving profile's in their place, so nothing else had to change and Primary keeps
 * exactly what it had before profiles existed.
 *
 * Kept per profile: channel, movie, series and category favorites and their order, folders and
 * genre chips; watch history, movie / series / episode progress; recent and last channels; Home
 * layouts, viewing styles, the TV sidebar and mini player, card size, theme and accent, Smart
 * Rows, Top Favorites, favorite teams and Home's Movies / Series / Watching tabs. Everything else
 * (logins, providers, hidden channels, channel numbers, recordings, playback and accessibility
 * settings) belongs to the device.
 *
 * Saved rows name their provider login; they are put back only onto that login's lists (a main
 * provider changed since gives that profile a fresh start there, never someone else's streamIds).
 */
@Singleton
class ProfileSwitcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager,
    private val repository: XtreamRepository,
) {
    private val lock = Mutex()
    private val dir: File get() = File(context.filesDir, "profiles").apply { mkdirs() }

    /** Switches to profile [targetId]. False when it doesn't exist; true once its data is in. */
    suspend fun switchTo(targetId: String): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock {
            val target = ProfileStore.profilesSaved(prefs).firstOrNull { it.id == targetId } ?: return@withLock false
            val current = prefs.activeProfileId.first()
            if (current == target.id) return@withLock true
            repository.withPersonalDataExclusive {
                // Saved first, then the marker: from here a crash finishes the switch at next start.
                writeAtomically(snapshotFile(current), capture().toString())
                writeAtomically(markerFile(), JSONObject().put("to", target.id).toString())
                finish(target)
            }
            true
        }
    }

    /** A switch cut short (the app was stopped half way): finishes it. Called at app start. */
    suspend fun recoverInterruptedSwitch() = withContext(NonCancellable + Dispatchers.IO) {
        lock.withLock {
            val marker = markerFile().takeIf { it.exists() } ?: return@withLock
            val to = runCatching { JSONObject(marker.readText()).optString("to") }.getOrNull()
            val target = ProfileStore.profilesSaved(prefs).firstOrNull { it.id == to }
                ?: ProfileStore.profilesSaved(prefs).first()
            repository.withPersonalDataExclusive { finish(target) }
        }
    }

    /** Forgets a removed profile's saved data. */
    suspend fun deleteData(profileId: String) = withContext(Dispatchers.IO) {
        lock.withLock { snapshotFile(profileId).delete() }
    }

    // Puts [target]'s data in (the leaving profile's is already saved). Safe to run again.
    private suspend fun finish(target: Profile) {
        val file = snapshotFile(target.id)
        val saved = file.takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        apply(saved ?: JSONObject(), target)
        if (saved == null) {
            // First time in: the viewing style picked for it, on phone and TV.
            ViewingPersona.entries.firstOrNull { it.id == target.startPersonaId && it != ViewingPersona.CUSTOM }?.let { persona ->
                HomeLayoutStore.applyPersona(prefs, tv = true, persona = persona)
                HomeLayoutStore.applyPersona(prefs, tv = false, persona = persona)
            }
        }
        markerFile().delete()
    }

    // ── Snapshot: what a profile owns ───────────────────────────────────────────────────

    private class Table(val name: String, val keys: List<String>, val cols: List<Pair<String, String>>, val otherProviders: Boolean = false) {
        val atDefault = cols.joinToString(" AND ") { (c, d) -> "$c IS $d" }
    }

    private val tables = listOf(
        Table("channels", listOf("streamId"), listOf(
            "isFavorite" to "0", "favOrder" to "0", "favoriteFolderId" to "NULL", "manualGenre" to "NULL",
            "lastWatched" to "NULL", "viewCount" to "0",
        )),
        Table("categories", listOf("categoryId"), listOf("isFavorite" to "0")),
        Table("vod_streams", listOf("streamId"), listOf(
            "isFavorite" to "0", "watchedMs" to "0", "durationMs" to "0", "lastWatchedAt" to "0", "dismissedFromContinueWatching" to "0",
        )),
        Table("series", listOf("seriesId"), listOf(
            "isFavorite" to "0", "watchedMs" to "0", "durationMs" to "0", "lastWatchedAt" to "0", "dismissedFromContinueWatching" to "0",
        )),
        Table("merged_channels", listOf("serverIndex", "streamId"), listOf(
            "isFavorite" to "0", "favoriteFolderId" to "NULL", "favOrder" to "0", "manualGenre" to "NULL",
        ), otherProviders = true),
        Table("merged_vod", listOf("serverIndex", "streamId"), listOf(
            "isFavorite" to "0", "favoriteFolderId" to "NULL", "watchedMs" to "0", "durationMs" to "0",
        ), otherProviders = true),
        Table("merged_series", listOf("serverIndex", "seriesId"), listOf("isFavorite" to "0", "favoriteFolderId" to "NULL"), otherProviders = true),
    )

    /** Settings that belong to the profile (PreferencesManager key names). */
    private val profileKeys = setOf(
        "favorite_live_category_ids", "favorite_merged_category_ids",
        "last_played_server_index", "last_played_stream_id", "recent_channels",
        "accent_color", "accent_color_end", "amoled_black", "app_base_theme",
        "home_layout_tv", "home_layout_phone", "tv_sidebar_config", "home_persona_tv", "home_persona_phone",
        "home_card_density", "tv_home_mini_player_mode",
        "home_smart_row_limit", "home_auto_collapse_empty_rows", "home_continue_watching_hide_completed",
        "top_favorites", "favorite_teams", "favorite_other_genre_label", "new_episode_last_seen_counts",
        "show_movies", "show_series", "show_watching",
    )

    private suspend fun login(): String = prefs.credentials.first().let { "${it.serverUrl.trim()}\n${it.username.trim()}" }

    /** Login per provider slot now: -1 is the main provider. */
    private suspend fun slots(): Map<Int, String> =
        mapOf(-1 to login()) + prefs.getExtraServersWithNick().mapIndexed { i, s -> i to "${s[0].trim()}\n${s[1].trim()}" }

    private suspend fun capture(): JSONObject {
        val sql = db.openHelper.writableDatabase
        val out = JSONObject().put("version", 1).put("login", login())
        out.put("slots", JSONObject().apply { slots().forEach { (i, l) -> put(i.toString(), l) } })
        val rows = JSONObject()
        for (t in tables) {
            val cols = t.keys + t.cols.map { it.first }
            val list = JSONArray()
            sql.query("SELECT ${cols.joinToString()} FROM ${t.name} WHERE NOT (${t.atDefault})").use { c ->
                while (c.moveToNext()) list.put(JSONArray().apply { cols.indices.forEach { put(value(c, it)) } })
            }
            rows.put(t.name, list)
        }
        val episodes = JSONArray()
        sql.query("SELECT seriesId, season, episode, watchedAt, watchedMs, durationMs FROM episode_watched").use { c ->
            while (c.moveToNext()) episodes.put(JSONArray().apply { (0 until 6).forEach { put(value(c, it)) } })
        }
        rows.put("episode_watched", episodes)
        out.put("rows", rows)
        val settings = JSONObject()
        prefs.allPrefs().asMap().forEach { (key, v) -> if (key.name in profileKeys) encode(v)?.let { settings.put(key.name, it) } }
        out.put("prefs", settings)
        return out
    }

    private suspend fun apply(saved: JSONObject, target: Profile) {
        val sameLogin = saved.optString("login", "") == login()
        // Saved slot -> slot now, by login (a provider may have moved, or gone).
        val savedSlots = saved.optJSONObject("slots")
        val slotNow = slots().entries.associate { (i, l) -> l to i }
        fun mapSlot(saved: Long): Int? = savedSlots?.optString(saved.toString())?.takeIf { it.isNotEmpty() }?.let { slotNow[it] }
        val rows = saved.optJSONObject("rows") ?: JSONObject()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            for (t in tables) {
                sql.execSQL("UPDATE ${t.name} SET ${t.cols.joinToString { (c, d) -> "$c = $d" }} WHERE NOT (${t.atDefault})")
                if (!t.otherProviders && !sameLogin) continue
                val list = rows.optJSONArray(t.name) ?: continue
                val set = t.cols.joinToString { "${it.first} = ?" }
                val where = t.keys.joinToString(" AND ") { "$it = ?" }
                for (i in 0 until list.length()) {
                    val r = list.optJSONArray(i) ?: continue
                    val keys = t.keys.indices.map { r.opt(it).let { v -> if (v == JSONObject.NULL) null else v } }.toMutableList()
                    if (t.otherProviders) keys[0] = mapSlot((keys[0] as? Number)?.toLong() ?: continue) ?: continue
                    val values = t.cols.indices.map { r.opt(t.keys.size + it).let { v -> if (v == JSONObject.NULL) null else v } }
                    sql.execSQL("UPDATE ${t.name} SET $set WHERE $where", (values + keys).toTypedArray())
                }
            }
            sql.execSQL("DELETE FROM episode_watched")
            if (sameLogin) rows.optJSONArray("episode_watched")?.let { list ->
                for (i in 0 until list.length()) {
                    val r = list.optJSONArray(i) ?: continue
                    sql.execSQL(
                        "INSERT OR REPLACE INTO episode_watched (seriesId, season, episode, watchedAt, watchedMs, durationMs) VALUES (?, ?, ?, ?, ?, ?)",
                        (0 until 6).map { r.opt(it).let { v -> if (v == JSONObject.NULL) null else v } }.toTypedArray(),
                    )
                }
            }
            // Folders are the household's: one removed since leaves its channels unfiled.
            for (t in listOf("channels", "merged_channels", "merged_vod", "merged_series")) {
                sql.execSQL("UPDATE $t SET favoriteFolderId = NULL WHERE favoriteFolderId IS NOT NULL AND favoriteFolderId NOT IN (SELECT id FROM favorite_folders)")
            }
        }
        val settings = saved.optJSONObject("prefs") ?: JSONObject()
        prefs.switchProfilePrefs(target.id, target.isKids, profileKeys) { p ->
            settings.keys().forEach { name -> if (name in profileKeys) decode(p, name, settings.optJSONObject(name)) }
        }
    }

    private fun value(c: Cursor, i: Int): Any = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        else -> c.getString(i)
    }

    private fun encode(v: Any): JSONObject? = when (v) {
        is String -> JSONObject().put("t", "s").put("v", v)
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is Double -> JSONObject().put("t", "d").put("v", v)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.filterIsInstance<String>()))
        else -> null
    }

    private fun decode(p: MutablePreferences, name: String, o: JSONObject?) {
        o ?: return
        when (o.optString("t")) {
            "s" -> p[stringPreferencesKey(name)] = o.optString("v")
            "b" -> p[booleanPreferencesKey(name)] = o.optBoolean("v")
            "i" -> p[intPreferencesKey(name)] = o.optInt("v")
            "l" -> p[longPreferencesKey(name)] = o.optLong("v")
            "f" -> p[floatPreferencesKey(name)] = o.optDouble("v").toFloat()
            "d" -> p[doublePreferencesKey(name)] = o.optDouble("v")
            "ss" -> o.optJSONArray("v")?.let { a -> p[stringSetPreferencesKey(name)] = (0 until a.length()).map { a.optString(it) }.toSet() }
        }
    }

    private fun snapshotFile(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}.json")
    private fun markerFile() = File(dir, "switching.json")

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Couldn't save ${file.name}" }
        }
    }

    /** For tests: the setting names a profile owns. */
    internal fun ownedSettingNames(): Set<String> = profileKeys
}

