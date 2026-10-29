package com.iptvapp.data.home

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.WatchLogEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Top Favorites: a short list of live channels someone picks by hand, shown as a Home row
 * (HomeLayout.Builtin.TOP_FAVORITES). It is its own list, apart from the regular favorites, so
 * adding, removing or reordering here never changes a channel's favorite star, folder or order.
 * TV and phone share it. Channels are kept by provider and stream id (HomeLayout.ItemRef), so the
 * same id on two providers stays two channels and nothing here is a URL or a credential.
 *
 * [Sort.MOST_WATCHED] only reorders what was picked, by real viewing time (watch_log); it never
 * adds a channel or changes the saved order, so switching back gives the hand-made order again.
 *
 * Every change is a pure function returning a new [State]; [parse] never throws.
 */
object TopFavorites {

    const val MAX = 50

    /** Less than this on one channel in a day is zapping past, not watching. */
    const val MEANINGFUL_MINUTES = 2f

    /** How far back Most Watched looks. */
    const val WINDOW_DAYS = 30

    enum class Sort(val id: String, val label: String) {
        MANUAL("manual", "Manual order"),
        MOST_WATCHED("most_watched", "Most watched");

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: MANUAL
        }
    }

    data class State(val items: List<HomeLayout.ItemRef> = emptyList(), val sort: Sort = Sort.MANUAL) {
        operator fun contains(ref: HomeLayout.ItemRef) = ref in items
    }

    fun ref(serverIndex: Int, streamId: Int) = HomeLayout.ItemRef(HomeLayout.Content.CHANNELS, serverIndex, streamId)

    // ── Changes ─────────────────────────────────────────────────────────

    /** Adds [ref] at the end, once. A full list is left as it is. */
    fun add(state: State, ref: HomeLayout.ItemRef): State =
        if (ref.content != HomeLayout.Content.CHANNELS || ref.serverIndex < -1 || ref in state || state.items.size >= MAX) state
        else state.copy(items = state.items + ref)

    fun remove(state: State, ref: HomeLayout.ItemRef) = state.copy(items = state.items - ref)

    /** Moves [ref] [delta] places, stopping at the ends. */
    fun move(state: State, ref: HomeLayout.ItemRef, delta: Int): State {
        val from = state.items.indexOf(ref)
        return if (from < 0) state else moveTo(state, ref, from + delta)
    }

    /** Puts [ref] at [to] (a drag). */
    fun moveTo(state: State, ref: HomeLayout.ItemRef, to: Int): State {
        val list = state.items.toMutableList()
        val from = list.indexOf(ref)
        if (from < 0) return state
        list.removeAt(from)
        list.add(to.coerceIn(0, list.size), ref)
        return if (list == state.items) state else state.copy(items = list)
    }

    /**
     * Moves [ref] just before (or [after]) [neighbor]: Move left/right on Home, where the card
     * beside it may not be its neighbor in the saved list (one between is hidden or gone).
     */
    fun moveNextTo(state: State, ref: HomeLayout.ItemRef, neighbor: HomeLayout.ItemRef, after: Boolean): State {
        if (ref == neighbor || ref !in state || neighbor !in state) return state
        val list = state.items.toMutableList()
        list.remove(ref)
        list.add(list.indexOf(neighbor) + if (after) 1 else 0, ref)
        return state.copy(items = list)
    }

    fun setSort(state: State, sort: Sort) = state.copy(sort = sort)

    /**
     * After the provider list changes, as HomeLayout.reindexProviders: channels follow their
     * provider to its new index, or are dropped with it, so an id never lands on another
     * provider's channel.
     */
    fun reindexProviders(state: State, moved: Map<Int, Int>?, primaryChanged: Boolean): State =
        state.copy(items = state.items.mapNotNull { i ->
            when {
                i.serverIndex == -1 -> if (primaryChanged) null else i
                moved == null -> i
                else -> moved[i.serverIndex]?.let { i.copy(serverIndex = it) }
            }
        }.distinct())

    /** [reindexProviders] on a stored list. Null (never used) stays null. */
    fun reindexJson(json: String?, moved: Map<Int, Int>?, primaryChanged: Boolean): String? {
        if (json == null) return null
        val before = parse(json)
        val after = reindexProviders(before, moved, primaryChanged)
        return if (after == before) json else toJson(after)
    }

    // ── Most watched ────────────────────────────────────────────────────

    /**
     * Minutes per channel (provider, stream id) from [log], counting only days with at least
     * [MEANINGFUL_MINUTES] on that channel, so a channel flicked through many times a day does
     * not outrank one that is actually watched.
     */
    fun watchedMinutes(log: List<WatchLogEntity>): Map<Pair<Int, Int>, Float> {
        val out = HashMap<Pair<Int, Int>, Float>()
        for (row in log) {
            if (row.minutes < MEANINGFUL_MINUTES) continue
            val key = row.serverIndex to row.streamId
            out[key] = (out[key] ?: 0f) + row.minutes
        }
        return out
    }

    /**
     * [items] most watched first. A channel in a Smart Channel group ([groupOf]) counts the time
     * on every copy in its group, since a backup copy is the same channel. Channels with no
     * meaningful viewing, and ties, keep their manual order after the watched ones.
     */
    fun rank(
        items: List<HomeLayout.ItemRef>,
        minutes: Map<Pair<Int, Int>, Float>,
        groupOf: Map<Pair<Int, Int>, String> = emptyMap(),
    ): List<HomeLayout.ItemRef> {
        val byGroup = HashMap<String, Float>()
        for ((key, m) in minutes) groupOf[key]?.let { g -> byGroup[g] = (byGroup[g] ?: 0f) + m }
        fun score(i: HomeLayout.ItemRef): Float {
            val key = i.serverIndex to i.id
            return groupOf[key]?.let { byGroup[it] } ?: minutes[key] ?: 0f
        }
        return items.withIndex()
            .sortedWith(compareByDescending<IndexedValue<HomeLayout.ItemRef>> { score(it.value) }.thenBy { it.index })
            .map { it.value }
    }

    // ── Storage ─────────────────────────────────────────────────────────

    fun toJson(state: State): String {
        val root = JsonObject()
        root.addProperty("schema", 1)
        root.addProperty("sort", state.sort.id)
        val arr = JsonArray()
        for (i in state.items) arr.add(i.key)
        root.add("items", arr)
        return root.toString()
    }

    /** A stored list, recovered as far as it goes: unreadable entries are dropped. Never throws. */
    fun parse(json: String?): State {
        if (json.isNullOrBlank()) return State()
        val root = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return State() }
        val sort = Sort.of(try { root.get("sort")?.asString } catch (_: Exception) { null })
        val items = (root.get("items") as? JsonArray)?.mapNotNull { el ->
            val parts = (try { el.asString } catch (_: Exception) { null })?.split(':') ?: return@mapNotNull null
            if (parts.size != 3 || parts[0] != HomeLayout.Content.CHANNELS.name) return@mapNotNull null
            val server = parts[1].toIntOrNull()?.takeIf { it >= -1 } ?: return@mapNotNull null
            val id = parts[2].toIntOrNull() ?: return@mapNotNull null
            ref(server, id)
        }?.distinct()?.take(MAX).orEmpty()
        return State(items, sort)
    }
}

/**
 * Reads and changes the saved Top Favorites under one lock, as HomeLayoutStore does for the
 * layout, so the Home menu and the editor never write over each other. [snapshot] is the last
 * list read or saved, for menus that must label "Add" or "Remove" without waiting.
 */
object TopFavoritesStore {
    private val lock = Mutex()

    @Volatile var snapshot: TopFavorites.State = TopFavorites.State()
        private set

    suspend fun load(prefs: PreferencesManager): TopFavorites.State =
        TopFavorites.parse(prefs.topFavoritesJson.first()).also { snapshot = it }

    /** Applies [change] to the saved list and returns what was saved. */
    suspend fun update(prefs: PreferencesManager, change: (TopFavorites.State) -> TopFavorites.State): TopFavorites.State =
        withContext(NonCancellable) {
            lock.withLock {
                val before = load(prefs)
                val after = change(before)
                if (after != before) prefs.setTopFavoritesJson(TopFavorites.toJson(after))
                snapshot = after
                after
            }
        }
}
