package com.iptvapp.ui.player

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.R
import com.iptvapp.util.QuickPeek
import com.iptvapp.util.RackAccent
import java.text.DateFormat
import java.util.Date

/**
 * Quick Peek's channel rows (see util/QuickPeek). A list swap replaces everything at once (lists
 * run to tens of thousands of rows, and two lists share little), while guide and clock updates
 * rebind just the rows on screen with [PAYLOAD_GUIDE], so a tick never rebuilds the list.
 */
class QuickPeekAdapter(
    private val onPick: (QuickPeek.Row) -> Unit,
    private val onHighlight: (QuickPeek.Row, Int) -> Unit,
) : RecyclerView.Adapter<QuickPeekAdapter.ViewHolder>() {

    private var rows: List<QuickPeek.Row> = emptyList()
    private var guide: Map<QuickPeek.Key, QuickPeek.NowNext> = emptyMap()
    private var playing: QuickPeek.Key? = null
    private var accent = RackAccent.Accent(0xFF06B6D4.toInt(), null)
    var nowMs: Long = System.currentTimeMillis()
        private set

    init { setHasStableIds(true) }

    val items: List<QuickPeek.Row> get() = rows

    fun submit(list: List<QuickPeek.Row>, playingKey: QuickPeek.Key?) {
        rows = list
        playing = playingKey
        nowMs = System.currentTimeMillis()
        notifyDataSetChanged()
    }

    /** New guide entries or a clock tick: rebinds only the rows in [first]..[last]. */
    fun updateGuide(entries: Map<QuickPeek.Key, QuickPeek.NowNext>, first: Int, last: Int) {
        guide = entries
        nowMs = System.currentTimeMillis()
        if (first < 0 || last < first || rows.isEmpty()) return
        val to = last.coerceAtMost(rows.lastIndex)
        notifyItemRangeChanged(first, to - first + 1, PAYLOAD_GUIDE)
    }

    fun setPlaying(key: QuickPeek.Key?) {
        val old = playing
        playing = key
        if (old == key) return
        positionOf(old).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        positionOf(key).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    fun setAccent(value: RackAccent.Accent) {
        accent = value
        notifyDataSetChanged()
    }

    fun guideFor(key: QuickPeek.Key): QuickPeek.NowNext? = guide[key]

    fun positionOf(key: QuickPeek.Key?): Int = if (key == null) -1 else rows.indexOfFirst { it.key == key }

    override fun getItemCount() = rows.size

    // A channel's (provider, stream) pair: unique across providers, unlike the stream id alone.
    override fun getItemId(position: Int): Long {
        return QuickPeek.stableId(rows[position].key)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_quick_peek, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(rows[position], full = true)

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_GUIDE }) holder.bind(rows[position], full = false)
        else holder.bind(rows[position], full = true)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        Glide.with(holder.logo).clear(holder.logo)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.ivPeekLogo)
        private val num: TextView = view.findViewById(R.id.tvPeekNum)
        private val name: TextView = view.findViewById(R.id.tvPeekName)
        private val fav: TextView = view.findViewById(R.id.tvPeekFav)
        private val playingBadge: TextView = view.findViewById(R.id.tvPeekPlaying)
        private val now: TextView = view.findViewById(R.id.tvPeekNow)
        private val left: TextView = view.findViewById(R.id.tvPeekLeft)
        private val progress: ProgressBar = view.findViewById(R.id.peekProgress)
        private val next: TextView = view.findViewById(R.id.tvPeekNext)

        init {
            RackAccent.paintProgress(progress, accent)
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onPick(rows[pos])
            }
            view.setOnFocusChangeListener { _, hasFocus ->
                val pos = bindingAdapterPosition
                if (hasFocus && pos != RecyclerView.NO_POSITION) onHighlight(rows[pos], pos)
            }
        }

        fun bind(row: QuickPeek.Row, full: Boolean) {
            val ctx = itemView.context
            val isPlaying = row.key == playing
            if (full) {
                num.text = if (row.number > 0) row.number.toString() else ""
                name.text = row.name
                fav.visibility = if (row.favorite) View.VISIBLE else View.GONE
                playingBadge.visibility = if (isPlaying) View.VISIBLE else View.GONE
                playingBadge.setTextColor(accent.start)
                RackAccent.paintProgress(progress, accent)
                // Small and fixed: a provider's 1000px logo is decoded once at row size and cached.
                Glide.with(logo).load(row.logo).override(LOGO_W, LOGO_H).fitCenter().into(logo)
                itemView.background = background(isPlaying)
            }
            val entry = guide[row.key]
            val show = entry?.now
            val at = nowMs
            if (show != null) {
                now.text = show.title
                left.text = QuickPeek.remainingLabel(QuickPeek.minutesLeft(show.stopMs, at))
                left.visibility = View.VISIBLE
                progress.progress = QuickPeek.progress(show.startMs, show.stopMs, at)
                progress.visibility = View.VISIBLE
            } else {
                now.text = if (entry == null) "" else ctx.getString(R.string.quick_peek_no_guide)
                left.visibility = View.GONE
                progress.visibility = View.INVISIBLE
            }
            val after = entry?.next
            if (after != null) {
                next.text = ctx.getString(R.string.quick_peek_next, clock(after.startMs), after.title)
                next.visibility = View.VISIBLE
            } else {
                next.visibility = View.GONE
            }
            itemView.contentDescription = QuickPeek.describe(row, entry, isPlaying, at)
        }

        private fun background(isPlaying: Boolean): StateListDrawable {
            val ctx = itemView.context
            val d = ctx.resources.displayMetrics.density
            fun card(fill: Int, stroke: Int, strokeDp: Float) = GradientDrawable().apply {
                cornerRadius = 4 * d
                setColor(fill)
                setStroke((strokeDp * d + 0.5f).toInt(), stroke)
            }
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),
                    card(ctx.getColor(R.color.rack_focus_fill), ctx.getColor(R.color.rack_focus_ring), 2f))
                addState(intArrayOf(android.R.attr.state_pressed),
                    card(ctx.getColor(R.color.rack_pressed), ctx.getColor(R.color.rack_border), 1f))
                addState(intArrayOf(),
                    if (isPlaying) card(ctx.getColor(R.color.rack_control_off), accent.start, 2f)
                    else card(0x00000000, 0x00000000, 0f))
            }
        }
    }

    private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    fun clock(ms: Long): String = timeFormat.format(Date(ms))

    companion object {
        const val PAYLOAD_GUIDE = "guide"
        private const val LOGO_W = 120
        private const val LOGO_H = 76
    }
}
