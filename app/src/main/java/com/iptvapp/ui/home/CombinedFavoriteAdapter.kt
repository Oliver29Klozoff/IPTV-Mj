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

// Distinct favorite-star color per provider, so a mixed Favorites list reads at a glance which
// channels came from where without needing to read the small server-nickname tag. Primary stays
// the existing app-wide favorite blue; each other configured server gets a color from this list,
// assigned deterministically by serverIndex (cycling if there are ever more providers than
// colors — an edge case, not worth a dynamic-palette system for).
object FavoriteStarColors {
    const val PRIMARY = "#008CFF"
    private val OTHER_PROVIDERS = listOf("#FFC107", "#FF4444", "#4CAF50", "#AB47BC", "#FF8A00", "#26C6DA")

    fun forServerIndex(serverIndex: Int): String =
        if (serverIndex == -1) PRIMARY else OTHER_PROVIDERS[serverIndex.mod(OTHER_PROVIDERS.size)]
}

// Shared "PROVIDER · CATEGORY" tag builder for tvServerNickname across LiveChannelAdapter,
// CombinedFavoriteAdapter, and MergedChannelAdapter — a channel row previously only showed which
// provider it came from; the category makes it clear at a glance where in that provider's
// listing it lives too, without needing to re-open the category picker to check.
object ProviderTag {
    fun format(serverNickname: String?, categoryName: String?): String? = when {
        serverNickname != null && categoryName != null -> "$serverNickname · $categoryName"
        serverNickname != null -> serverNickname
        categoryName != null -> categoryName
        else -> null
    }
}

