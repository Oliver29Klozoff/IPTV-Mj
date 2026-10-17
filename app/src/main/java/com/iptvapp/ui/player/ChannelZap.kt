package com.iptvapp.ui.player

/**
 * Which neighboring channels may be prepared before CH+ or CH−.
 *
 * A provider account that is already at its connection limit cannot open another stream.
 * Unknown limits are not treated as unlimited. Low-memory devices and a memory warning
 * prepare nothing. At most two neighbors are chosen, next channel first.
 */
object ChannelZap {
    const val MAX_NEIGHBORS = 2

    /** How much of a neighbor to have ready. Short on purpose: this is a live stream, not a download. */
    const val PRELOAD_RANGE_MS = 2_000L

    private val ACCOUNT_BUSY = intArrayOf(458, 460, 461)

    fun accountBusy(httpStatus: Int?): Boolean = httpStatus != null && httpStatus in ACCOUNT_BUSY

    /** Spare streams this account can still open. Null from the panel means unknown, so zero. */
    fun spareSlots(maxConnections: Int?, activeConnections: Int?): Int {
        if (maxConnections == null || activeConnections == null) return 0
        if (maxConnections <= 0) return 0
        return (maxConnections - activeConnections).coerceAtLeast(0)
    }

    /**
     * Spare streams while this device may already be watching.
     * A panel that still reports zero has not counted that stream yet.
     * An unknown count stays unknown, which prepares nothing.
     */
    fun spareWhilePlaying(maxConnections: Int?, activeConnections: Int?, holdsConnection: Boolean): Int {
        val active = if (holdsConnection && activeConnections == 0) 1 else activeConnections
        return spareSlots(maxConnections, active)
    }

    /** Next, then previous. One other channel when the list has only two. Empty for a single channel. */
    fun neighborOrder(current: Int, size: Int): List<Int> {
        if (size < 2 || current !in 0 until size) return emptyList()
        val next = (current + 1) % size
        val previous = if (current == 0) size - 1 else current - 1
        return if (next == previous) listOf(next) else listOf(next, previous)
    }

    /**
     * Indexes to preload. [serverOf] is one server index per channel, parallel to the zap list.
     * [spareByServer] is remaining streams for each account, already counting the one playing.
     * Each pick uses one spare on that channel's own account.
     */
    fun select(
        current: Int,
        serverOf: List<Int>,
        spareByServer: Map<Int, Int>,
        lowRamDevice: Boolean,
        memoryPressure: Boolean
    ): List<Int> {
        if (lowRamDevice || memoryPressure) return emptyList()
        val remaining = HashMap(spareByServer)
        val picked = ArrayList<Int>(MAX_NEIGHBORS)
        for (index in neighborOrder(current, serverOf.size)) {
            if (picked.size == MAX_NEIGHBORS) break
            val server = serverOf[index]
            val left = remaining[server] ?: 0
            if (left <= 0) continue
            remaining[server] = left - 1
            picked += index
        }
        return picked
    }
}
