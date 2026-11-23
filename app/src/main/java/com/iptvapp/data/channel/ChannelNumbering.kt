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

    /**
     * The number a search asks for: 1 to [MAX_DIGITS] digits ("206", "007" means 7), as text
     * without leading zeros; null for anything else (words, "0", a long pasted number).
     */
    fun numberQuery(query: String): String? {
        val q = query.trim()
        if (q.isEmpty() || q.length > MAX_DIGITS || !q.all { it in '0'..'9' }) return null
        return q.trimStart('0').ifEmpty { null }
    }

    /**
     * The number search finds a main-provider channel by: the one its list row shows. Sequential
     * numbers depend on the list on screen and Off shows none, so those find nothing by number.
     */
    fun searchableNumber(mode: LcnMode, customNum: Int?, providerNum: Int): Int? = when (mode) {
        LcnMode.CUSTOM_FIRST, LcnMode.PROVIDER_RAW -> displayNumber(mode, customNum, providerNum, -1)
        LcnMode.SEQUENTIAL, LcnMode.OFF -> null
    }

    fun searchableNumber(mode: LcnMode, channel: ChannelEntity): Int? =
        searchableNumber(mode, channel.customNum, channel.num)

    /** Whether search finds numbers at all under [mode]. */
    fun searchesNumbers(mode: LcnMode) = mode == LcnMode.CUSTOM_FIRST || mode == LcnMode.PROVIDER_RAW

    /** Whether [channel]'s searchable number starts with [number] (from [numberQuery]). */
    fun matchesNumber(mode: LcnMode, channel: ChannelEntity, number: String): Boolean =
        searchableNumber(mode, channel)?.toString()?.startsWith(number) == true

    /**
     * A number search's channels: the one numbered exactly [number] first, then the rest of
     * [byNumber] in its order (lowest number first), then [byName] matches not already listed.
     */
    fun searchOrder(
        byNumber: List<ChannelEntity>, byName: List<ChannelEntity>, number: String, numberOf: (ChannelEntity) -> Int?,
    ): List<ChannelEntity> {
        val exact = number.toIntOrNull()
        val (first, rest) = byNumber.partition { exact != null && numberOf(it) == exact }
        return (first + rest + byName).distinctBy { it.streamId }
    }

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
