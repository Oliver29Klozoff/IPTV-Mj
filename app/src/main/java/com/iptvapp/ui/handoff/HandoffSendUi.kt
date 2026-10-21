package com.iptvapp.ui.handoff

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.handoff.Descriptor
import com.iptvapp.handoff.Handoff
import com.iptvapp.handoff.HandoffCrypto
import com.iptvapp.handoff.HandoffDiscovery
import com.iptvapp.handoff.HandoffSender
import com.iptvapp.handoff.OutState
import com.iptvapp.handoff.Peer
import com.iptvapp.handoff.SourcePlayback
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the player offers to hand over. */
interface HandoffSource : SourcePlayback {
    /** Why this can't be handed over (a recording, a replay…), or null when it can. */
    fun unsupportedReason(): String?
    /** The descriptor for [peer] (its provider tag is keyed to that pairing); null if it can't be made. */
    suspend fun descriptor(peer: Peer, txId: String): Descriptor?
}

/**
 * "Send to device" from the player: pick a paired device (checked for an answer first), then watch
 * the transfer with a Cancel button. This device keeps playing until the other really plays.
 */
object HandoffSendUi {

    fun show(activity: AppCompatActivity, source: HandoffSource) {
        source.unsupportedReason()?.let {
            AlertDialog.Builder(activity).setTitle("Can't send this").setMessage(it).setPositiveButton("OK", null).show()
            return
        }
        if (Handoff.outgoing != null) {
            Toast.makeText(activity, "Already sending — wait for it to finish", Toast.LENGTH_SHORT).show()
            return
        }
        val tv = Handoff.isTv(activity)
        val peers = Handoff.registry(activity).all().filter { it.isTv != tv }
        if (peers.isEmpty()) {
            val b = AlertDialog.Builder(activity).setTitle(if (tv) "No paired phone" else "No paired TV")
            if (tv) {
                b.setMessage("On your phone, open MKTV → Remote and pick this TV to pair it. Then Send works both ways.")
                    .setPositiveButton("OK", null)
            } else {
                b.setMessage("Pair this phone with your TV first: MKTV must be open on the TV.")
                    .setPositiveButton("Pair a TV") { _, _ -> activity.startActivity(Intent(activity, com.iptvapp.ui.remote.RemoteActivity::class.java)) }
                    .setNegativeButton("Cancel", null)
            }
            b.show()
            return
        }
        val looking = AlertDialog.Builder(activity).setTitle(if (tv) "Send to phone" else "Send to TV")
            .setMessage("Looking for your devices…").setNegativeButton("Cancel", null).show()
        activity.lifecycleScope.launch {
            if (!tv) withTimeoutOrNull(3_500L) { HandoffDiscovery.refresh(activity, 2_500L) }
            val fresh = Handoff.registry(activity).all().filter { it.isTv != tv }
            val available = fresh.map { p ->
                async { p to (withTimeoutOrNull(5_000L) { reachable(activity, p) } ?: false) }
            }.awaitAll()
            if (!looking.isShowing) return@launch
            looking.dismiss()
            val labels = available.map { (p, ok) ->
                "${p.label} — ${if (p.isTv) "TV" else "Phone"} · " +
                    if (ok) "Available" else if (p.isTv) "Not reachable (is MKTV open on it?)" else "Not reachable (open MKTV on the phone)"
            }
            AlertDialog.Builder(activity).setTitle(if (tv) "Continue on phone" else "Send to TV")
                .setItems(labels.toTypedArray()) { _, i -> send(activity, source, available[i].first) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private suspend fun reachable(activity: AppCompatActivity, p: Peer): Boolean {
        // A device without an id yet (paired before handoff) says hello first, which also checks it answers.
        if (p.deviceId.isEmpty()) return Handoff.hello(activity, p) != null
        return Handoff.reachable(activity, p)
    }

    private fun send(activity: AppCompatActivity, source: HandoffSource, chosen: Peer) {
        if (Handoff.outgoing != null) return
        val status = AlertDialog.Builder(activity).setTitle("Sending to ${chosen.label}")
            .setMessage("Connecting…").setCancelable(false)
            .setNegativeButton("Cancel", null).show()
        val sender = HandoffSender(Handoff.client(activity), source, onState = { s, msg ->
            activity.runOnUiThread { if (status.isShowing) status.setMessage(stepText(s, msg)) }
        })
        Handoff.outgoing = sender
        status.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { sender.cancel(); status.setMessage("Cancelling…") }
        val app = activity.applicationContext
        activity.lifecycleScope.launch {
            var activePeer: Peer? = null
            var activeTx: String? = null
            val result = try {
                // A peer still without an id learns it now; the descriptor's provider tag is keyed to this pairing.
                val peer = (if (chosen.deviceId.isEmpty()) Handoff.hello(activity, chosen) else chosen)
                    ?: return@launch done(activity, status, OutState.FAILED, "Couldn't reach ${chosen.label}")
                val d = source.descriptor(peer, HandoffCrypto.randomHex(16))
                    ?: return@launch done(activity, status, OutState.FAILED, "This can't be sent")
                activePeer = peer
                activeTx = d.txId
                sender.run(peer, d)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // This player went away mid-transfer (closed, or Up Next replaced it): the other
                // device must not go on to open something this one no longer plays.
                val p = activePeer
                val tx = activeTx
                if (p != null && tx != null && sender.state != OutState.TRANSFERRED) {
                    Handoff.runAsync { Handoff.client(app).send(p, "cancel", org.json.JSONObject().put("t", "cancel").put("tx", tx)) }
                }
                throw e
            } finally {
                Handoff.outgoing = null
            }
            done(activity, status, result, null)
        }
    }

    private suspend fun done(activity: AppCompatActivity, status: AlertDialog, result: OutState, message: String?) {
        Handoff.outgoing = null
        if (!status.isShowing) return
        status.getButton(AlertDialog.BUTTON_NEGATIVE).text = "OK"
        status.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { status.dismiss() }
        message?.let { status.setMessage(it) }
        if (result == OutState.TRANSFERRED) {
            delay(2_500)
            if (status.isShowing) status.dismiss()
        } else if (message == null && result == OutState.CANCELLED) {
            status.dismiss()
        }
    }

    private fun stepText(s: OutState, msg: String): String = when (s) {
        OutState.TRANSFERRED -> "Playback transferred. Paused here."
        OutState.FAILED, OutState.DECLINED, OutState.TIMED_OUT -> "$msg\n\nStill playing here."
        else -> msg
    }
}
