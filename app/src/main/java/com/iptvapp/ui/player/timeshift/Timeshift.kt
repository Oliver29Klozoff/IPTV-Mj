package com.iptvapp.ui.player.timeshift

/**
 * Live-TV timeshift decisions that do not touch the player, disk, or network.
 *
 * Positions are relative to the current seekable window: 0 is the earliest byte still
 * available, and [TimeshiftReading.liveEdgeMs] is the live edge. A native provider window
 * and a local buffer both look like this once Media3 has a timeline.
 *
 * Timeshift is not a recording. Nothing here writes MediaStore or the download cache.
 */
enum class TimeshiftPlaybackState {
    LIVE,
    PAUSED,
    BEHIND_LIVE,
    SEEKING,
    RETURNING_TO_LIVE
}

enum class TimeshiftSource { NONE, NATIVE, LOCAL }

data class TimeshiftReading(
    val seekable: Boolean,
    val earliestMs: Long,
    val liveEdgeMs: Long,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val seeking: Boolean,
    val returningToLive: Boolean,
)

object TimeshiftNavigator {
    const val LIVE_EDGE_TOLERANCE_MS = 5_000L
    const val REPLAY_10_MS = 10_000L
    const val REPLAY_30_MS = 30_000L
    const val REPLAY_60_MS = 60_000L

    /** Shorter than this, a provider playlist is not a useful DVR window. */
    const val USEFUL_NATIVE_WINDOW_MS = 30_000L

    /** Hide the scrubber until there is something real to scrub. */
    const val MIN_CONTROLS_WINDOW_MS = 8_000L

    /**
     * While the buffer is still starting, the player is held paused on purpose.
     * The user's last request is [autoplay], not the empty player's playWhenReady.
     */
    fun intendedPlaying(starting: Boolean, autoplay: Boolean, playWhenReady: Boolean): Boolean =
        if (starting) autoplay else playWhenReady

    /**
     * Null is the initial start, which follows the live playlist's default position.
     * Zero is the beginning of the retained window and must not be discarded.
     */
    fun mediaStartMs(startMs: Long?): Long? = if (startMs != null && startMs >= 0L) startMs else null

    fun state(reading: TimeshiftReading): TimeshiftPlaybackState = when {
        reading.seeking -> TimeshiftPlaybackState.SEEKING
        reading.returningToLive && !atLiveEdge(reading) -> TimeshiftPlaybackState.RETURNING_TO_LIVE
        !reading.playWhenReady && reading.seekable -> TimeshiftPlaybackState.PAUSED
        reading.seekable && !atLiveEdge(reading) -> TimeshiftPlaybackState.BEHIND_LIVE
        else -> TimeshiftPlaybackState.LIVE
    }

    fun behindMs(reading: TimeshiftReading): Long =
        (reading.liveEdgeMs - reading.positionMs).coerceAtLeast(0L)

    fun atLiveEdge(reading: TimeshiftReading): Boolean =
        !reading.seekable || behindMs(reading) < LIVE_EDGE_TOLERANCE_MS

    /**
     * A pause within [LIVE_EDGE_TOLERANCE_MS] is still a pause. Dropping the window and
     * opening the provider would start playback again and lose that spot.
     * Playing at the edge has no offset to keep, so the player follows the provider.
     */
    fun followProviderOnStorageLoss(atLiveEdge: Boolean, intendedPlaying: Boolean): Boolean =
        intendedPlaying && atLiveEdge

    fun windowMs(reading: TimeshiftReading): Long =
        (reading.liveEdgeMs - reading.earliestMs).coerceAtLeast(0L)

    fun clamp(targetMs: Long, earliestMs: Long, liveEdgeMs: Long): Long {
        val end = liveEdgeMs.coerceAtLeast(earliestMs)
        return targetMs.coerceIn(earliestMs, end)
    }

    fun rewind(reading: TimeshiftReading, deltaMs: Long): Long =
        clamp(reading.positionMs - deltaMs.coerceAtLeast(0L), reading.earliestMs, reading.liveEdgeMs)

    fun forward(reading: TimeshiftReading, deltaMs: Long): Long =
        clamp(reading.positionMs + deltaMs.coerceAtLeast(0L), reading.earliestMs, reading.liveEdgeMs)

    /** Go Live always targets the live edge. It does not resume a paused offset. */
    fun goLive(reading: TimeshiftReading): Long = reading.liveEdgeMs.coerceAtLeast(reading.earliestMs)

