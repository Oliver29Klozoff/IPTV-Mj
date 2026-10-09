package com.iptvapp.ui.remote

import android.app.AlertDialog
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.databinding.ActivityRemoteBinding
import com.iptvapp.remote.RemoteControlServer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * Phone remote (v7.18): drives MKTV on the TV (the Shield) over the home Wi-Fi. The TV plays from
 * its own provider — this only presses its buttons and tells it which channel to put on.
 *
 * Finds the TV by mDNS (RemoteControlServer.SERVICE_TYPE), or by an address typed in when the
 * network blocks discovery. Pairs once with the code the TV shows; the token it gets back is kept
 * in SharedPreferences "remote_phone" with the TV's name and last address.
 */
@AndroidEntryPoint
class RemoteActivity : AppCompatActivity() {

    @Inject lateinit var db: IptvDatabase
    @Inject lateinit var prefs: PreferencesManager

    private lateinit var binding: ActivityRemoteBinding
    private val store by lazy { getSharedPreferences("remote_phone", Context.MODE_PRIVATE) }

    private data class Tv(val name: String, val host: String, val port: Int)

    /** TVs found on the network this visit, by service name. */
    private val found = linkedMapOf<String, Tv>()
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var searchJob: Job? = null
    private var pairPrompted = false

    private val pairedName get() = store.getString("name", null)
    private val pairedHost get() = store.getString("host", null)
    private val pairedPort get() = store.getInt("port", RemoteControlServer.PORT)
    private val token get() = store.getString("token", null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                    androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val header = binding.remoteHeader
        header.btnRemoteBack.setOnClickListener { finish() }
        header.btnRemoteChangeTv.setOnClickListener { pickTv() }
        lifecycleScope.launch { header.tvRemoteTitle.setTextColor(com.iptvapp.util.RackAccent.load(prefs).start) }

        val pad = binding.remotePad
        mapOf(
            pad.btnKeyUp to "up", pad.btnKeyDown to "down", pad.btnKeyLeft to "left", pad.btnKeyRight to "right",
            pad.btnKeyOk to "ok", pad.btnKeyBack to "back", pad.btnKeyChUp to "chup", pad.btnKeyChDown to "chdown",
            pad.btnKeyPlayPause to "playpause", pad.btnKeyLast to "last", pad.btnKeyGuide to "guide"
        ).forEach { (view, key) ->
            view.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                send(JSONObject().put("cmd", "key").put("key", key))
            }
        }

