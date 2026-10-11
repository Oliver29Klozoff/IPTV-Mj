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
 * is written synchronously on the crashing thread — after everything still queued, so the events
 * leading up to it aren't lost.
 *
 * Appends and clears share one ordered queue, so a clear removes exactly what was logged before
 * it — never an event logged after it, and never a crash written after it.
 */
object CrashLog {
    const val FILE_NAME = "crash_log.txt"
    private const val MAX_CHARS = 50_000
    private const val KEEP_CHARS = 40_000

    private sealed class Op {
        class Line(val text: String) : Op()
        object Clear : Op()
    }

    private val lock = Any()
    private val pending = ConcurrentLinkedQueue<Op>()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mktv-crash-log").apply { isDaemon = true }
    }

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** A playback event: returns at once; written in order on the log thread. */
    fun append(context: Context, line: String) = submit(context, Op.Line(line))

    /** Empties the log, in order with everything queued before and after it. */
    fun clear(context: Context) = submit(context, Op.Clear)

    /** A crash: written now, on this thread, after everything still queued. */
    fun appendNow(context: Context, entry: String) = flush(context.applicationContext, entry)

    private fun submit(context: Context, op: Op) {
        val app = context.applicationContext
        pending.add(op)
        writer.execute { flush(app, null) }
    }

    internal fun flush(context: Context, extra: String?) = synchronized(lock) {
        var clear = false
        val batch = StringBuilder()
        while (true) {
            when (val op = pending.poll() ?: break) {
                is Op.Line -> batch.append(op.text)
                // Everything before a clear is gone, file and batch alike.
                Op.Clear -> { clear = true; batch.setLength(0) }
            }
        }
        if (extra != null) batch.append(extra)
        try {
            val f = file(context)
            if (clear) f.delete()
            if (batch.isEmpty()) return@synchronized
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
