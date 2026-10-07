package com.iptvapp.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.coroutines.cancellation.CancellationException
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ad skip (v7.15): finds commercial breaks in a finished recording from its sound alone.
 *
 * Broadcasters drop to a moment of silence between commercials, and commercials run 15, 30, 45 or
 * 60 seconds (sometimes 10 or 20, or 90 / 120 for promos). So the method — the same idea the
 * well-known comskip tool uses — is: decode the audio, find the short silences, and call a break
 * any run of 3+ silences spaced at those lengths (a minute or more in all). Shows have silences
 * too, but not on that rhythm. It guesses: some channels don't leave clean silences, and then
 * nothing is found (which just means no Skip button).
 *
 * Breaks are kept per recording id in SharedPreferences "recording_breaks" as "start-end,…" ms.
 */
object AdBreakDetector {

    private const val PREFS = "recording_breaks"
    private const val WINDOW_MS = 100L
    // Spacings (seconds) between silences inside a break, with this much slack either way.
    private val AD_LENGTHS = listOf(10.0, 15.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0)
    private const val SLACK_S = 1.6
    private const val MIN_BREAK_MS = 60_000L

    fun breaksFor(context: Context, recordingId: Int): List<LongRange> =
        (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(recordingId.toString(), "") ?: "")
            .split(',').mapNotNull { part ->
                val (a, b) = part.split('-').takeIf { it.size == 2 } ?: return@mapNotNull null
                val s = a.toLongOrNull() ?: return@mapNotNull null
                val e = b.toLongOrNull() ?: return@mapNotNull null
                s until e
            }

    /** True once this recording has been analyzed, even if no breaks were found. */
    fun isAnalyzed(context: Context, recordingId: Int): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(recordingId.toString())

    fun save(context: Context, recordingId: Int, breaks: List<LongRange>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(recordingId.toString(), breaks.joinToString(",") { "${it.first}-${it.last + 1}" })
            .apply()
    }

    fun forget(context: Context, recordingId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(recordingId.toString()).apply()
    }

    /** Breaks found in the recording at [uri]; empty if none or the audio can't be read. */
    fun detect(context: Context, uri: Uri, isStopped: () -> Boolean = { false }): List<LongRange> {
        val levels = audioLevels(context, uri, isStopped) ?: return emptyList()
        if (levels.size < 600) return emptyList()   // under a minute of audio
        val silences = silenceTimes(levels)
        return breaksFrom(silences)
    }

    /** RMS level (0..32768) of each 100 ms of the playback timeline, all channels mixed. Windows
     * are placed by the decoder's presentation timestamps (from the first one, which is where
     * playback starts), so a gap in a recording — a reconnect mid-capture — stays a gap (NaN, never
     * counted as silence) instead of shifting every later break earlier. [isStopped] is checked
     * as it decodes; a stop throws CancellationException. */
    private fun audioLevels(context: Context, uri: Uri, isStopped: () -> Boolean): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sums = DoubleArray(40_000)
            var counts = IntArray(40_000)
            var lastWindow = -1
            var firstPtsUs = Long.MIN_VALUE
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (isStopped()) throw CancellationException("ad break analysis stopped")
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            if (firstPtsUs == Long.MIN_VALUE) firstPtsUs = info.presentationTimeUs
                            val out = codec.getOutputBuffer(outIndex)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val shorts = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val startUs = info.presentationTimeUs - firstPtsUs
                            var frame = 0L
                            var ch = 0
                            while (shorts.hasRemaining()) {
                                val v = shorts.get().toDouble()
                                val tUs = startUs + frame * 1_000_000L / sampleRate
                                val w = (tUs / (WINDOW_MS * 1000L)).toInt()
                                if (w >= 0) {
                                    if (w >= sums.size) {
                                        val n = maxOf(w + 1, sums.size * 2)
                                        sums = sums.copyOf(n)
                                        counts = counts.copyOf(n)
                                    }
                                    sums[w] += v * v
                                    counts[w]++
                                    if (w > lastWindow) lastWindow = w
                                }
                                if (++ch == channels) { ch = 0; frame++ }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (lastWindow < 0) return null
            return FloatArray(lastWindow + 1) { i ->
                if (counts[i] == 0) Float.NaN else sqrt(sums[i] / counts[i]).toFloat()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AdBreakDetector", "audio read failed: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Middle of each silence (ms): 200 ms or more well below the recording's normal level. */
    private fun silenceTimes(levels: FloatArray): List<Long> {
        val sorted = levels.filter { !it.isNaN() }.sorted()
        if (sorted.isEmpty()) return emptyList()
        val median = sorted[sorted.size / 2]
        // Digital silence between ads sits far below programme audio; cap it so a quiet show
        // doesn't turn its whole soundtrack into "silence".
        val threshold = minOf(400f, maxOf(30f, median * 0.06f))
        val out = mutableListOf<Long>()
        var runStart = -1
        for (i in levels.indices) {
            // A gap in the recording (NaN) is not a silence.
            val quiet = !levels[i].isNaN() && abs(levels[i]) < threshold
            if (quiet && runStart < 0) runStart = i
            if ((!quiet || i == levels.lastIndex) && runStart >= 0) {
                val runEnd = if (quiet) i else i - 1
                if (runEnd - runStart + 1 >= 2) out += ((runStart + runEnd) / 2L) * WINDOW_MS + WINDOW_MS / 2
                runStart = -1
            }
        }
        return out
    }

    private fun isAdSpacing(ms: Long): Boolean {
        val s = ms / 1000.0
        return AD_LENGTHS.any { abs(s - it) <= SLACK_S }
    }

    /** Runs of silences on the ad rhythm, a minute or longer; nearby runs merged. */
    private fun breaksFrom(silences: List<Long>): List<LongRange> {
        val runs = mutableListOf<LongRange>()
        var i = 0
        while (i < silences.size) {
            var j = i
            while (j + 1 < silences.size && isAdSpacing(silences[j + 1] - silences[j])) j++
            if (j - i >= 2 && silences[j] - silences[i] >= MIN_BREAK_MS) runs += silences[i] until silences[j]
            i = if (j > i) j else i + 1
        }
        val merged = mutableListOf<LongRange>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.last <= 20_000L) merged[merged.lastIndex] = last.first until r.last + 1
            else merged += r
        }
        return merged
    }
}
