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
import com.iptvapp.databinding.ActivityRemoteBinding
import com.iptvapp.remote.RemoteControlServer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    @Inject lateinit var repository: com.iptvapp.data.repository.XtreamRepository

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
    private val sendLock = kotlinx.coroutines.sync.Mutex()

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
            // Typing a search in portrait: the buttons step aside so the results have room above
            // the keyboard (landscape has them side by side already).
            val typing = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            binding.remotePad.root.visibility = if (typing && portrait) View.GONE else View.VISIBLE
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
            // Main provider and enabled other providers, like Home's favorites; searches go through
            // the repository, which turns typed text into a safe prefix query.
            val channels = withContext(Dispatchers.IO) {
                try {
                    if (query.isEmpty()) {
                        db.channelDao().getFavoriteChannels().first().map { Pick(it.customNum?.let { n -> "$n · ${it.name}" } ?: it.name, it.name) } +
                            repository.getMergedAllFavorites().first().filter { !it.isHidden }.map { Pick("${it.name} · ${it.serverNickname}", it.name) }
                    } else {
                        val enabled = prefs.enabledExtraServerIndices.first()
                        repository.searchChannels(query).first().filter { !it.isHidden }.map { Pick(it.customNum?.let { n -> "$n · ${it.name}" } ?: it.name, it.name) } +
                            repository.searchMergedChannels(query).first().filter { !it.isHidden && it.serverIndex in enabled }
                                .map { Pick("${it.name} · ${it.serverNickname}", it.name) }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    emptyList()
                }
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

    /** A channel in the list: what's shown, and the name the TV looks it up by. */
    private data class Pick(val label: String, val name: String)

    private class ChannelAdapter(private val onPick: (Pick) -> Unit) : RecyclerView.Adapter<ChannelAdapter.VH>() {
        private var items: List<Pick> = emptyList()
        class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        fun submit(list: List<Pick>) {
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
            val pick = items[position]
            holder.text.text = pick.label
            holder.text.setOnClickListener { onPick(pick) }
        }

        override fun getItemCount() = items.size
    }

    // ── Talking to the TV ───────────────────────────────────────────────────

    private class Reply(val code: Int, val json: JSONObject?)

    /** How far the TV's clock is ahead of this phone's, learned when it turns down a signature as
     * out of date — so a phone whose clock is a few minutes off still works. */
    @Volatile private var clockOffsetMs = 0L

    /** One request to the paired TV (or [tv], unsigned, for pairing); null when it couldn't be
     * reached. Requests to the paired TV are signed with its token, never send it. */
    private suspend fun request(path: String, body: JSONObject?, tv: Tv? = null): Reply? = withContext(Dispatchers.IO) {
        val first = requestOnce(path, body, tv)
        if (tv == null && first?.code == 401 && first.json?.optString("error") == "clock") {
            val serverTime = first.json.optLong("serverTime", 0L)
            if (serverTime > 0) {
                clockOffsetMs = serverTime - System.currentTimeMillis()
                return@withContext requestOnce(path, body, null)
            }
        }
        first
    }

    private fun requestOnce(path: String, body: JSONObject?, tv: Tv?): Reply? {
        val host = tv?.host ?: pairedHost ?: return null
        val port = tv?.port ?: pairedPort
        return try {
            val method = if (body != null) "POST" else "GET"
            val text = body?.toString().orEmpty()
            val conn = URL("http://${hostForUrl(host)}:$port$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            conn.requestMethod = method
            val key = token
            if (tv == null && key != null) {
                val at = System.currentTimeMillis() + clockOffsetMs
                conn.setRequestProperty("X-MKTV-Time", at.toString())
                conn.setRequestProperty("X-MKTV-Sig", RemoteControlServer.sign(key, method, path, at, text))
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val reply = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
            conn.disconnect()
            Reply(code, reply?.let { runCatching { JSONObject(it) }.getOrNull() })
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
            // One at a time, in the order tapped — ▼ then OK must reach the TV as ▼ then OK.
            val reply = sendLock.withLock { request("/command", command) }
            when {
                reply == null -> setStatus("Can't reach ${pairedName ?: "the TV"} — is MKTV open on it?")
                // Kept, not deleted: something answering at the TV's address can't make the phone
                // forget its pairing. Pairing again replaces it.
                reply.code == 401 && reply.json?.optString("error") != "clock" -> {
                    setStatus("The TV doesn't know this phone — pair again")
                    pickTv()
                }
                reply.code == 401 -> setStatus("The phone's and the TV's clocks are too far apart — check both")
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
                reply.code == 401 && reply.json?.optString("error") != "clock" -> "$name doesn't know this phone — tap PICK TV to pair again"
                reply.code == 401 -> "The phone's and the TV's clocks are too far apart — check both"
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
                val why = reply?.json?.optString("error")?.takeIf { it.isNotBlank() } ?: "Can't reach ${tv.name} — is MKTV open on it?"
                Toast.makeText(this@RemoteActivity, why, Toast.LENGTH_LONG).show()
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
