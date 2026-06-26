package com.iptvapp.ui.recordings

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.databinding.ActivityRecordingSchedulerBinding
import com.iptvapp.databinding.ItemRecordingBinding
import com.iptvapp.ui.player.PlayerActivity
import com.iptvapp.worker.RecordingWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class RecordingSchedulerActivity : AppCompatActivity() {

    @Inject lateinit var database: IptvDatabase

    private lateinit var binding: ActivityRecordingSchedulerBinding
    private var allChannels: List<ChannelEntity> = emptyList()
    private val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    private val recordingAdapter = RecordingAdapter(
        onDelete = { rec ->
            lifecycleScope.launch {
                WorkManager.getInstance(this@RecordingSchedulerActivity)
                    .cancelAllWorkByTag("rec_${rec.id}")
                database.recordingDao().delete(rec)
            }
        },
        onPlay = { rec ->
            val file = java.io.File(rec.outputPath)
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.provider", file
            )
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("stream_url", fileUri.toString())
                putExtra("stream_title", rec.channelName)
                putExtra("stream_id", rec.streamId)
                putExtra("is_vod", true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingSchedulerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter = recordingAdapter
        binding.fabAdd.setOnClickListener { showScheduleDialog() }

        lifecycleScope.launch {
            allChannels = database.channelDao().getFavoriteChannels().first()
        }
        lifecycleScope.launch {
            database.recordingDao().getAll().collect { list ->
                recordingAdapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showScheduleDialog() {
        if (allChannels.isEmpty()) {
            Toast.makeText(this, "Channel list not loaded yet", Toast.LENGTH_SHORT).show()
            return
        }
        val names = allChannels.map { it.name }.toTypedArray()
        var selectedIndex = 0
        AlertDialog.Builder(this)
            .setTitle("Select Channel")
            .setSingleChoiceItems(names, 0) { _, i -> selectedIndex = i }
            .setPositiveButton("Next") { _, _ ->
                pickDateTime { startMs ->
                    pickDuration { durationMs ->
                        scheduleRecording(allChannels[selectedIndex], startMs, durationMs)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickDateTime(onDone: (Long) -> Unit) {
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val cal = Calendar.getInstance()
                cal.set(year, month, day, hour, minute, 0)
                cal.set(Calendar.MILLISECOND, 0)
                onDone(cal.timeInMillis)
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickDuration(onDone: (Long) -> Unit) {
        val input = EditText(this).apply {
            hint = "Minutes"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("60")
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Duration (minutes)")
            .setView(input)
            .setPositiveButton("Schedule") { _, _ ->
                val mins = input.text.toString().toLongOrNull() ?: 60L
                onDone(mins * 60_000L)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scheduleRecording(channel: ChannelEntity, startMs: Long, durationMs: Long) {
        lifecycleScope.launch {
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?.let { File(it, "MKTV") } ?: File(filesDir, "MKTV")
            dir.mkdirs()
            val safeName = channel.name.replace(Regex("[^a-zA-Z0-9 _-]"), "_")
            val outputPath = File(dir, "${safeName}_${startMs}.ts").absolutePath

            val recording = RecordingEntity(
                streamId = channel.streamId,
                channelName = channel.name,
                scheduledStartMs = startMs,
                durationMs = durationMs,
                outputPath = outputPath
            )
            val id = database.recordingDao().insert(recording)

            val delay = (startMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val work = OneTimeWorkRequestBuilder<RecordingWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(
                    RecordingWorker.KEY_RECORDING_ID to id.toInt(),
                    RecordingWorker.KEY_STREAM_ID to channel.streamId,
                    RecordingWorker.KEY_CHANNEL_NAME to channel.name,
                    RecordingWorker.KEY_DURATION_MS to durationMs,
                    RecordingWorker.KEY_OUTPUT_PATH to outputPath
                ))
                .addTag("rec_$id")
                .build()
            WorkManager.getInstance(this@RecordingSchedulerActivity).enqueue(work)

            Toast.makeText(
                this@RecordingSchedulerActivity,
                "Scheduled: ${channel.name} at ${dateFmt.format(Date(startMs))}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    inner class RecordingAdapter(
        private val onDelete: (RecordingEntity) -> Unit,
        private val onPlay: (RecordingEntity) -> Unit
    ) : RecyclerView.Adapter<RecordingAdapter.VH>() {

        private var items: List<RecordingEntity> = emptyList()

        fun submitList(list: List<RecordingEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemRecordingBinding.inflate(layoutInflater, parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val b: ItemRecordingBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(rec: RecordingEntity) {
                b.tvRecChannel.text = rec.channelName
                val durMin = rec.durationMs / 60_000
                b.tvRecDetails.text = "${dateFmt.format(Date(rec.scheduledStartMs))} · ${durMin}min"
                b.tvRecStatus.text = rec.status
                val (bg, fg) = when (rec.status) {
                    "RECORDING" -> 0x33FF4444.toInt() to 0xFFFF4444.toInt()
                    "DONE"      -> 0x3300CC66.toInt() to 0xFF00CC66.toInt()
                    "FAILED"    -> 0x33FF8800.toInt() to 0xFFFF8800.toInt()
                    else        -> 0x33008CFF.toInt() to 0xFF008CFF.toInt()
                }
                b.tvRecStatus.setBackgroundColor(bg)
                b.tvRecStatus.setTextColor(fg)
                val isDone = rec.status == "DONE"
                b.btnPlay.visibility = if (isDone) View.VISIBLE else View.GONE
                b.btnPlay.setOnClickListener { onPlay(rec) }
                b.btnDelete.setOnClickListener { onDelete(rec) }
            }
        }
    }
}
