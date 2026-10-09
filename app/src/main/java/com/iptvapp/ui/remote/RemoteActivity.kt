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
        pad.remoteDpad.onPress = { key -> send(JSONObject().put("cmd", "key").put("key", key)) }
        mapOf(
            pad.btnKeyBack to "back", pad.btnKeyChUp to "chup", pad.btnKeyChDown to "chdown",
            pad.btnKeyPlayPause to "playpause", pad.btnKeyLast to "last", pad.btnKeyGuide to "guide"
        ).forEach { (view, key) ->
            view.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                send(JSONObject().put("cmd", "key").put("key", key))
            }
        }
        pad.btnKeyHome.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            send(JSONObject().put("cmd", "home"))
        }
        mapOf(pad.btnKeyVolUp to "up", pad.btnKeyVolDown to "down", pad.btnKeyMute to "mute").forEach { (view, dir) ->
            view.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                send(JSONObject().put("cmd", "volume").put("dir", dir)) { _, json ->
                    // The level the TV is now at, in the status line for a moment.
                    val level = json?.optInt("volume", -1) ?: -1
                    val max = json?.optInt("max", 0) ?: 0
                    if (level >= 0 && max > 0) {
                        setStatus(if (json?.optBoolean("muted") == true) "Muted" else "Volume $level of $max", holdMs = 3_000)
                    }
                }
            }
        }

        val list = binding.remoteList
        list.rvRemoteChannels.layoutManager = LinearLayoutManager(this)
        list.rvRemoteChannels.adapter = ChannelAdapter { ch ->
            send(JSONObject().put("cmd", "tune").put("name", ch.name)) { ok, _ ->
                if (ok) Toast.makeText(this, "Putting on ${ch.name}", Toast.LENGTH_SHORT).show()
            }
        }
        lifecycleScope.launch {
            (list.rvRemoteChannels.adapter as ChannelAdapter).accent = com.iptvapp.util.RackAccent.load(prefs).start
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
        // Shows end: the guide lines next to the channels are looked up again every minute.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(60_000)
                    showChannels(binding.remoteList.etRemoteSearch.text?.toString().orEmpty().trim(), immediate = true)
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

    private fun showChannels(query: String, immediate: Boolean = false) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (query.isNotEmpty() && !immediate) delay(250)
            // Main provider and enabled other providers, like Home's favorites; searches go through
            // the repository, which turns typed text into a safe prefix query.
            val channels = withContext(Dispatchers.IO) {
                try {
                    val picks = if (query.isEmpty()) {
                        db.channelDao().getFavoriteChannels().first().map { Pick(it.customNum?.let { n -> "$n · ${it.name}" } ?: it.name, it.name, -1, it.streamId) } +
                            repository.getMergedAllFavorites().first().filter { !it.isHidden }
                                .map { Pick("${it.name} · ${it.serverNickname}", it.name, it.serverIndex, it.streamId) }
                    } else {
                        val enabled = prefs.enabledExtraServerIndices.first()
                        repository.searchChannels(query).first().filter { !it.isHidden }
                            .map { Pick(it.customNum?.let { n -> "$n · ${it.name}" } ?: it.name, it.name, -1, it.streamId) } +
                            repository.searchMergedChannels(query).first().filter { !it.isHidden && it.serverIndex in enabled }
                                .map { Pick("${it.name} · ${it.serverNickname}", it.name, it.serverIndex, it.streamId) }
                    }
                    withGuide(picks)
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

    /** A channel in the list: what's shown, the name the TV looks it up by, and its guide from
     * this phone (what's on now, how far in 0..100, what's next). */
    private data class Pick(
        val label: String,
        val name: String,
        val serverIndex: Int,
        val streamId: Int,
        val now: String? = null,
        val progress: Int = -1,
        val next: String? = null
    )

    /** [picks] with what's on now and next from this phone's guide (main and other providers). */
    private suspend fun withGuide(picks: List<Pick>): List<Pick> {
        if (picks.isEmpty()) return picks
        fun ms(t: Long) = if (t < 100_000_000_000L) t * 1000L else t
        val now = System.currentTimeMillis()
        // Chunked: SQLite allows only so many values in one IN (…).
        val rows = picks.map { "${it.serverIndex}:${it.streamId}" }.distinct().chunked(400).flatMap { keys ->
            db.epgDao().getEpgForServerStreamKeys(keys).first()
        }.filter { ms(it.stopTimestamp) > now }.groupBy { it.serverIndex to it.streamId }
        val time = java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault())
        return picks.map { pick ->
            val shows = rows[pick.serverIndex to pick.streamId]?.sortedBy { ms(it.startTimestamp) } ?: return@map pick
            val current = shows.firstOrNull { ms(it.startTimestamp) <= now }
            val upcoming = shows.firstOrNull { ms(it.startTimestamp) > now }
            if (current == null && upcoming == null) return@map pick
            val start = current?.let { ms(it.startTimestamp) }
            val stop = current?.let { ms(it.stopTimestamp) }
            val left = stop?.let { ((it - now) / 60_000L).coerceAtLeast(0) }
            pick.copy(
                now = current?.let { "${it.title.trim()} · ${if (left!! < 1) "ending" else "$left min left"}" },
                progress = if (start != null && stop != null && stop > start) ((now - start) * 100 / (stop - start)).toInt().coerceIn(0, 100) else -1,
                next = upcoming?.let { "Next ${time.format(java.util.Date(ms(it.startTimestamp)))} · ${it.title.trim()}" }
            )
        }
    }

    private class ChannelAdapter(private val onPick: (Pick) -> Unit) : RecyclerView.Adapter<ChannelAdapter.VH>() {
        private var items: List<Pick> = emptyList()
        var accent: Int = 0xFF00E5FF.toInt()
            set(value) { field = value; notifyDataSetChanged() }

        class VH(val row: android.widget.LinearLayout, val name: TextView, val now: TextView, val bar: android.widget.ProgressBar, val next: TextView) :
            RecyclerView.ViewHolder(row)

        fun submit(list: List<Pick>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density
            fun line(sizeSp: Float, color: Int, font: Int) = TextView(ctx).apply {
                setTextColor(ctx.getColor(color))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                typeface = ResourcesCompat.getFont(ctx, font)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val name = line(16f, R.color.rack_text, R.font.barlow)
            val now = line(13f, R.color.rack_text_secondary, R.font.barlow)
            val next = line(12f, R.color.rack_text_muted, R.font.barlow)
            val bar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (3 * dp).toInt()).apply {
                    topMargin = (4 * dp).toInt()
                    bottomMargin = (4 * dp).toInt()
                }
            }
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val pad = (14 * dp).toInt()
                setPadding(pad, (10 * dp).toInt(), pad, (10 * dp).toInt())
                setBackgroundResource(R.drawable.rack_row_bg)
                isFocusable = true
                isClickable = true
                addView(name)
                addView(now)
                addView(bar)
                addView(next)
            }
            return VH(row, name, now, bar, next)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val pick = items[position]
            holder.name.text = pick.label
            holder.now.text = pick.now ?: if (pick.next == null) "No guide for this channel" else ""
            holder.now.visibility = if (holder.now.text.isEmpty()) View.GONE else View.VISIBLE
            holder.bar.visibility = if (pick.progress >= 0) View.VISIBLE else View.GONE
            holder.bar.progress = pick.progress.coerceAtLeast(0)
            holder.bar.progressTintList = android.content.res.ColorStateList.valueOf(accent)
            holder.bar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF262626.toInt())
            holder.next.text = pick.next.orEmpty()
            holder.next.visibility = if (pick.next == null) View.GONE else View.VISIBLE
            holder.row.setOnClickListener { onPick(pick) }
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

    private fun send(command: JSONObject, done: ((Boolean, JSONObject?) -> Unit)? = null) {
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
            done?.invoke(reply?.code in 200..299, reply?.json)
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

    /** Until when a volume reading stays in the status line before the periodic check replaces it. */
    private var statusHeldUntil = 0L

    private fun setStatus(text: String, holdMs: Long = 0L) {
        val now = System.currentTimeMillis()
        if (holdMs == 0L && now < statusHeldUntil) return
        statusHeldUntil = if (holdMs > 0) now + holdMs else 0L
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

    /** Pairing (see RemoteControlServer's kdoc): commit to a key, get the TV's, reveal ours, then
     * both screens show the same number and the person picks Pair on the TV. The token is worked
     * out on each side from the key exchange and never sent. */
    private fun startPairing(tv: Tv) {
        lifecycleScope.launch {
            val keys = withContext(Dispatchers.Default) { RemoteControlServer.newKeyPair() }
            val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val commit = RemoteControlServer.commitment(keys.public, nonce)
            fun fail(reply: Reply?) {
                val why = reply?.json?.optString("error")?.takeIf { it.isNotBlank() } ?: "Can't reach ${tv.name} — is MKTV open on it?"
                Toast.makeText(this@RemoteActivity, why, Toast.LENGTH_LONG).show()
            }
            val started = request("/pair/start", JSONObject().put("commit", commit), tv)
            val tvKey = started?.json?.optString("pub")?.takeIf { started.code in 200..299 }
                ?.let { runCatching { RemoteControlServer.decodePublicKey(it) }.getOrNull() }
            if (tvKey == null) { fail(started); return@launch }
            val revealed = request(
                "/pair",
                JSONObject().put("pub", RemoteControlServer.encodePublicKey(keys.public))
                    .put("nonce", android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)),
                tv
            )
            if (revealed == null || revealed.code !in 200..299) { fail(revealed); return@launch }
            val newToken = runCatching { RemoteControlServer.deriveToken(keys.private, tvKey) }.getOrNull()
                ?: run { fail(null); return@launch }
            val number = RemoteControlServer.comparisonNumber(keys.public, tvKey, nonce)

            var waiting = true
            val dialog = AlertDialog.Builder(this@RemoteActivity)
                .setTitle("Confirm on the TV")
                .setMessage("If the TV shows $number, choose Pair on the TV with its remote.\n\nIf the numbers differ, choose Cancel there.")
                .setNegativeButton("Cancel") { _, _ -> waiting = false }
                .setOnCancelListener { waiting = false }
                .show()
            val deadline = System.currentTimeMillis() + RemoteControlServer.PAIRING_TTL_MS
            var state = "waiting"
            while (waiting && state == "waiting" && System.currentTimeMillis() < deadline) {
                delay(1_000)
                state = request("/pair/status", JSONObject().put("commit", commit), tv)?.json?.optString("state") ?: "waiting"
            }
            dialog.dismiss()
            if (!waiting) return@launch
            if (state != "paired") {
                Toast.makeText(
                    this@RemoteActivity,
                    if (state == "refused") "Pairing was cancelled on the TV" else "Pairing timed out — try again",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            // A TV typed in by address is known by its own name from here on, so discovery can find
            // it again if its address changes.
            val name = revealed.json?.optString("name")?.takeIf { it.isNotBlank() && tv.name == tv.host } ?: tv.name
            store.edit().putString("name", name).putString("host", tv.host).putInt("port", tv.port)
                .putString("token", newToken).apply()
            Toast.makeText(this@RemoteActivity, "Paired with $name", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }
}
