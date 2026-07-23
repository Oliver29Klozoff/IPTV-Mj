package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.MergedVodEntity
import com.iptvapp.databinding.ItemMergedVodBinding

// Movies-tab equivalent of MergedChannelAdapter — see MergedVodEntity kdoc.
class MergedVodAdapter(
    private val onItemClick: (MergedVodEntity) -> Unit,
    private val onFavoriteClick: (MergedVodEntity) -> Unit = {},
    private val onItemLongClick: (MergedVodEntity) -> Unit = {}
) : ListAdapter<MergedVodEntity, MergedVodAdapter.ViewHolder>(DiffCallback()) {

    // Bulk-hide checkbox mode — same shape as MergedSeriesAdapter's bulk-select.
    private var bulkSelectedKeys: Set<String> = emptySet()
    private var bulkSelectMode: Boolean = false
    private fun keyOf(item: MergedVodEntity) = "${item.serverIndex}:${item.streamId}"

    fun submitBulkSelection(keys: Set<String>) {
        bulkSelectedKeys = keys
        bulkSelectMode = keys.isNotEmpty()
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemMergedVodBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MergedVodEntity) {
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
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMergedVodBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<MergedVodEntity>() {
        override fun areItemsTheSame(a: MergedVodEntity, b: MergedVodEntity): Boolean =
            a.serverIndex == b.serverIndex && a.streamId == b.streamId

        override fun areContentsTheSame(a: MergedVodEntity, b: MergedVodEntity): Boolean = a == b
    }
}
