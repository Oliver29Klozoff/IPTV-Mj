package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.databinding.ItemChannelBinding

// One row per GlobalSearchResult, reusing item_channel.xml for every content type (channel/vod/
// series, primary or merged) — it already has an icon, name, and a provider-label line
// (tvServerNickname), which is exactly the shape every result needs; a dedicated layout would
// just duplicate it.
class GlobalSearchAdapter(
    private val onClick: (GlobalSearchResult) -> Unit
) : ListAdapter<GlobalSearchResult, GlobalSearchAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(result: GlobalSearchResult) {
            binding.tvEpgNow.visibility = View.GONE
            binding.tvEpgNext?.visibility = View.GONE
            binding.ivFavorite.visibility = View.GONE
            binding.cbBulkSelect.visibility = View.GONE
            binding.epgProgressBar.visibility = View.INVISIBLE

            val (name, icon, typeLabel) = when (result) {
                is GlobalSearchResult.Channel -> Triple(result.entity.name, result.entity.streamIcon, "Live")
                is GlobalSearchResult.Vod -> Triple(result.entity.name, result.entity.streamIcon, "Movie")
                is GlobalSearchResult.Series -> Triple(result.entity.name, result.entity.cover, "Series")
                is GlobalSearchResult.MergedChannel -> Triple(result.entity.name, result.entity.streamIcon, "Live")
                is GlobalSearchResult.MergedVod -> Triple(result.entity.name, result.entity.streamIcon, "Movie")
                is GlobalSearchResult.MergedSeries -> Triple(result.entity.name, result.entity.cover, "Series")
                // The search term matched a PROGRAM's title/description, not this channel's own
                // name — the channel name alone here wouldn't explain why it showed up, so the
                // subtitle line below shows "Airing: <program>" instead of the usual type label.
                is GlobalSearchResult.ProgramMatch -> Triple(
                    result.channel?.name ?: result.mergedChannel?.name ?: "",
                    result.channel?.streamIcon ?: result.mergedChannel?.streamIcon,
                    "Airing: ${result.programTitle}"
                )
            }
            binding.tvChannelName.text = name
            val isChannel = result is GlobalSearchResult.Channel || result is GlobalSearchResult.MergedChannel ||
                result is GlobalSearchResult.ProgramMatch
            val quality = if (isChannel) com.iptvapp.util.ChannelQualityTag.labelFor(name) else null
            binding.tvQualityBadge?.apply {
                visibility = if (quality != null) View.VISIBLE else View.GONE
                text = quality ?: ""
            }
            Glide.with(binding.ivChannelLogo)
                .load(icon)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.ivChannelLogo)
            binding.tvServerNickname?.visibility = View.VISIBLE
            binding.tvServerNickname?.text = "$typeLabel · ${result.providerLabel}"
            binding.root.setOnClickListener { onClick(result) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class DiffCallback : DiffUtil.ItemCallback<GlobalSearchResult>() {
        private fun key(r: GlobalSearchResult): String = when (r) {
            is GlobalSearchResult.Channel -> "c:${r.entity.streamId}"
            is GlobalSearchResult.Vod -> "v:${r.entity.streamId}"
            is GlobalSearchResult.Series -> "s:${r.entity.seriesId}"
            is GlobalSearchResult.MergedChannel -> "mc:${r.entity.serverIndex}:${r.entity.streamId}"
            is GlobalSearchResult.MergedVod -> "mv:${r.entity.serverIndex}:${r.entity.streamId}"
            is GlobalSearchResult.MergedSeries -> "ms:${r.entity.serverIndex}:${r.entity.seriesId}"
            is GlobalSearchResult.ProgramMatch -> "pm:${r.channel?.streamId ?: r.mergedChannel?.serverIndex}:${r.mergedChannel?.streamId ?: r.channel?.streamId}:${r.programTitle}"
        }
        override fun areItemsTheSame(a: GlobalSearchResult, b: GlobalSearchResult) = key(a) == key(b)
        override fun areContentsTheSame(a: GlobalSearchResult, b: GlobalSearchResult) = a == b
    }
}
