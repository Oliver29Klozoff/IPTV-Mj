package com.iptvapp.handoff

import kotlinx.coroutines.delay
import org.json.JSONObject

/** Where an outgoing handoff stands. */
enum class OutState(val terminal: Boolean) {
    IDLE(false),
    REQUESTING(false),
    AWAITING_APPROVAL(false),
    PREPARING(false),
    TRANSFERRED(true),
    FAILED(true),
    DECLINED(true),
    CANCELLED(true),
    TIMED_OUT(true),
}

/** Sends one message to a peer; null when it couldn't be reached or the reply couldn't be trusted. */
interface HandoffTransport {
    suspend fun send(peer: Peer, type: String, message: JSONObject): JSONObject?
}

/** The source's own playback, read fresh at each step. */
interface SourcePlayback {
    fun positionMs(): Long
    fun isPlaying(): Boolean
    /** Called once, only after the receiver reports that it really plays. */
    fun pauseForHandoff()
}

/**
 * The sending side: offer, then poll about once a second (carrying the current position) until the
 * receiver plays, declines, fails or time runs out. The source's own playback is untouched until the
 * receiver reports PLAYING; then it pauses (once). Every failure leaves it playing as it was.
 *
 * Retries are bounded, every request in one transaction carries the same id (so a repeated offer is
 * answered, not acted on twice), and a run of unanswered polls ends the attempt.
 */
class HandoffSender(
    private val transport: HandoffTransport,
    private val playback: SourcePlayback,
    private val onState: (OutState, String) -> Unit,
    private val pollMs: Long = 1_000L,
    private val maxMissedPolls: Int = 6,
    /** Wall clock for the overall deadline (the test scheduler's time in tests). */
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile var state: OutState = OutState.IDLE
        private set
    @Volatile private var cancelled = false

    /** Asks to stop. Takes effect at the next step; nothing is undone after TRANSFERRED. */
    fun cancel() { cancelled = true }

    suspend fun run(peer: Peer, d: Descriptor): OutState {
        HandoffProtocol.validate(d)
        set(OutState.REQUESTING, "Connecting…")
        var reply: JSONObject? = null
        for (attempt in 0 until 3) {
            if (cancelled) return finish(peer, d, OutState.CANCELLED, "Cancelled")
            reply = transport.send(peer, "offer", JSONObject().put("t", "offer").put("tx", d.txId).put("d", HandoffProtocol.encode(d)))
            if (reply != null) break
            delay(500L * (attempt + 1))
        }
        // No answer doesn't mean the offer never arrived: tell the receiver to drop it all the same.
        if (reply == null) return finish(peer, d, OutState.FAILED, "Couldn't reach ${peer.label}")
        apply(reply)?.let { return finishFromReply(peer, d, it) }

        val deadline = now() + HandoffProtocol.APPROVAL_TTL_MS + HandoffProtocol.PREPARE_TTL_MS + 15_000L
        var missed = 0
        while (true) {
            if (cancelled) return finish(peer, d, OutState.CANCELLED, "Cancelled")
            if (now() > deadline) return finish(peer, d, OutState.TIMED_OUT, "${peer.label} didn't answer in time")
            delay(pollMs)
            if (cancelled) return finish(peer, d, OutState.CANCELLED, "Cancelled")
            val poll = JSONObject().put("t", "status").put("tx", d.txId)
            if (d.kind != ContentKind.LIVE) poll.put("pos", playback.positionMs().coerceAtLeast(0L)).put("playing", playback.isPlaying())
            val r = transport.send(peer, "status", poll)
            if (r == null) {
                if (++missed >= maxMissedPolls) return finish(peer, d, OutState.FAILED, "Lost contact with ${peer.label}")
                continue
            }
            missed = 0
            apply(r)?.let { return finishFromReply(peer, d, it) }
        }
    }

    /** Non-null when the reply ends the transaction. */
    private fun apply(reply: JSONObject): Pair<OutState, String>? {
        val reason = reply.optString("reason")
        return when (reply.optString("state")) {
            "awaiting" -> { set(OutState.AWAITING_APPROVAL, "Waiting for an answer on the other device…"); null }
            "preparing" -> { set(OutState.PREPARING, "Starting playback…"); null }
            "playing", "completed" -> OutState.TRANSFERRED to "Playback transferred"
            "declined" -> OutState.DECLINED to "Declined on the other device"
            "busy" -> OutState.FAILED to reason.ifBlank { "The other device is busy" }
            "cancelled" -> OutState.CANCELLED to "Cancelled"
            "timed_out" -> OutState.TIMED_OUT to "No answer on the other device"
            "failed" -> OutState.FAILED to reason.ifBlank { "The other device couldn't play it" }
            else -> OutState.FAILED to "The other device sent an unexpected answer"
        }
    }

    private suspend fun finishFromReply(peer: Peer, d: Descriptor, end: Pair<OutState, String>): OutState {
        if (end.first == OutState.TRANSFERRED) {
            // Only now, with the receiver really playing, does this side stop.
            playback.pauseForHandoff()
            set(OutState.TRANSFERRED, end.second)
            for (attempt in 0 until 3) {
                if (transport.send(peer, "complete", JSONObject().put("t", "complete").put("tx", d.txId)) != null) break
                delay(400L * (attempt + 1))
            }
            return OutState.TRANSFERRED
        }
        return set(end.first, end.second)
    }

    private suspend fun finish(peer: Peer, d: Descriptor, end: OutState, message: String): OutState {
        // Tell the receiver, so a prompt or a player still starting goes away. Best effort.
        val reply = transport.send(peer, "cancel", JSONObject().put("t", "cancel").put("tx", d.txId))
        // Too late to cancel: the other device already plays it. Then it moved, and this side pauses,
        // rather than both playing on.
        when (reply?.optString("state")) {
            "playing", "completed" -> return finishFromReply(peer, d, OutState.TRANSFERRED to "Playback transferred")
        }
        return set(end, message)
    }

    private fun set(s: OutState, message: String): OutState {
        state = s
        onState(s, message)
        return s
    }

}
