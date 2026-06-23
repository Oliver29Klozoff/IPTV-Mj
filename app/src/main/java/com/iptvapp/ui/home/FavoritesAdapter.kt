package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.databinding.ItemChannelBinding

class FavoritesAdapter(
    private val onChannelClick: (ChannelEntity) -> Unit,
    private val onFavoriteClick: (ChannelEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<FavoriteListItem>()

    fun submitItems(newItems: List<FavoriteListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is FavoriteListItem.Header -> 0
            is FavoriteListItem.Channel -> 1
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == 0) {
            val textView = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    48
                )
                setPadding(18, 8, 18, 8)
                setTextColor(0xFF00E5FF.toInt())
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundColor(0xFF111111.toInt())
            }
            HeaderViewHolder(textView)
        } else {
            val binding = ItemChannelBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            ChannelViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is FavoriteListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is FavoriteListItem.Channel -> (holder as ChannelViewHolder).bind(item.channel)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class HeaderViewHolder(private val textView: TextView) :
        RecyclerView.ViewHolder(textView) {
        fun bind(item: FavoriteListItem.Header) {
            textView.text = "Ã°Å¸â€œÂ ${item.categoryName}"
        }
    }

    inner class ChannelViewHolder(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChannelEntity) {
            binding.tvChannelName.text = item.name
            binding.tvEpgNow.text = ""

            Glide.with(binding.ivChannelLogo)
                .load(item.streamIcon)
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play)
                .into(binding.ivChannelLogo)

            binding.ivFavorite.setImageResource(android.R.drawable.btn_star_big_on)

            binding.root.setOnClickListener {
                onChannelClick(item)
            }

            binding.ivFavorite.setOnClickListener {
                onFavoriteClick(item)
            }
        }
    }
}