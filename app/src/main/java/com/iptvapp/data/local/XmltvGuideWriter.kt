package com.iptvapp.data.local

import androidx.room.withTransaction
import com.iptvapp.data.local.entities.EpgEntity
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Swaps one provider's guide rows only after a newer guide is ready to store.
 * Network download and XML parsing stay outside this class, so a failure there
 * never reaches the delete. One gate per serverIndex: a primary refresh does
 * not block a different provider, and an older refresh cannot commit after a
 * newer one has started or the provider in that slot has been cleared.
 */
class XmltvGuideWriter(private val db: IptvDatabase) {
    private class Gate {
        val mutex = Mutex()
        val ticket = AtomicInteger(0)
    }

    private val gates = ConcurrentHashMap<Int, Gate>()

    private fun gate(serverIndex: Int) = gates.getOrPut(serverIndex) { Gate() }

    /** A refresh calls this when it starts. A later [begin] or [clear] makes the ticket stale. */
    fun begin(serverIndex: Int): Int = gate(serverIndex).ticket.incrementAndGet()

    /**
     * The provider in this slot changed. Bump the ticket and delete that slot's guide
     * so a refresh that already started cannot write the old provider back afterwards.
     */
    suspend fun clear(serverIndex: Int) {
        val g = gate(serverIndex)
        g.mutex.withLock {
            g.ticket.incrementAndGet()
            coroutineContext.ensureActive()
            db.epgDao().deleteAllForServer(serverIndex)
        }
    }

    /**
     * Replace [serverIndex]'s guide with [entries].
     * [streamIds] null replaces the whole slot. A list replaces only those channels,
     * which is how the public backup guide behaves: it does not cover every channel.
     *
     * Returns the number of rows written, or 0 when nothing was written. An empty
     * [entries] list returns 0 without deleting. [afterDelete] runs inside the
     * transaction after the delete and before the insert, so a failure there rolls
     * the delete back. Production passes the default.
     */
    suspend fun commit(
        serverIndex: Int,
        ticket: Int,
        entries: List<EpgEntity>,
        streamIds: List<Int>?,
        stillCurrent: suspend () -> Boolean,
        afterDelete: suspend () -> Unit = {}
    ): Int {
        if (entries.isEmpty()) return 0
        if (streamIds != null && streamIds.isEmpty()) return 0
        coroutineContext.ensureActive()
        val g = gate(serverIndex)
        return g.mutex.withLock {
            if (g.ticket.get() != ticket) return@withLock 0
            coroutineContext.ensureActive()
            var wrote = 0
            db.withTransaction {
                if (!stillCurrent()) return@withTransaction
                coroutineContext.ensureActive()
                val dao = db.epgDao()
                if (streamIds == null) {
                    dao.deleteAllForServer(serverIndex)
                } else {
                    streamIds.distinct().chunked(500).forEach { dao.deleteForServerStreams(serverIndex, it) }
                }
                afterDelete()
                coroutineContext.ensureActive()
                entries.chunked(500).forEach { dao.upsertEpg(it) }
                wrote = entries.size
            }
            wrote
        }
    }
}
