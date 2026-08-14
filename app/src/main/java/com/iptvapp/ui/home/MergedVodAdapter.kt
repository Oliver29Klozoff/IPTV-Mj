package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.MergedVodEntity
import com.iptvapp.databinding.ItemMergedVodBinding
import com.iptvapp.databinding.ItemSectionHeaderBinding

// A "★ Favorites" header now separates favorited titles from the rest of the list, same
// treatment as the primary Movies tab's VodAdapter/VodRow — previously favorites just floated
// silently to the top of the flat list with nothing marking them as such, and merged Movies had
// no sort concept at all (see HomeViewModel.applyMergedVodSort).
sealed class MergedVodRow {
    data class Header(val title: String) : MergedVodRow()
    data class Item(val vod: MergedVodEntity) : MergedVodRow()
}

// Movies-tab equivalent of MergedChannelAdapter — see MergedVodEntity kdoc.
class MergedVodAdapter(
    private val onItemClick: (MergedVodEntity) -> Unit,
    private val onFavoriteClick: (MergedVodEntity) -> Unit = {},
    private val onItemLongClick: (MergedVodEntity) -> Unit = {},
    // Feature A: see VodAdapter's matching parameter kdoc — null means preview unsupported here.
    private val vodPreviewUrlProvider: (suspend (MergedVodEntity) -> String?)? = null
) : ListAdapter<MergedVodRow, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    // Same D-pad-reachable-star pattern as MergedChannelAdapter — without this, TV had no way
    // to reach the favorite star at all except a held-OK long-press into the actions menu.
    var isTvMode: Boolean = false

    /** Wraps a plain MergedVodEntity list, inserting a "★ Favorites" header before the leading
     * run of favorited titles when present — favorites-first ordering is still
     * HomeViewModel.applyMergedVodSort's job, this just labels it. */
    fun submitVodList(list: List<MergedVodEntity>) {
        val favoriteCount = list.takeWhile { it.isFavorite }.size
        val rows = buildList {
            if (favoriteCount > 0) {
                add(MergedVodRow.Header("★ Favorites"))
                addAll(list.take(favoriteCount).map { MergedVodRow.Item(it) })
                if (favoriteCount < list.size) add(MergedVodRow.Header("All Movies"))
            }
            addAll(list.drop(favoriteCount).map { MergedVodRow.Item(it) })
        }
        submitList(rows)
    }

    /** Plain list, no header, with a completion callback — TV's Providers Movies list (see
     * TvHomeActivity), which still needs the callback to restore D-pad focus after a resubmit.
     * The header row is a phone-only addition for now. */
    fun submitPlainList(list: List<MergedVodEntity>, commitCallback: () -> Unit) {
        submitList(list.map { MergedVodRow.Item(it) }, commitCallback)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is MergedVodRow.Header -> TYPE_HEADER
        is MergedVodRow.Item -> TYPE_ITEM
    }

    // Bulk-hide checkbox mode — same shape as MergedSeriesAdapter's bulk-select. Header rows are
    // never part of the selection (keyOf/bulkSelectedKeys only ever address Item rows).
    private var bulkSelectedKeys: Set<String> = emptySet()
    private var bulkSelectMode: Boolean = false
    private fun keyOf(item: MergedVodEntity) = "${item.serverIndex}:${item.streamId}"

    fun submitBulkSelection(keys: Set<String>) {
        bulkSelectedKeys = keys
        bulkSelectMode = keys.isNotEmpty()
        notifyDataSetChanged()
    }

    inner class HeaderViewHolder(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: MergedVodRow.Header) {
            binding.tvSectionHeader.text = row.title
        }
    }

    inner class ViewHolder(val binding: ItemMergedVodBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MergedVodEntity) {
            if (isTvMode) {
                binding.root.isFocusable = true
                binding.ivVodFavorite.isFocusable = true
                binding.root.nextFocusRightId = binding.ivVodFavorite.id
                binding.ivVodFavorite.nextFocusLeftId = binding.root.id
            } else {
                binding.ivVodFavorite.isFocusable = false
            }
            binding.tvVodName.text = item.name
            binding.tvVodRating.text = item.rating?.takeIf { it.isNotBlank() } ?: ""
            binding.tvServerNickname.text = item.serverNickname
            Glide.with(binding.ivVodPoster)
                .load(item.streamIcon)
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play)
                .into(binding.ivVodPoster)
            binding.ivVodFavorite.setImageResource(
                if (item.isFavorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
            )
            binding.ivVodFavorite.setColorFilter(
                if (item.isFavorite) android.graphics.Color.parseColor("#FFC107") else android.graphics.Color.parseColor("#555555")
            )
            if (item.watchedMs > 0 && item.durationMs > 0) {
                val pct = ((item.watchedMs * 100) / item.durationMs).coerceIn(0, 100).toInt()
                binding.progressMergedVod?.progress = pct
                binding.progressMergedVod?.visibility = android.view.View.VISIBLE
            } else {
                binding.progressMergedVod?.visibility = android.view.View.GONE
            }
            if (bulkSelectMode) {
                binding.cbVodBulkSelect?.visibility = android.view.View.VISIBLE
                binding.cbVodBulkSelect?.isChecked = bulkSelectedKeys.contains(keyOf(item))
                binding.root.setBackgroundColor(
                    if (bulkSelectedKeys.contains(keyOf(item))) 0x33008CFF else 0x00000000
                )
            } else {
                binding.cbVodBulkSelect?.visibility = android.view.View.GONE
                binding.root.setBackgroundResource(com.iptvapp.R.drawable.focus_selector)
            }
            binding.ivVodFavorite.setOnClickListener { onFavoriteClick(item) }
            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener { onItemLongClick(item); true }

            // Feature A: Auto-Generated VOD Trailers — see TilePreviewPlayer/VodAdapter kdoc.
            // Keyed "serverIndex:streamId" since plain streamId isn't unique across providers.
            val provider = vodPreviewUrlProvider
            if (provider != null) {
                binding.root.setOnFocusChangeListener { _, focused ->
                    val key = "${item.serverIndex}:${item.streamId}"
                    if (focused) {
                        TilePreviewPlayer.onTileFocused(binding.root.context, key, binding.playerVodPreview) {
                            provider(item)
                        }
                    } else {
                        TilePreviewPlayer.onTileUnfocused(key)
                    }
                }
            } else {
                binding.root.onFocusChangeListener = null
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(ItemSectionHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            ViewHolder(ItemMergedVodBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is MergedVodRow.Header -> (holder as HeaderViewHolder).bind(row)
            is MergedVodRow.Item -> (holder as ViewHolder).bind(row.vod)
        }
    }

    // See VodAdapter.onViewRecycled kdoc — same guarantee for this list's tiles.
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ViewHolder) {
            TilePreviewPlayer.releaseIfHolding(holder.binding.playerVodPreview)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<MergedVodRow>() {
        override fun areItemsTheSame(a: MergedVodRow, b: MergedVodRow): Boolean = when {
            a is MergedVodRow.Header && b is MergedVodRow.Header -> a.title == b.title
            a is MergedVodRow.Item && b is MergedVodRow.Item -> a.vod.serverIndex == b.vod.serverIndex && a.vod.streamId == b.vod.streamId
            else -> false
        }
        override fun areContentsTheSame(a: MergedVodRow, b: MergedVodRow): Boolean = a == b
    }
}
