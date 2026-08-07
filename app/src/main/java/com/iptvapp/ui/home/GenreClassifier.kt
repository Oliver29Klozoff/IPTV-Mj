package com.iptvapp.ui.home

// Shared keyword taxonomy for auto-classifying favorites by their own provider category name —
// previously duplicated byte-for-byte in HomeActivity.kt and TvHomeActivity.kt; extracted here
// so both platforms and the combined (primary + merged-provider) favorites list share one
// definition instead of three.
object GenreClassifier {
    // "Other" is not a keyword bucket like the rest — see matches()/detectGenres() below, it's
    // special-cased to mean "matched none of the keyword genres," so a favorite whose category
    // doesn't fit any known bucket (an unusual/foreign-language category name, or a channel not
    // filed under a recognizable category at all) still has somewhere to be found by genre
    // instead of being invisible outside the "All" view.
    const val OTHER = "Other"

    // Insertion order here IS the chip row's display order (see detectGenres/updateFavoriteGenreChips
    // in HomeActivity/TvHomeActivity) — All first, Unsorted (OTHER) is always appended separately
    // at the very end, everything else in between follows this order exactly per explicit request.
    val GENRE_KEYWORDS = linkedMapOf(
        "All"           to emptyList<String>(),
        "Movies"        to listOf("movie", "film", "cinema", "hbo", "showtime", "starz", "amc", "fx movie"),
        "Entertainment" to listOf("entertainment", "comedy", "drama", "tnt", "tbs", "bravo"),
        "Sports"        to listOf("sport", "espn", "nfl", "nba", "mlb", "nhl", "nascar", "tennis", "golf", "soccer", "football"),
        "Game Shows"    to listOf("game show", "gameshow", "quiz"),
        "Basic TV"      to listOf("basic", "local", "broadcast", "network tv"),
        "Music"         to listOf("music", "mtv", "vh1", "vevo"),
        "News"          to listOf("news", "cnn", "cnbc", "msnbc", "bbc", "fox news", "abc news", "nbc news"),
        "Weather"       to listOf("weather"),
        "Kids"          to listOf("kid", "children", "child", "disney", "nickelodeon", "nick", "cartoon", "toon")
    )

    private fun matchesKnownGenre(categoryName: String): Boolean =
        GENRE_KEYWORDS.entries.any { (genre, keywords) -> genre != "All" && keywords.any { kw -> categoryName.contains(kw, ignoreCase = true) } }

    /** manualGenre (see ChannelEntity.manualGenre kdoc) wins outright when set — the favorite
     * shows ONLY under that chip (or "All", which always shows everything) regardless of what
     * its real category would otherwise auto-classify it as. */
    fun matches(genre: String, categoryName: String?, manualGenre: String? = null): Boolean {
        if (manualGenre != null) return genre == "All" || genre == manualGenre
        if (genre == OTHER) return categoryName == null || !matchesKnownGenre(categoryName)
        if (categoryName == null) return false
        val keywords = GENRE_KEYWORDS[genre] ?: return false
        if (keywords.isEmpty()) return true
        return keywords.any { kw -> categoryName.contains(kw, ignoreCase = true) }
    }

    /** categoryNames and manualGenres must be the same length/order (one entry per favorite) —
     * see HomeActivity/TvHomeActivity's updateFavoriteGenreChips for how they're paired up. */
    fun detectGenres(categoryNames: List<String>, manualGenres: List<String?> = emptyList()): List<String> {
        val detected = GENRE_KEYWORDS.keys.filter { genre ->
            val keywords = GENRE_KEYWORDS[genre]!!
            keywords.isEmpty() || categoryNames.any { name -> keywords.any { kw -> name.contains(kw, ignoreCase = true) } }
        }
        val hasUnmatched = categoryNames.any { !matchesKnownGenre(it) }
        val hasManualOther = manualGenres.any { it == OTHER }
        return if (hasUnmatched || hasManualOther) detected + OTHER else detected
    }
}
