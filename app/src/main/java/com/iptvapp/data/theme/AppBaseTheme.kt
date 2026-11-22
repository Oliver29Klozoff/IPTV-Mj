package com.iptvapp.data.theme

import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import com.iptvapp.R

/**
 * Settings → Appearance → Base theme: the dark the whole app sits on, on TV, phone and car box.
 * ThemeEngine paints these onto each screen's near-black panels in place (no restart).
 * [backgroundColor] is the screen itself, [surfaceColor] raised panels (sidebars, headers),
 * [cardColor] cards, [borderColor] card outlines, [dividerColor] thin rules.
 */
enum class AppBaseTheme(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    @ColorInt val backgroundColor: Int,
    @ColorInt val surfaceColor: Int,
    @ColorInt val cardColor: Int,
    @ColorInt val borderColor: Int,
    @ColorInt val dividerColor: Int,
) {
    OLED_BLACK(
        id = "oled_black",
        titleRes = R.string.theme_oled_black,
        descRes = R.string.theme_oled_black_desc,
        backgroundColor = 0xFF000000.toInt(),
        surfaceColor = 0xFF080808.toInt(),
        cardColor = 0xFF000000.toInt(),
        borderColor = 0xFF222222.toInt(),
        dividerColor = 0xFF141414.toInt(),
    ),
    MIDNIGHT_NAVY(
        id = "midnight_navy",
        titleRes = R.string.theme_midnight_navy,
        descRes = R.string.theme_midnight_navy_desc,
        backgroundColor = 0xFF0B1120.toInt(),
        surfaceColor = 0xFF1E293B.toInt(),
        cardColor = 0xFF1E293B.toInt(),
        borderColor = 0xFF334155.toInt(),
        dividerColor = 0xFF1E293B.toInt(),
    ),
    CHARCOAL_DARK(
        id = "charcoal_dark",
        titleRes = R.string.theme_charcoal_dark,
        descRes = R.string.theme_charcoal_dark_desc,
        backgroundColor = 0xFF121212.toInt(),
        surfaceColor = 0xFF1E1E1E.toInt(),
        cardColor = 0xFF1E1E1E.toInt(),
        borderColor = 0xFF2C2C2C.toInt(),
        dividerColor = 0xFF242424.toInt(),
    );

    /**
     * The colour this theme gives a panel the layout painted [original], or null to leave it.
     * Only opaque, neutral near-black greys (the app's own chrome: #000000 to #282828, R/G/B
     * within 10 of each other) are themed, so the accent, posters, red record dots and see-through
     * scrims never change. How dark the original was picks its role: the darkest are the screen
     * itself, the next band raised panels, the lightest cards. A thin view ([thin]) is a rule.
     */
    @ColorInt
    fun surfaceFor(@ColorInt original: Int, thin: Boolean = false): Int? {
        if (original ushr 24 != 0xFF) return null
        val r = (original shr 16) and 0xFF
        val g = (original shr 8) and 0xFF
        val b = original and 0xFF
        val max = maxOf(r, g, b)
        if (max > NEAR_BLACK_MAX || max - minOf(r, g, b) > NEUTRAL_SPREAD) return null
        if (thin && max > 0) return dividerColor
        return when {
            max <= BACKGROUND_MAX -> backgroundColor
            max <= SURFACE_MAX -> surfaceColor
            else -> cardColor
        }
    }

    companion object {
        /** A new or updated install, and any unreadable saved value. */
        val DEFAULT = OLED_BLACK

        private const val NEAR_BLACK_MAX = 0x28
        private const val NEUTRAL_SPREAD = 10
        private const val BACKGROUND_MAX = 0x08
        private const val SURFACE_MAX = 0x12

        fun fromId(id: String?): AppBaseTheme =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}
