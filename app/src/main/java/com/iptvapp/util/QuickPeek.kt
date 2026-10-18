package com.iptvapp.util

/**
 * Quick Peek: the mini guide over live TV in the player. What it lists, what is on, and which
 * row a pick tunes. Nothing here touches the player, the database, or the network.
 *
 * The channel that is playing and the row the viewer has highlighted are kept apart: moving the
 * highlight never tunes anything, and only [Selection.select] hands a channel to the player.
 */
object QuickPeek {

    /** A channel on one provider. serverIndex -1 is the main provider. Stream ids repeat across providers. */
    data class Key(val serverIndex: Int, val streamId: Int)

    data class Row(
        val key: Key,
        val number: Int,
        val name: String,
        val logo: String?,
        val favorite: Boolean,
        val categoryId: String?,
    )

    enum class Kind { FAVORITES, CATEGORY, ALL }

    /** One list to browse. [id] is the category id for [Kind.CATEGORY]. */
    data class Source(val kind: Kind, val id: String?, val label: String)

    data class Airing(val title: String, val startMs: Long, val stopMs: Long, val description: String)

    data class NowNext(val now: Airing?, val next: Airing?) {
        val empty get() = now == null && next == null
    }

    /** One number per (provider, stream) pair, for RecyclerView ids: never equal for two providers. */
    fun stableId(key: Key): Long = (key.serverIndex.toLong() shl 32) or (key.streamId.toLong() and 0xFFFFFFFFL)

    /** Providers store guide times in seconds or milliseconds; under 100000000000 is seconds. */
    fun ms(ts: Long): Long = if (ts < 100_000_000_000L) ts * 1000L else ts

    /** The show on at [nowMs] and the one after it, from one channel's programmes in any order. */
    fun nowNext(programs: List<Airing>, nowMs: Long): NowNext {
        val sorted = programs.filter { it.stopMs > it.startMs }.sortedBy { it.startMs }
        val now = sorted.lastOrNull { it.startMs <= nowMs && nowMs < it.stopMs }
        val after = now?.stopMs ?: nowMs
        val next = sorted.firstOrNull { it.startMs >= after && it !== now }
        return NowNext(now, next)
    }

    /** How far through the show [nowMs] is, 0 to 1000. */
    fun progress(startMs: Long, stopMs: Long, nowMs: Long): Int {
        if (stopMs <= startMs) return 0
        return (((nowMs - startMs).toDouble() / (stopMs - startMs)) * 1000).toInt().coerceIn(0, 1000)
    }

    /** Whole minutes left, rounded up, so a show with 20 seconds to go still reads "1 min left". */
    fun minutesLeft(stopMs: Long, nowMs: Long): Int {
        val left = stopMs - nowMs
        if (left <= 0) return 0
        return ((left + 59_999L) / 60_000L).toInt()
    }

    fun remainingLabel(minutes: Int): String = when {
        minutes <= 0 -> "Ending"
        minutes < 60 -> "$minutes min left"
        minutes % 60 == 0 -> "${minutes / 60} h left"
        else -> "${minutes / 60} h ${minutes % 60} min left"
    }

    /**
     * The lists, in browsing order: Favorites, the playing channel's own category, All channels,
     * then the provider's other categories in its own order.
     */
    fun sources(categories: List<Pair<String, String>>, currentCategoryId: String?): List<Source> {
        val out = ArrayList<Source>(categories.size + 3)
        out += Source(Kind.FAVORITES, null, "Favorites")
        val current = categories.firstOrNull { it.first == currentCategoryId }
        if (current != null) out += Source(Kind.CATEGORY, current.first, current.second)
        out += Source(Kind.ALL, null, "All channels")
        for ((id, name) in categories) if (id != current?.first) out += Source(Kind.CATEGORY, id, name)
        return out
    }

    /** The list to open on: the playing channel's category when there is one, else Favorites. */
    fun startSource(sources: List<Source>, currentCategoryId: String?): Int =
        sources.indexOfFirst { it.kind == Kind.CATEGORY && it.id == currentCategoryId }.takeIf { it >= 0 } ?: 0

