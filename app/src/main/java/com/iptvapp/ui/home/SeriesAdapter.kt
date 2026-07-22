package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.SeriesEntity
import com.iptvapp.databinding.ItemSeriesBinding

class SeriesAdapter(
    private val onSeriesClick: (SeriesEntity) -> Unit,
    private val onFavoriteClick: (SeriesEntity) -> Unit = {}
) : ListAdapter<SeriesEntity, SeriesAdapter.ViewHolder>(DiffCallback()) {

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
            binding.root.setOnClickListener { onSeriesClick(item) }
            binding.ivSeriesFavorite.setOnClickListener { onFavoriteClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSeriesBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<SeriesEntity>() {
        override fun areItemsTheSame(a: SeriesEntity, b: SeriesEntity) = a.seriesId == b.seriesId
        override fun areContentsTheSame(a: SeriesEntity, b: SeriesEntity) = a == b
    }
}
