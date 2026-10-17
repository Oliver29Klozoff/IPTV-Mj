package com.iptvapp.util

/**
 * Settings > Match frame rate (next to Sports mode): when the player switches the TV's display mode to
 * suit the video (24 fps films at 24 Hz, 50 Hz European TV at 50 Hz), using the mode picker in
 * [SportsMode.displayModeId]. Many TVs blank for a second or two while they change mode, which is
 * fine once at the start of a film and tiresome on every channel change, so the default is
 * movies and series only.
 */
object FrameRateMatch {
    const val OFF = 0
    /** Movies, series, catch-up replays and recordings. */
    const val ON_DEMAND = 1
    /** Also every live channel. */
    const val EVERYTHING = 2
    const val DEFAULT = ON_DEMAND

    fun normalize(value: Int): Int = if (value in OFF..EVERYTHING) value else DEFAULT

    /**
     * Whether this playback should get a matching display mode. Sports Mode has matched frame
     * rates since it arrived and keeps doing so unless the setting is Off. Casting and listening
     * show nothing on this screen, so they never switch it.
     */
    fun wanted(
        setting: Int,
        sportsOn: Boolean,
        onDemand: Boolean,
        casting: Boolean,
        audioOnly: Boolean,
    ): Boolean {
        if (casting || audioOnly) return false
        return when (normalize(setting)) {
            OFF -> false
            EVERYTHING -> true
            else -> onDemand || sportsOn
        }
    }

    fun label(setting: Int): String = when (normalize(setting)) {
        OFF -> "Off"
        EVERYTHING -> "Everything"
        else -> "Movies & series"
    }
}
