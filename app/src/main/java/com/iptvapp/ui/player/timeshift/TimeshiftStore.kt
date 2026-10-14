package com.iptvapp.ui.player.timeshift

import java.io.File
import java.util.UUID

/**
 * Bounded temporary files for one live channel. Oldest segments are deleted when the
 * duration or byte budget is exceeded. The directory name is random: provider URLs
 * contain the account password and must not appear in a path.
 *
 * This is not a recording. It lives under the app cache, never MediaStore, and it is
 * not the download SimpleCache.
 */
class TimeshiftStore(
    private val sessionDir: File,
    private val durationBudgetMs: Long,
    private val byteBudget: Long,
    private val freeBytes: () -> Long,
    private val minFreeBytes: Long = TimeshiftLimits.RESERVED_FREE_BYTES,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val graceMs: Long = READER_GRACE_MS,
) {
    enum class Append { STORED, LOW_STORAGE, SKIPPED }

    private data class Held(
        val sequence: Long,
        val durationMs: Long,
        val bytes: Long,
        val file: File,
        val discontinuity: Boolean,
    )

    private val held = ArrayList<Held>()
    private val lock = Any()
    @Volatile private var deleted = false
    private var ended = false
    /** Keeps rising after old files are deleted, so a playlist never reuses a sequence. */
    private var nextSequence = 0L
    /** Session maximum. Evicting a long piece must not lower EXT-X-TARGETDURATION. */
    private var targetDurationSec = TimeshiftPlaylist.MIN_TARGET_SEC
    private var publishedBytes = 0L
    private var graceBytes = 0L
    private val grace = ArrayDeque<Grace>()

    /** Published segments plus files kept briefly for a player that already opened them. */
    val totalBytes: Long get() = synchronized(lock) { publishedBytes + graceBytes }
    var stoppedForStorage: Boolean = false
        private set

    val playlistFile: File get() = File(sessionDir, PLAYLIST)

    fun hasSegments(): Boolean = synchronized(lock) { held.isNotEmpty() }

    fun retainedDurationMs(): Long = synchronized(lock) { held.sumOf { it.durationMs } }

    fun segmentCount(): Int = synchronized(lock) { held.size }

    fun append(bytes: ByteArray, durationMs: Long, discontinuity: Boolean): Append = synchronized(lock) {
        if (deleted) return Append.SKIPPED
        if (stoppedForStorage) return Append.LOW_STORAGE
        if (bytes.isEmpty() || durationMs <= 0L) return Append.SKIPPED
        if (bytes.size.toLong() > byteBudget) return Append.SKIPPED
        if (freeBytes() < minFreeBytes) {
            stoppedForStorage = true
            return Append.LOW_STORAGE
        }
        sessionDir.mkdirs()
        trimGrace()
        evictUntilFits(bytes.size.toLong())
        if (publishedBytes + graceBytes + bytes.size > byteBudget) return Append.SKIPPED
        val sequence = nextSequence
        nextSequence += 1L
        val file = File(sessionDir, TimeshiftPlaylist.fileName(sequence))
        file.writeBytes(bytes)
        if (deleted) {
            file.delete()
            return Append.SKIPPED
        }
        held += Held(sequence, durationMs, bytes.size.toLong(), file, discontinuity)
        val seconds = ((durationMs + 999L) / 1000L).toInt().coerceAtLeast(1)
        if (seconds > targetDurationSec) targetDurationSec = seconds
        publishedBytes += bytes.size
        evictToDuration()
        writePlaylistLocked()
        Append.STORED
    }

    fun writePlaylist() = synchronized(lock) { writePlaylistLocked() }

    private fun writePlaylistLocked() {
        if (deleted || held.isEmpty()) return
        sessionDir.mkdirs()
        val text = TimeshiftPlaylist.render(
            held.map {
                TimeshiftPlaylist.Segment(it.sequence, it.durationMs, it.file.name, it.discontinuity)
            },
            endList = ended,
            targetDurationSec = targetDurationSec,
        )
        val tmp = File(sessionDir, "$PLAYLIST.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(playlistFile)) {
            playlistFile.writeText(text)
            tmp.delete()
        }
    }

    /** The pump has stopped. The player should play this window to its end, then leave it. */
    fun finishRetainedWindow() = synchronized(lock) {
        if (deleted || held.isEmpty()) return
        ended = true
        writePlaylistLocked()
    }

    fun deleteAll() = synchronized(lock) {
        deleted = true
        held.clear()
        grace.clear()
        publishedBytes = 0L
        graceBytes = 0L
        sessionDir.deleteRecursively()
        // Released only after the files are gone, so a startup sweep cannot remove them first.
        activeNames.remove(sessionDir.name)
    }

    private fun evictUntilFits(incoming: Long) {
        var spins = held.size + grace.size + GRACE_SEGMENTS + 2
        while (spins-- > 0 && publishedBytes + graceBytes + incoming > byteBudget) {
            when {
                held.isNotEmpty() && grace.size < GRACE_SEGMENTS -> unpublishOldest()
                grace.isNotEmpty() -> deleteOldestGrace()
                held.isNotEmpty() -> {
                    unpublishOldest()
                    if (publishedBytes + graceBytes + incoming > byteBudget && grace.isNotEmpty()) {
                        deleteOldestGrace()
                    }
                }
                else -> return
            }
        }
    }

    private fun evictToDuration() {
        while (held.size > 1 && retainedDurationMs() > durationBudgetMs) unpublishOldest()
    }

    /** Drop it from the playlist, but leave the file until the reader grace ends. */
    private fun unpublishOldest() {
        if (held.isEmpty()) return
        val oldest = held.removeAt(0)
        publishedBytes -= oldest.bytes
        grace.addLast(Grace(oldest.file, oldest.bytes, clock() + graceMs))
        graceBytes += oldest.bytes
        trimGrace()
    }

    private fun trimGrace() {
        val now = clock()
        while (grace.isNotEmpty() && grace.first().retireAtMs <= now) deleteOldestGrace()
        while (grace.size > GRACE_SEGMENTS) deleteOldestGrace()
    }

    private fun deleteOldestGrace() {
        if (grace.isEmpty()) return
        val item = grace.removeFirst()
        graceBytes -= item.bytes
        item.file.delete()
    }

    private data class Grace(val file: File, val bytes: Long, val retireAtMs: Long)

    companion object {
        const val ROOT_NAME = "timeshift"
        /** How long an unpublished segment stays on disk for a player that already asked for it. */
        const val READER_GRACE_MS = 15_000L
        /** Newest unpublished files kept. Older ones are deleted so a burst cannot retain the whole window. */
        const val GRACE_SEGMENTS = 3
        private const val PLAYLIST = "index.m3u8"

        private val activeNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun create(root: File, budget: TimeshiftLimits.Budget, freeBytes: () -> Long): TimeshiftStore {
            val name = "s" + UUID.randomUUID().toString().replace("-", "").take(16)
            // Named before the directory exists, so a sweep already in flight skips this session.
            activeNames.add(name)
            return TimeshiftStore(File(root, name), budget.durationMs, budget.maxBytes, freeBytes)
        }

        /**
         * Deletes buffers left by a crash or a killed process. [keep] and any session this
         * process still has open are left in place. Recordings are not under this directory.
         */
        fun sweepStale(root: File, keep: File?) {
            val children = root.listFiles() ?: return
            val open = activeNames.toSet()
            for (child in children) {
                if (keep != null && child.absolutePath == keep.absolutePath) continue
                if (child.name in open) continue
                if (child.isDirectory && child.name.startsWith("s")) child.deleteRecursively()
            }
        }
    }
}
