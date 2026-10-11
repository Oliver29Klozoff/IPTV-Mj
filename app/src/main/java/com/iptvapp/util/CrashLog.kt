package com.iptvapp.util

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * crash_log.txt: crashes plus non-fatal playback events, kept to its last ~40 KB.
 *
 * Every write reads the file, trims it and writes it back. Playback events come from the main
 * thread (player and home screens) dozens of times a session, so they are queued and written in
 * order on one background thread instead. A crash is different: the process is about to die, so it
 * is written synchronously on the crashing thread — after any events still queued, so the events
 * leading up to it aren't lost.
 */
object CrashLog {
    const val FILE_NAME = "crash_log.txt"
    private const val MAX_CHARS = 50_000
    private const val KEEP_CHARS = 40_000

    private val lock = Any()
    private val pending = ConcurrentLinkedQueue<String>()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mktv-crash-log").apply { isDaemon = true }
    }

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** A playback event: returns at once; written in order on the log thread. */
    fun append(context: Context, line: String) {
        val app = context.applicationContext
        pending.add(line)
        writer.execute { flush(app, null) }
    }

    /** A crash: written now, on this thread, after anything still queued. */
    fun appendNow(context: Context, entry: String) = flush(context.applicationContext, entry)

    /** Deletes the log off the calling thread, in order with queued writes. */
    fun clear(context: Context) {
        val app = context.applicationContext
        writer.execute { synchronized(lock) { runCatching { file(app).delete() } } }
    }

    internal fun flush(context: Context, extra: String?) = synchronized(lock) {
        val batch = StringBuilder()
        while (true) batch.append(pending.poll() ?: break)
        if (extra != null) batch.append(extra)
        if (batch.isEmpty()) return@synchronized
        try {
            val f = file(context)
            val existing = if (f.exists()) f.readText() else ""
            val trimmed = if (existing.length > MAX_CHARS) existing.takeLast(KEEP_CHARS) else existing
            f.writeText(trimmed + batch)
        } catch (_: Exception) {}
    }

    /** Waits for queued writes. Tests only. */
    internal fun drainForTest() {
        writer.submit {}.get()
    }
}
