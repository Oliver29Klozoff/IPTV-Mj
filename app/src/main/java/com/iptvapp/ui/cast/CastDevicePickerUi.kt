package com.iptvapp.ui.cast

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.iptvapp.R
import com.iptvapp.handoff.Handoff
import com.iptvapp.handoff.Peer
import com.iptvapp.remote.RemoteControlServer
import com.iptvapp.sync.LocalSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Unified Cast & Connect Bottom Sheet (4C).
 * Bridges Android TVs (via RemoteControlServer/Handoff) and Samsung/LG Smart TVs (via MKTVCast/CastRelayManager).
 * Also surfaces instant TV playback controls and local LAN sync.
 */
object CastDevicePickerUi {

    fun show(
        activity: AppCompatActivity,
        onSamsungLgCast: (() -> Unit)? = null,
        onPlayToDevice: ((Peer) -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_cast_devices, null)
        dialog.setContentView(view)

        val container = view.findViewById<LinearLayout>(R.id.deviceListContainer)
        val tvStatus = view.findViewById<TextView>(R.id.tvDiscoveryStatus)
        val btnSamsungLg = view.findViewById<View>(R.id.btnCastSamsungLg)
        val btnPairNewTv = view.findViewById<View>(R.id.btnPairNewTv)

        tvStatus?.text = "Checking devices on your home network..."

        btnSamsungLg?.setOnClickListener {
            dialog.dismiss()
            if (onSamsungLgCast != null) {
                onSamsungLgCast()
            } else {
                activity.startActivity(Intent(activity, com.iptvapp.ui.player.CastQrScanActivity::class.java))
            }
        }

        btnPairNewTv?.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, com.iptvapp.ui.remote.RemoteActivity::class.java))
        }

        activity.lifecycleScope.launch {
            val peers = Handoff.registry(activity).all().filter { it.isTv }
            val checked = peers.map { p ->
                async {
                    val reachable = withTimeoutOrNull(3000L) {
                        if (p.deviceId.isEmpty()) Handoff.hello(activity, p) != null
                        else Handoff.reachable(activity, p)
                    } ?: false
                    p to reachable
                }
            }.awaitAll()

            tvStatus?.visibility = if (checked.isEmpty()) View.VISIBLE else View.GONE
            if (checked.isEmpty()) {
                tvStatus?.text = "No paired Android TVs found. Pair a TV below or cast to Samsung/LG."
            }

            container?.removeAllViews()
            for ((peer, reachable) in checked) {
                val itemView = LayoutInflater.from(activity).inflate(R.layout.item_cast_device, container, false)
                val tvName = itemView.findViewById<TextView>(R.id.tvDeviceName)
                val tvSubtitle = itemView.findViewById<TextView>(R.id.tvDeviceStatus)
                val dot = itemView.findViewById<View>(R.id.viewStatusDot)

                tvName.text = peer.label
                tvSubtitle.text = if (reachable) "Online • Ready to control" else "Offline • Open MKTV on the TV"
                dot.setBackgroundColor(if (reachable) Color.parseColor("#00E676") else Color.parseColor("#757575"))

                itemView.setOnClickListener {
                    dialog.dismiss()
                    if (onPlayToDevice != null) {
                        AlertDialog.Builder(activity)
                            .setTitle(peer.label)
                            .setItems(arrayOf("▶  Play on TV", "🎮  TV Remote & Volume", "🔄  Sync Favorites & Settings")) { _, which ->
                                when (which) {
                                    0 -> onPlayToDevice(peer)
                                    1 -> showTvControllerDialog(activity, peer)
                                    2 -> syncToPeer(activity, peer)
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        showTvControllerDialog(activity, peer)
                    }
                }

                container?.addView(itemView)
            }
        }

        dialog.show()
    }

    fun showTvControllerDialog(activity: AppCompatActivity, peer: Peer) {
        val dialog = AlertDialog.Builder(activity).create()
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_tv_mini_controller, null)
        dialog.setView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvControllerTitle)
        val tvSub = view.findViewById<TextView>(R.id.tvControllerSubtitle)
        val btnPlayPause = view.findViewById<Button>(R.id.btnCtrlPlayPause)
        val btnChUp = view.findViewById<Button>(R.id.btnCtrlChUp)
        val btnChDown = view.findViewById<Button>(R.id.btnCtrlChDown)
        val btnVolUp = view.findViewById<Button>(R.id.btnCtrlVolUp)
        val btnVolDown = view.findViewById<Button>(R.id.btnCtrlVolDown)
        val btnOpenRemote = view.findViewById<Button>(R.id.btnCtrlOpenRemote)
        val btnSync = view.findViewById<Button>(R.id.btnCtrlSync)

        tvTitle.text = peer.label
        tvSub.text = "Connected over home Wi-Fi"

        fun sendCmd(cmdJson: JSONObject) {
            val host = peer.lastHost
            if (host.isNullOrBlank()) {
                Toast.makeText(activity, "TV address unknown - open Remote first", Toast.LENGTH_SHORT).show()
                return
            }
            val port = if (peer.lastPort > 0) peer.lastPort else RemoteControlServer.PORT
            activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val now = System.currentTimeMillis()
                    val body = cmdJson.toString()
                    val sig = RemoteControlServer.sign(peer.secret, "POST", "/command", now, body)
                    val conn = (URL("http://${RemoteControlServer.hostForUrl(host)}:$port/command").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 3000
                        readTimeout = 3000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("X-MKTV-Time", now.toString())
                        setRequestProperty("X-MKTV-Sig", sig)
                    }
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        // The TV says why (volume on a fixed-volume box, MKTV not on screen…).
                        val err = try {
                            JSONObject(conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()).optString("error")
                        } catch (_: Exception) { "" }
                        val message = when {
                            code == 401 && err == "clock" -> "This phone's clock and ${peer.label}'s differ - set both to automatic time"
                            code == 401 -> "${peer.label} doesn't know this phone any more - pair again in Remote"
                            err.isNotBlank() -> err
                            else -> "${peer.label} couldn't do that (HTTP $code)"
                        }
                        withContext(Dispatchers.Main) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Can't reach ${peer.label}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnPlayPause.setOnClickListener { sendCmd(JSONObject().put("cmd", "key").put("key", "playpause")) }
        btnChUp.setOnClickListener { sendCmd(JSONObject().put("cmd", "key").put("key", "chup")) }
        btnChDown.setOnClickListener { sendCmd(JSONObject().put("cmd", "key").put("key", "chdown")) }
        btnVolUp.setOnClickListener { sendCmd(JSONObject().put("cmd", "volume").put("dir", "up")) }
        btnVolDown.setOnClickListener { sendCmd(JSONObject().put("cmd", "volume").put("dir", "down")) }

        btnSync.setOnClickListener {
            dialog.dismiss()
            syncToPeer(activity, peer)
        }

        btnOpenRemote.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(com.iptvapp.ui.remote.RemoteActivity.forPeer(activity, peer))
        }

        dialog.show()
    }

    fun syncToPeer(activity: AppCompatActivity, peer: Peer) {
        val host = peer.lastHost
        if (host.isNullOrBlank()) {
            Toast.makeText(activity, "TV address unknown - connect via Remote first", Toast.LENGTH_SHORT).show()
            return
        }
        val port = if (peer.lastPort > 0) peer.lastPort else RemoteControlServer.PORT
        val progress = AlertDialog.Builder(activity)
            .setTitle("Syncing to " + peer.label)
            .setMessage("Pushing favorites & settings over Wi-Fi...")
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val res = LocalSyncManager.get(activity)
                .pushToTv(host = host, port = port, token = peer.secret)
            progress.dismiss()
            Toast.makeText(activity, res.message, Toast.LENGTH_LONG).show()
        }
    }
}
