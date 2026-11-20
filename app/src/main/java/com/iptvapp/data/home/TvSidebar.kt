package com.iptvapp.data.home

import org.json.JSONArray
import org.json.JSONObject

/**
 * The TV sidebar's order and which of its entries show (Customize Sidebar). Settings stays pinned
 * under the list and is not part of it. Saved as a JSON array of {"id","visible"} in
 * PreferencesManager; this is a screen preference only.
 *
 * Home's own Shown/Hidden is HomeLayout.showHomeTab (Customize Home's HOME TAB), not the flag
 * saved here, so the two switches can never disagree: [withHome] lays it over a loaded list.
 */
object TvSidebar {

    data class SidebarItem(val id: String, val visible: Boolean = true)

    /** Sidebar entries, in MKTV's own order. */
    enum class Entry(val id: String, val label: String) {
        HOME("home", "Home"),
        FAVORITES("favorites", "Favorites"),
        PROVIDERS("providers", "Providers"),
        LIVE("live", "Live"),
        CATEGORIES("categories", "Categories"),
        MOVIES("movies", "Movies"),
        SERIES("series", "Series"),
        GUIDE("guide", "Guide"),
        SPORTS("sports", "Sports"),
        SEARCH("search", "Search"),
        VOICE("voice", "Voice"),
        RECORDINGS("recordings", "Recordings");

        companion object {
            fun of(id: String?): Entry? = entries.firstOrNull { it.id == id }
        }
    }

    /** At least one of these always stays shown, so the sidebar never has nowhere to start. */
    val PRIMARY = setOf(Entry.HOME.id, Entry.FAVORITES.id, Entry.GUIDE.id, Entry.LIVE.id)

    fun default(): List<SidebarItem> = Entry.entries.map { SidebarItem(it.id) }

    /**
     * A saved list, cleaned up: unknown or repeated ids dropped, and any entry it doesn't name
     * (one added in a later version) put back where MKTV's order has it, shown.
     */
    fun parse(json: String?): List<SidebarItem> {
        if (json.isNullOrBlank()) return default()
        val saved = try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (Entry.of(id) == null) null else SidebarItem(id, o.optBoolean("visible", true))
            }.distinctBy { it.id }
        } catch (_: Exception) {
            return default()
        }
        return normalize(saved)
    }

    fun toJson(items: List<SidebarItem>): String {
        val arr = JSONArray()
        for (it in normalize(items)) arr.put(JSONObject().put("id", it.id).put("visible", it.visible))
        return arr.toString()
    }

    /** Every entry exactly once; missing ones go back after the entry that comes before them in MKTV's order. */
    fun normalize(items: List<SidebarItem>): List<SidebarItem> {
        val out = items.filter { Entry.of(it.id) != null }.distinctBy { it.id }.toMutableList()
        val all = Entry.entries
        for ((i, e) in all.withIndex()) {
            if (out.any { it.id == e.id }) continue
            val before = all.subList(0, i).map { it.id }
            val at = out.indexOfLast { it.id in before } + 1
            out.add(at, SidebarItem(e.id))
        }
        return out
    }

    /** [items] with Home's Shown/Hidden taken from Customize Home's HOME TAB. */
    fun withHome(items: List<SidebarItem>, homeShown: Boolean): List<SidebarItem> =
        items.map { if (it.id == Entry.HOME.id) it.copy(visible = homeShown) else it }

    fun isShown(items: List<SidebarItem>, id: String) = items.firstOrNull { it.id == id }?.visible ?: true

    /** False when hiding [id] would leave none of Home, Favorites, Guide and Live showing. */
    fun canHide(items: List<SidebarItem>, id: String): Boolean =
        id !in PRIMARY || items.any { it.id != id && it.id in PRIMARY && it.visible }

    /** Shows or hides [id]; the list is unchanged when that would hide the last of [PRIMARY]. */
    fun setVisible(items: List<SidebarItem>, id: String, visible: Boolean): List<SidebarItem> {
        if (!visible && !canHide(items, id)) return items
        return items.map { if (it.id == id) it.copy(visible = visible) else it }
    }

    fun move(items: List<SidebarItem>, id: String, delta: Int): List<SidebarItem> {
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) return items
        return moveTo(items, id, from + delta)
    }

    fun moveTo(items: List<SidebarItem>, id: String, to: Int): List<SidebarItem> {
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) return items
        val target = to.coerceIn(0, items.lastIndex)
        if (target == from) return items
        val out = items.toMutableList()
        out.add(target, out.removeAt(from))
        return out
    }
}
