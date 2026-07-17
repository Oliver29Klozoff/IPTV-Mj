package com.iptvapp.ui.home

// Shared keyword taxonomy for auto-classifying favorites by their own provider category name —
// previously duplicated byte-for-byte in HomeActivity.kt and TvHomeActivity.kt; extracted here
// so both platforms and the combined (primary + merged-provider) favorites list share one
// definition instead of three.
object GenreClassifier {
    val GENRE_KEYWORDS = linkedMapOf(
        "All"           to emptyList<String>(),
        "Sports"        to listOf("sport", "espn", "nfl", "nba", "mlb", "nhl", "nascar", "tennis", "golf", "soccer", "football"),
        "News"          to listOf("news", "cnn", "cnbc", "msnbc", "bbc", "fox news", "abc news", "nbc news"),
        "Movies"        to listOf("movie", "film", "cinema", "hbo", "showtime", "starz", "amc", "fx movie"),
        "Kids"          to listOf("kid", "children", "child", "disney", "nickelodeon", "nick", "cartoon", "toon"),
        "Entertainment" to listOf("entertainment", "comedy", "drama", "tnt", "tbs", "bravo", "mtv", "vh1")
    )

    fun matches(genre: String, categoryName: String?): Boolean {
        if (categoryName == null) return false
        val keywords = GENRE_KEYWORDS[genre] ?: return false
        if (keywords.isEmpty()) return true
        return keywords.any { kw -> categoryName.contains(kw, ignoreCase = true) }
    }

    fun detectGenres(categoryNames: List<String>): List<String> =
        GENRE_KEYWORDS.keys.filter { genre ->
            val keywords = GENRE_KEYWORDS[genre]!!
            keywords.isEmpty() || categoryNames.any { name -> keywords.any { kw -> name.contains(kw, ignoreCase = true) } }
        }
}
