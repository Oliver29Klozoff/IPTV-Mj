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
import com.iptvapp.util.isForceTvModeEnabled
import com.iptvapp.util.setForceTvModeEnabled
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
        binding.sectionStream, binding.sectionDisplay, binding.sectionServers,
        binding.sectionBackup, binding.sectionSync, binding.sectionUpdates
    )
    private val navButtonViews get() = listOf(
        binding.headerStream, binding.headerDisplay, binding.headerServers,
        binding.headerBackup, binding.headerSync, binding.headerUpdates
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
            + "Provider Speed Test: measures how fast your current provider responds, to help "
            + "compare multiple providers if you have them.\n\n"
            + "DNS over HTTPS (DoH): encrypts DNS lookups so your ISP can't see (or throttle "
            + "based on) which streaming domains you're connecting to.\n\n"
            + "Tunneled Playback: lets the device handle audio/video sync in hardware instead "
            + "of the app doing it in software — smoother on supported devices, but can cause "
            + "glitches on some. Off by default.\n\n"
            + "DV7 → HEVC Fallback: some devices can't properly decode Dolby Vision Profile 7 "
            + "and show a black screen or fail outright — this redirects that content to a "
            + "standard HEVC decoder instead (DV7 streams always include a valid HEVC base "
            + "layer), trading the extra HDR enhancement layer for a picture that actually "
            + "plays.\n\n"
            + "Audio Passthrough Fallback: some TV boxes report Dolby/DTS passthrough support "
            + "but produce no sound at all when no receiver is actually connected — this forces "
            + "stereo audio instead. Off by default since it disables surround sound on setups "
            + "that DO have a receiver; only turn it on if some channels/movies have no audio.\n\n"
            + "Autoplay Next Episode: shows a cancelable 10-second Up Next prompt and "
            + "auto-advances to the next episode when one finishes, including into the next "
            + "season once the current one runs out. On by default.\n\n"
            + "Global Extra Buffering: builds up a bigger buffer before playback starts, "
            + "trading a slower start for fewer stalls mid-stream on slow/unreliable "
            + "connections. On by default.\n\n"
            + "Show USA Channels Only / English Movies & Series Only: filters live channels "
            + "or VOD/series to just those tagged for that country/language by your "
            + "provider — depends entirely on your provider's own naming, so may not work "
            + "for every provider.\n\n"
            + "Preferred Audio / Subtitle Language: when a stream offers multiple language "
            + "tracks, automatically selects the one matching your choice instead of whatever "
            + "the stream defaults to. Only works if the stream actually tags its tracks with "
            + "language info — depends on your provider.",
        // 1: Display
        "Show Movies/Series/Watching Tab: hides tabs you don't use to declutter the home "
            + "screen — the content itself isn't deleted, just the tab.\n\n"
            + "Accent Color: the highlight color used for selected tabs, buttons, and "
            + "progress bars throughout the app.\n\n"
            + "AMOLED Black: forces pure black backgrounds everywhere instead of dark gray — "
            + "saves battery on OLED screens and looks better in a dark room. Requires an "
            + "app restart to fully apply everywhere.",
        // 2: Providers
        "Add, edit, or switch between multiple Xtream provider logins if you have more than "
            + "one IPTV subscription — only one is active for playback at a time, but you "
            + "can switch instantly without re-entering credentials.",
        // 3: Backup & Restore
        "Backup / Restore: saves your provider login, favorites, and settings to a file you "
            + "choose (Downloads, Drive, USB, etc.), or loads them back in — useful before "
            + "a factory reset or when moving to a new device. Nothing is uploaded "
            + "anywhere automatically; you pick the exact file location yourself.\n\n"
            + "Auto backup (weekly): does the same backup automatically once a week to that "
            + "same chosen location, without you having to remember to do it manually.",
        // 4: Sync
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
            + "playback, not the mini player.",
        // 5: Updates
        "Check for Updates: manually checks for a new version right now instead of waiting "
            + "for the automatic background check.\n\n"
            + "What's New / Changelog: shows what changed in the current and past versions.\n\n"
            + "Silent Self-Update: on Android 12+, skips the \"Install update?\" confirmation "
            + "screen when the OS allows it, updating more seamlessly. Off by default since "
            + "it bypasses a normal Android security prompt — only turn this on if you're "
            + "comfortable with that."
    )

    // ─── Search ─────────────────────────────────────────────────────────────
    // Hand-mapped rather than parsed from the layout — this XML mixes CardView sections and
    // collapsible sub-cards with no consistent id-to-label convention to walk automatically.
    // Each entry: (label, panel index into panelViews/navButtonViews, header id to expand if
    // this setting lives inside a collapsible card (null if not collapsible), target view id
    // to scroll to and highlight).
    private data class SettingSearchEntry(val label: String, val panelIndex: Int, val headerId: Int?, val targetId: Int)

    private val settingSearchIndex: List<SettingSearchEntry> by lazy {
        listOf(
            SettingSearchEntry("EPG URL", 0, R.id.hdrEpgUrl, R.id.hdrEpgUrl),
            SettingSearchEntry("Stream Format", 0, R.id.hdrFormat, R.id.hdrFormat),
            SettingSearchEntry("Preferred Audio Language", 0, R.id.hdrLanguage, R.id.hdrLanguage),
            SettingSearchEntry("Preferred Subtitle Language", 0, R.id.hdrLanguage, R.id.hdrLanguage),
            SettingSearchEntry("Video Player", 0, R.id.hdrPlayer, R.id.hdrPlayer),
            SettingSearchEntry("EPG Refresh", 0, R.id.hdrEpgSection, R.id.hdrEpgSection),
            SettingSearchEntry("Auto Refresh Schedule", 0, R.id.hdrEpgSection, R.id.hdrEpgSection),
            SettingSearchEntry("Provider Speed Test", 0, R.id.hdrSpeedTest, R.id.hdrSpeedTest),
            SettingSearchEntry("DNS over HTTPS", 0, R.id.hdrDoh, R.id.hdrDoh),
            SettingSearchEntry("Tunneled Playback", 0, null, R.id.switchTunneledPlayback),
            SettingSearchEntry("DV7 HEVC Fallback", 0, null, R.id.switchDv7Fallback),
            SettingSearchEntry("Audio Passthrough Fallback", 0, null, R.id.switchAudioPassthroughFallback),
            SettingSearchEntry("Autoplay Next Episode", 0, null, R.id.switchAutoplayNextEpisode),
            SettingSearchEntry("Global Extra Buffering", 0, null, R.id.switchExtraBuffering),
            SettingSearchEntry("Picture-in-Picture", 0, null, R.id.switchPipEnabled),
            SettingSearchEntry("Show USA Channels Only", 0, null, R.id.cbUsaOnlyChannels),
            SettingSearchEntry("Show English Movies & Series Only", 0, null, R.id.cbEnglishOnlyMovies),
            SettingSearchEntry("Channels & Tabs", 1, R.id.hdrChannelsTabs, R.id.hdrChannelsTabs),
            SettingSearchEntry("Show Movies Tab", 1, R.id.hdrChannelsTabs, R.id.hdrChannelsTabs),
            SettingSearchEntry("Show Series Tab", 1, R.id.hdrChannelsTabs, R.id.hdrChannelsTabs),
            SettingSearchEntry("Show Watching Tab", 1, R.id.hdrChannelsTabs, R.id.hdrChannelsTabs),
            SettingSearchEntry("Accent Color", 1, R.id.hdrAccentColor, R.id.hdrAccentColor),
            SettingSearchEntry("AMOLED Black", 1, R.id.hdrAccentColor, R.id.hdrAccentColor),
            SettingSearchEntry("Quick Actions", 1, R.id.hdrQuickActions, R.id.hdrQuickActions),
            SettingSearchEntry("Sort Channels", 1, R.id.hdrQuickActions, R.id.hdrQuickActions),
            SettingSearchEntry("Multi-view / Mosaic", 1, R.id.hdrQuickActions, R.id.hdrQuickActions),
            SettingSearchEntry("Feature Tour", 1, R.id.hdrQuickActions, R.id.hdrQuickActions),
            SettingSearchEntry("Subtitle Size", 1, null, R.id.sectionDisplay),
            SettingSearchEntry("Subtitle Text Color", 1, null, R.id.sectionDisplay),
            SettingSearchEntry("Subtitle Background Color", 1, null, R.id.sectionDisplay),
            SettingSearchEntry("Subtitle Outline", 1, null, R.id.sectionDisplay),
            SettingSearchEntry("Check for Updates", 5, R.id.hdrUpdates, R.id.hdrUpdates),
            SettingSearchEntry("What's New / Changelog", 5, R.id.hdrUpdates, R.id.hdrUpdates),
            SettingSearchEntry("Silent Self-Update", 5, R.id.hdrUpdates, R.id.hdrUpdates),
            SettingSearchEntry("Backup", 3, null, R.id.sectionBackup),
            SettingSearchEntry("Restore", 3, null, R.id.sectionBackup),
            SettingSearchEntry("Auto backup", 3, null, R.id.sectionBackup),
            SettingSearchEntry("Add Provider", 2, null, R.id.sectionServers),
            SettingSearchEntry("Cross-Device Sync", 4, R.id.hdrCrossDeviceSync, R.id.hdrCrossDeviceSync),
            SettingSearchEntry("Push to Cloud", 4, R.id.hdrCrossDeviceSync, R.id.hdrCrossDeviceSync),
            SettingSearchEntry("Pull from Cloud", 4, R.id.hdrCrossDeviceSync, R.id.hdrCrossDeviceSync),
            SettingSearchEntry("Pairing Code", 4, R.id.hdrCrossDeviceSync, R.id.hdrCrossDeviceSync),
            SettingSearchEntry("Diagnostics", 4, R.id.hdrDiagnostics, R.id.hdrDiagnostics),
            SettingSearchEntry("Send Debug Report", 4, R.id.hdrDiagnostics, R.id.hdrDiagnostics),
            SettingSearchEntry("Provider Health", 4, R.id.hdrDiagnostics, R.id.hdrDiagnostics),
            SettingSearchEntry("Connect Trakt", 4, null, R.id.sectionSync),
            SettingSearchEntry("Sync Watched History from Trakt", 4, null, R.id.sectionSync)
        )
    }

    private fun showSettingsSearchDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val input = android.widget.EditText(this).apply {
            hint = "Search settings…"
            setPadding(32, 16, 32, 16)
        }
        val resultsList = android.widget.ListView(this)
        container.addView(input)
        container.addView(resultsList, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (400 * resources.displayMetrics.density).toInt()
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Find in Settings")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()

        var currentMatches: List<SettingSearchEntry> = emptyList()
        fun renderMatches(query: String) {
            currentMatches = if (query.isBlank()) emptyList()
                else settingSearchIndex.filter { it.label.contains(query, ignoreCase = true) }
            resultsList.adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_list_item_1, currentMatches.map { it.label }
            )
        }
        resultsList.setOnItemClickListener { _, _, position, _ ->
            val match = currentMatches.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            jumpToSettingSearchResult(match)
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { renderMatches(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        dialog.show()
    }

    private fun jumpToSettingSearchResult(match: SettingSearchEntry) {
        // Reuse the existing left-rail nav click to switch panels, keeping its highlight/focus
        // side effects (backgroundTint, text color) consistent with a normal manual tap.
        navButtonViews.getOrNull(match.panelIndex)?.performClick()
        val panel = panelViews.getOrNull(match.panelIndex) as? android.widget.ScrollView ?: return
        panel.post {
            if (match.headerId != null) {
                val header = findViewById<View>(match.headerId)
                val bodyId = when (match.headerId) {
                    R.id.hdrEpgUrl -> R.id.bodyEpgUrl
                    R.id.hdrFormat -> R.id.bodyFormat
                    R.id.hdrLanguage -> R.id.bodyLanguage
                    R.id.hdrPlayer -> R.id.bodyPlayer
                    R.id.hdrEpgSection -> R.id.bodyEpgSection
                    R.id.hdrSpeedTest -> R.id.bodySpeedTest
                    R.id.hdrDoh -> R.id.bodyDoh
                    R.id.hdrChannelsTabs -> R.id.bodyChannelsTabs
                    R.id.hdrAccentColor -> R.id.bodyAccentColor
                    R.id.hdrQuickActions -> R.id.bodyQuickActions
                    R.id.hdrUpdates -> R.id.bodyUpdates
                    R.id.hdrCrossDeviceSync -> R.id.bodyCrossDeviceSync
                    R.id.hdrDiagnostics -> R.id.bodyDiagnostics
                    else -> null
                }
                val body = bodyId?.let { findViewById<View>(it) }
                if (body != null && body.visibility == View.GONE) header?.performClick()
            }
            panel.post {
                val target = findViewById<View>(match.targetId) ?: return@post
                panel.smoothScrollTo(0, target.top)
                val original = target.background
                target.setBackgroundColor(Color.parseColor("#1A008CFF"))
                target.postDelayed({ target.background = original }, 900)
            }
        }
    }

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
    // Named two-stop gradients — selectable alongside the flat swatches above. Picking one only
    // ever paints a real gradient on the tab indicator (the one place that can render it); every
    // other accent consumer (progress tints, text colors) falls back to the start color, same as
    // a plain solid pick. See HomeActivity.applyAccent().
    private val accentGradients = listOf(
        Triple("Sunset", "#FF9500", "#FF2D55"),
        Triple("Ocean", "#008CFF", "#34C759"),
        Triple("Berry", "#AF52DE", "#FF3B30"),
        Triple("Aurora", "#34C759", "#5AC8FA")
    )
    private var currentAccentColor = "#008CFF"
    private var currentAccentColorEnd = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        lifecycleScope.launch { com.iptvapp.util.ThemeUtils.applyAmoledIfEnabled(binding.root, prefs) }
        workManager = WorkManager.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSettingsHelp.setOnClickListener { showSettingsHelp() }
        binding.btnSettingsSearch.setOnClickListener { showSettingsSearchDialog() }

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
            FeatureTourDialog.startFromSettings(this)
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

        // Every phone-vs-TV routing decision (Splash's home-screen pick, Settings' own class
        // pick, PlayerActivity's fullscreen-return target, the Feature Tour) reads
        // isLargeScreenDevice() fresh each time it's needed — restarting into SplashActivity is
        // the simplest way to make every one of those decisions re-evaluate under the new
        // override immediately, rather than patching each call site's already-cached state.
        binding.switchForceTvMode.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            setForceTvModeEnabled(isChecked)
            val intent = Intent(this, com.iptvapp.ui.SplashActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
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

        setupLanguageSpinners()

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

    // (label, ISO 639-2 code) — "No preference" maps to "" (empty = auto/default track
    // selection, see PlayerActivity's DefaultTrackSelector setup). Covers the languages most
    // Xtream providers actually tag; anything untagged falls back to the stream's default.
    private val languageOptions = listOf(
        "No preference" to "",
        "English" to "eng",
        "Spanish" to "spa",
        "French" to "fra",
        "German" to "deu",
        "Italian" to "ita",
        "Portuguese" to "por",
        "Arabic" to "ara",
        "Russian" to "rus",
        "Hindi" to "hin",
        "Mandarin" to "zho"
    )

    private fun setupLanguageSpinners() {
        val labels = languageOptions.map { it.first }
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerAudioLanguage.adapter = adapter
        binding.spinnerSubtitleLanguage.adapter = adapter

        binding.spinnerAudioLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isLoadingSettings) return
                lifecycleScope.launch { prefs.setPreferredAudioLanguage(languageOptions[position].second) }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        binding.spinnerSubtitleLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isLoadingSettings) return
                lifecycleScope.launch { prefs.setPreferredSubtitleLanguage(languageOptions[position].second) }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
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

    // Tiles wrap into fixed-size sub-rows (rather than one long horizontal LinearLayout) since
    // 8 tiles at 44dp+margins overflows narrower phone screens — accentColorRow is now a
    // vertical container that this fills with as many horizontal sub-rows as needed.
    private val ACCENT_TILES_PER_ROW = 5

    private fun setupAccentPicker() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val container = binding.accentColorRow
        container.removeAllViews()

        fun newSubRow(): android.widget.LinearLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { if (container.childCount > 0) topMargin = dp(8) }
            container.addView(this)
        }

        // Tile kinds: a flat swatch (hex), a named gradient preset (gradient), or the custom-hue
        // picker tile (isCustomTile). Exactly one of hex/gradient is non-null unless isCustomTile.
        fun buildTile(isFirstInRow: Boolean, hex: String?, gradient: Triple<String, String, String>?, isCustomTile: Boolean): android.widget.FrameLayout {
            val outer = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    if (!isFirstInRow) marginStart = dp(8)
                }
            }
            val isSelected = when {
                isCustomTile -> accentPalette.none { it == currentAccentColor } && accentGradients.none { it.second == currentAccentColor && it.third == currentAccentColorEnd } && currentAccentColorEnd.isEmpty()
                gradient != null -> currentAccentColorEnd.equals(gradient.third, ignoreCase = true) && currentAccentColor.equals(gradient.second, ignoreCase = true)
                else -> hex == currentAccentColor && currentAccentColorEnd.isEmpty()
            }
            val swatch = View(this).apply {
                val gd = android.graphics.drawable.GradientDrawable()
                gd.shape = android.graphics.drawable.GradientDrawable.OVAL
                when {
                    isCustomTile -> {
                        if (isSelected) {
                            gd.setColor(Color.parseColor(currentAccentColor))
                        } else {
                            // Conic hint that this tile opens a picker, not a single fixed color —
                            // a plain solid circle here would look like just another preset.
                            gd.colors = intArrayOf(Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED)
                            gd.gradientType = android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT
                            gd.orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                        }
                    }
                    gradient != null -> {
                        gd.colors = intArrayOf(Color.parseColor(gradient.second), Color.parseColor(gradient.third))
                        gd.orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                    }
                    else -> gd.setColor(Color.parseColor(hex))
                }
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
                visibility = if (isSelected) View.VISIBLE else View.GONE
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            outer.addView(swatch)
            outer.addView(ring)
            outer.setOnClickListener {
                when {
                    isCustomTile -> showCustomColorPickerDialog()
                    gradient != null -> {
                        currentAccentColor = gradient.second
                        currentAccentColorEnd = gradient.third
                        lifecycleScope.launch { prefs.setAccentGradient(gradient.second, gradient.third) }
                        applyAccentToSettings(Color.parseColor(gradient.second))
                        setupAccentPicker()
                    }
                    hex != null -> {
                        currentAccentColor = hex
                        currentAccentColorEnd = ""
                        lifecycleScope.launch { prefs.setAccentColor(hex) }
                        applyAccentToSettings(Color.parseColor(hex))
                        setupAccentPicker()
                    }
                }
            }
            return outer
        }

        data class TileSpec(val hex: String?, val gradient: Triple<String, String, String>?, val isCustomTile: Boolean)
        val allTiles = accentPalette.map { TileSpec(it, null, false) } +
            accentGradients.map { TileSpec(null, it, false) } +
            listOf(TileSpec(null, null, true))
        var subRow: android.widget.LinearLayout? = null
        allTiles.forEachIndexed { i, spec ->
            val posInRow = i % ACCENT_TILES_PER_ROW
            if (posInRow == 0) subRow = newSubRow()
            subRow!!.addView(buildTile(isFirstInRow = posInRow == 0, hex = spec.hex, gradient = spec.gradient, isCustomTile = spec.isCustomTile))
        }
    }

    private fun showCustomColorPickerDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val startHsv = FloatArray(3)
        Color.colorToHSV(Color.parseColor(currentAccentColor), startHsv)
        // Custom colors are locked to full saturation/value (a clean, vivid hue) — this app's
        // accent is used as a small highlight color (buttons, focus rings, chips), where a
        // muddy/desaturated pick would look like a bug rather than a deliberate choice. Only
        // hue is actually adjustable; that alone spans the full color wheel.
        var hue = if (startHsv[1] > 0.3f) startHsv[0] else 210f

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(8))
        }
        val preview = View(this).apply {
            val gd = android.graphics.drawable.GradientDrawable()
            gd.shape = android.graphics.drawable.GradientDrawable.OVAL
            gd.setColor(Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            background = gd
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
            }
        }
        container.addView(preview)

        val hueBar = android.widget.SeekBar(this).apply {
            max = 360
            progress = hue.toInt()
            val hueColors = IntArray(37) { i -> Color.HSVToColor(floatArrayOf(i * 10f, 1f, 1f)) }
            progressDrawable = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, hueColors
            ).apply { cornerRadius = dp(4).toFloat() }
        }
        container.addView(hueBar)

        hueBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                hue = progress.toFloat()
                (preview.background as android.graphics.drawable.GradientDrawable)
                    .setColor(Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Custom Accent Color")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val hex = String.format("#%06X", 0xFFFFFF and Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                currentAccentColor = hex
                currentAccentColorEnd = ""
                lifecycleScope.launch { prefs.setAccentColor(hex) }
                applyAccentToSettings(Color.parseColor(hex))
                setupAccentPicker()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyAccentToSettings(colorInt: Int) {
        navButtonViews.forEachIndexed { i, btn ->
            btn.setTextColor(if (i == currentPanelIndex) colorInt else Color.parseColor("#AAAAAA"))
        }
    }

    // Subtitle styling (size/offset/bold/colors/outline) previously only existed on TV
    // Settings — the phone had no way to customize subtitle appearance at all, even though
    // playback already reads/applies these same PreferencesManager fields.
    private fun setupSubtitleSettings() {
        lifecycleScope.launch {
            val style = prefs.subtitleStyle.first()
            refreshSubtitleRows(style)
            binding.cbSubBold.isChecked = style.bold
            binding.cbSubOutline.isChecked = style.outlineEnabled
        }
        binding.rowSubSize.setOnClickListener {
            lifecycleScope.launch { showSubtitleSizeDialog(prefs.subtitleStyle.first().sizeScale) }
        }
        binding.rowSubOffset.setOnClickListener {
            lifecycleScope.launch { showSubtitleOffsetDialog(prefs.subtitleStyle.first().verticalOffsetDp) }
        }
        binding.cbSubBold.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setSubtitleBold(checked) }
        }
        binding.rowSubTextColor.setOnClickListener {
            lifecycleScope.launch {
                showSubtitleColorDialog("Text Color", prefs.subtitleStyle.first().textColor) { prefs.setSubtitleTextColor(it) }
            }
        }
        binding.rowSubBgColor.setOnClickListener {
            lifecycleScope.launch {
                showSubtitleColorDialog("Background Color", prefs.subtitleStyle.first().backgroundColor) { prefs.setSubtitleBackgroundColor(it) }
            }
        }
        binding.cbSubOutline.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { prefs.setSubtitleOutlineEnabled(checked) }
        }
        binding.rowSubOutlineColor.setOnClickListener {
            lifecycleScope.launch {
                showSubtitleColorDialog("Outline Color", prefs.subtitleStyle.first().outlineColor) { prefs.setSubtitleOutlineColor(it) }
            }
        }
    }

    private fun refreshSubtitleRows(style: PreferencesManager.SubtitleStyle) {
        binding.tvSubSizeValue.text = "${(style.sizeScale * 100).toInt()}%"
        binding.tvSubOffsetValue.text = if (style.verticalOffsetDp == 0) "Default" else "${style.verticalOffsetDp}dp"
        binding.tvSubTextColorValue.text = "#%08X".format(style.textColor)
        binding.tvSubBgColorValue.text = "#%08X".format(style.backgroundColor)
        binding.tvSubOutlineColorValue.text = "#%08X".format(style.outlineColor)
    }

    private fun showSubtitleSizeDialog(current: Float) {
        val options = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = options.map { "${(it * 100).toInt()}%" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Subtitle Size")
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                lifecycleScope.launch {
                    prefs.setSubtitleSizeScale(options[which])
                    refreshSubtitleRows(prefs.subtitleStyle.first())
                }
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
                lifecycleScope.launch {
                    prefs.setSubtitleVerticalOffsetDp(options[which])
                    refreshSubtitleRows(prefs.subtitleStyle.first())
                }
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
                lifecycleScope.launch {
                    onPicked(values[which])
                    refreshSubtitleRows(prefs.subtitleStyle.first())
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        wireCollapsible(R.id.hdrLanguage,    R.id.bodyLanguage,    R.id.chevLanguage)
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
            binding.switchCrashReporting.isChecked = prefs.crashReportingEnabled.first()
        }
        binding.switchCrashReporting.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                prefs.setCrashReportingEnabled(isChecked)
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(isChecked)
            }
        }

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

    // Login credentials, EPG/playback settings, and show/hide tab toggles are always included —
    // a backup missing the login is useless, and these are tiny/never worth excluding. Everything
    // else is optional so a user who only wants a lightweight settings-only backup (or wants to
    // deliberately leave watch history off a file they're about to share) can skip it — the
    // restore side already treats every one of these fields as independently optional (see
    // applyBackupJson), so any combination here restores safely.
    private data class BackupScope(
        val favorites: Boolean = true,
        val watchHistory: Boolean = true,
        val extraProviders: Boolean = true,
        val subtitleStyle: Boolean = true
    ) {
        val isFullBackup get() = favorites && watchHistory && extraProviders && subtitleStyle
    }

    private fun showBackupScopeDialog(onScopeChosen: (BackupScope) -> Unit) {
        val labels = arrayOf(
            "Favorites & folders",
            "Watch history & resume progress",
            "Extra providers & their favorites",
            "Subtitle style"
        )
        val checked = booleanArrayOf(true, true, true, true)
        AlertDialog.Builder(this)
            .setTitle("What to include")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Continue") { _, _ ->
                onScopeChosen(BackupScope(
                    favorites = checked[0],
                    watchHistory = checked[1],
                    extraProviders = checked[2],
                    subtitleStyle = checked[3]
                ))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun backupSettings() {
        showBackupScopeDialog { scope ->
            pendingBackupScope = scope
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            createBackupLauncher.launch("MKTV_backup_$timestamp.json")
        }
    }

    // Stashed between showBackupScopeDialog's callback and createBackupLauncher's own callback
    // (writeBackupToUri) — the SAF file-create flow is itself async/callback-based, so the scope
    // can't just be a local variable passed straight through.
    private var pendingBackupScope = BackupScope()

    /** Same private, app-only folder AutoBackupWorker writes weekly snapshots into —
     * keeping manual quick-backups alongside them means one list shows the full history. */
    private fun privateBackupsDir(): File =
        File(getExternalFilesDir(null), "backups").apply { mkdirs() }

    // "Quick Backup Now" (from the Manage Backups list) always takes a full snapshot rather than
    // asking scope questions first — it's meant to be a fast, no-decisions safety net, unlike the
    // primary Backup button which explicitly asks what to include.
    private suspend fun quickBackupNow() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(privateBackupsDir(), "MKTV_backup_$timestamp.json")
            val body = buildBackupJson(BackupScope()).toString(2)
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
        try {
            val body = buildBackupJson(pendingBackupScope).toString(2)
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(body.toByteArray()) }
                    ?: throw IllegalStateException("Could not open output stream")
                // Some DocumentsProvider implementations (certain file managers/Downloads
                // providers) have silently left behind a 0-byte file on write failure in the
                // past with no exception thrown — read it back so "Backup saved" is never shown
                // for a file that would fail to restore.
                val written = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (written == null || written.isEmpty()) {
                    throw IllegalStateException("file wrote empty, try a different save location")
                }
            }
            val suffix = if (pendingBackupScope.isFullBackup) "" else " (partial — some categories skipped)"
            binding.tvBackupStatus.text = "✓ Backup saved$suffix"
            Toast.makeText(this, "Backup saved$suffix", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showProviderHealthDialog() {
        Toast.makeText(this, "Checking providers…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val report = com.iptvapp.util.ProviderHealth.build(this@SettingsActivity, db, prefs)

            // Live reachability check for EVERY configured provider — the reliability-history
            // numbers above only exist for the primary provider (ChannelReliabilityEntity has
            // no serverIndex/merged-channel tracking at all), so "how's this other provider
            // doing" can only be answered as "is it responding right now", not a history.
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

            val builder = AlertDialog.Builder(this@SettingsActivity)
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
                    // version.json's "changelog" has always been published as a plain string,
                    // never a JSON array — optJSONArray() silently returns null for a string
                    // field, which meant this dialog always showed an empty "What's new" body.
                    // UpdateChecker.buildChangelog already has the correct array-or-string
                    // fallback (used by the automatic update popup) — reuse it here instead of
                    // re-duplicating (and re-breaking) the same logic.
                    val changelog = com.iptvapp.update.UpdateChecker(this@SettingsActivity).buildChangelog(obj)
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

    // Manual updates now go through the exact same UpdateChecker flow the automatic popup
    // uses: OkHttp download to app cache with a progress dialog, sha256 verification, install,
    // and deletion afterwards. The old DownloadManager-based path this replaces saved a
    // per-version APK into the app's external Downloads dir that nothing ever deleted, put a
    // download notification in the system tray, and choked on GitHub's S3 redirect chains
    // ("Download failed").
    private fun downloadAndInstall(apkUrl: String, versionName: String, expectedSha256: String?) {
        binding.tvUpdateStatus.text = "Downloading v$versionName..."
        com.iptvapp.update.UpdateChecker(this).downloadAndInstall(apkUrl, expectedSha256)
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
                val savedAudioLang = prefs.preferredAudioLanguage.first()
                binding.spinnerAudioLanguage.setSelection(
                    languageOptions.indexOfFirst { it.second == savedAudioLang }.coerceAtLeast(0)
                )
                val savedSubLang = prefs.preferredSubtitleLanguage.first()
                binding.spinnerSubtitleLanguage.setSelection(
                    languageOptions.indexOfFirst { it.second == savedSubLang }.coerceAtLeast(0)
                )
                binding.cbRefreshMissingOnly.isChecked = prefs.epgRefreshMissingOnly.first()
                binding.cbUsaOnlyChannels.isChecked = prefs.usaOnlyChannels.first()
                binding.cbEnglishOnlyMovies.isChecked = prefs.englishOnlyMovies.first()
                binding.cbAmoledBlack.isChecked = prefs.amoledBlack.first()
                binding.switchForceTvMode.isChecked = isForceTvModeEnabled()
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
                binding.switchTunneledPlayback.isChecked = prefs.tunneledPlaybackEnabled.first()
                binding.switchDv7Fallback.isChecked = prefs.dv7FallbackEnabled.first()
                binding.switchAudioPassthroughFallback.isChecked = prefs.audioPassthroughFallbackEnabled.first()
                binding.switchAutoplayNextEpisode.isChecked = prefs.autoplayNextEpisodeEnabled.first()
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
                currentAccentColorEnd = prefs.accentColorEnd.first()
                setupAccentPicker()
                applyAccentToSettings(Color.parseColor(currentAccentColor))
                setupSubtitleSettings()
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
                text = "PRIMARY PROVIDER"
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
            // Extra providers already show their URL directly (added earlier so a stray
            // space/typo could be spotted) — the primary row never did, making it impossible
            // to verify the nickname you see actually matches the credentials really in use.
            android.widget.TextView(this@SettingsActivity).apply {
                text = creds.serverUrl
                setTextColor(Color.parseColor("#777777"))
                textSize = 11f
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                primaryRow.addView(this)
            }
            android.widget.Button(this@SettingsActivity).apply {
                text = "Edit"
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#333333"))
                val heightPx = (40 * resources.displayMetrics.density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, heightPx
                ).also { it.topMargin = 12 }
                setOnClickListener { showEditPrimaryDialog(creds, primaryNick) }
                primaryRow.addView(this)
            }
            ll.addView(primaryRow)

            extraServers.forEachIndexed { i, server ->
                val url = server[0]; val user = server[1]
                val nick = server.getOrElse(3) { "" }.ifEmpty { user }
                val enabled = server.getOrElse(5) { "true" }.toBoolean()
                val row = android.widget.LinearLayout(this@SettingsActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#1A1A1A"))
                    setPadding(24, 20, 24, 20)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 12 }
                    // Dimmed while disabled — same "muted" visual language the app already uses
                    // (e.g. the #555555 INACTIVE label just below) rather than inventing a new
                    // convention. Credentials/nickname/URL stay fully readable, just de-emphasized.
                    alpha = if (enabled) 1f else 0.45f
                }
                android.widget.TextView(this@SettingsActivity).apply {
                    text = "PROVIDER ${i + 2}"
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
                android.widget.TextView(this@SettingsActivity).apply {
                    text = url
                    setTextColor(Color.parseColor("#777777"))
                    textSize = 11f
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    row.addView(this)
                }
                android.widget.Switch(this@SettingsActivity).apply {
                    text = if (enabled) "Enabled" else "Disabled"
                    isChecked = enabled
                    setTextColor(Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = 12 }
                    setOnCheckedChangeListener { _, checked ->
                        val updated = extraServers[i].toMutableList()
                        while (updated.size < 6) updated.add("true")
                        updated[5] = checked.toString()
                        extraServers[i] = updated
                        lifecycleScope.launch {
                            prefs.saveExtraServersWithNick(extraServers)
                            // Disabling is architecturally a temporary Remove — same cache-clear
                            // as the Remove button, so a disabled provider's stale favorited
                            // items don't linger in the aggregate Favorites views until the next
                            // full app restart.
                            db.mergedChannelDao().clearAll()
                            db.mergedVodDao().clearAll()
                            db.mergedSeriesDao().clearAll()
                        }
                        updateServerList()
                    }
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
                    text = "Edit"
                    isAllCaps = false
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#333333"))
                    val heightPx = (40 * resources.displayMetrics.density).toInt()
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, heightPx, 1f)
                        .also { it.marginEnd = 8 }
                    setOnClickListener { showEditServerDialog(i) }
                    btnRow.addView(this)
                }
                android.widget.Button(this@SettingsActivity).apply {
                    text = "Switch"
                    isAllCaps = false
                    textSize = 13f
                    setTextColor(Color.BLACK)
                    // setBackgroundColor() replaces the Material theme's rounded button
                    // background with a flat square rectangle — backgroundTintList keeps the
                    // theme's rounded shape/ripple and just recolors it, matching every other
                    // button in the app (all of which set backgroundTint, never a raw color).
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#008CFF"))
                    val heightPx = (40 * resources.displayMetrics.density).toInt()
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, heightPx, 1f)
                        .also { it.marginEnd = 8 }
                    setOnClickListener {
                        lifecycleScope.launch {
                            val primary = prefs.credentials.first()
                            val newPass = extraServers[i][2]
                            // The nickname you gave this extra server was never carried over
                            // to prefs.serverNickname (the single global "current primary's
                            // nickname" field) — switching kept showing whatever nickname the
                            // OLD primary had, making a freshly-edited nickname look like it
                            // had vanished.
                            val newNick = extraServers[i].getOrElse(3) { "" }
                            val updated = extraServers.toMutableList()
                            updated[i] = listOf(primary.serverUrl, primary.username, primary.password, prefs.serverNickname.first())
                            // The provider becoming primary may already have favorites recorded
                            // from when it was a secondary provider — those don't automatically
                            // carry over just because its role changed, so capture them now
                            // (before its old merged-provider identity/index is repurposed) and
                            // they'll reapply once its channels are fetched as the new primary.
                            repository.capturePendingPrimaryFavoritesFrom(i)
                            prefs.saveExtraServersWithNick(updated)
                            // Scoped to just the OLD primary's data — merged/other-provider
                            // favorites, folders, and pinned categories must survive a primary
                            // switch (clearAllTables() used to wipe those too).
                            repository.clearPrimaryProviderData()
                            prefs.saveCredentials(url, user, newPass)
                            prefs.setServerNickname(newNick)
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
                    isAllCaps = false
                    textSize = 13f
                    setTextColor(Color.parseColor("#FF6B6B"))
                    // Matches the app's established "danger" button convention (e.g. Trakt
                    // Disconnect): a subdued dark fill with red text, not a solid red block.
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E1E1E"))
                    val heightPx = (40 * resources.displayMetrics.density).toInt()
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, heightPx, 1f)
                    setOnClickListener {
                        extraServers.removeAt(i)
                        lifecycleScope.launch {
                            prefs.saveExtraServersWithNick(extraServers)
                            // Removing a server shifts every later server's index, which could
                            // silently re-attribute stale merged-channel/VOD/series rows to the
                            // wrong server until the next manual refresh — just clear the caches.
                            // mergedVodDao/mergedSeriesDao were added after this clear was
                            // originally written and had been missed until now.
                            db.mergedChannelDao().clearAll()
                            db.mergedVodDao().clearAll()
                            db.mergedSeriesDao().clearAll()
                        }
                        updateServerList()
                    }
                    btnRow.addView(this)
                }
                row.addView(btnRow)
                // Per-provider live-channel refresh — deliberately separate from the Movies/
                // Series refresh buttons in the Display section, and from Home's "Refresh All
                // Providers" (which touches every configured server at once). This only
                // re-fetches THIS provider's live channels/categories.
                android.widget.Button(this@SettingsActivity).apply {
                    text = "↻ Refresh Channels"
                    isAllCaps = false
                    textSize = 13f
                    setTextColor(Color.parseColor("#008CFF"))
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E1E1E"))
                    val heightPx = (40 * resources.displayMetrics.density).toInt()
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, heightPx
                    ).also { it.topMargin = 8 }
                    setOnClickListener {
                        isEnabled = false
                        val originalText = text
                        text = "Refreshing…"
                        lifecycleScope.launch {
                            val errors = repository.refreshMergedChannels(i)
                            isEnabled = true
                            text = originalText
                            val msg = errors[i]?.let { err -> "Failed: $err" } ?: "Channels refreshed"
                            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    row.addView(this)
                }
                ll.addView(row)
            }
        }
    }

    private fun showEditPrimaryDialog(creds: com.iptvapp.data.local.ServerCredentials, currentNick: String) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        fun android.widget.EditText.disableAutofill() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                setAutofillHints(null)
            }
        }
        val etNick = android.widget.EditText(this).apply {
            hint = "Nickname (optional)"; setText(currentNick); disableAutofill()
        }
        val etUrl = android.widget.EditText(this).apply {
            hint = "Provider URL (http://...)"; setText(creds.serverUrl); disableAutofill()
        }
        val etUser = android.widget.EditText(this).apply {
            hint = "Username"; setText(creds.username); disableAutofill()
        }
        val etPass = android.widget.EditText(this).apply {
            hint = "Password"
            setText(creds.password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            disableAutofill()
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
                        Toast.makeText(this@SettingsActivity, "Primary provider updated", Toast.LENGTH_SHORT).show()
                        updateServerList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditServerDialog(index: Int) {
        val server = extraServers.getOrNull(index) ?: return
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        fun android.widget.EditText.disableAutofill() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                setAutofillHints(null)
            }
        }
        val etNick = android.widget.EditText(this).apply {
            hint = "Nickname (optional)"; setText(server.getOrElse(3) { "" }); disableAutofill()
        }
        val etUrl = android.widget.EditText(this).apply {
            hint = "Provider URL (http://...)"; setText(server.getOrElse(0) { "" }); disableAutofill()
        }
        val etUser = android.widget.EditText(this).apply {
            hint = "Username"; setText(server.getOrElse(1) { "" }); disableAutofill()
        }
        val etPass = android.widget.EditText(this).apply {
            hint = "Password"
            setText(server.getOrElse(2) { "" })
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            disableAutofill()
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
        val etEpg = android.widget.EditText(this).apply {
            hint = "EPG URL (optional, http://...)"
            setText(server.getOrElse(4) { "" })
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            disableAutofill()
        }
        val tvTestResult = android.widget.TextView(this).apply {
            textSize = 12f
            setPadding(0, 12, 0, 0)
            visibility = View.GONE
        }
        val btnTest = android.widget.Button(this).apply {
            text = "Test Connection"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#333333"))
            setOnClickListener {
                val url = etUrl.text.toString().replace(" ", "").trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                if (url.isBlank() || user.isBlank()) {
                    Toast.makeText(this@SettingsActivity, "Enter a URL and username first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isEnabled = false
                tvTestResult.visibility = View.VISIBLE
                tvTestResult.text = "Testing…"
                tvTestResult.setTextColor(Color.parseColor("#888888"))
                lifecycleScope.launch {
                    val result = repository.testProviderConnection(url, user, pass)
                    isEnabled = true
                    if (result.reachable) {
                        tvTestResult.text = "✓ Connected (${result.responseMs}ms)"
                        tvTestResult.setTextColor(Color.parseColor("#4CD964"))
                    } else {
                        tvTestResult.text = "✗ Failed — ${result.error}"
                        tvTestResult.setTextColor(Color.parseColor("#FF6B6B"))
                    }
                }
            }
        }
        layout.addView(etNick); layout.addView(etUrl); layout.addView(etUser); layout.addView(etPass); layout.addView(cbShowPass); layout.addView(etEpg)
        layout.addView(btnTest); layout.addView(tvTestResult)
        AlertDialog.Builder(this)
            .setTitle("Edit Provider")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                // A URL should never legitimately contain whitespace — strip it all, not just
                // leading/trailing, since a stray space pasted mid-string (e.g. from a wrapped
                // line) silently breaks every request to that server with no visible error.
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
                        Toast.makeText(this@SettingsActivity, "Provider updated", Toast.LENGTH_SHORT).show()
                        updateServerList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val etUrl  = android.widget.EditText(this).apply { hint = "Provider URL (http://...)"; disableAutofill() }
        val etUser = android.widget.EditText(this).apply { hint = "Username"; disableAutofill() }
        val etPass = android.widget.EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            disableAutofill()
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
        val etEpg = android.widget.EditText(this).apply {
            hint = "EPG URL (optional, http://...)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            disableAutofill()
        }
        val tvTestResult = android.widget.TextView(this).apply {
            textSize = 12f
            setPadding(0, 12, 0, 0)
            visibility = View.GONE
        }
        // Nothing validated a provider's credentials before saving it — a typo'd URL or wrong
        // password just got saved silently and only surfaced later (or never) as an unrelated
        // failure elsewhere. This tests the actual connection right here, before Add is tapped.
        val btnTest = android.widget.Button(this).apply {
            text = "Test Connection"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#333333"))
            setOnClickListener {
                val url = etUrl.text.toString().replace(" ", "").trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                if (url.isBlank() || user.isBlank()) {
                    Toast.makeText(this@SettingsActivity, "Enter a URL and username first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isEnabled = false
                tvTestResult.visibility = View.VISIBLE
                tvTestResult.text = "Testing…"
                tvTestResult.setTextColor(Color.parseColor("#888888"))
                lifecycleScope.launch {
                    val result = repository.testProviderConnection(url, user, pass)
                    isEnabled = true
                    if (result.reachable) {
                        tvTestResult.text = "✓ Connected (${result.responseMs}ms)"
                        tvTestResult.setTextColor(Color.parseColor("#4CD964"))
                    } else {
                        tvTestResult.text = "✗ Failed — ${result.error}"
                        tvTestResult.setTextColor(Color.parseColor("#FF6B6B"))
                    }
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
                val epgUrl = etEpg.text.toString().replace(" ", "").trim()
                if (url.isNotEmpty() && user.isNotEmpty()) {
                    lifecycleScope.launch {
                        val fresh = prefs.getExtraServersWithNick().toMutableList()
                        fresh.add(listOf(url, user, pass, etNick.text.toString().trim(), epgUrl))
                        extraServers.clear()
                        extraServers.addAll(fresh)
                        prefs.saveExtraServersWithNick(extraServers)
                        Toast.makeText(this@SettingsActivity, "Provider added", Toast.LENGTH_SHORT).show()
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
                        "(${result.episodesMarked} episodes marked watched) — tap to view"
                binding.tvTraktSyncStatus.setOnClickListener {
                    showTraktSyncResultDialog(result)
                }
            }
        }
    }

    // One combined dialog: matched shows are real list rows you can tap straight into
    // SeriesDetailActivity (same extras Home's own primary-series list passes); unmatched
    // titles/movies are shown as plain reference text below since they have no local series to
    // open. Movies aren't listed as tappable rows since there's no per-title movie detail deep
    // link as useful as jumping into a show's episode list.
    private fun showTraktSyncResultDialog(result: com.iptvapp.trakt.TraktManager.SyncBackResult) {
        val container = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        container.setPadding(dp(8), dp(8), dp(8), dp(8))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Trakt Watched History")
            .setView(android.widget.ScrollView(this).apply { addView(container) })
            .setPositiveButton("Close", null)
            .show()

        if (result.matchedShows.isNotEmpty()) {
            container.addView(android.widget.TextView(this).apply {
                text = "Shows (tap to open):"
                setPadding(dp(8), dp(8), dp(8), dp(4))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            result.matchedShows.forEach { show ->
                container.addView(android.widget.TextView(this).apply {
                    text = show.name
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    isClickable = true
                    isFocusable = true
                    val outValue = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    setOnClickListener {
                        dialog.dismiss()
                        startActivity(Intent(this@SettingsActivity, com.iptvapp.ui.series.SeriesDetailActivity::class.java).apply {
                            putExtra("series_id", show.seriesId)
                            putExtra("series_name", show.name)
                            putExtra("series_cover", show.cover)
                            putExtra("series_genre", show.genre)
                            putExtra("series_rating", show.rating)
                            putExtra("series_plot", show.plot)
                        })
                    }
                })
            }
        }

        if (result.unmatchedMovies.isNotEmpty() || result.unmatchedShows.isNotEmpty()) {
            container.addView(android.widget.TextView(this).apply {
                text = "Not found in your library:"
                setPadding(dp(8), dp(16), dp(8), dp(4))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            (result.unmatchedMovies + result.unmatchedShows).forEach { title ->
                container.addView(android.widget.TextView(this).apply {
                    text = "•  $title"
                    setPadding(dp(16), dp(4), dp(16), dp(4))
                })
            }
        }

        if (result.matchedShows.isEmpty() && result.unmatchedMovies.isEmpty() && result.unmatchedShows.isEmpty()) {
            container.addView(android.widget.TextView(this).apply {
                text = "No watched movies or shows found on Trakt."
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
        }
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
                val lastError = traktManager.lastScrobbleError.value
                binding.tvTraktStatus.text = if (lastError != null) {
                    "✓ Connected — last scrobble failed: $lastError"
                } else {
                    "✓ Connected — scrobbling your watch activity"
                }
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
                if (code.isNotBlank()) prefs.addSavedPairingCode(code.uppercase())
                Toast.makeText(this@SettingsActivity, if (code.isBlank()) "Pairing code cleared" else "Paired ✓ — tap Pull from Cloud", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnSavedPairingCodes.setOnClickListener { showSavedPairingCodesDialog() }
        binding.switchSyncEnabled.setOnCheckedChangeListener { _, enabled ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setSyncEnabled(enabled) }
            if (enabled) scheduleAutoSync() else cancelAutoSync()
        }
        binding.switchTunneledPlayback.setOnCheckedChangeListener { _, enabled ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setTunneledPlaybackEnabled(enabled) }
        }
        binding.switchDv7Fallback.setOnCheckedChangeListener { _, enabled ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setDv7FallbackEnabled(enabled) }
        }
        binding.switchAudioPassthroughFallback.setOnCheckedChangeListener { _, enabled ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setAudioPassthroughFallbackEnabled(enabled) }
        }
        binding.switchAutoplayNextEpisode.setOnCheckedChangeListener { _, enabled ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setAutoplayNextEpisodeEnabled(enabled) }
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

    private fun showSavedPairingCodesDialog() {
        lifecycleScope.launch {
            val codes = prefs.getSavedPairingCodes()
            if (codes.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "No saved codes yet — pair with one first", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Long-press an entry to remove it — same convention as favorite folders' "hold to
            // manage" pattern elsewhere in this screen, rather than a separate edit mode.
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Saved Pairing Codes")
                .setItems(codes.toTypedArray()) { _, i ->
                    val code = codes[i]
                    binding.etGithubToken.setText(code)
                    lifecycleScope.launch {
                        syncManager.setPairingCode(code)
                        prefs.addSavedPairingCode(code)
                        Toast.makeText(this@SettingsActivity, "Paired with $code ✓ — tap Pull from Cloud", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Manage") { _, _ -> showManagePairingCodesDialog(codes) }
                .show()
        }
    }

    private fun showManagePairingCodesDialog(codes: List<String>) {
        val checked = BooleanArray(codes.size)
        AlertDialog.Builder(this)
            .setTitle("Remove Saved Codes")
            .setMultiChoiceItems(codes.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Remove Selected") { _, _ ->
                lifecycleScope.launch {
                    codes.forEachIndexed { i, code -> if (checked[i]) prefs.removeSavedPairingCode(code) }
                    Toast.makeText(this@SettingsActivity, "Removed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Was hardcoded to the primary server only (a single prefs.credentials read) — now tests
    // every currently-active provider (primary + enabled merged/secondary ones), same list
    // Provider Health already covers, since "test all the active providers" should mean exactly
    // that rather than just the one account this screen happens to be logged in as.
    private suspend fun runSpeedTest() {
        binding.btnSpeedTest.isEnabled = false
        binding.tvSpeedTestResult.text = "Testing all active providers…"
        try {
            val results = repository.runSpeedTestForAllProviders()
            binding.tvSpeedTestResult.text = results.joinToString("\n\n") { r ->
                val tcpStr = if (r.tcpAvgMs != null) "TCP Ping: ${r.tcpAvgMs}ms avg (${r.tcpSuccessCount}/3)" else "TCP Ping: failed"
                val httpStr = if (r.httpMs != null) "HTTP Response: ${r.httpMs}ms" else "HTTP Response: failed"
                val errorLine = r.error?.let { "\n$it" } ?: ""
                "${r.nickname}\n$tcpStr\n$httpStr\nServer: ${r.host}$errorLine"
            }
        } catch (e: Exception) {
            binding.tvSpeedTestResult.text = "Error: ${e.message}"
        } finally {
            binding.btnSpeedTest.isEnabled = true
        }
    }

    companion object {
        private const val AUTO_EPG_WORK_NAME = "auto_epg_refresh_work"
    }

    private suspend fun buildBackupJson(scope: BackupScope = BackupScope()): JSONObject = withContext(Dispatchers.IO) {
        val creds = prefs.credentials.first()
        JSONObject().apply {
            // Login + core playback/EPG settings + tab visibility are always included — a
            // backup missing the login is useless, and these are small enough to never be
            // worth excluding (see BackupScope kdoc).
            put("serverUrl", creds.serverUrl)
            put("username", creds.username)
            put("password", creds.password)
            put("epgUrl", prefs.epgUrl.first())
            put("preferredFormat", prefs.preferredFormat.first())
            put("preferredAudioLanguage", prefs.preferredAudioLanguage.first())
            put("preferredSubtitleLanguage", prefs.preferredSubtitleLanguage.first())
            put("epgAutoRefreshHours", prefs.epgAutoRefreshHours.first())
            put("epgRefreshMissingOnly", prefs.epgRefreshMissingOnly.first())
            put("usaOnlyChannels", prefs.usaOnlyChannels.first())
            put("showMovies", prefs.showMovies.first())
            put("showSeries", prefs.showSeries.first())
            put("showWatching", prefs.showWatching.first())
            // Display/playback/misc toggles — previously missing from every backup entirely,
            // so restoring onto a new device silently reset all of these to defaults instead of
            // "get back to exactly how I had it." No security reason to exclude any of these
            // (unlike Trakt/GitHub tokens below, which are live credentials).
            put("accentColor", prefs.accentColor.first())
            put("accentColorEnd", prefs.accentColorEnd.first())
            put("amoledBlack", prefs.amoledBlack.first())
            put("externalPlayer", prefs.externalPlayer.first())
            put("tunneledPlaybackEnabled", prefs.tunneledPlaybackEnabled.first())
            put("dv7FallbackEnabled", prefs.dv7FallbackEnabled.first())
            put("audioPassthroughFallbackEnabled", prefs.audioPassthroughFallbackEnabled.first())
            put("autoplayNextEpisodeEnabled", prefs.autoplayNextEpisodeEnabled.first())
            put("extraBufferingEnabled", prefs.extraBufferingEnabled.first())
            put("silentSelfUpdateEnabled", prefs.silentSelfUpdateEnabled.first())
            put("crashReportingEnabled", prefs.crashReportingEnabled.first())
            put("recordingFolderName", prefs.recordingFolderName.first())
            put("autoDeleteRecordingsDays", prefs.autoDeleteRecordingsDays.first())

            val folders = db.favoriteFolderDao().getAll().first()
            val folderNameById = folders.associate { it.id to it.name }

            if (scope.favorites) {
                put("favoriteCategoryIds", JSONArray(prefs.favoriteLiveCategoryIds.first().toList()))
                put("favoriteChannelIds", JSONArray(db.channelDao().getFavoriteChannelIds()))
                // Folder ids are local autoincrement values (not portable across a restore onto
                // a different/reset device), so folders are saved by NAME — same approach
                // SyncManager already uses. Previously omitted entirely, so restoring a backup
                // brought favorites back but dumped every one into Unsorted.
                val channelFolders = db.channelDao().getFavoriteChannelsBlocking()
                    .mapNotNull { ch -> ch.favoriteFolderId?.let { fid -> folderNameById[fid]?.let { name -> ch.streamId.toString() to name } } }
                    .toMap()
                put("favoriteFolders", JSONArray(folders.map { it.name }))
                put("channelFolders", JSONObject(channelFolders))
            }

            if (scope.watchHistory) {
                put("watchHistory", JSONArray(db.channelDao().getWatchHistoryForBackup().map {
                    JSONObject().apply {
                        put("streamId", it.streamId)
                        put("lastWatched", it.lastWatched)
                        put("viewCount", it.viewCount)
                    }
                }))
                // VOD/series watch progress and per-episode watched state — same fields/shape
                // SyncManager already pushes to Firebase, so a restored backup resumes movies/
                // shows from where they left off instead of starting over. Only rows with real
                // progress are included, same reasoning as favoriteChannelIds only including
                // actual favorites.
                put("vodProgress", JSONObject(db.vodDao().getUserData()
                    .filter { it.watchedMs > 0 }
                    .associate { it.streamId.toString() to JSONObject().apply {
                        put("watchedMs", it.watchedMs); put("durationMs", it.durationMs)
                    } }))
                put("seriesProgress", JSONObject(db.seriesDao().getUserData()
                    .filter { it.watchedMs > 0 }
                    .associate { it.seriesId.toString() to JSONObject().apply {
                        put("watchedMs", it.watchedMs); put("durationMs", it.durationMs)
                    } }))
                put("episodesWatched", JSONArray(db.episodeWatchedDao().getAll().map {
                    JSONObject().apply {
                        put("seriesId", it.seriesId); put("season", it.season); put("episode", it.episode)
                        put("watchedAt", it.watchedAt); put("watchedMs", it.watchedMs); put("durationMs", it.durationMs)
                    }
                }))
            }

            if (scope.extraProviders) {
                // Extra providers (the "Providers" merged-browse feature) were never included in
                // any backup — restoring one silently dropped every non-primary provider. Trakt's
                // OAuth tokens are deliberately NOT included here: unlike the rest of this file,
                // that's a live credential, and this JSON can end up shared/exported (QR backup,
                // emailed file) — reconnecting Trakt after a restore is a small one-time action,
                // copying a bearer token into a plaintext file users might hand to someone else is not.
                put("extraServers", JSONArray(prefs.getExtraServersWithNick().map { s ->
                    JSONObject().apply {
                        put("url", s[0]); put("user", s[1]); put("pass", s[2])
                        put("nick", s.getOrElse(3) { "" }); put("epg", s.getOrElse(4) { "" })
                    }
                }))

                // Merged/other-provider favorites, folder assignments, and pinned categories —
                // keyed by server URL (not serverIndex, which is meaningless across devices/
                // restores) so this matches a restore onto a device with providers configured in
                // a different order. Previously omitted entirely — restoring a backup brought the
                // provider list back but dropped every other-provider favorite silently.
                val mergedUrlByIndex = repository.getMergedServerUrls()
                val mergedFavorites = db.mergedChannelDao().getAllFavorites().first()
                val mergedFolderNameById = folderNameById
                put("mergedFavorites", JSONArray(mergedFavorites.mapNotNull { ch ->
                    val url = mergedUrlByIndex[ch.serverIndex] ?: return@mapNotNull null
                    JSONObject().apply {
                        put("serverUrl", url)
                        put("streamId", ch.streamId)
                        ch.favoriteFolderId?.let { fid -> mergedFolderNameById[fid]?.let { put("folderName", it) } }
                    }
                }))
                val favoriteMergedCategoryKeys = prefs.favoriteMergedCategoryIds.first()
                put("mergedFavoriteCategories", JSONArray(favoriteMergedCategoryKeys.mapNotNull { key ->
                    val serverIndex = key.substringBefore(':', "").toIntOrNull() ?: return@mapNotNull null
                    val categoryId = key.substringAfter(':', "")
                    val url = mergedUrlByIndex[serverIndex] ?: return@mapNotNull null
                    JSONObject().apply { put("serverUrl", url); put("categoryId", categoryId) }
                }))

                // Merged VOD/Series favorites — same URL-keyed shape as mergedFavorites above, no
                // category equivalent (neither DAO has a per-category favorite concept). Previously
                // omitted entirely — restoring a backup brought merged live-channel favorites back
                // but silently dropped every merged movie/show favorite.
                val mergedVodFavorites = db.mergedVodDao().getAllFavorites().first()
                put("mergedVodFavorites", JSONArray(mergedVodFavorites.mapNotNull { v ->
                    val url = mergedUrlByIndex[v.serverIndex] ?: return@mapNotNull null
                    JSONObject().apply {
                        put("serverUrl", url)
                        put("streamId", v.streamId)
                        v.favoriteFolderId?.let { fid -> mergedFolderNameById[fid]?.let { put("folderName", it) } }
                    }
                }))
                val mergedSeriesFavorites = db.mergedSeriesDao().getAllFavorites().first()
                put("mergedSeriesFavorites", JSONArray(mergedSeriesFavorites.mapNotNull { s ->
                    val url = mergedUrlByIndex[s.serverIndex] ?: return@mapNotNull null
                    JSONObject().apply {
                        put("serverUrl", url)
                        put("seriesId", s.seriesId)
                        s.favoriteFolderId?.let { fid -> mergedFolderNameById[fid]?.let { put("folderName", it) } }
                    }
                }))

                // Hidden categories in Providers > Movies/Series — same URL-keyed shape as the
                // favorites above, separate concept (see PreferencesManager.HIDDEN_MERGED_VOD_
                // CATEGORY_IDS kdoc).
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
            }

            if (scope.subtitleStyle) {
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
    }



    private fun showRestoreDialog() {
        openBackupLauncher.launch(arrayOf("application/json"))
    }

    private suspend fun restoreBackupFromUri(uri: Uri) {
        try {
            val jsonText = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } ?: return
            applyBackupJson(JSONObject(jsonText))
        } catch (e: Exception) {
            Toast.makeText(this, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        if (json.has("preferredAudioLanguage")) prefs.setPreferredAudioLanguage(json.optString("preferredAudioLanguage", ""))
        if (json.has("preferredSubtitleLanguage")) prefs.setPreferredSubtitleLanguage(json.optString("preferredSubtitleLanguage", ""))
        if (json.has("epgAutoRefreshHours")) prefs.setEpgAutoRefreshHours(json.optInt("epgAutoRefreshHours", 0))
        if (json.has("epgRefreshMissingOnly")) prefs.setEpgRefreshMissingOnly(json.optBoolean("epgRefreshMissingOnly", false))
        if (json.has("usaOnlyChannels")) prefs.setUsaOnlyChannels(json.optBoolean("usaOnlyChannels", true))
        if (json.has("showMovies")) prefs.setShowMovies(json.optBoolean("showMovies", true))
        if (json.has("showSeries")) prefs.setShowSeries(json.optBoolean("showSeries", true))
        if (json.has("showWatching")) prefs.setShowWatching(json.optBoolean("showWatching", true))
        json.optString("accentColor", "").takeIf { it.isNotEmpty() }?.let { start ->
            val end = json.optString("accentColorEnd", "")
            if (end.isNotEmpty()) prefs.setAccentGradient(start, end) else prefs.setAccentColor(start)
        }
        if (json.has("amoledBlack")) prefs.setAmoledBlack(json.optBoolean("amoledBlack", false))
        json.optString("externalPlayer", "").takeIf { it.isNotEmpty() }?.let { prefs.setExternalPlayer(it) }
        if (json.has("tunneledPlaybackEnabled")) prefs.setTunneledPlaybackEnabled(json.optBoolean("tunneledPlaybackEnabled", false))
        if (json.has("dv7FallbackEnabled")) prefs.setDv7FallbackEnabled(json.optBoolean("dv7FallbackEnabled", false))
        if (json.has("audioPassthroughFallbackEnabled")) prefs.setAudioPassthroughFallbackEnabled(json.optBoolean("audioPassthroughFallbackEnabled", false))
        if (json.has("autoplayNextEpisodeEnabled")) prefs.setAutoplayNextEpisodeEnabled(json.optBoolean("autoplayNextEpisodeEnabled", true))
        if (json.has("extraBufferingEnabled")) prefs.setExtraBufferingEnabled(json.optBoolean("extraBufferingEnabled", true))
        if (json.has("silentSelfUpdateEnabled")) prefs.setSilentSelfUpdateEnabled(json.optBoolean("silentSelfUpdateEnabled", false))
        if (json.has("crashReportingEnabled")) prefs.setCrashReportingEnabled(json.optBoolean("crashReportingEnabled", true))
        json.optString("recordingFolderName", "").takeIf { it.isNotEmpty() }?.let { prefs.setRecordingFolderName(it) }
        if (json.has("autoDeleteRecordingsDays")) prefs.setAutoDeleteRecordingsDays(json.optInt("autoDeleteRecordingsDays", 0))

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
            val existingIds = db.channelDao().getAllChannelIds().toSet()
            remoteChannelFolders.forEach { (streamId, folderName) ->
                if (streamId in existingIds) {
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

        // Merged/other-provider favorites can't be applied yet — that provider's channels
        // haven't been fetched into merged_channels at restore time (only channel IDs from the
        // backup, no local rows to mark isFavorite=1 on exist). Stored as pending, keyed by
        // server URL, and applied automatically the next time that server's channels are
        // refreshed (see XtreamRepository.refreshMergedChannels).
        json.optJSONArray("mergedFavorites")?.let { arr ->
            val keys = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                "${obj.optString("serverUrl")}|${obj.optInt("streamId")}"
            }.toSet()
            lifecycleScope.launch { prefs.setPendingMergedFavorites(keys) }
            val folderKeys = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val folderName = obj.optString("folderName", "")
                if (folderName.isBlank()) null
                else "${obj.optString("serverUrl")}|${obj.optInt("streamId")}|$folderName"
            }.toSet()
            if (folderKeys.isNotEmpty()) lifecycleScope.launch { prefs.setPendingMergedChannelFolders(folderKeys) }
        }
        json.optJSONArray("mergedFavoriteCategories")?.let { arr ->
            val keys = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                "${obj.optString("serverUrl")}|${obj.optString("categoryId")}"
            }.toSet()
            lifecycleScope.launch { prefs.setPendingMergedFavoriteCategories(keys) }
        }
        // Merged VOD/Series favorites — same pending-apply mechanism as mergedFavorites above,
        // consumed by XtreamRepository.refreshMergedVod/refreshMergedSeries once that server's
        // catalog is actually fetched.
        json.optJSONArray("mergedVodFavorites")?.let { arr ->
            val keys = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                "${obj.optString("serverUrl")}|${obj.optInt("streamId")}"
            }.toSet()
            lifecycleScope.launch { prefs.setPendingMergedVodFavorites(keys) }
            val folderKeys = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val folderName = obj.optString("folderName", "")
                if (folderName.isBlank()) null
                else "${obj.optString("serverUrl")}|${obj.optInt("streamId")}|$folderName"
            }.toSet()
            if (folderKeys.isNotEmpty()) lifecycleScope.launch { prefs.setPendingMergedVodFolders(folderKeys) }
        }
        json.optJSONArray("mergedSeriesFavorites")?.let { arr ->
            val keys = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                "${obj.optString("serverUrl")}|${obj.optInt("seriesId")}"
            }.toSet()
            lifecycleScope.launch { prefs.setPendingMergedSeriesFavorites(keys) }
            val folderKeys = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val folderName = obj.optString("folderName", "")
                if (folderName.isBlank()) null
                else "${obj.optString("serverUrl")}|${obj.optInt("seriesId")}|$folderName"
            }.toSet()
            if (folderKeys.isNotEmpty()) lifecycleScope.launch { prefs.setPendingMergedSeriesFolders(folderKeys) }
        }
        json.optJSONArray("hiddenMergedVodCategories")?.let { arr ->
            val keys = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                "${obj.optString("serverUrl")}|${obj.optString("categoryId")}"
            }.toSet()
            lifecycleScope.launch { prefs.setPendingHiddenMergedVodCategories(keys) }
        }
        json.optJSONArray("hiddenMergedSeriesCategories")?.let { arr ->
            val keys = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                "${obj.optString("serverUrl")}|${obj.optString("categoryId")}"
            }.toSet()
            lifecycleScope.launch { prefs.setPendingHiddenMergedSeriesCategories(keys) }
        }

        // VOD/series watch progress + per-episode watched state. Restore is a full overwrite
        // (unlike SyncManager.syncDown, which merges by "keep the larger watchedMs" since two
        // devices can both have made independent progress) — a restore is a deliberate "put me
        // back to this exact state" action, so the backup's numbers just win outright. Rows for
        // VOD/series not yet fetched locally are silently skipped (no pending-apply mechanism
        // for this, unlike mergedFavorites — VOD/series lists are already populated in the
        // overwhelmingly common restore-onto-an-already-set-up-device case).
        json.optJSONObject("vodProgress")?.let { obj ->
            obj.keys().forEach { key ->
                val streamId = key.toIntOrNull() ?: return@forEach
                val p = obj.getJSONObject(key)
                db.vodDao().updateWatchProgress(streamId, p.optLong("watchedMs", 0L), p.optLong("durationMs", 0L))
            }
        }
        json.optJSONObject("seriesProgress")?.let { obj ->
            obj.keys().forEach { key ->
                val seriesId = key.toIntOrNull() ?: return@forEach
                val p = obj.getJSONObject(key)
                db.seriesDao().updateWatchProgress(seriesId, p.optLong("watchedMs", 0L), p.optLong("durationMs", 0L))
            }
        }
        json.optJSONArray("episodesWatched")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val seriesId = e.optInt("seriesId", -1)
                val season = e.optInt("season", -1)
                val episode = e.optInt("episode", -1)
                if (seriesId < 0 || season < 0 || episode < 0) continue
                db.episodeWatchedDao().upsert(
                    com.iptvapp.data.local.entities.EpisodeWatchedEntity(
                        seriesId = seriesId, season = season, episode = episode,
                        watchedAt = e.optLong("watchedAt", 0L),
                        watchedMs = e.optLong("watchedMs", 0L),
                        durationMs = e.optLong("durationMs", 0L)
                    )
                )
            }
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

        // Reload UI after all prefs are set
        loadSettings()
        binding.tvBackupStatus.text = "✓ Restored successfully"
        Toast.makeText(this, "Restore complete", Toast.LENGTH_SHORT).show()
    }
}
