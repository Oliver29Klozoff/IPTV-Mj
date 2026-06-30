package com.iptvapp.ui.recordings

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityRecordingSchedulerBinding
import com.iptvapp.databinding.ItemRecordingBinding
import com.iptvapp.service.RecordingService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class RecordingSchedulerActivity : AppCompatActivity() {

    @Inject lateinit var database: IptvDatabase
    @Inject lateinit var repository: XtreamRepository

    private lateinit var binding: ActivityRecordingSchedulerBinding
    private var allChannels: List<ChannelEntity> = emptyList()
    private val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    private val recordingAdapter = RecordingAdapter(
        onDelete = { rec ->
            lifecycleScope.launch {
                cancelRecordingAlarm(rec.id)
                database.recordingDao().delete(rec)
            }
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
            val usCategories = database.categoryDao().getCategoriesByType("live").first()
                .filter { cat ->
                    cat.categoryName.startsWith("US|", ignoreCase = true) ||
                    cat.categoryName.contains("|US|", ignoreCase = true)
                }
                .map { it.categoryId }
                .toSet()

            allChannels = database.channelDao().getAllChannels().first()
                .filter { it.categoryId in usCategories }
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
                val mins = input.text.toString().toLongOrNull()?.coerceAtLeast(1L) ?: 60L
                onDone(mins * 60_000L)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scheduleRecording(channel: ChannelEntity, startMs: Long, durationMs: Long) {
        lifecycleScope.launch {
            try {
                val streamUrl = repository.getLiveStreamUrlForRecording(channel.streamId)
                val outputTarget = createOutputTarget(channel, startMs)

                val recording = RecordingEntity(
                    streamId = channel.streamId,
                    channelName = channel.name,
                    scheduledStartMs = startMs,
                    durationMs = durationMs,
                    outputPath = outputTarget
                )

                val id = database.recordingDao().insert(recording).toInt()

                scheduleRecordingAlarm(
                    recordingId = id,
                    channelName = channel.name,
                    streamUrl = streamUrl,
                    durationMs = durationMs,
                    outputTarget = outputTarget,
                    startMs = startMs
                )

                Toast.makeText(
                    this@RecordingSchedulerActivity,
                    "Scheduled: ${channel.name} at ${dateFmt.format(Date(startMs))}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@RecordingSchedulerActivity,
                    "Could not schedule recording: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun createOutputTarget(channel: ChannelEntity, startMs: Long): String {
        val safeName = channel.name.replace(Regex("[^a-zA-Z0-9 _-]"), "_")
        val fileName = "${safeName}_${startMs}.ts"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MKTV")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) return uri.toString()
        }

        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            .let { File(it, "MKTV") }
        dir.mkdirs()

        return File(dir, fileName).absolutePath
    }

    private fun scheduleRecordingAlarm(
        recordingId: Int,
        channelName: String,
        streamUrl: String,
        durationMs: Long,
        outputTarget: String,
        startMs: Long
    ) {
        val intent = Intent(this, RecordingAlarmReceiver::class.java).apply {
            putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
            putExtra(RecordingService.EXTRA_STREAM_URL, streamUrl)
            putExtra(RecordingService.EXTRA_CHANNEL_NAME, channelName)
            putExtra(RecordingService.EXTRA_DURATION_MS, durationMs)
            putExtra(RecordingService.EXTRA_OUTPUT_PATH, outputTarget)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            recordingId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = startMs.coerceAtLeast(System.currentTimeMillis() + 1000L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun cancelRecordingAlarm(recordingId: Int) {
        val intent = Intent(this, RecordingAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            recordingId,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    inner class RecordingAdapter(
        private val onDelete: (RecordingEntity) -> Unit
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
                b.tvRecDetails.text = "${dateFmt.format(Date(rec.scheduledStartMs))} - ${durMin}min"
                b.tvRecStatus.text = rec.status

                val (bg, fg) = when (rec.status) {
                    "RECORDING" -> 0x33FF4444.toInt() to 0xFFFF4444.toInt()
                    "DONE"      -> 0x3300CC66.toInt() to 0xFF00CC66.toInt()
                    "FAILED"    -> 0x33FF8800.toInt() to 0xFFFF8800.toInt()
                    else        -> 0x33008CFF.toInt() to 0xFF008CFF.toInt()
                }

                b.tvRecStatus.setBackgroundColor(bg)
                b.tvRecStatus.setTextColor(fg)
                b.btnDelete.setOnClickListener { onDelete(rec) }

                if (rec.status == "DONE") {
                    b.btnPlay.visibility = View.VISIBLE
                    b.btnPlay.setOnClickListener { playFile(rec.outputPath) }
                } else {
                    b.btnPlay.visibility = View.GONE
                }
            }
        }
    }

    private fun playFile(path: String) {
        val uri: Uri
        val type = "video/mp2t"

        if (path.startsWith("content://")) {
            uri = Uri.parse(path)
        } else {
            val file = File(path)

            if (!file.exists()) {
                Toast.makeText(this, "File not found: $path", Toast.LENGTH_LONG).show()
                return
            }

            if (file.length() < 1024) {
                Toast.makeText(this, "Recording incomplete (${file.length()} bytes)", Toast.LENGTH_LONG).show()
                return
            }

            uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, "Open recording with...").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        runCatching { startActivity(chooser) }
            .onFailure { Toast.makeText(this, "No video player installed", Toast.LENGTH_SHORT).show() }
    }
}