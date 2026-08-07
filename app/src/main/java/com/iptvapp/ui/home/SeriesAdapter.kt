package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.SeriesEntity
import com.iptvapp.databinding.ItemSeriesBinding
import com.iptvapp.databinding.ItemSectionHeaderBinding

// A "★ Favorites" header separates favorited shows from the rest of the list, same treatment as
// the Movies tab's VodAdapter/VodRow — previously favorites just floated silently to the top of
// the flat list with nothing marking them as such.
sealed class SeriesRow {
    data class Header(val title: String) : SeriesRow()
    data class Item(val series: SeriesEntity) : SeriesRow()
}

class SeriesAdapter(
    private val onSeriesClick: (SeriesEntity) -> Unit,
    private val onFavoriteClick: (SeriesEntity) -> Unit = {},
    private val onSeriesLongClick: (SeriesEntity) -> Unit = {}
) : ListAdapter<SeriesRow, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    /** Wraps a plain SeriesEntity list, inserting a "★ Favorites" header before the leading run
     * of favorited shows when present — favorites-first ordering is still
     * HomeViewModel.applySeriesSort's job, this just labels it. */
    fun submitSeriesList(list: List<SeriesEntity>) {
        val favoriteCount = list.takeWhile { it.isFavorite }.size
        val rows = buildList {
            if (favoriteCount > 0) {
                add(SeriesRow.Header("★ Favorites"))
                addAll(list.take(favoriteCount).map { SeriesRow.Item(it) })
                if (favoriteCount < list.size) add(SeriesRow.Header("All Series"))
            }
            addAll(list.drop(favoriteCount).map { SeriesRow.Item(it) })
        }
        submitList(rows)
    }

    /** Plain list, no header — used by TV's Series list (see TvHomeActivity), which is on hold
     * for the header treatment for now. */
    fun submitPlainList(list: List<SeriesEntity>) {
        submitList(list.map { SeriesRow.Item(it) })
    }

    /** Plain list, no header, with a completion callback — TV's Providers/Series list still
     * needs the callback to restore D-pad focus after a resubmit. */
    fun submitPlainList(list: List<SeriesEntity>, commitCallback: () -> Unit) {
        submitList(list.map { SeriesRow.Item(it) }, commitCallback)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is SeriesRow.Header -> TYPE_HEADER
        is SeriesRow.Item -> TYPE_ITEM
    }

    // Bulk-hide checkbox mode — same shape as ChannelAdapter's bulk-select: a real checkbox on
    // every row while active, plain taps toggle instead of opening the series. Header rows are
    // never part of the selection.
    private var bulkSelectedIds: Set<Int> = emptySet()
    private var bulkSelectMode: Boolean = false

    fun submitBulkSelection(ids: Set<Int>) {
        bulkSelectedIds = ids
        bulkSelectMode = ids.isNotEmpty()
        notifyDataSetChanged()
    }

    inner class HeaderViewHolder(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: SeriesRow.Header) {
            binding.tvSectionHeader.text = row.title
        }
    }

    inner class ViewHolder(val binding: ItemSeriesBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SeriesEntity) {
            binding.tvSeriesName.text = item.name
            binding.tvSeriesGenre.text = item.genre ?: ""
            Glide.with(binding.ivSeriesCover)
                .load(item.cover)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.ivSeriesCover)
            binding.ivSeriesFavorite.setImageResource(
                if (item.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            // Same fixed-tint bug as VodAdapter — item_series.xml previously had a static
            // android:tint="#008CFF" that never reflected favorite state.
            binding.ivSeriesFavorite.setColorFilter(
                if (item.isFavorite) android.graphics.Color.parseColor("#FFC107")
                else android.graphics.Color.parseColor("#555555")
            )
            if (bulkSelectMode) {
                binding.cbSeriesBulkSelect?.visibility = android.view.View.VISIBLE
                binding.cbSeriesBulkSelect?.isChecked = bulkSelectedIds.contains(item.seriesId)
                binding.root.setBackgroundColor(
                    if (bulkSelectedIds.contains(item.seriesId)) 0x33008CFF else 0x00000000
                )
            } else {
                binding.cbSeriesBulkSelect?.visibility = android.view.View.GONE
                binding.root.setBackgroundResource(com.iptvapp.R.drawable.focus_selector)
            }
            binding.root.setOnClickListener { onSeriesClick(item) }
            binding.root.setOnLongClickListener { onSeriesLongClick(item); true }
            binding.ivSeriesFavorite.setOnClickListener { onFavoriteClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(ItemSectionHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            ViewHolder(ItemSeriesBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is SeriesRow.Header -> (holder as HeaderViewHolder).bind(row)
            is SeriesRow.Item -> (holder as ViewHolder).bind(row.series)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SeriesRow>() {
        override fun areItemsTheSame(a: SeriesRow, b: SeriesRow): Boolean = when {
            a is SeriesRow.Header && b is SeriesRow.Header -> a.title == b.title
            a is SeriesRow.Item && b is SeriesRow.Item -> a.series.seriesId == b.series.seriesId
            else -> false
        }
        override fun areContentsTheSame(a: SeriesRow, b: SeriesRow): Boolean = a == b
    }
}

// Netflix-style poster grid for TvHomeActivity's full-screen Series browse — exact mirror of
// VodAdapter.kt's TvVodPosterAdapter (see its own kdoc for why this is a separate adapter from
// the plain-list SeriesAdapter above rather than a second view type on it).
class TvSeriesPosterAdapter(
    private val onSeriesClick: (SeriesEntity) -> Unit,
    private val onSeriesLongClick: (SeriesEntity) -> Unit = {}
) : ListAdapter<SeriesEntity, TvSeriesPosterAdapter.ViewHolder>(PosterDiffCallback()) {

    inner class ViewHolder(val binding: com.iptvapp.databinding.ItemTvSeriesPosterBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SeriesEntity) {
            binding.tvSeriesName.text = item.name
            binding.tvSeriesRating.text = if (!item.rating.isNullOrBlank()) "★ ${item.rating}" else ""
            Glide.with(binding.ivSeriesPoster)
                .load(item.cover)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.ivSeriesPoster)
            binding.ivSeriesFavorite.visibility = if (item.isFavorite) android.view.View.VISIBLE else android.view.View.GONE
            if (item.watchedMs > 0 && item.durationMs > 0) {
                val pct = ((item.watchedMs * 100) / item.durationMs).coerceIn(0, 100).toInt()
                binding.progressSeries.progress = pct
                binding.progressSeries.visibility = android.view.View.VISIBLE
            } else {
                binding.progressSeries.visibility = android.view.View.GONE
            }
            binding.root.setOnClickListener { onSeriesClick(item) }
            binding.root.setOnLongClickListener { onSeriesLongClick(item); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        com.iptvapp.databinding.ItemTvSeriesPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class PosterDiffCallback : DiffUtil.ItemCallback<SeriesEntity>() {
        override fun areItemsTheSame(a: SeriesEntity, b: SeriesEntity) = a.seriesId == b.seriesId
        override fun areContentsTheSame(a: SeriesEntity, b: SeriesEntity) = a == b
    }
}
