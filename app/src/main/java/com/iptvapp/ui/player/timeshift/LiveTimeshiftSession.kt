package com.iptvapp.ui.player.timeshift

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Owns the single provider connection for a local timeshift buffer. The player reads the
 * files in [TimeshiftStore] and does not open the channel itself.
 *
 * Listener methods run on the pump thread. Callers that touch the player must hop to the
 * main thread. Exceptions are not forwarded: their messages can contain the provider URL.
 */
class LiveTimeshiftSession(
    client: OkHttpClient,
    private val store: TimeshiftStore,
    userAgent: String,
    sleeper: TimeshiftSleeper = DEFAULT_SLEEPER,
    maxSteps: Int = Int.MAX_VALUE,
    cutterClock: () -> Long = System::currentTimeMillis,
    private val onNetworkBytes: (Long) -> Unit = {},
    initialListener: TimeshiftLoop.Listener = object : TimeshiftLoop.Listener {},
) {
    private val fetch = OkHttpTimeshiftFetch(client, userAgent, onNetworkBytes)
    private val loop = TimeshiftLoop(store, fetch, sleeper, PumpListener(), maxSteps, cutterClock)
    private var worker: Thread? = null
    private val cleanups = java.util.Collections.synchronizedList(ArrayList<Thread>())
    @Volatile private var listener: TimeshiftLoop.Listener = initialListener

    @Volatile var running: Boolean = false
        private set

    val sessionDir: java.io.File get() = store.playlistFile.parentFile ?: store.playlistFile

    fun start(url: String, listener: TimeshiftLoop.Listener) {
        this.listener = listener
        running = true
        worker = thread(name = "mktv-timeshift", isDaemon = true) {
            try {
                loop.run(url)
            } finally {
                running = false
            }
        }
    }

    fun forceReconnect() {
        fetch.cancel()
    }

    /**
     * Drops the buffer. Safe to call more than once, and it does not block the main thread.
     * Deletes the files here as well as in the pump, so a buffer the pump already decided to
     * keep is still removed when the viewer leaves it.
     */
    fun close() {
        loop.retainFiles = false
        loop.close()
        fetch.shutdown()
        worker?.interrupt()
        // Deleting can wait on a segment write and then remove a long directory.
        // That stays off the main thread. Channel changes call this directly.
        inBackground { store.deleteAll() }
    }

    /** Stops the provider read but leaves the files so a viewer who is behind can finish them. */
    fun stopKeepingFiles() {
        loop.retainFiles = true
        loop.close()
        fetch.shutdown()
        worker?.interrupt()
        // A live playlist that stops growing makes Media3 declare the stream stuck and
        // jump back to the provider. Ending the list lets the viewer finish the window.
        // If that write fails, the disk is still unusable. Tell the player instead of
        // letting the exception kill the process.
        inBackground {
            try {
                store.finishRetainedWindow()
            } catch (_: Exception) {
                notifyRetainFailed()
            }
        }
    }

    private fun notifyRetainFailed() {
        try {
            listener.onRetainFailed()
        } catch (_: Exception) {
        }
    }

    private fun inBackground(block: () -> Unit) {
        cleanups.add(thread(name = "mktv-timeshift-cleanup", isDaemon = true) {
            try {
                block()
            } catch (_: Exception) {
                // A cleanup failure must not take the process down.
            }
        })
    }

    fun joinForTest(timeoutMs: Long) {
        worker?.join(timeoutMs)
        cleanups.toList().forEach { it.join(timeoutMs) }
    }

    private inner class PumpListener : TimeshiftLoop.Listener {
        override fun onReady(playlist: java.io.File) = listener.onReady(playlist)
        override fun onGrew() = listener.onGrew()
        override fun onNeedDirect() = listener.onNeedDirect()
        override fun onContinuityLost() = listener.onContinuityLost()
        override fun onStorageDegraded() = listener.onStorageDegraded()
        override fun onGiveUp() = listener.onGiveUp()
        override fun onStopped() {
            running = false
            listener.onStopped()
        }
    }

    private class OkHttpTimeshiftFetch(
        private val client: OkHttpClient,
        private val userAgent: String,
        private val onNetworkBytes: (Long) -> Unit,
    ) : TimeshiftLoop.Fetcher {
        @Volatile private var stopped = false
        @Volatile private var active: Call? = null
        private val gate = Any()

        /** Aborts the call in flight. A later open is still allowed, so a reconnect can continue. */
        fun cancel() {
            val call = synchronized(gate) { active }
            call?.cancel()
        }

        /**
         * No further provider requests, including one that races this call.
         * [cancel] alone is not enough: the pump can be between segments, with nothing to abort,
         * and then open the next one beside the channel the viewer just switched to.
         */
        fun shutdown() {
            val call = synchronized(gate) {
                stopped = true
                active
            }
            call?.cancel()
        }

        override fun open(url: String): TimeshiftLoop.Opened {
            if (stopped) throw IOException("timeshift closed")
            if (!TimeshiftLoop.isHttp(url)) throw IOException("unsupported")
            val call = client.newCall(
                Request.Builder().url(url).header("User-Agent", userAgent).build()
            )
            synchronized(gate) {
                if (stopped) {
                    call.cancel()
                    throw IOException("timeshift closed")
                }
                active = call
            }
            val response = try {
                call.execute()
            } catch (e: IOException) {
                throw IOException("timeshift open failed")
            }
            if (!response.isSuccessful) {
                response.close()
                throw IOException("timeshift status")
            }
            val body = response.body ?: run {
                response.close()
                throw IOException("timeshift empty")
            }
            return OkOpened(
                response.request.url.toString(),
                response.header("Content-Type"),
                body.source(),
                response,
                onNetworkBytes,
            )
        }
    }

    private class OkOpened(
        private val url: String,
        private val contentType: String?,
        private val source: BufferedSource,
        private val response: okhttp3.Response,
        private val onNetworkBytes: (Long) -> Unit,
    ) : TimeshiftLoop.Opened {
        private var kind: TimeshiftLoop.Kind? = null

        override fun sniff(): TimeshiftLoop.Kind {
            kind?.let { return it }
            try {
                val peek = source.peek()
                peek.request(512)
                val size = minOf(512, peek.buffer.size.toInt())
                val prefix = ByteArray(size)
                if (size > 0) peek.read(prefix)
                val sniffed = TimeshiftLoop.sniff(prefix, contentType)
                kind = sniffed
                return sniffed
            } catch (_: IOException) {
                throw IOException("timeshift sniff")
            }
        }

        override fun playlistText(): String? {
            return try {
                val bytes = readLimited(source, MAX_PLAYLIST_BYTES)
                if (bytes.isNotEmpty()) onNetworkBytes(bytes.size.toLong())
                if (bytes.isEmpty()) null else bytes.toString(Charsets.UTF_8)
            } catch (_: IOException) {
                null
            }
        }

        override fun finalUrl(): String = url

        override fun read(buffer: ByteArray): Int {
            return try {
                val n = source.read(buffer)
                if (n > 0) onNetworkBytes(n.toLong())
                n
            } catch (_: IOException) {
                // A dropped segment is not a clean end. Callers must not store a short file.
                throw IOException("timeshift read")
            }
        }

        override fun close() {
            try {
                response.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private val DEFAULT_SLEEPER = TimeshiftSleeper { ms ->
            if (ms <= 0L) return@TimeshiftSleeper
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024

        private fun readLimited(source: BufferedSource, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (total < max) {
                val n = source.read(buffer, 0, minOf(buffer.size, max - total))
                if (n < 0) break
                if (n == 0) continue
                out.write(buffer, 0, n)
                total += n
            }
            return out.toByteArray()
        }
    }
}
