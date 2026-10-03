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
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.util.RackAccent

/** The player's Quick Zap strip (v6.93): favorite channels as cards with the show on now and its
 * progress; the playing one is outlined in the accent. Replaces the side drawer's channel list. */
class QuickZapAdapter(
    private val onChannelClick: (ChannelEntity) -> Unit
) : RecyclerView.Adapter<QuickZapAdapter.ViewHolder>() {

    /** What's on: title and progress 0-100 per streamId. */
    data class NowShowing(val title: String, val progress: Int)

    private var channels: List<ChannelEntity> = emptyList()
    private var now: Map<Int, NowShowing> = emptyMap()
    private var playingStreamId: Int = -1
    private var accent = RackAccent.Accent(0xFF06B6D4.toInt(), null)

    fun submit(list: List<ChannelEntity>, nowShowing: Map<Int, NowShowing>, playing: Int) {
        channels = list
        now = nowShowing
        playingStreamId = playing
        notifyDataSetChanged()
    }

    fun setAccent(value: RackAccent.Accent) {
        accent = value
        notifyDataSetChanged()
    }

    fun positionOf(streamId: Int) = channels.indexOfFirst { it.streamId == streamId }

    override fun getItemCount() = channels.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_quick_zap, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(channels[position])

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val logo: ImageView = view.findViewById(R.id.ivZapLogo)
        private val num: TextView = view.findViewById(R.id.tvZapNum)
        private val name: TextView = view.findViewById(R.id.tvZapName)
        private val show: TextView = view.findViewById(R.id.tvZapShow)
        private val progress: ProgressBar = view.findViewById(R.id.zapProgress)

        fun bind(ch: ChannelEntity) {
            val ctx = itemView.context
            val playing = ch.streamId == playingStreamId
            num.text = ch.num.takeIf { it > 0 }?.toString() ?: ""
            name.text = ch.name
            val n = now[ch.streamId]
            show.text = if (playing) "● Playing" + (n?.let { " · ${it.title}" } ?: "") else n?.title ?: ""
            if (playing) com.iptvapp.util.AccentText.apply(show, accent.start, accent.end)
            else com.iptvapp.util.AccentText.plain(show, ctx.getColor(R.color.rack_text_secondary))
            progress.progress = n?.progress ?: 0
            progress.visibility = if (n != null) View.VISIBLE else View.INVISIBLE
            RackAccent.paintProgress(progress, accent)
            Glide.with(ctx).load(ch.streamIcon).into(logo)

            val d = ctx.resources.displayMetrics.density
            fun card(fill: Int, stroke: Int, strokeDp: Float) = GradientDrawable().apply {
                cornerRadius = 4 * d
                setColor(fill)
                setStroke((strokeDp * d + 0.5f).toInt(), stroke)
            }
            itemView.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),
                    card(ctx.getColor(R.color.rack_focus_fill), ctx.getColor(R.color.rack_focus_ring), 2f))
                addState(intArrayOf(),
                    if (playing) card(ctx.getColor(R.color.rack_control_off), accent.start, 2f)
                    else card(0xE60D0D0D.toInt(), ctx.getColor(R.color.rack_border), 1f))
            }
            itemView.setOnClickListener { onChannelClick(ch) }
        }
    }
}
