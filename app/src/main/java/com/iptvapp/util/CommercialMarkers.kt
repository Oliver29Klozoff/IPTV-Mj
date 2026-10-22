package com.iptvapp.util

/**
 * Commercial intervals for one finished recording.
 *
 * Times are on the player's timeline: milliseconds from the start of playback.
 * [endMs] is exclusive, and a skip seeks to [endMs]. Suggested intervals are
 * only a review aid. Skipping uses confirmed intervals. A rejected interval is
 * kept so the same analysis does not put it back.
 */
object CommercialMarkers {
    const val ANALYSIS_VERSION = 1

    /** One background job per recording. A second request for the same recording replaces or keeps this name. */
    fun analysisWorkName(recordingId: Int): String = "ad-breaks-$recordingId"
    const val WINDOW_MS = 100L
    const val MIN_SEGMENT_MS = 10_000L
    const val MAX_SEGMENT_MS = 8 * 60_000L
    const val SKIP_TAIL_MS = 3_000L
    private const val MIN_BREAK_MS = 60_000L
    private const val MERGE_GAP_MS = 20_000L
    private val AD_LENGTHS = listOf(10.0, 15.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0)
    private const val SLACK_S = 1.6

    enum class Origin { MANUAL, AUTOMATIC }
    enum class Review { SUGGESTED, CONFIRMED, REJECTED }
    enum class DetectionMode { OFF, ON_DEMAND, AUTOMATIC }
    enum class SkipMode { OFF, BUTTON, AUTOMATIC }

    fun detectionModeOf(stored: String?): DetectionMode = when (stored) {
        "OFF" -> DetectionMode.OFF
        "AUTOMATIC" -> DetectionMode.AUTOMATIC
        else -> DetectionMode.ON_DEMAND
    }

    fun skipModeOf(stored: String?): SkipMode = when (stored) {
        "OFF" -> SkipMode.OFF
        "AUTOMATIC" -> SkipMode.AUTOMATIC
        else -> SkipMode.BUTTON
    }

    data class FileStamp(val size: Long, val modifiedMs: Long) {
        fun known(): Boolean = size > 0L
    }

    data class Segment(
        val startMs: Long,
        val endMs: Long,
        val origin: Origin,
        val review: Review,
        val evidence: String,
        val fileSize: Long = 0L,
        val fileModifiedMs: Long = 0L,
        val analysisVersion: Int = ANALYSIS_VERSION,
        val updatedAtMs: Long = 0L
    ) {
        fun key(): String = "$startMs-$endMs"
        fun lengthMs(): Long = endMs - startMs
    }

    data class Undo(val key: String, val fromMs: Long, val toMs: Long)

    data class Session(
        val handled: Set<String> = emptySet(),
        val left: Set<String> = emptySet(),
        val undoHold: String? = null,
        val undoHoldSeen: Boolean = false,
        val undo: Undo? = null
    )

    sealed class Action {
        data object None : Action()
        data class ShowSkip(val segment: Segment, val remainingMs: Long) : Action()
        data class AutoSkip(val segment: Segment, val toMs: Long) : Action()
    }

    /** A usable interval, or null when the times cannot be a commercial. */
    fun checked(startMs: Long, endMs: Long, durationMs: Long): Pair<Long, Long>? {
        if (startMs < 0L || endMs < 0L) return null
        if (endMs <= startMs) return null
        val end = if (durationMs > 0L && endMs > durationMs) durationMs else endMs
        if (end <= startMs) return null
        if (end - startMs < 1_000L) return null
        return startMs to end
    }

    fun manual(startMs: Long, endMs: Long, durationMs: Long, stamp: FileStamp, nowMs: Long): Segment? {
        val span = checked(startMs, endMs, durationMs) ?: return null
        return Segment(
            span.first, span.second, Origin.MANUAL, Review.CONFIRMED, "manual",
            stamp.size, stamp.modifiedMs, ANALYSIS_VERSION, nowMs
        )
    }

    /** Silence rhythm from [silenceMarks], as suggested intervals. Not confirmed. */
    fun suggestionsFromSilences(silences: List<Long>, stamp: FileStamp, nowMs: Long): List<Segment> =
        breaksFromSilences(silences).map {
            Segment(
                it.first, it.second, Origin.AUTOMATIC, Review.SUGGESTED, "silence_rhythm",
                stamp.size, stamp.modifiedMs, ANALYSIS_VERSION, nowMs
            )
        }

