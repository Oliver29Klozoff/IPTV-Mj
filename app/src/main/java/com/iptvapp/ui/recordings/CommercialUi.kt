package com.iptvapp.ui.recordings

import android.content.Context
import android.content.SharedPreferences
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.iptvapp.data.local.entities.RecordingEntity
import com.iptvapp.ui.player.CommercialEditor
import com.iptvapp.util.CommercialMarkers
import com.iptvapp.util.CommercialStore
import com.iptvapp.util.RecordingFileUtils
import com.iptvapp.worker.AdBreakWorker
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Commercial detection settings and the per-recording actions.
 * Phone and TV share this. The recording file is never changed here.
 */
object CommercialUi {
    private val detectionChoices = listOf(
        "Off" to CommercialMarkers.DetectionMode.OFF,
        "On demand" to CommercialMarkers.DetectionMode.ON_DEMAND,
        "Automatically analyze completed recordings" to CommercialMarkers.DetectionMode.AUTOMATIC
    )
    private val skipChoices = listOf(
        "Off" to CommercialMarkers.SkipMode.OFF,
        "Show skip button" to CommercialMarkers.SkipMode.BUTTON,
        "Automatically skip confirmed commercials" to CommercialMarkers.SkipMode.AUTOMATIC
    )

    private val libraryWatchers = WeakHashMap<AppCompatActivity, SharedPreferences.OnSharedPreferenceChangeListener>()

    /** The library list reads analysis notes, which are not recording rows. Refresh it when a job or a note changes. */
    fun watchLibrary(activity: AppCompatActivity, onChanged: () -> Unit) {
        if (libraryWatchers.containsKey(activity)) return
        WorkManager.getInstance(activity)
            .getWorkInfosByTagLiveData(AdBreakWorker.ANALYSIS_TAG)
            .observe(activity) { onChanged() }
        val prefs = activity.applicationContext.getSharedPreferences("commercial_analysis", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            activity.runOnUiThread(onChanged)
        }
        libraryWatchers[activity] = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
                libraryWatchers.remove(activity)
            }
        })
    }

    fun showSettings(activity: AppCompatActivity) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        container.addView(label(activity, "Commercial detection"))
        val detection = spinner(activity, detectionChoices.map { it.first })
        container.addView(detection)
        container.addView(label(activity, "Commercial skipping"))
        val skip = spinner(activity, skipChoices.map { it.first })
        container.addView(skip)
        container.addView(TextView(activity).apply {
            text = "Detection looks for a rhythm of quiet moments. It can miss a commercial or mark a quiet scene. " +
                "A suggestion is not skipped until you confirm it. Automatic skipping stays off until you turn it on, " +
                "and it only skips confirmed markers. One recording can use a different skip choice from its Commercials menu."
            setPadding(0, 24, 0, 0)
        })
        detection.setSelection(detectionChoices.indexOfFirst { it.second == CommercialStore.detectionMode(activity) }.coerceAtLeast(0))
        skip.setSelection(skipChoices.indexOfFirst { it.second == CommercialStore.skipMode(activity) }.coerceAtLeast(0))
        AlertDialog.Builder(activity)
            .setTitle("Commercial detection")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val mode = detectionChoices[detection.selectedItemPosition].second
                CommercialStore.setDetectionMode(activity, mode)
                CommercialStore.setSkipMode(activity, skipChoices[skip.selectedItemPosition].second)
                if (mode == CommercialMarkers.DetectionMode.OFF) {
                    WorkManager.getInstance(activity).cancelAllWorkByTag(AdBreakWorker.ANALYSIS_TAG)
                }
                Toast.makeText(activity, "Commercial detection saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showRecording(activity: AppCompatActivity, rec: RecordingEntity, onChanged: () -> Unit) {
        activity.lifecycleScope.launch {
            val stamp = withContext(Dispatchers.IO) { CommercialStore.stamp(activity, rec.outputPath) }
            val segments = withContext(Dispatchers.IO) {
                CommercialStore.load(activity, rec.id)
                if (stamp.known()) CommercialStore.stampStoredRows(activity, rec.id, stamp)
                else CommercialStore.load(activity, rec.id)
            }
            val status = CommercialStore.noteStatus(activity, rec.id, rec.status == "DONE")
            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            if (rec.status == "DONE") {
                labels += "Analyze recording"
                actions += { analyze(activity, rec, onChanged) }
                val saved = CommercialStore.note(activity, rec.id)?.status
                if (saved == CommercialStore.FAILED || saved == CommercialStore.UNSUPPORTED) {
                    labels += "Retry analysis"
                    actions += { analyze(activity, rec, onChanged) }
                }
            }
            val suggested = segments.any { it.review == CommercialMarkers.Review.SUGGESTED }
            labels += if (suggested) "Review commercials" else "Edit markers"
            actions += { edit(activity, rec, segments, stamp, onChanged) }
            if (segments.any { it.origin == CommercialMarkers.Origin.AUTOMATIC && it.review == CommercialMarkers.Review.SUGGESTED }) {
                labels += "Clear automatic suggestions"
                actions += {
                    activity.lifecycleScope.launch {
                        val kept = CommercialMarkers.dropSuggestions(segments)
                        withContext(Dispatchers.IO) { CommercialStore.saveEdit(activity, rec.id, segments, kept, stamp) }
                        onChanged()
                    }
                }
            }
            AlertDialog.Builder(activity)
                .setTitle(if (status.isEmpty()) "Commercials" else "Commercials — $status")
                .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun analyze(activity: AppCompatActivity, rec: RecordingEntity, onChanged: () -> Unit) {
        if (CommercialStore.detectionMode(activity) == CommercialMarkers.DetectionMode.OFF) {
            Toast.makeText(activity, "Commercial detection is off", Toast.LENGTH_LONG).show()
            return
        }
        AdBreakWorker.enqueue(
            activity,
            rec.id,
            AdBreakWorker.uriForPath(rec.outputPath),
            replace = true,
            force = true
        )
        Toast.makeText(activity, "Analyzing this recording", Toast.LENGTH_SHORT).show()
        onChanged()
    }

    private fun edit(
        activity: AppCompatActivity,
        rec: RecordingEntity,
        segments: List<CommercialMarkers.Segment>,
        stamp: CommercialMarkers.FileStamp,
        onChanged: () -> Unit
    ) {
        CommercialEditor.show(
            activity,
            rec.id,
            segments,
            stamp,
            positionMs = { 0L },
            durationMs = { 0L },
            onSeek = { at -> RecordingFileUtils.playInApp(activity, rec, at, reviewMarker = true) },
            onChanged = { before, next ->
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { CommercialStore.saveEdit(activity, rec.id, before, next, stamp) }
                    onChanged()
                }
            }
        )
    }

    private fun label(activity: AppCompatActivity, text: String) = TextView(activity).apply {
        this.text = text
        setPadding(0, 24, 0, 8)
    }

    private fun spinner(activity: AppCompatActivity, labels: List<String>) = Spinner(activity).apply {
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }
}
