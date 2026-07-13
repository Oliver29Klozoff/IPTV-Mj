package com.iptvapp.util

/** Shared keyword-bucketing used to fold providers' messy raw genre/category tags into a
 * handful of broad folders (Comedy, Drama, Action & Adventure, ...) — used by both phone and
 * TV for Series (genre tag) and Movies (category name) folder chips. */
object GenreBuckets {
    val MAP = linkedMapOf(
        "Comedy" to listOf("comedy"),
        "Drama" to listOf("drama"),
        "Action & Adventure" to listOf("action", "adventure"),
        "Sci-Fi & Fantasy" to listOf("sci-fi", "scifi", "science fiction", "fantasy"),
        "Crime & Mystery" to listOf("crime", "mystery", "detective"),
        "Horror & Thriller" to listOf("horror", "thriller", "suspense"),
        "Animation" to listOf("animation", "anime", "cartoon"),
        "Documentary" to listOf("documentary", "docu"),
        "Kids & Family" to listOf("kids", "family", "children"),
        "Reality" to listOf("reality", "game show", "talk show"),
        "Romance" to listOf("romance", "romantic"),
        "War & History" to listOf("war", "history", "historical"),
        "Music" to listOf("music", "musical")
    )

    /** Which bucket(s) a single item belongs to, given its raw tag strings (a genre field split
     * on commas, or just a single-element list for a category name). "Other" if none match. */
    fun bucketsFor(tags: List<String>): List<String> {
        val cleaned = tags.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return emptyList()
        val matched = MAP.filter { (_, keywords) ->
            cleaned.any { tag -> keywords.any { kw -> tag.contains(kw, ignoreCase = true) } }
        }.keys.toList()
        return matched.ifEmpty { listOf("Other") }
    }

    /** Which buckets are present across a whole list of items' tag-lists, in MAP order with
     * "Other" appended last if applicable. */
    fun presentBuckets(allTagLists: List<List<String>>): List<String> {
        val present = allTagLists.flatMap { bucketsFor(it) }.toSet()
        val ordered = MAP.keys.filter { it in present }
        return if ("Other" in present) ordered + "Other" else ordered
    }
}
