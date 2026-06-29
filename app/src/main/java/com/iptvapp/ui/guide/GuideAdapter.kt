package com.iptvapp.ui.guide

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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

            val nowMs = System.currentTimeMillis()

            fun toMs(ts: Long) = if (ts < 100_000_000_000L) ts * 1000L else ts

            // Drop programs that have already ended; keep current + upcoming (max 8)
            val visible = row.programs
                .filter { toMs(it.stopTimestamp) > nowMs }
                .take(8)

            if (visible.isEmpty()) {
                binding.programContainer.addView(makeProgramText("No upcoming guide data", 0xFF555555.toInt(), null))
            } else {
                visible.forEach { program ->
                    val startMs = toMs(program.startTimestamp)
                    val isNow = startMs <= nowMs
                    val start = if (isNow) "▶ NOW" else formatTime(program.startTimestamp)
                    val stop = formatTime(program.stopTimestamp)
                    val isReplay = !isNow && row.channel.tvArchive == 1 && program.hasArchive == 1
                    val label = when {
                        isNow    -> "$start  ${program.title}  (until $stop)"
                        isReplay -> "$start - $stop  ${program.title}  ▶ Replay"
                        else     -> "$start - $stop  ${program.title}"
                    }
                    val color = when {
                        isNow    -> 0xFF00FF88.toInt()
                        isReplay -> 0xFF00AAFF.toInt()
                        else     -> 0xFFCCCCCC.toInt()
                    }

                    binding.programContainer.addView(
                        makeProgramText(label, color,
                            onClick = { if (isReplay) onReplayClick(row, program) },
                            onLongClick = { showTimerDialog(row, program) }
                        )
                    )
                }
            }

            binding.root.setOnClickListener {
                onChannelClick(row)
            }
        }

        private fun makeProgramText(text: String, color: Int, onClick: (() -> Unit)?, onLongClick: (() -> Unit)? = null): TextView {
            return TextView(binding.root.context).apply {
                this.text = text
                setTextColor(color)
                textSize = 14f
                setPadding(18, 12, 18, 12)
                minWidth = 300
                isClickable = true
                isFocusable = true
                if (onClick != null) setOnClickListener { onClick() }
                setOnLongClickListener {
                    onLongClick?.invoke()
                    true
                }
            }
        }

        private fun showTimerDialog(row: GuideRow, program: EpgEntity) {
            val nowMs = System.currentTimeMillis()
            val startMs = if (program.startTimestamp < 100_000_000_000L) program.startTimestamp * 1000L else program.startTimestamp
            if (startMs <= nowMs) return
            val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date(startMs))
            AlertDialog.Builder(binding.root.context)
                .setTitle("Set Reminder")
                .setMessage("Remind me when \"${program.title}\" starts on ${row.channel.name} at $timeStr?")
                .setPositiveButton("Set Reminder") { _, _ ->
                    ChannelTimerScheduler.schedule(binding.root.context, row.channel.streamId, row.channel.name, program.title, startMs)
                    Toast.makeText(binding.root.context, "Reminder set for $timeStr", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
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