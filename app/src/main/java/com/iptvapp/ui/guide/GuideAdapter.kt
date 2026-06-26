package com.iptvapp.ui.guide

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.databinding.ItemGuideRowBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GuideAdapter(
    private val onChannelClick: (GuideRow) -> Unit,
    private val onReplayClick: (GuideRow, EpgEntity) -> Unit = { _, _ -> }
) : ListAdapter<GuideRow, GuideAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemGuideRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: GuideRow) {
            binding.tvChannelName.text = row.channel.name
            binding.programContainer.removeAllViews()

            if (row.programs.isEmpty()) {
                binding.programContainer.addView(makeProgramText("No guide data", false, null))
            } else {
                row.programs.forEach { program ->
                    val start = formatTime(program.startTimestamp)
                    val stop = formatTime(program.stopTimestamp)
                    val isReplay = row.channel.tvArchive == 1 && program.hasArchive == 1
                    val label = if (isReplay)
                        "$start - $stop  ${program.title}  ▶ Replay"
                    else
                        "$start - $stop  ${program.title}"

                    binding.programContainer.addView(
                        makeProgramText(label, isReplay) { if (isReplay) onReplayClick(row, program) }
                    )
                }
            }

            binding.root.setOnClickListener {
                onChannelClick(row)
            }
        }

        private fun makeProgramText(text: String, isReplay: Boolean, onClick: (() -> Unit)?): TextView {
            return TextView(binding.root.context).apply {
                this.text = text
                setTextColor(if (isReplay) 0xFF00AAFF.toInt() else 0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(18, 12, 18, 12)
                minWidth = 300
                if (isReplay && onClick != null) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                }
            }
        }

        private fun formatTime(timestamp: Long): String {
            val millis = if (timestamp < 100000000000L) timestamp * 1000 else timestamp
            return SimpleDateFormat("h:mm a", Locale.US).format(Date(millis))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGuideRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<GuideRow>() {
        override fun areItemsTheSame(a: GuideRow, b: GuideRow): Boolean =
            a.channel.streamId == b.channel.streamId

        override fun areContentsTheSame(a: GuideRow, b: GuideRow): Boolean =
            a == b
    }
}