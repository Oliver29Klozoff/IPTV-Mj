package com.iptvapp.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Seek preview: the picture and time shown while choosing where to jump in a movie, episode or
 * recording (PlayerActivity, ui/player/ThumbnailPreviewManager). Nothing here touches the player,
 * Android, or the network, so all of it is unit-tested.
 *
 * Choosing a place and jumping there are separate: [Session] holds the target while the viewer
 * moves it, and only [Session.commit] hands a position to the player, once.
 */
object SeekPreview {

    /** Thumbnails are made for whole buckets of time, not every second: about 100 per video, 5 s to 60 s apart. */
    fun intervalMs(durationMs: Long): Long {
        if (durationMs <= 0) return 10_000L
        val raw = durationMs / 100
        val five = ((raw + 4_999L) / 5_000L) * 5_000L
        return five.coerceIn(5_000L, 60_000L)
    }

    /** The bucket a position falls in: the nearest one, never past the last second. */
    fun bucketMs(positionMs: Long, durationMs: Long): Long {
        val step = intervalMs(durationMs)
        val last = if (durationMs > 1_000L) durationMs - 1_000L else 0L
        val b = ((positionMs.coerceAtLeast(0L) + step / 2) / step) * step
        return b.coerceAtMost(((last / step) * step).coerceAtLeast(0L))
    }

    /**
     * How far one press moves: 10 s, then 30 s after ten presses in a row, 60 s after twenty
     * (the same 10 s / 30 s the player already used, with one more gear for long films).
     */
    fun stepMs(pressesInARow: Int): Long = when {
        pressesInARow <= 10 -> 10_000L
        pressesInARow <= 20 -> 30_000L
        else -> 60_000L
    }

    /**
     * Where the preview's left edge goes: centred over [fraction] of the bar, kept [marginPx]
     * inside the screen at both ends.
     */
    fun previewLeftPx(fraction: Float, barStartPx: Float, barWidthPx: Float, previewWidthPx: Float, screenWidthPx: Float, marginPx: Float): Float {
        val centre = barStartPx + barWidthPx * fraction.coerceIn(0f, 1f)
        val left = centre - previewWidthPx / 2f
        val max = (screenWidthPx - marginPx - previewWidthPx).coerceAtLeast(marginPx)
        return left.coerceIn(marginPx, max)
    }

    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** What TalkBack says for a position: "1 hour 16 minutes 30 seconds". */
    fun spoken(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val parts = ArrayList<String>(3)
        if (h > 0) parts += if (h == 1L) "1 hour" else "$h hours"
        if (m > 0) parts += if (m == 1L) "1 minute" else "$m minutes"
        if (s > 0 || parts.isEmpty()) parts += if (s == 1L) "1 second" else "$s seconds"
        return parts.joinToString(" ")
    }

    /**
     * One round of choosing a place. [targetMs] moves freely; the player is only told on
     * [commit], which returns the position once (null when nothing was being chosen).
     * [generation] changes with every new target, so an answer for an older one can be told apart.
     */
    class Session {
        enum class Phase { IDLE, PREVIEW }

        var phase = Phase.IDLE
            private set
        var startMs = 0L
            private set
        var targetMs = 0L
            private set
        var durationMs = 0L
            private set
        var generation = 0
            private set
        var presses = 0
            private set

        val active get() = phase == Phase.PREVIEW

        fun begin(fromMs: Long, durationMs: Long) {
            if (active) return
            phase = Phase.PREVIEW
            this.durationMs = durationMs
            startMs = clamp(fromMs)
            targetMs = startMs
            presses = 0
            generation++
        }

        /** A Left or Right press: moves the target by the current step. */
        fun nudge(forward: Boolean): Long {
            presses++
            val step = stepMs(presses)
            return setTarget(targetMs + if (forward) step else -step)
        }

        fun setTarget(ms: Long): Long {
            val next = clamp(ms)
            if (next != targetMs) generation++
            targetMs = next
            return targetMs
        }

        /** The position to play from, once. */
        fun commit(): Long? {
            if (!active) return null
            phase = Phase.IDLE
            generation++
            return targetMs
        }

        /** Back: nothing is sought. */
        fun cancel() {
            if (!active) return
            phase = Phase.IDLE
            generation++
        }

        private fun clamp(ms: Long): Long {
            val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
            return ms.coerceIn(0L, max)
        }
    }

    // ── Cache identity ──────────────────────────────────────────────────

    /**
     * A thumbnail cache key for a provider stream, with no credentials in it. An Xtream address
     * (host/movie|series/user/pass/id.ext) becomes its host, kind and file; any other address is
     * a salted hash, so nothing in it can be read back. [serverIndex] keeps two providers apart.
     */
    fun keyForUrl(url: String, serverIndex: Int, salt: String): String {
        val m = XTREAM_VOD.find(url)
        if (m != null) {
            val (host, kind, file) = m.destructured
            return "xt|$serverIndex|${host.lowercase(Locale.US)}|$kind|$file"
        }
        return "url|$serverIndex|" + sha256(salt + "\n" + url).take(32)
    }

