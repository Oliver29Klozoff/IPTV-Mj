package com.iptvapp.util

/**
 * One row per channel in a category list. Panels often carry the same channel several times
 * ("ESPN", "ESPN HD", "ESPN FHD"): those share a name once ChannelNameMatcher has removed the
 * quality tag, and collapse into the copy that has played best. Its row says how many more
 * copies exist; long-press All Sources lists every one.
 *
 * A name that normalises to nothing (a non-Latin script ChannelNameMatcher drops) is never
 * grouped with anything, since there is no safe way to tell those channels apart.
 */
object DuplicateChannels {

    /** A collapsed row's name: "ESPN FHD  +2" when it stands for two more copies. */
    fun label(name: String, extraCopies: Int?): String =
        if (extraCopies == null || extraCopies <= 0) name else "$name  +$extraCopies"

    /** [item] is the copy to show and play; [extraCopies] is how many others it stands for. */
    data class Collapsed<T>(val item: T, val extraCopies: Int)

    /**
     * [items] in their display order. Each group sits where its first member was, so a sorted
     * list stays sorted. [scoreOf] is a copy's feed score, or null with no history; the highest
     * wins, ties and no history keep the first.
     */
    fun <T> collapse(items: List<T>, nameOf: (T) -> String, scoreOf: (T) -> Int?): List<Collapsed<T>> {
        val groups = LinkedHashMap<Any, MutableList<T>>()
        items.forEachIndexed { i, item ->
            val key: Any = ChannelNameMatcher.normalize(nameOf(item)).ifEmpty { "#$i" }
            groups.getOrPut(key) { ArrayList(1) }.add(item)
        }
        return groups.values.map { members ->
            var best = members.first()
            var bestScore = scoreOf(best) ?: FeedScore.UNKNOWN
            for (m in members.drop(1)) {
                val s = scoreOf(m) ?: FeedScore.UNKNOWN
                if (s > bestScore) {
                    best = m
                    bestScore = s
                }
            }
            Collapsed(best, members.size - 1)
        }
    }
}
