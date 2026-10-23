package com.iptvapp.ui.recordings

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.*
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.service.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SeriesRecordingActivity : AppCompatActivity() {
    @Inject lateinit var db: IptvDatabase
    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var engine: SeriesRecordingEngine
    private lateinit var rows: LinearLayout
    private val date = java.text.SimpleDateFormat("EEE MMM d, HH:mm z", java.util.Locale.getDefault())
    private fun time(ms: Long) = date.format(java.util.Date(ms))

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 24) }
        setContentView(ScrollView(this).apply { addView(rows) })
        reload()
        if (state == null && intent.hasExtra("epg_id")) lifecycleScope.launch {
            val epg = db.epgDao().getEpgForStream(intent.getIntExtra("stream_id", -1), intent.getIntExtra("server_index", -1))
                .first().firstOrNull { it.id == intent.getStringExtra("epg_id") }
            if (epg != null) recordOptions(epg) else message("This guide entry changed. Select it again in the guide.")
        }
    }

    private fun label(text: String) { rows.addView(TextView(this).apply { this.text = text; textSize = 17f; setPadding(8, 16, 8, 8) }) }
    private fun button(text: String, action: () -> Unit) {
        rows.addView(Button(this).apply { this.text = text; isAllCaps = false; minHeight = 56; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { action() }
        })
    }
    private fun message(text: String) { AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show() }
    private fun work(action: suspend () -> Unit) { lifecycleScope.launch {
        try { action(); reload() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { message("Could not update series recording. Your existing recordings are preserved.") }
    } }

    private fun recordOptions(epg: EpgEntity) {
        AlertDialog.Builder(this).setTitle("Record Options — ${epg.title}")
            .setItems(arrayOf("Record This Episode", "Record All Episodes", "Record New Episodes Only", "Record This Series", "Manage Series Recording")) { _, choice ->
                if (choice == 4) { reload(); return@setItems }
                // One airing: the existing scheduler, as the guide's Record button always did.
                if (choice == 0) { recordOnce(epg); return@setItems }
                work {
                    val key = engine.providerKey(epg.serverIndex) ?: error("Provider unavailable")
                    val name = channelName(epg.serverIndex, epg.streamId)
                    val rule = SeriesRecordingRule(title = epg.title, seriesKey = epg.seriesKey, providerKey = key,
                        serverIndex = epg.serverIndex, streamId = epg.streamId, channelName = name,
                        mode = if (choice == 0) "SINGLE" else if (choice == 2) "NEW" else "ALL")
                    configure(rule) { saved -> work { engine.create(saved, epg); engine.refresh() } }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun recordOnce(epg: EpgEntity) {
        val start = SeriesRecordingPolicy.ms(epg.startTimestamp)
        val intent = Intent(this, RecordingSchedulerActivity::class.java)
            .putExtra(RecordingSchedulerActivity.EXTRA_PREFILL_START_MS, start)
            .putExtra(RecordingSchedulerActivity.EXTRA_PREFILL_DURATION_MS, (SeriesRecordingPolicy.ms(epg.stopTimestamp) - start).coerceAtLeast(60_000L))
        if (epg.serverIndex == -1) intent.putExtra(RecordingSchedulerActivity.EXTRA_PREFILL_STREAM_ID, epg.streamId)
        else intent.putExtra(RecordingSchedulerActivity.EXTRA_PREFILL_SERVER_INDEX, epg.serverIndex)
            .putExtra(RecordingSchedulerActivity.EXTRA_PREFILL_MERGED_STREAM_ID, epg.streamId)
        startActivity(intent)
        finish()
    }

    private suspend fun channelName(server: Int, stream: Int): String =
        if (server == -1) repository.getChannelById(stream)?.name ?: "Unavailable channel"
        else repository.getMergedChannelByIndexAndId(server, stream)?.name ?: "Unavailable channel"

    private fun configure(rule: SeriesRecordingRule, save: (SeriesRecordingRule) -> Unit) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        fun spinner(title: String, values: List<String>, selected: Int): Spinner {
            form.addView(TextView(this).apply { text = title })
            return Spinner(this).apply { adapter = ArrayAdapter(this@SeriesRecordingActivity, android.R.layout.simple_spinner_dropdown_item, values)
                setSelection(selected.coerceIn(values.indices)); form.addView(this) }
        }
        form.addView(TextView(this).apply { text = "Preferred source: ${rule.channelName} (${if (rule.serverIndex == -1) "Primary" else "Provider ${rule.serverIndex + 1}"})\nUncertain episode identities and unknown new/rerun flags require confirmation." })
        val start = spinner("Start padding", (0..15).map { "$it minutes" }, rule.startPaddingMinutes)
        val end = spinner("End padding", (0..60).map { "$it minutes" }, rule.endPaddingMinutes)
        val keepValues = listOf(0, 3, 5, 10)
        val keep = spinner("Retention", listOf("Keep all episodes", "Keep last 3", "Keep last 5", "Keep last 10"), keepValues.indexOf(rule.keepCount))
        val priority = spinner("Priority (existing reservations are never displaced automatically)", listOf("Low", "Normal", "High"), if (rule.priority < 50) 0 else if (rule.priority > 50) 2 else 1)
        val low = CheckBox(this).apply { text = "Stop recording when storage is low"; isChecked = rule.stopLowStorage; form.addView(this) }
        AlertDialog.Builder(this).setTitle(rule.title).setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ -> save(rule.copy(startPaddingMinutes = start.selectedItemPosition,
                endPaddingMinutes = end.selectedItemPosition, keepCount = keepValues[keep.selectedItemPosition],
                priority = listOf(10, 50, 90)[priority.selectedItemPosition], stopLowStorage = low.isChecked)) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun reload() = lifecycleScope.launch {
        val rules = db.seriesRecordingDao().rules()
        val episodes = db.seriesRecordingDao().episodes()
        val recordings = db.recordingDao().getAll().first()
        val byId = recordings.associateBy { it.id }
        rows.removeAllViews()
        val conflictsOnly = intent.getBooleanExtra("conflicts", false)
        label(if (conflictsOnly) "Recording Conflicts" else "Series Recording Manager")
        button("Back") { finish() }
        label("Free space: ${engine.freeBytes()?.let { "${it / (1024 * 1024)} MiB" } ?: "Storage unavailable"}\nCapture estimate: 8 Mbps + 512 MiB reserve. Overlapping recordings are limited to your provider's connection count (up to ${SeriesRecordingPolicy.DEVICE_CAPTURE_LIMIT}).")
        if (!RecordingAlarms.canScheduleExact(this@SeriesRecordingActivity)) label("Exact alarms are disabled: recordings may start late or need a tap. Enable Alarms & reminders in Android settings.")
        button("Refresh schedule") { work { engine.refresh() } }
        if (!conflictsOnly) button("Recording Conflicts") { startActivity(Intent(this@SeriesRecordingActivity, SeriesRecordingActivity::class.java).putExtra("conflicts", true)) }
        for (rule in rules.filter { !it.cancelled }) {
            val own = episodes.filter { it.ruleId == rule.id }
            val completed = own.mapNotNull { byId[it.recordingId] }.count { it.status == "DONE" }
            if (!conflictsOnly) button("${rule.title} — ${if (rule.paused) "Paused" else rule.mode}\n${rule.channelName} • priority ${rule.priority} • $completed completed • keep ${rule.keepCount.takeIf { it > 0 } ?: "all"}") { manage(rule) }
            for (episode in own.filter { !conflictsOnly || it.status == "CONFLICT" }) {
                val rec = byId[episode.recordingId]
                val state = if (episode.status in setOf("CONFLICT", "UNCERTAIN")) episode.status else rec?.status ?: episode.status
                button("${episode.title} — $state\n${time(episode.startMs)}\n${episode.reason}${rec?.failureReason?.let { "\n$it" }.orEmpty()}${rec?.takeIf { it.status != "SCHEDULED" }?.let { "\n" + com.iptvapp.util.RecordingFileUtils.sizeLabel(this@SeriesRecordingActivity, it.outputPath) }.orEmpty()}") { episodeMenu(episode, rec) }
            }
        }
        // Existing manual reservations remain visible in the conflict screen too.
        if (conflictsOnly) {
            val scheduled = recordings.filter { it.status in setOf("SCHEDULED", "RECORDING") }
            for ((i, a) in scheduled.withIndex()) for (b in scheduled.drop(i + 1)) {
                if (SeriesRecordingPolicy.overlaps(a.scheduledStartMs, a.scheduledStartMs + a.durationMs, b.scheduledStartMs, b.scheduledStartMs + b.durationMs))
                    button("${a.programTitle ?: a.channelName} overlaps ${b.programTitle ?: b.channelName}\nExisting reservations preserved") {
                        startActivity(Intent(this@SeriesRecordingActivity, RecordingSchedulerActivity::class.java))
                    }
            }
        } else {
            label("Protect recordings from automatic cleanup")
            for (rec in recordings.filter { it.status == "DONE" }) button("${if (rec.isProtected) "Protected" else "Unprotected"}: ${rec.programTitle ?: rec.channelName}") {
                work { db.recordingDao().protect(rec.id, !rec.isProtected) }
            }
        }
    }

    private fun manage(rule: SeriesRecordingRule) {
        AlertDialog.Builder(this).setTitle(rule.title).setItems(arrayOf("Edit settings / priority", "Preferred channel / provider", if (rule.paused) "Resume" else "Pause", "Cancel series rule")) { _, which ->
            when (which) {
                0 -> configure(rule) { work { engine.setRule(it); engine.refresh() } }
                1 -> preferredSource(rule)
                2, 3 -> AlertDialog.Builder(this).setTitle(if (which == 3) "Cancel series recording?" else if (rule.paused) "Resume series?" else "Pause series?")
                    .setMessage("Pending episodes will ${if (rule.paused && which == 2) "be scheduled again" else "stop being scheduled"}. Completed recordings and captures already in progress will be kept.")
                    .setPositiveButton("Confirm") { _, _ -> work { engine.setRule(rule.copy(paused = if (which == 2) !rule.paused else rule.paused, cancelled = which == 3)); engine.refresh() } }
                    .setNegativeButton("Keep unchanged", null).show()
            }
        }.show()
    }

    private fun preferredSource(rule: SeriesRecordingRule) = work {
        if (rule.seriesKey == null) { message("No reliable series ID is available. Select the intended channel's program in the guide to create a rule for that source."); return@work }
        val sources = db.epgDao().seriesSources(rule.seriesKey).distinctBy { it.serverIndex to it.streamId }
        val names = sources.map { "${channelName(it.serverIndex, it.streamId)} (${if (it.serverIndex == -1) "Primary" else "Provider ${it.serverIndex + 1}"})" }
        AlertDialog.Builder(this).setTitle("Preferred source — future episodes").setItems(names.toTypedArray()) { _, index -> work {
            val epg = sources[index]; val key = engine.providerKey(epg.serverIndex) ?: error("Unavailable")
            engine.setRule(rule.copy(providerKey = key, serverIndex = epg.serverIndex, streamId = epg.streamId, channelName = channelName(epg.serverIndex, epg.streamId)))
            engine.refresh()
        } }.setNegativeButton("Cancel", null).show()
    }

    private fun episodeMenu(episode: SeriesRecordingEpisode, rec: RecordingEntity?) {
        AlertDialog.Builder(this).setTitle(episode.title)
            .setItems(arrayOf("Confirm this uncertain airing / retry scheduling", "Find another verified airing", "Play recording", "Protect / unprotect recording", "Cancel this scheduled episode")) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this).setMessage("Confirm this exact airing? Unknown identity or new/rerun status cannot be verified from this guide. Existing conflicts will still be respected.")
                        .setPositiveButton("Confirm airing") { _, _ -> work { engine.confirm(episode.identity); engine.refresh() } }.setNegativeButton("Cancel", null).show()
                    1 -> work {
                        val rule = db.seriesRecordingDao().rule(episode.ruleId) ?: return@work
                        val alternatives = db.epgDao().getEpgForStream(rule.streamId, rule.serverIndex).first().filter {
                            SeriesRecordingPolicy.reliable(it) && SeriesRecordingPolicy.identity(rule.providerKey, it) == episode.identity && SeriesRecordingPolicy.ms(it.startTimestamp) > System.currentTimeMillis()
                        }
                        if (alternatives.isEmpty()) message("No other verified airing is available in the current guide.")
                        else AlertDialog.Builder(this).setTitle("Choose verified airing").setItems(alternatives.map { time(SeriesRecordingPolicy.ms(it.startTimestamp)) }.toTypedArray()) { _, i -> work { engine.chooseAiring(episode.identity, alternatives[i]); engine.refresh() } }.show()
                    }
                    2 -> if (rec?.status == "DONE") com.iptvapp.util.RecordingFileUtils.playInApp(this, rec) else message("No completed recording is available.")
                    3 -> if (rec?.status == "DONE") work { db.recordingDao().protect(rec.id, !rec.isProtected) }
                    4 -> AlertDialog.Builder(this).setMessage("Cancel this pending episode? Existing recorded files and active captures will be kept.")
                        .setPositiveButton("Cancel episode") { _, _ -> work { engine.cancelEpisode(episode.identity) } }.setNegativeButton("Keep", null).show()
                }
            }.setNegativeButton("Close", null).show()
    }
}
