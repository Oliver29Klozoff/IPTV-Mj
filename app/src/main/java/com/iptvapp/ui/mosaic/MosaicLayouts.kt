package com.iptvapp.ui.mosaic

import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved Mosaic layouts ("NFL Sunday", "News"): a grid size and the channel in each tile.
 * A tile is a provider and that provider's own stream id, the same pair feed scores use, so a
 * layout can mix providers. Null is an empty tile. Stored as one JSON string in preferences.
 */
data class MosaicLayout(val name: String, val gridSize: Int, val tiles: List<Tile?>, val selectedTile: Int = 0, val muted: Boolean = false) {
    /** [channelName] is checked on load: a provider whose login changed reuses ids for other channels. */
    data class Tile(val serverIndex: Int, val streamId: Int, val channelName: String)
}

object MosaicLayouts {
    val GRID_SIZES = listOf(1, 2, 4, 6)
    const val MAX_NAME = 40

    fun decode(json: String?): List<MosaicLayout> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val size = o.optInt("grid", 4).takeIf { it in GRID_SIZES } ?: 4
                val tilesJson = o.optJSONArray("tiles") ?: JSONArray()
                val tiles = (0 until size).map { t ->
                    val tile = tilesJson.optJSONObject(t) ?: return@map null
                    if (!tile.has("server") || !tile.has("stream")) null
                    else MosaicLayout.Tile(tile.getInt("server"), tile.getInt("stream"), tile.optString("channel"))
                }
                MosaicLayout(name, size, tiles, o.optInt("selected", 0).coerceIn(0, size - 1), o.optBoolean("muted", false))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(layouts: List<MosaicLayout>): String = JSONArray().apply {
        layouts.forEach { l ->
            put(JSONObject().apply {
                put("name", l.name)
                put("grid", l.gridSize)
                put("selected", l.selectedTile)
                put("muted", l.muted)
                put("tiles", JSONArray().apply {
                    l.tiles.forEach { t ->
                        put(if (t == null) JSONObject.NULL else JSONObject().put("server", t.serverIndex).put("stream", t.streamId).put("channel", t.channelName))
                    }
                })
            })
        }
    }.toString()

    /** Saving under a name that exists (any case) replaces it, in its old place in the list. */
    fun upsert(layouts: List<MosaicLayout>, layout: MosaicLayout): List<MosaicLayout> {
        val clean = layout.copy(name = layout.name.trim().take(MAX_NAME))
        val at = layouts.indexOfFirst { it.name.equals(clean.name, ignoreCase = true) }
        return if (at < 0) layouts + clean else layouts.toMutableList().also { it[at] = clean }
    }

    fun remove(layouts: List<MosaicLayout>, name: String): List<MosaicLayout> =
        layouts.filterNot { it.name.equals(name, ignoreCase = true) }

    /**
     * When a provider is removed, its tiles empty and later providers move down one,
     * matching how merged_channels and feed_stats are renumbered.
     */
    fun afterProviderRemoved(layouts: List<MosaicLayout>, removedIndex: Int): List<MosaicLayout> =
        layouts.map { l ->
            l.copy(tiles = l.tiles.map { t ->
                when {
                    t == null || t.serverIndex < removedIndex -> t
                    t.serverIndex == removedIndex -> null
                    else -> t.copy(serverIndex = t.serverIndex - 1)
                }
            })
        }
}