    /**
     * Position in a freshly opened provider window that keeps the same distance behind live.
     * The window may have slid forward while the player was rebuilt, so the offset is measured
     * from the new live edge. Null leaves the item at its own default: already at the edge,
     * or the new window cannot be seeked yet.
     */
    fun nativeResumeMs(
        behindLiveMs: Long,
        seekable: Boolean,
        earliestMs: Long,
        liveEdgeMs: Long
    ): Long? {
        if (behindLiveMs < LIVE_EDGE_TOLERANCE_MS) return null
        if (!seekable || liveEdgeMs <= earliestMs) return null
        return clamp(liveEdgeMs - behindLiveMs, earliestMs, liveEdgeMs)
    }

    /** Resume keeps the paused position. Go Live is the only jump to the edge. */
    fun resumePosition(pausedPositionMs: Long): Long = pausedPositionMs

    fun controlsAvailable(timeshiftEnabled: Boolean, seekable: Boolean, windowMs: Long): Boolean =
        timeshiftEnabled && seekable && windowMs >= MIN_CONTROLS_WINDOW_MS

    /**
     * Media3 reports an unknown duration or live offset as its TIME_UNSET. Pass null for those.
     * [durationMs] and [positionMs] are the current window, whose start is the earliest seek.
     */
    fun readingFromTimeline(
        isLive: Boolean,
        seekable: Boolean,
        durationMs: Long?,
        positionMs: Long,
        liveOffsetMs: Long?,
        playWhenReady: Boolean,
        seeking: Boolean,
        returningToLive: Boolean,
    ): TimeshiftReading {
        val window = durationMs?.takeIf { it > 0L }
        val offset = liveOffsetMs?.takeIf { it >= 0L }
        // currentLiveOffset is distance from wall-clock live, not duration minus position.
        // Seeks use the timeline position. The offset only fills in an edge when duration is unknown.
        val liveEdge = window ?: positionMs.coerceAtLeast(0L) + (offset ?: 0L)
        val position = positionMs.coerceIn(0L, liveEdge)
        return TimeshiftReading(
            seekable = isLive && seekable && liveEdge > 0L,
            earliestMs = 0L,
            liveEdgeMs = liveEdge,
            positionMs = position,
            playWhenReady = playWhenReady,
            seeking = seeking,
            returningToLive = returningToLive,
        )
    }

    fun freshLive(edgeMs: Long = 0L): TimeshiftReading = TimeshiftReading(
        seekable = true,
        earliestMs = 0L,
        liveEdgeMs = edgeMs,
        positionMs = edgeMs,
        playWhenReady = true,
        seeking = false,
        returningToLive = false,
    )
}

/**
 * How long a local buffer may grow. The saved setting is the wish; free space can shorten
 * it or turn the local buffer off. Native provider windows are unaffected.
 */
object TimeshiftLimits {
    const val DEFAULT_MINUTES = 30
    const val ABSOLUTE_MAX_BYTES = 1536L * 1024L * 1024L
    const val LOW_FREE_BYTES = 512L * 1024L * 1024L
    const val LOW_DEVICE_FREE_BYTES = 2L * 1024L * 1024L * 1024L
    const val RESERVED_FREE_BYTES = 256L * 1024L * 1024L
    const val MIN_USEFUL_BYTES = 32L * 1024L * 1024L

    data class Budget(val durationMs: Long, val maxBytes: Long, val localEnabled: Boolean)

    fun normalizeMinutes(value: Int): Int = when (value) {
        // 5 is the Sports Mode session replay, used only for that viewing. It is not a saved setting.
        0, 5, 15, 30, 60 -> value
        else -> DEFAULT_MINUTES
    }

    fun resolve(requestedMinutes: Int, freeBytes: Long): Budget {
        val minutes = normalizeMinutes(requestedMinutes)
        if (minutes == 0 || freeBytes < LOW_FREE_BYTES) return Budget(0L, 0L, localEnabled = false)
        val capped = if (freeBytes < LOW_DEVICE_FREE_BYTES) minOf(minutes, 15) else minutes
        val share = freeBytes / 100L * 15L
        val headroom = (freeBytes - RESERVED_FREE_BYTES).coerceAtLeast(0L)
        val maxBytes = minOf(ABSOLUTE_MAX_BYTES, share, headroom)
        if (maxBytes < MIN_USEFUL_BYTES) return Budget(0L, 0L, localEnabled = false)
        return Budget(capped * 60_000L, maxBytes, localEnabled = true)
    }

    /** Stopping the local buffer must not stop the channel. */
    fun playbackContinuesWhenStorageStops(): Boolean = true
}

/**
 * What to do when the provider connection drops. A new URL (failover) or a restarted
 * playlist cannot be spliced onto the old buffer, so playback returns to the live edge.
 */
object TimeshiftReconnect {
    const val MAX_GAP_MS = 15_000L

    enum class Action { KEEP_BUFFER, RETURN_TO_LIVE }

