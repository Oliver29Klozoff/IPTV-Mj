package com.iptvapp.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.inspector.frame.FrameExtractor
import com.iptvapp.util.SeekPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * The pictures in the seek preview (util/SeekPreview has the rules; PlayerActivity shows them).
 *
 * Where a picture can come from, checked in this order:
 *  - a recording on this device (MediaMetadataRetriever on its own file),
 *  - a finished download (read from the download cache only: never the network),
 *  - the provider's own file, only for a plain file (not HLS, not live) and only when the
 *    provider says the account has a spare connection. Most accounts allow one stream at a
 *    time, and a second connection would cut off the film being watched, so this is off for them.
 * Anything else shows the time alone. Xtream panels publish no thumbnail sprites or tracks, so
 * there is nothing ready-made to use.
 *
 * Pictures are made one at a time on one background thread, newest request only (an older one
 * still waiting is dropped), cached in memory (bounded by bytes) and on disk (bounded by size
 * and age), and never touch the player. A source that keeps failing or is slow is switched off
 * for the rest of that video. Nothing here logs an address.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ThumbnailPreviewManager(private val context: Context) {

    /** Where pictures come from for the video now playing. [key] has no credentials in it. */
    sealed class Source(val key: String) {
        class Local(val uri: Uri, key: String) : Source(key)
        class Downloaded(val url: String, val cache: Cache, key: String) : Source(key)
        class Remote(val url: String, val userAgent: String, key: String) : Source(key)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val memory = SeekPreview.SizedLru<String, Bitmap>(MEMORY_BYTES) { it.allocationByteCount.toLong() }
    private val disk = SeekPreview.DiskCache(
        File(context.cacheDir, "seek_thumbs"), DISK_BYTES, DISK_AGE_MS, MIN_FREE_BYTES
    )

    // Main-thread state.
    private var source: Source? = null
    // Read by the worker too, to stop opening a video that has been left.
    @Volatile private var sourceGeneration = 0
    private var wanted: Pair<Int, Long>? = null
    private var failures = 0
    private var off = false

    // Worker-thread state: the open retriever for the current source.
    @Volatile private var openGrabber: Grabber? = null
    private var openFor = -1
    // Worker-thread count of pictures written since the disk cache was last trimmed.
    private var writesSinceTrim = 0
    // Worker-thread: how this request's open went, when it opened the file (for the report).
    private var lastOpen: String? = null

    private class Job(val generation: Int, val key: String, val bucketMs: Long, val onResult: (Long, Bitmap?) -> Unit)

    private val requests = SeekPreview.LatestWorker<Job>(scope) { job -> run(job) }

    init {
        scope.launch(worker) { disk.trim() }
    }

    /** True when this video can show pictures (the time always shows). */
    val supported get() = source != null && !off && allowed

    /**
     * The provider's own file takes a second connection, so the player checks for a spare one at
     * each seek and sets this; meanwhile, and when there is none, the time shows alone. The video's
     * failures, its slow-source cut-off and its byte budget carry on regardless.
     */
    var allowed = true

    /**
     * The check's definite answer was no (the account full, or not verified): a film still being
     * opened in the background stops. Not set while a check is in flight, only by its answer.
     */
    @Volatile var refused = false

    /** A picture source that needs that check (and is still worth checking for). */
    val needsConnectionCheck get() = source is Source.Remote && !off

    /** The video changed (or went): earlier pictures and requests no longer apply. */
    fun setSource(next: Source?) {
        sourceGeneration++
        source = next
        allowed = true
        wanted = null
        refused = false
        failures = 0
        off = false
        val gen = sourceGeneration
        scope.launch(worker) {
            if (openFor != gen) closeGrabber()
            // Between videos: back under the disk limit.
            if (writesSinceTrim > 0) { disk.trim(); writesSinceTrim = 0 }
        }
    }

    /**
     * The picture for [positionMs] of a [durationMs] video, to [onResult] on the main thread with
     * the bucket it is for, or null when there isn't one. Only the newest request is answered.
     */
    fun request(positionMs: Long, durationMs: Long, onResult: (bucketMs: Long, Bitmap?) -> Unit) {
        val src = source
        if (src == null || off || !allowed || durationMs <= 0) return
        val bucket = SeekPreview.bucketMs(positionMs, durationMs)
        wanted = sourceGeneration to bucket
        memory.get(memKey(src.key, bucket))?.let { onResult(bucket, it); return }
        requests.send(Job(sourceGeneration, src.key, bucket, onResult))
    }

    /**
     * The preview closed (a jump, or Back): the request waiting is dropped and a read of the
     * provider's file in progress stops. Pictures already made stay cached.
     */
    fun cancelPending() {
        wanted = null
        activeToken?.set(true)
    }

    // The request being worked on: set to stop it, from any thread, whether the file is still
    // being opened or a frame is being read. Every provider read checks it.
    @Volatile private var activeToken: java.util.concurrent.atomic.AtomicBoolean? = null

    /** Leaving the player: stops work and lets go of the pictures. */
    fun close() {
        cancelPending()
        setSource(null)
        requests.close()
        memory.clear()
        scope.launch(worker) { closeGrabber() }.invokeOnCompletion { scope.cancel() }
    }

    private suspend fun run(job: Job) {
        // Superseded while it waited: another video, or the viewer has moved on.
        if (job.generation != sourceGeneration || wanted != (job.generation to job.bucketMs)) return
        // Switched off, or the go-ahead taken away, while it waited: nothing is read.
        if (off || !allowed) return
        val src = source ?: return
        val started = SystemClock.elapsedRealtime()
        var fromDisk = false
        val token = java.util.concurrent.atomic.AtomicBoolean(false)
        activeToken = token
        val bitmap = withContext(worker) {
            disk.get(job.key, job.bucketMs)?.let { bytes ->
                fromDisk = true
                return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            lastOpen = null
            var grabber: Grabber? = null
            val grabbed = try {
                grabber = grabberFor(src, job.generation)
                try {
                    // Closed while the file was being opened: no decoding for it.
                    if (token.get()) null else grabber?.frame(job.bucketMs, WIDTH_PX, HEIGHT_PX)
                } finally {
                    // Decoded or not, no provider connection stays open after this request.
                    grabber?.releaseConnection()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            // What happened, for Settings: no address, only timings, sizes and the kind of failure.
            if (src is Source.Remote) {
                val data = grabber?.data as? RangeData
                lastReport = listOfNotNull(
                    when {
                        grabbed != null -> "picture"
                        token.get() -> "stopped by the jump"
                        else -> "no picture"
                    } + " after " + seconds(SystemClock.elapsedRealtime() - started),
                    lastOpen,
                    grabber?.info,
                    data?.let { "${"%.1f".format(java.util.Locale.US, it.read / 1_048_576.0)} MB in ${it.requests} requests" },
                    data?.lastError?.let { "error: $it" },
                ).joinToString(" · ")
            }
            if (grabbed != null) {
                val out = ByteArrayOutputStream()
                if (grabbed.compress(Bitmap.CompressFormat.JPEG, 80, out) && disk.put(job.key, job.bucketMs, out.toByteArray())) {
                    // Kept under its limit while in use too, not only when a player opens.
                    if (++writesSinceTrim >= TRIM_EVERY) { disk.trim(); writesSinceTrim = 0 }
                }
            }
            grabbed
        }
        if (activeToken === token) activeToken = null
        val took = SystemClock.elapsedRealtime() - started
        // Timing only: the kind of source and how long, never an address.
        if (com.iptvapp.BuildConfig.DEBUG) android.util.Log.d("SeekPreview", "${src.javaClass.simpleName} ${if (fromDisk) "disk" else "decode"} ${if (bitmap != null) "ok" else "none"} ${took}ms")
        if (job.generation != sourceGeneration) return
        // Given up on because the preview closed, not because the source failed.
        val cancelled = wanted != (job.generation to job.bucketMs)
        if (bitmap == null && cancelled) return
        if (bitmap == null) {
            if (++failures >= MAX_FAILURES) off = true
        } else {
            failures = 0
            memory.put(memKey(job.key, job.bucketMs), bitmap)
            // Too slow on this device or this file: the time alone from here on.
            if (took > if (src is Source.Remote) SLOW_REMOTE_MS else SLOW_MS) off = true
        }
        // Stale: the viewer has moved to another place since this was asked for.
        if (wanted == (job.generation to job.bucketMs)) job.onResult(job.bucketMs, bitmap)
    }

    private fun grabberFor(src: Source, generation: Int): Grabber? {
        if (openFor == generation) return openGrabber
        closeGrabber()
        openFor = generation
        // Opening reads the file's index, which from a slow provider can take longer than one
        // seek lasts: it carries on past a jump, so the film is opened once rather than again at
        // every press. Only leaving the video or OPEN_LIMIT_MS stops it; each picture after that
        // stops with its own request.
        val opening = java.util.concurrent.atomic.AtomicBoolean(true)
        val began = SystemClock.elapsedRealtime()
        openGrabber = try {
            Grabber.open(context, src) {
                // Leaving the video, or a check answering no, stops it at any point (Media3 opens the
                // file lazily, inside the first picture); a jump stops only pictures, not opening.
                generation != sourceGeneration || refused ||
                    if (opening.get()) SystemClock.elapsedRealtime() - began > OPEN_LIMIT_MS else activeToken?.get() == true
            }.also { lastOpen = "opened in ${seconds(SystemClock.elapsedRealtime() - began)}" }
        } catch (e: Exception) {
            // Not opened (stopped part-way, a timeout, a 503): tried again next time; two failed
            // requests in a row switch pictures off for this video (MAX_FAILURES).
            lastOpen = "couldn't open after ${seconds(SystemClock.elapsedRealtime() - began)}: ${reason(e)}"
            openFor = -1
            null
        } finally {
            opening.set(false)
        }
        return openGrabber
    }

    private fun closeGrabber() {
        try { openGrabber?.close() } catch (_: Exception) {}
        openGrabber = null
        openFor = -1
    }

    private fun memKey(key: String, bucketMs: Long) = "$key@$bucketMs"

    private fun seconds(ms: Long) = "%.1f s".format(java.util.Locale.US, ms / 1000.0)

    private fun reason(e: Exception) = failureOf(e)

    /** One open picture source for one video. Used from the single worker only. */
    private abstract class Grabber(val data: MediaDataSource?) {

        /** The file's kind and picture size, or how it failed: for the Settings report. */
        abstract val info: String?

        abstract fun frame(atMs: Long, width: Int, height: Int): Bitmap?

        /** The provider's file: no connection is held open between pictures. */
        fun releaseConnection() { (data as? RangeData)?.releaseConnection() }

        open fun close() {
            try { data?.close() } catch (_: Exception) {}
        }

        companion object {
            fun open(context: Context, src: Source, cancelled: () -> Boolean = { false }): Grabber = when (src) {
                // The provider's film: read the way the player reads it (Media3), so a film the
                // player can seek in gives pictures too. Android's own retriever can't seek in
                // many provider MKVs ("did not locate the video track for seeking").
                is Source.Remote -> {
                    val range = RangeData(src.url, src.userAgent, cancelled = cancelled)
                    try {
                        // The opening, done here so it carries on past a jump: the start and the last
                        // two pieces, where MP4s and MKVs keep their index. Media3 then finds them read.
                        val size = range.size
                        val one = ByteArray(1)
                        if (size > 0) {
                            range.readAt((size - 2L * RangeData.PIECE_BYTES).coerceAtLeast(0L), one, 0, 1)
                            range.readAt(size - 1, one, 0, 1)
                        }
                    } catch (e: Exception) {
                        range.close()
                        throw e
                    }
                    ExtractorGrabber(context, src.url, range, cancelled)
                }
                else -> RetrieverGrabber.open(context, src)
            }
        }
    }

    /** Android's retriever: files on the device and finished downloads. */
    private class RetrieverGrabber(private val retriever: MediaMetadataRetriever, data: MediaDataSource?) : Grabber(data) {

        override val info: String? = try {
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            listOfNotNull(mime, if (w != null && h != null) "${w}×$h" else null).joinToString(" ").ifBlank { null }
        } catch (_: Exception) { null }

        override fun frame(atMs: Long, width: Int, height: Int): Bitmap? {
            val us = atMs * 1000L
            val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                return retriever.getScaledFrameAtTime(us, option, width, height)
            }
            val full = retriever.getFrameAtTime(us, option) ?: return null
            if (full.width <= width && full.height <= height) return full
            val scale = minOf(width.toFloat() / full.width, height.toFloat() / full.height)
            val small = Bitmap.createScaledBitmap(full, (full.width * scale).toInt().coerceAtLeast(1), (full.height * scale).toInt().coerceAtLeast(1), true)
            if (small !== full) full.recycle()
            return small
        }

        override fun close() {
            try { retriever.release() } catch (_: Exception) {}
            super.close()
        }

        companion object {
            fun open(context: Context, src: Source): Grabber {
                val r = MediaMetadataRetriever()
                var data: MediaDataSource? = null
                try {
                    when (src) {
                        is Source.Local -> r.setDataSource(context, src.uri)
                        is Source.Downloaded -> { data = CacheOnlyData(src.cache, src.url); r.setDataSource(data) }
                        is Source.Remote -> throw IllegalArgumentException("provider films use Media3")
                    }
                } catch (e: Exception) {
                    try { r.release() } catch (_: Exception) {}
                    try { data?.close() } catch (_: Exception) {}
                    throw e
                }
                return RetrieverGrabber(r, data)
            }
        }
    }

    /**
     * Media3's frame extractor over the provider's file: the same readers as the player (MKV
     * cues included), at the nearest keyframe, scaled on the GPU. Every byte still comes through
     * [RangeData] (bounded pieces, the byte budget, stopping on a jump), and the extractor's own
     * buffering is small. One that failed or was stopped is rebuilt for the next picture; the
     * pieces already read stay, so it doesn't start from nothing.
     */
    private class ExtractorGrabber(
        private val context: Context,
        private val url: String,
        private val range: RangeData,
        private val cancelled: () -> Boolean,
    ) : Grabber(range) {
        private var extractor: FrameExtractor? = null
        private var failure: String? = null

        override val info: String? get() = "Media3" + (failure?.let { " · $it" } ?: "")

        override fun frame(atMs: Long, width: Int, height: Int): Bitmap? {
            failure = null
            val ex = extractor ?: build(height).also { extractor = it }
            val future = ex.getFrame(atMs)
            val began = SystemClock.elapsedRealtime()
            while (true) {
                try {
                    return future.get(POLL_MS, TimeUnit.MILLISECONDS).bitmap
                } catch (_: java.util.concurrent.TimeoutException) {
                    if (cancelled() || SystemClock.elapsedRealtime() - began > FRAME_WAIT_MS) {
                        if (!cancelled()) failure = "no frame in ${FRAME_WAIT_MS / 1000} s"
                        future.cancel(true)
                        reset()
                        return null
                    }
                } catch (e: java.util.concurrent.ExecutionException) {
                    var root: Throwable = e
                    while (root.cause != null && root.cause !== root) root = root.cause!!
                    failure = (root as? Exception)?.let { failureOf(it) } ?: root.javaClass.simpleName
                    reset()
                    return null
                }
            }
        }

        private fun build(height: Int): FrameExtractor =
            FrameExtractor.Builder(context, MediaItem.fromUri(url))
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                .setMediaSourceFactory(ProgressiveMediaSource.Factory(DataSource.Factory { RangeDataSource(range) }))
                .setEffects(listOf(Presentation.createForHeight(height)))
                .build()

        private fun reset() {
            try { extractor?.close() } catch (_: Exception) {}
            extractor = null
        }

        override fun close() {
            reset()
            super.close()
        }

        companion object {
            const val POLL_MS = 100L
            // A picture from a slow provider is still worth keeping (cached for the next seek).
            const val FRAME_WAIT_MS = 20_000L
        }
    }

    /** [RangeData] as a Media3 data source: reads go to its bounded, cached pieces. */
    private class RangeDataSource(private val range: RangeData) : BaseDataSource(true) {
        private var uri: Uri? = null
        private var position = 0L
        private var remaining = 0L
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            val size = range.size
            position = dataSpec.position
            if (size >= 0 && position > size) throw IOException("seek preview read past the end")
            remaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                size >= 0 -> size - position
                else -> C.LENGTH_UNSET.toLong()
            }
            opened = true
            transferStarted(dataSpec)
            return remaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
            val n = range.readAt(position, buffer, offset, want)
            if (n <= 0) return C.RESULT_END_OF_INPUT
            position += n
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }


    /** A finished download's bytes, from the download cache only. A part that isn't there fails; nothing is fetched. */
    private class CacheOnlyData(private val cache: Cache, private val url: String) : MediaDataSource() {
        private val source = CacheDataSource(cache, null)
        private var openAt = -1L
        private var length = C.LENGTH_UNSET.toLong()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            val total = getSize()
            if (total >= 0 && position >= total) return -1
            if (position != openAt) {
                try { source.close() } catch (_: Exception) {}
                source.open(DataSpec.Builder().setUri(url).setPosition(position).build())
                openAt = position
            }
            val n = source.read(buffer, offset, size)
            if (n == C.RESULT_END_OF_INPUT) return -1
            openAt += n
            return n
        }

        override fun getSize(): Long {
            if (length == C.LENGTH_UNSET.toLong()) {
                length = ContentMetadata.getContentLength(cache.getContentMetadata(url)).takeIf { it >= 0 } ?: -1L
            }
            return length
        }

        override fun close() { try { source.close() } catch (_: Exception) {} }
    }

    /**
     * The provider's file, read in whole 256 KB pieces with a client of its own (no request
     * logging). The retriever reads a file in many small, scattered pieces; asking the provider
     * for each one opened a new connection every time and took seconds before a single frame.
     * Here each piece is one bounded range request read to its end, so one kept-alive connection
     * serves them all, and the last few pieces are kept so nearby reads cost nothing. The
     * provider's redirect (Xtream sends films on to a streaming server) is remembered. A byte
     * budget per video means a file that can't be read near the asked spot is given up on rather
     * than downloaded, and no connection is kept between pictures (releaseConnection).
     */
    internal class RangeData(
        private val url: String,
        private val userAgent: String,
        private val budget: Long = BYTE_BUDGET,
        private val cancelled: () -> Boolean = { false },
    ) : MediaDataSource() {
        private val pieces = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_PIECES
        }
        private var length = -1L
        /** Bytes read from the provider for this video so far. */
        var read = 0L
            private set
        /** Why the last read failed, if one did (never an address). */
        @Volatile var lastError: String? = null
            private set
        // Where the provider sent the first request; tried first after that, the original if it stops working.
        private var target = url
        /** Set from another thread to stop: the next read fails instead of carrying on. */
        @Volatile var abort = false

        /** Requests made to the provider: for tests. */
        var requests = 0
            private set

        // Media3 reads on its own loading thread: one read at a time.
        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            checkGoing()
            if (size == 0) return 0
            val total = getSize()
            if (total >= 0 && position >= total) return -1
            var copied = 0
            var at = position
            while (copied < size && (total < 0 || at < total)) {
                val index = at / PIECE_BYTES
                val piece = pieces[index] ?: fetch(index)
                val within = (at - index * PIECE_BYTES).toInt()
                if (within >= piece.size) break
                val n = minOf(size - copied, piece.size - within)
                System.arraycopy(piece, within, buffer, offset + copied, n)
                copied += n
                at += n
            }
            return if (copied == 0) -1 else copied
        }

        private fun checkGoing() {
            if (abort || cancelled()) throw IOException("seek preview closed")
        }

        private fun fetch(index: Long): ByteArray = try {
            fetchPiece(index)
        } catch (e: IOException) {
            lastError = failureOf(e)
            throw e
        }

        private fun fetchPiece(index: Long): ByteArray {
            checkGoing()
            val start = index * PIECE_BYTES
            val end = start + PIECE_BYTES - 1
            val bytes = try {
                rangeOf(target, start, end)
            } catch (e: IOException) {
                // A remembered redirect can expire: back to the address the player was given.
                if (target == url || abort || cancelled()) throw e
                target = url
                rangeOf(url, start, end)
            }
            read += bytes.size
            if (read > budget) throw IOException("seek preview budget used")
            pieces[index] = bytes
            return bytes
        }

        private fun rangeOf(address: String, start: Long, end: Long): ByteArray {
            requests++
            lastRequestAt = android.os.SystemClock.elapsedRealtime()
            client.newCall(
                Request.Builder().url(address).header("User-Agent", userAgent).header("Range", "bytes=$start-$end").build()
            ).execute().use { response ->
                // A server that ignores ranges would send the whole file from the start.
                if (response.code != 206) throw IOException("ranges not supported (HTTP ${response.code})")
                if (length < 0) {
                    length = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                }
                target = response.request.url.toString()
                val body = response.body ?: throw IOException("empty")
                // Never more than the piece asked for, whatever the server sends.
                val out = java.io.ByteArrayOutputStream(PIECE_BYTES)
                val buf = ByteArray(16 * 1024)
                val stream = body.byteStream()
                while (out.size() < PIECE_BYTES) {
                    checkGoing()
                    val n = stream.read(buf, 0, minOf(buf.size, PIECE_BYTES - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                lastRequestAt = android.os.SystemClock.elapsedRealtime()
                return out.toByteArray()
            }
        }

        @Synchronized
        override fun getSize(): Long {
            if (length < 0 && pieces.isEmpty()) fetch(0)
            return length
        }

        /** No connection stays open between pictures: the idle one is closed (pieces already read stay). */
        fun releaseConnection() {
            try { client.connectionPool.evictAll() } catch (_: Exception) {}
        }

        override fun close() {
            // A read still going on another thread stops at its next check.
            abort = true
            synchronized(this) { pieces.clear() }
            releaseConnection()
        }

        companion object {
            const val BYTE_BUDGET = 48L * 1024 * 1024
            const val PIECE_BYTES = 256 * 1024
            const val MAX_PIECES = 32
            /** When the provider was last asked for a piece (elapsedRealtime): the one-stream experiment watches it. */
            @Volatile var lastRequestAt = 0L
            val client: OkHttpClient by lazy {
                OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
            }
        }
    }

    companion object {
        const val WIDTH_PX = 320
        const val HEIGHT_PX = 180
        const val MEMORY_BYTES = 8L * 1024 * 1024
        const val DISK_BYTES = 40L * 1024 * 1024
        const val DISK_AGE_MS = 14L * 24 * 60 * 60_000
        const val MIN_FREE_BYTES = 64L * 1024 * 1024
        const val MAX_FAILURES = 2
        const val SLOW_MS = 4_000L
        // A provider film is read over the network, often a slow one: more time before giving up on it.
        const val SLOW_REMOTE_MS = 10_000L
        // Opening a provider film (reading its index) is given up on after this, jump or no jump.
        const val OPEN_LIMIT_MS = 30_000L
        /** The last provider picture attempt, for Settings: timings, sizes, the kind of file and failure; never an address. */
        @Volatile var lastReport: String? = null

        /** The kind of failure only: messages of its own, else the exception's name (a message could carry an address). */
        fun failureOf(e: Exception): String =
            e.message?.takeIf { e is IOException && (it.startsWith("seek preview") || it.startsWith("ranges not supported")) } ?: e.javaClass.simpleName
        /** Trims the disk cache after this many new pictures (about 0.5 MB). */
        const val TRIM_EVERY = 25

        /** The player's own User-Agent, so the provider sees the same client for a picture as for the film. */
        fun playbackUserAgent() =
            "MKTV/${com.iptvapp.BuildConfig.VERSION_NAME} (Linux;Android ${Build.VERSION.RELEASE}) ExoPlayerLib/1.4.1"

        /** A recording's key: its id, and its file's size and date as the system reports them. */
        fun recordingKey(context: Context, recordingId: Int, uri: Uri): String? {
            try {
                if (uri.scheme == "file") {
                    val f = File(uri.path ?: return null)
                    if (!f.isFile) return null
                    return SeekPreview.keyForRecording(recordingId, f.length(), f.lastModified())
                }
                return context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) null else {
                        val size = c.getLong(0)
                        if (size <= 0) null else SeekPreview.keyForRecording(recordingId, size, 0L)
                    }
                }
            } catch (_: Exception) {
                return null
            }
        }
    }
}
