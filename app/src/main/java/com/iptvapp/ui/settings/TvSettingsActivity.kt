package com.iptvapp.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.iptvapp.AppConstants
import com.iptvapp.IptvApplication
import com.iptvapp.util.LogSanitizer
import com.iptvapp.util.isForceTvModeEnabled
import com.iptvapp.util.setForceTvModeEnabled
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ActivityTvSettingsBinding
import com.iptvapp.sync.SyncManager
import com.iptvapp.ui.login.LoginActivity
import com.iptvapp.update.UpdateChecker
import com.iptvapp.worker.EpgRefreshWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@Suppress("DEPRECATION")
@AndroidEntryPoint
class TvSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvSettingsBinding
    private lateinit var workManager: WorkManager
    private lateinit var adapter: TvSettingsAdapter

    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var db: IptvDatabase
    @Inject lateinit var syncManager: SyncManager
    @Inject lateinit var traktManager: com.iptvapp.trakt.TraktManager
    @Inject lateinit var repository: com.iptvapp.data.repository.XtreamRepository
    private var traktAuthJob: kotlinx.coroutines.Job? = null
    private var lastTraktSyncResult: com.iptvapp.trakt.TraktManager.SyncBackResult? = null

    private val settingsItems = mutableListOf<TvSettingItem>()
    private val sectionItems = linkedMapOf<String, MutableList<TvSettingItem>>()
    private var activeSection: String? = null
    private val accentPalette = listOf(
        "Blue" to "#008CFF", "Red" to "#FF3B30", "Green" to "#34C759", "Purple" to "#AF52DE",
        "Orange" to "#FF9500", "Pink" to "#FF2D55", "Teal" to "#5AC8FA"
    )
    private var currentAccentColorHex = "#008CFF"
    private val epgUrls = mutableListOf<String>()
    private val extraServers = mutableListOf<List<String>>()
    private var currentEpgWorkId: UUID? = null
    private var isEpgRefreshing = false

    private val createBackupLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) lifecycleScope.launch { writeBackupToUri(uri) } }

    private val openBackupLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) lifecycleScope.launch { restoreBackupFromUri(uri) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        lifecycleScope.launch { com.iptvapp.util.ThemeUtils.applyAmoledIfEnabled(binding.root, prefs) }
        workManager = WorkManager.getInstance(this)

        adapter = TvSettingsAdapter(settingsItems)
        binding.rvTvSettings.apply {
            layoutManager = LinearLayoutManager(this@TvSettingsActivity)
            adapter = this@TvSettingsActivity.adapter
        }

        lifecycleScope.launch {
            buildSettingsList()
            showSectionMenu()
        }
        observeEpgWork()
        binding.btnSettingsSearch.setOnClickListener { showSearchDialog() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_BACK)) {
            if (activeSection != null) {
                // Collapsing back to the section menu previously always focused the first row
                // (Stream), regardless of which section you were just inside — annoying when
                // Providers/Trakt/Sync etc. are several rows down, since every back-out meant
                // re-navigating from the top. Remember which section is closing and focus that
                // row instead.
                val closingSection = activeSection
                showSectionMenu(focusSection = closingSection)
            } else finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ─── Section drill-down ─────────────────────────────────────────────────
    // The settings list used to be one long flat scroll of ~30 rows across 8
    // sections. Splitting it into a short section menu (one row per section)
    // plus a per-section detail view keeps each screen short and scannable.

    private fun groupIntoSections() {
        sectionItems.clear()
        var current = "General"
        for (item in settingsItems) {
            if (item is TvSettingItem.Header) {
                current = item.title
                sectionItems.getOrPut(current) { mutableListOf() }
            } else {
                sectionItems.getOrPut(current) { mutableListOf() }.add(item)
            }
        }
    }

    private fun sectionIcon(title: String): String = when (title) {
        "Stream" -> "📡"
        "Display" -> "🎨"
        "EPG" -> "📺"
        "Updates" -> "⬇️"
        "Backup & Restore" -> "💾"
        "Providers" -> "🖥️"
        "Account" -> "👤"
        "Sync" -> "☁️"
        "Trakt" -> "🎬"
        "Subtitles" -> "💬"
        else -> "⚙️"
    }

    /** @param focusSection When set, focuses that section's own row in the menu instead of the
     * first row — used when collapsing back out of a section via Back/D-pad-left, so you land
     * back on the section you were just inside rather than always at the top of the list. */
    private fun showSectionMenu(focusFirst: Boolean = true, focusSection: String? = null) {
        activeSection = null
        settingsItems.clear()
        sectionItems.forEach { (title, items) ->
            settingsItems += TvSettingItem.Action("section_$title", "${sectionIcon(title)}   $title") {
                showSection(title)
            }
        }
        adapter.notifyDataSetChanged()
        binding.tvSettingsSectionTitle.text = "Settings"
        when {
            focusSection != null -> focusItemById("section_$focusSection")
            focusFirst -> focusFirstItem()
        }
    }

    private fun showSection(title: String, focusFirst: Boolean = true) {
        activeSection = title
        settingsItems.clear()
        settingsItems.addAll(visibleItemsForSection(title))
        adapter.notifyDataSetChanged()
        binding.tvSettingsSectionTitle.text = title
        if (focusFirst) focusFirstItem()
    }

    /** Filters out rows that fall under a collapsed SubHeader within a section. */
    private fun visibleItemsForSection(title: String): List<TvSettingItem> {
        val all = sectionItems[title].orEmpty()
        val result = mutableListOf<TvSettingItem>()
        var hiding = false
        for (item in all) {
            if (item is TvSettingItem.SubHeader) {
                hiding = !item.expanded
                result += item
                continue
            }
            if (!hiding) result += item
        }
        return result
    }

    /** Toggles a SubHeader's expanded state in place and re-renders the current section. */
    private fun toggleSubHeader(sectionTitle: String, subHeaderId: String) {
        val sub = sectionItems[sectionTitle].orEmpty()
            .filterIsInstance<TvSettingItem.SubHeader>()
            .firstOrNull { it.id == subHeaderId } ?: return
        sub.expanded = !sub.expanded
        showSection(sectionTitle, focusFirst = false)
    }

    // ─── Search ─────────────────────────────────────────────────────────────
    // One row per searchable setting (Toggle/Action/Info titles+subtitles), tagged with which
    // section it lives in and which SubHeader (if any) needs expanding to reveal it — built
    // fresh from sectionItems each time the dialog opens so it always reflects current state
    // (e.g. newly-added provider servers, current toggle values shown as subtitle context).
    private data class SearchEntry(val sectionTitle: String, val itemId: String, val label: String, val subHeaderId: String?)

    private fun buildSearchIndex(): List<SearchEntry> {
        val entries = mutableListOf<SearchEntry>()
        sectionItems.forEach { (sectionTitle, items) ->
            var currentSubHeader: String? = null
            items.forEach { item ->
                when (item) {
                    is TvSettingItem.SubHeader -> currentSubHeader = item.id
                    is TvSettingItem.Toggle -> entries += SearchEntry(sectionTitle, item.id, item.title, currentSubHeader)
                    is TvSettingItem.Action -> entries += SearchEntry(sectionTitle, item.id, item.title, currentSubHeader)
                    is TvSettingItem.Info -> if (item.text.isNotBlank()) entries += SearchEntry(sectionTitle, item.id, item.text, currentSubHeader)
                    else -> {}
                }
            }
        }
        return entries
    }

    private fun showSearchDialog() {
        if (sectionItems.isEmpty()) groupIntoSections()
        val index = buildSearchIndex()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val input = EditText(this).apply {
            hint = "Search settings…"
            setPadding(32, 16, 32, 16)
        }
        val resultsList = android.widget.ListView(this)
        container.addView(input)
        container.addView(resultsList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (400 * resources.displayMetrics.density).toInt()
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Find in Settings")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()

        var currentMatches: List<SearchEntry> = emptyList()
        fun renderMatches(query: String) {
            currentMatches = if (query.isBlank()) emptyList()
                else index.filter { it.label.contains(query, ignoreCase = true) }.take(30)
            resultsList.adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_list_item_1,
                currentMatches.map { "${it.label}  —  ${it.sectionTitle}" }
            )
        }
        resultsList.setOnItemClickListener { _, _, position, _ ->
            val match = currentMatches.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            jumpToSearchResult(match)
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { renderMatches(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        dialog.show()
    }

    /** Navigates to the section containing a search result, expanding its SubHeader if
     * collapsed, then scrolls to and briefly highlights the matching row. */
    private fun jumpToSearchResult(match: SearchEntry) {
        if (match.subHeaderId != null) {
            val sub = sectionItems[match.sectionTitle].orEmpty()
                .filterIsInstance<TvSettingItem.SubHeader>()
                .firstOrNull { it.id == match.subHeaderId }
            if (sub != null && !sub.expanded) sub.expanded = true
        }
        showSection(match.sectionTitle, focusFirst = false)
        binding.rvTvSettings.post {
            val idx = indexOfItem(match.itemId)
            if (idx < 0) return@post
            binding.rvTvSettings.scrollToPosition(idx)
            binding.rvTvSettings.post {
                val vh = binding.rvTvSettings.findViewHolderForAdapterPosition(idx)
                vh?.itemView?.let { view ->
                    view.requestFocus()
                    val original = view.background
                    view.setBackgroundColor(Color.parseColor("#1A008CFF"))
                    view.postDelayed({ view.background = original }, 900)
                }
            }
        }
    }

    private fun focusFirstItem() {
        binding.rvTvSettings.scrollToPosition(0)
        binding.rvTvSettings.post {
            for (i in 0 until binding.rvTvSettings.childCount) {
                val child = binding.rvTvSettings.getChildAt(i)
                if (child?.isFocusable == true) { child.requestFocus(); break }
            }
        }
    }

    private fun itemIdOf(item: TvSettingItem): String? = when (item) {
        is TvSettingItem.Toggle -> item.id
        is TvSettingItem.Action -> item.id
        is TvSettingItem.Info -> item.id
        is TvSettingItem.SubHeader -> item.id
        is TvSettingItem.Header -> null
    }

    /** Scrolls to and focuses the row matching [id], falling back to the first focusable row if
     * not found (e.g. a section that no longer exists). Uses a short RecyclerView-bind-then-
     * focus pattern like focusFirstItem(), since the target row's View may not exist yet the
     * instant this is called (RecyclerView lays out lazily after notifyDataSetChanged). */
    private fun focusItemById(id: String) {
        val pos = settingsItems.indexOfFirst { itemIdOf(it) == id }
        if (pos < 0) { focusFirstItem(); return }
        binding.rvTvSettings.scrollToPosition(pos)
        binding.rvTvSettings.post {
            val holder = binding.rvTvSettings.findViewHolderForAdapterPosition(pos)
            val target = holder?.itemView
            if (target?.isFocusable == true) target.requestFocus() else focusFirstItem()
        }
    }

    // ─── List building ────────────────────────────────────────────────────────

    private suspend fun buildSettingsList() {
        val creds        = prefs.credentials.first()
        val format       = prefs.preferredFormat.first()
        val preWarm      = prefs.preWarmOnFocus.first()
        val usaOnly      = prefs.usaOnlyChannels.first()
        val showMovies   = prefs.showMovies.first()
        val showSeries   = prefs.showSeries.first()
        epgUrls.clear(); epgUrls.addAll(prefs.getEpgUrls())
        val missingOnly  = prefs.epgRefreshMissingOnly.first()
        val autoHours    = prefs.epgAutoRefreshHours.first()
        val lastRefresh  = getLastRefreshText()
        extraServers.clear(); extraServers.addAll(prefs.getExtraServersWithNick())
        val syncEnabled  = prefs.syncEnabled.first()
        val syncSummary  = syncManager.getLastSyncSummary()
        // Auto Sync's own row previously gave no indication of when it actually last ran —
        // that lived only in the separate sync_status Info row further down, easy to miss/not
        // obviously connected to the toggle above it. Show it as the toggle's own subtitle too.
        val lastSyncMs   = prefs.lastSyncTime.first()
        val lastSyncText = if (lastSyncMs == 0L) "Never synced yet"
            else "Last synced: " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(lastSyncMs))
        val activeIdx    = prefs.activeServerIndex.first()
        val primaryNick  = prefs.serverNickname.first().ifEmpty { creds.username }
        val versionInfo  = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "v${pi.versionName}  (build ${pi.longVersionCode})"
        } catch (_: Exception) { "Unknown" }

        settingsItems.clear()

        // ── STREAM ──
        settingsItems += TvSettingItem.Header("Stream")
        settingsItems += TvSettingItem.Info("stream_server",
            "${creds.serverUrl.ifBlank { "Not set" }}  ·  ${creds.username.ifBlank { "—" }}")
        settingsItems += TvSettingItem.Toggle("stream_format", "Stream Format",
            subtitle = "TS for live channels, M3U8 as fallback",
            checked = (format == "ts"),
            valueOn = "TS", valueOff = "M3U8") { isTs ->
            lifecycleScope.launch { prefs.setPreferredFormat(if (isTs) "ts" else "m3u8") }
        }
        settingsItems += TvSettingItem.Toggle("stream_prewarm", "Pre-warm Streams on Focus",
            subtitle = "Starts resolving a stream URL when a channel tile receives focus, before you press play",
            checked = preWarm) { c -> lifecycleScope.launch { prefs.setPreWarmOnFocus(c) } }
        settingsItems += TvSettingItem.SubHeader("stream_sub_decoder", "Decoder") { toggleSubHeader("Stream", "stream_sub_decoder") }
        settingsItems += TvSettingItem.Info("stream_decoder_note",
            "Hardware (device) decoders are always used — this build has no software decoder fallback to prefer against.")
        settingsItems += TvSettingItem.Toggle("stream_tunneling", "Tunneled Playback",
            subtitle = "Lets the device handle audio/video sync in hardware — smoother on supported devices, but can cause glitches on some. Off by default.",
            checked = prefs.tunneledPlaybackEnabled.first()) { c -> lifecycleScope.launch { prefs.setTunneledPlaybackEnabled(c) } }
        settingsItems += TvSettingItem.Toggle("stream_dv7_fallback", "DV7 → HEVC Fallback",
            subtitle = "Maps Dolby Vision Profile 7 content to standard HEVC for devices without proper DV7 hardware support",
            checked = prefs.dv7FallbackEnabled.first()) { c -> lifecycleScope.launch { prefs.setDv7FallbackEnabled(c) } }
        settingsItems += TvSettingItem.Toggle("stream_extra_buffering", "Global Extra Buffering",
            subtitle = "Enable extra buffering for all providers by default — trades a slower start/seek for fewer mid-playback stalls. On by default.",
            checked = prefs.extraBufferingEnabled.first()) { c -> lifecycleScope.launch { prefs.setExtraBufferingEnabled(c) } }

        // ── DISPLAY ──
        currentAccentColorHex = prefs.accentColor.first()
        // ── MULTI-VIEW ──
        settingsItems += TvSettingItem.Header("Multi-View")
        settingsItems += TvSettingItem.Action("open_mosaic", "Open Mosaic (4/6-channel grid)") {
            startActivity(Intent(this, com.iptvapp.ui.mosaic.MosaicActivity::class.java))
        }

        settingsItems += TvSettingItem.Header("Display")
        settingsItems += TvSettingItem.Action("display_accent", "Accent Color",
            value = accentColorName(currentAccentColorHex)) { showAccentColorDialog() }
        settingsItems += TvSettingItem.Toggle("display_amoled", "AMOLED Black",
            subtitle = "Pure black backgrounds — saves battery and looks better on OLED TVs",
            checked = prefs.amoledBlack.first()) { c ->
            lifecycleScope.launch {
                prefs.setAmoledBlack(c)
                Toast.makeText(this@TvSettingsActivity, "Restart the app for AMOLED Black to fully apply", Toast.LENGTH_LONG).show()
            }
        }
        // Turning this OFF here is the only way back once a phone-formatted device (a car
        // head unit/box) has been switched into the TV interface via this same toggle on the
        // phone side — that phone Settings screen is unreachable once routing always resolves
        // to TvSettingsActivity, so this toggle has to exist on both sides.
        settingsItems += TvSettingItem.Toggle("display_force_tv", "Force TV Mode",
            subtitle = "This device is using the TV interface because Force TV Mode is on. Turn off to return to the phone interface (restarts the app).",
            checked = isForceTvModeEnabled()) { c ->
            setForceTvModeEnabled(c)
            val intent = Intent(this@TvSettingsActivity, com.iptvapp.ui.SplashActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
        settingsItems += TvSettingItem.Toggle("display_usa", "USA Channels Only",
            checked = usaOnly) { c -> lifecycleScope.launch { prefs.setUsaOnlyChannels(c) } }
        settingsItems += TvSettingItem.Toggle("display_english_only", "English Movies & Series Only",
            subtitle = "Experimental — only works if your provider labels movie/series categories with an EN/ENG/ENGLISH/US/USA tag.",
            checked = prefs.englishOnlyMovies.first()) { c -> lifecycleScope.launch { prefs.setEnglishOnlyMovies(c) } }
        // Phone Settings already had dedicated refresh buttons (btnRefreshMovies/
        // btnRefreshSeries) — TV had no way to re-fetch the VOD/series catalog at all, only EPG
        // program-guide data below, so a newly-added title on the provider's end could go
        // permanently missing on TV until the next full app data refresh. Attached directly to
        // the relevant toggle row (rather than a separate row) per request, as a small
        // independently-clickable button next to the ON/OFF value.
        settingsItems += TvSettingItem.Toggle("display_movies", "Show Movies Tab",
            checked = showMovies,
            actionLabel = "↻ Refresh",
            onAction = {
                setItemAction("display_movies", "Loading…", enabled = false)
                lifecycleScope.launch {
                    repository.fetchVodCategories()
                    val result = repository.fetchVodStreams()
                    setItemAction("display_movies", "↻ Refresh", enabled = true)
                    val msg = if (result is com.iptvapp.util.Resource.Success)
                        "Movies refreshed (${result.data?.size ?: 0} titles)"
                    else "Failed — server timeout or no content"
                    toast(msg)
                }
            }
        ) { c -> lifecycleScope.launch { prefs.setShowMovies(c) } }
        settingsItems += TvSettingItem.Toggle("display_series", "Show Series Tab",
            checked = showSeries,
            actionLabel = "↻ Refresh",
            onAction = {
                setItemAction("display_series", "Loading…", enabled = false)
                lifecycleScope.launch {
                    val result = repository.fetchSeries()
                    setItemAction("display_series", "↻ Refresh", enabled = true)
                    val msg = if (result is com.iptvapp.util.Resource.Success)
                        "Series refreshed (${result.data?.size ?: 0} titles)"
                    else "Failed — server timeout or no content"
                    toast(msg)
                }
            }
        ) { c -> lifecycleScope.launch { prefs.setShowSeries(c) } }
        // Show/Refresh Movies/Series above only ever touch the PRIMARY provider's own catalog
        // (fetchVodStreams/fetchSeries) — merged/secondary providers' movies and shows (browsed
        // via the Providers tab's Movies/Series mode buttons) had no refresh entry point on TV
        // at all. These call the merged-only refresh functions (same ones the phone's Provider
        // Speed Test/manual-refresh buttons use) — separate from the primary rows above, so
        // refreshing one never touches the other. No show/hide toggle here since there's no
        // separate "Merged Movies tab" to hide — Providers' Movies/Series modes already exist
        // independent of any toggle.
        val refreshMergedMovies: () -> Unit = {
            setItemAction("display_merged_movies_refresh", "Loading…", enabled = false)
            lifecycleScope.launch {
                val errors = repository.refreshMergedVod()
                setItemAction("display_merged_movies_refresh", "↻ Refresh", enabled = true)
                toast(if (errors.isEmpty()) "Merged movies refreshed" else "Refreshed with ${errors.size} provider error(s)")
            }
        }
        settingsItems += TvSettingItem.Action("display_merged_movies_refresh", "Merged Movies",
            actionLabel = "↻ Refresh", onAction = refreshMergedMovies, onClick = refreshMergedMovies)
        val refreshMergedSeries: () -> Unit = {
            setItemAction("display_merged_series_refresh", "Loading…", enabled = false)
            lifecycleScope.launch {
                val errors = repository.refreshMergedSeries()
                setItemAction("display_merged_series_refresh", "↻ Refresh", enabled = true)
                toast(if (errors.isEmpty()) "Merged series refreshed" else "Refreshed with ${errors.size} provider error(s)")
            }
        }
        settingsItems += TvSettingItem.Action("display_merged_series_refresh", "Merged Series",
            actionLabel = "↻ Refresh", onAction = refreshMergedSeries, onClick = refreshMergedSeries)

        // ── EPG ──
        settingsItems += TvSettingItem.Header("EPG")
        settingsItems += TvSettingItem.SubHeader("epg_sub_sources", "Sources") { toggleSubHeader("EPG", "epg_sub_sources") }
        if (epgUrls.isEmpty()) {
            settingsItems += TvSettingItem.Info("epg_no_sources", "No EPG sources configured")
        }
        settingsItems += TvSettingItem.Toggle(
            "epg_use_default_us",
            "No guide from your provider? Use the default US guide",
            checked = epgUrls.contains(com.iptvapp.AppConstants.DEFAULT_US_EPG_URL)
        ) { checked ->
            if (checked) epgUrls.add(com.iptvapp.AppConstants.DEFAULT_US_EPG_URL)
            else epgUrls.remove(com.iptvapp.AppConstants.DEFAULT_US_EPG_URL)
            lifecycleScope.launch {
                prefs.saveEpgUrls(epgUrls)
                rebuildList("epg_use_default_us")
                toast(if (checked) "Default US guide added — refresh EPG to load it" else "Default US guide removed")
            }
        }
        epgUrls.forEachIndexed { i, url ->
            settingsItems += TvSettingItem.Action("epg_source_$i",
                if (i == 0) "Primary EPG Source" else "EPG Source ${i + 1}",
                value = url.take(60)) { showEpgSourceOptions(i) }
        }
        settingsItems += TvSettingItem.Action("epg_add", "Add EPG Source") { showAddEpgDialog() }
        settingsItems += TvSettingItem.SubHeader("epg_sub_refresh", "Refresh") { toggleSubHeader("EPG", "epg_sub_refresh") }
        settingsItems += TvSettingItem.Action("epg_refresh",
            if (isEpgRefreshing) "Cancel EPG Refresh" else "Refresh EPG Now",
            value = lastRefresh) { startOrCancelEpgRefresh() }
        settingsItems += TvSettingItem.Toggle("epg_missing_only", "Refresh Missing Only",
            subtitle = "Skip channels that already have EPG data",
            checked = missingOnly) { c -> lifecycleScope.launch { prefs.setEpgRefreshMissingOnly(c) } }
        settingsItems += TvSettingItem.Action("epg_auto_refresh", "Auto Refresh",
            value = if (autoHours == 0) "Off" else "Every ${autoHours}h") {
            lifecycleScope.launch {
                showAutoRefreshDialog(prefs.epgAutoRefreshHours.first())
            }
        }

        // ── UPDATES ──
        settingsItems += TvSettingItem.Header("Updates")
        settingsItems += TvSettingItem.Info("update_version", versionInfo)
        settingsItems += TvSettingItem.Action("update_check", "Check for Updates") {
            toast("Checking for updates...")
            UpdateChecker(this).check(lifecycleScope)
        }
        settingsItems += TvSettingItem.Action("update_whats_new", "What's New") { showChangelog() }

        // ── SUBTITLES ──
        val subStyle = prefs.subtitleStyle.first()
        settingsItems += TvSettingItem.Header("Subtitles")
        settingsItems += TvSettingItem.Action("sub_size", "Subtitle Size",
            value = "${(subStyle.sizeScale * 100).toInt()}%") { showSubtitleSizeDialog(subStyle.sizeScale) }
        settingsItems += TvSettingItem.Action("sub_offset", "Vertical Offset",
            value = "${subStyle.verticalOffsetDp}dp") { showSubtitleOffsetDialog(subStyle.verticalOffsetDp) }
        settingsItems += TvSettingItem.Toggle("sub_bold", "Bold",
            subtitle = "Use a heavier subtitle font weight",
            checked = subStyle.bold) { c -> lifecycleScope.launch { prefs.setSubtitleBold(c) } }
        settingsItems += TvSettingItem.Action("sub_text_color", "Text Color",
            value = "#%08X".format(subStyle.textColor)) { showSubtitleColorDialog("Text Color", subStyle.textColor) { prefs.setSubtitleTextColor(it) } }
        settingsItems += TvSettingItem.Action("sub_bg_color", "Background Color",
            value = "#%08X".format(subStyle.backgroundColor)) { showSubtitleColorDialog("Background Color", subStyle.backgroundColor) { prefs.setSubtitleBackgroundColor(it) } }
        settingsItems += TvSettingItem.Toggle("sub_outline", "Outline",
            subtitle = "Draw a border around subtitle text",
            checked = subStyle.outlineEnabled) { c -> lifecycleScope.launch { prefs.setSubtitleOutlineEnabled(c) } }
        settingsItems += TvSettingItem.Action("sub_outline_color", "Outline Color",
            value = "#%08X".format(subStyle.outlineColor)) { showSubtitleColorDialog("Outline Color", subStyle.outlineColor) { prefs.setSubtitleOutlineColor(it) } }
        settingsItems += TvSettingItem.Info("sub_ass_note",
            "Advanced ASS/SSA subtitle rendering (custom styles, positioning, animations) requires a libass-based player engine and isn't supported by the current ExoPlayer-based player.")

        // ── TRAKT ──
        settingsItems += TvSettingItem.Header("Trakt")
        if (!traktManager.isConfigured) {
            settingsItems += TvSettingItem.Info("trakt_not_configured", "Trakt is not configured for this build")
        } else {
            val traktConnected = traktManager.isConnected.first()
            if (traktConnected) {
                settingsItems += TvSettingItem.Info("trakt_status", "✓ Connected — scrobbling your watch activity")
                settingsItems += TvSettingItem.Action("trakt_sync_history", "Sync Watched History from Trakt") {
                    setItemEnabled("trakt_sync_history", false)
                    setItemValue("trakt_sync_history", "Syncing…")
                    lifecycleScope.launch {
                        val result = traktManager.syncWatchedHistoryBack()
                        lastTraktSyncResult = result
                        Toast.makeText(this@TvSettingsActivity,
                            "Matched ${result.moviesMatched} movies, ${result.showsMatched} shows", Toast.LENGTH_LONG).show()
                        buildSettingsList()
                        showSection("Trakt", focusFirst = false)
                    }
                }
                if (lastTraktSyncResult?.let { it.unmatchedMovies.isNotEmpty() || it.unmatchedShows.isNotEmpty() } == true) {
                    settingsItems += TvSettingItem.Action("trakt_sync_unmatched", "View Unmatched Titles") {
                        showUnmatchedTraktDialog(lastTraktSyncResult!!)
                    }
                }
                settingsItems += TvSettingItem.Action("trakt_disconnect", "Disconnect Trakt", danger = true) {
                    lifecycleScope.launch { traktManager.disconnect(); buildSettingsList(); showSection("Trakt", focusFirst = false) }
                }
            } else {
                settingsItems += TvSettingItem.Action("trakt_connect", "Connect to Trakt") { showTraktConnectDialog() }
            }
        }

        // ── BACKUP ──
        settingsItems += TvSettingItem.Header("Backup & Restore")
        settingsItems += TvSettingItem.Action("backup_to_file", "Backup to File") {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            createBackupLauncher.launch("MKTV_backup_$timestamp.json")
        }
        settingsItems += TvSettingItem.Action("restore_from_file", "Restore from File") {
            openBackupLauncher.launch(arrayOf("application/json"))
        }
        settingsItems += TvSettingItem.Action("manage_backups", "Manage Backups on This Device") {
            showManageBackupsDialog()
        }
        settingsItems += TvSettingItem.Action("backup_qr", "Generate Backup QR Code") {
            lifecycleScope.launch { doQrBackup() }
        }
        settingsItems += TvSettingItem.Action("backup_debug", "Send Debug Report") { sendDebugReport() }
        settingsItems += TvSettingItem.Action("provider_health", "Provider Health") { showProviderHealthDialog() }
        settingsItems += TvSettingItem.Action("provider_speed_test", "Provider Speed Test") { showSpeedTestDialog() }

        // ── SERVERS ──
        settingsItems += TvSettingItem.Header("Providers")
        val primaryActive = activeIdx == -1
        settingsItems += TvSettingItem.Action("server_primary",
            "${if (primaryActive) "●  " else ""}$primaryNick",
            value = creds.serverUrl.take(50).ifBlank { "Not set" }) { showEditPrimaryDialog(creds, primaryNick) }
        extraServers.forEachIndexed { i, server ->
            val nick = server.getOrElse(3) { "" }.ifEmpty { server.getOrElse(1) { "Provider ${i + 2}" } }
            val isActive = activeIdx == i
            val isEnabled = server.getOrElse(5) { "true" }.toBoolean()
            // Same enable/disable toggle phone's Settings has, as a Toggle row so the D-pad can
            // reach it inline (to the right of the provider's own row) instead of a separate row
            // underneath — actionLabel/onAction is this row's existing "secondary button on the
            // same row, independently focusable" mechanism (already used for e.g. Show Movies
            // Tab's refresh button), reused here for the Switch/Edit/Update/Remove dialog so
            // tapping the row body toggles enabled/disabled and the action button opens options.
            settingsItems += TvSettingItem.Toggle(
                id = "server_$i",
                title = "${if (isActive) "●  " else ""}$nick",
                subtitle = server.getOrElse(0) { "" }.take(45),
                checked = isEnabled,
                valueOn = "Enabled", valueOff = "Disabled",
                actionLabel = "Options",
                onAction = { showServerOptions(i) },
                onToggle = { checked ->
                    val updated = extraServers[i].toMutableList()
                    while (updated.size < 6) updated.add("true")
                    updated[5] = checked.toString()
                    extraServers[i] = updated
                    lifecycleScope.launch {
                        prefs.saveExtraServersWithNick(extraServers)
                        db.mergedChannelDao().clearAll()
                        db.mergedVodDao().clearAll()
                        db.mergedSeriesDao().clearAll()
                        rebuildList("server_add")
                    }
                }
            )
        }
        settingsItems += TvSettingItem.Action("server_add", "Add Provider") { showAddServerDialog() }
        settingsItems += TvSettingItem.Action("server_update_channels", "Update All Provider Channels") {
            toast("Updating provider channels…")
            lifecycleScope.launch { repository.refreshMergedChannels() }
        }

        // ── ACCOUNT ──
        settingsItems += TvSettingItem.Header("Account")
        settingsItems += TvSettingItem.Action("account_logout", "Logout",
            danger = true) { confirmLogout() }
        settingsItems += TvSettingItem.Action("account_epg_refresh",
            if (isEpgRefreshing) "Cancel EPG Refresh" else "Refresh EPG Now",
            value = lastRefresh) { startOrCancelEpgRefresh() }
        settingsItems += TvSettingItem.Action("account_update_check", "Check for Updates") {
            toast("Checking for updates...")
            UpdateChecker(this).check(lifecycleScope)
        }

        // ── SYNC ──
        settingsItems += TvSettingItem.Header("Sync")
        val ownCode = syncManager.getOwnSyncCode().take(8).uppercase()
        settingsItems += TvSettingItem.Info("sync_own_code", if (ownCode.isNotEmpty()) "Your sync code: $ownCode" else "")
        settingsItems += TvSettingItem.SubHeader("sync_sub_pairing", "Pairing") { toggleSubHeader("Sync", "sync_sub_pairing") }
        settingsItems += TvSettingItem.Action("sync_pair", "Enter Pairing Code",
            value = "Set to pull another device's favorites") { showPairingCodeDialog() }
        settingsItems += TvSettingItem.Action("sync_saved_codes", "Saved Pairing Codes") { showSavedPairingCodesDialog() }
        settingsItems += TvSettingItem.SubHeader("sync_sub_actions", "Actions") { toggleSubHeader("Sync", "sync_sub_actions") }
        settingsItems += TvSettingItem.Toggle("sync_auto", "Auto Sync to Cloud (daily)",
            subtitle = lastSyncText,
            checked = syncEnabled) { enabled ->
            lifecycleScope.launch { prefs.setSyncEnabled(enabled) }
            if (enabled) scheduleAutoSync() else cancelAutoSync()
        }
        settingsItems += TvSettingItem.Action("sync_up", "Push to Cloud") { doSyncUp() }
        settingsItems += TvSettingItem.Action("sync_down", "Pull from Cloud") { doSyncDown() }
        settingsItems += TvSettingItem.Info("sync_status", syncSummary)

        groupIntoSections()
    }

    // ─── Item helpers ─────────────────────────────────────────────────────────

    private fun indexOfItem(id: String) = settingsItems.indexOfFirst { item ->
        when (item) {
            is TvSettingItem.Toggle -> item.id == id
            is TvSettingItem.Action -> item.id == id
            is TvSettingItem.Info   -> item.id == id
            else -> false
        }
    }

    private fun setItemValue(id: String, value: String) {
        val idx = indexOfItem(id)
        if (idx < 0) return
        when (val item = settingsItems[idx]) {
            is TvSettingItem.Action -> item.value = value
            is TvSettingItem.Info   -> item.text  = value
            else -> return
        }
        adapter.notifyItemChanged(idx)
    }

    private fun setItemTitle(id: String, title: String) {
        val idx = indexOfItem(id)
        if (idx < 0) return
        val item = settingsItems[idx] as? TvSettingItem.Action ?: return
        item.title = title
        adapter.notifyItemChanged(idx)
    }

    private fun setItemEnabled(id: String, enabled: Boolean) {
        val idx = indexOfItem(id)
        if (idx < 0) return
        val item = settingsItems[idx] as? TvSettingItem.Action ?: return
        item.enabled = enabled
        adapter.notifyItemChanged(idx)
    }

    // Updates a Toggle row's secondary action button label/enabled state (e.g. the Refresh
    // button attached to Show Movies/Series Tab) — separate from setItemEnabled/setItemTitle
    // since those only handle plain Action rows, not a Toggle's optional inline action.
    private fun setItemAction(id: String, label: String, enabled: Boolean) {
        val idx = indexOfItem(id)
        if (idx < 0) return
        when (val item = settingsItems[idx]) {
            is TvSettingItem.Toggle -> { item.actionLabel = label; item.actionEnabled = enabled }
            is TvSettingItem.Action -> { item.actionLabel = label; item.actionEnabled = enabled }
            else -> return
        }
        adapter.notifyItemChanged(idx)
    }

    private fun rebuildList(scrollToId: String? = null) {
        val lm = binding.rvTvSettings.layoutManager as LinearLayoutManager
        val savedPos = lm.findFirstVisibleItemPosition()
        val section = activeSection
        lifecycleScope.launch {
            buildSettingsList()
            if (section != null) showSection(section, focusFirst = false) else showSectionMenu(focusFirst = false)
            val pos = if (scrollToId != null) {
                indexOfItem(scrollToId).coerceAtLeast(0)
            } else {
                savedPos.coerceAtLeast(0)
            }
            binding.rvTvSettings.scrollToPosition(pos)
        }
    }

    // ─── EPG ─────────────────────────────────────────────────────────────────

    private fun startOrCancelEpgRefresh() {
        if (isEpgRefreshing) {
            workManager.cancelUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME)
            isEpgRefreshing = false
            setItemTitle("epg_refresh", "Refresh EPG Now")
            lifecycleScope.launch { setItemValue("epg_refresh", getLastRefreshText()) }
        } else {
            lifecycleScope.launch {
                isEpgRefreshing = true
                val missingOnly = prefs.epgRefreshMissingOnly.first()
                val request = OneTimeWorkRequestBuilder<EpgRefreshWorker>()
                    .setInputData(workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to missingOnly))
                    .build()
                currentEpgWorkId = request.id
                setItemTitle("epg_refresh", "Cancel EPG Refresh")
                setItemValue("epg_refresh", "Starting...")
                workManager.enqueueUniqueWork(
                    EpgRefreshWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request
                )
                observeCurrentEpgWork(request.id)
            }
        }
    }

    private fun observeCurrentEpgWork(workId: UUID) {
        workManager.getWorkInfoByIdLiveData(workId).observe(this) { info ->
            if (info == null) return@observe
            val status = info.progress.getString(EpgRefreshWorker.KEY_STATUS)
                ?: info.outputData.getString(EpgRefreshWorker.KEY_STATUS)
                ?: ""
            if (status.isNotBlank()) setItemValue("epg_refresh", status)
            val running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
            if (!running) {
                isEpgRefreshing = false
                setItemTitle("epg_refresh", "Refresh EPG Now")
                if (info.state.isFinished) {
                    lifecycleScope.launch { setItemValue("epg_refresh", getLastRefreshText()) }
                    if (info.state == WorkInfo.State.SUCCEEDED) toast("EPG refresh complete")
                }
            }
        }
    }

    private fun observeEpgWork() {
        workManager.getWorkInfosForUniqueWorkLiveData(EpgRefreshWorker.UNIQUE_WORK_NAME)
            .observe(this) { infos ->
                val running = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: return@observe
                isEpgRefreshing = true
                val status = running.progress.getString(EpgRefreshWorker.KEY_STATUS) ?: ""
                if (status.isNotBlank()) setItemValue("epg_refresh", status)
                setItemTitle("epg_refresh", "Cancel EPG Refresh")
            }
    }

    private fun scheduleAutoEpgRefresh(hours: Int) {
        if (hours == 0) { workManager.cancelUniqueWork(AUTO_EPG_WORK); return }
        val req = PeriodicWorkRequestBuilder<EpgRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setInputData(workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to true))
            .build()
        workManager.enqueueUniquePeriodicWork(AUTO_EPG_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private suspend fun getLastRefreshText(): String {
        val t = prefs.lastEpgRefreshTime.first()
        return if (t == 0L) "Never refreshed"
        else SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(t))
    }

    private fun showEpgSourceOptions(index: Int) {
        if (index == 0) {
            showEditEpgDialog(0)
        } else {
            AlertDialog.Builder(this)
                .setTitle("EPG Source ${index + 1}")
                .setMessage(epgUrls.getOrElse(index) { "" })
                .setPositiveButton("Edit") { _, _ -> showEditEpgDialog(index) }
                .setNeutralButton("Remove") { _, _ ->
                    epgUrls.removeAt(index)
                    lifecycleScope.launch {
                        prefs.saveEpgUrls(epgUrls)
                        rebuildList("epg_add")
                        toast("EPG source removed")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showSubtitleSizeDialog(current: Float) {
        val options = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = options.map { "${(it * 100).toInt()}%" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Subtitle Size")
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                lifecycleScope.launch { prefs.setSubtitleSizeScale(options[which]); rebuildList("sub_size") }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubtitleOffsetDialog(current: Int) {
        val options = listOf(-60, -40, -20, 0, 20, 40, 60)
        val labels = options.map { if (it == 0) "Default" else "${it}dp" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Vertical Offset")
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(options.indexOf(0))) { dialog, which ->
                lifecycleScope.launch { prefs.setSubtitleVerticalOffsetDp(options[which]); rebuildList("sub_offset") }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubtitleColorDialog(title: String, current: Int, onPicked: suspend (Int) -> Unit) {
        val presets = linkedMapOf(
            "White" to 0xFFFFFFFF.toInt(),
            "Yellow" to 0xFFFFFF00.toInt(),
            "Black" to 0xFF000000.toInt(),
            "Transparent" to 0x00000000
        )
        val labels = presets.keys.toTypedArray()
        val values = presets.values.toList()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                lifecycleScope.launch { onPicked(values[which]); buildSettingsList(); showSection("Subtitles", focusFirst = false) }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUnmatchedTraktDialog(result: com.iptvapp.trakt.TraktManager.SyncBackResult) {
        val message = buildString {
            if (result.unmatchedMovies.isNotEmpty()) {
                append("Movies not found in your library:\n")
                result.unmatchedMovies.forEach { append("• $it\n") }
            }
            if (result.unmatchedShows.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("Shows not found in your library:\n")
                result.unmatchedShows.forEach { append("• $it\n") }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Unmatched Trakt Titles")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showTraktConnectDialog() {
        val messageView = android.widget.TextView(this).apply {
            text = "Contacting Trakt…"
            setPadding(48, 24, 48, 24)
            textSize = 16f
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect to Trakt")
            .setView(messageView)
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .create()
        dialog.show()

        traktAuthJob?.cancel()
        traktAuthJob = lifecycleScope.launch {
            traktManager.startDeviceAuth { result ->
                runOnUiThread {
                    when (result) {
                        is com.iptvapp.trakt.TraktDeviceAuthResult.Pending -> {
                            messageView.text = "1. On any device, go to:\n${result.verificationUrl}\n\n" +
                                "2. Enter this code:\n\n${result.userCode}\n\n" +
                                "Waiting for you to authorize…"
                        }
                        is com.iptvapp.trakt.TraktDeviceAuthResult.Success -> {
                            dialog.dismiss()
                            toast("Connected to Trakt")
                            lifecycleScope.launch { buildSettingsList(); showSection("Trakt", focusFirst = false) }
                        }
                        is com.iptvapp.trakt.TraktDeviceAuthResult.Expired -> {
                            dialog.dismiss()
                            toast("Trakt code expired — try again")
                        }
                        is com.iptvapp.trakt.TraktDeviceAuthResult.Denied -> {
                            dialog.dismiss()
                            toast("Trakt authorization denied")
                        }
                        is com.iptvapp.trakt.TraktDeviceAuthResult.Error -> {
                            dialog.dismiss()
                            toast("Trakt error: ${result.message}")
                        }
                    }
                }
            }
        }
    }

    private fun showAddEpgDialog() {
        val et = EditText(this).apply {
            hint = "http://yourserver.com/xmltv.php"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Add EPG Source")
            .setView(et)
            .setPositiveButton("Add") { _, _ ->
                val url = et.text.toString().trim()
                if (url.isNotEmpty()) {
                    epgUrls.add(url)
                    lifecycleScope.launch {
                        prefs.saveEpgUrls(epgUrls)
                        rebuildList("epg_add")
                        toast("EPG source added")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditEpgDialog(index: Int) {
        val et = EditText(this).apply {
            setText(epgUrls.getOrElse(index) { "" })
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(if (index == 0) "Edit Primary EPG Source" else "Edit EPG Source ${index + 1}")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val url = et.text.toString().trim()
                if (url.isNotEmpty()) {
                    epgUrls[index] = url
                    lifecycleScope.launch {
                        prefs.saveEpgUrls(epgUrls)
                        setItemValue("epg_source_$index", url.take(60))
                        toast("EPG source updated")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAutoRefreshDialog(currentHours: Int) {
        val options = arrayOf("Off", "Every 6 hours", "Every 12 hours", "Every 24 hours")
        val hours   = intArrayOf(0, 6, 12, 24)
        val selIdx  = hours.indexOf(currentHours).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Auto Refresh EPG")
            .setSingleChoiceItems(options, selIdx) { dialog, which ->
                val h = hours[which]
                lifecycleScope.launch {
                    prefs.setEpgAutoRefreshHours(h)
                    scheduleAutoEpgRefresh(h)
                    setItemValue("epg_auto_refresh", if (h == 0) "Off" else "Every ${h}h")
                    toast(if (h == 0) "Auto EPG refresh off" else "Auto EPG refresh every $h hours")
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Display ─────────────────────────────────────────────────────────────

    private fun accentColorName(hex: String): String =
        accentPalette.firstOrNull { it.second.equals(hex, ignoreCase = true) }?.first ?: "Blue"

    private fun showAccentColorDialog() {
        val names = accentPalette.map { it.first }.toTypedArray()
        val selIdx = accentPalette.indexOfFirst { it.second.equals(currentAccentColorHex, ignoreCase = true) }
            .coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Accent Color")
            .setSingleChoiceItems(names, selIdx) { dialog, which ->
                val hex = accentPalette[which].second
                currentAccentColorHex = hex
                lifecycleScope.launch {
                    prefs.setAccentColor(hex)
                    setItemValue("display_accent", accentPalette[which].first)
                    toast("Accent color set to ${accentPalette[which].first}")
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Updates ─────────────────────────────────────────────────────────────

    private fun showChangelog() {
        val text = try {
            assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        } catch (_: Exception) { "Changelog not available." }
        AlertDialog.Builder(this)
            .setTitle("What's New")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .show()
    }

    // ─── Backup ──────────────────────────────────────────────────────────────

    private suspend fun buildBackupJson(): JSONObject {
        val creds = prefs.credentials.first()
        return JSONObject().apply {
            put("serverUrl", creds.serverUrl)
            put("username", creds.username)
            put("password", creds.password)
            put("epgUrl", prefs.epgUrl.first())
            put("preferredFormat", prefs.preferredFormat.first())
            put("epgAutoRefreshHours", prefs.epgAutoRefreshHours.first())
            put("epgRefreshMissingOnly", prefs.epgRefreshMissingOnly.first())
            put("usaOnlyChannels", prefs.usaOnlyChannels.first())
            put("showMovies", prefs.showMovies.first())
            put("showSeries", prefs.showSeries.first())
            put("showWatching", prefs.showWatching.first())
            put("favoriteCategoryIds", JSONArray(prefs.favoriteLiveCategoryIds.first().toList()))
            put("favoriteChannelIds", JSONArray(db.channelDao().getFavoriteChannelIds()))
            put("watchHistory", JSONArray(db.channelDao().getWatchHistoryForBackup().map {
                JSONObject().apply {
                    put("streamId", it.streamId)
                    put("lastWatched", it.lastWatched)
                    put("viewCount", it.viewCount)
                }
            }))
            // Folder ids are local autoincrement values (not portable across a restore onto
            // a different/reset device), so folders are saved by NAME — same approach
            // SyncManager already uses. Previously omitted entirely, so restoring a backup
            // brought favorites back but dumped every one into Unsorted.
            val folders = db.favoriteFolderDao().getAll().first()
            val folderNameById = folders.associate { it.id to it.name }
            val channelFolders = db.channelDao().getFavoriteChannelsBlocking()
                .mapNotNull { ch -> ch.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { name -> ch.streamId.toString() to name } } }
                .toMap()
            put("favoriteFolders", JSONArray(folders.map { it.name }))
            put("channelFolders", JSONObject(channelFolders))

            // Extra providers (the "Providers" merged-browse feature) were never included in
            // any backup — restoring one silently dropped every non-primary provider. Trakt's
            // OAuth tokens are deliberately NOT included: unlike the rest of this file, that's
            // a live credential, and this JSON can end up shared/exported (QR backup, emailed
            // file) — reconnecting Trakt after a restore is a small one-time action, copying a
            // bearer token into a plaintext file users might hand to someone else is not.
            put("extraServers", JSONArray(prefs.getExtraServersWithNick().map { s ->
                JSONObject().apply {
                    put("url", s[0]); put("user", s[1]); put("pass", s[2])
                    put("nick", s.getOrElse(3) { "" }); put("epg", s.getOrElse(4) { "" })
                }
            }))

            // Merged/other-provider favorites, folder assignments, pinned categories, and
            // merged VOD/Series favorites — keyed by server URL (not serverIndex, meaningless
            // across devices/restores). Previously missing entirely from TV's backup (unlike
            // phone's SettingsActivity, which already had the channel-favorites half of this) —
            // restoring a backup on TV silently dropped every merged favorite, not just VOD/
            // Series ones.
            val mergedUrlByIndex = repository.getMergedServerUrls()
            val mergedFavorites = db.mergedChannelDao().getAllFavorites().first()
            put("mergedFavorites", JSONArray(mergedFavorites.mapNotNull { ch ->
                val url = mergedUrlByIndex[ch.serverIndex] ?: return@mapNotNull null
                JSONObject().apply {
                    put("serverUrl", url)
                    put("streamId", ch.streamId)
                    ch.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { put("folderName", it) } }
                }
            }))
            val favoriteMergedCategoryKeys = prefs.favoriteMergedCategoryIds.first()
            put("mergedFavoriteCategories", JSONArray(favoriteMergedCategoryKeys.mapNotNull { key ->
                val serverIndex = key.substringBefore(':', "").toIntOrNull() ?: return@mapNotNull null
                val categoryId = key.substringAfter(':', "")
                val url = mergedUrlByIndex[serverIndex] ?: return@mapNotNull null
                JSONObject().apply { put("serverUrl", url); put("categoryId", categoryId) }
            }))
            val mergedVodFavorites = db.mergedVodDao().getAllFavorites().first()
            put("mergedVodFavorites", JSONArray(mergedVodFavorites.mapNotNull { v ->
                val url = mergedUrlByIndex[v.serverIndex] ?: return@mapNotNull null
                JSONObject().apply {
                    put("serverUrl", url)
                    put("streamId", v.streamId)
                    v.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { put("folderName", it) } }
                }
            }))
            val mergedSeriesFavorites = db.mergedSeriesDao().getAllFavorites().first()
            put("mergedSeriesFavorites", JSONArray(mergedSeriesFavorites.mapNotNull { s ->
                val url = mergedUrlByIndex[s.serverIndex] ?: return@mapNotNull null
                JSONObject().apply {
                    put("serverUrl", url)
                    put("seriesId", s.seriesId)
                    s.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { put("folderName", it) } }
                }
            }))

            val hiddenVodKeys = prefs.hiddenMergedVodCategoryIds.first()
            put("hiddenMergedVodCategories", JSONArray(hiddenVodKeys.mapNotNull { key ->
                val serverIndex = key.substringBefore(':', "").toIntOrNull() ?: return@mapNotNull null
                val categoryId = key.substringAfter(':', "")
                val url = mergedUrlByIndex[serverIndex] ?: return@mapNotNull null
                JSONObject().apply { put("serverUrl", url); put("categoryId", categoryId) }
            }))
            val hiddenSeriesKeys = prefs.hiddenMergedSeriesCategoryIds.first()
            put("hiddenMergedSeriesCategories", JSONArray(hiddenSeriesKeys.mapNotNull { key ->
                val serverIndex = key.substringBefore(':', "").toIntOrNull() ?: return@mapNotNull null
                val categoryId = key.substringAfter(':', "")
                val url = mergedUrlByIndex[serverIndex] ?: return@mapNotNull null
                JSONObject().apply { put("serverUrl", url); put("categoryId", categoryId) }
            }))

            val style = prefs.subtitleStyle.first()
            put("subtitleStyle", JSONObject().apply {
                put("sizeScale", style.sizeScale)
                put("verticalOffsetDp", style.verticalOffsetDp)
                put("bold", style.bold)
                put("textColor", style.textColor)
                put("backgroundColor", style.backgroundColor)
                put("outlineEnabled", style.outlineEnabled)
                put("outlineColor", style.outlineColor)
            })
        }
    }

    private suspend fun writeBackupToUri(uri: Uri) {
        try {
            val body = buildBackupJson().toString(2)
            contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            toast("Backup saved")
        } catch (e: Exception) {
            toast("Backup failed: ${e.message}")
        }
    }

    private suspend fun restoreBackupFromUri(uri: Uri) {
        try {
            val jsonText = contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return
            applyBackupJson(JSONObject(jsonText))
        } catch (e: Exception) {
            toast("Restore failed: ${e.message}")
        }
    }

    private suspend fun restoreBackupFromFile(file: java.io.File) {
        try {
            val jsonText = withContext(Dispatchers.IO) { file.readText() }
            applyBackupJson(JSONObject(jsonText))
        } catch (e: Exception) {
            toast("Restore failed: ${e.message}")
        }
    }

    /** Same private, app-only folder AutoBackupWorker writes weekly snapshots into. */
    private fun privateBackupsDir(): java.io.File =
        java.io.File(getExternalFilesDir(null), "backups").apply { mkdirs() }

    private suspend fun quickBackupNow() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = java.io.File(privateBackupsDir(), "MKTV_backup_$timestamp.json")
            val body = buildBackupJson().toString(2)
            withContext(Dispatchers.IO) { file.writeText(body) }
            toast("Backup saved on this device")
        } catch (e: Exception) {
            toast("Backup failed: ${e.message}")
        }
    }

    private fun showManageBackupsDialog() {
        val files = privateBackupsDir().listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        val labels = arrayOf("+ Quick Backup Now") +
            files.map { dateFmt.format(Date(it.lastModified())) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Backups on This Device")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    lifecycleScope.launch { quickBackupNow(); showManageBackupsDialog() }
                } else {
                    showBackupFileActionDialog(files[which - 1])
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBackupFileActionDialog(file: java.io.File) {
        val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        AlertDialog.Builder(this)
            .setTitle(dateFmt.format(Date(file.lastModified())))
            .setItems(arrayOf("Restore this backup", "Delete")) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this)
                        .setTitle("Restore this backup?")
                        .setMessage("This will overwrite your current login, favorites, and settings.")
                        .setPositiveButton("Restore") { _, _ -> lifecycleScope.launch { restoreBackupFromFile(file) } }
                        .setNegativeButton("Cancel", null)
                        .show()
                    1 -> {
                        file.delete()
                        toast("Backup deleted")
                        showManageBackupsDialog()
                    }
                }
            }
            .show()
    }

    private suspend fun applyBackupJson(json: JSONObject) {
        try {
            val serverUrl = json.optString("serverUrl", "")
            val username = json.optString("username", "")
            val password = json.optString("password", "")
            if (serverUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()) {
                prefs.saveCredentials(serverUrl, username, password)
            }

            json.optString("epgUrl", "").takeIf { it.isNotEmpty() }?.let { prefs.setEpgUrl(it) }
            json.optString("preferredFormat", "").takeIf { it.isNotEmpty() }?.let { prefs.setPreferredFormat(it) }
            if (json.has("epgAutoRefreshHours")) prefs.setEpgAutoRefreshHours(json.optInt("epgAutoRefreshHours", 0))
            if (json.has("epgRefreshMissingOnly")) prefs.setEpgRefreshMissingOnly(json.optBoolean("epgRefreshMissingOnly", false))
            if (json.has("usaOnlyChannels")) prefs.setUsaOnlyChannels(json.optBoolean("usaOnlyChannels", true))
            if (json.has("showMovies")) prefs.setShowMovies(json.optBoolean("showMovies", true))
            if (json.has("showSeries")) prefs.setShowSeries(json.optBoolean("showSeries", true))
            if (json.has("showWatching")) prefs.setShowWatching(json.optBoolean("showWatching", true))

            val favCatArray = json.optJSONArray("favoriteCategoryIds")
            if (favCatArray != null) {
                val ids = (0 until favCatArray.length()).map { favCatArray.getString(it) }.toSet()
                prefs.setFavoriteLiveCategoryIds(ids)
            }
            val favChanArray = json.optJSONArray("favoriteChannelIds")
            if (favChanArray != null) {
                val ids = (0 until favChanArray.length()).map { favChanArray.getInt(it) }
                val existingIds = db.channelDao().getAllChannelIds().toSet()
                db.channelDao().clearAllFavorites()
                ids.filter { it in existingIds }.forEach { db.channelDao().setFavorite(it, true) }
                val missingIds = ids.filter { it !in existingIds }.toSet()
                if (missingIds.isNotEmpty()) prefs.setPendingFavoriteChannelIds(missingIds)
            }

            // Folders matched/created by NAME, same approach syncDown() uses — ids are local
            // autoincrement values, not portable across a restore onto a different/reset device.
            val remoteFolderNames = json.optJSONArray("favoriteFolders")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            val remoteChannelFolders = json.optJSONObject("channelFolders")
                ?.let { obj -> obj.keys().asSequence().mapNotNull { key -> key.toIntOrNull()?.let { it to obj.getString(key) } }.toList() }
                ?: emptyList()
            if (remoteFolderNames.isNotEmpty() || remoteChannelFolders.isNotEmpty()) {
                val existingFolders = db.favoriteFolderDao().getAll().first()
                val idByName = existingFolders.associate { it.name to it.id }.toMutableMap()
                var nextOrder = existingFolders.size
                for (name in remoteFolderNames) {
                    if (name !in idByName) {
                        val newId = db.favoriteFolderDao().insert(
                            com.iptvapp.data.local.entities.FavoriteFolderEntity(name = name, sortOrder = nextOrder++)
                        ).toInt()
                        idByName[name] = newId
                    }
                }
                val existingChannelIds = db.channelDao().getAllChannelIds().toSet()
                remoteChannelFolders.forEach { (streamId, folderName) ->
                    if (streamId in existingChannelIds) {
                        idByName[folderName]?.let { folderId -> db.channelDao().setFavoriteFolder(streamId, folderId) }
                    }
                }
            }

            val watchHistoryArray = json.optJSONArray("watchHistory")
            if (watchHistoryArray != null) {
                val existingIds = db.channelDao().getAllChannelIds().toSet()
                for (i in 0 until watchHistoryArray.length()) {
                    val entry = watchHistoryArray.getJSONObject(i)
                    val streamId = entry.optInt("streamId", -1)
                    if (streamId in existingIds) {
                        db.channelDao().restoreWatchHistory(
                            streamId,
                            entry.optLong("lastWatched", 0L),
                            entry.optInt("viewCount", 0)
                        )
                    }
                }
            }

            val extraServersArray = json.optJSONArray("extraServers")
            if (extraServersArray != null) {
                val restored = (0 until extraServersArray.length()).map { i ->
                    val obj = extraServersArray.getJSONObject(i)
                    listOf(
                        obj.optString("url", ""), obj.optString("user", ""), obj.optString("pass", ""),
                        obj.optString("nick", ""), obj.optString("epg", "")
                    )
                }
                prefs.saveExtraServersWithNick(restored)
                extraServers.clear(); extraServers.addAll(restored)
                db.mergedChannelDao().clearAll()
                db.mergedVodDao().clearAll()
                db.mergedSeriesDao().clearAll()
            }

            // Merged/other-provider favorites, folders, pinned categories, and merged VOD/
            // Series favorites can't be applied yet — that provider's catalog hasn't been
            // fetched at restore time, so there's no local row to mark isFavorite=1 on yet.
            // Stored as pending, keyed by server URL, and applied once that server is actually
            // refreshed (see XtreamRepository.refreshMergedChannels/refreshMergedVod/
            // refreshMergedSeries). Previously entirely missing from TV's restore.
            json.optJSONArray("mergedFavorites")?.let { arr ->
                val keys = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    "${obj.optString("serverUrl")}|${obj.optInt("streamId")}"
                }.toSet()
                prefs.setPendingMergedFavorites(keys)
                val folderKeys = (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    val folderName = obj.optString("folderName", "")
                    if (folderName.isBlank()) null
                    else "${obj.optString("serverUrl")}|${obj.optInt("streamId")}|$folderName"
                }.toSet()
                if (folderKeys.isNotEmpty()) prefs.setPendingMergedChannelFolders(folderKeys)
            }
            json.optJSONArray("mergedFavoriteCategories")?.let { arr ->
                val keys = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    "${obj.optString("serverUrl")}|${obj.optString("categoryId")}"
                }.toSet()
                prefs.setPendingMergedFavoriteCategories(keys)
            }
            json.optJSONArray("mergedVodFavorites")?.let { arr ->
                val keys = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    "${obj.optString("serverUrl")}|${obj.optInt("streamId")}"
                }.toSet()
                prefs.setPendingMergedVodFavorites(keys)
                val folderKeys = (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    val folderName = obj.optString("folderName", "")
                    if (folderName.isBlank()) null
                    else "${obj.optString("serverUrl")}|${obj.optInt("streamId")}|$folderName"
                }.toSet()
                if (folderKeys.isNotEmpty()) prefs.setPendingMergedVodFolders(folderKeys)
            }
            json.optJSONArray("mergedSeriesFavorites")?.let { arr ->
                val keys = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    "${obj.optString("serverUrl")}|${obj.optInt("seriesId")}"
                }.toSet()
                prefs.setPendingMergedSeriesFavorites(keys)
                val folderKeys = (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    val folderName = obj.optString("folderName", "")
                    if (folderName.isBlank()) null
                    else "${obj.optString("serverUrl")}|${obj.optInt("seriesId")}|$folderName"
                }.toSet()
                if (folderKeys.isNotEmpty()) prefs.setPendingMergedSeriesFolders(folderKeys)
            }
            json.optJSONArray("hiddenMergedVodCategories")?.let { arr ->
                val keys = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    "${obj.optString("serverUrl")}|${obj.optString("categoryId")}"
                }.toSet()
                prefs.setPendingHiddenMergedVodCategories(keys)
            }
            json.optJSONArray("hiddenMergedSeriesCategories")?.let { arr ->
                val keys = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    "${obj.optString("serverUrl")}|${obj.optString("categoryId")}"
                }.toSet()
                prefs.setPendingHiddenMergedSeriesCategories(keys)
            }

            json.optJSONObject("subtitleStyle")?.let { s ->
                prefs.setSubtitleSizeScale(s.optDouble("sizeScale", 1.0).toFloat())
                prefs.setSubtitleVerticalOffsetDp(s.optInt("verticalOffsetDp", 0))
                prefs.setSubtitleBold(s.optBoolean("bold", false))
                prefs.setSubtitleTextColor(s.optInt("textColor", 0xFFFFFFFF.toInt()))
                prefs.setSubtitleBackgroundColor(s.optInt("backgroundColor", 0x00000000))
                prefs.setSubtitleOutlineEnabled(s.optBoolean("outlineEnabled", true))
                prefs.setSubtitleOutlineColor(s.optInt("outlineColor", 0xFF000000.toInt()))
            }

            buildSettingsList()
            toast("Restore complete")
        } catch (e: Exception) {
            toast("Restore failed: ${e.message}")
        }
    }

    private fun showProviderHealthDialog() {
        toast("Checking providers…")
        lifecycleScope.launch {
            val report = com.iptvapp.util.ProviderHealth.build(this@TvSettingsActivity, db, prefs)

            // Live reachability check for EVERY configured provider — see phone SettingsActivity's
            // identical comment: only the primary provider has real reliability-history tracking.
            val allHealth = repository.checkAllProviderHealth()
            val allHealthText = allHealth.joinToString("\n\n") { s ->
                val statusLine = when {
                    s.reachable -> "✓ Online (${s.responseMs}ms)"
                    else -> "✗ Unreachable — ${s.error}"
                }
                val label = if (s.serverIndex == -1) "${s.nickname} (Primary)" else s.nickname
                "$label\n$statusLine"
            }

            val message = com.iptvapp.util.ProviderHealth.formatReport(report) +
                "\n\n— All Providers —\n\n" + allHealthText

            val builder = AlertDialog.Builder(this@TvSettingsActivity)
                .setTitle("Provider Health")
                .setMessage(message)
                .setPositiveButton("Close", null)
            if (report.worstChannels.isNotEmpty()) {
                builder.setNeutralButton("Least Reliable Channels") { _, _ ->
                    showWorstChannelsDialog(report.worstChannels)
                }
            }
            builder.show()
        }
    }

    // Phone's equivalent (runSpeedTest() in SettingsActivity.kt) writes into a dedicated
    // tvSpeedTestResult TextView on that screen's layout — TV settings has no such row, so this
    // shows the same repository.runSpeedTestForAllProviders() results in a dialog instead.
    private fun showSpeedTestDialog() {
        toast("Testing all active providers…")
        lifecycleScope.launch {
            try {
                val results = repository.runSpeedTestForAllProviders()
                val message = results.joinToString("\n\n") { r ->
                    val tcpStr = if (r.tcpAvgMs != null) "TCP Ping: ${r.tcpAvgMs}ms avg (${r.tcpSuccessCount}/3)" else "TCP Ping: failed"
                    val httpStr = if (r.httpMs != null) "HTTP Response: ${r.httpMs}ms" else "HTTP Response: failed"
                    val errorLine = r.error?.let { "\n$it" } ?: ""
                    "${r.nickname}\n$tcpStr\n$httpStr\nServer: ${r.host}$errorLine"
                }
                AlertDialog.Builder(this@TvSettingsActivity)
                    .setTitle("Provider Speed Test")
                    .setMessage(message)
                    .setPositiveButton("Close", null)
                    .show()
            } catch (e: Exception) {
                toast("Speed test failed: ${e.message}")
            }
        }
    }

    private fun showWorstChannelsDialog(channels: List<com.iptvapp.util.ProviderHealth.ChannelScore>) {
        val labels = channels.map { "${it.name} — ${it.reliabilityPercent}%" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Least Reliable Channels")
            .setItems(labels) { _, which -> showChannelHealthActionDialog(channels[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showChannelHealthActionDialog(channel: com.iptvapp.util.ProviderHealth.ChannelScore) {
        AlertDialog.Builder(this)
            .setTitle("${channel.name} — ${channel.reliabilityPercent}%")
            .setItems(arrayOf("Play This Channel", "Hide This Channel")) { _, which ->
                when (which) {
                    0 -> {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("mktv://play/${channel.streamId}")))
                        finish()
                    }
                    1 -> lifecycleScope.launch {
                        db.channelDao().setHidden(channel.streamId, true)
                        toast("${channel.name} hidden")
                    }
                }
            }
            .show()
    }

    private suspend fun doQrBackup() {
        try {
            val creds = prefs.credentials.first()
            val payload = JSONObject().apply {
                put("s", creds.serverUrl)
                put("u", creds.username)
                put("p", creds.password)
            }.toString()
            val encoded = android.util.Base64.encodeToString(
                payload.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            showQrDialog("mktv://restore?d=$encoded")
        } catch (_: Exception) {
            toast("Backup failed")
        }
    }

    private fun showQrDialog(content: String) {
        val size   = 600
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size)
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        try {
            val logo   = BitmapFactory.decodeResource(resources, R.drawable.splash_logo)
            val ls     = size / 4
            val scaled = Bitmap.createScaledBitmap(logo, ls, ls, true)
            val c = Canvas(bitmap)
            c.drawCircle(size / 2f, size / 2f, ls / 2f + 14, Paint().apply { color = Color.WHITE })
            c.drawBitmap(scaled, size / 2f - ls / 2f, size / 2f - ls / 2f, null)
            scaled.recycle(); logo.recycle()
        } catch (_: Exception) {}
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap); setPadding(32, 32, 32, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Scan to Login on Phone")
            .setMessage("Open MKTV on your phone and scan this code to restore login credentials")
            .setView(iv)
            .setPositiveButton("Done", null)
            .show()
    }

    // ─── Servers ─────────────────────────────────────────────────────────────

    /** Explicitly chains D-pad up/down and Enter/IME-next focus through a stacked form's fields,
     * in order — Android's default focus-finder doesn't reliably traverse a plain vertical
     * LinearLayout of mixed EditText/CheckBox views on TV D-pad input (a CheckBox breaks the
     * chain for the fields after it), and a bare EditText's Enter key does nothing by default.
     * Every EditText in the chain gets IME_ACTION_NEXT (IME_ACTION_DONE on the last one) so
     * Enter/OK advances to the next field and closes the keyboard on the last. */
    private fun wireFieldChain(vararg fields: android.view.View) {
        fields.forEachIndexed { i, field ->
            val next = fields.getOrNull(i + 1)
            val prev = fields.getOrNull(i - 1)
            if (next != null) field.nextFocusDownId = next.id.takeIf { it != android.view.View.NO_ID } ?: android.view.View.generateViewId().also { next.id = it }
            if (prev != null) field.nextFocusUpId = prev.id.takeIf { it != android.view.View.NO_ID } ?: android.view.View.generateViewId().also { prev.id = it }
            if (field is EditText) {
                field.imeOptions = if (next != null) android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                    else android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                field.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                        next?.requestFocus()
                        true
                    } else if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        field.clearFocus()
                        true
                    } else false
                }
            }
        }
    }

    private fun showServerOptions(index: Int) {
        val server = extraServers.getOrNull(index) ?: return
        // "Test Connection" used to only be reachable by opening Edit first — moved up here as
        // its own top-level action so checking connectivity doesn't require opening (and
        // potentially fat-fingering) the full edit form. "Remove" previously deleted immediately
        // on tap with no confirmation at all — an accidental D-pad press on the wrong row (this
        // list has no visible danger styling to warn you) silently wiped that provider and its
        // whole merged cache. Now asks first, see confirmRemoveServer().
        val actions = arrayOf("Switch", "Edit", "Test Connection", "Update Channels", "Remove")
        AlertDialog.Builder(this)
            .setTitle(server.getOrElse(3) { "" }.ifBlank { "Provider ${index + 2}" })
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> switchToServer(index)
                    1 -> showEditServerDialog(index)
                    2 -> testServerConnection(index)
                    3 -> {
                        toast("Updating channels for this provider…")
                        lifecycleScope.launch { repository.refreshMergedChannels() }
                    }
                    4 -> confirmRemoveServer(index)
                }
            }
            .show()
    }

    private fun testServerConnection(index: Int) {
        val server = extraServers.getOrNull(index) ?: return
        val url = server.getOrElse(0) { "" }
        val user = server.getOrElse(1) { "" }
        val pass = server.getOrElse(2) { "" }
        if (url.isBlank() || user.isBlank()) {
            toast("This provider has no URL/username set — edit it first")
            return
        }
        toast("Testing connection…")
        lifecycleScope.launch {
            val result = repository.testProviderConnection(url, user, pass)
            toast(if (result.reachable) "✓ Connected (${result.responseMs}ms)" else "✗ Failed — ${result.error}")
        }
    }

    private fun confirmRemoveServer(index: Int) {
        val server = extraServers.getOrNull(index) ?: return
        val nick = server.getOrElse(3) { "" }.ifBlank { "Provider ${index + 2}" }
        AlertDialog.Builder(this)
            .setTitle("Remove $nick?")
            .setMessage("This removes the provider and clears its cached merged channels/movies/shows. This can't be undone — you'll need to re-add it manually.")
            .setPositiveButton("Remove") { _, _ ->
                extraServers.removeAt(index)
                lifecycleScope.launch {
                    prefs.saveExtraServersWithNick(extraServers)
                    // Removing a server shifts every later server's index, which could
                    // silently re-attribute stale merged-channel/VOD/series rows to the
                    // wrong server until the next manual refresh — just clear the caches.
                    db.mergedChannelDao().clearAll()
                    db.mergedVodDao().clearAll()
                    db.mergedSeriesDao().clearAll()
                    rebuildList("server_add")
                    toast("Provider removed")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditPrimaryDialog(creds: com.iptvapp.data.local.ServerCredentials, currentNick: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
        }
        val etNick = EditText(this).apply { hint = "Nickname (optional)"; setText(currentNick) }
        val etUrl  = EditText(this).apply { hint = "Provider URL (http://...)"; setText(creds.serverUrl) }
        val etUser = EditText(this).apply { hint = "Username"; setText(creds.username) }
        val etPass = EditText(this).apply {
            hint = "Password"
            setText(creds.password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val cbShowPass = android.widget.CheckBox(this).apply {
            text = "Show password"
            setOnCheckedChangeListener { _, checked ->
                etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    if (checked) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                etPass.setSelection(etPass.text.length)
            }
        }
        layout.addView(etNick); layout.addView(etUrl); layout.addView(etUser); layout.addView(etPass); layout.addView(cbShowPass)
        wireFieldChain(etNick, etUrl, etUser, etPass, cbShowPass)
        AlertDialog.Builder(this)
            .setTitle("Edit Primary Provider")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val url = etUrl.text.toString().replace(" ", "").trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                val nick = etNick.text.toString().trim()
                if (url.isNotEmpty() && user.isNotEmpty()) {
                    lifecycleScope.launch {
                        prefs.saveCredentials(url, user, pass)
                        prefs.setServerNickname(nick)
                        db.mergedChannelDao().clearAll()
                        db.mergedVodDao().clearAll()
                        db.mergedSeriesDao().clearAll()
                        toast("Primary provider updated")
                        rebuildList("server_primary")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditServerDialog(index: Int) {
        val server = extraServers.getOrNull(index) ?: return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
        }
        val etNick = EditText(this).apply { hint = "Nickname (optional)"; setText(server.getOrElse(3) { "" }) }
        val etUrl  = EditText(this).apply { hint = "Provider URL (http://...)"; setText(server.getOrElse(0) { "" }) }
        val etUser = EditText(this).apply { hint = "Username"; setText(server.getOrElse(1) { "" }) }
        val etPass = EditText(this).apply {
            hint = "Password"
            setText(server.getOrElse(2) { "" })
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val cbShowPass = android.widget.CheckBox(this).apply {
            text = "Show password"
            setOnCheckedChangeListener { _, checked ->
                etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    if (checked) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                etPass.setSelection(etPass.text.length)
            }
        }
        val etEpg = EditText(this).apply {
            hint = "EPG URL (optional, http://...)"
            setText(server.getOrElse(4) { "" })
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        // Test Connection moved to the Options list (showServerOptions/testServerConnection) so
        // it's reachable without opening this full edit form — no longer duplicated here.
        layout.addView(etNick); layout.addView(etUrl); layout.addView(etUser); layout.addView(etPass); layout.addView(cbShowPass); layout.addView(etEpg)
        // D-pad down previously couldn't reliably reach etEpg — the checkbox sitting between
        // etPass and etEpg breaks Android's default focus-finder chain on some TV D-pad
        // implementations, since a CheckBox's focus behavior differs from an EditText's. Chain
        // focus explicitly instead of relying on default up/down traversal, and make Enter/OK
        // advance to the next field (IME_ACTION_NEXT) instead of doing nothing, ending on the
        // last field with IME_ACTION_DONE to close the keyboard.
        wireFieldChain(etNick, etUrl, etUser, etPass, cbShowPass, etEpg)
        AlertDialog.Builder(this)
            .setTitle("Edit Provider")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                // A URL should never legitimately contain whitespace — strip it all, not just
                // leading/trailing, since a stray space pasted mid-string silently breaks every
                // request to that server with no visible error.
                val url = etUrl.text.toString().replace(" ", "").trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                val epgUrl = etEpg.text.toString().replace(" ", "").trim()
                if (url.isNotEmpty() && user.isNotEmpty()) {
                    lifecycleScope.launch {
                        extraServers[index] = listOf(url, user, pass, etNick.text.toString().trim(), epgUrl)
                        prefs.saveExtraServersWithNick(extraServers)
                        db.mergedChannelDao().clearAll()
                        db.mergedVodDao().clearAll()
                        db.mergedSeriesDao().clearAll()
                        rebuildList("server_add")
                        toast("Provider updated")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchToServer(i: Int) {
        AlertDialog.Builder(this)
            .setTitle("Switch Provider")
            .setMessage("Switch to this provider? Local data will be cleared and the app will restart.")
            .setPositiveButton("Switch") { _, _ ->
                lifecycleScope.launch {
                    val server  = extraServers[i]
                    val url     = server[0]; val user = server[1]; val pass = server[2]
                    // Same nickname-loss bug fixed on phone: the target server's nickname was
                    // never carried over to prefs.serverNickname, so switching kept showing
                    // whatever nickname the OLD primary had.
                    val newNick = server.getOrElse(3) { "" }
                    val primary = prefs.credentials.first()
                    val updated = extraServers.toMutableList()
                    updated[i]  = listOf(primary.serverUrl, primary.username, primary.password,
                        prefs.serverNickname.first())
                    // The provider becoming primary may already have favorites recorded from
                    // when it was a secondary provider — carry them over so they reapply once
                    // its channels are fetched as the new primary.
                    repository.capturePendingPrimaryFavoritesFrom(i)
                    prefs.saveExtraServersWithNick(updated)
                    // Scoped to just the OLD primary's data — merged/other-provider favorites,
                    // folders, and pinned categories must survive a primary switch.
                    repository.clearPrimaryProviderData()
                    prefs.saveCredentials(url, user, pass)
                    prefs.setServerNickname(newNick)
                    prefs.setActiveServerIndex(-1)
                    startActivity(
                        Intent(this@TvSettingsActivity,
                            com.iptvapp.ui.home.HomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddServerDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
        }
        val etNick = EditText(this).apply { hint = "Nickname (optional)" }
        val etUrl  = EditText(this).apply { hint = "Provider URL (http://...)" }
        val etUser = EditText(this).apply { hint = "Username" }
        val etPass = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val cbShowPass = android.widget.CheckBox(this).apply {
            text = "Show password"
            setOnCheckedChangeListener { _, checked ->
                etPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    if (checked) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                etPass.setSelection(etPass.text.length)
            }
        }
        val etEpg = EditText(this).apply {
            hint = "EPG URL (optional, http://...)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val tvTestResult = android.widget.TextView(this).apply {
            textSize = 12f
            setPadding(0, 12, 0, 0)
            visibility = android.view.View.GONE
        }
        val btnTest = android.widget.Button(this).apply {
            text = "Test Connection"
            isAllCaps = false
            textSize = 13f
            setOnClickListener {
                val url = etUrl.text.toString().replace(" ", "").trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                if (url.isBlank() || user.isBlank()) {
                    toast("Enter a URL and username first")
                    return@setOnClickListener
                }
                isEnabled = false
                tvTestResult.visibility = android.view.View.VISIBLE
                tvTestResult.text = "Testing…"
                lifecycleScope.launch {
                    val result = repository.testProviderConnection(url, user, pass)
                    isEnabled = true
                    tvTestResult.text = if (result.reachable) "✓ Connected (${result.responseMs}ms)"
                        else "✗ Failed — ${result.error}"
                }
            }
        }
        layout.addView(etNick); layout.addView(etUrl); layout.addView(etUser); layout.addView(etPass); layout.addView(cbShowPass); layout.addView(etEpg)
        layout.addView(btnTest); layout.addView(tvTestResult)
        AlertDialog.Builder(this)
            .setTitle("Add Provider")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val url  = etUrl.text.toString().replace(" ", "").trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                val nick = etNick.text.toString().trim()
                val epgUrl = etEpg.text.toString().replace(" ", "").trim()
                if (url.isNotEmpty() && user.isNotEmpty()) {
                    lifecycleScope.launch {
                        val fresh = prefs.getExtraServersWithNick().toMutableList()
                        fresh.add(listOf(url, user, pass, nick, epgUrl))
                        extraServers.clear(); extraServers.addAll(fresh)
                        prefs.saveExtraServersWithNick(extraServers)
                        toast("Provider added")
                        rebuildList("server_add")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Account ─────────────────────────────────────────────────────────────

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("This will clear all data and return to the login screen. Continue?")
            .setPositiveButton("Logout") { _, _ ->
                lifecycleScope.launch {
                    try {
                        prefs.clearCredentials()
                        withContext(Dispatchers.IO) { db.clearAllTables() }
                    } catch (_: Exception) {}
                    startActivity(
                        Intent(this@TvSettingsActivity, LoginActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Debug ───────────────────────────────────────────────────────────────

    private fun sendDebugReport() {
        setItemEnabled("backup_debug", false)
        setItemValue("backup_debug", "Collecting info...")
        lifecycleScope.launch {
            try {
                val pInfo     = packageManager.getPackageInfo(packageName, 0)
                val cm        = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps      = cm.getNetworkCapabilities(cm.activeNetwork)
                val netType   = when {
                    caps == null -> "No network"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WiFi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "Unknown"
                }
                val channelCount = try { db.channelDao().getCount() }     catch (_: Exception) { -1 }
                val favCount     = try { db.channelDao().getFavoriteCount() } catch (_: Exception) { -1 }
                val vodCount     = try { db.vodDao().getCount() }          catch (_: Exception) { -1 }
                val seriesCount  = try { db.seriesDao().getCount() }       catch (_: Exception) { -1 }
                val epgCount     = try { db.epgDao().getEpgCount() }       catch (_: Exception) { -1 }
                val format       = prefs.preferredFormat.first()
                val usaOnly      = prefs.usaOnlyChannels.first()
                val serverUrl    = try {
                    prefs.credentials.first().serverUrl.let { u ->
                        java.net.URI(u).let { "${it.host}:${it.port}" }
                    }
                } catch (_: Exception) { "unknown" }
                val lastRefresh  = prefs.lastEpgRefreshTime.first().let { t ->
                    if (t == 0L) "Never"
                    else SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(t))
                }
                val autoH        = prefs.epgAutoRefreshHours.first()
                val missingOnly  = prefs.epgRefreshMissingOnly.first()
                val am           = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memInfo      = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                val ramFree      = "%.1f GB".format(memInfo.availMem / 1e9)
                val ramTotal     = "%.1f GB".format(memInfo.totalMem / 1e9)
                val stat         = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                val storageFree  = "%.1f GB".format(stat.availableBlocksLong * stat.blockSizeLong / 1e9)
                val dm           = resources.displayMetrics
                val screen       = "${dm.widthPixels}x${dm.heightPixels} (${dm.densityDpi}dpi)"
                val epgState     = try {
                    workManager.getWorkInfosForUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME).get()
                        .firstOrNull()?.state?.name ?: "None"
                } catch (_: Exception) { "Unknown" }
                setItemValue("backup_debug", "Reading crash log...")
                // Defense in depth: the crash handler already redacts before writing to disk,
                // but redact again here too in case anything else ever lands in this log.
                val crashLog = LogSanitizer.redactCredentials(IptvApplication.getCrashLog(this@TvSettingsActivity))
                val debugText = """
                    App: v${pInfo.versionName} (${pInfo.longVersionCode})
                    Device: ${Build.MANUFACTURER} ${Build.MODEL}
                    Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                    Screen: $screen  Network: $netType
                    RAM: $ramFree free / $ramTotal total  Storage: $storageFree free
                    Server: $serverUrl
                    Channels: $channelCount | Favorites: $favCount
                    VOD: $vodCount | Series: $seriesCount | EPG: $epgCount
                    Format: $format | USA Only: $usaOnly
                    Last EPG: $lastRefresh | Auto: ${if (autoH == 0) "Off" else "${autoH}h"} | Missing: $missingOnly
                    EPG Worker: $epgState
                """.trimIndent()
                val fullDebug = debugText + "\n\n=== CRASH LOG ===\n" + crashLog
                val reportTitle = "Debug Report — v${pInfo.versionName} — ${Build.MODEL}"
                val discordJson = JSONObject().apply {
                    put("username", "Captain Hook")
                    put("embeds", JSONArray().put(JSONObject().apply {
                        put("title", reportTitle)
                        put("description", "```\n${fullDebug.take(3900)}\n```")
                        put("color", 0xF57C00)
                    }))
                }
                setItemValue("backup_debug", "Sending...")
                val response = withContext(Dispatchers.IO) {
                    OkHttpClient().newCall(
                        Request.Builder()
                            .url(AppConstants.DISCORD_WEBHOOK)
                            .post(discordJson.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()
                }
                if (response.isSuccessful || response.code == 204) {
                    setItemValue("backup_debug", "Report sent ✓")
                    toast("Debug report sent")
                } else {
                    setItemValue("backup_debug", "Failed: ${response.code}")
                }
            } catch (e: Exception) {
                setItemValue("backup_debug", "Error: ${e.message}")
            } finally {
                setItemEnabled("backup_debug", true)
            }
        }
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    private fun showPairingCodeDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. ABC12345"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(32, 16, 32, 16)
            lifecycleScope.launch {
                val existing = prefs.getSyncGistId()
                if (existing.isNotBlank()) setText(existing.take(8).uppercase())
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Enter Pairing Code")
            .setMessage("Enter the 8-character sync code shown on your other device.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val code = input.text.toString().trim()
                lifecycleScope.launch {
                    syncManager.setPairingCode(code)
                    if (code.isNotBlank()) prefs.addSavedPairingCode(code.uppercase())
                    toast(if (code.isBlank()) "Pairing code cleared" else "Paired ✓ — tap Pull from Cloud")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSavedPairingCodesDialog() {
        lifecycleScope.launch {
            val codes = prefs.getSavedPairingCodes()
            if (codes.isEmpty()) {
                toast("No saved codes yet — pair with one first")
                return@launch
            }
            AlertDialog.Builder(this@TvSettingsActivity)
                .setTitle("Saved Pairing Codes")
                .setItems(codes.toTypedArray()) { _, i ->
                    val code = codes[i]
                    lifecycleScope.launch {
                        syncManager.setPairingCode(code)
                        prefs.addSavedPairingCode(code)
                        toast("Paired with $code ✓ — tap Pull from Cloud")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun scheduleAutoSync() {
        val request = PeriodicWorkRequestBuilder<com.iptvapp.worker.SyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build())
            .build()
        workManager.enqueueUniquePeriodicWork(
            com.iptvapp.worker.SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        toast("Auto sync scheduled daily")
    }

    private fun cancelAutoSync() {
        workManager.cancelUniqueWork(com.iptvapp.worker.SyncWorker.WORK_NAME)
    }

    private fun doSyncUp() {
        setItemEnabled("sync_up",   false)
        setItemEnabled("sync_down", false)
        lifecycleScope.launch {
            val result = syncManager.syncUp()
            setItemValue("sync_status", result)
            setItemEnabled("sync_up",   true)
            setItemEnabled("sync_down", true)
            toast(result)
        }
    }

    private fun doSyncDown() {
        setItemEnabled("sync_up",   false)
        setItemEnabled("sync_down", false)
        lifecycleScope.launch {
            val result = syncManager.syncDown()
            setItemValue("sync_status", result)
            setItemEnabled("sync_up",   true)
            setItemEnabled("sync_down", true)
            toast(result)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val AUTO_EPG_WORK = "auto_epg_refresh_work"
    }
}
