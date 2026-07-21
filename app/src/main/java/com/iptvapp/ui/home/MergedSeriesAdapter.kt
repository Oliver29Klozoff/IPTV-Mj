package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.MergedSeriesEntity
import com.iptvapp.databinding.ItemMergedSeriesBinding

// Series-tab equivalent of MergedVodAdapter — see MergedSeriesEntity kdoc. Tapping a row opens
// SeriesDetailActivity (season/episode picker), unlike merged VOD which plays directly — a
// series item is never itself a single playable stream.
class MergedSeriesAdapter(
    private val onItemClick: (MergedSeriesEntity) -> Unit,
    private val onFavoriteClick: (MergedSeriesEntity) -> Unit = {},
    private val onItemLongClick: (MergedSeriesEntity) -> Unit = {}
) : ListAdapter<MergedSeriesEntity, MergedSeriesAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemMergedSeriesBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MergedSeriesEntity) {
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
            binding.ivSeriesFavorite.setOnClickListener { onFavoriteClick(item) }
            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener { onItemLongClick(item); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMergedSeriesBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<MergedSeriesEntity>() {
        override fun areItemsTheSame(a: MergedSeriesEntity, b: MergedSeriesEntity): Boolean =
            a.serverIndex == b.serverIndex && a.seriesId == b.seriesId

        override fun areContentsTheSame(a: MergedSeriesEntity, b: MergedSeriesEntity): Boolean = a == b
    }
}
