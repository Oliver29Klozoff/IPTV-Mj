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
     * Player extra: a token for the list on screen when the player opened ([remember]), so
     * Sequential numbers typed in the player mean the places that list showed. The list itself
     * stays in memory here: a long one would not fit through an Intent.
     */
    const val EXTRA_SEQUENTIAL_TOKEN = "lcn_sequential_token"

    private var rememberedToken = 0L
    private var rememberedKeys: List<String> = emptyList()

    /** Keeps [keys] for the player; returns the token to send with it. */
    @Synchronized
    fun remember(keys: List<String>): Long {
        rememberedToken += 1
        rememberedKeys = keys
        return rememberedToken
    }

    /** The list sent with [token], or null when it's gone (a newer list, or the app was restarted). */
    @Synchronized
    fun recall(token: Long): List<String>? = if (token == rememberedToken && token != 0L) rememberedKeys else null

    fun parseKey(key: String): Pair<Int, Int>? {
        val parts = key.split(':')
        if (parts.size != 2) return null
        val server = parts[0].toIntOrNull() ?: return null
        val id = parts[1].toIntOrNull() ?: return null
        return server to id
    }

    /**
     * The rows of [adapter] (a channel list) as player keys: only in Sequential numbering, and
     * null when it isn't a channel list.
     */
    fun keysOf(adapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>?, mode: LcnMode): List<String>? =
        if (mode != LcnMode.SEQUENTIAL) null else when (adapter) {
        is com.iptvapp.ui.home.ChannelAdapter -> adapter.currentList.map { "-1:${it.streamId}" }
        is com.iptvapp.ui.home.LiveChannelAdapter -> adapter.currentList.map { "${it.serverIndex}:${it.streamId}" }
        is com.iptvapp.ui.home.CombinedFavoriteAdapter -> adapter.shownRows().map {
            when (it) {
                is com.iptvapp.ui.home.CombinedFavorite.Primary -> "-1:${it.channel.streamId}"
                is com.iptvapp.ui.home.CombinedFavorite.Merged -> "${it.channel.serverIndex}:${it.channel.streamId}"
            }
        }
        else -> null
    }?.takeIf { it.isNotEmpty() }

    /** True when the payloads ask for nothing but new numbers. */
    fun onlyNumbers(payloads: List<Any>): Boolean = payloads.isNotEmpty() && payloads.all { it == PAYLOAD }
}
