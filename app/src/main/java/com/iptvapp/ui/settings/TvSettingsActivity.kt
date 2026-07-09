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
    private var traktAuthJob: kotlinx.coroutines.Job? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.iptvapp.util.ThemeUtils.applyAmoledIfEnabled(binding.root, prefs)
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
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_BACK)) {
            if (activeSection != null) showSectionMenu() else finish()
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
        "Servers" -> "🖥️"
        "Account" -> "👤"
        "Sync" -> "☁️"
        "Trakt" -> "🎬"
        "Subtitles" -> "💬"
        else -> "⚙️"
    }

    private fun showSectionMenu(focusFirst: Boolean = true) {
        activeSection = null
        settingsItems.clear()
        sectionItems.forEach { (title, items) ->
            settingsItems += TvSettingItem.Action("section_$title", "${sectionIcon(title)}   $title") {
                showSection(title)
            }
        }
        adapter.notifyDataSetChanged()
        binding.tvSettingsSectionTitle.text = "Settings"
        if (focusFirst) focusFirstItem()
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

    private fun focusFirstItem() {
        binding.rvTvSettings.scrollToPosition(0)
        binding.rvTvSettings.post {
            for (i in 0 until binding.rvTvSettings.childCount) {
                val child = binding.rvTvSettings.getChildAt(i)
                if (child?.isFocusable == true) { child.requestFocus(); break }
            }
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
            subtitle = "Enable extra buffering for all servers by default — trades a slower start/seek for fewer mid-playback stalls. On by default.",
            checked = prefs.extraBufferingEnabled.first()) { c -> lifecycleScope.launch { prefs.setExtraBufferingEnabled(c) } }

        // ── DISPLAY ──
        currentAccentColorHex = prefs.accentColor.first()
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
        settingsItems += TvSettingItem.Toggle("display_usa", "USA Channels Only",
            checked = usaOnly) { c -> lifecycleScope.launch { prefs.setUsaOnlyChannels(c) } }
        settingsItems += TvSettingItem.Toggle("display_movies", "Show Movies Tab",
            checked = showMovies) { c -> lifecycleScope.launch { prefs.setShowMovies(c) } }
        settingsItems += TvSettingItem.Toggle("display_series", "Show Series Tab",
            checked = showSeries) { c -> lifecycleScope.launch { prefs.setShowSeries(c) } }

        // ── EPG ──
        settingsItems += TvSettingItem.Header("EPG")
        settingsItems += TvSettingItem.SubHeader("epg_sub_sources", "Sources") { toggleSubHeader("EPG", "epg_sub_sources") }
        if (epgUrls.isEmpty()) {
            settingsItems += TvSettingItem.Info("epg_no_sources", "No EPG sources configured")
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
                settingsItems += TvSettingItem.Action("trakt_disconnect", "Disconnect Trakt", danger = true) {
                    lifecycleScope.launch { traktManager.disconnect(); buildSettingsList(); showSection("Trakt", focusFirst = false) }
                }
            } else {
                settingsItems += TvSettingItem.Action("trakt_connect", "Connect to Trakt") { showTraktConnectDialog() }
            }
        }

        // ── BACKUP ──
        settingsItems += TvSettingItem.Header("Backup & Restore")
        settingsItems += TvSettingItem.Action("backup_qr", "Generate Backup QR Code") {
            lifecycleScope.launch { doQrBackup() }
        }
        settingsItems += TvSettingItem.Action("backup_debug", "Send Debug Report") { sendDebugReport() }

        // ── SERVERS ──
        settingsItems += TvSettingItem.Header("Servers")
        val primaryActive = activeIdx == -1
        settingsItems += TvSettingItem.Info("server_primary",
            "${if (primaryActive) "●  " else ""}$primaryNick  •  ${creds.serverUrl.take(50).ifBlank { "Not set" }}")
        extraServers.forEachIndexed { i, server ->
            val nick = server.getOrElse(3) { "" }.ifEmpty { server.getOrElse(1) { "Server ${i + 2}" } }
            val isActive = activeIdx == i
            settingsItems += TvSettingItem.Action("server_$i",
                "${if (isActive) "●  " else ""}$nick",
                value = server.getOrElse(0) { "" }.take(45)) { switchToServer(i) }
        }
        settingsItems += TvSettingItem.Action("server_add", "Add Server") { showAddServerDialog() }

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
        settingsItems += TvSettingItem.SubHeader("sync_sub_actions", "Actions") { toggleSubHeader("Sync", "sync_sub_actions") }
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

    private fun switchToServer(i: Int) {
        AlertDialog.Builder(this)
            .setTitle("Switch Server")
            .setMessage("Switch to this server? Local data will be cleared and the app will restart.")
            .setPositiveButton("Switch") { _, _ ->
                lifecycleScope.launch {
                    val server  = extraServers[i]
                    val url     = server[0]; val user = server[1]; val pass = server[2]
                    val primary = prefs.credentials.first()
                    val updated = extraServers.toMutableList()
                    updated[i]  = listOf(primary.serverUrl, primary.username, primary.password,
                        prefs.serverNickname.first())
                    prefs.saveExtraServersWithNick(updated)
                    withContext(Dispatchers.IO) { db.clearAllTables() }
                    prefs.saveCredentials(url, user, pass)
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
        val etUrl  = EditText(this).apply { hint = "Server URL (http://...)" }
        val etUser = EditText(this).apply { hint = "Username" }
        val etPass = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etNick); layout.addView(etUrl); layout.addView(etUser); layout.addView(etPass)
        AlertDialog.Builder(this)
            .setTitle("Add Server")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val url  = etUrl.text.toString().trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                val nick = etNick.text.toString().trim()
                if (url.isNotEmpty() && user.isNotEmpty()) {
                    lifecycleScope.launch {
                        val fresh = prefs.getExtraServersWithNick().toMutableList()
                        fresh.add(listOf(url, user, pass, nick))
                        extraServers.clear(); extraServers.addAll(fresh)
                        prefs.saveExtraServersWithNick(extraServers)
                        toast("Server added")
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
                    toast(if (code.isBlank()) "Pairing code cleared" else "Paired ✓ — tap Pull from Cloud")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
