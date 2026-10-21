package com.iptvapp.handoff

import org.json.JSONObject

/** Where an incoming handoff stands, as the sender sees it ([wire]). */
enum class InState(val wire: String, val active: Boolean) {
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
        // Resolution reads the catalog (and maybe the provider) — outside the lock.
        val resolution = matcher.resolve(d, peer.secret, peer.providerMap)
        val incoming = Incoming(d.txId, peer.deviceId, peer.label, d, now(), resolution)
        synchronized(this) {
            if (recent.containsKey(d.txId)) return replyFor(recent[d.txId]!!)
            if (current() != null) return reply("busy", "Another handoff is waiting on this device")
            when (resolution) {
                is Resolution.NotFound -> fail(incoming, resolution.message)
                is Resolution.Unavailable -> fail(incoming, resolution.message)
                else -> Unit
            }
            remember(incoming)
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
            return r
        }
    }

    /** The player opened for [txId] is really playing. */
    @Synchronized
    fun onPlaying(txId: String) {
        val inc = recent[txId] ?: return
        if (inc.state == InState.PREPARING) inc.state = InState.PLAYING
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
    }
}