    /**
     * Middles of quiet runs, in milliseconds. A NaN sample is a hole in the
     * recording, not silence. Quiet means well below the recording's own level.
     */
    fun silenceMarks(levels: FloatArray, windowMs: Long = WINDOW_MS): List<Long> {
        val sorted = levels.filter { !it.isNaN() }.sorted()
        if (sorted.isEmpty()) return emptyList()
        val median = sorted[sorted.size / 2]
        val threshold = minOf(400f, maxOf(30f, median * 0.06f))
        val out = mutableListOf<Long>()
        var runStart = -1
        for (i in levels.indices) {
            val quiet = !levels[i].isNaN() && kotlin.math.abs(levels[i]) < threshold
            if (quiet && runStart < 0) runStart = i
            if ((!quiet || i == levels.lastIndex) && runStart >= 0) {
                val runEnd = if (quiet) i else i - 1
                if (runEnd - runStart + 1 >= 2) {
                    out += ((runStart + runEnd) / 2L) * windowMs + windowMs / 2
                }
                runStart = -1
            }
        }
        return out
    }

    /** Start inclusive, end exclusive. A run needs three silences on an ad spacing and a minute of span. */
    fun breaksFromSilences(silences: List<Long>): List<Pair<Long, Long>> {
        val runs = mutableListOf<Pair<Long, Long>>()
        var i = 0
        while (i < silences.size) {
            var j = i
            while (j + 1 < silences.size && adSpacing(silences[j + 1] - silences[j])) j++
            if (j - i >= 2 && silences[j] - silences[i] >= MIN_BREAK_MS) {
                runs += silences[i] to silences[j]
            }
            i = if (j > i) j else i + 1
        }
        val merged = mutableListOf<Pair<Long, Long>>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.second <= MERGE_GAP_MS) {
                merged[merged.lastIndex] = last.first to r.second
            } else {
                merged += r
            }
        }
        return merged.filter { it.second - it.first in MIN_SEGMENT_MS..MAX_SEGMENT_MS }
    }

    private fun adSpacing(ms: Long): Boolean {
        val seconds = ms / 1000.0
        return AD_LENGTHS.any { kotlin.math.abs(seconds - it) <= SLACK_S }
    }

    fun overlaps(a: Segment, b: Segment): Boolean = a.startMs < b.endMs && b.startMs < a.endMs

    /** True when most of the shorter interval sits inside the other. */
    fun sameBreak(a: Segment, b: Segment): Boolean {
        val overlap = minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)
        if (overlap <= 0L) return false
        val shorter = minOf(a.lengthMs(), b.lengthMs()).coerceAtLeast(1L)
        return overlap * 2 >= shorter
    }

    /**
     * New suggestions replace only untouched automatic suggestions. Confirmed, rejected,
     * and manual rows stay, including a suggestion whose boundary the viewer moved.
     * A new suggestion that repeats one of those breaks is dropped only when that
     * decision names this same file. A marker from an older copy stays stored, and
     * playback already hides it, but it must not hide a break found in the file
     * being analyzed.
     */
    fun applyAnalysis(existing: List<Segment>, suggestions: List<Segment>, stamp: FileStamp): List<Segment> {
        val kept = existing.filter { it.review != Review.SUGGESTED || it.origin == Origin.MANUAL }
        val fresh = suggestions
            .filter { it.review == Review.SUGGESTED && it.origin == Origin.AUTOMATIC }
            .map {
                it.copy(fileSize = stamp.size, fileModifiedMs = stamp.modifiedMs, analysisVersion = ANALYSIS_VERSION)
            }
            .filter { suggestion ->
                kept.none { sameFile(it, stamp) && sameBreak(it, suggestion) }
            }
        return (kept + fresh)
            .distinctBy { "${it.key()}-${it.review.name}-${it.fileSize}-${it.fileModifiedMs}" }
            .sortedBy { it.startMs }
    }

    private fun sameFile(segment: Segment, stamp: FileStamp): Boolean =
        segment.fileSize == stamp.size && segment.fileModifiedMs == stamp.modifiedMs

    /**
     * Applies one editor change to the rows stored now. [before] and [after] are the dialog's
     * lists. Rows that appeared after the dialog opened, such as a finished analysis, stay.
     */
    fun applyEdit(current: List<Segment>, before: List<Segment>, after: List<Segment>): List<Segment> {
        fun id(segment: Segment) = segment.key() to segment.review
        val beforeById = before.associateBy { id(it) }
        val afterById = after.associateBy { id(it) }
        val removed = beforeById.keys - afterById.keys
        // Confirming an existing marker can change its file stamp without changing its range or review.
        val changed = afterById.filter { (key, segment) ->
            val previous = beforeById[key]
            previous != null && previous != segment
        }
        val added = after.filter { id(it) !in beforeById }
        return (current.filter { id(it) !in removed && id(it) !in changed } + changed.values + added)
            .distinctBy { it.key() + it.review.name }
            .sortedBy { it.startMs }
    }

    /**
     * The note key that belongs to rows read while the note stayed on [beforeKey].
     * A different [afterKey] was saved with rows this read did not see, so the caller tries again.
     */
    fun cacheKeyIfUnchanged(beforeKey: String, afterKey: String): String? =
        if (beforeKey == afterKey) afterKey else null

    /** Fills in a missing file stamp. Rows that already name a file are left as they are. */
    fun stampMissingFiles(segments: List<Segment>, stamp: FileStamp): List<Segment> {
        if (!stamp.known()) return segments
        return segments.map { row ->
            if (row.fileSize == 0L) row.copy(fileSize = stamp.size, fileModifiedMs = stamp.modifiedMs) else row
        }
    }

    /** Intervals that may draw and skip for this file. A different file stamp does not qualify. */
    fun forFile(segments: List<Segment>, stamp: FileStamp): List<Segment> {
        if (!stamp.known()) return emptyList()
        return segments.filter {
            it.review != Review.REJECTED && it.fileSize == stamp.size && it.fileModifiedMs == stamp.modifiedMs
        }
    }

    fun effectiveSkip(global: SkipMode, override: SkipMode?): SkipMode = override ?: global

    /**
     * One step of playback. Scrubbing does not skip and does not change what
     * has already been skipped. Automatic mode seeks once per entry into a
     * confirmed interval.
     */
    fun tick(
        positionMs: Long,
        durationMs: Long,
        segments: List<Segment>,
        skipMode: SkipMode,
        playing: Boolean,
        scrubbing: Boolean,
        seekable: Boolean,
        session: Session
    ): Pair<Action, Session> {
        if (durationMs <= 0L || positionMs < 0L) return Action.None to session
        if (scrubbing) {
            val shown = if (seekable) buttonTarget(positionMs, segments) else null
            val action = if (skipMode != SkipMode.OFF && shown != null) {
                Action.ShowSkip(shown, (shown.endMs - positionMs).coerceAtLeast(0L))
            } else Action.None
            return action to session
        }
        var next = session
        val inside = segments.firstOrNull { it.review == Review.CONFIRMED && positionMs >= it.startMs && positionMs < it.endMs }
        val insideKey = inside?.key()
        // The hold stays through the seek back. It ends only after playback has
        // been seen inside the interval and has then left it.
        if (next.undoHold != null && next.undoHold == insideKey) {
            next = next.copy(undoHoldSeen = true)
        } else if (next.undoHold != null && next.undoHoldSeen) {
            next = next.copy(undoHold = null, undoHoldSeen = false)
        }
        val leftNow = next.handled.filter { it != insideKey }.toSet()
        next = next.copy(left = next.left + leftNow)
        if (insideKey != null && insideKey in next.left && next.undoHold != insideKey) {
            next = next.copy(handled = next.handled - insideKey, left = next.left - insideKey)
        }
        if (skipMode == SkipMode.AUTOMATIC && playing && seekable && inside != null &&
            inside.key() !in next.handled && next.undoHold != inside.key()
        ) {
            val toMs = inside.endMs.coerceAtMost(durationMs)
            next = next.copy(
                handled = next.handled + inside.key(),
                undo = Undo(inside.key(), positionMs, toMs)
            )
            return Action.AutoSkip(inside, toMs) to next
        }
        val button = if (skipMode == SkipMode.OFF || !seekable) null else buttonTarget(positionMs, segments)
        val action = if (button != null) Action.ShowSkip(button, (button.endMs - positionMs).coerceAtLeast(0L)) else Action.None
        return action to next
    }

    fun buttonTarget(positionMs: Long, segments: List<Segment>): Segment? =
        segments.firstOrNull {
            it.review == Review.CONFIRMED &&
                positionMs >= it.startMs &&
                positionMs < it.endMs - SKIP_TAIL_MS
        }

    /**
     * Where a skip lands, on the player timeline. An MPEG-TS seek stops on a
     * keyframe, which can be a little before this time. Do not seek again just
     * because playback is still inside the interval.
     */
    fun skipTarget(segment: Segment, durationMs: Long): Long =
        segment.endMs.coerceIn(0L, if (durationMs > 0L) durationMs else segment.endMs)

    /** Back to the pre-skip spot. Automatic skip stays off until playback has been inside and then left. */
    fun undo(session: Session): Pair<Long, Session>? {
        val undo = session.undo ?: return null
        return undo.fromMs to session.copy(
            undo = null,
            undoHold = undo.key,
            undoHoldSeen = false,
            handled = session.handled + undo.key
        )
    }

    fun dismissUndo(session: Session): Session = session.copy(undo = null)

    fun confirm(segment: Segment, nowMs: Long): Segment =
        segment.copy(review = Review.CONFIRMED, updatedAtMs = nowMs)

    fun reject(segment: Segment, nowMs: Long): Segment =
        segment.copy(review = Review.REJECTED, updatedAtMs = nowMs)

    fun without(segments: List<Segment>, segment: Segment): List<Segment> =
        segments.filterNot { it.key() == segment.key() && it.review == segment.review }

    /** Automatic suggestions only. Confirmed, rejected, and manual rows stay. */
    fun dropSuggestions(segments: List<Segment>): List<Segment> =
        segments.filterNot { it.origin == Origin.AUTOMATIC && it.review == Review.SUGGESTED }

    /** Share of the timeline, 0 at the start and 1 at the known duration. The marker bar uses this. */
    fun fraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return positionMs.coerceIn(0L, durationMs).toFloat() / durationMs.toFloat()
    }

    fun libraryStatus(
        recordingDone: Boolean,
        analyzed: Boolean,
        analyzing: Boolean,
        failed: Boolean,
        unsupported: Boolean,
        segments: List<Segment>
    ): String {
        if (!recordingDone) return ""
        if (analyzing) return "Analyzing"
        if (segments.any { it.review == Review.SUGGESTED }) return "Review Suggested Markers"
        if (segments.any { it.review == Review.CONFIRMED }) return "Commercials Found"
        if (unsupported) return "Analysis Unsupported"
        if (failed) return "Analysis Failed"
        if (analyzed) return "No Commercials Detected"
        return "Not Analyzed"
    }

    /**
     * Library note after a marker edit. NONE means analysis finished and found nothing.
     * A recording that was never analyzed stays blank, so removing a manual marker does not
     * look like a finished scan. A failed or unsupported scan stays failed or unsupported.
     */
    /** Note written when a scan finishes. An empty result is a finished scan, not an unanalyzed recording. */
    fun statusAfterAnalysis(segments: List<Segment>): String = when {
        segments.any { it.review == Review.SUGGESTED } -> "REVIEW"
        segments.any { it.review == Review.CONFIRMED } -> "FOUND"
        else -> "NONE"
    }

    fun statusAfterMarkerEdit(segments: List<Segment>, analyzed: Boolean, previousStatus: String?): String = when {
        segments.any { it.review == Review.SUGGESTED } -> "REVIEW"
        segments.any { it.review == Review.CONFIRMED } -> "FOUND"
        analyzed -> "NONE"
        previousStatus == "FAILED" || previousStatus == "UNSUPPORTED" -> previousStatus
        else -> ""
    }

    /**
     * What to store when analysis stops before it saves. A finished note is restored.
     * A note left on [runningStatus] is rebuilt from the markers still stored.
     * Null means there is nothing to store.
     */
    fun statusAfterStoppedAnalysis(previousStatus: String?, runningStatus: String, segments: List<Segment>): String? {
        if (previousStatus != null && previousStatus != runningStatus) return previousStatus
        if (segments.any { it.review == Review.SUGGESTED }) return "REVIEW"
        if (segments.any { it.review == Review.CONFIRMED }) return "FOUND"
        return null
    }

    fun label(segment: Segment): String {
        val kind = when (segment.review) {
            Review.SUGGESTED -> "Suggested"
            Review.CONFIRMED -> "Confirmed"
            Review.REJECTED -> "Not a commercial"
        }
        return "${clock(segment.startMs)}–${clock(segment.endMs)} · $kind"
    }

    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    fun remainingLabel(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }
}
