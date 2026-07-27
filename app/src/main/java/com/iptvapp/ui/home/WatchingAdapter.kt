package com.iptvapp.ui.home

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.dao.InProgressSeriesRow
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.databinding.ItemChannelBinding
import com.iptvapp.databinding.ItemSectionHeaderBinding
import com.iptvapp.databinding.ItemSeriesBinding
import com.iptvapp.databinding.ItemVodBinding

// The History tab previously conflated two unrelated things depending on which adapter happened
// to be attached to rvChannels at the time (recently-watched primary channels via channelAdapter,
// or in-progress movies via a mostly-dead vodAdapter collector that rarely actually rendered
// anything) — and had no in-progress-series concept at all. WatchingRow/WatchingAdapter replace
// both with one adapter showing three clearly labeled sections: Recently Watched (channels, which
// have a real lastWatched timestamp), Continue Watching Movies, and Continue Watching Series
// (movies/series only track resume position, not a "when", so they can't be honestly interleaved
// with channels by true recency — each section is sorted by whatever signal it actually has).
// Long-press on a Continue Watching Movies/Series row starts bulk-select (checkbox on every
// row in both sections, further taps/long-presses toggle) so multiple items can be cleared at
// once via HomeActivity's shared bulk-select bar — clearing removes them from this list without
// touching their resume position (see HomeViewModel.dismissVodFromContinueWatching kdoc).
sealed class WatchingRow {
    data class Header(val title: String) : WatchingRow()
    data class ChannelItem(val channel: ChannelEntity) : WatchingRow()
    data class VodItem(val vod: VodEntity) : WatchingRow()
    data class SeriesItem(val row: InProgressSeriesRow) : WatchingRow()
}

