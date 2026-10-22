package com.iptvapp.handoff

import org.json.JSONObject

/** Where an incoming handoff stands, as the sender sees it ([wire]). */
enum class InState(val wire: String, val active: Boolean) {
    /** Registered, its catalog still being checked (the sender just waits). */
    RESOLVING("awaiting", true),
    AWAITING_APPROVAL("awaiting", true),
    PREPARING("preparing", true),
    PLAYING("playing", false),
    COMPLETED("completed", false),
    DECLINED("declined", false),
    FAILED("failed", false),
    CANCELLED("cancelled", false),
    TIMED_OUT("timed_out", false),
}

/** One incoming handoff. Mutable only inside [HandoffReceiver] (synchronized). */
class Incoming internal constructor(
    val txId: String,
    val peerId: String,
    val peerName: String,
    val descriptor: Descriptor,
    val receivedAtMs: Long,
    resolution: Resolution,
) {
    var resolution: Resolution = resolution
        internal set
    var state: InState = InState.AWAITING_APPROVAL
        internal set
    var reason: String = ""
        internal set
    var acceptedAtMs: Long = 0L
        internal set
    /** The sender's latest position and whether it was playing, and when we heard it. */
    internal var sourcePositionMs = descriptor.positionMs
    internal var sourcePlaying = descriptor.playing
    internal var sourceAtMs = receivedAtMs
}

/** What the receiver's screens do; the app's implementation shows the prompt or a notification. */
interface ReceiverUi {
    /** Asks the person. False when it can't be shown (MKTV not in front on a TV, notifications off). */
    fun present(incoming: Incoming): Boolean
    /** The prompt for [txId] is no longer needed (cancelled, timed out, superseded). */
    fun dismiss(txId: String)
    /** A player opened for [txId] should close (the sender cancelled before it started). */
    fun closePlayer(txId: String)
}

/** What to open once the person accepts. */
data class StartPlan(val txId: String, val target: Target, val startPositionMs: Long, val descriptor: Descriptor)

/**
 * The receiving side of handoff: one incoming at a time, keyed by transaction id (a repeated offer
 * gets the same answer and never a second prompt or player), with deadlines for the answer and for
 * the player to really start. The sender is told PLAYING only when the player reports it plays.
 */
