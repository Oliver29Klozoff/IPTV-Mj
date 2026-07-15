package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.databinding.ItemMergedChannelBinding

// Deliberately separate from ChannelAdapter — browse-and-play only (no favorite star, no
// long-press menu, no drag handles), since merged/secondary-provider channels don't have a
// globally-unique streamId to safely favorite/record against. See MergedChannelEntity kdoc.
class MergedChannelAdapter(
    private val onChannelClick: (MergedChannelEntity) -> Unit
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
            binding.root.setOnClickListener { onChannelClick(item) }
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