class WatchingAdapter(
    private val onChannelClick: (ChannelEntity) -> Unit,
    private val onChannelFavoriteClick: (ChannelEntity) -> Unit,
    private val onVodClick: (VodEntity) -> Unit,
    private val onVodFavoriteClick: (VodEntity) -> Unit,
    private val onSeriesClick: (InProgressSeriesRow) -> Unit,
    private val onSeriesFavoriteClick: (InProgressSeriesRow) -> Unit,
    // key is "v:$streamId" or "s:$seriesId" — see bulkSelectedKeys kdoc below.
    private val onBulkStart: (String) -> Unit,
    private val onBulkToggle: (String) -> Unit
) : ListAdapter<WatchingRow, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHANNEL = 1
        private const val TYPE_VOD = 2
        private const val TYPE_SERIES = 3
    }

    // Bulk-select for "Clear All Continue Watching" — same shape as SeriesAdapter's bulk-hide
    // checkbox mode. Keys are "v:$streamId" / "s:$seriesId" (prefixed, since VOD streamIds and
    // series seriesIds are unrelated id spaces that could otherwise collide) so one selection
    // set can span both Continue Watching sections at once. Recently Watched channels are never
    // selectable here — long-press only dismisses movies/series, never channel history.
    private var bulkSelectedKeys: Set<String> = emptySet()
    private var bulkSelectMode: Boolean = false

    fun submitBulkSelection(keys: Set<String>) {
        bulkSelectedKeys = keys
        bulkSelectMode = keys.isNotEmpty()
        notifyDataSetChanged()
    }

    fun submitRows(recentChannels: List<ChannelEntity>, inProgressVod: List<VodEntity>, inProgressSeries: List<InProgressSeriesRow>) {
        val rows = buildList {
            if (recentChannels.isNotEmpty()) {
                add(WatchingRow.Header("Recently Watched"))
                addAll(recentChannels.map { WatchingRow.ChannelItem(it) })
            }
            if (inProgressVod.isNotEmpty()) {
                add(WatchingRow.Header("Continue Watching Movies"))
                addAll(inProgressVod.map { WatchingRow.VodItem(it) })
            }
            if (inProgressSeries.isNotEmpty()) {
                add(WatchingRow.Header("Continue Watching Series"))
                addAll(inProgressSeries.map { WatchingRow.SeriesItem(it) })
            }
        }
        submitList(rows)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is WatchingRow.Header -> TYPE_HEADER
        is WatchingRow.ChannelItem -> TYPE_CHANNEL
        is WatchingRow.VodItem -> TYPE_VOD
        is WatchingRow.SeriesItem -> TYPE_SERIES
    }

    inner class HeaderViewHolder(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: WatchingRow.Header) {
            binding.tvSectionHeader.text = row.title
        }
    }

    inner class ChannelViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChannelEntity) {
            binding.tvChannelName.text = item.name
            val quality = com.iptvapp.util.ChannelQualityTag.labelFor(item.name)
            binding.tvQualityBadge?.apply {
                visibility = if (quality != null) View.VISIBLE else View.GONE
                text = quality ?: ""
            }
            binding.tvEpgNow.visibility = View.GONE
            Glide.with(binding.ivChannelLogo)
                .load(item.streamIcon)
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play)
                .into(binding.ivChannelLogo)
            binding.ivFavorite.setImageResource(
                if (item.isFavorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
            )
            binding.ivFavorite.setColorFilter(
                if (item.isFavorite) Color.parseColor("#FFC107") else Color.parseColor("#555555")
            )
            binding.ivFavorite.setOnClickListener { onChannelFavoriteClick(item) }
            binding.root.setOnClickListener { onChannelClick(item) }
        }
    }

    inner class VodViewHolder(val binding: ItemVodBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VodEntity) {
            binding.tvVodName.text = item.name
            Glide.with(binding.ivVodPoster)
                .load(item.streamIcon)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.ivVodPoster)
            binding.ivVodFavorite.setImageResource(
                if (item.isFavorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
            )
            binding.ivVodFavorite.setColorFilter(
                if (item.isFavorite) Color.parseColor("#FFC107") else Color.parseColor("#555555")
            )
            if (item.durationMs > 0) {
                val pct = ((item.watchedMs * 100) / item.durationMs).coerceIn(0, 100).toInt()
                binding.tvVodRating.text = "$pct% watched"
                binding.progressVod.progress = pct
                binding.progressVod.visibility = View.VISIBLE
            } else {
                binding.tvVodRating.text = ""
                binding.progressVod.visibility = View.GONE
            }
            val key = "v:${item.streamId}"
            if (bulkSelectMode) {
                binding.cbVodBulkSelect?.visibility = View.VISIBLE
                binding.cbVodBulkSelect?.isChecked = bulkSelectedKeys.contains(key)
                binding.root.setBackgroundColor(if (bulkSelectedKeys.contains(key)) 0x33008CFF else 0x00000000)
                binding.root.setOnClickListener { onBulkToggle(key) }
                binding.root.setOnLongClickListener { onBulkToggle(key); true }
            } else {
                binding.cbVodBulkSelect?.visibility = View.GONE
                binding.root.setBackgroundResource(com.iptvapp.R.drawable.focus_selector)
                binding.ivVodFavorite.setOnClickListener { onVodFavoriteClick(item) }
                binding.root.setOnClickListener { onVodClick(item) }
                binding.root.setOnLongClickListener { onBulkStart(key); true }
            }
        }
    }

    inner class SeriesViewHolder(val binding: ItemSeriesBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: InProgressSeriesRow) {
            val series = row.series
            binding.tvSeriesName.text = series.name
            binding.tvSeriesGenre.text = "S${row.lastSeason} E${row.lastEpisode}"
            Glide.with(binding.ivSeriesCover)
                .load(series.cover)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.ivSeriesCover)
            binding.ivSeriesFavorite.setImageResource(
                if (series.isFavorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
            )
            binding.ivSeriesFavorite.setColorFilter(
                if (series.isFavorite) Color.parseColor("#FFC107") else Color.parseColor("#555555")
            )
            if (row.lastEpisodeDurationMs > 0) {
                val pct = ((row.lastEpisodeWatchedMs * 100) / row.lastEpisodeDurationMs).coerceIn(0, 100).toInt()
                binding.progressSeries.progress = pct
                binding.progressSeries.visibility = View.VISIBLE
            } else {
                binding.progressSeries.visibility = View.GONE
            }
            val key = "s:${series.seriesId}"
            if (bulkSelectMode) {
                binding.cbSeriesBulkSelect?.visibility = View.VISIBLE
                binding.cbSeriesBulkSelect?.isChecked = bulkSelectedKeys.contains(key)
                binding.root.setBackgroundColor(if (bulkSelectedKeys.contains(key)) 0x33008CFF else 0x00000000)
                binding.root.setOnClickListener { onBulkToggle(key) }
                binding.root.setOnLongClickListener { onBulkToggle(key); true }
            } else {
                binding.cbSeriesBulkSelect?.visibility = View.GONE
                binding.root.setBackgroundResource(com.iptvapp.R.drawable.focus_selector)
                binding.ivSeriesFavorite.setOnClickListener { onSeriesFavoriteClick(row) }
                binding.root.setOnClickListener { onSeriesClick(row) }
                binding.root.setOnLongClickListener { onBulkStart(key); true }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
            TYPE_CHANNEL -> ChannelViewHolder(ItemChannelBinding.inflate(inflater, parent, false))
            TYPE_VOD -> VodViewHolder(ItemVodBinding.inflate(inflater, parent, false))
            else -> SeriesViewHolder(ItemSeriesBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is WatchingRow.Header -> (holder as HeaderViewHolder).bind(row)
            is WatchingRow.ChannelItem -> (holder as ChannelViewHolder).bind(row.channel)
            is WatchingRow.VodItem -> (holder as VodViewHolder).bind(row.vod)
            is WatchingRow.SeriesItem -> (holder as SeriesViewHolder).bind(row.row)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<WatchingRow>() {
        override fun areItemsTheSame(a: WatchingRow, b: WatchingRow): Boolean = when {
            a is WatchingRow.Header && b is WatchingRow.Header -> a.title == b.title
            a is WatchingRow.ChannelItem && b is WatchingRow.ChannelItem -> a.channel.streamId == b.channel.streamId
            a is WatchingRow.VodItem && b is WatchingRow.VodItem -> a.vod.streamId == b.vod.streamId
            a is WatchingRow.SeriesItem && b is WatchingRow.SeriesItem -> a.row.series.seriesId == b.row.series.seriesId
            else -> false
        }
        override fun areContentsTheSame(a: WatchingRow, b: WatchingRow): Boolean = a == b
    }
}
