package com.iptvapp.tv

import android.content.Context
import com.iptvapp.data.api.SeriesInfo
import com.iptvapp.data.local.ServerCredentials
import org.json.JSONArray
import org.json.JSONObject

/** Bounded local index of episodes already fetched by the normal detail/player path. No URLs. */
object LauncherEpisodeCache {
    data class Ref(val season: Int, val episode: Int, val title: String)
    private fun key(context: Context, credentials: ServerCredentials, id: Int) =
        LauncherLink.token(LauncherLinkKey.get(context), LauncherLink.movieIdentity(credentials.serverUrl, credentials.username, id))

    @Synchronized
    fun save(context: Context, credentials: ServerCredentials, id: Int, info: SeriesInfo) {
        val prefs = context.getSharedPreferences("launcher_episode_index", Context.MODE_PRIVATE)
        val rows = info.episodes?.values?.flatten().orEmpty()
        if (rows.size > 2000) return // Do not persist an unbounded provider response.
        val array = JSONArray()
        rows.filter { it.id.isNotBlank() && it.season in 0..9999 && it.episodeNum in 0..99999 }
            .distinctBy { it.season to it.episodeNum }.forEach {
                array.put(JSONObject().put("s", it.season).put("e", it.episodeNum).put("t", it.title.take(256)))
            }
        val edit = prefs.edit()
        prefs.all.entries.sortedBy { runCatching { JSONObject(it.value as String).getLong("at") }.getOrDefault(0) }
            .take((prefs.all.size - 19).coerceAtLeast(0)).forEach { edit.remove(it.key) }
        edit.putString(key(context, credentials, id), JSONObject().put("at", System.currentTimeMillis()).put("episodes", array).toString()).apply()
    }

    fun read(context: Context, credentials: ServerCredentials, id: Int): List<Ref> = runCatching {
        val raw = context.getSharedPreferences("launcher_episode_index", Context.MODE_PRIVATE).getString(key(context, credentials, id), null) ?: return emptyList()
        val json = JSONObject(raw)
        if (System.currentTimeMillis() - json.getLong("at") > 7 * 86400000L) return emptyList()
        val array = json.getJSONArray("episodes")
        (0 until array.length()).map { array.getJSONObject(it).let { row -> Ref(row.getInt("s"), row.getInt("e"), row.getString("t")) } }
    }.getOrDefault(emptyList())

    fun remember(context: Context, credentials: ServerCredentials, id: Int, season: Int, episode: Int) {
        val prefs = context.getSharedPreferences("launcher_episode_progress", 0)
        val edit = prefs.edit()
        prefs.all.entries.sortedBy { (it.value as? String)?.substringBefore(':')?.toLongOrNull() ?: 0 }
            .take((prefs.all.size - 19).coerceAtLeast(0)).forEach { edit.remove(it.key) }
        edit.putString(key(context, credentials, id), "${System.currentTimeMillis()}:$season:$episode").apply()
    }
    fun last(context: Context, credentials: ServerCredentials, id: Int): Pair<Int, Int>? {
        val parts = context.getSharedPreferences("launcher_episode_progress", 0).getString(key(context, credentials, id), null)?.split(':') ?: return null
        return if (parts.size == 3) (parts[1].toIntOrNull() ?: return null) to (parts[2].toIntOrNull() ?: return null) else null
    }

    fun next(rows: List<Ref>, season: Int, episode: Int): Ref? = rows
        .filter { it.season > season || it.season == season && it.episode > episode }
        .minWithOrNull(compareBy<Ref> { it.season }.thenBy { it.episode })
}
