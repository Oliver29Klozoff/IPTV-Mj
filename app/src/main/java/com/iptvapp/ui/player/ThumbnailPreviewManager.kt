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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
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
    private var sourceGeneration = 0
    private var wanted: Pair<Int, Long>? = null
    private var failures = 0
    private var off = false

    // Worker-thread state: the open retriever for the current source.
    @Volatile private var openGrabber: Grabber? = null
    private var openFor = -1
    // Worker-thread count of pictures written since the disk cache was last trimmed.
    private var writesSinceTrim = 0

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

    /** A picture source that needs that check (and is still worth checking for). */
    val needsConnectionCheck get() = source is Source.Remote && !off

    /** The video changed (or went): earlier pictures and requests no longer apply. */
    fun setSource(next: Source?) {
        sourceGeneration++
        source = next
        allowed = true
        wanted = null
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
            val grabbed = try {
                val grabber = grabberFor(src, job.generation)
                // Closed while the file was being opened: no decoding for it.
                if (token.get()) null else grabber?.frame(job.bucketMs, WIDTH_PX, HEIGHT_PX)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
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
            if (took > SLOW_MS) off = true
        }
        // Stale: the viewer has moved to another place since this was asked for.
        if (wanted == (job.generation to job.bucketMs)) job.onResult(job.bucketMs, bitmap)
    }

    private fun grabberFor(src: Source, generation: Int): Grabber? {
        if (openFor == generation) return openGrabber
        closeGrabber()
        openFor = generation
        openGrabber = try {
            Grabber.open(context, src) { activeToken?.get() == true }
        } catch (e: Exception) {
            // Stopped part-way because the preview closed: open it again next time, not never.
            if (activeToken?.get() == true) openFor = -1
            null
        }
        return openGrabber
    }

    private fun closeGrabber() {
        try { openGrabber?.close() } catch (_: Exception) {}
        openGrabber = null
        openFor = -1
    }

    private fun memKey(key: String, bucketMs: Long) = "$key@$bucketMs"

    /** One open retriever for one video. Not thread-safe: used from the single worker only. */
    private class Grabber(private val retriever: MediaMetadataRetriever, private val data: MediaDataSource?) {

        fun frame(atMs: Long, width: Int, height: Int): Bitmap? = try {
            grab(atMs, width, height)
        } finally {
            // The provider's file: no connection is held open between pictures.
            (data as? RangeData)?.releaseConnection()
        }

        private fun grab(atMs: Long, width: Int, height: Int): Bitmap? {
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

        fun close() {
            try { retriever.release() } catch (_: Exception) {}
            try { data?.close() } catch (_: Exception) {}
        }

        companion object {
            fun open(context: Context, src: Source, cancelled: () -> Boolean = { false }): Grabber {
                val r = MediaMetadataRetriever()
                var data: MediaDataSource? = null
                try {
                    when (src) {
                        is Source.Local -> r.setDataSource(context, src.uri)
                        is Source.Downloaded -> { data = CacheOnlyData(src.cache, src.url); r.setDataSource(data) }
                        is Source.Remote -> { data = RangeData(src.url, src.userAgent, cancelled = cancelled); r.setDataSource(data) }
                    }
                } catch (e: Exception) {
                    try { r.release() } catch (_: Exception) {}
                    try { data?.close() } catch (_: Exception) {}
                    throw e
                }
                return Grabber(r, data)
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
     * The provider's file, read in ranges with a client of its own (no request logging), with a
     * byte budget per video so a file that can't be read near the asked spot is given up on
     * rather than downloaded.
     */
    internal class RangeData(
        private val url: String,
        private val userAgent: String,
        private val budget: Long = BYTE_BUDGET,
        private val cancelled: () -> Boolean = { false },
    ) : MediaDataSource() {
        private var body: InputStream? = null
        private var bodyAt = -1L
        private var length = -1L
        private var read = 0L
        /** Set from another thread to stop: the next read fails instead of carrying on. */
        @Volatile var abort = false

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (abort || cancelled()) throw IOException("seek preview closed")
            if (size == 0) return 0
            if (length >= 0 && position >= length) return -1
            if (position != bodyAt || body == null) openAt(position)
            val n = body!!.read(buffer, offset, size)
            if (n < 0) return -1
            bodyAt += n
            read += n
            if (read > budget) throw IOException("seek preview budget used")
            return n
        }

        private fun openAt(position: Long) {
            body?.close()
            val response = client.newCall(
                Request.Builder().url(url).header("User-Agent", userAgent).header("Range", "bytes=$position-").build()
            ).execute()
            // A server that ignores ranges would send the whole file from the start.
            if (response.code != 206) {
                response.close()
                throw IOException("ranges not supported")
            }
            if (length < 0) {
                length = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
            }
            body = response.body?.byteStream() ?: throw IOException("empty")
            bodyAt = position
        }

        override fun getSize(): Long {
            if (length < 0 && body == null) openAt(0)
            return length
        }

        /** Closes the open response; the next read opens a new range where it is needed. */
        fun releaseConnection() {
            try { body?.close() } catch (_: Exception) {}
            body = null
            bodyAt = -1L
        }

        override fun close() { try { body?.close() } catch (_: Exception) {} }

        companion object {
            const val BYTE_BUDGET = 48L * 1024 * 1024
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
