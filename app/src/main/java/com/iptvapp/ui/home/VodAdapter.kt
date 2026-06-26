package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.VodEntity
import com.iptvapp.databinding.ItemVodBinding

class VodAdapter(
    private val onVodClick: (VodEntity) -> Unit,
    private val onFavoriteClick: (VodEntity) -> Unit
) : ListAdapter<VodEntity, VodAdapter.ViewHolder>(DiffCallback()) {

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
            if (item.watchedMs > 0 && item.durationMs > 0) {
                val pct = ((item.watchedMs * 100) / item.durationMs).coerceIn(0, 100).toInt()
                binding.progressVod.progress = pct
                binding.progressVod.visibility = android.view.View.VISIBLE
            } else {
                binding.progressVod.visibility = android.view.View.GONE
            }
            binding.root.setOnClickListener { onVodClick(item) }
            binding.ivVodFavorite.setOnClickListener { onFavoriteClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVodBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<VodEntity>() {
        override fun areItemsTheSame(a: VodEntity, b: VodEntity) = a.streamId == b.streamId
        override fun areContentsTheSame(a: VodEntity, b: VodEntity) = a == b
    }
}