// Favorites-tab-only adapter over the display union CombinedFavorite (primary + Providers-tab
// favorites shown together). Ported from ChannelAdapter's Glide/health-dot/double-click/
// pressed-row-guard/bulk-select logic rather than sharing a base class, since some ChannelAdapter
// features don't apply here: no EPG progress bar (merged channels have no locally cached EPG
// entries to compute progress from — only the short now/next text fetched per-row). Drag-reorder
// (see showDragHandles/itemTouchHelper) restores the manual-order feature Favorites lost when it
// became a combined primary+merged list — favOrder now lives on both underlying tables (see
// MergedChannelEntity.favOrder kdoc) so a drag can freely mix channels from any provider.
class CombinedFavoriteAdapter(
    private val onChannelClick: (CombinedFavorite) -> Unit,
    private val onChannelDoubleClick: (CombinedFavorite) -> Unit = {},
    private val onFavoriteClick: (CombinedFavorite) -> Unit,
    private val onChannelLongClick: ((CombinedFavorite) -> Unit)? = null
) : ListAdapter<CombinedFavorite, CombinedFavoriteAdapter.ViewHolder>(DiffCallback()) {

    var isTvMode: Boolean = false
    var onChannelFocused: ((CombinedFavorite) -> Unit)? = null

    // Reorder mode — an ItemTouchHelper drives drag gestures started from the handle, moving
    // rows directly via notifyItemMoved rather than going through ListAdapter's submitList/
    // DiffUtil path, since diffing against a list that's changing every frame of the drag would
    // fight the drag gesture instead of just reflecting it. currentList itself IS mutated here
    // (via a private mutable copy), which is safe as long as nothing else calls submitList while
    // reorder mode is active (see HomeActivity's reorder start/stop, which enforces exactly that).
    var itemTouchHelper: androidx.recyclerview.widget.ItemTouchHelper? = null
    var showDragHandles: Boolean = false
    private var workingList: MutableList<CombinedFavorite> = mutableListOf()

    // Reorder-mode-only checkbox selection — separate set from bulkSelectedIds (the Remove-from-
    // Favorites bulk-select), since the two modes are mutually exclusive but share the checkbox
    // visual. Checking rows marks them to move TOGETHER as one block on the next drag; dragging
    // an unchecked row still just moves that single row, same as before this existed.
    private var reorderSelectedIds: Set<String> = emptySet()

    fun beginReorder() {
        workingList = currentList.toMutableList()
        reorderSelectedIds = emptySet()
    }

    fun toggleReorderSelection(id: String) {
        reorderSelectedIds = if (id in reorderSelectedIds) reorderSelectedIds - id else reorderSelectedIds + id
        val pos = workingList.indexOfFirst { it.id == id }
        if (pos >= 0) notifyItemChanged(pos)
    }

    /** Current on-screen order once reorder mode ends — the caller persists this as the new
     * favOrder sequence (see HomeActivity's exitReorderMode). */
    fun currentOrder(): List<CombinedFavorite> = workingList.ifEmpty { currentList }

    // Live drag always moves just the ONE row being dragged, via plain notifyItemMoved — trying
    // to reshuffle a whole checked group DURING an active ItemTouchHelper gesture fought its own
    // drag-state tracking (the dragged ViewHolder got invalidated out from under the gesture,
    // which is what made rows appear to vanish mid-drag). The checked group is moved together as
    // one block only once the drag finishes — see finishGroupMoveIfNeeded, called from
    // FavoritesReorderCallback.clearView.
    private var lastDropPosition: Int = -1

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val item = workingList.removeAt(fromPosition)
        workingList.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
        lastDropPosition = toPosition
    }

    /** Called once the drag gesture ends (finger lifted) — if the row that was actually dragged
     * is part of a multi-row checked group, pulls the rest of that group out and reinserts it
     * as one contiguous block right where the dragged row ended up, preserving the group's own
     * relative order. A single notifyDataSetChanged here (after the gesture is already over) is
     * safe, unlike doing this mid-drag. */
    fun finishGroupMoveIfNeeded() {
        val dropPosition = lastDropPosition
        lastDropPosition = -1
        if (dropPosition < 0 || dropPosition >= workingList.size) return
        val draggedId = workingList[dropPosition].id
        if (reorderSelectedIds.size <= 1 || draggedId !in reorderSelectedIds) return
        // The dragged row's own id is always excluded from "remainder" below (it's part of the
        // group being pulled out), so it can't be used as the anchor to reinsert next to. Anchor
        // on whichever non-checked row ends up adjacent to the drop slot instead: prefer the next
        // one after it (insert the group right before that row); if the drop landed at/past the
        // last non-checked row, fall back to the previous one (insert the group right after it).
        val group = workingList.filter { it.id in reorderSelectedIds }
        val remainder = workingList.filterNot { it.id in reorderSelectedIds }
        val nextAnchorId = workingList.drop(dropPosition + 1).firstOrNull { it.id !in reorderSelectedIds }?.id
        val insertAt = if (nextAnchorId != null) {
            remainder.indexOfFirst { it.id == nextAnchorId }.let { if (it < 0) remainder.size else it }
        } else {
            val prevAnchorId = workingList.take(dropPosition).lastOrNull { it.id !in reorderSelectedIds }?.id
            if (prevAnchorId == null) remainder.size
            else remainder.indexOfFirst { it.id == prevAnchorId }.let { if (it < 0) remainder.size else it + 1 }
        }
        workingList = (remainder.subList(0, insertAt) + group + remainder.subList(insertAt, remainder.size)).toMutableList()
        notifyDataSetChanged()
    }

    private var epgTextById: Map<String, String> = emptyMap()
    private var epgNextTextById: Map<String, String> = emptyMap()
    private var currentlyPlayingId: String? = null
    private var healthById: Map<String, Boolean?> = emptyMap()

    // Same rebind-mid-long-press guard as ChannelAdapter — see its kdoc for why this exists.
    private var pressedId: String? = null

    // Bulk-select for removing favorites in one pass — same shape as ChannelAdapter's, keyed by
    // CombinedFavorite.id ("primary:$streamId" or "$serverIndex:$streamId") since this tab mixes
    // primary and merged-provider favorites in one list.
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

    inner class ViewHolder(val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var lastClickTime = 0L

        fun bind(item: CombinedFavorite) {
            binding.tvChannelName.text = item.name
            val quality = com.iptvapp.util.ChannelQualityTag.labelFor(item.name)
            binding.tvQualityBadge?.apply {
                visibility = if (quality != null) View.VISIBLE else View.GONE
                text = quality ?: ""
            }
            binding.tvEpgNow.text = epgTextById[item.id] ?: "Guide loading..."

            val nextText = epgNextTextById[item.id]
            if (isTvMode && nextText != null) {
                binding.tvEpgNext?.text = "Next: $nextText"
            } else {
                binding.tvEpgNext?.visibility = View.GONE
            }

            if (item.serverNickname != null) {
                binding.tvServerNickname?.text = ProviderTag.format(item.serverNickname, item.categoryName)
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
                // D-pad right from the row moves focus onto the star so OK favorites/unfavorites
                // directly, instead of requiring a held-OK long-press to reach the actions menu.
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

            val isFavorite = when (item) {
                is CombinedFavorite.Primary -> item.channel.isFavorite
                is CombinedFavorite.Merged -> item.channel.isFavorite
            }
            val serverIndex = when (item) {
                is CombinedFavorite.Primary -> -1
                is CombinedFavorite.Merged -> item.channel.serverIndex
            }
            binding.ivFavorite.setImageResource(
                if (isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            binding.ivFavorite.setColorFilter(
                if (isFavorite) android.graphics.Color.parseColor(FavoriteStarColors.forServerIndex(serverIndex))
                else android.graphics.Color.parseColor("#444444")
            )

            binding.root.isSelected = item.id == currentlyPlayingId
            if (showDragHandles) {
                // Reorder mode's own checkbox selection (reorderSelectedIds) — checking several
                // rows marks them to drag together as one block (see moveItem/moveGroupTo).
                binding.cbBulkSelect?.visibility = View.VISIBLE
                binding.cbBulkSelect?.isChecked = item.id in reorderSelectedIds
                binding.root.setBackgroundColor(
                    if (item.id in reorderSelectedIds) 0x33008CFF else 0x00000000
                )
            } else if (bulkSelectMode) {
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
                if (showDragHandles) {
                    toggleReorderSelection(item.id)
                    return@setOnClickListener
                }
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

    // While reordering, rows come from workingList (mutated directly by moveItem, bypassing
    // DiffUtil — see the class kdoc for why) instead of ListAdapter's own submitList-managed list.
    override fun getItemCount(): Int = if (showDragHandles) workingList.size else super.getItemCount()

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(if (showDragHandles) workingList[position] else getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<CombinedFavorite>() {
        override fun areItemsTheSame(a: CombinedFavorite, b: CombinedFavorite): Boolean = a.id == b.id
        override fun areContentsTheSame(a: CombinedFavorite, b: CombinedFavorite): Boolean = a == b
    }
}
