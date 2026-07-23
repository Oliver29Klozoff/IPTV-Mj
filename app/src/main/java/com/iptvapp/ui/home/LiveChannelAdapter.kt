package com.iptvapp.ui.home

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.databinding.ItemChannelBinding

// Combined Live-tab channel list adapter — ported from CombinedFavoriteAdapter almost verbatim
// (same reasoning: no drag-reorder/bulk-select/EPG-progress-bar for merged rows), but for
// LiveChannelRow instead of CombinedFavorite, since Live now merges the primary provider with
// every configured secondary provider the same way Favorites already does.
class LiveChannelAdapter(
    private val onChannelClick: (LiveChannelRow) -> Unit,
    private val onChannelDoubleClick: (LiveChannelRow) -> Unit = {},
    private val onFavoriteClick: (LiveChannelRow) -> Unit,
    private val onChannelLongClick: ((LiveChannelRow) -> Unit)? = null
) : ListAdapter<LiveChannelRow, LiveChannelAdapter.ViewHolder>(DiffCallback()) {

    var isTvMode: Boolean = false
    var onChannelFocused: ((LiveChannelRow) -> Unit)? = null

    private var epgTextById: Map<String, String> = emptyMap()
    private var epgNextTextById: Map<String, String> = emptyMap()
    private var currentlyPlayingId: String? = null
    private var healthById: Map<String, Boolean?> = emptyMap()
    private var pressedId: String? = null

    private inline fun notifyChangedRows(changed: (id: String) -> Boolean) {
        currentList.forEachIndexed { index, item ->
            if (item.id != pressedId && changed(item.id)) notifyItemChanged(index)
        }
    }

    fun setCurrentlyPlayingId(id: String?) {
        val old = currentlyPlayingId
        currentlyPlayingId = id
        notifyChangedRows { it == old || it == id }
    }

    fun submitEpgText(epgMap: Map<String, String>) {
        val old = epgTextById
        epgTextById = epgMap
        notifyChangedRows { old[it] != epgMap[it] }
    }

    fun submitEpgNextText(nextMap: Map<String, String>) {
        val old = epgNextTextById
        epgNextTextById = nextMap
        notifyChangedRows { old[it] != nextMap[it] }
    }

    fun submitHealth(healthMap: Map<String, Boolean?>) {
        val old = healthById
        healthById = healthMap
        notifyChangedRows { old[it] != healthMap[it] }
    }

    // Bulk-select — a real checkbox on every row while active, keyed by LiveChannelRow.id
    // ("primary:$streamId" or "$serverIndex:$streamId"), covering primary AND merged channels
    // in this one combined list.
    private var bulkSelectedIds: Set<String> = emptySet()
    private var bulkSelectMode: Boolean = false

    fun submitBulkSelection(ids: Set<String>) {
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

        private var lastClickTime = 0L

        fun bind(item: LiveChannelRow) {
            binding.tvChannelName.text = item.name
            binding.tvEpgNow.text = epgTextById[item.id] ?: "Guide loading..."

            val nextText = epgNextTextById[item.id]
            if (isTvMode && nextText != null) {
                binding.tvEpgNext?.text = "Next: $nextText"
            } else {
                binding.tvEpgNext?.visibility = View.GONE
            }

            val nickname = item.mergedChannel?.serverNickname
            if (nickname != null) {
                binding.tvServerNickname?.text = nickname
                binding.tvServerNickname?.visibility = View.VISIBLE
            } else {
                binding.tvServerNickname?.visibility = View.GONE
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
                binding.root.isFocusable = true
                binding.ivFavorite.isFocusable = true
                binding.root.nextFocusRightId = binding.ivFavorite.id
                binding.ivFavorite.nextFocusLeftId = binding.root.id
            } else {
                binding.root.onFocusChangeListener = null
                binding.ivFavorite.isFocusable = false
            }

            binding.epgProgressBar?.visibility = View.INVISIBLE

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
                if (item.isFavorite) android.graphics.Color.parseColor(FavoriteStarColors.forServerIndex(item.serverIndex))
                else android.graphics.Color.parseColor("#444444")
            )

            binding.root.isSelected = item.id == currentlyPlayingId
            if (bulkSelectMode) {
                binding.cbBulkSelect?.visibility = View.VISIBLE
                binding.cbBulkSelect?.isChecked = bulkSelectedIds.contains(item.id)
                binding.root.setBackgroundColor(
                    if (bulkSelectedIds.contains(item.id)) 0x33008CFF else 0x00000000
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
                    MotionEvent.ACTION_DOWN -> pressedId = item.id
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        if (pressedId == item.id) pressedId = null
                }
                v.onTouchEvent(event)
            }

            if (isTvMode) {
                binding.root.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                        when (event.action) {
                            android.view.KeyEvent.ACTION_DOWN -> pressedId = item.id
                            android.view.KeyEvent.ACTION_UP ->
                                if (pressedId == item.id) pressedId = null
                        }
                    }
                    false
                }
            }

            binding.ivFavorite.setOnClickListener {
                onFavoriteClick(item)
            }

            val health = healthById[item.id]
            if (health != null || healthById.containsKey(item.id)) {
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

            binding.ivDragHandle?.visibility = View.GONE
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

    class DiffCallback : DiffUtil.ItemCallback<LiveChannelRow>() {
        override fun areItemsTheSame(a: LiveChannelRow, b: LiveChannelRow): Boolean = a.id == b.id
        override fun areContentsTheSame(a: LiveChannelRow, b: LiveChannelRow): Boolean = a == b
    }
}
