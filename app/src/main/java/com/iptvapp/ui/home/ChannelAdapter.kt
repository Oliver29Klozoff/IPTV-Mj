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

    // A periodic EPG refresh (every 30s on TV) recomputes progress for every visible channel,
    // and since progress % changes almost every tick, it used to rebind nearly every row on
    // every refresh — including whichever one the user happened to be mid-long-press on right
    // then. Rebinding calls setOnLongClickListener again, which can disrupt Android's pending
    // long-press callback for that gesture, making "hold to open channel actions" intermittently
    // just not fire depending on unlucky timing. Track whichever row currently has a pointer
    // down and skip rebinding it until released.
    private var pressedStreamId: Int? = null

    // Targeted per-row notifications (never notifyDataSetChanged, and not even a blanket
    // notifyItemRangeChanged across the whole list) — these EPG/health maps refresh on a
    // timer, and rebinding a row re-triggers its Glide load and resets its view state.
    // Rebinding every row on every tick — including whichever one currently has D-pad
    // focus, even when that channel's own value didn't change — was causing a visible
    // flicker on the focused row every refresh. Only notify rows whose value actually changed.
    private inline fun notifyChangedRows(changed: (streamId: Int) -> Boolean) {
        currentList.forEachIndexed { index, channel ->
            if (channel.streamId != pressedStreamId && changed(channel.streamId)) notifyItemChanged(index)
        }
    }

    fun setCurrentlyPlayingStreamId(streamId: Int) {
        val old = currentlyPlayingStreamId
        currentlyPlayingStreamId = streamId
        notifyChangedRows { it == old || it == streamId }
    }

    fun submitEpgText(epgMap: Map<Int, String>) {
        val old = epgTextByStreamId
        epgTextByStreamId = epgMap
        notifyChangedRows { old[it] != epgMap[it] }
    }

    fun submitEpgNextText(nextMap: Map<Int, String>) {
        val old = epgNextTextByStreamId
        epgNextTextByStreamId = nextMap
        notifyChangedRows { old[it] != nextMap[it] }
    }

    fun submitEpgProgress(progressMap: Map<Int, Int>) {
        val old = epgProgressByStreamId
        epgProgressByStreamId = progressMap
        notifyChangedRows { old[it] != progressMap[it] }
    }

    fun submitHealth(healthMap: Map<Int, Boolean?>) {
        val old = healthByStreamId
        healthByStreamId = healthMap
        notifyChangedRows { old[it] != healthMap[it] }
    }

    // Bulk-select (phone only, for moving channels into a favorite folder) previously had no
    // visual indication at all of which rows were selected — cancelling the resulting dialog
    // then looked identical to selection having silently reset, even though it hadn't.
    private var bulkSelectedIds: Set<Int> = emptySet()
    private var bulkSelectMode: Boolean = false

    fun submitBulkSelection(ids: Set<Int>) {
        val old = bulkSelectedIds
        val oldMode = bulkSelectMode
        bulkSelectedIds = ids
        bulkSelectMode = ids.isNotEmpty()
        if (oldMode != bulkSelectMode) {
            notifyItemRangeChanged(0, itemCount)
        } else {
            notifyChangedRows { old.contains(it) != ids.contains(it) }
        }
    }

    inner class ViewHolder(val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // Must survive across rebinds (a click triggers an EPG/health refresh that rebinds
        // this holder almost immediately) — a local var inside bind() reset to 0 every time,
        // which meant the second click of a double-click was never within the window.
        private var lastClickTime = 0L

        fun bind(item: ChannelEntity) {
            binding.tvChannelName.text = item.name
            val quality = com.iptvapp.util.ChannelQualityTag.labelFor(item.name)
            binding.tvQualityBadge?.apply {
                visibility = if (quality != null) View.VISIBLE else View.GONE
                text = quality ?: ""
            }
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
                // D-pad right from the row moves focus onto the star so OK favorites directly,
                // instead of requiring a held-OK long-press to reach the actions menu. Left (or
                // Back) from the star returns focus to the row for normal up/down navigation.
                binding.root.isFocusable = true
                binding.ivFavorite.isFocusable = true
                binding.root.nextFocusRightId = binding.ivFavorite.id
                binding.ivFavorite.nextFocusLeftId = binding.root.id
            } else {
                binding.root.onFocusChangeListener = null
                binding.ivFavorite.isFocusable = false
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
            // A real checkbox on every row while bulk mode is active — makes it obvious every
            // row is selectable, not just that the tapped one changed (a repurposed star icon
            // only showing state on already-selected rows gave no such at-a-glance affordance).
            if (bulkSelectMode) {
                binding.cbBulkSelect?.visibility = View.VISIBLE
                binding.cbBulkSelect?.isChecked = bulkSelectedIds.contains(item.streamId)
                binding.root.setBackgroundColor(
                    if (bulkSelectedIds.contains(item.streamId)) 0x33008CFF else 0x00000000
                )
            } else {
                binding.cbBulkSelect?.visibility = View.GONE
                binding.root.setBackgroundResource(com.iptvapp.R.drawable.focus_selector)
            }
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

            @SuppressLint("ClickableViewAccessibility")
            binding.root.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> pressedStreamId = item.streamId
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        if (pressedStreamId == item.streamId) pressedStreamId = null
                }
                v.onTouchEvent(event)
            }

            // TV long-press is a held D-pad OK/Enter key, not a touch gesture — needs the
            // same pressed-row guard via key events instead of MotionEvent.
            if (isTvMode) {
                binding.root.setOnKeyListener { v, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                        when (event.action) {
                            android.view.KeyEvent.ACTION_DOWN -> pressedStreamId = item.streamId
                            android.view.KeyEvent.ACTION_UP ->
                                if (pressedStreamId == item.streamId) pressedStreamId = null
                        }
                    }
                    false
                }
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