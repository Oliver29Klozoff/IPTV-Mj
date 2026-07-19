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
import android.net.Uri
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
import com.iptvapp.util.RecordingFileUtils
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

    companion object {
        // Mirrors RecordingSchedulerActivity's prefill extras — lets PlayerActivity's Record
        // button jump straight to date/duration for an already-known channel on TV too, instead
        // of only ever supporting this one-tap flow on phone.
        const val EXTRA_PREFILL_STREAM_ID = "prefill_stream_id"
        const val EXTRA_PREFILL_START_MS = "prefill_start_ms"
        const val EXTRA_PREFILL_DURATION_MS = "prefill_duration_ms"
        const val EXTRA_PREFILL_SERVER_INDEX = "prefill_server_index"
        const val EXTRA_PREFILL_MERGED_STREAM_ID = "prefill_merged_stream_id"
        private const val FAVORITES_CATEGORY_ID = "__favorites__"
        // A second synthetic category tile, alongside "★ FAVORITES", drilling into a flat list
        // built from the combined (primary + every other provider) favorites list instead of
        // primary-only ChannelEntity.isFavorite — recording for other providers is scoped to
        // already-favorited channels (confirmed with the user), same reasoning as the phone picker.
        private const val OTHER_PROVIDERS_CATEGORY_ID = "__other_provider_favorites__"
        // Every scheduled recording starts a bit early and runs a bit late — the requested
        // start time isn't always exactly when a show actually begins/ends on the provider's
        // end, so this padding catches a slightly-early or slightly-late program boundary.
        private const val PRE_ROLL_MS = 20_000L
        private const val POST_ROLL_MS = 20_000L
    }

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

    private var currentFavorites: List<com.iptvapp.ui.home.CombinedFavorite> = emptyList()
    private var selectedFavorite: com.iptvapp.ui.home.CombinedFavorite? = null

    private var allRecordings: List<RecordingEntity> = emptyList()
    private var showingScheduleView = false
    private var dayOffset = 0
    private val dayLabelFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecordingsList()
        setupSearch()
        setupButtons()
        loadData()
        showStep(Step.LIST)
        handlePrefill()
    }

    private fun handlePrefill() {
        val prefillServerIndex = intent.getIntExtra(EXTRA_PREFILL_SERVER_INDEX, -1)
        val prefillStreamId = intent.getIntExtra(EXTRA_PREFILL_STREAM_ID, -1)
        val prefillMergedStreamId = intent.getIntExtra(EXTRA_PREFILL_MERGED_STREAM_ID, -1)
        if (prefillServerIndex != -1 && prefillMergedStreamId != -1) {
            val prefillStartMs = intent.getLongExtra(EXTRA_PREFILL_START_MS, 0L)
            val prefillDurationMs = intent.getLongExtra(EXTRA_PREFILL_DURATION_MS, 60 * 60_000L)
            lifecycleScope.launch {
                val channel = repository.getMergedChannelByIndexAndId(prefillServerIndex, prefillMergedStreamId)
                if (channel == null) {
                    Toast.makeText(this@TvRecordingActivity, "Channel not found", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                scheduleMergedRecording(channel, prefillStartMs, prefillDurationMs)
            }
        } else if (prefillStreamId != -1) {
            val prefillStartMs = intent.getLongExtra(EXTRA_PREFILL_START_MS, 0L)
            val prefillDurationMs = intent.getLongExtra(EXTRA_PREFILL_DURATION_MS, 60 * 60_000L)
            lifecycleScope.launch {
                val channel = database.channelDao().getAllChannels().first()
                    .firstOrNull { it.streamId == prefillStreamId }
                if (channel == null) {
                    Toast.makeText(this@TvRecordingActivity, "Channel not found", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                scheduleRecording(channel, prefillStartMs, prefillDurationMs)
            }
        }
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
                allRecordings = list
                (binding.rvRecordings.adapter as? RecordingListAdapter)?.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty() && !showingScheduleView) View.VISIBLE else View.GONE
                if (showingScheduleView) refreshScheduleView()
            }
        }
    }

    private fun toggleScheduleView() {
        showingScheduleView = !showingScheduleView
        if (showingScheduleView) {
            dayOffset = 0
            binding.rvRecordings.visibility = View.GONE
            binding.dayPagingHeader.visibility = View.VISIBLE
            binding.scheduleScroll.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
            refreshScheduleView()
        } else {
            binding.rvRecordings.visibility = View.VISIBLE
            binding.dayPagingHeader.visibility = View.GONE
            binding.scheduleScroll.visibility = View.GONE
            binding.tvEmpty.visibility = if (allRecordings.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun refreshScheduleView() {
        val dayStartMs = DayScheduleView.dayStartMsForOffset(dayOffset)
        binding.tvDayLabel.text = dayLabelFmt.format(Date(dayStartMs))
        binding.dayScheduleView.submitDay(dayStartMs, allRecordings, isToday = dayOffset == 0)
    }

    // Same actions the flat list's row already offers, just entered from a schedule block
    // instead — no new business logic, only a new tap target for existing functionality.
    private fun onScheduleBlockClick(rec: RecordingEntity) {
        when (rec.status) {
            "DONE" -> playFile(rec.outputPath)
            "FAILED" -> retryRecording(rec)
            else -> showDeleteRecordingDialog(rec)
        }
    }

    /** If RecordingService got killed mid-recording (common on TV boxes under memory
     * pressure), the DB row is stuck at "RECORDING" forever since onDestroy never ran
     * to mark it FAILED. Sweep anything whose window has clearly passed. */
    private suspend fun cleanupStaleRecordings() {
        val now = System.currentTimeMillis()
        database.recordingDao().getAll().first()
            .filter { rec ->
                when (rec.status) {
                    "RECORDING" -> (rec.scheduledStartMs + rec.durationMs) < now - 60_000L
                    // Compression runs after the raw file is already safely captured, so if the
                    // service died mid-compress the recording itself is still good — just give
                    // it a longer grace window since transcoding legitimately takes a while.
                    "COMPRESSING" -> (rec.scheduledStartMs + rec.durationMs) < now - 15 * 60_000L
                    else -> false
                }
            }
            .forEach { rec ->
                if (rec.status == "RECORDING") {
                    // The service died before finalizeTarget() ran, so this is an orphaned,
                    // truncated .pending file that will never show up in Gallery (IS_PENDING
                    // stays set) and will just sit there consuming storage forever otherwise.
                    runCatching {
                        if (rec.outputPath.startsWith("content://")) {
                            contentResolver.delete(android.net.Uri.parse(rec.outputPath), null, null)
                        } else {
                            java.io.File(rec.outputPath).delete()
                        }
                    }
                    database.recordingDao().updateStatus(rec.id, "FAILED")
                } else {
                    database.recordingDao().updateStatus(rec.id, "DONE")
                }
            }
    }

    private fun setupRecordingsList() {
        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter = RecordingListAdapter(
            onPlay = { rec -> playFile(rec.outputPath) },
            onShare = { rec -> shareFile(rec.outputPath) },
            onDelete = { rec -> showDeleteRecordingDialog(rec) },
            onRename = { rec ->
                val input = android.widget.EditText(this).apply {
                    setText(rec.channelName)
                    setSelection(text.length)
                }
                AlertDialog.Builder(this)
                    .setTitle("Rename Recording")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) {
                            lifecycleScope.launch { database.recordingDao().rename(rec.id, name) }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onRetry = { rec -> retryRecording(rec) }
        )
    }

    private fun playFile(path: String) = com.iptvapp.util.RecordingFileUtils.playFile(this, path)

    private fun shareFile(path: String) = com.iptvapp.util.RecordingFileUtils.shareFile(this, path)

    // "Remove from list" and "delete the actual file from device storage" are two different
    // user intents — a completed recording's file can be large, so deleting it should be an
    // explicit choice, not an automatic side effect of removing the row from this screen.
    private fun showDeleteRecordingDialog(rec: RecordingEntity) {
        val canDeleteFile = rec.status == "DONE" || rec.status == "FAILED"
        AlertDialog.Builder(this)
            .setTitle("Delete Recording?")
            .setMessage("${rec.channelName}\n${dateFmt.format(Date(rec.scheduledStartMs))}")
            .also { builder ->
                if (canDeleteFile) {
                    builder.setPositiveButton("Delete + Remove File") { _, _ ->
                        lifecycleScope.launch {
                            cancelAlarm(rec.id)
                            com.iptvapp.util.RecordingFileUtils.deleteFile(this@TvRecordingActivity, rec.outputPath)
                            database.recordingDao().delete(rec)
                        }
                    }
                    builder.setNeutralButton("Remove from List Only") { _, _ ->
                        lifecycleScope.launch {
                            cancelAlarm(rec.id)
                            database.recordingDao().delete(rec)
                        }
                    }
                } else {
                    builder.setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            cancelAlarm(rec.id)
                            database.recordingDao().delete(rec)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // The failed attempt's own scheduled time has already passed by the time anyone notices
    // it failed — re-recording that exact original window would be pointless. Retry instead
    // starts a fresh recording right now, for the same duration, on the same channel.
    private fun retryRecording(rec: RecordingEntity) {
        lifecycleScope.launch {
            if (rec.serverIndex == -1) {
                val channel = database.channelDao().getAllChannels().first()
                    .firstOrNull { it.streamId == rec.streamId }
                if (channel == null) {
                    Toast.makeText(this@TvRecordingActivity, "Channel no longer available", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                database.recordingDao().delete(rec)
                scheduleRecording(channel, System.currentTimeMillis(), rec.durationMs)
            } else {
                val channel = repository.getMergedChannelByIndexAndId(rec.serverIndex, rec.streamId)
                if (channel == null) {
                    Toast.makeText(this@TvRecordingActivity, "Channel no longer available", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                database.recordingDao().delete(rec)
                scheduleMergedRecording(channel, System.currentTimeMillis(), rec.durationMs)
            }
        }
    }

    private fun setupButtons() {
        binding.btnAddNew.setOnClickListener { showStep(Step.CATEGORY) }
        binding.btnCatBack.setOnClickListener { showStep(Step.LIST) }
        binding.btnChanBack.setOnClickListener { showStep(Step.CATEGORY) }
        binding.btnToggleScheduleView.setOnClickListener { toggleScheduleView() }
        binding.btnDayPrev.setOnClickListener { dayOffset -= 1; refreshScheduleView() }
        binding.btnDayNext.setOnClickListener { dayOffset += 1; refreshScheduleView() }
        binding.dayScheduleView.onBlockClick = { rec -> onScheduleBlockClick(rec) }
        binding.dayScheduleView.onBlockLongClick = { rec ->
            val input = android.widget.EditText(this).apply {
                setText(rec.channelName)
                setSelection(text.length)
            }
            AlertDialog.Builder(this)
                .setTitle("Rename Recording")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        lifecycleScope.launch { database.recordingDao().rename(rec.id, name) }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun rebuildCategoryList(query: String) {
        val q = query.trim().lowercase()
        val favoriteChannels = allChannels.filter { it.isFavorite }

        val items = mutableListOf<PickerItem>()
        if (favoriteChannels.isNotEmpty() && (q.isEmpty() || "favorites".contains(q))) {
            items.add(PickerItem(FAVORITES_CATEGORY_ID, "★ FAVORITES", "${favoriteChannels.size} channels"))
        }
        if (q.isEmpty() || "other provider".contains(q) || "favorites".contains(q)) {
            items.add(PickerItem(OTHER_PROVIDERS_CATEGORY_ID, "★ OTHER PROVIDERS", "Favorited elsewhere"))
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
            if (item.id == OTHER_PROVIDERS_CATEGORY_ID) {
                lifecycleScope.launch {
                    currentFavorites = repository.getMergedAllFavorites().first().map {
                        com.iptvapp.ui.home.CombinedFavorite.Merged(it)
                    }
                    binding.etSearchChannel.setText("")
                    rebuildOtherProvidersChannelList("")
                    showStep(Step.CHANNEL)
                }
                return@PickerAdapter
            }
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

    // "Other Providers" branch of the Favorites category — same shape as rebuildChannelList,
    // just sourced from currentFavorites (CombinedFavorite.Merged only; primary favorites are
    // already reachable via the normal ★ FAVORITES tile) instead of allChannels.
    private fun rebuildOtherProvidersChannelList(query: String) {
        val q = query.trim().lowercase()
        val filtered = currentFavorites.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        binding.rvChannels.adapter = PickerAdapter(
            items = filtered.map { PickerItem(it.id, "${it.name} · ${it.serverNickname}", null) }
        ) { chanItem ->
            selectedFavorite = filtered.first { it.id == chanItem.id }
            promptDateTimeAndScheduleMerged()
        }
    }

    private fun promptDateTimeAndScheduleMerged() {
        val fav = selectedFavorite as? com.iptvapp.ui.home.CombinedFavorite.Merged ?: return
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val cal = Calendar.getInstance()
                cal.set(year, month, day, hour, minute, 0)
                cal.set(Calendar.MILLISECOND, 0)
                pickDuration { durationMs -> scheduleMergedRecording(fav.channel, cal.timeInMillis, durationMs) }
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
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

    private fun scheduleRecording(channel: ChannelEntity, requestedStartMs: Long, requestedDurationMs: Long) {
        val startMs = requestedStartMs - PRE_ROLL_MS
        val durationMs = requestedDurationMs + PRE_ROLL_MS + POST_ROLL_MS
        lifecycleScope.launch {
            // Most Xtream plans allow only one simultaneous stream, so two recordings
            // scheduled at overlapping times will very likely just fail each other silently —
            // worth a heads-up before committing, same check as the phone scheduler.
            val overlapping = try { repository.getOverlappingRecordings(startMs, durationMs) } catch (_: Exception) { emptyList() }
            if (overlapping.isNotEmpty()) {
                val names = overlapping.joinToString(", ") { it.channelName }
                val proceed = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    androidx.appcompat.app.AlertDialog.Builder(this@TvRecordingActivity)
                        .setTitle("Overlapping Recording")
                        .setMessage(
                            "This overlaps with a recording already scheduled for $names. " +
                                "If your provider only allows one stream at a time, one of these " +
                                "recordings will likely fail. Schedule anyway?"
                        )
                        .setPositiveButton("Schedule Anyway") { _, _ -> cont.resume(true) {} }
                        .setNegativeButton("Cancel") { _, _ -> cont.resume(false) {} }
                        .setOnCancelListener { cont.resume(false) {} }
                        .show()
                }
                if (!proceed) return@launch
            }
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

    // Mirrors scheduleRecording(ChannelEntity, ...) above exactly, just sourcing the URL from
    // the merged-channel repository call and recording serverIndex on the RecordingEntity so
    // observeActive/retryRecording can disambiguate this streamId from a primary/other-server
    // channel that happens to reuse the same numeric id.
    private fun scheduleMergedRecording(channel: com.iptvapp.data.local.entities.MergedChannelEntity, requestedStartMs: Long, requestedDurationMs: Long) {
        val startMs = requestedStartMs - PRE_ROLL_MS
        val durationMs = requestedDurationMs + PRE_ROLL_MS + POST_ROLL_MS
        lifecycleScope.launch {
            val overlapping = try { repository.getOverlappingRecordings(startMs, durationMs) } catch (_: Exception) { emptyList() }
            if (overlapping.isNotEmpty()) {
                val names = overlapping.joinToString(", ") { it.channelName }
                val proceed = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    androidx.appcompat.app.AlertDialog.Builder(this@TvRecordingActivity)
                        .setTitle("Overlapping Recording")
                        .setMessage(
                            "This overlaps with a recording already scheduled for $names. " +
                                "If your provider only allows one stream at a time, one of these " +
                                "recordings will likely fail. Schedule anyway?"
                        )
                        .setPositiveButton("Schedule Anyway") { _, _ -> cont.resume(true) {} }
                        .setNegativeButton("Cancel") { _, _ -> cont.resume(false) {} }
                        .setOnCancelListener { cont.resume(false) {} }
                        .show()
                }
                if (!proceed) return@launch
            }
            try {
                val streamUrl = repository.getMergedLiveStreamUrlForRecording(channel.serverIndex, channel.streamId)
                val outputTarget = createOutputTarget(channel.name, startMs)

                val recording = RecordingEntity(
                    streamId = channel.streamId,
                    serverIndex = channel.serverIndex,
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

    private fun createOutputTarget(channel: ChannelEntity, startMs: Long): String =
        createOutputTarget(channel.name, startMs)

    private fun createOutputTarget(channelName: String, startMs: Long): String {
        val safeName = channelName.replace(Regex("[^a-zA-Z0-9 _-]"), "_")
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
        private val onPlay: (RecordingEntity) -> Unit,
        private val onShare: (RecordingEntity) -> Unit,
        private val onDelete: (RecordingEntity) -> Unit,
        private val onRename: (RecordingEntity) -> Unit = {},
        private val onRetry: (RecordingEntity) -> Unit = {}
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
                b.rowRecordingMain.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        onPlay(items[bindingAdapterPosition])
                        true
                    } else false
                }
                b.rowRecordingMain.setOnClickListener { onPlay(items[bindingAdapterPosition]) }
                b.rowRecordingMain.setOnLongClickListener { onRename(items[bindingAdapterPosition]); true }
                b.btnTvRecShare.setOnClickListener { onShare(items[bindingAdapterPosition]) }
                b.btnTvRecRetry.setOnClickListener { onRetry(items[bindingAdapterPosition]) }
                b.btnTvRecDelete.setOnClickListener { onDelete(items[bindingAdapterPosition]) }
            }

            fun bind(rec: RecordingEntity) {
                b.tvRecChannel.text = rec.channelName
                val durMin = rec.durationMs / 60_000
                val sizeLabel = RecordingFileUtils.sizeLabel(b.root.context, rec.outputPath)
                val sizeSuffix = if (sizeLabel.isNotEmpty()) "  •  $sizeLabel" else ""
                b.tvRecDetails.text = "${dateFmt.format(Date(rec.scheduledStartMs))}  •  ${durMin} min$sizeSuffix"
                b.tvRecStatus.text = rec.status
                val (bg, fg) = when (rec.status) {
                    "RECORDING"   -> 0x33FF4444.toInt() to 0xFFFF4444.toInt()
                    "COMPRESSING" -> 0x33AF52DE.toInt() to 0xFFAF52DE.toInt()
                    "DONE"        -> 0x3300CC66.toInt() to 0xFF00CC66.toInt()
                    "FAILED"      -> 0x33FF8800.toInt() to 0xFFFF8800.toInt()
                    else          -> 0x33008CFF.toInt() to 0xFF008CFF.toInt()
                }
                b.tvRecStatus.setBackgroundColor(bg)
                b.tvRecStatus.setTextColor(fg)

                val isDone = rec.status == "DONE"
                b.btnTvRecShare.visibility = if (isDone) View.VISIBLE else View.GONE
                b.btnTvRecRetry.visibility = if (rec.status == "FAILED") View.VISIBLE else View.GONE
                b.rowRecordingMain.isFocusable = true
                b.rowRecordingMain.isFocusableInTouchMode = false
            }
        }
    }
}