    fun onStreamReconnect(sameUrl: Boolean, missedMs: Long, sequenceRestarted: Boolean): Action = when {
        !sameUrl || sequenceRestarted || missedMs > MAX_GAP_MS -> Action.RETURN_TO_LIVE
        else -> Action.KEEP_BUFFER
    }

    /** Returning to live is playing, not left looking paused. */
    fun stateAfter(action: Action, wasPaused: Boolean, stillBehind: Boolean): TimeshiftPlaybackState = when (action) {
        Action.RETURN_TO_LIVE -> TimeshiftPlaybackState.LIVE
        Action.KEEP_BUFFER -> when {
            wasPaused -> TimeshiftPlaybackState.PAUSED
            stillBehind -> TimeshiftPlaybackState.BEHIND_LIVE
            else -> TimeshiftPlaybackState.LIVE
        }
    }
}

object TimeshiftEpg {
    data class Show(val title: String, val startMs: Long, val stopMs: Long)

    /** Behind by less than this, the UI still describes the live programme. */
    const val LIVE_EPG_SLACK_MS = 15_000L

    fun programmeAt(shows: List<Show>, wallMs: Long): Show? =
        shows.firstOrNull { wallMs >= it.startMs && wallMs < it.stopMs }

    fun playbackWallMs(nowMs: Long, behindMs: Long): Long = nowMs - behindMs.coerceAtLeast(0L)

    /**
     * The show whose title and progress bar match the playhead.
     * At the live edge that is [nowMs]. [frozenEndpointMs] is the wall time of a retained
     * window's end. Behind is then distance from that end, so a pause does not walk the
     * guide forward as the clock moves.
     */
    fun programmeForUi(
        shows: List<Show>,
        nowMs: Long,
        behindMs: Long,
        atLive: Boolean,
        frozenEndpointMs: Long = 0L,
    ): Show? {
        val wall = when {
            frozenEndpointMs > 0L -> playbackWallMs(frozenEndpointMs, behindMs)
            atLive || behindMs < LIVE_EPG_SLACK_MS -> nowMs
            else -> playbackWallMs(nowMs, behindMs)
        }
        return programmeAt(shows, wall)
    }
}

object TimeshiftCopy {
    fun formatClock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    fun spokenDuration(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        val parts = ArrayList<String>(3)
        if (hours > 0) parts += if (hours == 1L) "1 hour" else "$hours hours"
        if (minutes > 0) parts += if (minutes == 1L) "1 minute" else "$minutes minutes"
        if (seconds > 0 || parts.isEmpty()) parts += if (seconds == 1L) "1 second" else "$seconds seconds"
        return parts.joinToString(" ")
    }

    fun liveButtonText(behindMs: Long, atLive: Boolean): String =
        if (atLive) "\u25CF LIVE" else "\u25CF LIVE -${formatClock(behindMs)}"

    fun liveButtonDescription(behindMs: Long, atLive: Boolean): String =
        if (atLive) "Live, at the live edge"
        else "Go live, ${spokenDuration(behindMs)} behind live"

    fun pauseDescription(playWhenReady: Boolean): String =
        if (playWhenReady) "Pause live TV" else "Resume"

    const val REPLAY_10 = "Replay 10 seconds"
    const val REPLAY_30 = "Replay 30 seconds"
    const val FORWARD_10 = "Forward 10 seconds"
}

data class HlsSegment(
    val uri: String,
    val durationMs: Long,
    val sequence: Long,
    val discontinuity: Boolean,
)

data class HlsProbe(
    val isMaster: Boolean,
    val isLive: Boolean,
    val windowMs: Long,
    val targetDurationSec: Int,
    val mediaSequence: Long,
    val variantUris: List<String>,
    val segments: List<HlsSegment>,
    /**
     * False when a local copy would drop encryption, an init map, a byte range, or a
     * separate audio rendition. Those streams play from the provider URL.
     */
    val canBufferLocally: Boolean,
)

