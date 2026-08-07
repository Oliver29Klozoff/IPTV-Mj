package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.databinding.ItemVodBinding
import com.iptvapp.databinding.ItemSectionHeaderBinding

// A "★ Favorites" header now separates favorited titles from the rest of the list — previously
// favorites just floated silently to the top of the same flat list with nothing marking them as
// such. VodRow wraps VodEntity so the header can be a real, distinct row (this is a plain
// LinearLayoutManager list, not a grid, so no span-size-lookup is needed for a full-width row).
sealed class VodRow {
    data class Header(val title: String) : VodRow()
    data class Item(val vod: VodEntity) : VodRow()
}

class VodAdapter(
    private val onVodClick: (VodEntity) -> Unit,
    private val onFavoriteClick: (VodEntity) -> Unit,
    private val onVodLongClick: (VodEntity) -> Unit = {}
) : ListAdapter<VodRow, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    /** Wraps a plain VodEntity list, inserting a "★ Favorites" header before the leading run of
     * favorited titles when present — callers keep submitting List<VodEntity> exactly as before
     * (favorites-first ordering is still HomeViewModel.applyVodSort's job), this just adds the
     * label on top of that existing order. */
    fun submitVodList(list: List<VodEntity>) {
        val favoriteCount = list.takeWhile { it.isFavorite }.size
        val rows = buildList {
            if (favoriteCount > 0) {
                add(VodRow.Header("★ Favorites"))
                addAll(list.take(favoriteCount).map { VodRow.Item(it) })
                if (favoriteCount < list.size) add(VodRow.Header("All Movies"))
            }
            addAll(list.drop(favoriteCount).map { VodRow.Item(it) })
        }
        submitList(rows)
    }

    /** Plain list, no header — used by the History tab, which reuses this same adapter/
     * RecyclerView for a "recently watched" list that has no favorites-section concept. */
    fun submitPlainList(list: List<VodEntity>) {
        submitList(list.map { VodRow.Item(it) })
    }

    /** Plain list, no header, with a completion callback — TV's Movies list (see
     * TvHomeActivity), which still needs the callback to restore D-pad focus after a resubmit.
     * The header row is a phone-only addition for now. */
    fun submitPlainList(list: List<VodEntity>, commitCallback: () -> Unit) {
        submitList(list.map { VodRow.Item(it) }, commitCallback)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is VodRow.Header -> TYPE_HEADER
        is VodRow.Item -> TYPE_ITEM
    }

    inner class HeaderViewHolder(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: VodRow.Header) {
            binding.tvSectionHeader.text = row.title
        }
    }

    inner class ViewHolder(val binding: ItemVodBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VodEntity) {
            binding.tvVodName.text = item.name
            binding.tvVodRating.text = if (!item.rating.isNullOrBlank()) "★ ${item.rating}" else ""
            Glide.with(binding.ivVodPoster)
                .load(item.streamIcon)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.ivVodPoster)
            binding.ivVodFavorite.setImageResource(
                if (item.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            // item_vod.xml used to have a fixed android:tint="#008CFF" on this ImageView, which
            // never changed regardless of favorite state — a blue-tinted "off" star was easy to
            // mistake for an "on" one at a glance, especially against varied poster art, making
            // every row look favorited. Explicit per-state tint here instead.
            binding.ivVodFavorite.setColorFilter(
                if (item.isFavorite) android.graphics.Color.parseColor("#FFC107")
                else android.graphics.Color.parseColor("#555555")
            )
            if (item.watchedMs > 0 && item.durationMs > 0) {
                val pct = ((item.watchedMs * 100) / item.durationMs).coerceIn(0, 100).toInt()
                binding.progressVod.progress = pct
                binding.progressVod.visibility = android.view.View.VISIBLE
            } else {
                binding.progressVod.visibility = android.view.View.GONE
            }
            binding.root.setOnClickListener { onVodClick(item) }
            binding.root.setOnLongClickListener { onVodLongClick(item); true }
            binding.ivVodFavorite.setOnClickListener { onFavoriteClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(ItemSectionHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            ViewHolder(ItemVodBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is VodRow.Header -> (holder as HeaderViewHolder).bind(row)
            is VodRow.Item -> (holder as ViewHolder).bind(row.vod)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<VodRow>() {
        override fun areItemsTheSame(a: VodRow, b: VodRow): Boolean = when {
            a is VodRow.Header && b is VodRow.Header -> a.title == b.title
            a is VodRow.Item && b is VodRow.Item -> a.vod.streamId == b.vod.streamId
            else -> false
        }
        override fun areContentsTheSame(a: VodRow, b: VodRow): Boolean = a == b
    }
}

// Netflix-style poster grid for TvHomeActivity's full-screen Movies browse (see
// item_tv_vod_poster.xml / showMoviesFullScreen). Separate from VodAdapter since a grid has no
// use for VodAdapter's favorites-header row (that grouping doesn't need to survive the switch
// from a list to a grid), and keeping this one plain-list-only avoids span-size handling for a
// header that would need to stretch across every grid column.
class TvVodPosterAdapter(
    private val onVodClick: (VodEntity) -> Unit,
    private val onVodLongClick: (VodEntity) -> Unit = {}
) : ListAdapter<VodEntity, TvVodPosterAdapter.ViewHolder>(PosterDiffCallback()) {

    inner class ViewHolder(val binding: com.iptvapp.databinding.ItemTvVodPosterBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VodEntity) {
            binding.tvVodName.text = item.name
            binding.tvVodRating.text = if (!item.rating.isNullOrBlank()) "★ ${item.rating}" else ""
            Glide.with(binding.ivVodPoster)
                .load(item.streamIcon)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.ivVodPoster)
            // Non-interactive badge now — favoriting lives on VodDetailActivity's "Add to
            // Favorites" button (long-press a poster to get there), see item_tv_vod_poster.xml.
            binding.ivVodFavorite.visibility = if (item.isFavorite) android.view.View.VISIBLE else android.view.View.GONE
            if (item.watchedMs > 0 && item.durationMs > 0) {
                val pct = ((item.watchedMs * 100) / item.durationMs).coerceIn(0, 100).toInt()
                binding.progressVod.progress = pct
                binding.progressVod.visibility = android.view.View.VISIBLE
            } else {
                binding.progressVod.visibility = android.view.View.GONE
            }
            binding.root.setOnClickListener { onVodClick(item) }
            binding.root.setOnLongClickListener { onVodLongClick(item); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        com.iptvapp.databinding.ItemTvVodPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class PosterDiffCallback : DiffUtil.ItemCallback<VodEntity>() {
        override fun areItemsTheSame(a: VodEntity, b: VodEntity) = a.streamId == b.streamId
        override fun areContentsTheSame(a: VodEntity, b: VodEntity) = a == b
    }
}
