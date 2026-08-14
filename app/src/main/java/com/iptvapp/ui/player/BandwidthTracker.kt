package com.iptvapp.ui.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.iptvapp.data.local.dao.BandwidthUsageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Accumulates real network bytes transferred during playback, attributed to the provider
 * (serverIndex) the current stream is playing from, and periodically flushes the running total
 * into BandwidthUsageEntity rather than writing to Room on every onBytesTransferred callback —
 * that callback fires many times per second during normal playback, far too often to hit the DB
 * directly. Cache hits (offline-download playback served from disk, see PlayerActivity's
 * cacheDataSourceFactory kdoc) never reach the network DataSource, so they're correctly excluded
 * — only isNetwork transfers count. One instance per PlayerActivity/player build; call
 * attachTo(factory) on the final DataSource.Factory feeding the player, and flush()/stop() from
 * onStop/onDestroy. */
class BandwidthTracker(
    private val dao: BandwidthUsageDao,
    @Volatile private var serverIndex: Int,
    private val scope: CoroutineScope,
    // Feature C hook — called (best-effort, on IO) after every successful flush so
    // BandwidthBudgetManager.checkAndWarn can re-evaluate the monthly cap against freshly
    // written usage totals. Optional so every pre-existing BandwidthTracker(...) call site
    // that doesn't care about budget warnings keeps compiling unchanged.
    private val onFlushed: (suspend () -> Unit)? = null
) {
    private val pendingBytes = AtomicLong(0L)
    private var flushJob: kotlinx.coroutines.Job? = null

    /** Channel zapping (playChannel/playMergedChannel) and live failover reassign the Activity's
     * own serverIndex field in place and reuse the SAME player/DataSource.Factory/tracker
     * instance (loadStream just calls setMediaItem+prepare, buildPlayer isn't called again) — so
     * without this, bytes transferred after a zap/failover would stay credited to whichever
     * provider was playing when this tracker was first constructed. Flushing before switching the
     * key attributes everything counted so far to the correct (old) provider. */
    fun updateServerIndex(newServerIndex: Int) {
        if (newServerIndex == serverIndex) return
        // flush() reads the mutable `serverIndex` field at whatever point its coroutine actually
        // runs — launching it and reassigning serverIndex right after (without waiting) was a
        // race: if the coroutine got delayed even slightly, it could read the ALREADY-reassigned
        // new index, crediting the old provider's pending bytes to the new one — exactly the bug
        // this whole mechanism exists to prevent. Snapshot the old index and pending bytes here
        // (synchronously, before reassigning) and pass them through explicitly instead.
        val oldServerIndex = serverIndex
        val bytes = pendingBytes.getAndSet(0L)
        serverIndex = newServerIndex
        if (bytes > 0L) {
            scope.launch(Dispatchers.IO) { flushBytes(oldServerIndex, bytes) }
        }
    }

    private val listener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork && bytesTransferred > 0) pendingBytes.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }

    /** Wraps an existing DataSource.Factory so every DataSource it creates reports transfers
     * through this tracker, without needing per-concrete-Factory setTransferListener calls
     * (OkHttpDataSource.Factory/DefaultDataSource.Factory have one, the plain DataSource.Factory
     * interface CacheDataSource.Factory implements doesn't). */
    fun wrap(factory: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        factory.createDataSource().also { it.addTransferListener(listener) }
    }

    /** Starts the periodic flush; only needs to run once per player instance. */
    fun startPeriodicFlush(intervalMs: Long = 7_000L) {
        flushJob?.cancel()
        flushJob = scope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(intervalMs)
                flush()
            }
        }
    }

    /** Writes whatever's accumulated since the last flush and resets the counter. Safe to call
     * repeatedly (e.g. once more from onDestroy after the periodic loop already stopped) since
     * getAndSet(0) makes a no-op flush a true no-op. */
    suspend fun flush() {
        val bytes = pendingBytes.getAndSet(0L)
        if (bytes <= 0L) return
        flushBytes(serverIndex, bytes)
    }

    private suspend fun flushBytes(targetServerIndex: Int, bytes: Long) {
        val yearMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        try {
            dao.addUsage(targetServerIndex, yearMonth, bytes)
            onFlushed?.invoke()
        } catch (_: Exception) {
            // Best-effort stat tracking — never worth crashing playback over.
        }
    }

    fun stop() {
        flushJob?.cancel()
        flushJob = null
    }
}
