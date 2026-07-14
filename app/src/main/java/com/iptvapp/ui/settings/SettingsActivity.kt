package com.iptvapp.ui.settings
import com.iptvapp.BuildConfig

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.iptvapp.AppConstants
import com.iptvapp.IptvApplication
import com.iptvapp.util.LogSanitizer
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ActivitySettingsBinding
import com.iptvapp.ui.onboarding.FeatureTourDialog
import com.iptvapp.worker.AutoBackupWorker
import com.iptvapp.worker.EpgRefreshWorker
import androidx.work.Constraints
import androidx.work.NetworkType
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
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var workManager: WorkManager
    private var currentEpgWorkId: UUID? = null
    private var isLoadingSettings = false
    private var currentPanelIndex = 0

    private val panelViews get() = listOf(
        binding.sectionStream, binding.sectionDisplay, binding.sectionUpdates,
        binding.sectionBackup, binding.sectionServers, binding.sectionSync
    )
    private val navButtonViews get() = listOf(
        binding.headerStream, binding.headerDisplay, binding.headerUpdates,
        binding.headerBackup, binding.headerServers, binding.headerSync
    )

    // Explains what each control in the currently-open section actually does — many of these
    // toggles (Tunneled Playback, DV7 fallback, DoH, Extra Buffering...) are meaningful but
    // not self-explanatory to someone who isn't already familiar with streaming internals.
    private val sectionHelp = listOf(
        // 0: Stream & EPG
        "Stream Format (TS/M3U8): which container the provider serves live channels in. TS "
            + "is the primary format; M3U8 is used as a fallback if TS fails.\n\n"
            + "Video Player: which app actually plays streams — the built-in player, an "
            + "installed external player (VLC/MX Player), or asking each time.\n\n"
            + "EPG Refresh / Auto Refresh Schedule: how often the program guide data is "
            + "re-downloaded from your provider in the background.\n\n"
            + "Refresh only channels missing guide data: skips channels that already have "
            + "EPG data, making refreshes faster.\n\n"
            + "Server Speed Test: measures how fast your current server responds, to help "
            + "compare multiple servers if you have them.\n\n"
            + "DNS over HTTPS (DoH): encrypts DNS lookups so your ISP can't see (or throttle "
            + "based on) which streaming domains you're connecting to.\n\n"
            + "Global Extra Buffering: builds up a bigger buffer before playback starts, "
            + "trading a slower start for fewer stalls mid-stream on slow/unreliable "
            + "connections. On by default.\n\n"
            + "Show USA Channels Only / English Movies & Series Only: filters live channels "
            + "or VOD/series to just those tagged for that country/language by your "
            + "provider — depends entirely on your provider's own naming, so may not work "
            + "for every server.",
        // 1: Display
        "Show Movies/Series/Watching Tab: hides tabs you don't use to declutter the home "
            + "screen — the content itself isn't deleted, just the tab.\n\n"
            + "Accent Color: the highlight color used for selected tabs, buttons, and "
            + "progress bars throughout the app.\n\n"
            + "AMOLED Black: forces pure black backgrounds everywhere instead of dark gray — "
            + "saves battery on OLED screens and looks better in a dark room. Requires an "
            + "app restart to fully apply everywhere.",
        // 2: Updates
        "Check for Updates: manually checks for a new version right now instead of waiting "
            + "for the automatic background check.\n\n"
            + "What's New / Changelog: shows what changed in the current and past versions.\n\n"
            + "Silent Self-Update: on Android 12+, skips the \"Install update?\" confirmation "
            + "screen when the OS allows it, updating more seamlessly. Off by default since "
            + "it bypasses a normal Android security prompt — only turn this on if you're "
            + "comfortable with that.",
        // 3: Backup & Restore
        "Backup / Restore: saves your server login, favorites, and settings to a file you "
            + "choose (Downloads, Drive, USB, etc.), or loads them back in — useful before "
            + "a factory reset or when moving to a new device. Nothing is uploaded "
            + "anywhere automatically; you pick the exact file location yourself.\n\n"
            + "Auto backup (weekly): does the same backup automatically once a week to that "
            + "same chosen location, without you having to remember to do it manually.",
        // 4: Servers
        "Add, edit, or switch between multiple Xtream server logins if you have more than "
            + "one IPTV subscription — only one is active for playback at a time, but you "
            + "can switch instantly without re-entering credentials.",
        // 5: Sync
        "Cross-Device Sync: keeps your favorites and watch history in sync across your "
            + "devices via a private Firebase-backed store — nothing public, only devices "
            + "using the same Pairing Code see each other's data.\n\n"
            + "Push to Cloud / Pull from Cloud: manually send this device's data up, or pull "
            + "another device's data down, instead of waiting for automatic sync.\n\n"
            + "Pairing Code: the code that links devices together — enter the same code on "
            + "every device you want kept in sync.\n\n"
            + "Send Debug Report: uploads device info and recent playback error logs so a "
            + "problem can be diagnosed — no account credentials are included.\n\n"
            + "Connect Trakt: links your Trakt.tv account so movies and TV episodes you watch "
            + "in fullscreen are automatically tracked there — check trakt.tv itself (Currently "
            + "Watching / History) to confirm it's working, since there's no local record in "
            + "this app. Only counts something as \"watched\" once you've watched past "
            + "roughly 80% of it, same as Trakt does everywhere else. Only tracks fullscreen "
            + "playback, not the mini player."
    )

    private fun showSettingsHelp() {
        val idx = currentPanelIndex.coerceIn(sectionHelp.indices)
        val sectionName = navButtonViews.getOrNull(idx)?.text?.toString() ?: "Settings"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("$sectionName — What do these do?")
            .setMessage(sectionHelp[idx])
            .setPositiveButton("Got it", null)
            .show()
    }

    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var db: IptvDatabase
    @Inject lateinit var repository: com.iptvapp.data.repository.XtreamRepository
    @Inject lateinit var syncManager: com.iptvapp.sync.SyncManager
    @Inject lateinit var traktManager: com.iptvapp.trakt.TraktManager
    private var traktAuthJob: kotlinx.coroutines.Job? = null

    // The backup file contains the account's plaintext username/password. Rather than
    // auto-writing it to a fixed public location any app with storage/media permissions
    // could read, the user explicitly picks the destination/source via the system file
    // picker — still fully portable (Downloads, Drive, USB, wherever they choose), just not
    // silently exposed to every other app on the device by default.
    private val createBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch { writeBackupToUri(uri) }
    }
    private val openBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { restoreBackupFromUri(uri) }
    }

    private val sortLabels = listOf("Default", "A-Z", "Popular", "Recent")
    private var currentSortIndex = 0

    private val accentPalette = listOf(
        "#008CFF", "#FF3B30", "#34C759", "#AF52DE", "#FF9500", "#FF2D55", "#5AC8FA"
    )
    private var currentAccentColor = "#008CFF"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.iptvapp.util.ThemeUtils.applyAmoledIfEnabled(binding.root, prefs)
        workManager = WorkManager.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSettingsHelp.setOnClickListener { showSettingsHelp() }

        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("This will clear all data and return to the login screen. Continue?")
                .setPositiveButton("Logout") { _, _ ->
                    lifecycleScope.launch {
                        try { repository.logout() } catch (_: Exception) {}
                        val intent = Intent(this@SettingsActivity, com.iptvapp.ui.login.LoginActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnNavRefreshEpg.setOnClickListener { startEpgRefresh() }
        binding.btnNavCheckUpdate.setOnClickListener { checkForUpdate() }

        binding.btnWhatsNew.setOnClickListener { showChangelog() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }

        // Quick Actions — sort cycles, others launch intents back to home
        binding.btnSettingsSort.setOnClickListener {
            currentSortIndex = (currentSortIndex + 1) % sortLabels.size
            binding.btnSettingsSort.text = "⇅  Sort Channels: ${sortLabels[currentSortIndex]}"
            lifecycleScope.launch { prefs.setChannelSortMode(currentSortIndex) }
        }
        binding.btnSettingsMosaic.setOnClickListener {
            startActivity(Intent(this, com.iptvapp.ui.mosaic.MosaicActivity::class.java))
        }
        binding.btnFeatureTour.setOnClickListener {
            FeatureTourDialog.show(this)
        }

        binding.btnSaveEpg.setOnClickListener {
            lifecycleScope.launch {
                val url = binding.etEpgUrl.text.toString().trim()
                prefs.setEpgUrl(url)
                Toast.makeText(this@SettingsActivity, "EPG URL saved", Toast.LENGTH_SHORT).show()
                binding.cbUseDefaultUsEpg.isChecked = (url == com.iptvapp.AppConstants.DEFAULT_US_EPG_URL)
            }
        }
        binding.cbUseDefaultUsEpg.setOnCheckedChangeListener { _, checked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            val url = if (checked) com.iptvapp.AppConstants.DEFAULT_US_EPG_URL else ""
            binding.etEpgUrl.setText(url)
            lifecycleScope.launch {
                prefs.setEpgUrl(url)
                Toast.makeText(
                    this@SettingsActivity,
                    if (checked) "Default US guide set — tap Refresh EPG to load it" else "EPG URL cleared",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnSpeedTest.setOnClickListener { lifecycleScope.launch { runSpeedTest() } }

        binding.cbDohEnabled.setOnCheckedChangeListener { _, checked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setDohEnabled(checked) }
            binding.rgDohProvider.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }
        binding.rgDohProvider.setOnCheckedChangeListener { _, checkedId ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            val provider = when (checkedId) {
                R.id.rbDohGoogle -> "google"
                R.id.rbDohNextDns -> "nextdns"
                else -> "cloudflare"
            }
            lifecycleScope.launch { prefs.setDohProvider(provider) }
        }

        binding.btnRefreshEpg.setOnClickListener { startEpgRefresh() }

        binding.btnCancelEpgRefresh.setOnClickListener {
            workManager.cancelUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME)
            binding.tvEpgRefreshStatus.text = "Cancelled"
            binding.btnRefreshEpg.isEnabled = true
            binding.btnCancelEpgRefresh.visibility = View.GONE
        }

        binding.cbRefreshMissingOnly.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setEpgRefreshMissingOnly(isChecked) }
        }

        binding.cbUsaOnlyChannels.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setUsaOnlyChannels(isChecked) }
        }

        binding.cbEnglishOnlyMovies.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setEnglishOnlyMovies(isChecked) }
        }

        binding.cbAmoledBlack.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setAmoledBlack(isChecked) }
            Toast.makeText(this, "Restart the app for AMOLED Black to fully apply", Toast.LENGTH_LONG).show()
        }

        binding.switchSilentSelfUpdate.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setSilentSelfUpdateEnabled(isChecked) }
        }

        binding.cbShowMovies.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setShowMovies(isChecked) }
        }

        binding.cbShowSeries.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setShowSeries(isChecked) }
        }

        binding.cbShowWatching.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setShowWatching(isChecked) }
        }

        binding.btnRefreshMovies.setOnClickListener {
            binding.btnRefreshMovies.isEnabled = false
            binding.btnRefreshMovies.text = "Loading…"
            lifecycleScope.launch {
                repository.fetchVodCategories()
                val result = repository.fetchVodStreams()
                binding.btnRefreshMovies.isEnabled = true
                binding.btnRefreshMovies.text = "↻ Refresh"
                val msg = if (result is com.iptvapp.util.Resource.Success)
                    "Movies refreshed (${result.data?.size ?: 0} titles)"
                else
                    "Failed — server timeout or no content"
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnRefreshSeries.setOnClickListener {
            binding.btnRefreshSeries.isEnabled = false
            binding.btnRefreshSeries.text = "Loading…"
            lifecycleScope.launch {
                val result = repository.fetchSeries()
                binding.btnRefreshSeries.isEnabled = true
                binding.btnRefreshSeries.text = "↻ Refresh"
                val msg = if (result is com.iptvapp.util.Resource.Success)
                    "Series refreshed (${result.data?.size ?: 0} titles)"
                else
                    "Failed — server timeout or no content"
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        binding.rgAutoEpgRefresh.setOnCheckedChangeListener { _, checkedId ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                val hours = when (checkedId) {
                    binding.rbAuto6.id -> 6
                    binding.rbAuto12.id -> 12
                    binding.rbAuto24.id -> 24
                    else -> 0
                }
                prefs.setEpgAutoRefreshHours(hours)
                scheduleAutoEpgRefresh(hours)
                val msg = if (hours == 0) "Auto EPG refresh off" else "Auto EPG refresh every $hours hours"
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.rgFormat.setOnCheckedChangeListener { _, checkedId ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                val format = when (checkedId) {
                    binding.rbTs.id -> "ts"
                    else -> "m3u8"
                }
                prefs.setPreferredFormat(format)
                Toast.makeText(this@SettingsActivity, "Format set to $format", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rgPlayer.setOnCheckedChangeListener { _, checkedId ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                val player = when (checkedId) {
                    binding.rbPlayerVlc.id    -> "vlc"
                    binding.rbPlayerMx.id     -> "mxplayer"
                    binding.rbPlayerSystem.id -> "system"
                    else                      -> "internal"
                }
                prefs.setExternalPlayer(player)
                val label = when (player) {
                    "vlc"      -> "VLC"
                    "mxplayer" -> "MX Player"
                    "system"   -> "System chooser"
                    else       -> "Built-in player"
                }
                Toast.makeText(this@SettingsActivity, "Player: $label", Toast.LENGTH_SHORT).show()
            }
        }

        setupSectionToggles()
        setupBackupRestore()
        setupServers()
        setupSyncSection()
        setupTraktSection()
        binding.btnOpenMosaic.setOnClickListener {
            startActivity(Intent(this, com.iptvapp.ui.mosaic.MosaicActivity::class.java))
        }
        observeEpgRefreshWork()
        loadSettings()
    }

    private fun setupSectionToggles() {
        fun selectPanel(index: Int) {
            currentPanelIndex = index
            panelViews.forEachIndexed { i, panel ->
                panel.visibility = if (i == index) View.VISIBLE else View.GONE
            }
            navButtonViews.forEachIndexed { i, btn ->
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (i == index) Color.parseColor("#1A3A5C") else Color.parseColor("#1A1A1A")
                )
                btn.setTextColor(
                    if (i == index) Color.parseColor(currentAccentColor) else Color.parseColor("#AAAAAA")
                )
            }
        }
        navButtonViews.forEachIndexed { i, btn -> btn.setOnClickListener { selectPanel(i) } }
        binding.headerRecordings.setOnClickListener {
            startActivity(Intent(this, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java))
        }
        selectPanel(0)
        setupCollapsibleCards()
    }

    private fun setupAccentPicker() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val row = binding.accentColorRow
        row.removeAllViews()
        accentPalette.forEachIndexed { i, hex ->
            val outer = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    if (i > 0) marginStart = dp(8)
                }
            }
            val swatch = View(this).apply {
                val gd = android.graphics.drawable.GradientDrawable()
                gd.shape = android.graphics.drawable.GradientDrawable.OVAL
                gd.setColor(Color.parseColor(hex))
                background = gd
                tag = hex
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(32), dp(32)).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            val ring = View(this).apply {
                val gd = android.graphics.drawable.GradientDrawable()
                gd.shape = android.graphics.drawable.GradientDrawable.OVAL
                gd.setStroke(dp(2), Color.WHITE)
                gd.setColor(Color.TRANSPARENT)
                background = gd
                visibility = if (hex == currentAccentColor) View.VISIBLE else View.GONE
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            outer.addView(swatch)
            outer.addView(ring)
            outer.setOnClickListener {
                currentAccentColor = hex
                for (j in 0 until row.childCount) {
                    val child = row.getChildAt(j) as? android.widget.FrameLayout ?: continue
                    child.getChildAt(1)?.visibility = if (child.getChildAt(0)?.tag == hex) View.VISIBLE else View.GONE
                }
                lifecycleScope.launch { prefs.setAccentColor(hex) }
                applyAccentToSettings(Color.parseColor(hex))
            }
            row.addView(outer)
        }
    }

    private fun applyAccentToSettings(colorInt: Int) {
        navButtonViews.forEachIndexed { i, btn ->
            btn.setTextColor(if (i == currentPanelIndex) colorInt else Color.parseColor("#AAAAAA"))
        }
    }

    private fun wireCollapsible(headerId: Int, bodyId: Int, chevronId: Int) {
        val header  = findViewById<View>(headerId)   ?: return
        val body    = findViewById<View>(bodyId)     ?: return
        val chevron = findViewById<android.widget.TextView>(chevronId) ?: return
        header.setOnClickListener {
            val expanding = body.visibility == View.GONE
            body.visibility = if (expanding) View.VISIBLE else View.GONE
            chevron.text    = if (expanding) "▲" else "▼"
        }
    }

    private fun setupCollapsibleCards() {
        wireCollapsible(R.id.hdrEpgUrl,      R.id.bodyEpgUrl,      R.id.chevEpgUrl)
        wireCollapsible(R.id.hdrFormat,      R.id.bodyFormat,      R.id.chevFormat)
        wireCollapsible(R.id.hdrPlayer,      R.id.bodyPlayer,      R.id.chevPlayer)
        wireCollapsible(R.id.hdrEpgSection,  R.id.bodyEpgSection,  R.id.chevEpgSection)
        wireCollapsible(R.id.hdrSpeedTest,   R.id.bodySpeedTest,   R.id.chevSpeedTest)
        wireCollapsible(R.id.hdrDoh,         R.id.bodyDoh,         R.id.chevDoh)
        wireCollapsible(R.id.hdrChannelsTabs,     R.id.bodyChannelsTabs,     R.id.chevChannelsTabs)
        wireCollapsible(R.id.hdrAccentColor,      R.id.bodyAccentColor,      R.id.chevAccentColor)
        wireCollapsible(R.id.hdrQuickActions,     R.id.bodyQuickActions,     R.id.chevQuickActions)
        wireCollapsible(R.id.hdrUpdates,          R.id.bodyUpdates,          R.id.chevUpdates)
        wireCollapsible(R.id.hdrCrossDeviceSync,  R.id.bodyCrossDeviceSync,  R.id.chevCrossDeviceSync)
        wireCollapsible(R.id.hdrDiagnostics,      R.id.bodyDiagnostics,      R.id.chevDiagnostics)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val inContent = panelViews[currentPanelIndex].hasFocus()
            val inNav = binding.settingsNavRail.hasFocus()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (inContent) {
                    navButtonViews[currentPanelIndex].requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (inNav) {
                    focusFirstInCurrentPanel()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> if (inContent) {
                    navButtonViews[currentPanelIndex].requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun focusFirstInCurrentPanel() {
        val panel = panelViews[currentPanelIndex]
        val first = firstFocusableIn(panel)
        if (first != null) first.requestFocus() else panel.requestFocus()
    }

    private fun firstFocusableIn(view: View): View? {
        if (view !is ViewGroup && view.isFocusable && view.isEnabled && view.visibility == View.VISIBLE) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                firstFocusableIn(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun setupBackupRestore() {
        binding.btnBackupSettings.setOnClickListener { backupSettings() }
        binding.btnRestoreSettings.setOnClickListener { showRestoreDialog() }
        binding.btnSendDebugReport.setOnClickListener { sendDebugReport() }
        binding.btnProviderHealth.setOnClickListener { showProviderHealthDialog() }
        binding.btnManageBackups.setOnClickListener { showManageBackupsDialog() }

        lifecycleScope.launch {
            val enabled = prefs.autoBackupEnabled.first()
            binding.switchAutoBackup.isChecked = enabled
            updateAutoBackupPathLabel(enabled)
        }
        binding.switchAutoBackup.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                prefs.setAutoBackupEnabled(isChecked)
                if (isChecked) scheduleAutoBackup() else cancelAutoBackup()
                updateAutoBackupPathLabel(isChecked)
            }
        }
    }

    private fun updateAutoBackupPathLabel(enabled: Boolean) {
        binding.tvAutoBackupPath.text = if (enabled) "Saved privately to app storage (weekly)" else ""
    }

    private fun scheduleAutoBackup() {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            AutoBackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Toast.makeText(this, "Auto backup scheduled weekly", Toast.LENGTH_SHORT).show()
    }

    private fun cancelAutoBackup() {
        WorkManager.getInstance(this).cancelUniqueWork(AutoBackupWorker.WORK_NAME)
        Toast.makeText(this, "Auto backup disabled", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleAutoSync() {
        val request = PeriodicWorkRequestBuilder<com.iptvapp.worker.SyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            com.iptvapp.worker.SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Toast.makeText(this, "Auto sync scheduled daily", Toast.LENGTH_SHORT).show()
    }

    private fun cancelAutoSync() {
        WorkManager.getInstance(this).cancelUniqueWork(com.iptvapp.worker.SyncWorker.WORK_NAME)
    }

    private fun backupSettings() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        createBackupLauncher.launch("MKTV_backup_$timestamp.json")
    }

    /** Same private, app-only folder AutoBackupWorker writes weekly snapshots into —
     * keeping manual quick-backups alongside them means one list shows the full history. */
    private fun privateBackupsDir(): File =
        File(getExternalFilesDir(null), "backups").apply { mkdirs() }

    private suspend fun quickBackupNow() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(privateBackupsDir(), "MKTV_backup_$timestamp.json")
            val body = buildBackupJson().toString(2)
            withContext(Dispatchers.IO) { file.writeText(body) }
            Toast.makeText(this, "Backup saved on this device", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
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

    private fun showBackupFileActionDialog(file: File) {
        val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        AlertDialog.Builder(this)
            .setTitle(dateFmt.format(Date(file.lastModified())))
            .setItems(arrayOf("Restore this backup", "Share / export a copy", "Delete")) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this)
                        .setTitle("Restore this backup?")
                        .setMessage("This will overwrite your current login, favorites, and settings.")
                        .setPositiveButton("Restore") { _, _ -> lifecycleScope.launch { restoreBackupFromFile(file) } }
                        .setNegativeButton("Cancel", null)
                        .show()
                    1 -> shareBackupFile(file)
                    2 -> {
                        file.delete()
                        Toast.makeText(this, "Backup deleted", Toast.LENGTH_SHORT).show()
                        showManageBackupsDialog()
                    }
                }
            }
            .show()
    }

    private fun shareBackupFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share backup"))
    }

    private suspend fun writeBackupToUri(uri: Uri) {
        val body = buildBackupJson().toString(2)
        try {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                    ?: throw IllegalStateException("Could not open output stream")
            }
            binding.tvBackupStatus.text = "✓ Backup saved"
            Toast.makeText(this, "Backup saved", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showProviderHealthDialog() {
        lifecycleScope.launch {
            val report = com.iptvapp.util.ProviderHealth.build(this@SettingsActivity, db, prefs)
            val builder = AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Provider Health")
                .setMessage(com.iptvapp.util.ProviderHealth.formatReport(report))
                .setPositiveButton("Close", null)
            if (report.worstChannels.isNotEmpty()) {
                builder.setNeutralButton("Least Reliable Channels") { _, _ ->
                    showWorstChannelsDialog(report.worstChannels)
                }
            }
            builder.show()
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
                        startActivity(Intent(this, com.iptvapp.ui.home.HomeActivity::class.java).apply {
                            putExtra(com.iptvapp.ui.home.HomeActivity.EXTRA_JUMP_TO_STREAM_ID, channel.streamId)
                        })
                        finish()
                    }
                    1 -> lifecycleScope.launch {
                        db.channelDao().setHidden(channel.streamId, true)
                        Toast.makeText(this@SettingsActivity, "${channel.name} hidden", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun sendDebugReport() {
        binding.btnSendDebugReport.isEnabled = false
        binding.btnSendDebugReport.text = "Collecting..."
        binding.tvReportStatus.text = "Collecting device info..."
        lifecycleScope.launch {
            try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                val netType = when {
                    caps == null -> "No network"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "Unknown"
                }
                val channelCount = try { db.channelDao().getCount() } catch (_: Exception) { -1 }
                val favCount = try { db.channelDao().getFavoriteCount() } catch (_: Exception) { -1 }
                val vodCount = try { db.vodDao().getCount() } catch (_: Exception) { -1 }
                val seriesCount = try { db.seriesDao().getCount() } catch (_: Exception) { -1 }
                val epgCount = try { db.epgDao().getEpgCount() } catch (_: Exception) { -1 }
                val format = prefs.preferredFormat.first()
                val usaOnly = prefs.usaOnlyChannels.first()
                val serverUrl = try {
                    prefs.credentials.first().serverUrl.let { url ->
                        java.net.URI(url).let { "${it.host}:${it.port}" }
                    }
                } catch (_: Exception) { "unknown" }
                val lastRefresh = prefs.lastEpgRefreshTime.first().let { t ->
                    if (t == 0L) "Never"
                    else SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(t))
                }
                val autoRefreshHours = prefs.epgAutoRefreshHours.first()
                val autoRefreshStr = if (autoRefreshHours == 0) "Off" else "Every ${autoRefreshHours}h"
                val missingOnly = prefs.epgRefreshMissingOnly.first()
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                val ramFree = "%.1f GB".format(memInfo.availMem / 1e9)
                val ramTotal = "%.1f GB".format(memInfo.totalMem / 1e9)
                val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                val storageFree = "%.1f GB".format(stat.availableBlocksLong * stat.blockSizeLong / 1e9)
                val dm = resources.displayMetrics
                val screen = "${dm.widthPixels}x${dm.heightPixels} (${dm.densityDpi}dpi)"
                val epgWorkState = try {
                    WorkManager.getInstance(this@SettingsActivity)
                        .getWorkInfosForUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME).get()
                        .firstOrNull()?.state?.name ?: "None"
                } catch (_: Exception) { "Unknown" }
                binding.tvReportStatus.text = "Reading crash log & sending..."
                // Defense in depth: the crash handler already redacts before writing to disk,
                // but redact again here too in case anything else ever lands in this log.
                val crashLog = LogSanitizer.redactCredentials(IptvApplication.getCrashLog(this@SettingsActivity))
                val debugText = """
                    App: v${pInfo.versionName} (${pInfo.longVersionCode})
                    Device: ${Build.MANUFACTURER} ${Build.MODEL}
                    Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                    Screen: $screen
                    Network: $netType
                    RAM: $ramFree free / $ramTotal total
                    Storage: $storageFree free
                    Server: $serverUrl
                    Channels: $channelCount | Favorites: $favCount
                    VOD: $vodCount | Series: $seriesCount | EPG: $epgCount
                    Format: $format | USA Only: $usaOnly
                    Last EPG Refresh: $lastRefresh
                    Auto-refresh: $autoRefreshStr | Missing-only: $missingOnly
                    EPG Worker: $epgWorkState
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
                binding.tvReportStatus.text = "Sending report..."
                val response = withContext(Dispatchers.IO) {
                    OkHttpClient().newCall(
                        Request.Builder()
                            .url(AppConstants.DISCORD_WEBHOOK)
                            .post(discordJson.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()
                }
                if (response.isSuccessful || response.code == 204) {
                    binding.tvReportStatus.text = "✓ Report sent"
                } else {
                    binding.tvReportStatus.text = "Send failed (HTTP ${response.code})"
                }
            } catch (e: Exception) {
                binding.tvReportStatus.text = "Error: ${e.message}"
            } finally {
                binding.btnSendDebugReport.text = "Send Debug Report"
                binding.btnSendDebugReport.isEnabled = true
            }
        }
    }

    private fun checkForUpdate() {
        binding.tvUpdateStatus.text = "Checking..."
        binding.btnCheckUpdate.isEnabled = false
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    URL("https://raw.githubusercontent.com/Oliver29Klozoff/IPTV-Mj/main/version.json").readText()
                }
                val obj = JSONObject(json)
                val latestCode = obj.getInt("versionCode")
                val latestName = obj.getString("versionName")
                val apkUrl = obj.getString("apkUrl")
                val apkSha256 = obj.optString("apkSha256", "").takeIf { it.isNotBlank() }
                val installedCode = packageManager.getPackageInfo(packageName, 0).longVersionCode
                if (latestCode > installedCode) {
                    val changelog = buildString {
                        val arr = obj.optJSONArray("changelog")
                        if (arr != null) for (i in 0 until arr.length()) append("• ${arr.getString(i)}\n")
                    }.trimEnd()
                    binding.tvUpdateStatus.text = "v$latestName available"
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("MKTV $latestName Available")
                        .setMessage("What's new:\n\n$changelog")
                        .setPositiveButton("Update now") { _, _ -> downloadAndInstall(apkUrl, latestName, apkSha256) }
                        .setNegativeButton("Later", null)
                        .show()
                } else {
                    binding.tvUpdateStatus.text = "✓ Up to date (v$latestName)"
                }
            } catch (e: Exception) {
                binding.tvUpdateStatus.text = "Check failed — ${e.message}"
            } finally {
                binding.btnCheckUpdate.isEnabled = true
            }
        }
    }

    private fun resolveRedirect(url: String): String {
        var connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connect()
        var finalUrl = url
        while (connection.responseCode in 300..399) {
            finalUrl = connection.getHeaderField("Location")
            connection = java.net.URL(finalUrl).openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connect()
        }
        connection.disconnect()
        return finalUrl
    }

    private fun downloadAndInstall(apkUrl: String, versionName: String, expectedSha256: String?) {
        binding.tvUpdateStatus.text = "Resolving download URL..."
        lifecycleScope.launch {
            val resolvedUrl = withContext(Dispatchers.IO) { resolveRedirect(apkUrl) }
            downloadFromUrl(resolvedUrl, versionName, expectedSha256)
        }
    }

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun downloadFromUrl(apkUrl: String, versionName: String, expectedSha256: String?) {
        binding.tvUpdateStatus.text = "Downloading v$versionName..."
        val fileName = "MKTV-update-$versionName.apk"
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) file.delete()
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("MKTV Update v$versionName")
            .setDescription("Downloading update...")
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)
        val installTriggered = java.util.concurrent.atomic.AtomicBoolean(false)
        val progressHandler = Handler(Looper.getMainLooper())
        val progressRunnable = object : Runnable {
            override fun run() {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt()
                        binding.tvUpdateStatus.text = "Downloading... $pct%"
                    }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        progressHandler.removeCallbacks(this)
                        if (installTriggered.compareAndSet(false, true)) {
                            binding.tvUpdateStatus.text = "Download complete — verifying..."
                            verifyAndInstall(file, expectedSha256)
                        }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        progressHandler.removeCallbacks(this)
                        binding.tvUpdateStatus.text = "Download failed"
                    } else {
                        progressHandler.postDelayed(this, 500)
                    }
                }
                cursor.close()
            }
        }
        progressHandler.post(progressRunnable)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    unregisterReceiver(this)
                    progressHandler.removeCallbacks(progressRunnable)
                    if (installTriggered.compareAndSet(false, true)) {
                        binding.tvUpdateStatus.text = "Download complete — verifying..."
                        verifyAndInstall(file, expectedSha256)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun verifyAndInstall(file: File, expectedSha256: String?) {
        if (expectedSha256 == null) {
            installApk(file)
            return
        }
        lifecycleScope.launch {
            val actual = withContext(Dispatchers.IO) { sha256Of(file) }
            if (actual.equals(expectedSha256, ignoreCase = true)) {
                installApk(file)
            } else {
                file.delete()
                binding.tvUpdateStatus.text = "Update verification failed — download discarded"
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Update Verification Failed")
                    .setMessage("The downloaded update did not match the expected checksum and was discarded for your safety. Please try again or check your network.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun installApk(file: File) {
        val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
        if (!canInstall) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            })
            binding.tvUpdateStatus.text = "Allow installs from unknown sources, then retry"
            return
        }
        // On Android 12+ (API 31), PackageInstaller.Session with setRequireUserAction(false)
        // lets an app that is already the "installer of record" for itself update silently —
        // no confirmation dialog, no Play Protect scan interstitial. First-ever installs (or
        // any app that isn't the installer of record) still get STATUS_PENDING_USER_ACTION,
        // which we handle by launching the confirmation intent Android hands back — so this
        // naturally degrades to the old visible-install behavior when silent isn't allowed.
        // Opt-in only (off by default) — this skips a user-facing OS security gate, so it
        // must be a deliberate choice, not a default.
        val silentUpdateEnabled = kotlinx.coroutines.runBlocking { prefs.silentSelfUpdateEnabled.first() }
        if (silentUpdateEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            com.iptvapp.IptvApplication.logPlaybackEvent(applicationContext, "SILENT UPDATE: attempting session install (sdk=${Build.VERSION.SDK_INT})")
            try {
                installApkViaSession(file)
                return
            } catch (e: Exception) {
                com.iptvapp.IptvApplication.logPlaybackEvent(applicationContext, "SILENT UPDATE: session install threw ${e.javaClass.simpleName}: ${e.message} — falling back")
                Log.e("SettingsActivity", "Session install failed, falling back: ${e.message}")
            }
        } else if (silentUpdateEnabled) {
            com.iptvapp.IptvApplication.logPlaybackEvent(applicationContext, "SILENT UPDATE: enabled but sdk=${Build.VERSION.SDK_INT} < 31, not attempted")
        }
        installApkViaIntent(file)
    }

    private fun installApkViaIntent(file: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, "${packageName}.provider", file)
        } else Uri.fromFile(file)
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            binding.tvUpdateStatus.text = "Install failed: ${e.message}"
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun installApkViaSession(file: File) {
        val installer = packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        session.use {
            it.openWrite("update", 0, file.length()).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                it.fsync(out)
            }
            val action = "com.iptvapp.INSTALL_RESULT"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    unregisterReceiver(this)
                    when (val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)) {
                        android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                            com.iptvapp.IptvApplication.logPlaybackEvent(applicationContext, "SILENT UPDATE: STATUS_SUCCESS")
                            binding.tvUpdateStatus.text = "✓ Updated successfully"
                            Toast.makeText(this@SettingsActivity, "MKTV updated", Toast.LENGTH_LONG).show()
                        }
                        android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            // The most common outcome for a normal (non-Play-Store) app —
                            // setRequireUserAction(false) only takes effect once this app is
                            // already its own "installer of record", which this same
                            // session-based path is what establishes going forward. Logged so
                            // a debug report can distinguish "OS still requires confirmation"
                            // from "something actually broke" without needing a live adb session.
                            com.iptvapp.IptvApplication.logPlaybackEvent(
                                applicationContext,
                                "SILENT UPDATE: STATUS_PENDING_USER_ACTION (OS still requires confirmation — falling back to visible install)"
                            )
                            @Suppress("DEPRECATION")
                            val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            if (confirmIntent != null) {
                                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(confirmIntent)
                            } else {
                                installApkViaIntent(file)
                            }
                        }
                        else -> {
                            val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                            com.iptvapp.IptvApplication.logPlaybackEvent(applicationContext, "SILENT UPDATE FAILED: status=$status msg=$msg — falling back")
                            Log.e("SettingsActivity", "Session install status=$status msg=$msg — falling back")
                            installApkViaIntent(file)
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, IntentFilter(action))
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                this, sessionId, Intent(action).setPackage(packageName),
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            binding.tvUpdateStatus.text = "Installing update..."
            it.commit(pendingIntent.intentSender)
        }
    }

    private fun showChangelog() {
        val text = try {
            assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "Changelog not available."
        }
        val scrollView = android.widget.ScrollView(this)
        val tv = android.widget.TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(48, 32, 48, 32)
        }
        scrollView.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Changelog")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            // Re-establish periodic work in case it was cleared by an app update (KEEP = don't reset the timer)
            val savedHours = prefs.epgAutoRefreshHours.first()
            if (savedHours > 0) {
                val req = PeriodicWorkRequestBuilder<EpgRefreshWorker>(savedHours.toLong(), TimeUnit.HOURS)
                    .setInputData(workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to true))
                    .build()
                workManager.enqueueUniquePeriodicWork(AUTO_EPG_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            }

            isLoadingSettings = true
            try {
                val savedEpgUrl = prefs.epgUrl.first()
                binding.etEpgUrl.setText(savedEpgUrl)
                binding.cbUseDefaultUsEpg.isChecked = (savedEpgUrl == com.iptvapp.AppConstants.DEFAULT_US_EPG_URL)
                when (prefs.preferredFormat.first()) {
                    "ts" -> binding.rbTs.isChecked = true
                    else -> binding.rbM3u8.isChecked = true
                }
                binding.cbRefreshMissingOnly.isChecked = prefs.epgRefreshMissingOnly.first()
                binding.cbUsaOnlyChannels.isChecked = prefs.usaOnlyChannels.first()
                binding.cbEnglishOnlyMovies.isChecked = prefs.englishOnlyMovies.first()
                binding.cbAmoledBlack.isChecked = prefs.amoledBlack.first()
                binding.switchSilentSelfUpdate.isChecked = prefs.silentSelfUpdateEnabled.first()
                binding.cbShowMovies.isChecked = prefs.showMovies.first()
                binding.cbShowSeries.isChecked = prefs.showSeries.first()
                binding.cbShowWatching.isChecked = prefs.showWatching.first()
                when (prefs.externalPlayer.first()) {
                    "vlc"      -> binding.rbPlayerVlc.isChecked = true
                    "mxplayer" -> binding.rbPlayerMx.isChecked = true
                    "system"   -> binding.rbPlayerSystem.isChecked = true
                    else       -> binding.rbPlayerInternal.isChecked = true
                }
                when (prefs.epgAutoRefreshHours.first()) {
                    6    -> binding.rbAuto6.isChecked = true
                    12   -> binding.rbAuto12.isChecked = true
                    24   -> binding.rbAuto24.isChecked = true
                    else -> binding.rbAutoOff.isChecked = true
                }
                binding.switchSyncEnabled.isChecked = prefs.syncEnabled.first()
                binding.switchExtraBuffering.isChecked = prefs.extraBufferingEnabled.first()
                binding.switchPipEnabled.isChecked = prefs.pipEnabled.first()
                val dohEnabled = prefs.dohEnabled.first()
                binding.cbDohEnabled.isChecked = dohEnabled
                binding.rgDohProvider.visibility = if (dohEnabled) android.view.View.VISIBLE else android.view.View.GONE
                when (prefs.dohProvider.first()) {
                    "google"  -> binding.rbDohGoogle.isChecked = true
                    "nextdns" -> binding.rbDohNextDns.isChecked = true
                    else      -> binding.rbDohCloudflare.isChecked = true
                }
                currentSortIndex = prefs.channelSortMode.first().coerceIn(0, sortLabels.lastIndex)
                binding.btnSettingsSort.text = "⇅  Sort Channels: ${sortLabels[currentSortIndex]}"
                currentAccentColor = prefs.accentColor.first()
                setupAccentPicker()
                applyAccentToSettings(Color.parseColor(currentAccentColor))
                updateLastRefreshText()
                updateCacheAgeText()
                binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            } finally {
                isLoadingSettings = false
            }
        }
    }

    private fun startEpgRefresh() {
        lifecycleScope.launch {
            val missingOnly = prefs.epgRefreshMissingOnly.first()
            val request = OneTimeWorkRequestBuilder<EpgRefreshWorker>()
                .setInputData(workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to missingOnly))
                .build()
            currentEpgWorkId = request.id
            binding.progressEpgRefresh.visibility = View.VISIBLE
            binding.progressEpgRefresh.progress = 0
            binding.tvEpgRefreshStatus.text = "Starting EPG refresh..."
            binding.btnRefreshEpg.isEnabled = false
            binding.btnCancelEpgRefresh.visibility = View.VISIBLE
            workManager.enqueueUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            observeCurrentEpgWork(request.id)
        }
    }

    private fun observeCurrentEpgWork(workId: UUID) {
        workManager.getWorkInfoByIdLiveData(workId).observe(this) { info ->
            if (info == null) return@observe
            val progress = info.progress.getInt(EpgRefreshWorker.KEY_PROGRESS, 0)
            val status = info.progress.getString(EpgRefreshWorker.KEY_STATUS)
                ?: info.outputData.getString(EpgRefreshWorker.KEY_STATUS)
                ?: "EPG refresh: ${info.state}"
            binding.progressEpgRefresh.visibility = View.VISIBLE
            binding.progressEpgRefresh.progress = progress
            binding.tvEpgRefreshStatus.text = status
            val running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
            binding.btnRefreshEpg.isEnabled = !running
            binding.btnCancelEpgRefresh.visibility = if (running) View.VISIBLE else View.GONE
            if (info.state.isFinished) {
                lifecycleScope.launch { updateLastRefreshText(); updateCacheAgeText() }
            }
        }
    }

    private fun observeEpgRefreshWork() {
        workManager.getWorkInfosForUniqueWorkLiveData(EpgRefreshWorker.UNIQUE_WORK_NAME)
            .observe(this) { infos ->
                val info = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: return@observe
                val progress = info.progress.getInt(EpgRefreshWorker.KEY_PROGRESS, 0)
                val status = info.progress.getString(EpgRefreshWorker.KEY_STATUS) ?: "EPG refreshing..."
                binding.progressEpgRefresh.visibility = View.VISIBLE
                binding.progressEpgRefresh.progress = progress
                binding.tvEpgRefreshStatus.text = status
                binding.btnRefreshEpg.isEnabled = false
                binding.btnCancelEpgRefresh.visibility = View.VISIBLE
            }
    }

    private fun scheduleAutoEpgRefresh(hours: Int) {
        if (hours == 0) {
            workManager.cancelUniqueWork(AUTO_EPG_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setInputData(workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to true))
            .build()
        workManager.enqueueUniquePeriodicWork(AUTO_EPG_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private suspend fun updateLastRefreshText() {
        val time = prefs.lastEpgRefreshTime.first()
        binding.tvLastEpgRefresh.text = if (time == 0L) {
            "Last EPG Refresh: Never"
        } else {
            val formatted = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(time))
            "Last EPG Refresh: $formatted"
        }
    }

    private suspend fun updateCacheAgeText() {
        val newest = db.epgDao().getNewestEpgStopTimestamp()
        val nowSeconds = System.currentTimeMillis() / 1000
        binding.tvEpgCacheAge.text = when {
            newest == null -> "EPG Cache Age: Unknown"
            newest < nowSeconds -> "EPG Cache: Expired"
            else -> "EPG Cache: covers ~${(newest - nowSeconds) / 3600}h ahead"
        }
    }

    private val extraServers = mutableListOf<List<String>>()

    private fun setupServers() {
        lifecycleScope.launch {
            extraServers.clear()
            extraServers.addAll(prefs.getExtraServersWithNick())
            updateServerList()
        }
        binding.btnAddServer.setOnClickListener { showAddServerDialog() }
    }

    private fun updateServerList() {
        val ll = binding.llServers
        ll.removeAllViews()
        lifecycleScope.launch {
            val creds = prefs.credentials.first()
            val activeIndex = prefs.activeServerIndex.first()
            val primaryNick = prefs.serverNickname.first().ifEmpty { creds.username }

            val primaryRow = android.widget.LinearLayout(this@SettingsActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1A1A1A"))
                setPadding(24, 20, 24, 20)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 12 }
            }
            android.widget.TextView(this@SettingsActivity).apply {
                text = "PRIMARY"
                setTextColor(Color.parseColor("#777777"))
                textSize = 10f
                primaryRow.addView(this)
            }
            android.widget.TextView(this@SettingsActivity).apply {
                text = primaryNick
                setTextColor(Color.WHITE)
                textSize = 14f
                primaryRow.addView(this)
            }
            android.widget.TextView(this@SettingsActivity).apply {
                text = if (activeIndex == -1) "● ACTIVE" else "INACTIVE"
                setTextColor(if (activeIndex == -1) Color.parseColor(currentAccentColor) else Color.parseColor("#555555"))
                textSize = 12f
                primaryRow.addView(this)
            }
            ll.addView(primaryRow)

            extraServers.forEachIndexed { i, server ->
                val url = server[0]; val user = server[1]
                val nick = server.getOrElse(3) { "" }.ifEmpty { user }
                val row = android.widget.LinearLayout(this@SettingsActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#1A1A1A"))
                    setPadding(24, 20, 24, 20)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 12 }
                }
                android.widget.TextView(this@SettingsActivity).apply {
                    text = "SERVER ${i + 2}"
                    setTextColor(Color.parseColor("#777777"))
                    textSize = 10f
                    row.addView(this)
                }
                android.widget.TextView(this@SettingsActivity).apply {
                    text = nick
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    row.addView(this)
                }
                android.widget.TextView(this@SettingsActivity).apply {
                    text = if (activeIndex == i) "● ACTIVE" else "INACTIVE"
                    setTextColor(if (activeIndex == i) Color.parseColor(currentAccentColor) else Color.parseColor("#555555"))
                    textSize = 12f
                    row.addView(this)
                }
                val btnRow = android.widget.LinearLayout(this@SettingsActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = 12 }
                }
                android.widget.Button(this@SettingsActivity).apply {
                    text = "Switch"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#1976D2"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .also { it.marginEnd = 8 }
                    setOnClickListener {
                        lifecycleScope.launch {
                            val primary = prefs.credentials.first()
                            val newPass = extraServers[i][2]
                            val updated = extraServers.toMutableList()
                            updated[i] = listOf(primary.serverUrl, primary.username, primary.password, prefs.serverNickname.first())
                            prefs.saveExtraServersWithNick(updated)
                            withContext(Dispatchers.IO) { db.clearAllTables() }
                            prefs.saveCredentials(url, user, newPass)
                            prefs.setActiveServerIndex(-1)
                            val intent = Intent(this@SettingsActivity, com.iptvapp.ui.home.HomeActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                        }
                    }
                    btnRow.addView(this)
                }
                android.widget.Button(this@SettingsActivity).apply {
                    text = "Remove"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#CC0000"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        extraServers.removeAt(i)
                        lifecycleScope.launch { prefs.saveExtraServersWithNick(extraServers) }
                        updateServerList()
                    }
                    btnRow.addView(this)
                }
                row.addView(btnRow)
                ll.addView(row)
            }
        }
    }

    private fun showAddServerDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        // Android's autofill service treats these as a generic login form and will silently
        // suggest/inject the already-saved primary account's credentials into them — which
        // looked exactly like "I typed a different provider's login but it reverted to mine"
        // since the overwrite happens before the fields are ever read. Opting every field out
        // of autofill (importantForAutofill + a no-op autofillHints) stops that substitution.
        fun android.widget.EditText.disableAutofill() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                setAutofillHints(null)
            }
        }
        val etNick = android.widget.EditText(this).apply { hint = "Nickname (optional)"; disableAutofill() }
        val etUrl  = android.widget.EditText(this).apply { hint = "Server URL (http://...)"; disableAutofill() }
        val etUser = android.widget.EditText(this).apply { hint = "Username"; disableAutofill() }
        val etPass = android.widget.EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            disableAutofill()
        }
        layout.addView(etNick); layout.addView(etUrl); layout.addView(etUser); layout.addView(etPass)
        AlertDialog.Builder(this)
            .setTitle("Add Server")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val url  = etUrl.text.toString().trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                if (url.isNotEmpty() && user.isNotEmpty()) {
                    lifecycleScope.launch {
                        val fresh = prefs.getExtraServersWithNick().toMutableList()
                        fresh.add(listOf(url, user, pass, etNick.text.toString().trim()))
                        extraServers.clear()
                        extraServers.addAll(fresh)
                        prefs.saveExtraServersWithNick(extraServers)
                        Toast.makeText(this@SettingsActivity, "Server added", Toast.LENGTH_SHORT).show()
                        updateServerList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupTraktSection() {
        refreshTraktStatus()
        binding.btnTraktConnect.setOnClickListener { showTraktConnectDialog() }
        binding.btnTraktDisconnect.setOnClickListener {
            lifecycleScope.launch {
                traktManager.disconnect()
                refreshTraktStatus()
                Toast.makeText(this@SettingsActivity, "Disconnected from Trakt", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnTraktSyncHistory.setOnClickListener {
            binding.btnTraktSyncHistory.isEnabled = false
            binding.tvTraktSyncStatus.text = "Syncing watched history from Trakt…"
            lifecycleScope.launch {
                val result = traktManager.syncWatchedHistoryBack()
                binding.btnTraktSyncHistory.isEnabled = true
                val unmatchedCount = result.unmatchedMovies.size + result.unmatchedShows.size
                binding.tvTraktSyncStatus.text =
                    "Matched ${result.moviesMatched} movies, ${result.showsMatched} shows " +
                        "(${result.episodesMarked} episodes marked watched)" +
                        if (unmatchedCount > 0) " — $unmatchedCount unmatched (tap to view)" else ""
                binding.tvTraktSyncStatus.setOnClickListener {
                    if (unmatchedCount > 0) showUnmatchedTraktDialog(result)
                }
            }
        }
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

    private fun refreshTraktStatus() {
        lifecycleScope.launch {
            if (!traktManager.isConfigured) {
                binding.tvTraktStatus.text = "Trakt is not configured for this build"
                binding.btnTraktConnect.visibility = View.GONE
                binding.btnTraktDisconnect.visibility = View.GONE
                binding.btnTraktSyncHistory.visibility = View.GONE
                return@launch
            }
            val connected = traktManager.isConnected.first()
            if (connected) {
                binding.tvTraktStatus.text = "✓ Connected — scrobbling your watch activity"
                binding.btnTraktConnect.visibility = View.GONE
                binding.btnTraktDisconnect.visibility = View.VISIBLE
                binding.btnTraktSyncHistory.visibility = View.VISIBLE
            } else {
                binding.tvTraktStatus.text = "Not connected"
                binding.btnTraktConnect.visibility = View.VISIBLE
                binding.btnTraktDisconnect.visibility = View.GONE
                binding.btnTraktSyncHistory.visibility = View.GONE
            }
        }
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
                            Toast.makeText(this@SettingsActivity, "Connected to Trakt", Toast.LENGTH_SHORT).show()
                            refreshTraktStatus()
                        }
                        is com.iptvapp.trakt.TraktDeviceAuthResult.Expired -> {
                            dialog.dismiss()
                            Toast.makeText(this@SettingsActivity, "Trakt code expired — try again", Toast.LENGTH_SHORT).show()
                        }
                        is com.iptvapp.trakt.TraktDeviceAuthResult.Denied -> {
                            dialog.dismiss()
                            Toast.makeText(this@SettingsActivity, "Trakt authorization denied", Toast.LENGTH_SHORT).show()
                        }
                        is com.iptvapp.trakt.TraktDeviceAuthResult.Error -> {
                            dialog.dismiss()
                            Toast.makeText(this@SettingsActivity, "Trakt error: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun setupSyncSection() {
        binding.tvSyncStatus.text = ""
        lifecycleScope.launch {
            val ownCode = syncManager.getOwnSyncCode().take(8).uppercase()
            val summary = syncManager.getLastSyncSummary()
            binding.tvSyncStatus.text = if (ownCode.isNotEmpty()) "Your sync code: $ownCode\n$summary" else summary
            // Pre-fill pairing code field if one is saved
            val existing = prefs.getSyncGistId()
            if (existing.isNotBlank()) binding.etGithubToken.setText(existing.take(8).uppercase())
        }
        binding.btnSaveGithubToken.setOnClickListener {
            val code = binding.etGithubToken.text.toString().trim()
            lifecycleScope.launch {
                syncManager.setPairingCode(code)
                Toast.makeText(this@SettingsActivity, if (code.isBlank()) "Pairing code cleared" else "Paired ✓ — tap Pull from Cloud", Toast.LENGTH_SHORT).show()
            }
        }
        binding.switchSyncEnabled.setOnCheckedChangeListener { _, enabled ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setSyncEnabled(enabled) }
            if (enabled) scheduleAutoSync() else cancelAutoSync()
        }
        binding.switchExtraBuffering.setOnCheckedChangeListener { _, enabled ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setExtraBufferingEnabled(enabled) }
        }
        binding.switchPipEnabled.setOnCheckedChangeListener { _, enabled ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setPipEnabled(enabled) }
        }
        binding.btnSyncUp.setOnClickListener {
            binding.tvSyncStatus.text = "Pushing to cloud..."
            binding.btnSyncUp.isEnabled = false
            binding.btnSyncDown.isEnabled = false
            lifecycleScope.launch {
                val result = syncManager.syncUp()
                binding.tvSyncStatus.text = result
                binding.btnSyncUp.isEnabled = true
                binding.btnSyncDown.isEnabled = true
                Toast.makeText(this@SettingsActivity, result, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnSyncDown.setOnClickListener {
            binding.tvSyncStatus.text = "Pulling from cloud..."
            binding.btnSyncUp.isEnabled = false
            binding.btnSyncDown.isEnabled = false
            lifecycleScope.launch {
                val result = syncManager.syncDown()
                binding.tvSyncStatus.text = result
                binding.btnSyncUp.isEnabled = true
                binding.btnSyncDown.isEnabled = true
                Toast.makeText(this@SettingsActivity, result, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun runSpeedTest() {
        binding.btnSpeedTest.isEnabled = false
        binding.tvSpeedTestResult.text = "Testing..."
        try {
            val result = withContext(Dispatchers.IO) {
                val serverUrl = try { prefs.credentials.first().serverUrl } catch (_: Exception) { "" }
                val uri = try { java.net.URI(serverUrl) } catch (_: Exception) {
                    return@withContext "Error: invalid server URL"
                }
                val host = uri.host ?: return@withContext "Error: could not parse host"
                val port = if (uri.port > 0) uri.port else 80

                // TCP ping
                val tcpTimes = mutableListOf<Long>()
                repeat(3) {
                    try {
                        val start = System.currentTimeMillis()
                        val socket = java.net.Socket()
                        socket.connect(java.net.InetSocketAddress(host, port), 3000)
                        val elapsed = System.currentTimeMillis() - start
                        socket.close()
                        tcpTimes.add(elapsed)
                    } catch (_: Exception) {}
                }
                val tcpAvg = if (tcpTimes.isNotEmpty()) tcpTimes.average().toLong() else -1L
                val tcpStr = if (tcpAvg >= 0) "TCP Ping: ${tcpAvg}ms avg (${tcpTimes.size}/3)"
                             else "TCP Ping: failed"

                // HTTP response
                val httpStr = try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(8, TimeUnit.SECONDS)
                        .build()
                    val start = System.currentTimeMillis()
                    val response = client.newCall(Request.Builder().url(serverUrl).build()).execute()
                    val elapsed = System.currentTimeMillis() - start
                    response.close()
                    "HTTP Response: ${elapsed}ms"
                } catch (e: Exception) {
                    "HTTP Response: failed (${e.message})"
                }

                "$tcpStr\n$httpStr\nServer: $host:$port"
            }
            binding.tvSpeedTestResult.text = result
        } catch (e: Exception) {
            binding.tvSpeedTestResult.text = "Error: ${e.message}"
        } finally {
            binding.btnSpeedTest.isEnabled = true
        }
    }

    companion object {
        private const val AUTO_EPG_WORK_NAME = "auto_epg_refresh_work"
    }

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
        }
    }



    private fun showRestoreDialog() {
        openBackupLauncher.launch(arrayOf("application/json"))
    }

    private suspend fun restoreBackupFromUri(uri: Uri) {
        val jsonText = contentResolver.openInputStream(uri)
            ?.bufferedReader()?.use { it.readText() } ?: return
        applyBackupJson(JSONObject(jsonText))
    }

    private suspend fun restoreBackupFromFile(file: File) {
        try {
            val jsonText = withContext(Dispatchers.IO) { file.readText() }
            applyBackupJson(JSONObject(jsonText))
        } catch (e: Exception) {
            Toast.makeText(this, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun applyBackupJson(json: JSONObject) {
        val serverUrl = json.optString("serverUrl", "")
        val username  = json.optString("username", "")
        val password  = json.optString("password", "")
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

        // Reload UI after all prefs are set
        loadSettings()
        binding.tvBackupStatus.text = "✓ Restored successfully"
        Toast.makeText(this, "Restore complete", Toast.LENGTH_SHORT).show()
    }
}
