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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.CategoryEntity
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityRecordingSchedulerBinding
import com.iptvapp.databinding.ItemRecordingBinding
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
class RecordingSchedulerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREFILL_STREAM_ID = "prefill_stream_id"
        const val EXTRA_PREFILL_START_MS = "prefill_start_ms"
        const val EXTRA_PREFILL_DURATION_MS = "prefill_duration_ms"
        // -1/unset when prefilling a primary channel (EXTRA_PREFILL_STREAM_ID is used instead).
        const val EXTRA_PREFILL_SERVER_INDEX = "prefill_server_index"
        const val EXTRA_PREFILL_MERGED_STREAM_ID = "prefill_merged_stream_id"
        // Every scheduled recording starts a bit early and runs a bit late — the requested
        // start time isn't always exactly when a show actually begins/ends on the provider's
        // end, so this padding catches a slightly-early or slightly-late program boundary.
        private const val PRE_ROLL_MS = 20_000L
        private const val POST_ROLL_MS = 20_000L
    }

    @Inject lateinit var database: IptvDatabase
    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var prefs: com.iptvapp.data.local.PreferencesManager

    private lateinit var binding: ActivityRecordingSchedulerBinding
    private var allChannels: List<ChannelEntity> = emptyList()
    private var allCategories: List<CategoryEntity> = emptyList()
    private var allRecordings: List<RecordingEntity> = emptyList()
    private var showingScheduleView = false
    private var dayOffset = 0
    private val dayLabelFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
    private var epgNowMap: Map<Int, String> = emptyMap()
    private val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    private val recordingAdapter = RecordingAdapter(
        onDelete = { rec -> showDeleteRecordingDialog(rec) },
        onRename = { rec -> showRenameDialog(rec) },
        onRetry = { rec -> retryRecording(rec) }
    )

    // The failed attempt's own scheduled time has already passed by the time anyone notices
    // it failed — re-recording that exact original window would be pointless. Retry instead
    // starts a fresh recording right now, for the same duration, on the same channel.
    private fun retryRecording(rec: RecordingEntity) {
        lifecycleScope.launch {
            if (rec.serverIndex == -1) {
                val channel = database.channelDao().getAllChannels().first()
                    .firstOrNull { it.streamId == rec.streamId }
                if (channel == null) {
                    Toast.makeText(this@RecordingSchedulerActivity, "Channel no longer available", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                database.recordingDao().delete(rec)
                scheduleRecording(channel, System.currentTimeMillis(), rec.durationMs)
            } else {
                val channel = repository.getMergedChannelByIndexAndId(rec.serverIndex, rec.streamId)
                if (channel == null) {
                    Toast.makeText(this@RecordingSchedulerActivity, "Channel no longer available", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                database.recordingDao().delete(rec)
                scheduleMergedRecording(channel, System.currentTimeMillis(), rec.durationMs)
            }
        }
    }

    // "Remove from list" and "delete the actual file from device storage" are two different
    // user intents — a completed recording's file can be large, so deleting it should be an
    // explicit choice, not an automatic side effect of removing the row from this screen.
    private fun showDeleteRecordingDialog(rec: RecordingEntity) {
        val canDeleteFile = rec.status == "DONE" || rec.status == "FAILED"
        AlertDialog.Builder(this)
            .setTitle("Delete Recording?")
            .setMessage(rec.channelName)
            .also { builder ->
                if (canDeleteFile) {
                    builder.setPositiveButton("Delete + Remove File") { _, _ ->
                        lifecycleScope.launch {
                            cancelRecordingAlarm(rec.id)
                            RecordingFileUtils.deleteFile(this@RecordingSchedulerActivity, rec.outputPath)
                            database.recordingDao().delete(rec)
                        }
                    }
                    builder.setNeutralButton("Remove from List Only") { _, _ ->
                        lifecycleScope.launch {
                            cancelRecordingAlarm(rec.id)
                            database.recordingDao().delete(rec)
                        }
                    }
                } else {
                    builder.setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            cancelRecordingAlarm(rec.id)
                            database.recordingDao().delete(rec)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(rec: RecordingEntity) {
        val input = EditText(this).apply {
            setText(rec.channelName)
            setSelection(text.length)
            setPadding(48, 24, 48, 24)
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

    private fun showRecordingStorageSettingsDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val folderLabel = android.widget.TextView(this).apply {
            text = "Folder name (under Movies/)"
            setTextColor(android.graphics.Color.WHITE)
        }
        val folderInput = EditText(this).apply {
            setPadding(0, 16, 0, 24)
        }
        val retentionLabel = android.widget.TextView(this).apply {
            text = "Auto-delete recordings after"
            setTextColor(android.graphics.Color.WHITE)
        }
        val retentionOptions = listOf("Never" to 0, "7 days" to 7, "14 days" to 14, "30 days" to 30, "60 days" to 60, "90 days" to 90)
        val retentionSpinner = android.widget.Spinner(this)
        retentionSpinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_item, retentionOptions.map { it.first }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        container.addView(folderLabel)
        container.addView(folderInput)
        container.addView(retentionLabel)
        container.addView(retentionSpinner)

        lifecycleScope.launch {
            folderInput.setText(prefs.recordingFolderName.first())
            val savedDays = prefs.autoDeleteRecordingsDays.first()
            retentionSpinner.setSelection(retentionOptions.indexOfFirst { it.second == savedDays }.coerceAtLeast(0))
        }

        AlertDialog.Builder(this)
            .setTitle("Recording Storage")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val folderName = folderInput.text.toString().trim().ifEmpty { "MKTV" }
                    .replace(Regex("[^a-zA-Z0-9 _-]"), "_")
                val days = retentionOptions[retentionSpinner.selectedItemPosition].second
                lifecycleScope.launch {
                    prefs.setRecordingFolderName(folderName)
                    prefs.setAutoDeleteRecordingsDays(days)
                    if (days > 0) {
                        val request = androidx.work.PeriodicWorkRequestBuilder<com.iptvapp.worker.RecordingCleanupWorker>(1, java.util.concurrent.TimeUnit.DAYS).build()
                        androidx.work.WorkManager.getInstance(this@RecordingSchedulerActivity).enqueueUniquePeriodicWork(
                            com.iptvapp.worker.RecordingCleanupWorker.WORK_NAME,
                            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                            request
                        )
                    } else {
                        androidx.work.WorkManager.getInstance(this@RecordingSchedulerActivity).cancelUniqueWork(com.iptvapp.worker.RecordingCleanupWorker.WORK_NAME)
                    }
                    Toast.makeText(this@RecordingSchedulerActivity, "New recordings will save to Movies/$folderName", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingSchedulerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter = recordingAdapter
        binding.fabAdd.setOnClickListener { showAddRecordingChooser() }

        binding.dayScheduleView.onBlockClick = { rec -> onScheduleBlockClick(rec) }
        binding.dayScheduleView.onBlockLongClick = { rec -> showRenameDialog(rec) }
        binding.btnToggleScheduleView.setOnClickListener { toggleScheduleView() }
        binding.btnRecordingStorageSettings.setOnClickListener { showRecordingStorageSettingsDialog() }
        binding.btnDayPrev.setOnClickListener { dayOffset -= 1; refreshScheduleView() }
        binding.btnDayNext.setOnClickListener { dayOffset += 1; refreshScheduleView() }

        lifecycleScope.launch {
            allCategories = database.categoryDao().getCategoriesByType("live").first()
                .filter { it.categoryName.startsWith("US|", ignoreCase = true) }
            val usCategoryIds = allCategories.map { it.categoryId }.toSet()
            allChannels = database.channelDao().getAllChannels().first()
                .filter { it.categoryId in usCategoryIds }

            // Build a "currently airing" map for the channel picker
            val nowMs = System.currentTimeMillis()
            val nowSec = nowMs / 1000L
            epgNowMap = database.epgDao().getCurrentlyAiring(nowSec)
                .associate { it.streamId to it.title }
        }

        lifecycleScope.launch { cleanupStaleRecordings() }

        // Re-establish the cleanup worker in case it was cleared by an app update (KEEP = don't
        // reset the timer) — mirrors how EpgRefreshWorker is re-armed in SettingsActivity.
        lifecycleScope.launch {
            if (prefs.autoDeleteRecordingsDays.first() > 0) {
                val request = androidx.work.PeriodicWorkRequestBuilder<com.iptvapp.worker.RecordingCleanupWorker>(1, java.util.concurrent.TimeUnit.DAYS).build()
                androidx.work.WorkManager.getInstance(this@RecordingSchedulerActivity).enqueueUniquePeriodicWork(
                    com.iptvapp.worker.RecordingCleanupWorker.WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }

        lifecycleScope.launch {
            database.recordingDao().getAll().collect { list ->
                allRecordings = list
                recordingAdapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty() && !showingScheduleView) View.VISIBLE else View.GONE
                if (showingScheduleView) refreshScheduleView()
            }
        }

        val prefillServerIndex = intent.getIntExtra(EXTRA_PREFILL_SERVER_INDEX, -1)
        val prefillStreamId = intent.getIntExtra(EXTRA_PREFILL_STREAM_ID, -1)
        val prefillMergedStreamId = intent.getIntExtra(EXTRA_PREFILL_MERGED_STREAM_ID, -1)
        if (prefillServerIndex != -1 && prefillMergedStreamId != -1) {
            val prefillStartMs = intent.getLongExtra(EXTRA_PREFILL_START_MS, 0L)
            val prefillDurationMs = intent.getLongExtra(EXTRA_PREFILL_DURATION_MS, 60 * 60_000L)
            lifecycleScope.launch {
                val channel = repository.getMergedChannelByIndexAndId(prefillServerIndex, prefillMergedStreamId)
                if (channel == null) {
                    Toast.makeText(this@RecordingSchedulerActivity, "Channel not found", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this@RecordingSchedulerActivity, "Channel not found", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                scheduleRecording(channel, prefillStartMs, prefillDurationMs)
            }
        }
    }

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
                            contentResolver.delete(Uri.parse(rec.outputPath), null, null)
                        } else {
                            File(rec.outputPath).delete()
                        }
                    }
                    database.recordingDao().updateStatus(rec.id, "FAILED")
                } else {
                    database.recordingDao().updateStatus(rec.id, "DONE")
                }
            }
    }

    // Kept as a separate first step rather than folding merged channels into
    // showScheduleDialog()'s existing category-drill state machine — that dialog already closes
    // over a lot of mutable filter/selection state built around ChannelEntity specifically, and
    // recording is scoped (by design, confirmed with the user) to already-favorited channels
    // from other providers rather than a full per-provider category browse, so a flat separate
    // picker is both simpler and matches the actual use case.
    private fun showAddRecordingChooser() {
        AlertDialog.Builder(this)
            .setTitle("Record a Channel")
            .setItems(arrayOf("Primary Channels", "Favorited (Any Provider)")) { _, which ->
                if (which == 0) showScheduleDialog() else showFavoritedRecordingPicker()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFavoritedRecordingPicker() {
        lifecycleScope.launch {
            val favorites = repository.getFavoriteChannels().first().map {
                com.iptvapp.ui.home.CombinedFavorite.Primary(it, null)
            } + repository.getMergedAllFavorites().first().map {
                com.iptvapp.ui.home.CombinedFavorite.Merged(it)
            }
            if (favorites.isEmpty()) {
                Toast.makeText(this@RecordingSchedulerActivity, "No favorited channels yet", Toast.LENGTH_SHORT).show()
                return@launch
            }
            var selected: com.iptvapp.ui.home.CombinedFavorite? = favorites.firstOrNull()
            val labels = favorites.map { fav ->
                val tag = fav.serverNickname?.let { " · $it" } ?: ""
                "${fav.name}$tag"
            }.toTypedArray()
            AlertDialog.Builder(this@RecordingSchedulerActivity)
                .setTitle("Select Favorited Channel")
                .setSingleChoiceItems(labels, 0) { _, pos -> selected = favorites.getOrNull(pos) }
                .setPositiveButton("Next") { _, _ ->
                    val fav = selected ?: return@setPositiveButton
                    pickDateTime { startMs ->
                        pickDuration { durationMs ->
                            when (fav) {
                                is com.iptvapp.ui.home.CombinedFavorite.Primary -> scheduleRecording(fav.channel, startMs, durationMs)
                                is com.iptvapp.ui.home.CombinedFavorite.Merged -> scheduleMergedRecording(fav.channel, startMs, durationMs)
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showScheduleDialog() {
        if (allChannels.isEmpty()) {
            Toast.makeText(this, "Channel list not loaded yet", Toast.LENGTH_SHORT).show()
            return
        }

        var filteredChannels = allChannels.toMutableList()
        var filteredCategories = allCategories.toMutableList()
        var selectedChannel: ChannelEntity? = null
        var currentCategoryId: String? = null  // null = category level, non-null = channel level

        val FILL = -1; val WRAP = -2
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 0)
        }

        val etSearch = android.widget.EditText(this).apply {
            hint = "Search all channels..."
            setSingleLine()
            layoutParams = android.widget.LinearLayout.LayoutParams(FILL, WRAP)
        }
        layout.addView(etSearch)

        // Breadcrumb shown when inside a category; tap to go back
        val tvBreadcrumb = android.widget.TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF008CFF.toInt())
            setPadding(4, 8, 4, 4)
            layoutParams = android.widget.LinearLayout.LayoutParams(FILL, WRAP)
            visibility = View.GONE
        }
        layout.addView(tvBreadcrumb)

        val listHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        val listView = android.widget.ListView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(FILL, listHeight)
            choiceMode = android.widget.AbsListView.CHOICE_MODE_SINGLE
        }
        layout.addView(listView)

        fun rebuildList() {
            val q = etSearch.text.toString().trim().lowercase()
            when {
                q.isNotEmpty() -> {
                    // Search across all channels regardless of category
                    filteredChannels = allChannels.filter { it.name.lowercase().contains(q) }.toMutableList()
                    listView.adapter = android.widget.ArrayAdapter(
                        this, android.R.layout.simple_list_item_activated_1,
                        filteredChannels.map { ch ->
                            val now = epgNowMap[ch.streamId]
                            if (now != null) "${ch.name}  —  $now" else ch.name
                        }
                    )
                    selectedChannel = filteredChannels.firstOrNull()
                    if (filteredChannels.isNotEmpty()) listView.setItemChecked(0, true)
                    tvBreadcrumb.visibility = View.GONE
                    listView.setOnItemClickListener { _, _, pos, _ ->
                        selectedChannel = filteredChannels.getOrNull(pos)
                    }
                }
                currentCategoryId == null -> {
                    // Category level
                    filteredCategories = allCategories.toMutableList()
                    listView.adapter = android.widget.ArrayAdapter(
                        this, android.R.layout.simple_list_item_1,
                        filteredCategories.map { cat ->
                            val count = allChannels.count { it.categoryId == cat.categoryId }
                            "📁 ${cat.categoryName}  ($count)"
                        }
                    )
                    selectedChannel = null
                    tvBreadcrumb.visibility = View.GONE
                    listView.setOnItemClickListener { _, _, pos, _ ->
                        val cat = filteredCategories.getOrNull(pos) ?: return@setOnItemClickListener
                        currentCategoryId = cat.categoryId
                        tvBreadcrumb.text = "◀ All Categories  /  ${cat.categoryName}"
                        tvBreadcrumb.visibility = View.VISIBLE
                        rebuildList()
                    }
                }
                else -> {
                    // Channel level (inside a category)
                    filteredChannels = allChannels.filter { it.categoryId == currentCategoryId }.toMutableList()
                    listView.adapter = android.widget.ArrayAdapter(
                        this, android.R.layout.simple_list_item_activated_1,
                        filteredChannels.map { ch ->
                            val now = epgNowMap[ch.streamId]
                            if (now != null) "${ch.name}  —  $now" else ch.name
                        }
                    )
                    selectedChannel = filteredChannels.firstOrNull()
                    if (filteredChannels.isNotEmpty()) listView.setItemChecked(0, true)
                    listView.setOnItemClickListener { _, _, pos, _ ->
                        selectedChannel = filteredChannels.getOrNull(pos)
                    }
                }
            }
        }

        // Breadcrumb tap → back to category list
        tvBreadcrumb.setOnClickListener {
            currentCategoryId = null
            selectedChannel = null
            rebuildList()
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable) = rebuildList()
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        })

        rebuildList()

        AlertDialog.Builder(this)
            .setTitle("Select Channel")
            .setView(layout)
            .setPositiveButton("Next") { _, _ ->
                val ch = selectedChannel ?: run {
                    Toast.makeText(this, "No channel selected", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pickDateTime { startMs ->
                    pickDuration { durationMs ->
                        scheduleRecording(ch, startMs, durationMs)
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

    private fun scheduleRecording(channel: ChannelEntity, requestedStartMs: Long, requestedDurationMs: Long) {
        val startMs = requestedStartMs - PRE_ROLL_MS
        val durationMs = requestedDurationMs + PRE_ROLL_MS + POST_ROLL_MS
        lifecycleScope.launch {
            val overlapping = try { repository.getOverlappingRecordings(startMs, durationMs) } catch (_: Exception) { emptyList() }
            if (overlapping.isNotEmpty()) {
                // Most Xtream plans allow only one simultaneous stream, so two recordings
                // scheduled at overlapping times will very likely just fail each other
                // silently — worth a heads-up before committing rather than discovering it
                // after the fact via two empty/failed recordings.
                val names = overlapping.joinToString(", ") { it.channelName }
                val proceed = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    AlertDialog.Builder(this@RecordingSchedulerActivity)
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
                    AlertDialog.Builder(this@RecordingSchedulerActivity)
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

    private suspend fun createOutputTarget(channel: ChannelEntity, startMs: Long): String =
        createOutputTarget(channel.name, startMs)

    private suspend fun createOutputTarget(channelName: String, startMs: Long): String {
        val safeName = channelName.replace(Regex("[^a-zA-Z0-9 _-]"), "_")
        val fileName = "${safeName}_${startMs}.ts"
        val folderName = prefs.recordingFolderName.first()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$folderName")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) return uri.toString()
        }

        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            .let { File(it, folderName) }
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
        val serviceExtras = Intent(this, RecordingService::class.java).apply {
            putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
            putExtra(RecordingService.EXTRA_STREAM_URL, streamUrl)
            putExtra(RecordingService.EXTRA_CHANNEL_NAME, channelName)
            putExtra(RecordingService.EXTRA_DURATION_MS, durationMs)
            putExtra(RecordingService.EXTRA_OUTPUT_PATH, outputTarget)
        }

        // If start time is now or in the past, skip the alarm and start immediately
        if (startMs <= System.currentTimeMillis() + 3000L) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceExtras)
            } else {
                startService(serviceExtras)
            }
            return
        }

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Warn if exact alarm permission is missing — recording may fire late
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            AlertDialog.Builder(this)
                .setTitle("Exact Alarm Permission Needed")
                .setMessage("Without this permission, scheduled recordings may start late. Tap Allow to fix it.")
                .setPositiveButton("Allow") { _, _ ->
                    startActivity(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM.let {
                        android.content.Intent(it)
                    })
                }
                .setNegativeButton("Continue Anyway", null)
                .show()
        }

        val intent = Intent(this, RecordingAlarmReceiver::class.java).apply {
            putExtras(serviceExtras)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, recordingId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMs, pendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMs, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, startMs, pendingIntent)
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
        private val onDelete: (RecordingEntity) -> Unit,
        private val onRename: (RecordingEntity) -> Unit = {},
        private val onRetry: (RecordingEntity) -> Unit = {}
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
                val sizeLabel = RecordingFileUtils.sizeLabel(b.root.context, rec.outputPath)
                val sizeSuffix = if (sizeLabel.isNotEmpty()) " - $sizeLabel" else ""
                b.tvRecDetails.text = "${dateFmt.format(Date(rec.scheduledStartMs))} - ${durMin}min$sizeSuffix"
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
                b.btnDelete.setOnClickListener { onDelete(rec) }
                b.root.setOnLongClickListener { onRename(rec); true }

                if (rec.status == "DONE") {
                    b.btnPlay.visibility = View.VISIBLE
                    b.btnPlay.setOnClickListener { playFile(rec.outputPath) }
                    b.btnShare.visibility = View.VISIBLE
                    b.btnShare.setOnClickListener { shareFile(rec.outputPath) }
                } else {
                    b.btnPlay.visibility = View.GONE
                    b.btnShare.visibility = View.GONE
                }

                if (rec.status == "FAILED") {
                    b.btnRetry.visibility = View.VISIBLE
                    b.btnRetry.setOnClickListener { onRetry(rec) }
                } else {
                    b.btnRetry.visibility = View.GONE
                }
            }
        }
    }

    private fun playFile(path: String) = com.iptvapp.util.RecordingFileUtils.playFile(this, path)

    private fun shareFile(path: String) = com.iptvapp.util.RecordingFileUtils.shareFile(this, path)
}