package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.MergedVodEntity
import com.iptvapp.databinding.ItemMergedVodBinding

// Movies-tab equivalent of MergedChannelAdapter — see MergedVodEntity kdoc. No EPG/health dot
// (not applicable to VOD) and no resume progress bar in v1 (merged VOD has no watchedMs yet).
class MergedVodAdapter(
    private val onItemClick: (MergedVodEntity) -> Unit,
    private val onFavoriteClick: (MergedVodEntity) -> Unit = {},
    private val onItemLongClick: (MergedVodEntity) -> Unit = {}
) : ListAdapter<MergedVodEntity, MergedVodAdapter.ViewHolder>(DiffCallback()) {

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
