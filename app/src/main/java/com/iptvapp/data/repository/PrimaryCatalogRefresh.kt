package com.iptvapp.data.repository

import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.CategoryEntity
import com.iptvapp.data.local.entities.SeriesEntity
import com.iptvapp.data.local.entities.VodEntity
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A movie or series refresh that lost its turn to a newer refresh or a provider switch. Not a
 * failure — the download was fine, it just no longer matters — so screens don't show it as
 * "Failed". A real cancellation of the caller still propagates as CancellationException (v7.21).
 */
class CatalogRefreshSuperseded : Exception(MESSAGE) {
    companion object {
        const val MESSAGE = "Replaced by a newer refresh"
    }
}

/** True when this result is a refresh that was replaced, not one that failed. */
fun com.iptvapp.util.Resource<*>.wasSuperseded(): Boolean =
    this is com.iptvapp.util.Resource.Error && cause is CatalogRefreshSuperseded

/**
 * Primary movie and series catalog writes.
 *
 * One refresh of a kind runs at a time. Starting another, or a provider switch
 * ([supersede]), makes the older one write nothing. A switch cancels the download
 * and does not wait for the old provider to answer; it only waits for a write that
 * has already started. Rows are stored before any old row is removed, and a refresh
 * that lost its turn removes nothing. An empty list is a real empty catalog and
 * clears that table. A null response never reaches these methods.
 *
 * Titles are mapped one chunk at a time so a 100k-title catalog is not held twice.
 */
class PrimaryCatalogRefresh(private val db: IptvDatabase) {
    private class Gate {
        val download = Mutex()
        val write = Mutex()
        val ticket = AtomicInteger(0)
        // The running download and the ticket it belongs to.
        val inflight = AtomicReference<Pair<Int, Job>?>(null)
    }

    // Categories and titles have separate turns. A category fetch must not discard an
    // in-flight title download. A provider switch ([supersede]) ends every turn.
    private val vodTitles = Gate()
    private val vodCategories = Gate()
    private val seriesTitles = Gate()
    private val seriesCategories = Gate()

    /** The in-flight movie and series refreshes, titles and categories, are now obsolete. */
    fun supersede() {
        listOf(vodTitles, vodCategories, seriesTitles, seriesCategories).forEach { gate ->
            gate.ticket.incrementAndGet()
            gate.inflight.get()?.second?.cancel()
        }
    }

    /**
     * Runs [block] only if this call is still the latest movie-title refresh.
     * Returns null when a newer refresh or [supersede] already took the turn.
     * [block]'s `still` is false once that happens, and throws if this job was cancelled.
     */
    suspend fun <T> runVod(block: suspend (still: suspend () -> Boolean) -> T): T? = run(vodTitles, block)

    /** Movie categories. Independent of [runVod]. */
    suspend fun <T> runVodCategories(block: suspend (still: suspend () -> Boolean) -> T): T? =
        run(vodCategories, block)

    /** Series counterpart of [runVod]. */
    suspend fun <T> runSeries(block: suspend (still: suspend () -> Boolean) -> T): T? = run(seriesTitles, block)

    /** Series categories. Independent of [runSeries]. */
    suspend fun <T> runSeriesCategories(block: suspend (still: suspend () -> Boolean) -> T): T? =
        run(seriesCategories, block)

    /**
     * Waits until movie and series writes have stopped, then runs [block].
     * Does not wait for a download. [supersede] cancels that download.
     */
    suspend fun <T> afterIdle(block: suspend () -> T): T =
        vodTitles.write.withLock {
            vodCategories.write.withLock {
                seriesTitles.write.withLock {
                    seriesCategories.write.withLock { block() }
                }
            }
        }

