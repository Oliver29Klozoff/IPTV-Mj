package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.databinding.ItemMergedChannelBinding

// Favorites/folders now supported for merged channels too (see MergedChannelEntity kdoc) —
// identity for that purpose is the (serverIndex, streamId) pair, not a bare streamId, so it's
// safe even though two different servers can reuse the same numeric stream id.
class MergedChannelAdapter(
    private val onChannelClick: (MergedChannelEntity) -> Unit,
    private val onFavoriteClick: (MergedChannelEntity) -> Unit = {},
    private val onChannelLongClick: (MergedChannelEntity) -> Unit = {}
) : ListAdapter<MergedChannelEntity, MergedChannelAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemMergedChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MergedChannelEntity) {
            binding.tvChannelName.text = item.name
            binding.tvServerNickname.text = item.serverNickname
            Glide.with(binding.ivChannelLogo)
                .load(item.streamIcon)
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play)
                .into(binding.ivChannelLogo)
            binding.ivFavorite.setImageResource(
                if (item.isFavorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
            )
            binding.ivFavorite.setColorFilter(
                if (item.isFavorite) android.graphics.Color.parseColor("#FFC107") else android.graphics.Color.parseColor("#555555")
            )
            binding.ivFavorite.setOnClickListener { onFavoriteClick(item) }
            binding.root.setOnClickListener { onChannelClick(item) }
            binding.root.setOnLongClickListener { onChannelLongClick(item); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMergedChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<MergedChannelEntity>() {
        override fun areItemsTheSame(a: MergedChannelEntity, b: MergedChannelEntity): Boolean =
            a.serverIndex == b.serverIndex && a.streamId == b.streamId

        override fun areContentsTheSame(a: MergedChannelEntity, b: MergedChannelEntity): Boolean = a == b
    }
}
