package com.iptvapp.ui.recordings

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.CategoryEntity
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityTvRecordingBinding
import com.iptvapp.databinding.ItemTvPickerBinding
import com.iptvapp.databinding.ItemTvRecordingRowBinding
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
class TvRecordingActivity : AppCompatActivity() {

    @Inject lateinit var database: IptvDatabase
    @Inject lateinit var repository: XtreamRepository

    private lateinit var binding: ActivityTvRecordingBinding

    private enum class Step { LIST, CATEGORY, CHANNEL }
    private var step = Step.LIST

    private var allCategories: List<CategoryEntity> = emptyList()
    private var allChannels: List<ChannelEntity> = emptyList()
    private var epgNowMap: Map<Int, String> = emptyMap()

    private var currentChannels: List<ChannelEntity> = emptyList()
    private var selectedChannel: ChannelEntity? = null

    private val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    companion object {
        private const val FAVORITES_CATEGORY_ID = "__favorites__"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecordingsList()
        setupSearch()
        setupButtons()
        loadData()
        showStep(Step.LIST)
    }

    private fun setupSearch() {
        binding.etSearchCategory.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable) = rebuildCategoryList(s.toString())
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        })
        binding.etSearchChannel.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable) = rebuildChannelList(s.toString())
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        })
    }

    private fun loadData() {
        lifecycleScope.launch {
            allCategories = database.categoryDao().getCategoriesByType("live").first()
                .filter { it.categoryName.startsWith("US|", ignoreCase = true) }
            val usCategoryIds = allCategories.map { it.categoryId }.toSet()
            allChannels = database.channelDao().getAllChannels().first()
                .filter { it.categoryId in usCategoryIds }
            val nowSec = System.currentTimeMillis() / 1000L
            epgNowMap = database.epgDao().getCurrentlyAiring(nowSec)
                .associate { it.streamId to it.title }
        }

        lifecycleScope.launch { cleanupStaleRecordings() }

        lifecycleScope.launch {
            database.recordingDao().getAll().collect { list ->
                (binding.rvRecordings.adapter as? RecordingListAdapter)?.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /** If RecordingService got killed mid-recording (common on TV boxes under memory
     * pressure), the DB row is stuck at "RECORDING" forever since onDestroy never ran
     * to mark it FAILED. Sweep anything whose window has clearly passed. */
    private suspend fun cleanupStaleRecordings() {
        val now = System.currentTimeMillis()
        database.recordingDao().getAll().first()
            .filter { it.status == "RECORDING" && (it.scheduledStartMs + it.durationMs) < now - 60_000L }
            .forEach { database.recordingDao().updateStatus(it.id, "FAILED") }
    }

    private fun setupRecordingsList() {
        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter = RecordingListAdapter { rec ->
            AlertDialog.Builder(this)
                .setTitle("Delete Recording?")
                .setMessage("${rec.channelName}\n${dateFmt.format(Date(rec.scheduledStartMs))}")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        cancelAlarm(rec.id)
                        database.recordingDao().delete(rec)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupButtons() {
        binding.btnAddNew.setOnClickListener { showStep(Step.CATEGORY) }
        binding.btnCatBack.setOnClickListener { showStep(Step.LIST) }
        binding.btnChanBack.setOnClickListener { showStep(Step.CATEGORY) }
    }

    private fun rebuildCategoryList(query: String) {
        val q = query.trim().lowercase()
        val favoriteChannels = allChannels.filter { it.isFavorite }

        val items = mutableListOf<PickerItem>()
        if (favoriteChannels.isNotEmpty() && (q.isEmpty() || "favorites".contains(q))) {
            items.add(PickerItem(FAVORITES_CATEGORY_ID, "★ FAVORITES", "${favoriteChannels.size} channels"))
        }
        items.addAll(
            allCategories
                .filter { q.isEmpty() || it.categoryName.lowercase().contains(q) }
                .map {
                    val count = allChannels.count { ch -> ch.categoryId == it.categoryId }
                    PickerItem(it.categoryId, it.categoryName.removePrefix("US|").trim(), "$count channels")
                }
        )

        binding.rvCategories.adapter = PickerAdapter(items) { item ->
            binding.tvChanCategoryName.text = item.name
            currentChannels = if (item.id == FAVORITES_CATEGORY_ID) {
                favoriteChannels
            } else {
                allChannels.filter { it.categoryId == item.id }
            }
            binding.etSearchChannel.setText("")
            rebuildChannelList("")
            showStep(Step.CHANNEL)
        }
    }

    private fun rebuildChannelList(query: String) {
        val q = query.trim().lowercase()
        val filtered = currentChannels.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        binding.rvChannels.adapter = PickerAdapter(
            items = filtered.map { PickerItem(it.streamId.toString(), it.name, epgNowMap[it.streamId]) }
        ) { chanItem ->
            selectedChannel = filtered.first { it.streamId.toString() == chanItem.id }
            promptDateTimeAndSchedule()
        }
    }

    private fun promptDateTimeAndSchedule() {
        val ch = selectedChannel ?: return
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val cal = Calendar.getInstance()
                cal.set(year, month, day, hour, minute, 0)
                cal.set(Calendar.MILLISECOND, 0)
                pickDuration { durationMs -> scheduleRecording(ch, cal.timeInMillis, durationMs) }
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickDuration(onDone: (Long) -> Unit) {
        val input = EditText(this).apply {
            hint = "Minutes"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("60")
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xFF555555.toInt())
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

    private fun showStep(s: Step) {
        step = s
        binding.stepList.visibility     = if (s == Step.LIST)     View.VISIBLE else View.GONE
        binding.stepCategory.visibility = if (s == Step.CATEGORY) View.VISIBLE else View.GONE
        binding.stepChannel.visibility  = if (s == Step.CHANNEL)  View.VISIBLE else View.GONE

        when (s) {
            Step.LIST -> {
                binding.btnAddNew.requestFocus()
            }
            Step.CATEGORY -> {
                binding.rvCategories.layoutManager = LinearLayoutManager(this)
                binding.etSearchCategory.setText("")
                rebuildCategoryList("")
                binding.rvCategories.requestFocus()
            }
            Step.CHANNEL -> {
                binding.rvChannels.layoutManager = LinearLayoutManager(this)
                binding.rvChannels.requestFocus()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            when (step) {
                Step.LIST     -> finish()
                Step.CATEGORY -> showStep(Step.LIST)
                Step.CHANNEL  -> showStep(Step.CATEGORY)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
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
                scheduleAlarm(id, channel.name, streamUrl, durationMs, outputTarget, startMs)

                Toast.makeText(
                    this@TvRecordingActivity,
                    "Scheduled: ${channel.name} at ${dateFmt.format(Date(startMs))}",
                    Toast.LENGTH_LONG
                ).show()
                showStep(Step.LIST)
            } catch (e: Exception) {
                Toast.makeText(this@TvRecordingActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MKTV")
        dir.mkdirs()
        return File(dir, fileName).absolutePath
    }

    private fun scheduleAlarm(
        recordingId: Int, channelName: String, streamUrl: String,
        durationMs: Long, outputTarget: String, startMs: Long
    ) {
        val serviceIntent = Intent(this, RecordingService::class.java).apply {
            putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
            putExtra(RecordingService.EXTRA_STREAM_URL, streamUrl)
            putExtra(RecordingService.EXTRA_CHANNEL_NAME, channelName)
            putExtra(RecordingService.EXTRA_DURATION_MS, durationMs)
            putExtra(RecordingService.EXTRA_OUTPUT_PATH, outputTarget)
        }
        if (startMs <= System.currentTimeMillis() + 3000L) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            return
        }
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, RecordingAlarmReceiver::class.java).apply { putExtras(serviceIntent) }
        val pi = PendingIntent.getBroadcast(
            this, recordingId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms() ->
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMs, pi)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMs, pi)
            else -> alarmManager.setExact(AlarmManager.RTC_WAKEUP, startMs, pi)
        }
    }

    private fun cancelAlarm(recordingId: Int) {
        val intent = Intent(this, RecordingAlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            this, recordingId, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
            pi.cancel()
        }
    }

    // ── Adapters ──────────────────────────────────────────────────────────────

    data class PickerItem(val id: String, val name: String, val sub: String? = null)

    inner class PickerAdapter(
        private val items: List<PickerItem>,
        private val onSelect: (PickerItem) -> Unit
    ) : RecyclerView.Adapter<PickerAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTvPickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val b: ItemTvPickerBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                b.root.setOnClickListener { onSelect(items[adapterPosition]) }
                b.root.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        onSelect(items[adapterPosition])
                        true
                    } else false
                }
            }

            fun bind(item: PickerItem) {
                b.tvPickerName.text = item.name
                if (item.sub != null) {
                    b.tvPickerSub.text = item.sub
                    b.tvPickerSub.visibility = View.VISIBLE
                } else {
                    b.tvPickerSub.visibility = View.GONE
                }
            }
        }
    }

    inner class RecordingListAdapter(
        private val onDelete: (RecordingEntity) -> Unit
    ) : RecyclerView.Adapter<RecordingListAdapter.VH>() {

        private var items: List<RecordingEntity> = emptyList()

        fun submitList(list: List<RecordingEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemTvRecordingRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val b: ItemTvRecordingRowBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                b.root.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        onDelete(items[adapterPosition])
                        true
                    } else false
                }
                b.root.setOnClickListener { onDelete(items[adapterPosition]) }
            }

            fun bind(rec: RecordingEntity) {
                b.tvRecChannel.text = rec.channelName
                val durMin = rec.durationMs / 60_000
                b.tvRecDetails.text = "${dateFmt.format(Date(rec.scheduledStartMs))}  •  ${durMin} min"
                b.tvRecStatus.text = rec.status
                val (bg, fg) = when (rec.status) {
                    "RECORDING" -> 0x33FF4444.toInt() to 0xFFFF4444.toInt()
                    "DONE"      -> 0x3300CC66.toInt() to 0xFF00CC66.toInt()
                    "FAILED"    -> 0x33FF8800.toInt() to 0xFFFF8800.toInt()
                    else        -> 0x33008CFF.toInt() to 0xFF008CFF.toInt()
                }
                b.tvRecStatus.setBackgroundColor(bg)
                b.tvRecStatus.setTextColor(fg)
            }
        }
    }
}
