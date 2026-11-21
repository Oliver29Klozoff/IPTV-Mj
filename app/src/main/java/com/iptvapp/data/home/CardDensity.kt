package com.iptvapp.data.home

import androidx.annotation.StringRes
import com.iptvapp.R

/**
 * How big Home's cards are (Customize Home → Card Density & Size), on both home screens, and how
 * many columns the TV's full-screen Movies and Series grids use. [STANDARD] is how MKTV has always
 * looked and what an unknown or missing value reads as, so an update changes nothing.
 */
enum class CardDensity(
    val id: String,
    /** Picture size against Standard. */
    val scaleMultiplier: Float,
    /** Columns added to the TV grids' five (Compact six, Expanded four). */
    val gridSpanOffset: Int,
    /** Padding inside each card, all round. */
    val cardPaddingDp: Int,
    /** The card's title, line and next-show text sizes. */
    val titleSp: Float,
    val lineSp: Float,
    val nextSp: Float,
    @StringRes val titleResId: Int,
    @StringRes val descResId: Int,
) {
    COMPACT("compact", 0.82f, 1, 3, 12f, 11f, 10f, R.string.density_compact_title, R.string.density_compact_desc),
    STANDARD("standard", 1.0f, 0, 4, 14f, 12f, 11f, R.string.density_standard_title, R.string.density_standard_desc),
    EXPANDED("expanded", 1.22f, -1, 6, 16f, 14f, 12f, R.string.density_expanded_title, R.string.density_expanded_desc);

    /** Columns for a grid of [base] columns at Standard, kept between 4 and 7. */
    fun gridSpan(base: Int = TV_GRID_BASE_SPAN): Int = (base + gridSpanOffset).coerceIn(MIN_SPAN, MAX_SPAN)

    companion object {
        const val TV_GRID_BASE_SPAN = 5
        const val MIN_SPAN = 4
        const val MAX_SPAN = 7

        fun fromId(id: String?): CardDensity = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: STANDARD
    }
}
