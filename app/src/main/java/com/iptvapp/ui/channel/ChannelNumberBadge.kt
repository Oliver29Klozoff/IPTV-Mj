package com.iptvapp.ui.channel

import android.view.View
import android.widget.TextView
import com.iptvapp.data.channel.ChannelNumbering
import com.iptvapp.data.channel.LcnMode
import com.iptvapp.data.local.entities.ChannelEntity

/** The channel number on a list row (item_channel's tvChannelNumber). */
object ChannelNumberBadge {

    /** Rebind payload: only the numbers changed (the mode, or the rows' places in Sequential). */
    const val PAYLOAD = "lcn_number"

    fun bind(view: TextView, number: Int?) {
        if (number == null) {
            view.visibility = View.GONE
            view.text = ""
        } else {
            view.text = number.toString()
            view.visibility = View.VISIBLE
        }
    }

    /** A main-provider channel's number at [position] in its list. */
    fun numberFor(mode: LcnMode, channel: ChannelEntity, position: Int): Int? =
        ChannelNumbering.displayNumber(mode, channel, position)

    /**
     * Another provider's channel: only Sequential numbers it. Its own "num" is often just its place
     * in that provider's list, and number entry never reaches it.
     */
    fun numberForOtherProvider(mode: LcnMode, position: Int): Int? =
        if (mode == LcnMode.SEQUENTIAL && position >= 0) position + 1 else null

    /**
     * Player extra: the list on screen when the player opened, as "serverIndex:streamId" in row
     * order, so Sequential numbers typed in the player mean the places that list showed.
     */
    const val EXTRA_SEQUENTIAL_KEYS = "lcn_sequential_keys"

    fun parseKey(key: String): Pair<Int, Int>? {
        val parts = key.split(':')
        if (parts.size != 2) return null
        val server = parts[0].toIntOrNull() ?: return null
        val id = parts[1].toIntOrNull() ?: return null
        return server to id
    }

    /** Larger lists aren't sent (an Intent has to stay well under Binder's 1 MB). */
    private const val MAX_SEQUENTIAL_KEYS = 10_000

    /**
     * The rows of [adapter] (a channel list) as player keys: only in Sequential numbering, and
     * null when it isn't a channel list or is too long to send.
     */
    fun keysOf(adapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>?, mode: LcnMode): Array<String>? =
        if (mode != LcnMode.SEQUENTIAL || (adapter?.itemCount ?: 0) > MAX_SEQUENTIAL_KEYS) null else when (adapter) {
        is com.iptvapp.ui.home.ChannelAdapter -> adapter.currentList.map { "-1:${it.streamId}" }
        is com.iptvapp.ui.home.LiveChannelAdapter -> adapter.currentList.map { "${it.serverIndex}:${it.streamId}" }
        is com.iptvapp.ui.home.CombinedFavoriteAdapter -> adapter.currentOrder().map {
            when (it) {
                is com.iptvapp.ui.home.CombinedFavorite.Primary -> "-1:${it.channel.streamId}"
                is com.iptvapp.ui.home.CombinedFavorite.Merged -> "${it.channel.serverIndex}:${it.channel.streamId}"
            }
        }
        else -> null
    }?.takeIf { it.isNotEmpty() }?.toTypedArray()

    /** True when the payloads ask for nothing but new numbers. */
    fun onlyNumbers(payloads: List<Any>): Boolean = payloads.isNotEmpty() && payloads.all { it == PAYLOAD }
}