        val list = binding.remoteList
        list.rvRemoteChannels.layoutManager = LinearLayoutManager(this)
        list.rvRemoteChannels.adapter = ChannelAdapter { ch ->
            send(JSONObject().put("cmd", "tune").put("name", ch.name)) { ok ->
                if (ok) Toast.makeText(this, "Putting on ${ch.name}", Toast.LENGTH_SHORT).show()
            }
        }
        list.etRemoteSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { showChannels(s?.toString().orEmpty().trim()) }
        })
        showChannels("")

        // What the TV is showing, while this screen is up.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshStatus()
                    delay(5_000)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onStop() {
        super.onStop()
        stopDiscovery()
    }

    // ── Channels ────────────────────────────────────────────────────────────

    private fun showChannels(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (query.isNotEmpty()) delay(250)
            val channels = withContext(Dispatchers.IO) {
                if (query.isEmpty()) db.channelDao().getFavoriteChannels().first()
                else db.channelDao().searchChannels(query).first().filter { !it.isHidden }
            }
            val list = binding.remoteList
            list.tvRemoteListLabel.text = when {
                query.isNotEmpty() && channels.isEmpty() -> "NO CHANNELS MATCH"
                query.isNotEmpty() -> "CHANNELS"
                channels.isEmpty() -> "NO FAVORITES YET — SEARCH ABOVE"
                else -> "FAVORITES"
            }
            (list.rvRemoteChannels.adapter as ChannelAdapter).submit(channels)
        }
    }

    private class ChannelAdapter(private val onPick: (ChannelEntity) -> Unit) : RecyclerView.Adapter<ChannelAdapter.VH>() {
        private var items: List<ChannelEntity> = emptyList()
        class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        fun submit(list: List<ChannelEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14f, ctx.resources.displayMetrics).toInt()
            val tv = TextView(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(pad, pad, pad, pad)
                setBackgroundResource(R.drawable.rack_row_bg)
                isFocusable = true
                isClickable = true
                setTextColor(ctx.getColor(R.color.rack_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = ResourcesCompat.getFont(ctx, R.font.barlow)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = items[position]
            holder.text.text = ch.customNum?.let { "$it · ${ch.name}" } ?: ch.name
            holder.text.setOnClickListener { onPick(ch) }
        }

        override fun getItemCount() = items.size
    }

    // ── Talking to the TV ───────────────────────────────────────────────────

    private class Reply(val code: Int, val json: JSONObject?)

    /** One request to the paired TV (or [tv]); null when it couldn't be reached. */
    private suspend fun request(path: String, body: JSONObject?, tv: Tv? = null): Reply? = withContext(Dispatchers.IO) {
        val host = tv?.host ?: pairedHost ?: return@withContext null
        val port = tv?.port ?: pairedPort
        try {
            val conn = URL("http://${hostForUrl(host)}:$port$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            conn.requestMethod = if (body != null) "POST" else "GET"
            token?.let { conn.setRequestProperty("X-MKTV-Token", it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
            conn.disconnect()
            Reply(code, text?.let { runCatching { JSONObject(it) }.getOrNull() })
        } catch (_: Exception) {
            null
        }
    }

    /** IPv6 literals need brackets in a URL. */
    private fun hostForUrl(host: String) = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private fun send(command: JSONObject, done: ((Boolean) -> Unit)? = null) {
        if (token == null || pairedHost == null) {
            Toast.makeText(this, "Pick your TV first", Toast.LENGTH_SHORT).show()
            pickTv()
            return
        }
        lifecycleScope.launch {
            val reply = request("/command", command)
            when {
                reply == null -> setStatus("Can't reach ${pairedName ?: "the TV"} — is MKTV open on it?")
                reply.code == 401 -> {
                    store.edit().remove("token").apply()
                    setStatus("The TV forgot this phone — pair again")
                    pickTv()
                }
                reply.code !in 200..299 -> Toast.makeText(this@RemoteActivity, reply.json?.optString("error") ?: "The TV said no", Toast.LENGTH_SHORT).show()
            }
            done?.invoke(reply?.code in 200..299)
        }
    }

    private suspend fun refreshStatus() {
        val name = pairedName
        if (token == null || pairedHost == null) {
            setStatus(if (found.isEmpty()) "Looking for MKTV on your Wi-Fi… open MKTV on the TV" else "Tap PICK TV to pair with your TV")
            return
        }
        val reply = request("/status", null)
        setStatus(
            when {
                reply == null -> "Can't reach $name — is MKTV open on it?"
                reply.code == 401 -> "$name forgot this phone — tap PICK TV to pair again"
                else -> reply.json?.optString("nowPlaying").orEmpty()
                    .let { if (it.isBlank()) "Connected to $name" else "$name · $it" }
            }
        )
    }

    private fun setStatus(text: String) {
        binding.remoteHeader.tvRemoteStatus.text = text
    }

    // ── Finding and pairing ─────────────────────────────────────────────────

    private fun startDiscovery() {
        val manager = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = manager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                runOnUiThread { resolveQueue.addLast(info); resolveNext() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                runOnUiThread { found.remove(info.serviceName) }
            }
        }
        discovery = listener
        try {
            manager.discoverServices(RemoteControlServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            discovery = null
        }
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        discovery = null
        resolveQueue.clear()
        resolving = false
    }

    /** One resolve at a time — the platform refuses a second while one is running. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || discovery == null) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val manager = nsd ?: return
        manager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                runOnUiThread { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                runOnUiThread {
                    resolving = false
                    if (host != null) onTvFound(Tv(info.serviceName, host, info.port))
                    resolveNext()
                }
            }
        })
    }

    private fun onTvFound(tv: Tv) {
        found[tv.name] = tv
        if (tv.name == pairedName) {
            // The paired TV, maybe at a new address since last time.
            if (tv.host != pairedHost || tv.port != pairedPort) {
                store.edit().putString("host", tv.host).putInt("port", tv.port).apply()
            }
            lifecycleScope.launch { refreshStatus() }
        } else if (token == null && !pairPrompted && !isFinishing) {
            pairPrompted = true
            pickTv()
        }
    }

    private fun pickTv() {
        val tvs = found.values.toList()
        val labels = tvs.map { if (it.name == pairedName && token != null) "${it.name} (paired)" else it.name } + "Type the TV's address…"
        AlertDialog.Builder(this)
            .setTitle(if (tvs.isEmpty()) "No TV found yet — open MKTV on the TV" else "Pick your TV")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < tvs.size) startPairing(tvs[which]) else askAddress()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askAddress() {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(pairedHost.orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle("TV's address")
            .setMessage("On the TV: Settings → Device Preferences → About → Status shows its IP address.")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val host = input.text.toString().trim()
                if (host.isNotEmpty()) startPairing(Tv(host, host, RemoteControlServer.PORT))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startPairing(tv: Tv) {
        lifecycleScope.launch {
            val reply = request("/pair/start", JSONObject(), tv)
            if (reply == null || reply.code !in 200..299) {
                Toast.makeText(this@RemoteActivity, "Can't reach ${tv.name} — is MKTV open on it?", Toast.LENGTH_LONG).show()
                return@launch
            }
            askCode(tv)
        }
    }

    private fun askCode(tv: Tv) {
        val input = EditText(this).apply {
            hint = "4-digit code"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Enter the code on the TV")
            .setView(input)
            .setPositiveButton("Pair") { _, _ ->
                val code = input.text.toString().trim()
                lifecycleScope.launch {
                    val reply = request("/pair", JSONObject().put("code", code), tv)
                    val newToken = reply?.json?.optString("token").orEmpty()
                    if (reply == null || reply.code !in 200..299 || newToken.isEmpty()) {
                        Toast.makeText(this@RemoteActivity, reply?.json?.optString("error") ?: "Can't reach ${tv.name}", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    // A TV typed in by address is known by its own name from here on, so discovery
                    // can find it again if its address changes.
                    val name = reply.json?.optString("name")?.takeIf { it.isNotBlank() && tv.name == tv.host } ?: tv.name
                    store.edit().putString("name", name).putString("host", tv.host).putInt("port", tv.port)
                        .putString("token", newToken).apply()
                    Toast.makeText(this@RemoteActivity, "Paired with $name", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
