package com.iptvapp.data.channel

import android.view.KeyEvent
import com.iptvapp.data.local.entities.ChannelEntity

/**
 * Channel numbers, as shown on the lists and typed on the remote. Plain functions, so the
 * screens and the tests share one set of rules.
 */
object ChannelNumbering {

    /** Up to channel 99,999: some providers number their US block in the tens of thousands. */
    const val MAX_DIGITS = 5

    /** How long the player waits after the last digit before it tunes (OK tunes at once). */
    const val TUNE_DELAY_MS = 1400L

    /**
     * The number a row shows, or null for none. [position] is the row's place in its list
     * (0-based), used by [LcnMode.SEQUENTIAL]. A provider number of 0 or less means "none".
     */
    fun displayNumber(mode: LcnMode, customNum: Int?, providerNum: Int, position: Int): Int? = when (mode) {
        LcnMode.CUSTOM_FIRST -> customNum ?: providerNum.takeIf { it > 0 }
        LcnMode.SEQUENTIAL -> if (position >= 0) position + 1 else null
        LcnMode.PROVIDER_RAW -> providerNum.takeIf { it > 0 }
        LcnMode.OFF -> null
    }

    fun displayNumber(mode: LcnMode, channel: ChannelEntity, position: Int): Int? =
        displayNumber(mode, channel.customNum, channel.num, position)

    /** The digit a remote key types (top-row and keypad digits), or null for any other key. */
    fun digitOf(keyCode: Int): Char? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        else -> null
    }

    /** [buffer] with [digit] added, unless it already holds [MAX_DIGITS] digits. */
    fun append(buffer: String, digit: Char): String =
        if (buffer.length >= MAX_DIGITS) buffer else buffer + digit

    /** Channel [num] when the numbers are 1, 2, 3… down [list]. */
    fun <T> sequentialPick(list: List<T>, num: Int): T? = if (num >= 1) list.getOrNull(num - 1) else null

    /**
     * Auto-Number Favorites: the favorites in their own order (as the Favorites list shows them)
     * numbered 1..N, as streamId to number.
     */
    fun favoriteNumbers(favorites: List<ChannelEntity>): List<Pair<Int, Int>> =
        favorites.sortedWith(compareBy<ChannelEntity> { it.favOrder }.thenBy { it.name })
            .mapIndexed { i, ch -> ch.streamId to i + 1 }
}