object HlsPlaylistProbe {
    fun parse(text: String): HlsProbe {
        val lines = text.trimStart('\uFEFF').split('\n').map { it.trim('\r', ' ') }
        var master = false
        var endList = false
        var target = 4
        var sequence = 0L
        var nextSeq = 0L
        var pendingMs = -1L
        var pendingBreak = false
        var expectVariant = false
        var unsupported = false
        val variants = ArrayList<String>()
        val segments = ArrayList<HlsSegment>()
        for (line in lines) {
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                master = true
                expectVariant = true
                continue
            }
            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXTINF:") -> {
                        val raw = line.substringAfter("#EXTINF:").substringBefore(',')
                        pendingMs = ((raw.toDoubleOrNull() ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
                    }
                    line.startsWith("#EXT-X-TARGETDURATION:") ->
                        target = line.substringAfter(':').toIntOrNull()?.coerceAtLeast(1) ?: target
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                        sequence = line.substringAfter(':').toLongOrNull() ?: 0L
                        nextSeq = sequence
                    }
                    line.startsWith("#EXT-X-ENDLIST") -> endList = true
                    line.startsWith("#EXT-X-DISCONTINUITY") -> pendingBreak = true
                    line.startsWith("#EXT-X-KEY") ||
                        line.startsWith("#EXT-X-MAP") ||
                        line.startsWith("#EXT-X-BYTERANGE") -> unsupported = true
                    line.startsWith("#EXT-X-MEDIA:") && separateRendition(line) -> unsupported = true
                }
                continue
            }
            if (expectVariant) {
                variants += line
                expectVariant = false
                continue
            }
            if (pendingMs >= 0L) {
                segments += HlsSegment(line, pendingMs, nextSeq, pendingBreak)
                nextSeq++
                pendingMs = -1L
                pendingBreak = false
            }
        }
        val window = segments.sumOf { it.durationMs }
        return HlsProbe(
            isMaster = master && segments.isEmpty(),
            isLive = !endList && !master,
            windowMs = window,
            targetDurationSec = target,
            mediaSequence = sequence,
            variantUris = variants,
            segments = segments,
            canBufferLocally = !unsupported,
        )
    }

    fun usefulNative(probe: HlsProbe): Boolean =
        probe.isLive && !probe.isMaster && probe.segments.isNotEmpty() &&
            probe.windowMs >= TimeshiftNavigator.USEFUL_NATIVE_WINDOW_MS

    /** True when a rendition names its own playlist. Muxed audio has no URI and stays here. */
    private fun separateRendition(line: String): Boolean =
        !attribute(line.substringAfter(':'), "URI").isNullOrEmpty()

    private fun attribute(attrs: String, name: String): String? {
        var i = 0
        while (i < attrs.length) {
            while (i < attrs.length && (attrs[i] == ',' || attrs[i].isWhitespace())) i++
            if (i >= attrs.length) return null
            val eq = attrs.indexOf('=', i)
            if (eq < 0) return null
            val key = attrs.substring(i, eq).trim()
            val value: String
            val next: Int
            if (eq + 1 < attrs.length && attrs[eq + 1] == '"') {
                val end = attrs.indexOf('"', eq + 2)
                if (end < 0) return null
                value = attrs.substring(eq + 2, end)
                next = end + 1
            } else {
                val comma = attrs.indexOf(',', eq + 1).let { if (it < 0) attrs.length else it }
                value = attrs.substring(eq + 1, comma).trim()
                next = comma
            }
            if (key.equals(name, ignoreCase = true)) return value
            i = next
        }
        return null
    }
}

object TimeshiftPlaylist {
    /**
     * Raw TS publishes a ~1s first piece, then waits up to the cutter's 8s maximum.
     * Media3 calls a live playlist stuck at about 3.5 times TARGETDURATION, so a
     * target of 1s fails before the next piece exists. This floor stays for the session.
     */
    const val MIN_TARGET_SEC = 8

    data class Segment(
        val sequence: Long,
        val durationMs: Long,
        val fileName: String,
        val discontinuity: Boolean,
    )

    fun fileName(sequence: Long): String = "seg-%08d.ts".format(sequence)

    fun render(segments: List<Segment>, endList: Boolean = false, targetDurationSec: Int = MIN_TARGET_SEC): String {
        val mediaSequence = segments.firstOrNull()?.sequence ?: 0L
        val fromSegments = segments.maxOfOrNull { ((it.durationMs + 999L) / 1000L).toInt() } ?: 1
        // Never follow the current window downward. A later short piece must not shrink the target.
        val target = maxOf(MIN_TARGET_SEC, targetDurationSec, fromSegments)
        val text = StringBuilder()
        text.append("#EXTM3U\n")
        text.append("#EXT-X-VERSION:3\n")
        text.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
        text.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSequence).append('\n')
        for (segment in segments) {
            if (segment.discontinuity) text.append("#EXT-X-DISCONTINUITY\n")
            val seconds = segment.durationMs / 1000.0
            text.append("#EXTINF:").append("%.3f".format(java.util.Locale.US, seconds)).append(",\n")
            text.append(segment.fileName).append('\n')
        }
        if (endList) text.append("#EXT-X-ENDLIST\n")
        return text.toString()
    }

    fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return java.net.URI(base).resolve(ref).toString()
    }
}

/** One generation per channel. A stale callback from the previous channel must not apply. */
class TimeshiftSessionGate {
    var generation: Long = 0L
        private set

    fun channelChanged(): Long {
        generation += 1L
        return generation
    }

    fun stale(seen: Long): Boolean = seen != generation
}
