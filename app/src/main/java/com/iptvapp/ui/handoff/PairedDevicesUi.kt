package com.iptvapp.ui.handoff

import android.content.Intent
import android.text.format.DateUtils
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.handoff.Handoff
import com.iptvapp.handoff.Peer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Settings → Paired MKTV devices: who can send to this device and receive from it, whether each
 * answers now, and Forget (which also tells the other device to forget this one, and on a TV stops
 * that phone's remote control). Addresses aren't shown.
 */
object PairedDevicesUi {

    fun show(activity: AppCompatActivity) {
        val peers = Handoff.registry(activity).all().sortedBy { it.label.lowercase() }
        val tv = Handoff.isTv(activity)
        if (peers.isEmpty()) {
            val b = AlertDialog.Builder(activity).setTitle("Paired MKTV devices")
            if (tv) {
                b.setMessage("No phones yet. On your phone, open MKTV → Remote and pick this TV; confirm the number here.")
                    .setPositiveButton("OK", null)
            } else {
                b.setMessage("No TVs yet. Open MKTV on your TV, then pair from Remote on this phone.")
                    .setPositiveButton("Pair a TV") { _, _ -> activity.startActivity(Intent(activity, com.iptvapp.ui.remote.RemoteActivity::class.java)) }
                    .setNegativeButton("Close", null)
            }
            b.show()
            return
        }
        val checking = AlertDialog.Builder(activity).setTitle("Paired MKTV devices").setMessage("Checking which devices answer…")
            .setNegativeButton("Close", null).show()
        activity.lifecycleScope.launch {
            val status = peers.map { p ->
                async {
                    p to (withTimeoutOrNull(4_000L) { if (p.deviceId.isEmpty()) Handoff.hello(activity, p) != null else Handoff.reachable(activity, p) } ?: false)
                }
            }.awaitAll()
            if (!checking.isShowing) return@launch
            checking.dismiss()
            val now = System.currentTimeMillis()
            val labels = status.map { (p, ok) ->
                val kind = if (p.isTv) "TV" else "Phone"
                val seen = when {
                    ok -> "Available now"
                    p.lastSeenMs > 0 -> "Last seen " + DateUtils.getRelativeTimeSpanString(p.lastSeenMs, now, DateUtils.MINUTE_IN_MILLIS)
                    else -> "Not reachable"
                }
                val ho = if (p.handoff || ok) "Send ✓" else "Send: update MKTV there"
                "${p.label}\n$kind · Paired · $seen · $ho"
            }
            val b = AlertDialog.Builder(activity).setTitle("Paired MKTV devices")
                .setItems(labels.toTypedArray()) { _, i -> confirmForget(activity, status[i].first) }
                .setNegativeButton("Close", null)
            if (!tv) b.setNeutralButton("Pair a TV") { _, _ -> activity.startActivity(Intent(activity, com.iptvapp.ui.remote.RemoteActivity::class.java)) }
            b.show()
        }
    }

    private fun confirmForget(activity: AppCompatActivity, p: Peer) {
        AlertDialog.Builder(activity).setTitle("Forget ${p.label}?")
            .setMessage(
                "It can no longer send to this device or receive from it" +
                    (if (Handoff.isTv(activity)) ", and it stops working as a remote for this TV." else ".") +
                    " Pair again to bring it back."
            )
            .setPositiveButton("Forget") { _, _ ->
                Handoff.forget(activity, p)
                show(activity)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
