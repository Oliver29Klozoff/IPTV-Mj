package com.iptvapp.ui.player

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.iptvapp.util.CommercialMarkers
import com.iptvapp.util.CommercialStore

/**
 * Remote- and touch-friendly marker list. The recording file is not changed.
 * [positionMs] and [durationMs] are read when a row is chosen, so a marker
 * uses the spot that is playing at that moment.
 */
object CommercialEditor {
    fun show(
        context: Context,
        recordingId: Int,
        segments: List<CommercialMarkers.Segment>,
        stamp: CommercialMarkers.FileStamp,
        positionMs: () -> Long,
        durationMs: () -> Long,
        onSeek: (Long) -> Unit,
        onChanged: (List<CommercialMarkers.Segment>, List<CommercialMarkers.Segment>) -> Unit
    ) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val here = positionMs()
        // The library editor has no playhead. Position 0 there is not the start of a commercial.
        if (durationMs() > 0L) {
            labels += "Mark commercial start (${CommercialMarkers.clock(here)})"
            actions += {
                pendingStart = positionMs()
                show(context, recordingId, segments, stamp, positionMs, durationMs, onSeek, onChanged)
            }
            labels += if (pendingStart >= 0L) {
                "Mark commercial end (${CommercialMarkers.clock(here)})"
            } else {
                "Mark commercial end"
            }
            actions += {
                val start = pendingStart
                val made = if (start < 0L) null else CommercialMarkers.manual(start, positionMs(), durationMs(), stamp, System.currentTimeMillis())
                if (start >= 0L) pendingStart = -1L
                if (made != null) {
                    val next = CommercialMarkers.addedManual(segments, made)
                    onChanged(segments, next)
                    show(context, recordingId, next, stamp, positionMs, durationMs, onSeek, onChanged)
                }
            }
        } else {
            labels += "Play the recording to mark a commercial"
            actions += { onSeek(0L) }
        }
        val skip = CommercialMarkers.effectiveSkip(
            CommercialStore.skipMode(context),
            CommercialStore.note(context, recordingId)?.skipOverride
        )
        labels += "Skipping: ${skip.name.lowercase().replaceFirstChar { it.uppercase() }}"
        actions += {
            val order = CommercialMarkers.SkipMode.entries
            val nextMode = order[(order.indexOf(skip) + 1) % order.size]
            val note = CommercialStore.note(context, recordingId)
            CommercialStore.putNote(
                context,
                recordingId,
                CommercialStore.AnalysisNote(
                    note?.status ?: "",
                    if (stamp.known()) stamp.size else note?.size ?: 0L,
                    if (stamp.known()) stamp.modifiedMs else note?.modifiedMs ?: 0L,
                    nextMode,
                    note?.attempt ?: 0L,
                    note?.analyzed == true,
                    note?.revision ?: 0L
                )
            )
            show(context, recordingId, segments, stamp, positionMs, durationMs, onSeek, onChanged)
        }
        segments.sortedBy { it.startMs }.forEach { segment ->
            labels += CommercialMarkers.label(segment)
            actions += { editOne(context, recordingId, segments, segment, stamp, positionMs, durationMs, onSeek, onChanged) }
        }
        AlertDialog.Builder(context)
            .setTitle("Commercial markers")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editOne(
        context: Context,
        recordingId: Int,
        segments: List<CommercialMarkers.Segment>,
        segment: CommercialMarkers.Segment,
        stamp: CommercialMarkers.FileStamp,
        positionMs: () -> Long,
        durationMs: () -> Long,
        onSeek: (Long) -> Unit,
        onChanged: (List<CommercialMarkers.Segment>, List<CommercialMarkers.Segment>) -> Unit
    ) {
        val labels = mutableListOf("Jump to start", "Confirm", "Not a commercial")
        if (durationMs() > 0L) {
            labels += "Set start to current time"
            labels += "Set end to current time"
        }
        labels += "Remove"
        AlertDialog.Builder(context)
            .setTitle(CommercialMarkers.label(segment))
            .setItems(labels.toTypedArray()) { _, which ->
                val choice = labels[which]
                if (choice == "Jump to start") {
                    onSeek(segment.startMs)
                    return@setItems
                }
                val now = System.currentTimeMillis()
                val next = when (choice) {
                    "Confirm" -> segments.map { if (it == segment) CommercialMarkers.confirm(it, now).copy(fileSize = stamp.size, fileModifiedMs = stamp.modifiedMs) else it }
                    "Not a commercial" -> segments.map { if (it == segment) CommercialMarkers.reject(it, now) else it }
                    "Set start to current time" -> retimed(segments, segment, positionMs(), segment.endMs, durationMs(), stamp, now)
                    "Set end to current time" -> retimed(segments, segment, segment.startMs, positionMs(), durationMs(), stamp, now)
                    else -> CommercialMarkers.without(segments, segment)
                }
                onChanged(segments, next)
                show(context, recordingId, next, stamp, positionMs, durationMs, onSeek, onChanged)
            }
            .setNegativeButton("Back") { _, _ ->
                show(context, recordingId, segments, stamp, positionMs, durationMs, onSeek, onChanged)
            }
            .show()
    }

    private fun retimed(
        segments: List<CommercialMarkers.Segment>,
        segment: CommercialMarkers.Segment,
        startMs: Long,
        endMs: Long,
        durationMs: Long,
        stamp: CommercialMarkers.FileStamp,
        now: Long
    ): List<CommercialMarkers.Segment> {
        val span = CommercialMarkers.checked(startMs, endMs, durationMs) ?: return segments
        val updated = segment.copy(
            startMs = span.first,
            endMs = span.second,
            origin = CommercialMarkers.Origin.MANUAL,
            fileSize = stamp.size,
            fileModifiedMs = stamp.modifiedMs,
            updatedAtMs = now
        )
        return segments.map { if (it == segment) updated else it }.sortedBy { it.startMs }
    }

    /** Start chosen in this dialog, kept until the end is chosen or the player is left. */
    private var pendingStart = -1L

    fun clearPending() {
        pendingStart = -1L
    }
}
