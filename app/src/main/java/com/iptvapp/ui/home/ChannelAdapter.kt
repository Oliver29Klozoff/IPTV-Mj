package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onChannelClick: (ChannelEntity) -> Unit,
    private val onFavoriteClick: (ChannelEntity) -> Unit
) : ListAdapter<ChannelEntity, ChannelAdapter.ViewHolder>(DiffCallback()) {

    private var epgTextByStreamId: Map<Int, String> = emptyMap()

    fun submitEpgText(epgMap: Map<Int, String>) {
        epgTextByStreamId = epgMap
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChannelEntity) {
            binding.tvChannelName.text = item.name
            binding.tvEpgNow.text = epgTextByStreamId[item.streamId] ?: "Guide loading..."

            Glide.with(binding.ivChannelLogo)
                .load(item.streamIcon)
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play)
                .into(binding.ivChannelLogo)

            binding.ivFavorite.setImageResource(
                if (item.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )

            binding.root.setOnClickListener {
                onChannelClick(item)
            }

            // Both long-press on the row AND tap on the star toggle the favorite
            binding.root.setOnLongClickListener {
                onFavoriteClick(item)
                true
            }

            binding.ivFavorite.setOnClickListener {
                onFavoriteClick(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<ChannelEntity>() {
        override fun areItemsTheSame(a: ChannelEntity, b: ChannelEntity): Boolean =
            a.streamId == b.streamId

        override fun areContentsTheSame(a: ChannelEntity, b: ChannelEntity): Boolean =
            a == b
    }
}