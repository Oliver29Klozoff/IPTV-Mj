package com.iptvapp.ui.player.timeshift

/**
 * Reads one provider connection and fills a [TimeshiftStore]. The player then plays the
 * local playlist, so pause and rewind do not open a second stream.
 *
 * A provider window that is already a useful DVR is not copied: the loop reports
 * [Step.NeedDirect] and the player keeps the original URL.
 */
class HlsIngest(
    private val store: TimeshiftStore,
    private val stillRunning: () -> Boolean = { true },
    private val fetchBytes: (String) -> ByteArray?,
) {
    var started: Boolean = false
        private set
    private var lastProviderSeq = -1L

    fun ingest(finalPlaylistUrl: String, text: String?): Step {
        if (text.isNullOrBlank()) return Step.Failed
        val probe = HlsPlaylistProbe.parse(text)
        if (probe.isMaster) {
            // Leave rendition choice to ExoPlayer. Copying one variant would ignore Data Saver
            // and adaptive selection, and a separate audio playlist would go silent.
            return if (started) Step.ContinuityLost else Step.NeedDirect
        }
        if (!probe.isLive) {
            return if (started) Step.ContinuityLost else Step.NeedDirect
        }
        // Encryption, init maps, and byte ranges are not copied into the local playlist.
        if (!probe.canBufferLocally) {
            return if (started) Step.ContinuityLost else Step.NeedDirect
        }
        if (!started && HlsPlaylistProbe.usefulNative(probe)) return Step.NeedDirect
        if (probe.segments.isEmpty()) return Step.Wait
        // A restarted playlist numbers its window below everything already stored. Filtering
        // those out would look like "no new segment" and the old buffer would keep playing.
        if (lastProviderSeq >= 0L && probe.segments.last().sequence < lastProviderSeq) {
            return Step.ContinuityLost
        }
        val incoming = if (lastProviderSeq < 0L) {
            listOf(probe.segments.last())
        } else {
            probe.segments.filter { it.sequence > lastProviderSeq }
        }
        if (incoming.isEmpty()) return Step.Wait
        if (lastProviderSeq >= 0L) {
            val missed = incoming.first().sequence - lastProviderSeq - 1L
            val missedMs = missed.coerceAtLeast(0L) * probe.targetDurationSec * 1000L
            val action = TimeshiftReconnect.onStreamReconnect(
                sameUrl = true,
                missedMs = missedMs,
                sequenceRestarted = false,
            )
            if (action == TimeshiftReconnect.Action.RETURN_TO_LIVE) return Step.ContinuityLost
        }
        var grew = false
        var discontinuity = lastProviderSeq >= 0L && incoming.first().sequence > lastProviderSeq + 1L
        for (segment in incoming) {
            // A channel change can land between two segments of the same playlist.
            // Do not open the next one.
            if (!stillRunning()) return Step.Failed
            val url = TimeshiftPlaylist.resolveUrl(finalPlaylistUrl, segment.uri)
            val bytes = fetchBytes(url) ?: return Step.Failed
            // A full disk throws from the file write. That is a storage stop, same as the
            // TS path: keep segments already stored instead of retrying like a network drop.
            val stored = try {
                store.append(bytes, segment.durationMs.coerceAtLeast(1L), discontinuity || segment.discontinuity)
            } catch (_: Exception) {
                return Step.Storage
            }
            when (stored) {
                TimeshiftStore.Append.LOW_STORAGE -> return Step.Storage
                TimeshiftStore.Append.SKIPPED -> Unit
                TimeshiftStore.Append.STORED -> grew = true
            }
            discontinuity = false
            lastProviderSeq = segment.sequence
        }
        if (!grew) return Step.Wait
        val ready = !started
        started = true
        return if (ready) Step.Ready(store.playlistFile) else Step.Grew
    }
}

class TsIngest(
    private val store: TimeshiftStore,
    private val cutter: TsCutter,
) {
    var started: Boolean = false
        private set

    fun accept(data: ByteArray, length: Int): Step {
        var grew = false
        var ready = false
        for (cut in cutter.feed(data, 0, length)) {
            when (store.append(cut.bytes, cut.durationMs, cut.discontinuity)) {
                TimeshiftStore.Append.LOW_STORAGE -> return Step.Storage
                TimeshiftStore.Append.SKIPPED -> Unit
                TimeshiftStore.Append.STORED -> {
                    grew = true
                    if (!started) ready = true
                    started = true
                }
            }
        }
        return when {
            ready -> Step.Ready(store.playlistFile)
            grew -> Step.Grew
            else -> Step.Wait
        }
    }

    fun markDiscontinuity() = cutter.markDiscontinuity()
}

