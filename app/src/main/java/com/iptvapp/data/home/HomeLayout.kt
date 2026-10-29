package com.iptvapp.data.home

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * How someone has arranged their Home: which rows show, in what order, how they look, and the
 * rows they made themselves. TV and phone keep one each (see PreferencesManager.homeLayoutJson).
 *
 * Only arrangement lives here. A row's content still comes from the catalog when Home is built
 * (SmartHome.rows), so nothing here can change a favorite, a watch position, or a recording, and
 * deleting a row deletes only the row. Items are kept by provider and id (never by name, list
 * position, or URL), so the same stream id on two providers stays two items and no credentials
 * are stored.
 *
 * Every change is a pure function returning a new [Layout]; [parse] never throws.
 */
object HomeLayout {

    const val SCHEMA = 1
    const val MAX_SECTIONS = 40
    const val MAX_ITEMS = 100
    const val MAX_TITLE = 40
    const val CUSTOM_PREFIX = "custom:"

    enum class Content { CHANNELS, MOVIES, SERIES, MIXED, PROGRAMS, RECORDINGS }

    /** Card looks. Each row offers only the ones that suit its content ([styles]). */
    enum class Style(val label: String) {
        WIDE("Landscape cards"),
        POSTER("Posters"),
        COMPACT("Small tiles")
    }

    /**
     * Rows MKTV builds itself. Top Favorites leads (it stays out of sight until a channel is
     * added). Then come the Smart Home rows as they were before customizing, in that order and
     * shown; the rest are new and start hidden, so an upgrade leaves Home looking the same.
     */
    enum class Builtin(
        val id: String,
        val title: String,
        val content: Content,
        val style: Style,
        val shownByDefault: Boolean,
        val emptyText: String,
    ) {
        TOP_FAVORITES("top_favorites", "Top Favorites", Content.CHANNELS, Style.WIDE, true,
            "Hold OK on a channel (long-press on a phone) and choose Add to Top Favorites."),
        CONTINUE("continue", "Continue Watching", Content.MIXED, Style.POSTER, true,
            "Movies and shows you start will appear here."),
        ON_NOW("on_now", "On Now", Content.PROGRAMS, Style.WIDE, true,
            "What's on your favorite and recent channels shows here."),
        FAVORITES("favorites", "Favorite Channels", Content.CHANNELS, Style.WIDE, true,
            "Add channels to Favorites to see them here."),
        RECENT("recent", "Recently Watched", Content.CHANNELS, Style.WIDE, true,
            "Channels you watch will appear here."),
        STARTING_SOON("starting_soon", "Starting Soon", Content.PROGRAMS, Style.WIDE, true,
            "Shows starting in the next two hours on your channels show here."),
        NEW_EPISODES("new_episodes", "New Episodes", Content.SERIES, Style.POSTER, true,
            "New episodes of the series you watch will appear here."),
        RECORDINGS("recordings", "Recordings", Content.RECORDINGS, Style.WIDE, true,
            "Your recordings will appear here."),
        RECOMMENDED("recommended", "Recommended for You", Content.MIXED, Style.POSTER, true,
            "Picks from the categories you watch will appear here."),
        RECENT_MOVIES("recent_movies", "Recently Added Movies", Content.MOVIES, Style.POSTER, false,
            "Movies your provider adds will appear here."),
        FAVORITE_MOVIES("favorite_movies", "Favorite Movies", Content.MOVIES, Style.POSTER, false,
            "Add movies to Favorites to see them here."),
        FAVORITE_SERIES("favorite_series", "Favorite Series", Content.SERIES, Style.POSTER, false,
            "Add series to Favorites to see them here.");

        companion object {
            fun of(id: String): Builtin? = entries.firstOrNull { it.id == id }
        }
    }

    /** One item in a row someone made: a channel, movie, or series on one provider (-1 = the main one). */
    data class ItemRef(val content: Content, val serverIndex: Int, val id: Int) {
        val key get() = "${content.name}:$serverIndex:$id"
    }

    /** A row that fills itself: everything in one category on one provider. Movies and series are main-provider only. */
    data class Filter(val serverIndex: Int, val categoryId: String?, val categoryName: String) {
        /** Its provider was removed, or another account took its place. */
        val gone get() = serverIndex == PROVIDER_GONE
    }

