package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.databinding.ItemChannelGridBinding

class ChannelGridAdapter(
    private val onChannelClick: (ChannelEntity) -> Unit,
    private val onChannelFocused: ((ChannelEntity) -> Unit)? = null
) : ListAdapter<ChannelEntity, ChannelGridAdapter.ViewHolder>(DiffCallback()) {

    private var epgTextByStreamId: Map<Int, String> = emptyMap()

    fun submitEpgText(epgMap: Map<Int, String>) {
        epgTextByStreamId = epgMap
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemChannelGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChannelEntity) {
            binding.tvGridName.text = item.name
            binding.tvGridEpg.text = epgTextByStreamId[item.streamId] ?: ""

            Glide.with(binding.ivGridLogo)
                .load(item.streamIcon)
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play)
                .into(binding.ivGridLogo)

            binding.root.setOnClickListener { onChannelClick(item) }
            binding.root.setOnFocusChangeListener { _, focused ->
                if (focused) onChannelFocused?.invoke(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<ChannelEntity>() {
        override fun areItemsTheSame(a: ChannelEntity, b: ChannelEntity) = a.streamId == b.streamId
        override fun areContentsTheSame(a: ChannelEntity, b: ChannelEntity) = a == b
    }
}
