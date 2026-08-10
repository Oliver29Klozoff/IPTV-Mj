package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.databinding.ItemTvWhatsOnNowBinding

// TV equivalent of WhatsOnNowAdapter (phone's Favorites tab strip) — focusable cards instead of
// plain click targets, since TV navigation is D-pad-driven. See
// TvHomeActivity.updateTvWhatsOnNowStrip's kdoc for the Favorites-only scoping this backs.
class TvWhatsOnNowAdapter(
    private val onClick: (HomeViewModel.WhatsOnNowEntry) -> Unit
) : ListAdapter<HomeViewModel.WhatsOnNowEntry, TvWhatsOnNowAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemTvWhatsOnNowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: HomeViewModel.WhatsOnNowEntry) {
            binding.tvTvWonChannel.text = entry.channel.name
            binding.tvTvWonProgram.text = entry.programTitle
            binding.tvPbWonProgress.progress = entry.progressPercent
            binding.root.setOnClickListener { onClick(entry) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemTvWhatsOnNowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class DiffCallback : DiffUtil.ItemCallback<HomeViewModel.WhatsOnNowEntry>() {
        override fun areItemsTheSame(a: HomeViewModel.WhatsOnNowEntry, b: HomeViewModel.WhatsOnNowEntry) =
            a.channel.streamId == b.channel.streamId
        override fun areContentsTheSame(a: HomeViewModel.WhatsOnNowEntry, b: HomeViewModel.WhatsOnNowEntry) = a == b
    }
}
