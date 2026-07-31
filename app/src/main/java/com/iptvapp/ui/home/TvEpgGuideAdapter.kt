package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.databinding.ItemTvEpgRowBinding
import com.iptvapp.ui.guide.GuideRow
import com.iptvapp.ui.guide.providerColorFor

// Previously bound plain ChannelEntity rows sourced from viewModel.channels/channelEpgText —
// primary-provider-only, so a favorited merged/secondary-provider channel's guide data never
// showed here at all (unlike the phone's full-screen Guide, which already merges both). Now
// binds the same GuideRow model the phone Guide uses (see GuideModels.kt), computing NOW/NEXT/
// progress directly from row.programs instead of relying on separately-maintained text maps —
// those maps (channelEpgText etc.) were primary-only by construction and had no merged-row
// equivalent to extend.
class TvEpgGuideAdapter(
    private val onChannelClick: (GuideRow) -> Unit,
    private val onChannelLongClick: (GuideRow) -> Unit = {}
) : ListAdapter<GuideRow, TvEpgGuideAdapter.VH>(Diff()) {

    // Same reasoning as ChannelAdapter: rebinding a row the user is mid-long-press on can
    // disrupt Android's pending long-press callback, which used a periodic full-list
    // notifyItemRangeChanged here to begin with — every single row was rebinding on every
    // refresh, not just changed ones, making this the more likely place to actually hit it.
    private var pressedKey: String? = null
    private fun keyOf(row: GuideRow) = "${row.serverIndex}:${row.streamId}"

    inner class VH(val b: ItemTvEpgRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTvEpgRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        if (keyOf(row) == pressedKey) return
        val b = holder.b
        b.tvEpgRowChannel.text = row.name
        b.tvEpgRowProviderStripe.setBackgroundColor(providerColorFor(row.serverIndex) ?: 0x00000000)

        val nowMs = System.currentTimeMillis()
        fun toMs(ts: Long) = if (ts < 100_000_000_000L) ts * 1000L else ts
        val current = row.programs.firstOrNull { toMs(it.startTimestamp) <= nowMs && nowMs < toMs(it.stopTimestamp) }
        val next = row.programs
            .filter { toMs(it.startTimestamp) > nowMs }
            .minByOrNull { it.startTimestamp }

        b.tvEpgRowNow.text = current?.title ?: "—"
        b.tvEpgRowNext.text = next?.let { "Next: ${it.title}" } ?: ""
        val progress = if (current != null) {
            val startMs = toMs(current.startTimestamp)
            val stopMs = toMs(current.stopTimestamp)
            val span = (stopMs - startMs).coerceAtLeast(1)
            (((nowMs - startMs) * 100) / span).toInt().coerceIn(0, 100)
        } else 0
        b.tvEpgRowProgress.progress = progress
        b.tvEpgRowProgress.visibility = if (progress > 0) View.VISIBLE else View.GONE

        b.root.setOnClickListener { onChannelClick(row) }
        b.root.setOnLongClickListener { onChannelLongClick(row); true }
        b.root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    android.view.KeyEvent.ACTION_DOWN -> pressedKey = keyOf(row)
                    android.view.KeyEvent.ACTION_UP -> if (pressedKey == keyOf(row)) pressedKey = null
                }
            }
            false
        }
    }

    private class Diff : DiffUtil.ItemCallback<GuideRow>() {
        override fun areItemsTheSame(a: GuideRow, b: GuideRow) = a.serverIndex == b.serverIndex && a.streamId == b.streamId
        override fun areContentsTheSame(a: GuideRow, b: GuideRow) = a == b
    }
}
