package com.iptvapp.util

/**
 * Sports, movie, or standard picture. Auto follows the programme.
 * The TV applies a profile only when it has that system profile. Frame-rate matching
 * still runs for sports and movies when the panel cannot change picture settings.
 */
object PictureMode {
    const val AUTO = 0
    const val SPORTS = 1
    const val MOVIE = 2
    const val STANDARD = 3

    const val PROFILE_STANDARD = "standard"
    const val PROFILE_SPORTS = "sports"
    const val PROFILE_MOVIE = "movie"

    fun normalize(value: Int): Int = if (value in AUTO..STANDARD) value else AUTO

    fun label(value: Int): String = when (normalize(value)) {
        SPORTS -> "Sports"
        MOVIE -> "Movie"
        STANDARD -> "Standard"
        else -> "Auto"
    }

    /**
     * [movie] is a film, not a series episode and not a catch-up replay.
     * [live] is a channel. A sports title on live TV wins over a generic channel.
     */
    fun profileName(setting: Int, live: Boolean, title: String, movie: Boolean): String {
        return when (normalize(setting)) {
            SPORTS -> PROFILE_SPORTS
            MOVIE -> PROFILE_MOVIE
            STANDARD -> PROFILE_STANDARD
            else -> when {
                live && SportsFinder.leagueOf(title) != null -> PROFILE_SPORTS
                movie -> PROFILE_MOVIE
                else -> PROFILE_STANDARD
            }
        }
    }

    /**
     * Match the display refresh to the video for sports and movies.
     * Sports Mode's low-latency buffer already matches, and that stays on when picture mode is standard.
     */
    fun matchFrameRate(
        setting: Int,
        live: Boolean,
        title: String,
        movie: Boolean,
        sportsBufferOn: Boolean
    ): Boolean {
        if (sportsBufferOn) return true
        val profile = profileName(setting, live, title, movie)
        return profile == PROFILE_SPORTS || profile == PROFILE_MOVIE
    }
}