    /** A category row whose provider is no longer there. */
    const val PROVIDER_GONE = -99

    data class Section(
        val id: String,
        val visible: Boolean,
        val style: Style,
        /** Built-in rows: still shown (with a hint) when empty. Custom rows default to yes. */
        val showWhenEmpty: Boolean,
        /** Custom rows only. */
        val title: String? = null,
        val content: Content? = null,
        val items: List<ItemRef> = emptyList(),
        val filter: Filter? = null,
    ) {
        val builtin: Builtin? get() = Builtin.of(id)
        val isCustom get() = id.startsWith(CUSTOM_PREFIX)
        val isDynamic get() = isCustom && filter != null
        val displayTitle get() = builtin?.title ?: title.orEmpty().ifBlank { "My Collection" }
        val contentType get() = builtin?.content ?: content ?: Content.CHANNELS
        val emptyText get() = builtin?.emptyText ?: if (filter?.gone == true) "This row's provider was removed. Edit it to pick another category." else if (isDynamic) "Nothing in this category right now." else "Add content to this collection."
    }

    /** What the home screen opens on. Each form factor offers its own list. */
    enum class StartScreen(val id: String, val label: String) {
        HOME("home", "Home"),
        FAVORITES("favorites", "Favorites"),
        LIVE("live", "Live TV"),
        MOVIES("movies", "Movies"),
        SERIES("series", "Series"),
        GUIDE("guide", "Guide");

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: HOME
        }
    }

    data class Layout(val sections: List<Section>, val start: StartScreen = StartScreen.HOME) {
        fun section(id: String) = sections.firstOrNull { it.id == id }
    }

    /** The styles a row can use. A channel or a programme reads as a landscape card; posters suit films. */
    fun styles(content: Content): List<Style> = when (content) {
        Content.CHANNELS, Content.PROGRAMS, Content.RECORDINGS -> listOf(Style.WIDE, Style.COMPACT)
        Content.MOVIES, Content.SERIES, Content.MIXED -> listOf(Style.POSTER, Style.WIDE)
    }

    fun defaultSection(b: Builtin) = Section(b.id, b.shownByDefault, b.style, showWhenEmpty = false)

    /** Home as MKTV shipped it: the Smart Home rows in their usual order. */
    fun default(): Layout = Layout(Builtin.entries.map { defaultSection(it) })

    // ── Changes ─────────────────────────────────────────────────────────

    /** Moves a row [delta] places, stopping at the ends. */
    fun move(layout: Layout, id: String, delta: Int): Layout {
        val from = layout.sections.indexOfFirst { it.id == id }
        if (from < 0) return layout
        val to = (from + delta).coerceIn(0, layout.sections.lastIndex)
        return moveTo(layout, id, to)
    }

    /** Puts a row at [to] (a drag). */
    fun moveTo(layout: Layout, id: String, to: Int): Layout {
        val list = layout.sections.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0 || from == to) return layout
        val s = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), s)
        return layout.copy(sections = list)
    }

    fun setVisible(layout: Layout, id: String, visible: Boolean) = update(layout, id) { it.copy(visible = visible) }

    fun setStyle(layout: Layout, id: String, style: Style) = update(layout, id) {
        if (style in styles(it.contentType)) it.copy(style = style) else it
    }

    fun setShowWhenEmpty(layout: Layout, id: String, show: Boolean) = update(layout, id) { it.copy(showWhenEmpty = show) }

    fun rename(layout: Layout, id: String, title: String) = update(layout, id) {
        if (!it.isCustom) it else it.copy(title = cleanTitle(title).ifBlank { it.title })
    }

    fun setStart(layout: Layout, start: StartScreen) = layout.copy(start = start)

    /** A new row of hand-picked items, or (with [filter]) one category, placed at the top. */
    fun addCustom(layout: Layout, newId: String, title: String, content: Content, filter: Filter? = null): Layout {
        if (layout.sections.size >= MAX_SECTIONS) return layout
        val type = if (content == Content.CHANNELS || content == Content.MOVIES || content == Content.SERIES) content else Content.CHANNELS
        val s = Section(
            id = CUSTOM_PREFIX + newId,
            visible = true,
            style = styles(type).first(),
            showWhenEmpty = true,
            title = cleanTitle(title).ifBlank { "My Collection" },
            content = type,
            filter = filter,
        )
        return layout.copy(sections = listOf(s) + layout.sections)
    }

    /** Removes a row someone made. Built-in rows can only be hidden. The items themselves are untouched. */
    fun delete(layout: Layout, id: String): Layout =
        if (!id.startsWith(CUSTOM_PREFIX)) layout else layout.copy(sections = layout.sections.filterNot { it.id == id })

    fun setFilter(layout: Layout, id: String, filter: Filter) = update(layout, id) {
        if (it.isCustom) it.copy(filter = filter, items = emptyList()) else it
    }

    /** Adds an item to the end of a hand-picked row (once; the type must match the row). */
    fun addItem(layout: Layout, id: String, item: ItemRef) = update(layout, id) { s ->
        if (!s.isCustom || s.isDynamic || item.content != s.contentType || s.items.any { it == item } || s.items.size >= MAX_ITEMS) s
        else s.copy(items = s.items + item)
    }

    fun removeItem(layout: Layout, id: String, item: ItemRef) = update(layout, id) { s -> s.copy(items = s.items - item) }

    /** Moves an item within its row only; no catalog order changes. */
    fun moveItem(layout: Layout, id: String, item: ItemRef, to: Int) = update(layout, id) { s ->
        val list = s.items.toMutableList()
        val from = list.indexOf(item)
        if (from < 0) s else {
            list.removeAt(from)
            list.add(to.coerceIn(0, list.size), item)
            s.copy(items = list)
        }
    }

    /** Back to [default]. Rows someone made are dropped from Home; nothing they listed is touched. */
    fun reset(layout: Layout): Layout = default().copy(start = layout.start)

    /**
     * After the provider list changes: [moved] maps each extra provider's old index to its new one
     * (one that isn't there was removed or is now a different account), or null when they didn't change, and [primaryChanged] says
     * the main login is a different account. Items follow their provider or are dropped, so an id
     * never lands on another provider's channel; a category row whose provider went stays, empty.
     */
    fun reindexProviders(layout: Layout, moved: Map<Int, Int>?, primaryChanged: Boolean): Layout {
        fun target(server: Int): Int? = when {
            server == -1 -> if (primaryChanged) null else -1
            server == PROVIDER_GONE -> PROVIDER_GONE
            else -> if (moved == null) server else moved[server]
        }
        return layout.copy(sections = layout.sections.map { s ->
            if (!s.isCustom) return@map s
            s.copy(
                items = s.items.mapNotNull { i -> target(i.serverIndex)?.let { i.copy(serverIndex = it) } },
                filter = s.filter?.let { f -> f.copy(serverIndex = target(f.serverIndex) ?: PROVIDER_GONE) },
            )
        })
    }

    /** [reindexProviders] on a stored layout. Null (never customized) stays null. */
    fun reindexJson(json: String?, moved: Map<Int, Int>?, primaryChanged: Boolean): String? {
        if (json == null) return null
        val before = parse(json)
        val after = reindexProviders(before, moved, primaryChanged)
        return if (after == before) json else toJson(after)
    }

    private fun update(layout: Layout, id: String, change: (Section) -> Section) =
        layout.copy(sections = layout.sections.map { if (it.id == id) change(it) else it })

    private fun cleanTitle(t: String) = t.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE)

    // ── Storage ─────────────────────────────────────────────────────────

    fun toJson(layout: Layout): String {
        val root = JsonObject()
        root.addProperty("schema", SCHEMA)
        root.addProperty("start", layout.start.id)
        val arr = JsonArray()
        for (s in layout.sections) {
            val o = JsonObject()
            o.addProperty("id", s.id)
            o.addProperty("visible", s.visible)
            o.addProperty("style", s.style.name)
            o.addProperty("showWhenEmpty", s.showWhenEmpty)
            if (s.isCustom) {
                o.addProperty("title", s.title)
                o.addProperty("content", s.contentType.name)
                s.filter?.let { f ->
                    val fo = JsonObject()
                    fo.addProperty("serverIndex", f.serverIndex)
                    if (f.categoryId != null) fo.addProperty("categoryId", f.categoryId)
                    fo.addProperty("categoryName", f.categoryName)
                    o.add("filter", fo)
                }
                val items = JsonArray()
                for (i in s.items) items.add(i.key)
                o.add("items", items)
            }
            arr.add(o)
        }
        root.add("sections", arr)
        return root.toString()
    }

    /**
     * A stored layout, recovered as far as it goes: unknown or broken rows are dropped, a row
     * MKTV has added since is appended in its default state, and anything unreadable gives
     * [default]. Never throws.
     */
    fun parse(json: String?): Layout {
        if (json.isNullOrBlank()) return default()
        val root = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return default() }
        val start = StartScreen.of(root.str("start"))
        val arr = (root.get("sections") as? JsonArray) ?: return default().copy(start = start)
        val out = ArrayList<Section>()
        val seen = HashSet<String>()
        for (el in arr) {
            val s = try { sectionOf(el) } catch (_: Exception) { null } ?: continue
            if (!seen.add(s.id)) continue
            if (out.size >= MAX_SECTIONS) break
            out += s
        }
        // Built-in rows the saved layout doesn't know (it predates them): after the others, as shipped.
        // Top Favorites goes first instead, where a new Home has it.
        for (b in Builtin.entries) if (b.id !in seen) {
            if (b == Builtin.TOP_FAVORITES) out.add(0, defaultSection(b)) else out += defaultSection(b)
        }
        return Layout(out, start)
    }

    private fun sectionOf(el: JsonElement): Section? {
        val o = el as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        val builtin = Builtin.of(id)
        val custom = id.startsWith(CUSTOM_PREFIX) && id.length > CUSTOM_PREFIX.length
        if (builtin == null && !custom) return null
        val visible = o.bool("visible") ?: builtin?.shownByDefault ?: true
        val showWhenEmpty = o.bool("showWhenEmpty") ?: custom
        if (builtin != null) {
            val style = styleOf(o.str("style"), builtin.content) ?: builtin.style
            return Section(id, visible, style, showWhenEmpty)
        }
        val content = o.str("content")?.let { c -> Content.entries.firstOrNull { it.name == c } }
            ?.takeIf { it == Content.CHANNELS || it == Content.MOVIES || it == Content.SERIES } ?: return null
        val filter = (o.get("filter") as? JsonObject)?.let { f ->
            val server = f.int("serverIndex") ?: return@let null
            if (server < -1 && server != PROVIDER_GONE) return@let null
            val name = f.str("categoryName") ?: return@let null
            // Movies and series rows follow the main provider's categories only.
            if (content != Content.CHANNELS && server != -1 && server != PROVIDER_GONE) return@let null
            Filter(server, f.str("categoryId"), name)
        }
        val items = (o.get("items") as? JsonArray)?.mapNotNull { itemOf(it, content) }?.distinct()?.take(MAX_ITEMS).orEmpty()
        return Section(
            id = id,
            visible = visible,
            style = styleOf(o.str("style"), content) ?: styles(content).first(),
            showWhenEmpty = showWhenEmpty,
            title = cleanTitle(o.str("title").orEmpty()).ifBlank { "My Collection" },
            content = content,
            items = if (filter != null) emptyList() else items,
            filter = filter,
        )
    }

    private fun itemOf(el: JsonElement, content: Content): ItemRef? {
        val parts = (try { el.asString } catch (_: Exception) { null })?.split(':') ?: return null
        if (parts.size != 3 || parts[0] != content.name) return null
        val server = parts[1].toIntOrNull() ?: return null
        val id = parts[2].toIntOrNull() ?: return null
        if (server < -1) return null
        // Movies and series are picked from the main provider.
        if (content != Content.CHANNELS && server != -1) return null
        return ItemRef(content, server, id)
    }

    private fun styleOf(name: String?, content: Content) =
        Style.entries.firstOrNull { it.name == name }?.takeIf { it in styles(content) }

    private fun JsonObject.str(k: String) = (get(k)?.takeIf { it.isJsonPrimitive })?.let { try { it.asString } catch (_: Exception) { null } }
    private fun JsonObject.bool(k: String) = (get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean })?.asBoolean
    private fun JsonObject.int(k: String) = (get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber })?.let {
        try { it.asInt } catch (_: Exception) { null }
    }
}
