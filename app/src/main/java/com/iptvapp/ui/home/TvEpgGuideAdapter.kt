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

    private var hourlyData: Map<Int, List<EpgSlot>> = emptyMap()

    fun submitHourlyData(map: Map<Int, List<EpgSlot>>) {
        hourlyData = map
        notifyItemRangeChanged(0, itemCount)
    }

    // Keep for mini player EPG text (channel focus) — not used by guide display
    private var epgText: Map<Int, String> = emptyMap()
    private var epgProgress: Map<Int, Int> = emptyMap()

    fun submitEpgText(map: Map<Int, String>) { epgText = map }
    fun submitEpgNextText(map: Map<Int, String>) { /* unused — guide uses hourly */ }
    fun submitEpgProgress(map: Map<Int, Int>) { epgProgress = map }

    inner class VH(val b: ItemTvEpgRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTvEpgRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = getItem(position)
        val b = holder.b
        b.tvEpgRowChannel.text = ch.name

        val slots = hourlyData[ch.streamId]
        val s0 = slots?.getOrNull(0)
        val s1 = slots?.getOrNull(1)
        val s2 = slots?.getOrNull(2)
        val s3 = slots?.getOrNull(3)

        // NOW slot
        b.tvEpgRowNowLabel.text = s0?.label ?: "NOW"
        b.tvEpgRowNow.text = s0?.title ?: "—"
        val prog = s0?.progress ?: 0
        b.tvEpgRowProgress.progress = prog
        b.tvEpgRowProgress.visibility = if (prog > 0) View.VISIBLE else View.GONE

        // +1h slot
        b.tvEpgRow1Label.text = s1?.label ?: ""
        b.tvEpgRow1.text = s1?.title ?: "—"

        // +2h slot
        b.tvEpgRow2Label.text = s2?.label ?: ""
        b.tvEpgRow2.text = s2?.title ?: "—"

        // +3h slot
        b.tvEpgRow3Label.text = s3?.label ?: ""
        b.tvEpgRow3.text = s3?.title ?: "—"

        b.root.setOnClickListener { onChannelClick(ch) }
    }

    private class Diff : DiffUtil.ItemCallback<ChannelEntity>() {
        override fun areItemsTheSame(a: ChannelEntity, b: ChannelEntity) = a.streamId == b.streamId
        override fun areContentsTheSame(a: ChannelEntity, b: ChannelEntity) = a == b
    }
}
