package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.MergedSeriesEntity
import com.iptvapp.databinding.ItemMergedSeriesBinding
import com.iptvapp.databinding.ItemSectionHeaderBinding

// A "★ Favorites" header separates favorited shows from the rest of the list, same treatment as
// MergedVodAdapter/MergedVodRow — previously favorites just floated silently to the top of the
// flat list with nothing marking them as such.
sealed class MergedSeriesRow {
    data class Header(val title: String) : MergedSeriesRow()
    data class Item(val series: MergedSeriesEntity) : MergedSeriesRow()
}

// Series-tab equivalent of MergedVodAdapter — see MergedSeriesEntity kdoc. Tapping a row opens
// SeriesDetailActivity (season/episode picker), unlike merged VOD which plays directly — a
// series item is never itself a single playable stream.
class MergedSeriesAdapter(
    private val onItemClick: (MergedSeriesEntity) -> Unit,
    private val onFavoriteClick: (MergedSeriesEntity) -> Unit = {},
    private val onItemLongClick: (MergedSeriesEntity) -> Unit = {}
) : ListAdapter<MergedSeriesRow, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    // Same D-pad-reachable-star pattern as MergedChannelAdapter/MergedVodAdapter.
    var isTvMode: Boolean = false

    /** Wraps a plain MergedSeriesEntity list, inserting a "★ Favorites" header before the
     * leading run of favorited shows when present — favorites-first ordering is still
     * HomeViewModel's job, this just labels it. */
    fun submitSeriesList(list: List<MergedSeriesEntity>) {
        val favoriteCount = list.takeWhile { it.isFavorite }.size
        val rows = buildList {
            if (favoriteCount > 0) {
                add(MergedSeriesRow.Header("★ Favorites"))
                addAll(list.take(favoriteCount).map { MergedSeriesRow.Item(it) })
                if (favoriteCount < list.size) add(MergedSeriesRow.Header("All Series"))
            }
            addAll(list.drop(favoriteCount).map { MergedSeriesRow.Item(it) })
        }
        submitList(rows)
    }

    /** Plain list, no header — used by TV's Providers Series list (see TvHomeActivity), which
     * is on hold for the header treatment for now. */
    fun submitPlainList(list: List<MergedSeriesEntity>) {
        submitList(list.map { MergedSeriesRow.Item(it) })
    }

    /** Plain list, no header, with a completion callback — TV still needs the callback to
     * restore D-pad focus after a resubmit. */
    fun submitPlainList(list: List<MergedSeriesEntity>, commitCallback: () -> Unit) {
        submitList(list.map { MergedSeriesRow.Item(it) }, commitCallback)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is MergedSeriesRow.Header -> TYPE_HEADER
        is MergedSeriesRow.Item -> TYPE_ITEM
    }

    // Bulk-hide checkbox mode — same shape as MergedChannelAdapter's bulk-select. Header rows
    // are never part of the selection.
    private var bulkSelectedKeys: Set<String> = emptySet()
    private var bulkSelectMode: Boolean = false
    private fun keyOf(item: MergedSeriesEntity) = "${item.serverIndex}:${item.seriesId}"

    fun submitBulkSelection(keys: Set<String>) {
        bulkSelectedKeys = keys
        bulkSelectMode = keys.isNotEmpty()
        notifyDataSetChanged()
    }

    inner class HeaderViewHolder(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: MergedSeriesRow.Header) {
            binding.tvSectionHeader.text = row.title
        }
    }

    inner class ViewHolder(val binding: ItemMergedSeriesBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MergedSeriesEntity) {
            if (isTvMode) {
                binding.root.isFocusable = true
                binding.ivSeriesFavorite.isFocusable = true
                binding.root.nextFocusRightId = binding.ivSeriesFavorite.id
                binding.ivSeriesFavorite.nextFocusLeftId = binding.root.id
            } else {
                binding.ivSeriesFavorite.isFocusable = false
            }
            binding.tvSeriesName.text = item.name
            binding.tvSeriesRating.text = item.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" } ?: ""
            binding.tvServerNickname.text = item.serverNickname
            Glide.with(binding.ivSeriesCover)
                .load(item.cover)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.ivSeriesCover)
            binding.ivSeriesFavorite.setImageResource(
                if (item.isFavorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
            )
            binding.ivSeriesFavorite.setColorFilter(
                if (item.isFavorite) android.graphics.Color.parseColor("#FFC107") else android.graphics.Color.parseColor("#555555")
            )
            if (bulkSelectMode) {
                binding.cbSeriesBulkSelect?.visibility = android.view.View.VISIBLE
                binding.cbSeriesBulkSelect?.isChecked = bulkSelectedKeys.contains(keyOf(item))
                binding.root.setBackgroundColor(
                    if (bulkSelectedKeys.contains(keyOf(item))) 0x33008CFF else 0x00000000
                )
            } else {
                binding.cbSeriesBulkSelect?.visibility = android.view.View.GONE
                binding.root.setBackgroundResource(com.iptvapp.R.drawable.focus_selector)
            }
            binding.ivSeriesFavorite.setOnClickListener { onFavoriteClick(item) }
            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener { onItemLongClick(item); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(ItemSectionHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            ViewHolder(ItemMergedSeriesBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is MergedSeriesRow.Header -> (holder as HeaderViewHolder).bind(row)
            is MergedSeriesRow.Item -> (holder as ViewHolder).bind(row.series)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<MergedSeriesRow>() {
        override fun areItemsTheSame(a: MergedSeriesRow, b: MergedSeriesRow): Boolean = when {
            a is MergedSeriesRow.Header && b is MergedSeriesRow.Header -> a.title == b.title
            a is MergedSeriesRow.Item && b is MergedSeriesRow.Item -> a.series.serverIndex == b.series.serverIndex && a.series.seriesId == b.series.seriesId
            else -> false
        }
        override fun areContentsTheSame(a: MergedSeriesRow, b: MergedSeriesRow): Boolean = a == b
    }
}