class HandoffReceiver(
    private val matcher: ContentMatcher,
    private val ui: ReceiverUi,
    /** True while this device is itself sending a handoff (opposing transfers are refused). */
    private val isSending: () -> Boolean,
    /** "TV" or "phone": this device, for messages the sender shows. */
    private val ownKind: String = "TV",
) {
    private val recent = LinkedHashMap<String, Incoming>()

    @Synchronized
    fun current(): Incoming? = recent.values.lastOrNull { it.state.active }?.also { expire(it, now()) }?.takeIf { it.state.active }

    @Synchronized
    fun get(txId: String): Incoming? = recent[txId]

    /** Test hook and the single clock for deadlines. */
    var now: () -> Long = { System.currentTimeMillis() }

    suspend fun offer(peer: Peer, d: Descriptor): JSONObject {
        synchronized(this) {
            recent[d.txId]?.let { existing ->
                // The same offer again (lost reply, retry): the same answer, never a second prompt.
                if (existing.peerId != peer.deviceId) return reply("failed", "Not your request")
                expire(existing, now())
                return replyFor(existing)
            }
            if (isSending()) return reply("busy", "This device is sending something itself")
            current()?.let { return reply("busy", "Another handoff is waiting on this device") }
        }
        // Registered before the catalog is read, so a cancel during the lookup is not lost and a
        // second offer meanwhile is busy.
        val incoming = Incoming(d.txId, peer.deviceId, peer.label, d, now(), Resolution.Unavailable("Checking"))
        synchronized(this) {
            if (recent.containsKey(d.txId)) return replyFor(recent[d.txId]!!)
            if (current() != null) return reply("busy", "Another handoff is waiting on this device")
            incoming.state = InState.RESOLVING
            remember(incoming)
        }
        // Resolution reads the catalog (and maybe the provider) — outside the lock.
        val resolution = matcher.resolve(d, peer.secret, peer.providerMap)
        synchronized(this) {
            // Cancelled (or expired) while the catalog was read: nothing is shown.
            if (incoming.state != InState.RESOLVING) return replyFor(incoming)
            incoming.resolution = resolution
            incoming.state = InState.AWAITING_APPROVAL
            when (resolution) {
                is Resolution.NotFound -> fail(incoming, resolution.message)
                is Resolution.Unavailable -> fail(incoming, resolution.message)
                else -> Unit
            }
            if (incoming.state == InState.AWAITING_APPROVAL && !ui.present(incoming)) {
                fail(incoming, "Open MKTV on the $ownKind to accept")
            }
            return replyFor(incoming)
        }
    }

    /** The sender's poll, with its current position. */
    @Synchronized
    fun status(peer: Peer, txId: String, source: HandoffProtocol.SourceState?): JSONObject {
        val inc = recent[txId]?.takeIf { it.peerId == peer.deviceId } ?: return reply("failed", "Unknown request")
        if (source != null && inc.state.active) {
            inc.sourcePositionMs = source.positionMs
            inc.sourcePlaying = source.playing
            inc.sourceAtMs = now()
        }
        expire(inc, now())
        return replyFor(inc)
    }

    /** The sender stopped its own playback: done. */
    @Synchronized
    fun complete(peer: Peer, txId: String): JSONObject {
        val inc = recent[txId]?.takeIf { it.peerId == peer.deviceId } ?: return reply("failed", "Unknown request")
        if (inc.state == InState.PLAYING) inc.state = InState.COMPLETED
        return replyFor(inc)
    }

    /** The sender gave up. A prompt goes away; a player that hasn't started yet closes. */
    @Synchronized
    fun cancel(peer: Peer, txId: String): JSONObject {
        val inc = recent[txId]?.takeIf { it.peerId == peer.deviceId } ?: return reply("failed", "Unknown request")
        when (inc.state) {
            InState.RESOLVING -> inc.state = InState.CANCELLED
            InState.AWAITING_APPROVAL -> { inc.state = InState.CANCELLED; ui.dismiss(txId) }
            InState.PREPARING -> { inc.state = InState.CANCELLED; ui.closePlayer(txId) }
            else -> Unit
        }
        return replyFor(inc)
    }

    /** The person picked Play here (with [choice] when there were several). Null if it's too late. */
    @Synchronized
    fun accept(txId: String, choice: Target? = null): StartPlan? {
        val inc = recent[txId] ?: return null
        expire(inc, now())
        if (inc.state != InState.AWAITING_APPROVAL) return null
        val target = when (val r = inc.resolution) {
            is Resolution.Playable -> r.target
            is Resolution.Choose -> choice?.takeIf { it in r.options } ?: return null
            else -> return null
        }
        inc.state = InState.PREPARING
        inc.acceptedAtMs = now()
        return StartPlan(txId, target, startPosition(inc, target), inc.descriptor)
    }

    @Synchronized
    fun decline(txId: String) {
        val inc = recent[txId] ?: return
        if (inc.state == InState.AWAITING_APPROVAL) inc.state = InState.DECLINED
    }

    /** The person mapped the sender's provider to [slot]: resolve again (the prompt stays up). */
    suspend fun remap(peer: Peer, txId: String): Resolution? {
        val inc = synchronized(this) { recent[txId]?.takeIf { it.state == InState.AWAITING_APPROVAL } } ?: return null
        val r = matcher.resolve(inc.descriptor, peer.secret, peer.providerMap)
        synchronized(this) {
            if (inc.state != InState.AWAITING_APPROVAL) return null
            inc.resolution = r
            // The chosen provider doesn't have it (or can't be checked): over, and the sender is told why.
            when (r) {
                is Resolution.NotFound -> fail(inc, r.message)
                is Resolution.Unavailable -> fail(inc, r.message)
                else -> Unit
            }
            return r
        }
    }

    /**
     * The player opened for [txId] is really playing. False when it's too late (deadline passed,
     * cancelled): that player should close, since the sender carries on.
     */
    @Synchronized
    fun onPlaying(txId: String): Boolean {
        val inc = recent[txId] ?: return false
        expire(inc, now())
        if (inc.state != InState.PREPARING) return false
        inc.state = InState.PLAYING
        return true
    }

    /**
     * Applies deadlines without waiting for the sender (it may be gone): a prompt nobody answered
     * goes away, a player that never started closes. True while something is still active.
     */
    @Synchronized
    fun sweep(): Boolean {
        val t = now()
        for (inc in recent.values.toList()) if (inc.state.active) expire(inc, t)
        return recent.values.any { it.state.active }
    }

    /**
     * The player opened for [txId] closed. Before the sender confirmed (PLAYING, not COMPLETED) the
     * transfer didn't happen after all: the sender must not pause for a player that's gone.
     */
    @Synchronized
    fun onPlayerClosed(txId: String) {
        val inc = recent[txId] ?: return
        if (inc.state == InState.PLAYING || inc.state == InState.PREPARING) fail(inc, "Playback was closed on the other device")
    }

    /** The catalog check for [txId] was given up (took too long): over, so the next offer isn't busy. */
    @Synchronized
    fun abandonResolving(txId: String, why: String) {
        val inc = recent[txId] ?: return
        if (inc.state == InState.RESOLVING) fail(inc, why)
    }

    /** The player opened for [txId] couldn't play it. */
    @Synchronized
    fun onPlayerFailed(txId: String, why: String) {
        val inc = recent[txId] ?: return
        if (inc.state == InState.PREPARING) fail(inc, why)
    }

    private fun fail(inc: Incoming, why: String) {
        inc.state = InState.FAILED
        inc.reason = why
    }

    private fun expire(inc: Incoming, nowMs: Long) {
        when (inc.state) {
            InState.RESOLVING -> if (nowMs - inc.receivedAtMs > RESOLVE_TTL_MS) fail(inc, "This device took too long to check its catalog")
            InState.AWAITING_APPROVAL -> if (nowMs - inc.receivedAtMs > HandoffProtocol.APPROVAL_TTL_MS) {
                inc.state = InState.TIMED_OUT
                ui.dismiss(inc.txId)
            }
            InState.PREPARING -> if (nowMs - inc.acceptedAtMs > HandoffProtocol.PREPARE_TTL_MS) {
                fail(inc, "The player didn't start in time")
                ui.closePlayer(inc.txId)
            }
            else -> Unit
        }
    }

    private fun remember(inc: Incoming) {
        recent[inc.txId] = inc
        while (recent.size > 16) recent.remove(recent.keys.first())
    }

    private fun replyFor(inc: Incoming): JSONObject = reply(inc.state.wire, inc.reason)

    private fun reply(state: String, reason: String) = JSONObject().put("state", state).put("reason", reason)

    companion object {
        /**
         * Where to start: live always at the live edge (0); otherwise the sender's latest position,
         * plus the time since we heard it if it was playing (capped), never negative.
         */
        fun startPosition(inc: Incoming, target: Target): Long {
            if (target.kind == ContentKind.LIVE) return 0L
            val elapsed = if (inc.sourcePlaying) (inc.acceptedAtMs - inc.sourceAtMs).coerceIn(0L, HandoffProtocol.MAX_CATCH_UP_MS) else 0L
            return (inc.sourcePositionMs + elapsed).coerceAtLeast(0L)
        }

        /** A start position kept inside the receiver's own copy ([durationMs] known): not in its last seconds. */
        fun clampToDuration(positionMs: Long, durationMs: Long): Long {
            if (durationMs <= 0L) return positionMs.coerceAtLeast(0L)
            val latest = (durationMs - END_MARGIN_MS).coerceAtLeast(0L)
            return positionMs.coerceIn(0L, latest)
        }

        const val END_MARGIN_MS = 10_000L
        /** A catalog check that never finishes doesn't hold the receiver. */
        const val RESOLVE_TTL_MS = 30_000L
    }
}
