package com.iptvapp.ui.home

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.databinding.ItemTvEpgProgramBlockBinding
import com.iptvapp.databinding.ItemTvEpgRowBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// dp per minute of program time — 30min=150dp, 1hr=300dp
const val TV_EPG_DP_PER_MIN = 5f
private const val MIN_BLOCK_MINUTES = 20f

class TvEpgGuideAdapter(
    private val onChannelClick: (ChannelEntity) -> Unit
) : ListAdapter<ChannelEntity, TvEpgGuideAdapter.VH>(Diff()) {

    private var programsMap: Map<Int, List<EpgEntity>> = emptyMap()
    private var sharedScrollX = 0
    private var isSyncingScroll = false
    private val liveHolders = mutableSetOf<VH>()
    var onScrollSynced: ((Int) -> Unit)? = null

    fun submitPrograms(map: Map<Int, List<EpgEntity>>) {
        programsMap = map
        notifyItemRangeChanged(0, itemCount)
    }

    inner class VH(val b: ItemTvEpgRowBinding) : RecyclerView.ViewHolder(b.root) {
        internal val programAdapter = TvEpgProgramAdapter { onChannelClick(getItem(bindingAdapterPosition)) }

        init {
            b.rvPrograms.layoutManager = LinearLayoutManager(b.root.context, LinearLayoutManager.HORIZONTAL, false)
            b.rvPrograms.adapter = programAdapter
            b.rvPrograms.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dx == 0 || isSyncingScroll) return
                    sharedScrollX += dx
                    isSyncingScroll = true
                    liveHolders.forEach { h ->
                        if (h !== this@VH) {
                            val current = h.b.rvPrograms.computeHorizontalScrollOffset()
                            val delta = sharedScrollX - current
                            if (delta != 0) h.b.rvPrograms.scrollBy(delta, 0)
                        }
                    }
                    isSyncingScroll = false
                    onScrollSynced?.invoke(sharedScrollX)
                }
            })
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTvEpgRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = getItem(position)
        holder.b.tvEpgRowChannel.text = ch.name
        holder.programAdapter.submitPrograms(programsMap[ch.streamId].orEmpty())
        holder.b.rvPrograms.post {
            val current = holder.b.rvPrograms.computeHorizontalScrollOffset()
            val delta = sharedScrollX - current
            if (delta != 0) holder.b.rvPrograms.scrollBy(delta, 0)
        }
    }

    override fun onViewAttachedToWindow(holder: VH) {
        super.onViewAttachedToWindow(holder)
        liveHolders.add(holder)
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        super.onViewDetachedFromWindow(holder)
        liveHolders.remove(holder)
    }

    private class Diff : DiffUtil.ItemCallback<ChannelEntity>() {
        override fun areItemsTheSame(a: ChannelEntity, b: ChannelEntity) = a.streamId == b.streamId
        override fun areContentsTheSame(a: ChannelEntity, b: ChannelEntity) = a == b
    }
}

internal class TvEpgProgramAdapter(
    private val onClick: () -> Unit
) : RecyclerView.Adapter<TvEpgProgramAdapter.VH>() {

    private var programs: List<EpgEntity> = emptyList()
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun submitPrograms(list: List<EpgEntity>) {
        programs = list
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemTvEpgProgramBlockBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount() = if (programs.isEmpty()) 1 else programs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTvEpgProgramBlockBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.itemView.context
        val dpPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TV_EPG_DP_PER_MIN, ctx.resources.displayMetrics)
        val nowMs = System.currentTimeMillis()

        if (programs.isEmpty()) {
            holder.b.tvBlockTitle.text = "No guide data"
            holder.b.tvBlockTime.text = ""
            holder.b.tvBlockProgress.visibility = View.GONE
            holder.itemView.layoutParams = ViewGroup.LayoutParams(
                (200 * dpPx / TV_EPG_DP_PER_MIN).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
            )
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.itemView.setOnClickListener { onClick() }
            return
        }

        val program = programs[position]
        fun toMs(ts: Long) = if (ts < 100_000_000_000L) ts * 1000L else ts
        val realStartMs = toMs(program.startTimestamp)
        val stopMs = toMs(program.stopTimestamp)
        val isNow = realStartMs <= nowMs && stopMs > nowMs
        // Align pixel 0 with "now" for every row: an in-progress program only occupies its remaining time
        val visibleStartMs = if (isNow) nowMs else realStartMs
        val durationMin = ((stopMs - visibleStartMs) / 60_000f).coerceAtLeast(MIN_BLOCK_MINUTES)
        val widthPx = (durationMin * dpPx).toInt()

        holder.itemView.layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT)
        holder.b.tvBlockTitle.text = program.title
        holder.b.tvBlockTime.text = "${timeFmt.format(Date(realStartMs))} - ${timeFmt.format(Date(stopMs))}"
        holder.itemView.setBackgroundColor(if (isNow) 0xFF15243A.toInt() else Color.TRANSPARENT)

        if (isNow && stopMs > realStartMs) {
            val elapsed = (nowMs - realStartMs).coerceAtLeast(0)
            val total = stopMs - realStartMs
            holder.b.tvBlockProgress.progress = ((elapsed * 100L) / total).coerceIn(0, 100).toInt()
            holder.b.tvBlockProgress.visibility = View.VISIBLE
        } else {
            holder.b.tvBlockProgress.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick() }
    }
}
