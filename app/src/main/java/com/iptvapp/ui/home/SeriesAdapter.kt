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
    private val onFavoriteClick: (SeriesEntity) -> Unit = {},
    private val onSeriesLongClick: (SeriesEntity) -> Unit = {}
) : ListAdapter<SeriesEntity, SeriesAdapter.ViewHolder>(DiffCallback()) {

    // Bulk-hide checkbox mode — same shape as ChannelAdapter's bulk-select: a real checkbox on
    // every row while active, plain taps toggle instead of opening the series.
    private var bulkSelectedIds: Set<Int> = emptySet()
    private var bulkSelectMode: Boolean = false

    fun submitBulkSelection(ids: Set<Int>) {
        bulkSelectedIds = ids
        bulkSelectMode = ids.isNotEmpty()
        notifyDataSetChanged()
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