sealed class Step {
    data object NeedDirect : Step()
    data object ContinuityLost : Step()
    data object Storage : Step()
    data object Failed : Step()
    data object Wait : Step()
    data object Grew : Step()
    data class Ready(val playlist: java.io.File) : Step()
    data class Follow(val url: String) : Step()
}

fun interface TimeshiftSleeper {
    fun sleep(ms: Long)
}

/**
 * One pass over the provider. [Fetcher] is OkHttp in the app and a fake in tests.
 * Callbacks run on the caller thread.
 */
class TimeshiftLoop(
    private val store: TimeshiftStore,
    private val fetch: Fetcher,
    private val sleeper: TimeshiftSleeper,
    private val listener: Listener,
    private val maxSteps: Int = Int.MAX_VALUE,
    private val cutterClock: () -> Long = System::currentTimeMillis,
) {
    interface Fetcher {
        fun open(url: String): Opened
    }

    interface Opened {
        fun sniff(): Kind
        fun playlistText(): String?
        fun finalUrl(): String
        fun read(buffer: ByteArray): Int
        fun close()
    }

    enum class Kind { HLS, TS, UNKNOWN }

    interface Listener {
        fun onReady(playlist: java.io.File) {}
        fun onGrew() {}
        fun onNeedDirect() {}
        fun onContinuityLost() {}
        fun onStorageDegraded() {}
        fun onGiveUp() {}
        fun onStopped() {}
        /** The kept window could not be finalized. Callers should return to the provider. */
        fun onRetainFailed() {}
    }

    @Volatile var closed: Boolean = false
    @Volatile var retainFiles: Boolean = false

    fun close() {
        closed = true
    }

    fun run(startUrl: String) {
        var url = startUrl
        var failures = 0
        var steps = 0
        val hls = HlsIngest(store, stillRunning = { !closed }) { segmentUrl ->
            if (closed) return@HlsIngest null
            try {
                fetch.open(segmentUrl).useForBytes()
            } catch (_: Exception) {
                null
            }
        }
        var ts: TsIngest? = null
        var opened: Opened? = null
        // Wall time of the last read that returned bytes. A stalled socket only fails when
        // the client timeout ends, so the hole starts at this byte, not at the throw.
        // A zero-length read is not a byte.
        var lastGoodReadMs = -1L
        try {
            if (!isHttp(url)) {
                listener.onNeedDirect()
                return
            }
            while (!closed && steps++ < maxSteps) {
                if (ts == null) {
                    if (closed) return
                    val body = try {
                        fetch.open(url)
                    } catch (_: Exception) {
                        null
                    }
                    if (body == null) {
                        if (!failOnce(failures)) return else failures++
                        continue
                    }
                    try {
                        when (body.sniff()) {
                            Kind.HLS -> {
                                val text = body.playlistText()
                                val finalUrl = body.finalUrl()
                                body.close()
                                when (val step = hls.ingest(finalUrl, text)) {
                                    is Step.Follow -> url = step.url
                                    Step.Failed -> if (!failOnce(failures)) return else failures++
                                    else -> {
                                        if (!dispatch(step)) return
                                        if (hls.started) failures = 0
                                        if (!closed) sleeper.sleep(POLL_MS)
                                    }
                                }
                            }
                            Kind.TS -> {
                                ts = TsIngest(store, TsCutter(clock = cutterClock))
                                opened = body
                                failures = 0
                            }
                            Kind.UNKNOWN -> {
                                body.close()
                                listener.onNeedDirect()
                                return
                            }
                        }
                    } catch (_: Exception) {
                        try {
                            body.close()
                        } catch (_: Exception) {
                        }
                        opened = null
                        ts = null
                        if (!failOnce(failures)) return else failures++
                    }
                } else {
                    val source = opened ?: break
                    val buffer = ByteArray(16 * 1024)
                    val read = try {
                        source.read(buffer)
                    } catch (_: Exception) {
                        -1
                    }
                    if (read == 0) {
                        sleeper.sleep(50L)
                        continue
                    }
                    if (read < 0) {
                        try {
                            source.close()
                        } catch (_: Exception) {
                        }
                        opened = null
                        // An empty 200 is not progress. Count it before opening another connection,
                        // or a body that sniffs as TS and then ends will reset the limit forever.
                        // A hole longer than the reconnect limit cannot be spliced onto a buffer
                        // the viewer may be paused in. Measure it from the last byte: the read
                        // that just failed may have been blocked for the whole outage.
                        val brokeAt = if (ts.started && lastGoodReadMs >= 0L) lastGoodReadMs else -1L
                        if (!failOnce(failures)) return
                        failures++
                        // Retry this same URL until it comes back or the failure limit is hit.
                        // Leaving opened null with a TS ingest set would exit on the next pass
                        // and delete the buffer without telling the player.
                        var recovered = false
                        while (!closed && !recovered) {
                            val again = try {
                                fetch.open(url)
                            } catch (_: Exception) {
                                null
                            }
                            val kind = try {
                                again?.sniff()
                            } catch (_: Exception) {
                                null
                            }
                            if (again != null && kind == Kind.TS) {
                                val gapMs = if (brokeAt >= 0L) (cutterClock() - brokeAt).coerceAtLeast(0L) else 0L
                                val action = TimeshiftReconnect.onStreamReconnect(
                                    sameUrl = true,
                                    missedMs = gapMs,
                                    sequenceRestarted = false,
                                )
                                if (action == TimeshiftReconnect.Action.RETURN_TO_LIVE) {
                                    try {
                                        again.close()
                                    } catch (_: Exception) {
                                    }
                                    dispatch(Step.ContinuityLost)
                                    return
                                }
                                ts.markDiscontinuity()
                                opened = again
                                recovered = true
                                break
                            }
                            try {
                                again?.close()
                            } catch (_: Exception) {
                            }
                            if (!failOnce(failures)) return
                            failures++
                        }
                        continue
                    }
                    failures = 0
                    lastGoodReadMs = cutterClock()
                    // A full disk or a deleted cache directory throws from the file write.
                    // That must not escape the worker thread.
                    val step = try {
                        ts.accept(buffer, read)
                    } catch (_: Exception) {
                        Step.Storage
                    }
                    if (!dispatch(step)) return
                }
            }
        } finally {
            opened?.close()
            if (!retainFiles) store.deleteAll()
            listener.onStopped()
        }
    }

    /** @return false when the loop must stop. Increments nothing; the caller counts [failures]. */
    private fun failOnce(failures: Int): Boolean {
        val next = failures + 1
        if (next >= MAX_FAILURES) {
            listener.onGiveUp()
            return false
        }
        sleeper.sleep(backoff(next))
        return true
    }

    private fun dispatch(step: Step): Boolean {
        when (step) {
            Step.NeedDirect -> listener.onNeedDirect()
            Step.ContinuityLost -> listener.onContinuityLost()
            Step.Storage -> {
                retainFiles = true
                listener.onStorageDegraded()
            }
            Step.Failed -> listener.onGiveUp()
            Step.Grew -> listener.onGrew()
            Step.Wait -> Unit
            is Step.Ready -> listener.onReady(step.playlist)
            is Step.Follow -> Unit
        }
        return step is Step.Wait || step is Step.Grew || step is Step.Ready
    }

    companion object {
        const val MAX_FAILURES = 5
        const val POLL_MS = 1_000L

        fun isHttp(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

        fun backoff(failures: Int): Long = (1_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(16_000L)

        fun sniff(prefix: ByteArray, contentType: String?): Kind {
            val type = contentType?.lowercase().orEmpty()
            val text = prefix.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            if (text.startsWith("#EXTM3U") || "mpegurl" in type || "mpegURL" in (contentType ?: "")) return Kind.HLS
            if (prefix.size >= TsCutter.PACKET && prefix[0] == 0x47.toByte()) return Kind.TS
            val scan = minOf(prefix.size - TsCutter.PACKET, 376)
            if (scan > 0) {
                for (i in 0 until scan) {
                    if (prefix[i] == 0x47.toByte() && prefix[i + TsCutter.PACKET] == 0x47.toByte()) return Kind.TS
                }
            }
            if ("mp2t" in type || type.contains("video/mp2t")) return Kind.TS
            return Kind.UNKNOWN
        }
    }
}

private fun TimeshiftLoop.Opened.useForBytes(): ByteArray? {
    try {
        if (sniff() == TimeshiftLoop.Kind.HLS) return null
        return try {
            collectSegment({ read(it) }, MAX_SEGMENT_BYTES)
        } catch (_: Exception) {
            null
        }
    } finally {
        close()
    }
}

/** Reads one segment. A body larger than [cap] is rejected whole, not stored short. */
internal fun collectSegment(read: (ByteArray) -> Int, cap: Int): ByteArray? {
    if (cap <= 0) return null
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(minOf(16 * 1024, cap))
    var total = 0
    while (total < cap) {
        val n = read(buffer)
        if (n < 0) break
        if (n == 0) continue
        val room = cap - total
        if (n > room) return null
        out.write(buffer, 0, n)
        total += n
    }
    if (total >= cap && read(ByteArray(1)) > 0) return null
    return if (total == 0) null else out.toByteArray()
}

private const val MAX_SEGMENT_BYTES = 20 * 1024 * 1024
