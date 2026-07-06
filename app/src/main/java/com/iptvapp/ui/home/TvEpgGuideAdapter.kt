package com.iptvapp.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.util.TypedValue
import android.view.KeyEvent
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

// Must match the history window HomeViewModel fetches, so the leading gap and the
// initial "scroll to now" offset line up with the data that's actually available.
private const val TV_EPG_HOURS_BACK = 3f

class TvEpgGuideAdapter(
    private val onChannelClick: (ChannelEntity) -> Unit
) : ListAdapter<ChannelEntity, TvEpgGuideAdapter.VH>(Diff()) {

    private var programsMap: Map<Int, List<EpgEntity>> = emptyMap()
    private var timelineStartMs = 0L
    private var sharedScrollX = 0
    private var initialScrollSet = false
    private var isSyncingScroll = false
    private val liveHolders = mutableSetOf<VH>()
    var onScrollSynced: ((Int) -> Unit)? = null

    fun submitPrograms(map: Map<Int, List<EpgEntity>>) {
        programsMap = map
        timelineStartMs = System.currentTimeMillis() - (TV_EPG_HOURS_BACK * 3_600_000L).toLong()
        notifyItemRangeChanged(0, itemCount)
    }

    /** Jumps every row back to "now", e.g. from a NOW button above the guide. */
    fun scrollToNow(context: Context) {
        val dpPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TV_EPG_DP_PER_MIN, context.resources.displayMetrics)
        sharedScrollX = (TV_EPG_HOURS_BACK * 60 * dpPx).toInt()
        initialScrollSet = true
        liveHolders.forEach { h ->
            val current = h.b.rvPrograms.computeHorizontalScrollOffset()
            val delta = sharedScrollX - current
            if (delta != 0) h.b.rvPrograms.smoothScrollBy(delta, 0)
        }
        onScrollSynced?.invoke(sharedScrollX)
    }

    internal inner class GapDecoration(private val programAdapter: TvEpgProgramAdapter) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.setEmpty()
            if (parent.getChildAdapterPosition(view) == 0) {
                outRect.left = programAdapter.computeLeadingGapPx(timelineStartMs)
            }
        }
    }

    inner class VH(val b: ItemTvEpgRowBinding) : RecyclerView.ViewHolder(b.root) {
        internal val programAdapter = TvEpgProgramAdapter(b.root.context) { onChannelClick(getItem(bindingAdapterPosition)) }

        init {
            b.rvPrograms.layoutManager = LinearLayoutManager(b.root.context, LinearLayoutManager.HORIZONTAL, false)
            b.rvPrograms.adapter = programAdapter
            b.rvPrograms.addItemDecoration(GapDecoration(programAdapter))
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
        val programs = programsMap[ch.streamId].orEmpty()
        holder.programAdapter.submitPrograms(programs)
        holder.b.rvPrograms.invalidateItemDecorations()

        // Only the very first time real data shows up do we snap the shared scroll
        // position to "now" — later periodic refreshes must not yank the user's
        // current scroll position back.
        if (!initialScrollSet && programs.isNotEmpty()) {
            val dpPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TV_EPG_DP_PER_MIN, holder.b.root.context.resources.displayMetrics)
            sharedScrollX = (TV_EPG_HOURS_BACK * 60 * dpPx).toInt()
            initialScrollSet = true
        }

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
    private val context: Context,
    private val onClick: () -> Unit
) : RecyclerView.Adapter<TvEpgProgramAdapter.VH>() {

    private var programs: List<EpgEntity> = emptyList()
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun submitPrograms(list: List<EpgEntity>) {
        programs = list
        notifyDataSetChanged()
    }

    /** Pixel width of the empty space before the first program block, so that every row's
     * item 0 starts at the same absolute point on the shared timeline — even when one
     * channel has more history fetched than another. */
    fun computeLeadingGapPx(timelineStartMs: Long): Int {
        val first = programs.firstOrNull() ?: return 0
        val dpPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TV_EPG_DP_PER_MIN, context.resources.displayMetrics)
        val firstStartMs = toMs(first.startTimestamp)
        val gapMin = ((firstStartMs - timelineStartMs) / 60_000f).coerceAtLeast(0f)
        return (gapMin * dpPx).toInt()
    }

    private fun toMs(ts: Long) = if (ts < 100_000_000_000L) ts * 1000L else ts

    inner class VH(val b: ItemTvEpgProgramBlockBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            // Explicit, position-based focus movement: Android's default focus finder
            // can't locate off-screen/unrecycled neighbors once a row is scrolled deep,
            // which made LEFT/RIGHT stop responding. Stepping by adapter position and
            // asking RecyclerView to scroll to it is reliable regardless of what's
            // currently attached.
            b.root.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val rv = b.root.parent as? RecyclerView ?: return@setOnKeyListener false
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { moveFocusTo(rv, pos + 1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (pos == 0) false // let the Activity handle exiting to the channel list
                        else { moveFocusTo(rv, pos - 1); true }
                    }
                    else -> false
                }
            }
        }
    }

    private fun moveFocusTo(rv: RecyclerView, target: Int) {
        if (target < 0 || target >= itemCount) return
        val holder = rv.findViewHolderForAdapterPosition(target)
        if (holder != null) {
            holder.itemView.requestFocus()
        } else {
            rv.scrollToPosition(target)
            rv.post { rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
    }

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
