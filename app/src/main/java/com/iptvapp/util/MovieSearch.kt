package com.iptvapp.util

/**
 * Movie title matching for search: what's compared, and the order results are shown in.
 *
 * Provider titles carry noise around the real name ("EN - Billy Madison (1995) [1080p]"), and
 * people type titles loosely ("spiderman", "oceans eleven"). Matching works on the bare words;
 * ranking puts the title someone most likely meant first instead of alphabetical order.
 */
object MovieSearch {

    // A 2-3 letter language/region code and a separator. Kept to a known list: a title can
    // legitimately start with an acronym that's part of its name.
    private val titlePrefix = Regex(
        "^(EN|US|UK|FR|DE|ES|IT|PT|AR|IN|CA|AU|NZ|MX|BR)\\s*[-|:]\\s*",
        RegexOption.IGNORE_CASE
    )

    private val parenthesized = Regex("\\([^)]*\\)")
    private val bracketed = Regex("\\[[^]]*]")
    private val punctuation = Regex("[^\\p{L}\\p{N} ]")
    private val spaces = Regex("\\s+")

    /** The bare title words: no language prefix, nothing in brackets, no punctuation. */
    fun normalizeTitle(rawTitle: String): String {
        val withoutPrefix = rawTitle.trim().replace(titlePrefix, "")
        val withoutBracketed = withoutPrefix.replace(parenthesized, " ").replace(bracketed, " ")
        return withoutBracketed.replace(punctuation, " ").replace(spaces, " ").trim()
    }

    /** [title] with every space and punctuation removed, lowercase — "Spider-Man" is "spiderman". */
    fun compact(title: String): String = normalizeTitle(title).lowercase().replace(" ", "")

    /**
     * Lower is better: 0 the whole title, 1 the start of the title, 2 the start of the title
     * typed without spaces, 3 every typed word starts a title word, 4 anything else that matched.
     */
    fun score(title: String, query: String): Int {
        val t = normalizeTitle(title).lowercase()
        val q = normalizeTitle(query).lowercase()
        if (q.isEmpty()) return 4
        if (t == q) return 0
        if (t.startsWith(q)) return 1
        val ct = t.replace(" ", "")
        val cq = q.replace(" ", "")
        if (ct.startsWith(cq)) return 2
        val words = t.split(' ')
        if (q.split(' ').all { typed -> words.any { it.startsWith(typed) } }) return 3
        return 4
    }

    /** [items] best match first; equally good matches shortest title first, then A–Z. */
    fun <T> rank(items: List<T>, query: String, title: (T) -> String): List<T> =
        items.map { Triple(it, score(title(it), query), normalizeTitle(title(it)).lowercase()) }
            .sortedWith(compareBy({ it.second }, { it.third.length }, { it.third }))
            .map { it.first }
}
