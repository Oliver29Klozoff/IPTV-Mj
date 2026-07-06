package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.databinding.ItemTvEpgRowBinding

class TvEpgGuideAdapter(
    private val onChannelClick: (ChannelEntity) -> Unit
) : ListAdapter<ChannelEntity, TvEpgGuideAdapter.VH>(Diff()) {

    private var epgText: Map<Int, String> = emptyMap()
    private var epgNextText: Map<Int, String> = emptyMap()
    private var epgProgress: Map<Int, Int> = emptyMap()

    fun submitEpgText(map: Map<Int, String>) {
        epgText = map
        notifyItemRangeChanged(0, itemCount)
    }

    fun submitEpgNextText(map: Map<Int, String>) {
        epgNextText = map
        notifyItemRangeChanged(0, itemCount)
    }

    fun submitEpgProgress(map: Map<Int, Int>) {
        epgProgress = map
        notifyItemRangeChanged(0, itemCount)
    }

    inner class VH(val b: ItemTvEpgRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTvEpgRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = getItem(position)
        val b = holder.b
        b.tvEpgRowChannel.text = ch.name
        b.tvEpgRowNow.text = epgText[ch.streamId] ?: "—"
        val next = epgNextText[ch.streamId]
        b.tvEpgRowNext.text = if (next != null) "Next: $next" else ""
        val prog = epgProgress[ch.streamId] ?: 0
        b.tvEpgRowProgress.progress = prog
        b.tvEpgRowProgress.visibility = if (prog > 0) View.VISIBLE else View.GONE
        b.root.setOnClickListener { onChannelClick(ch) }
    }

    private class Diff : DiffUtil.ItemCallback<ChannelEntity>() {
        override fun areItemsTheSame(a: ChannelEntity, b: ChannelEntity) = a.streamId == b.streamId
        override fun areContentsTheSame(a: ChannelEntity, b: ChannelEntity) = a == b
    }
}
