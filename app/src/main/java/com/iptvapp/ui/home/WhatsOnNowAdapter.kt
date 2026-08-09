package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.databinding.ItemWhatsOnNowBinding

// Favorites' "What's On Now" strip (see HomeViewModel.loadWhatsOnNow's kdoc) — a plain
// horizontal card list, no favoriting/long-press/bulk-select here, just tap-to-play.
class WhatsOnNowAdapter(
    private val onClick: (HomeViewModel.WhatsOnNowEntry) -> Unit
) : ListAdapter<HomeViewModel.WhatsOnNowEntry, WhatsOnNowAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemWhatsOnNowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: HomeViewModel.WhatsOnNowEntry) {
            binding.tvWhatsOnChannelName.text = entry.channel.name
            binding.tvWhatsOnProgramTitle.text = entry.programTitle
            binding.whatsOnProgress.progress = entry.progressPercent
            binding.root.setOnClickListener { onClick(entry) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemWhatsOnNowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class DiffCallback : DiffUtil.ItemCallback<HomeViewModel.WhatsOnNowEntry>() {
        override fun areItemsTheSame(a: HomeViewModel.WhatsOnNowEntry, b: HomeViewModel.WhatsOnNowEntry) =
            a.channel.streamId == b.channel.streamId
        override fun areContentsTheSame(a: HomeViewModel.WhatsOnNowEntry, b: HomeViewModel.WhatsOnNowEntry) = a == b
    }
}
