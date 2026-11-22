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

    /** True when the payloads ask for nothing but new numbers. */
    fun onlyNumbers(payloads: List<Any>): Boolean = payloads.isNotEmpty() && payloads.all { it == PAYLOAD }
}