    /** Left and Right walk the lists and wrap at either end. */
    fun cycle(index: Int, delta: Int, size: Int): Int {
        if (size <= 0) return 0
        return ((index + delta) % size + size) % size
    }

    /** Where focus lands in a new list: the same channel if it is there, else the playing one, else the top. */
    fun focusIndex(rows: List<Row>, highlighted: Key?, playing: Key?): Int {
        if (rows.isEmpty()) return -1
        highlighted?.let { k -> rows.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { return it } }
        playing?.let { k -> rows.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { return it } }
        return 0
    }

    /** Up and Down move one row and stop at the ends; Page keys move [page] rows. */
    fun step(index: Int, delta: Int, size: Int): Int {
        if (size <= 0) return -1
        return (index + delta).coerceIn(0, size - 1)
    }

    /**
     * The rows around what is on screen whose guide is not loaded yet, so a scroll reads only
     * those from the local guide. [pad] rows either side are read ahead.
     */
    fun toLoad(rows: List<Row>, first: Int, last: Int, pad: Int, loaded: Set<Key>): List<Key> {
        if (rows.isEmpty() || first < 0 || last < first) return emptyList()
        val from = (first - pad).coerceAtLeast(0)
        val to = (last + pad).coerceAtMost(rows.lastIndex)
        val out = ArrayList<Key>(to - from + 1)
        for (i in from..to) {
            val k = rows[i].key
            if (k !in loaded) out += k
        }
        return out
    }

    /** True when a loaded guide entry needs reading again: its show has ended, or the next one started. */
    fun stale(entry: NowNext, nowMs: Long): Boolean {
        val now = entry.now
        if (now != null) return nowMs >= now.stopMs
        val next = entry.next ?: return false
        return nowMs >= next.startMs
    }

    /** What TalkBack reads for a row. Says playing, favorite, and the time left in words. */
    fun describe(row: Row, nowNext: NowNext?, playing: Boolean, nowMs: Long): String {
        val parts = ArrayList<String>(6)
        parts += if (row.number > 0) "Channel ${row.number}, ${row.name}" else row.name
        if (playing) parts += "playing now"
        if (row.favorite) parts += "favorite"
        val now = nowNext?.now
        if (now != null) {
            parts += "On now: ${now.title}"
            parts += remainingLabel(minutesLeft(now.stopMs, nowMs))
        }
        nowNext?.next?.let { parts += "Next: ${it.title}" }
        if (nowNext == null || nowNext.empty) parts += "No guide information"
        return parts.joinToString(", ")
    }

    /**
     * Which channel is playing, which row is highlighted, and the one pick on its way to the
     * player. A pick of the playing channel just closes; a second pick of the same channel while
     * the first is still tuning does nothing.
     */
    class Selection {
        var playing: Key? = null
            private set
        var highlighted: Key? = null
            private set
        var pending: Key? = null
            private set
        /** Bumped when the provider changes, so a list loaded for the old one is thrown away. */
        var generation: Int = 0
            private set

        sealed class Decision {
            object Close : Decision()
            object Ignore : Decision()
            data class Tune(val key: Key) : Decision()
        }

        fun highlight(key: Key?) { highlighted = key }

        fun select(key: Key): Decision {
            if (key == pending) return Decision.Ignore
            if (key == playing && pending == null) return Decision.Close
            pending = key
            return Decision.Tune(key)
        }

        /**
         * The player is on [key] now (a pick, CH +/-, LAST, or anything else that changed it).
         * Any change settles the pick: the player may have tuned a more reliable copy instead.
         */
        fun playingNow(key: Key?) {
            pending = null
            if (key?.serverIndex != playing?.serverIndex) {
                generation++
                highlighted = null
            }
            playing = key
        }

        /** The pick did not reach the player (cancelled or failed). */
        fun clearPending() { pending = null }
    }
}