    /** A recording: its id plus the file's size and age, so a different file under the same id never matches. */
    fun keyForRecording(recordingId: Int, sizeBytes: Long, modifiedMs: Long) = "rec|$recordingId|$sizeBytes|$modifiedMs"

    /** The folder name on disk for a key: a hash, so even the non-secret key isn't spelled out in file names. */
    fun fileNameFor(key: String) = sha256(key).take(40)

    private val XTREAM_VOD = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^/@]*@)?([^/:?#]+)(?::\d+)?/(movie|series)/[^/]+/[^/]+/([^/?#]+)""")

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // ── Caches ──────────────────────────────────────────────────────────

    /** A least-recently-used map bounded by total size ([sizeOf]), not count. */
    class SizedLru<K : Any, V : Any>(private val maxSize: Long, private val sizeOf: (V) -> Long) {
        private val map = LinkedHashMap<K, V>(16, 0.75f, true)
        var size = 0L
            private set

        @Synchronized fun get(key: K): V? = map[key]

        /** Stores [value]; one bigger than the whole cache is not kept. Returns what was pushed out. */
        @Synchronized fun put(key: K, value: V): List<V> {
            val s = sizeOf(value)
            if (s > maxSize) return emptyList()
            map.remove(key)?.let { size -= sizeOf(it) }
            map[key] = value
            size += s
            val evicted = ArrayList<V>()
            val it = map.entries.iterator()
            while (size > maxSize && it.hasNext()) {
                val e = it.next()
                if (e.key == key) continue
                size -= sizeOf(e.value)
                evicted += e.value
                it.remove()
            }
            return evicted
        }

        @Synchronized fun removeIf(test: (K) -> Boolean): List<V> {
            val out = ArrayList<V>()
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (test(e.key)) { size -= sizeOf(e.value); out += e.value; it.remove() }
            }
            return out
        }

        @Synchronized fun clear(): List<V> = removeIf { true }
        @Synchronized fun count() = map.size
    }

    /**
     * Thumbnails on disk, under the app's cache folder: one folder per video (hashed key), one
     * small JPEG per bucket. Bounded by [maxBytes] (oldest-used first) and [maxAgeMs]; writes
     * are skipped when free space is under [minFreeBytes]. A folder deleted underneath it is
     * just a miss. Nothing here throws.
     */
    class DiskCache(
        private val dir: File,
        private val maxBytes: Long,
        private val maxAgeMs: Long,
        private val minFreeBytes: Long,
        private val clock: () -> Long = System::currentTimeMillis,
        private val freeBytes: (File) -> Long = { it.usableSpace },
    ) {
        private fun fileFor(key: String, bucketMs: Long) = File(File(dir, fileNameFor(key)), "$bucketMs.jpg")

        fun get(key: String, bucketMs: Long): ByteArray? = try {
            val f = fileFor(key, bucketMs)
            if (!f.isFile) null
            else if (clock() - f.lastModified() > maxAgeMs) { f.delete(); null }
            else {
                val bytes = f.readBytes()
                f.setLastModified(clock())
                bytes.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) { null }

        /** False when it wasn't written (low space, or a write that failed). */
        fun put(key: String, bucketMs: Long, bytes: ByteArray): Boolean = try {
            dir.mkdirs()
            if (freeBytes(dir) < minFreeBytes) false else {
                val f = fileFor(key, bucketMs)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) { tmp.delete(); false } else {
                    f.setLastModified(clock())
                    true
                }
            }
        } catch (_: Exception) { false }

        /** Drops a video's thumbnails (its file changed or went). */
        fun remove(key: String) {
            try { File(dir, fileNameFor(key)).deleteRecursively() } catch (_: Exception) {}
        }

        /** Expired files out, then oldest-used until under the size limit. */
        fun trim() {
            try {
                val now = clock()
                val files = dir.listFiles()?.flatMap { d -> d.listFiles()?.toList().orEmpty() }.orEmpty().filter { it.isFile }
                val live = ArrayList<File>()
                for (f in files) {
                    if (f.name.endsWith(".tmp") || now - f.lastModified() > maxAgeMs) f.delete() else live += f
                }
                var total = live.sumOf { it.length() }
                for (f in live.sortedBy { it.lastModified() }) {
                    if (total <= maxBytes) break
                    total -= f.length()
                    f.delete()
                }
                dir.listFiles()?.forEach { d -> if (d.isDirectory && d.listFiles().isNullOrEmpty()) d.delete() }
            } catch (_: Exception) {}
        }

        fun sizeBytes(): Long = try {
            dir.listFiles()?.flatMap { d -> d.listFiles()?.toList().orEmpty() }.orEmpty().sumOf { it.length() }
        } catch (_: Exception) { 0L }
    }

    /**
     * One worker that only ever does the newest job: a job sent while another runs replaces any
     * job still waiting, so fast seeking never queues more than one. Work runs in [scope].
     */
    class LatestWorker<T>(scope: CoroutineScope, private val block: suspend (T) -> Unit) {
        private val jobs = Channel<T>(Channel.CONFLATED)

        init {
            scope.launch {
                for (job in jobs) block(job)
            }
        }

        fun send(job: T) { jobs.trySend(job) }

        fun close() { jobs.close() }
    }
}
