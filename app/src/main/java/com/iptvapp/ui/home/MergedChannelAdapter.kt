package com.iptvapp.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.databinding.ItemMergedChannelBinding

// Favorites/folders now supported for merged channels too (see MergedChannelEntity kdoc) —
// identity for that purpose is the (serverIndex, streamId) pair, not a bare streamId, so it's
// safe even though two different servers can reuse the same numeric stream id. EPG text and
// health dots mirror ChannelAdapter's, keyed by the same composite "serverIndex:streamId"
// string the ViewModel uses.
class MergedChannelAdapter(
    private val onChannelClick: (MergedChannelEntity) -> Unit,
    private val onFavoriteClick: (MergedChannelEntity) -> Unit = {},
    private val onChannelLongClick: (MergedChannelEntity) -> Unit = {},
    private val onChannelDoubleClick: (MergedChannelEntity) -> Unit = {}
) : ListAdapter<MergedChannelEntity, MergedChannelAdapter.ViewHolder>(DiffCallback()) {

    private var epgTextByKey: Map<String, String> = emptyMap()
    private var healthByKey: Map<String, Boolean?> = emptyMap()
    // Highlights whichever row is currently loaded in the mini player — ChannelAdapter (Live
    // tab) and CombinedFavoriteAdapter (Favorites tab) already do this via isSelected; this was
    // the one channel list missing it.
    private var currentlyPlayingKey: String? = null

    fun setCurrentlyPlayingKey(key: String?) {
        val old = currentlyPlayingKey
        currentlyPlayingKey = key
        notifyChangedRows { it == old || it == key }
    }

    private fun keyOf(item: MergedChannelEntity) = "${item.serverIndex}:${item.streamId}"

    // Targeted per-row notifications, same reasoning as ChannelAdapter: these maps refresh
    // repeatedly, and rebinding every row on every tick re-triggers Glide loads and flickers
    // whichever row currently has focus. Only rebind rows whose value actually changed.
    private inline fun notifyChangedRows(changed: (key: String) -> Boolean) {
        currentList.forEachIndexed { index, channel ->
            if (changed(keyOf(channel))) notifyItemChanged(index)
        }
    }

    fun submitEpgText(map: Map<String, String>) {
        val old = epgTextByKey
        epgTextByKey = map
        notifyChangedRows { old[it] != map[it] }
    }

    fun submitHealth(map: Map<String, Boolean?>) {
        val old = healthByKey
        healthByKey = map
        notifyChangedRows { old[it] != map[it] || old.containsKey(it) != map.containsKey(it) }
    }

    // Same "$serverIndex:$streamId" key as everything else here — bulk-select for merged
    // channels (Favorite/Hide), mirroring ChannelAdapter's checkbox-per-row bulk mode.
    private var bulkSelectedKeys: Set<String> = emptySet()
    private var bulkSelectMode: Boolean = false

    fun submitBulkSelection(keys: Set<String>) {
        val old = bulkSelectedKeys
        val oldMode = bulkSelectMode
        bulkSelectedKeys = keys
        bulkSelectMode = keys.isNotEmpty()
        if (oldMode != bulkSelectMode) {
            notifyItemRangeChanged(0, itemCount)
        } else {
            notifyChangedRows { old.contains(it) != keys.contains(it) }
        }
    }

    inner class ViewHolder(val binding: ItemMergedChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // Must survive rebinds (an EPG/health refresh can rebind between the two taps of a
        // double-tap) — same fix ChannelAdapter needed.
        private var lastClickTime = 0L

        fun bind(item: MergedChannelEntity) {
            val key = keyOf(item)
            binding.tvChannelName.text = item.name
            binding.tvServerNickname.text = item.serverNickname
            val epg = epgTextByKey[key]
            binding.tvEpgNow.visibility = if (epg != null) View.VISIBLE else View.GONE
            binding.tvEpgNow.text = epg ?: ""
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
            val health = healthByKey[key]
            if (healthByKey.containsKey(key)) {
                binding.viewHealthDot.visibility = View.VISIBLE
                val dotColor = when (health) {
                    true  -> android.graphics.Color.parseColor("#00CC66")
                    false -> android.graphics.Color.parseColor("#FF4444")
                    null  -> android.graphics.Color.parseColor("#888888")
                }
                (binding.viewHealthDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(dotColor)
            } else {
                binding.viewHealthDot.visibility = View.GONE
            }
            binding.root.isSelected = key == currentlyPlayingKey
            if (bulkSelectMode) {
                binding.cbBulkSelect?.visibility = View.VISIBLE
                binding.cbBulkSelect?.isChecked = bulkSelectedKeys.contains(key)
                binding.root.setBackgroundColor(
                    if (bulkSelectedKeys.contains(key)) 0x33008CFF else 0x00000000
                )
            } else {
                binding.cbBulkSelect?.visibility = View.GONE
                binding.root.setBackgroundResource(com.iptvapp.R.drawable.focus_selector)
            }
            binding.ivFavorite.setOnClickListener { onFavoriteClick(item) }
            binding.root.setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastClickTime < 350) {
                    lastClickTime = 0L
                    onChannelDoubleClick(item)
                } else {
                    lastClickTime = now
                    onChannelClick(item)
                }
            }
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