    private suspend fun <T> run(gate: Gate, block: suspend (still: suspend () -> Boolean) -> T): T? {
        val mine = gate.ticket.incrementAndGet()
        // The download already running is now obsolete: cancel it rather than wait for it (a stalled
        // request could hold the turn up to its read timeout). Only an older ticket's download —
        // never a newer call's that got the lock first.
        gate.inflight.get()?.let { (ticket, job) -> if (ticket < mine) job.cancel() }
        return gate.download.withLock {
            coroutineContext.ensureActive()
            if (gate.ticket.get() != mine) return@withLock null
            // The download has its own job. A provider switch cancels that job only, so the
            // screen that started the refresh can still re-enable its button. Cancelling the
            // screen's own job still cancels this one. Publish the job before it starts, then
            // drop it if the turn was already lost, so the switch cannot miss it.
            supervisorScope {
                val child = async(start = CoroutineStart.LAZY) {
                    block {
                        coroutineContext.ensureActive()
                        gate.ticket.get() == mine
                    }
                }
                val entry = mine to child
                gate.inflight.set(entry)
                if (gate.ticket.get() != mine) child.cancel()
                try {
                    child.await()
                } catch (e: CancellationException) {
                    // Only a refresh that lost its turn ends quietly (null). The caller being
                    // cancelled, or a cancellation from inside the refresh itself, propagates (v7.21).
                    if (!coroutineContext.isActive || gate.ticket.get() == mine) throw e
                    null
                } finally {
                    gate.inflight.compareAndSet(entry, null)
                }
            }
        }
    }

    suspend fun <T> replaceVod(
        items: List<T>,
        stillCurrent: suspend () -> Boolean,
        toRow: (T) -> VodEntity,
        onChunk: (saved: Int, total: Int) -> Unit = { _, _ -> }
    ): Boolean = vodTitles.write.withLock {
        replace(
            items, stillCurrent, toRow, VodEntity::streamId, onChunk,
            db.vodDao()::upsertVod,
            // Only titles with nothing of the user's on them. A favorite or a started movie the
            // provider leaves out (often for a while) keeps its row, so nothing is lost if it returns.
            { db.vodDao().getUserData().filter { !it.isFavorite && it.watchedMs == 0L && it.durationMs == 0L }.map { it.streamId } },
            db.vodDao()::deleteStaleByIds
        )
    }

    suspend fun <T> replaceSeries(
        items: List<T>,
        stillCurrent: suspend () -> Boolean,
        toRow: (T) -> SeriesEntity,
        onChunk: (saved: Int, total: Int) -> Unit = { _, _ -> }
    ): Boolean = seriesTitles.write.withLock {
        replace(
            items, stillCurrent, toRow, SeriesEntity::seriesId, onChunk,
            db.seriesDao()::upsertSeries,
            // Same rule as movies: a favorite, started or hidden show keeps its row.
            {
                db.seriesDao().getUserData()
                    .filter { !it.isFavorite && it.watchedMs == 0L && it.durationMs == 0L && !it.isHidden }
                    .map { it.seriesId }
            },
            db.seriesDao()::deleteStaleByIds
        )
    }

    suspend fun replaceCategories(
        type: String,
        rows: List<CategoryEntity>,
        stillCurrent: suspend () -> Boolean
    ): Boolean = categoriesGate(type).write.withLock {
        if (!stillCurrent()) return@withLock false
        if (rows.isNotEmpty()) db.categoryDao().upsertCategories(rows)
        if (!stillCurrent()) return@withLock false
        val keep = rows.map { it.categoryId }.toHashSet()
        val stale = db.categoryDao().getIdsByType(type).filter { it !in keep }
        if (!stillCurrent()) return@withLock false
        for (chunk in stale.chunked(500)) {
            if (!stillCurrent()) return@withLock false
            db.categoryDao().deleteByTypeAndIds(type, chunk)
        }
        true
    }

    private fun categoriesGate(type: String) = if (type == "series") seriesCategories else vodCategories

    private suspend fun <T, E> replace(
        items: List<T>,
        stillCurrent: suspend () -> Boolean,
        toRow: (T) -> E,
        idOf: (E) -> Int,
        onChunk: (saved: Int, total: Int) -> Unit,
        upsert: suspend (List<E>) -> Unit,
        existingIds: suspend () -> List<Int>,
        deleteIds: suspend (List<Int>) -> Unit
    ): Boolean {
        val keep = HashSet<Int>(items.size)
        var saved = 0
        for (chunk in items.chunked(2000)) {
            if (!stillCurrent()) return false
            val rows = chunk.map(toRow)
            rows.forEach { keep.add(idOf(it)) }
            upsert(rows)
            saved += chunk.size
            onChunk(saved, items.size)
        }
        if (!stillCurrent()) return false
        val stale = existingIds().filter { it !in keep }
        if (!stillCurrent()) return false
        for (chunk in stale.chunked(500)) {
            if (!stillCurrent()) return false
            deleteIds(chunk)
        }
        return true
    }
}
