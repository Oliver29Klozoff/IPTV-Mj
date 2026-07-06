package com.iptvapp.ui.home

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onChannelClick: (ChannelEntity) -> Unit,
    private val onChannelDoubleClick: (ChannelEntity) -> Unit = {},
    private val onFavoriteClick: (ChannelEntity) -> Unit,
    private val onChannelLongClick: ((ChannelEntity) -> Unit)? = null
) : ListAdapter<ChannelEntity, ChannelAdapter.ViewHolder>(DiffCallback()) {

    var itemTouchHelper: ItemTouchHelper? = null
    var showDragHandles: Boolean = false
    var isTvMode: Boolean = false
    var onChannelFocused: ((ChannelEntity) -> Unit)? = null

    private var epgTextByStreamId: Map<Int, String> = emptyMap()
    private var epgNextTextByStreamId: Map<Int, String> = emptyMap()
    private var epgProgressByStreamId: Map<Int, Int> = emptyMap()
    private var currentlyPlayingStreamId: Int = -1
    private var healthByStreamId: Map<Int, Boolean?> = emptyMap()

    // Targeted range-changed notifications (not notifyDataSetChanged) so RecyclerView
    // rebinds existing views in place instead of detaching/reattaching them — a full
    // invalidate was knocking D-pad focus off the channel item and onto whatever
    // focusable view sits above the list (e.g. the back button).
    fun setCurrentlyPlayingStreamId(streamId: Int) {
        currentlyPlayingStreamId = streamId
        notifyItemRangeChanged(0, itemCount)
    }

    fun submitEpgText(epgMap: Map<Int, String>) {
        epgTextByStreamId = epgMap
        notifyItemRangeChanged(0, itemCount)
    }

    fun submitEpgNextText(nextMap: Map<Int, String>) {
        epgNextTextByStreamId = nextMap
        notifyItemRangeChanged(0, itemCount)
    }

    fun submitEpgProgress(progressMap: Map<Int, Int>) {
        epgProgressByStreamId = progressMap
        notifyItemRangeChanged(0, itemCount)
    }

    fun submitHealth(healthMap: Map<Int, Boolean?>) {
        healthByStreamId = healthMap
        notifyItemRangeChanged(0, itemCount)
    }

    inner class ViewHolder(val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // Must survive across rebinds (a click triggers an EPG/health refresh that rebinds
        // this holder almost immediately) — a local var inside bind() reset to 0 every time,
        // which meant the second click of a double-click was never within the window.
        private var lastClickTime = 0L

        fun bind(item: ChannelEntity) {
            binding.tvChannelName.text = item.name
            binding.tvEpgNow.text = epgTextByStreamId[item.streamId] ?: "Guide loading..."

            val nextText = epgNextTextByStreamId[item.streamId]
            if (isTvMode && nextText != null) {
                binding.tvEpgNext?.text = "Next: $nextText"
            } else {
                binding.tvEpgNext?.visibility = View.GONE
            }

            if (isTvMode) {
                binding.root.setOnFocusChangeListener { _, focused ->
                    if (focused) {
                        if (nextText != null) binding.tvEpgNext?.visibility = View.VISIBLE
                        onChannelFocused?.invoke(item)
                    } else {
                        binding.tvEpgNext?.visibility = View.GONE
                    }
                }
            } else {
                binding.root.onFocusChangeListener = null
            }

            val progress = epgProgressByStreamId[item.streamId] ?: 0
            binding.epgProgressBar.visibility = if (progress > 0) View.VISIBLE else View.INVISIBLE
            binding.epgProgressBar.progress = progress

            Glide.with(binding.ivChannelLogo)
                .load(item.streamIcon)
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play)
                .into(binding.ivChannelLogo)

            binding.ivFavorite.setImageResource(
                if (item.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            binding.ivFavorite.setColorFilter(
                if (item.isFavorite) android.graphics.Color.parseColor("#008CFF")
                else android.graphics.Color.parseColor("#444444")
            )

            binding.root.isSelected = item.streamId == currentlyPlayingStreamId
            binding.root.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastClickTime < 400) {
                    onChannelDoubleClick(item)
                } else {
                    onChannelClick(item)
                }
                lastClickTime = now
            }

            binding.root.setOnLongClickListener {
                if (onChannelLongClick != null) {
                    onChannelLongClick.invoke(item)
                } else {
                    onFavoriteClick(item)
                }
                true
            }

            binding.ivFavorite.setOnClickListener {
                onFavoriteClick(item)
            }

            val health = healthByStreamId[item.streamId]
            if (health != null || healthByStreamId.containsKey(item.streamId)) {
                binding.viewHealthDot?.visibility = View.VISIBLE
                val dotColor = when (health) {
                    true  -> android.graphics.Color.parseColor("#00CC66")
                    false -> android.graphics.Color.parseColor("#FF4444")
                    null  -> android.graphics.Color.parseColor("#888888")
                }
                (binding.viewHealthDot?.background as? android.graphics.drawable.GradientDrawable)?.setColor(dotColor)
            } else {
                binding.viewHealthDot?.visibility = View.GONE
            }

            binding.ivDragHandle?.visibility = if (showDragHandles) View.VISIBLE else View.GONE
            @SuppressLint("ClickableViewAccessibility")
            binding.ivDragHandle?.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper?.startDrag(this)
                }
                false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<ChannelEntity>() {
        override fun areItemsTheSame(a: ChannelEntity, b: ChannelEntity): Boolean =
            a.streamId == b.streamId

        override fun areContentsTheSame(a: ChannelEntity, b: ChannelEntity): Boolean =
            a == b
    }
}