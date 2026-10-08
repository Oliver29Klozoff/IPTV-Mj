package com.iptvapp.ui.settings
import com.iptvapp.BuildConfig
import com.iptvapp.util.Resource

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
import androidx.core.view.doOnNextLayout
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
import com.iptvapp.util.DebugInfoCollector
import com.iptvapp.util.LanExportServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import com.iptvapp.util.isForceTvModeEnabled
import com.iptvapp.util.isLargeScreenDevice
import com.iptvapp.util.setForceTvModeEnabled
import com.iptvapp.util.versionCodeCompat
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ActivitySettingsBinding
import com.iptvapp.ui.onboarding.FeatureTourDialog
import com.iptvapp.worker.AutoBackupWorker
import com.iptvapp.worker.EpgRefreshWorker
import com.iptvapp.worker.NewEpisodeCheckWorker
import androidx.work.Constraints
import androidx.work.NetworkType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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
            + "Extra Buffering: builds up a bigger buffer before playback starts, "
            + "trading a slower start for fewer stalls mid-stream on slow/unreliable "
            + "connections. On by default.\n\n"
            + "Remind Me Before a Show: how long before a program starts a Guide \"Remind Me\" "
            + "notification fires — gives you time to actually switch over instead of finding "
            + "out right as it starts.\n\n"
            + "Live Reconnect Speed: how fast a live channel keeps retrying after a "
            + "dropped/stalled connection. Aggressive retries quickly with a lower ceiling; "
            + "Patient waits longer between attempts — useful for slower or less reliable "
            + "providers.\n\n"
            + "Data Saver: caps video at 480p / about 1.2 Mbps for slow Wi-Fi like on a plane. "
            + "Only helps on channels your provider offers in more than one quality — a "
            + "single-quality channel plays as usual. Takes effect the next time a channel "
            + "starts.\n\n"
            + "Allow Picture-in-Picture: shrinks live playback into a small floating window "
            + "when you leave the app (e.g. press Home) instead of stopping it. On by default.\n\n"
            + "Live Preview on Press-and-Hold: hold a channel's logo in the list to play a small "
            + "muted preview before you commit to tuning in. Uses a real connection to your "
            + "provider per preview, so it's off by default.\n\n"
            + "Preferred Audio / Subtitle Language: when a stream offers multiple language "
            + "tracks, automatically selects the one matching your choice instead of whatever "
            + "the stream defaults to. Only works if the stream actually tags its tracks with "
            + "language info — depends on your provider.\n\n"
            + "Subtitle Style (size, position, bold, colors, outline): customizes how subtitles "
            + "look during playback — lives under Audio & Subtitles alongside the language "
            + "settings since both apply to subtitles.",
        // 1: Display
        "Show USA Channels Only / English Movies & Series Only: filters live channels "
            + "or VOD/series to just those tagged for that country/language by your "
            + "provider — depends entirely on your provider's own naming, so may not work "
            + "for every provider.\n\n"
            + "Show Movies/Series/Watching Tab: hides tabs you don't use to declutter the home "
            + "screen — the content itself isn't deleted, just the tab.\n\n"
            + "Auto-Clear Continue Watching: automatically removes an in-progress movie or "
            + "show from Continue Watching once it's been untouched for the chosen number of "
            + "days — it comes back automatically if you actually resume it later. Off by "
            + "default.\n\n"
            + "Catalog — Refresh: re-downloads your primary provider's live channels, movies or "
            + "series right now instead of waiting for the automatic refresh.\n\n"
            + "Hidden Channels: lists every channel you hid from its long-press menu, on any "
            + "provider — tick the ones to bring back, or Unhide all.\n\n"
            + "Clear Favorite Channels: un-favorites every channel on your primary provider. "
            + "Favorites on other providers, folders, and movie/series favorites are not "
            + "affected. Can't be undone.\n\n"
            + "Accent Color: the highlight color used for selected tabs, buttons, toggles and "
            + "progress bars — on this screen and throughout the app.\n\n"
            + "AMOLED Black: forces pure black backgrounds everywhere instead of dark gray — "
            + "saves battery on OLED screens and looks better in a dark room. Requires an "
            + "app restart to fully apply everywhere.\n\n"
            + "Force TV Mode: uses the TV interface and D-pad navigation on this device even "
            + "though it's not detected as a TV — for a car head unit/box or other non-standard "
            + "screen. Restarts the app.\n\n"
            + "Mosaic: watch up to 6 channels at once in a grid, with audio following whichever "
            + "tile you select.",
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
            + "same chosen location, without you having to remember to do it manually.\n\n"
            + "New Episode Notifications: checks your favorited shows once a day and notifies "
            + "you when one gets a new episode.",
        // 4: Sync
        "Cross-Device Sync: keeps your favorites and watch history in sync across your "
            + "devices via a private Firebase-backed store — nothing public, only devices "
            + "using the same Pairing Code see each other's data.\n\n"
            + "Push to Cloud / Pull from Cloud: manually send this device's data up, or pull "
            + "another device's data down, instead of waiting for automatic sync.\n\n"
            + "Pairing Code: the code that links devices together — enter the same code on "
            + "every device you want kept in sync.\n\n"
            + "Automatic Crash Reporting: automatically sends crash details so bugs can be found "
            + "and fixed without you needing to send a debug report. No account info is "
            + "included.\n\n"
            + "Share Anonymous Stream Health Data: off by default. When on, a hashed (never the "
            + "raw URL or your login) provider identifier plus the channel name and error type "
            + "are shared anonymously so other users can see \"N people reported issues with "
            + "this channel recently\". No account info is included.\n\n"
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
            + "comfortable with that.\n\n"
            + "Quick Actions: Sort Channels cycles the live channel order (Default, A-Z, "
            + "Popular, Recent); Multi-view opens the Mosaic; Feature Tour goes back to the "
            + "home screen and walks you through its tabs and buttons."
    )

    // ─── Search ─────────────────────────────────────────────────────────────
    // Hand-mapped rather than parsed from the layout — row labels and view ids don't follow one
    // convention that could be walked automatically.
    // Each entry: (label, panel index into panelViews/navButtonViews, disclosure row to open
    // first if the setting sits inside one (null otherwise), target view to scroll to and
    // highlight). A target that's a toggle resolves to its whole row (see jumpToSettingSearchResult).
    private data class SettingSearchEntry(val label: String, val panelIndex: Int, val headerId: Int?, val targetId: Int)

    private val settingSearchIndex: List<SettingSearchEntry> by lazy {
        listOf(
            SettingSearchEntry("EPG Refresh", 0, null, R.id.btnRefreshEpg),
            SettingSearchEntry("Refresh Only Missing Guide Data", 0, null, R.id.cbRefreshMissingOnly),
            SettingSearchEntry("Auto Refresh Schedule", 0, null, R.id.rgAutoEpgRefresh),
            SettingSearchEntry("Remind Me Lead Time", 0, null, R.id.rowReminderLeadTime),
            SettingSearchEntry("Show Alerts", 0, null, R.id.rowShowAlerts),
            SettingSearchEntry("EPG URL", 0, R.id.hdrEpgUrl, R.id.hdrEpgUrl),
            SettingSearchEntry("Default US Guide", 0, R.id.hdrEpgUrl, R.id.cbUseDefaultUsEpg),
            SettingSearchEntry("Stream Format", 0, null, R.id.rgFormat),
            SettingSearchEntry("Video Player", 0, null, R.id.rgPlayer),
            SettingSearchEntry("Autoplay Next Episode", 0, null, R.id.switchAutoplayNextEpisode),
            SettingSearchEntry("Use the Most Reliable Copy", 0, null, R.id.switchPreferReliableCopy),
            SettingSearchEntry("Tunneled Playback", 0, null, R.id.switchTunneledPlayback),
            SettingSearchEntry("DV7 HEVC Fallback", 0, null, R.id.switchDv7Fallback),
            SettingSearchEntry("Audio Passthrough Fallback", 0, null, R.id.switchAudioPassthroughFallback),
            SettingSearchEntry("Preferred Audio Language", 0, R.id.hdrLanguage, R.id.spinnerAudioLanguage),
            SettingSearchEntry("Preferred Subtitle Language", 0, R.id.hdrLanguage, R.id.spinnerSubtitleLanguage),
            SettingSearchEntry("Subtitle Size", 0, R.id.hdrLanguage, R.id.rowSubSize),
            SettingSearchEntry("Subtitle Position", 0, R.id.hdrLanguage, R.id.rowSubOffset),
            SettingSearchEntry("Subtitle Text Color", 0, R.id.hdrLanguage, R.id.rowSubTextColor),
            SettingSearchEntry("Subtitle Background Color", 0, R.id.hdrLanguage, R.id.rowSubBgColor),
            SettingSearchEntry("Subtitle Outline", 0, R.id.hdrLanguage, R.id.cbSubOutline),
            SettingSearchEntry("Extra Buffering", 0, null, R.id.switchExtraBuffering),
            SettingSearchEntry("Live Reconnect Speed", 0, null, R.id.rowLiveReconnectSpeed),
            SettingSearchEntry("Channel Change Speed", 0, null, R.id.rowChannelZapSpeed),
            SettingSearchEntry("Pre-warm Streams on Focus", 0, null, R.id.switchPreWarmOnFocus),
            SettingSearchEntry("Data Saver", 0, null, R.id.switchDataSaver),
            SettingSearchEntry("DNS over HTTPS", 0, null, R.id.cbDohEnabled),
            SettingSearchEntry("Provider Speed Test", 0, null, R.id.btnSpeedTest),
            SettingSearchEntry("Picture-in-Picture", 0, null, R.id.switchPipEnabled),
            SettingSearchEntry("Live Channel Preview", 0, null, R.id.switchLiveChannelPreview),
            // These two had panel 0 here before, but they live in Display (panel 1).
            SettingSearchEntry("Show USA Channels Only", 1, null, R.id.cbUsaOnlyChannels),
            SettingSearchEntry("Show English Movies & Series Only", 1, null, R.id.cbEnglishOnlyMovies),
            SettingSearchEntry("Channels & Tabs", 1, null, R.id.cbUsaOnlyChannels),
            SettingSearchEntry("Show Movies Tab", 1, null, R.id.cbShowMovies),
            SettingSearchEntry("Show Series Tab", 1, null, R.id.cbShowSeries),
            SettingSearchEntry("Show Watching Tab", 1, null, R.id.cbShowWatching),
            SettingSearchEntry("Auto-Clear Continue Watching", 1, null, R.id.rowAutoClearContinueWatching),
            SettingSearchEntry("Refresh Channels, Movies or Series", 1, null, R.id.btnRefreshChannels),
            SettingSearchEntry("Hidden Channels", 1, null, R.id.rowHiddenChannels),
            SettingSearchEntry("Clear Favorite Channels", 1, null, R.id.btnClearFavoriteChannels),
            SettingSearchEntry("Accent Color", 1, null, R.id.accentColorRow),
            SettingSearchEntry("AMOLED Black", 1, null, R.id.cbAmoledBlack),
            SettingSearchEntry("Force TV Mode", 1, null, R.id.switchForceTvMode),
            SettingSearchEntry("Mosaic", 1, null, R.id.btnOpenMosaic),
            SettingSearchEntry("Add Provider", 2, null, R.id.btnAddServer),
            SettingSearchEntry("Backup", 3, null, R.id.btnBackupSettings),
            SettingSearchEntry("Restore", 3, null, R.id.btnRestoreSettings),
            SettingSearchEntry("Auto backup", 3, null, R.id.switchAutoBackup),
            SettingSearchEntry("Manage Backups", 3, null, R.id.btnManageBackups),
            SettingSearchEntry("Auto-preview Movies", 3, null, R.id.switchVodAutoPreview),
            SettingSearchEntry("Monthly Data Cap", 3, null, R.id.btnBandwidthBudget),
            SettingSearchEntry("New Episode Notifications", 3, null, R.id.switchNewEpisodeNotifications),
            SettingSearchEntry("Cross-Device Sync", 4, null, R.id.switchSyncEnabled),
            SettingSearchEntry("Push to Cloud", 4, null, R.id.btnSyncUp),
            SettingSearchEntry("Pull from Cloud", 4, null, R.id.btnSyncDown),
            SettingSearchEntry("Pairing Code", 4, null, R.id.etGithubToken),
            SettingSearchEntry("Join Watch Party", 4, null, R.id.btnJoinWatchParty),
            SettingSearchEntry("Diagnostics", 4, null, R.id.switchCrashReporting),
            SettingSearchEntry("Crash Reporting", 4, null, R.id.switchCrashReporting),
            SettingSearchEntry("Send Debug Report", 4, null, R.id.btnSendDebugReport),
            SettingSearchEntry("Provider Health", 4, null, R.id.btnProviderHealth),
            SettingSearchEntry("Data Usage", 4, null, R.id.btnDataUsage),
            SettingSearchEntry("LAN Export", 4, null, R.id.btnLanExport),
            SettingSearchEntry("Receive a Cast", 4, null, R.id.btnReceiveCast),
            // tvTraktStatus rather than the Trakt buttons: which of those is visible depends on
            // whether Trakt is connected, and a hidden view has no position to scroll to.
            SettingSearchEntry("Connect Trakt", 4, null, R.id.tvTraktStatus),
            SettingSearchEntry("Sync Watched History from Trakt", 4, null, R.id.tvTraktStatus),
            SettingSearchEntry("Check for Updates", 5, null, R.id.btnCheckUpdate),
            SettingSearchEntry("What's New / Changelog", 5, null, R.id.btnWhatsNew),
            SettingSearchEntry("Silent Self-Update", 5, null, R.id.switchSilentSelfUpdate),
            // These four had panel 1 here before, but they live in Updates (panel 5).
            SettingSearchEntry("Quick Actions", 5, null, R.id.btnSettingsSort),
            SettingSearchEntry("Sort Channels", 5, null, R.id.btnSettingsSort),
            SettingSearchEntry("Multi-view / Mosaic", 5, null, R.id.btnSettingsMosaic),
            SettingSearchEntry("Feature Tour", 5, null, R.id.btnFeatureTour)
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
        // Same path as tapping the tab, so the active tab's accent styling stays consistent.
        navButtonViews.getOrNull(match.panelIndex)?.performClick()
        val panel = panelViews.getOrNull(match.panelIndex) as? android.widget.ScrollView ?: return
        // The two disclosure rows are the only things left that can hide a setting.
        if (match.headerId != null) {
            val bodyId = when (match.headerId) {
                R.id.hdrEpgUrl -> R.id.bodyEpgUrl
                R.id.hdrLanguage -> R.id.bodyLanguage
                else -> null
            }
            val body = bodyId?.let { findViewById<View>(it) }
            if (body != null && body.visibility == View.GONE) findViewById<View>(match.headerId)?.performClick()
        }
        // A toggle isn't a focus / touch target of its own any more — its row is.
        val found = findViewById<View>(match.targetId) ?: return
        val target = (found.parent as? View)?.takeIf { it.tag == TAG_TOGGLE_ROW } ?: found
        // Scroll once the layout pass that the tab switch / disclosure above just queued has run,
        // by the target's position within the whole panel. (target.top alone is relative to its
        // own parent, which in these nested groups is nowhere near the panel's scroll offset —
        // the old jump scrolled to roughly the top of the panel whatever the target was.)
        panel.doOnNextLayout {
            val rect = android.graphics.Rect()
            target.getDrawingRect(rect)
            panel.offsetDescendantRectToMyCoords(target, rect)
            panel.smoothScrollTo(0, (rect.top - (16 * resources.displayMetrics.density).toInt()).coerceAtLeast(0))
            // With a remote, land focus on it too, so OK acts on the setting straight away.
            if (!panel.isInTouchMode) (if (target.isFocusable) target else firstFocusableIn(target))?.requestFocus()
            val original = target.background
            val accent = accentColorInt()
            target.setBackgroundColor(Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent)))
            target.postDelayed({ target.background = original }, 900)
        }
        panel.requestLayout()
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
    @Inject lateinit var watchPartyManager: com.iptvapp.sync.WatchPartyManager
    @Inject lateinit var castRelay: com.iptvapp.sync.CastRelayManager
    private var castListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
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
    // "Add Source > M3U Playlist > Choose file" — same OpenDocument/read-as-text pattern
    // LoginActivity's m3uFileLauncher uses for the separate replace-primary M3U import.
    // pendingM3uSourceNickname carries the nickname collected before the picker launched, since
    // the launcher callback has no way to receive extra arguments.
    private var pendingM3uSourceNickname: String = ""
    private val m3uSourceFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val text = try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) { null }
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@SettingsActivity, "Couldn't read that file", Toast.LENGTH_SHORT).show()
                } else {
                    importM3uSource(pendingM3uSourceNickname, sourceUrl = null, content = text)
                }
            }
        }
    }
    // Requested when the New Episode Notifications switch is turned on (Android 13+ requires
    // this at request time, not just declared in the manifest) — same
    // ActivityResultContracts.RequestPermission() shape HomeActivity's notifPermLauncher already
    // uses for EPG reminders, just scoped to this one toggle instead of fired unconditionally on
    // every launch.
    private val newEpisodeNotifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val sortLabels = listOf("Default", "A-Z", "Popular", "Recent")
    private var currentSortIndex = 0

    // #06B6D4 (cyan) leads the list because it's the default accent since v6.77 — before this it
    // wasn't a swatch at all, so anyone who tried another color could only get back to the
    // default through the custom hue picker. #F5A524 (amber) is the Studio Rack Settings
    // design's own highlight (v6.80), one tap away for anyone who wants that exact look.
    private val accentPalette = listOf(
        "#06B6D4", "#F5A524", "#008CFF", "#FF3B30", "#34C759", "#AF52DE", "#FF9500", "#FF2D55", "#5AC8FA"
    )
    // Spoken names for the swatches (TalkBack), instead of reading out hex codes.
    private val accentSwatchNames = mapOf(
        "#06B6D4" to "cyan", "#F5A524" to "amber", "#008CFF" to "blue", "#FF3B30" to "red",
        "#34C759" to "green", "#AF52DE" to "purple", "#FF9500" to "orange", "#FF2D55" to "pink",
        "#5AC8FA" to "light blue"
    )
    // Named two-stop gradients — selectable alongside the flat swatches above. Drawn as a real
    // gradient wherever a surface can hold one (see applyRackAccent here and
    // HomeActivity.applyAccent); single-tint things like progress spinners use the start color.
    // Neon (cyan → purple) was asked for by name in v6.82 and leads the list.
    private val accentGradients = listOf(
        Triple("Neon", "#06B6D4", "#AF52DE"),
        Triple("Sunset", "#FF9500", "#FF2D55"),
        Triple("Ocean", "#008CFF", "#34C759"),
        Triple("Berry", "#AF52DE", "#FF3B30"),
        Triple("Aurora", "#34C759", "#5AC8FA")
    )
    // Default only — the swatches/gradients above and prefs.accentColor's own DataStore
    // fallback (PreferencesManager.kt) are the real source of truth for anyone who's actually
    // picked something; this is just what a fresh install / never-customized account starts on,
    // now matching the rest of the OLED/cyan reskin instead of the old blue.
    private var currentAccentColor = "#06B6D4"
    private var currentAccentColorEnd = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Default accent until loadSettings reads the real one — the Rack drawables are drawn
        // white where the accent goes, so they need a tint before the first frame.
        applyRackAccent()
        wireToggleRows(binding.root)
        hideWhileEmpty(
            binding.tvEpgRefreshStatus, binding.tvSpeedTestResult, binding.tvUpdateStatus,
            binding.tvBackupStatus, binding.tvAutoBackupPath, binding.tvReportStatus,
            binding.tvSyncStatus, binding.tvTraktStatus, binding.tvTraktSyncStatus
        )
        lifecycleScope.launch { com.iptvapp.util.ThemeUtils.applyAmoledIfEnabled(binding.root, prefs) }
        workManager = WorkManager.getInstance(this)

        // Re-establish in case it was cleared by an app update (KEEP = don't reset the timer) —
        // mirrors RecordingSchedulerActivity's identical re-arm for RecordingCleanupWorker.
        lifecycleScope.launch {
            if (prefs.autoClearContinueWatchingDays.first() > 0) {
                val request = androidx.work.PeriodicWorkRequestBuilder<com.iptvapp.worker.ContinueWatchingCleanupWorker>(1, java.util.concurrent.TimeUnit.DAYS).build()
                workManager.enqueueUniquePeriodicWork(
                    com.iptvapp.worker.ContinueWatchingCleanupWorker.WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }

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
            binding.btnSettingsSort.text = sortButtonLabel()
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
                binding.cbUseDefaultUsEpg.isChecked = (com.iptvapp.AppConstants.currentEpgUrl(url) == com.iptvapp.AppConstants.DEFAULT_US_EPG_URL)
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
            // USA Only now decides what's downloaded, not just what's shown, so the channel lists
            // are reloaded under the new setting — in the background, finishing even if you leave.
            lifecycleScope.launch {
                prefs.setUsaOnlyChannels(isChecked)
                Toast.makeText(this@SettingsActivity, "Reloading channel lists…", Toast.LENGTH_SHORT).show()
                val app = applicationContext
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                kotlinx.coroutines.GlobalScope.launch {
                    repository.fetchLiveStreams()
                    repository.refreshMergedChannels()
                    prefs.setLastMergedChannelsRefresh(System.currentTimeMillis())
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(app, "Channel lists updated", Toast.LENGTH_SHORT).show()
                    }
                }
            }
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

        binding.rowAutoClearContinueWatching.setOnClickListener { showAutoClearContinueWatchingDialog() }

        binding.btnRefreshMovies.setOnClickListener {
            binding.btnRefreshMovies.isEnabled = false
            binding.btnRefreshMovies.text = "Loading…"
            lifecycleScope.launch {
                repository.fetchVodCategories()
                val result = repository.fetchVodStreams()
                binding.btnRefreshMovies.isEnabled = true
                binding.btnRefreshMovies.text = "Refresh"
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
                binding.btnRefreshSeries.text = "Refresh"
                val msg = if (result is com.iptvapp.util.Resource.Success)
                    "Series refreshed (${result.data?.size ?: 0} titles)"
                else
                    "Failed — server timeout or no content"
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        // TV Settings got this in v6.32, but phone had no way at all to force a primary-provider
        // live-channel resync — it only ever ran automatically, gated behind a 4-hour
        // cache-staleness check (HomeViewModel.init). That meant a channel added/removed on the
        // provider's end (or a stale row left behind by a primary-provider swap, see
        // XtreamRepository.fetchLiveStreams's stale-channel sweep) could sit wrong for hours with
        // no way to force it sooner.
        binding.btnRefreshChannels.setOnClickListener {
            binding.btnRefreshChannels.isEnabled = false
            binding.btnRefreshChannels.text = "Loading…"
            lifecycleScope.launch {
                // A brief WiFi/router DNS blip at the exact moment of a manual refresh — real,
                // observed case — showed as an outright "Failed" even though the provider itself
                // was fine seconds later. One quiet retry (no extra toast/UI state) absorbs a
                // transient blip without asking the user to notice the failure and tap Refresh
                // again themselves.
                var result = repository.fetchLiveStreams()
                if (result !is com.iptvapp.util.Resource.Success) result = repository.fetchLiveStreams()
                repository.fetchLiveCategories()
                binding.btnRefreshChannels.isEnabled = true
                binding.btnRefreshChannels.text = "Refresh"
                val msg = if (result is com.iptvapp.util.Resource.Success)
                    "Channels refreshed (${result.data?.size ?: 0} channels)"
                else
                    "Failed — server timeout or no content"
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        // Explicit escape hatch requested after the automatic stale-channel sweep (v6.31) and
        // the manual refresh button above (v6.33) still weren't enough to clear out a user's
        // leftover favorites from an earlier provider switch — rather than debug further, just
        // let them wipe every primary-provider favorite and re-pick manually. Scoped to
        // channelDao.clearAllFavorites() only — never touches merged/secondary-provider
        // favorites, folders, or VOD/series favorites, since those aren't part of this bug.
        binding.btnClearFavoriteChannels.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear all favorited channels?")
                .setMessage("This un-favorites every channel on your primary provider. Favorites on other providers, folders, and movie/series favorites are not affected. This can't be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch {
                        db.channelDao().clearAllFavorites()
                        Toast.makeText(this@SettingsActivity, "Favorites cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
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
                binding.tvStatusFormat.text = format.uppercase(Locale.US)
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
            navButtonViews.forEachIndexed { i, btn -> styleNavTab(btn, active = i == index) }
        }
        navButtonViews.forEachIndexed { i, btn -> btn.setOnClickListener { selectPanel(i) } }
        binding.headerRecordings.setOnClickListener {
            startActivity(Intent(this, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java))
        }
        selectPanel(0)
        setupCollapsibleCards()
    }

    // Accent picker, in the Studio Rack look: SOLID square-cornered swatches, then the named
    // GRADIENTS as labeled chips, then the custom-hue picker as its own chip. Both rows wrap
    // (FlowLayout). What counts as "selected" and what each tap saves are unchanged. Every tile
    // and chip is D-pad focusable with the same light focus outline as the rest of the screen.
    private fun setupAccentPicker() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val container = binding.accentColorRow
        container.removeAllViews()

        fun sectionLabel(text: String, topMarginDp: Int) = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.rack_text_muted))
            textSize = 12f
            typeface = rackFont(R.font.barlow_condensed_semibold)
            letterSpacing = 0.16f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(topMarginDp); bottomMargin = dp(6) }
        }

        // Foreground outline that only shows under D-pad focus.
        fun focusRing(cornerDp: Int) = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(cornerDp).toFloat()
                setStroke(dp(2), getColor(R.color.rack_focus_ring))
                setColor(Color.TRANSPARENT)
            })
        }

        fun pickSolid(hex: String) {
            currentAccentColor = hex
            currentAccentColorEnd = ""
            lifecycleScope.launch { prefs.setAccentColor(hex) }
            applyAccentToSettings()
            setupAccentPicker()
        }

        fun pickGradient(gradient: Triple<String, String, String>) {
            currentAccentColor = gradient.second
            currentAccentColorEnd = gradient.third
            lifecycleScope.launch { prefs.setAccentGradient(gradient.second, gradient.third) }
            applyAccentToSettings()
            setupAccentPicker()
        }

        // SOLID — one swatch per color; the selected one gets a frame in its own color.
        container.addView(sectionLabel("SOLID", 0))
        val solids = FlowLayout(this).apply {
            spacingPx = dp(4)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        accentPalette.forEach { hex ->
            val color = Color.parseColor(hex)
            val isSelected = hex == currentAccentColor && currentAccentColorEnd.isEmpty()
            val tile = android.widget.FrameLayout(this).apply {
                layoutParams = ViewGroup.MarginLayoutParams(dp(44), dp(44))
                isFocusable = true
                foreground = focusRing(cornerDp = 6)
                contentDescription = "Accent color " + (accentSwatchNames[hex] ?: hex) + if (hex == "#06B6D4") " (default)" else ""
                setOnClickListener { pickSolid(hex) }
            }
            tile.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(color)
                }
                tag = hex
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(28), dp(28), android.view.Gravity.CENTER)
            })
            if (isSelected) tile.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(2), color)
                    setColor(Color.TRANSPARENT)
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(38), dp(38), android.view.Gravity.CENTER)
            })
            solids.addView(tile)
        }
        container.addView(solids)

        // GRADIENTS + Custom — chips with a color swatch and the preset's name. The selected chip
        // gets an outline in its own colors (a gradient ring for a gradient); Custom is dashed
        // until it's the active choice.
        container.addView(sectionLabel("GRADIENTS", 12))
        val chips = FlowLayout(this).apply {
            spacingPx = dp(8)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fun chip(label: String, dot: android.graphics.drawable.Drawable, selected: IntArray?, dashed: Boolean, onClick: () -> Unit) =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
                setPadding(dp(10), 0, dp(14), 0)
                background = if (selected != null) {
                    // A stroke can only be one color, so the ring is a gradient-filled shape with
                    // the group's own surface color laid 2dp inside it.
                    android.graphics.drawable.LayerDrawable(arrayOf(
                        android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, selected
                        ).apply { cornerRadius = dp(5).toFloat() },
                        android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(3).toFloat()
                            setColor(getColor(R.color.rack_surface))
                        }
                    )).apply { setLayerInset(1, dp(2), dp(2), dp(2), dp(2)) }
                } else android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(5).toFloat()
                    setColor(Color.TRANSPARENT)
                    if (dashed) setStroke(dp(1), getColor(R.color.rack_control_edge), dp(4).toFloat(), dp(3).toFloat())
                    else setStroke(dp(1), getColor(R.color.rack_control_edge))
                }
                isFocusable = true
                foreground = focusRing(cornerDp = 5)
                setOnClickListener { onClick() }
                addView(View(this@SettingsActivity).apply {
                    background = dot
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20))
                })
                addView(android.widget.TextView(this@SettingsActivity).apply {
                    text = label
                    textSize = 15f
                    typeface = rackFont(R.font.barlow_medium)
                    setTextColor(getColor(if (dashed) R.color.rack_text_secondary else R.color.rack_text))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8) }
                })
            }
        accentGradients.forEach { gradient ->
            val isSelected = currentAccentColorEnd.equals(gradient.third, ignoreCase = true) && currentAccentColor.equals(gradient.second, ignoreCase = true)
            val dot = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor(gradient.second), Color.parseColor(gradient.third))
            ).apply { cornerRadius = dp(3).toFloat() }
            chips.addView(chip(
                gradient.first, dot,
                if (isSelected) intArrayOf(Color.parseColor(gradient.second), Color.parseColor(gradient.third)) else null,
                dashed = false
            ) { pickGradient(gradient) })
        }
        val customSelected = accentPalette.none { it == currentAccentColor } && accentGradients.none { it.second == currentAccentColor && it.third == currentAccentColorEnd } && currentAccentColorEnd.isEmpty()
        val customDot = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(3).toFloat()
            if (customSelected) {
                setColor(Color.parseColor(currentAccentColor))
            } else {
                // Conic hint that this chip opens a picker, not one fixed color — a plain solid
                // dot here would look like just another preset.
                colors = intArrayOf(Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED)
                gradientType = android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT
            }
        }
        chips.addView(chip(
            "Custom", customDot,
            if (customSelected) Color.parseColor(currentAccentColor).let { intArrayOf(it, it) } else null,
            dashed = !customSelected
        ) { showCustomColorPickerDialog() })
        container.addView(chips)
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
                applyAccentToSettings()
                setupAccentPicker()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Picking a new accent changes what "the active color" is without switching tabs, so
    // everything accent-colored — the active tab included — gets repainted here, or it would stay
    // on the previous accent until something else happened to refresh it. Reads the new pick from
    // currentAccentColor / currentAccentColorEnd, which every caller sets first.
    private fun applyAccentToSettings() {
        applyRackAccent()
    }

    // One tab of the tab row. Idle tabs keep their XML look (Rack.Tab: grey label; a lifted fill
    // and light outline under D-pad focus). The active tab's label and a 2dp underline are the
    // user's accent — a left-to-right sweep when it's a gradient. The underline is the button's
    // foreground, so it never fights the focus fill and outline, which are its background —
    // focus stays visible on the active tab too. The font stays the same either way, so switching
    // tabs never shifts their widths.
    private fun styleNavTab(btn: android.widget.Button, active: Boolean) {
        val tab = btn as? com.google.android.material.button.MaterialButton ?: return
        if (active) {
            val start = accentColorInt()
            val end = accentEndColorInt()
            com.iptvapp.util.AccentText.apply(tab, start, end)
            tab.foreground = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end ?: start)
                )
            )).apply {
                setLayerGravity(0, android.view.Gravity.BOTTOM or android.view.Gravity.FILL_HORIZONTAL)
                setLayerHeight(0, px(2))
            }
        } else {
            com.iptvapp.util.AccentText.plain(tab, getColorStateList(R.color.rack_tab_text))
            tab.foreground = null
        }
    }

    // currentAccentColor as a color int, falling back to the default cyan if it's ever unparseable.
    private fun accentColorInt(): Int =
        try { Color.parseColor(currentAccentColor) } catch (_: Exception) { getColor(R.color.oled_cyan_primary) }

    // The gradient's second color when the accent is a gradient preset, null for a solid accent.
    private fun accentEndColorInt(): Int? =
        currentAccentColorEnd.takeIf { it.isNotEmpty() }?.let { try { Color.parseColor(it) } catch (_: Exception) { null } }

    // ─── Studio Rack styling (v6.80) ───────────────────────────────────────────

    private fun rackFont(res: Int): android.graphics.Typeface? =
        try { androidx.core.content.res.ResourcesCompat.getFont(this, res) } catch (_: Exception) { null }

    private fun px(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    // Black or white, whichever reads better on the accent — over both ends of a gradient, so it
    // holds up across the whole sweep. Black for every swatch and preset; white only for a dark
    // custom hue (a pure blue, say), where black would fall under ~4.5:1.
    private fun onAccentColor(start: Int, end: Int?): Int {
        val stops = listOfNotNull(start, end)
        val black = stops.minOf { androidx.core.graphics.ColorUtils.calculateContrast(Color.BLACK, it) }
        val white = stops.minOf { androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE, it) }
        return if (black >= white) Color.BLACK else Color.WHITE
    }

    // Paints the user's accent onto everything accent-colored under [root]. A gradient accent is
    // drawn as a real left-to-right gradient on every surface that can hold one — main buttons,
    // "on" toggles, chosen segments, the active tab, values, dots, the progress bar — instead of
    // falling back to its first color (which made Sunset look exactly like the orange swatch,
    // Ocean like the blue one, and so on). Outlined buttons and icons stay on the first color.
    // Views are found by the android:tag their Rack style gives them (styles_settings.xml lists
    // the tags), Switches and ProgressBars by type. Runs on open (with the default, then the
    // saved accent), on every accent pick, and on each batch of rebuilt provider cards;
    // repainting a view is harmless.
    private fun applyRackAccent(root: View = binding.root) {
        val start = accentColorInt()
        val end = accentEndColorInt()
        val fill = intArrayOf(start, end ?: start)
        val onAccent = onAccentColor(start, end)
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val focused = intArrayOf(android.R.attr.state_focused)
        val checked = intArrayOf(android.R.attr.state_checked)
        val otherwise = intArrayOf()
        fun csl(vararg entries: Pair<IntArray, Int>) = android.content.res.ColorStateList(
            entries.map { it.first }.toTypedArray(), entries.map { it.second }.toIntArray()
        )
        val fillText = csl(disabled to getColor(R.color.rack_disabled_text), otherwise to onAccent)
        val outlineStroke = csl(
            disabled to getColor(R.color.rack_control_edge),
            focused to getColor(R.color.rack_focus_ring),
            otherwise to start
        )
        val outlineText = csl(disabled to getColor(R.color.rack_disabled_text), otherwise to start)
        val segmentText = csl(
            checked to onAccent,
            disabled to getColor(R.color.rack_disabled_text),
            focused to getColor(R.color.rack_text),
            otherwise to getColor(R.color.rack_text_muted)
        )

        fun paint(v: View) {
            when (v.tag) {
                TAG_ACCENT_FILL -> (v as? com.google.android.material.button.MaterialButton)?.apply {
                    // A MaterialButton's own background can only take one tint color, so it
                    // gets a drawn one (fill, focus outline, disabled grey, ripple) instead.
                    background = rackFillBackground(fill, px(4).toFloat())
                    backgroundTintList = null
                    setTextColor(fillText); iconTint = fillText
                }
                TAG_ACCENT_OUTLINE -> (v as? com.google.android.material.button.MaterialButton)?.apply {
                    strokeColor = outlineStroke; setTextColor(outlineText); iconTint = outlineText
                }
                TAG_ACCENT_ICON -> (v as? com.google.android.material.button.MaterialButton)?.iconTint = outlineText
                TAG_ACCENT_TEXT -> (v as? android.widget.TextView)?.let { com.iptvapp.util.AccentText.apply(it, start, end) }
                TAG_ACCENT_DOT -> v.background = accentDot(start, end)
                TAG_TOGGLE -> (v as? android.widget.CompoundButton)?.let { toggle ->
                    androidx.core.widget.CompoundButtonCompat.setButtonTintList(toggle, null)
                    toggle.buttonDrawable = rackToggleDrawable(fill, onAccent)
                }
                TAG_SEGMENT -> (v as? android.widget.RadioButton)?.let { segment ->
                    androidx.core.view.ViewCompat.setBackgroundTintList(segment, null)
                    segment.background = rackSegmentBackground(fill)
                    segment.setTextColor(segmentText)
                }
            }
            when (v) {
                is androidx.appcompat.widget.SwitchCompat -> {
                    v.trackDrawable = rackTrackDrawable(fill); v.trackTintList = null
                    v.thumbDrawable = rackThumbDrawable(onAccent); v.thumbTintList = null
                }
                is android.widget.Switch -> {
                    v.trackDrawable = rackTrackDrawable(fill); v.trackTintList = null
                    v.thumbDrawable = rackThumbDrawable(onAccent); v.thumbTintList = null
                }
                is android.widget.ProgressBar -> if (!v.isIndeterminate) {
                    v.progressDrawable = rackProgressDrawable(fill)
                    v.progressTintList = null
                    v.progressBackgroundTintList = null
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) paint(v.getChildAt(i))
        }
        paint(root)
        if (root === binding.root) {
            navButtonViews.forEachIndexed { i, btn -> styleNavTab(btn, active = i == currentPanelIndex) }
            binding.btnSettingsSort.text = sortButtonLabel(start)
        }
    }

    // The drawn background of a solid accent button: the accent (or gradient) fill, a light
    // outline under D-pad focus, grey while disabled (e.g. Refresh mid-refresh), and a ripple.
    private fun rackFillBackground(fill: IntArray, radius: Float): android.graphics.drawable.Drawable {
        fun shape(colors: IntArray?, solid: Int, stroke: Int?) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius
            if (colors != null) {
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                this.colors = colors
            } else {
                setColor(solid)
            }
            if (stroke != null) setStroke(px(2), stroke)
        }
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(null, getColor(R.color.rack_control_off), null))
            addState(intArrayOf(android.R.attr.state_focused), shape(fill, 0, getColor(R.color.rack_focus_ring)))
            addState(intArrayOf(), shape(fill, 0, null))
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33000000), states, shape(null, Color.WHITE, null)
        )
    }

    // A Switch's track: the accent (or gradient) when on, the grey square well when off. Same
    // shape and size as rack_switch_track.xml, which is only the first frame.
    private fun rackTrackDrawable(fill: IntArray) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, fill
        ).apply { cornerRadius = px(5).toFloat(); setSize(px(56), px(30)) })
        addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(5).toFloat()
            setColor(getColor(R.color.rack_control_off))
            setStroke(px(1), getColor(R.color.rack_control_edge))
            setSize(px(56), px(30))
        })
    }

    // A Switch's knob: black or white on the accent when on, grey when off. 22dp with 3dp / 4dp of
    // transparent inset, so the Switch's own width math (2 x thumb width) lands on the 56dp track —
    // same as rack_switch_thumb.xml.
    private fun rackThumbDrawable(onAccent: Int): android.graphics.drawable.Drawable {
        fun knob(color: Int) = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(3).toFloat()
                setColor(color)
                setSize(px(22), px(22))
            }
        )).apply { setLayerInset(0, px(3), px(4), px(3), px(4)) }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), knob(onAccent))
            addState(intArrayOf(), knob(getColor(R.color.rack_knob_off)))
        }
    }

    // The same toggle for CheckBox-backed settings, as one drawable (track and knob together).
    private fun rackToggleDrawable(fill: IntArray, onAccent: Int): android.graphics.drawable.Drawable {
        fun toggle(track: IntArray, edge: Int?, knob: Int, knobAtEnd: Boolean) = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, track
            ).apply {
                cornerRadius = px(5).toFloat()
                if (edge != null) setStroke(px(1), edge)
                setSize(px(56), px(30))
            },
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(3).toFloat()
                setColor(knob)
            }
        )).apply {
            setLayerGravity(1, (if (knobAtEnd) android.view.Gravity.END else android.view.Gravity.START) or android.view.Gravity.CENTER_VERTICAL)
            setLayerInset(1, px(4), 0, px(4), 0)
            setLayerSize(1, px(22), px(22))
        }
        val off = getColor(R.color.rack_control_off)
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), toggle(fill, null, onAccent, knobAtEnd = true))
            addState(intArrayOf(), toggle(
                intArrayOf(off, off), getColor(R.color.rack_control_edge),
                getColor(R.color.rack_knob_off), knobAtEnd = false
            ))
        }
    }

    // One segment's background: the accent (or gradient) when chosen, a lifted fill under D-pad
    // focus or a press, nothing otherwise. The focus outline is the segment's foreground
    // (rack_segment_focus.xml), so swapping this never touches it.
    private fun rackSegmentBackground(fill: IntArray) = android.graphics.drawable.StateListDrawable().apply {
        val radius = px(4).toFloat()
        addState(intArrayOf(android.R.attr.state_checked), android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, fill
        ).apply { cornerRadius = radius })
        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius; setColor(getColor(R.color.rack_focus_fill))
        })
        addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius; setColor(getColor(R.color.rack_pressed))
        })
    }

    // The EPG refresh bar: a grey track with the accent (or gradient) revealed as it fills.
    private fun rackProgressDrawable(fill: IntArray): android.graphics.drawable.Drawable {
        val radius = px(2).toFloat()
        return android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = radius; setColor(getColor(R.color.rack_control_off))
            },
            android.graphics.drawable.ClipDrawable(
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, fill
                ).apply { cornerRadius = radius },
                android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL
            )
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    // The status strip's (and a provider card's) ACTIVE dot: the accent — or the gradient, on a
    // diagonal — with a soft halo standing in for a glow.
    private fun accentDot(start: Int, end: Int?): android.graphics.drawable.Drawable {
        val inset = px(2)
        val stops = intArrayOf(start, end ?: start)
        return android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                stops.map { Color.argb(70, Color.red(it), Color.green(it), Color.blue(it)) }.toIntArray()
            ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL },
            android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, stops
            ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
        )).apply { setLayerInset(1, inset, inset, inset, inset) }
    }

    // "Sort channels: A-Z" with the current order in the accent, like a value row's value.
    private fun sortButtonLabel(accent: Int = accentColorInt()): CharSequence {
        val value = sortLabels[currentSortIndex]
        return android.text.SpannableString("Sort channels: $value").apply {
            setSpan(
                android.text.style.ForegroundColorSpan(accent),
                length - value.length, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    // Toggle rows (tag toggleRow) are one D-pad / touch target: a click anywhere on the row flips
    // the Switch or toggle CheckBox inside it, which fires the same OnCheckedChangeListener that
    // tapping the toggle itself always did. The row also tells TalkBack it's a switch and whether
    // it's on, since the toggle inside is no longer focusable on its own.
    private fun wireToggleRows(root: View) {
        if (root.tag == TAG_TOGGLE_ROW && root is ViewGroup) {
            val toggle = findCompoundButton(root) ?: return
            root.setOnClickListener { if (toggle.isEnabled) toggle.toggle() }
            androidx.core.view.ViewCompat.setAccessibilityDelegate(root, object : androidx.core.view.AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true
                    info.isChecked = toggle.isChecked
                    info.className = android.widget.Switch::class.java.name
                }
            })
            return
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) wireToggleRows(root.getChildAt(i))
    }

    private fun findCompoundButton(group: ViewGroup): android.widget.CompoundButton? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is android.widget.CompoundButton) return child
            if (child is ViewGroup) findCompoundButton(child)?.let { return it }
        }
        return null
    }

    // Status lines that are empty most of the time (refresh status, speed-test result …) take no
    // room until they have something to say, instead of leaving a blank line in their row.
    private fun hideWhileEmpty(vararg views: android.widget.TextView) {
        views.forEach { tv ->
            tv.visibility = if (tv.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            tv.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    tv.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
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

    // A disclosure row: clicking it shows / hides the rows under it, and its chevron turns to
    // point up while they're showing.
    private fun wireCollapsible(headerId: Int, bodyId: Int, chevronId: Int) {
        val header  = findViewById<View>(headerId)   ?: return
        val body    = findViewById<View>(bodyId)     ?: return
        val chevron = findViewById<View>(chevronId)  ?: return
        chevron.rotation = if (body.visibility == View.VISIBLE) 180f else 0f
        header.setOnClickListener {
            val expanding = body.visibility == View.GONE
            body.visibility = if (expanding) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (expanding) 180f else 0f).setDuration(150).start()
        }
    }

    // Only two things still open and close in the Rack layout; everything else is always shown.
    private fun setupCollapsibleCards() {
        wireCollapsible(R.id.hdrEpgUrl,   R.id.bodyEpgUrl,   R.id.chevEpgUrl)
        wireCollapsible(R.id.hdrLanguage, R.id.bodyLanguage, R.id.chevLanguage)
    }

    // D-pad with the tabs across the top: Down from a tab goes to the first setting of its panel
    // (not whatever happens to sit geometrically below the tab), and Back from inside a panel
    // returns to its tab. Everything else — Left / Right between the two columns on a wide
    // screen, Up from the top of a panel back to the tabs — is plain focus search.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val inContent = panelViews[currentPanelIndex].hasFocus()
            val inNav = binding.settingsNavRail.hasFocus()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (inNav) {
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

    // ─── Swipe between tabs ─────────────────────────────────────────────────
    // A sideways swipe anywhere over the panels moves to the next / previous tab (Recordings is
    // skipped — it opens its own screen, it isn't a panel). Watched at the Activity level so it
    // works over any row; once a drag is clearly sideways the rows under the finger get a CANCEL,
    // so a swipe never also taps a toggle or button. Vertical drags are left to the ScrollView,
    // and the tab row and Quick bar (which scroll sideways themselves) aren't swipe areas.
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeTracking = false
    private var swiping = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX
                swipeDownY = ev.rawY
                swiping = false
                val panels = binding.sectionStream.parent as View
                val loc = IntArray(2).also { panels.getLocationOnScreen(it) }
                swipeTracking = ev.rawX >= loc[0] && ev.rawX < loc[0] + panels.width &&
                    ev.rawY >= loc[1] && ev.rawY < loc[1] + panels.height
            }
            android.view.MotionEvent.ACTION_MOVE -> if (swipeTracking && !swiping) {
                val dx = Math.abs(ev.rawX - swipeDownX)
                val dy = Math.abs(ev.rawY - swipeDownY)
                if (dx > slop * 2 && dx > dy * 1.5f) {
                    swiping = true
                    val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                } else if (dy > slop) {
                    swipeTracking = false
                }
            }
            android.view.MotionEvent.ACTION_UP -> if (swiping) {
                swiping = false
                swipeTracking = false
                val dx = ev.rawX - swipeDownX
                if (Math.abs(dx) > px(64)) swipeToPanel(if (dx < 0) currentPanelIndex + 1 else currentPanelIndex - 1, fromRight = dx < 0)
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> { swiping = false; swipeTracking = false }
        }
        if (swiping) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun swipeToPanel(index: Int, fromRight: Boolean) {
        if (index !in panelViews.indices) return
        navButtonViews[index].performClick()
        val tab = navButtonViews[index]
        binding.settingsNavRail.smoothScrollTo((tab.left - px(48)).coerceAtLeast(0), 0)
        // The new panel slides in a little from the side it came from.
        val panel = panelViews[index]
        panel.translationX = (if (fromRight) 1 else -1) * px(48).toFloat()
        panel.alpha = 0f
        panel.animate().translationX(0f).alpha(1f).setDuration(180).start()
    }

    private fun focusFirstInCurrentPanel() {
        val panel = panelViews[currentPanelIndex]
        val first = firstFocusableIn(panel)
        if (first != null) first.requestFocus() else panel.requestFocus()
    }

    // First focusable view under [view] in layout order, skipping anything hidden (a view inside a
    // GONE parent reports itself VISIBLE, so a closed disclosure row's contents have to be skipped
    // at the parent). Rows count too — a toggle row is itself the focus target.
    private fun firstFocusableIn(view: View): View? {
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (child.isFocusable && child.isEnabled) return child
            firstFocusableIn(child)?.let { return it }
        }
        return null
    }

    private fun setupBackupRestore() {
        binding.btnBackupSettings.setOnClickListener { backupSettings() }
        binding.btnRestoreSettings.setOnClickListener { showRestoreDialog() }
        binding.btnSendDebugReport.setOnClickListener { sendDebugReport() }
        binding.btnProviderHealth.setOnClickListener { showProviderHealthDialog() }
        binding.btnDataUsage.setOnClickListener { showDataUsageDialog() }
        binding.btnBandwidthBudget.setOnClickListener { showBandwidthBudgetDialog() }
        binding.btnProviderWeather.setOnClickListener { showProviderWeatherDialog() }
        binding.btnLanExport.setOnClickListener { showLanExportDialog() }
        binding.btnReceiveCast.setOnClickListener { showReceiveCastDialog() }
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
            binding.switchCommunityHealthSharing.isChecked = prefs.communityHealthSharingEnabled.first()
        }
        binding.switchCommunityHealthSharing.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setCommunityHealthSharingEnabled(isChecked) }
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

        lifecycleScope.launch {
            binding.switchVodAutoPreview.isChecked = prefs.vodAutoPreviewEnabled.first()
        }
        binding.switchVodAutoPreview.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setVodAutoPreviewEnabled(isChecked) }
        }

        lifecycleScope.launch {
            binding.switchLiveChannelPreview.isChecked = prefs.liveChannelPreviewEnabled.first()
        }
        binding.switchLiveChannelPreview.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setLiveChannelPreviewEnabled(isChecked) }
        }
        lifecycleScope.launch {
            binding.switchDataSaver.isChecked = prefs.dataSaverEnabled.first()
        }
        binding.switchDataSaver.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setDataSaverEnabled(isChecked) }
        }
        lifecycleScope.launch {
            binding.switchPreWarmOnFocus.isChecked = prefs.preWarmOnFocus.first()
        }
        binding.switchPreWarmOnFocus.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setPreWarmOnFocus(isChecked) }
        }

        lifecycleScope.launch {
            binding.switchNewEpisodeNotifications.isChecked = prefs.newEpisodeNotificationsEnabled.first()
        }
        binding.switchNewEpisodeNotifications.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                prefs.setNewEpisodeNotificationsEnabled(isChecked)
                if (isChecked) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        androidx.core.content.ContextCompat.checkSelfPermission(this@SettingsActivity, android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        newEpisodeNotifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    scheduleNewEpisodeCheck()
                } else {
                    cancelNewEpisodeCheck()
                }
            }
        }
    }

    private fun scheduleNewEpisodeCheck() {
        val request = PeriodicWorkRequestBuilder<NewEpisodeCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            NewEpisodeCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Toast.makeText(this, "New episode notifications scheduled daily", Toast.LENGTH_SHORT).show()
    }

    private fun cancelNewEpisodeCheck() {
        WorkManager.getInstance(this).cancelUniqueWork(NewEpisodeCheckWorker.WORK_NAME)
        Toast.makeText(this, "New episode notifications disabled", Toast.LENGTH_SHORT).show()
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
    //
    // Filename prefix is deliberately "MKTV_manual_", NOT "MKTV_backup_" (what AutoBackupWorker
    // uses) — they used to share the same prefix, which meant AutoBackupWorker's own 5-newest
    // retention prune (keeps only the 5 most recent MKTV_backup_*.json files, deleting the rest)
    // couldn't tell a manual snapshot apart from its own weekly ones. Any time the account had
    // built up more than 5 total backups across both, the next scheduled auto-backup run would
    // silently delete a manual backup the user had deliberately kept, with no warning. Separate
    // prefixes give each its own independent retention scope.
    private suspend fun quickBackupNow() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(privateBackupsDir(), "MKTV_manual_$timestamp.json")
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
        val totalSizeLabel = formatBackupBytes(files.sumOf { it.length() })
        val labels = arrayOf("+ Quick Backup Now") +
            files.map { f ->
                val kind = if (f.name.startsWith("MKTV_manual_")) "Manual" else "Auto"
                "${dateFmt.format(Date(f.lastModified()))}  •  $kind  •  ${formatBackupBytes(f.length())}"
            }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Backups on This Device (${files.size}, $totalSizeLabel)")
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

    private fun formatBackupBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.1f MB".format(kb / 1024.0)
    }

    // Parses just enough of the file to answer "what's actually in this?" without going
    // through the full applyBackupJson restore path — restoring previously required blindly
    // trusting a generic "this will overwrite your login, favorites, and settings" message with
    // no indication of what the file actually contained (how many favorites, whether extra
    // providers were included, etc.) until after committing to the restore.
    private fun backupContentSummary(file: File): String = try {
        val json = org.json.JSONObject(file.readText())
        val parts = mutableListOf<String>()
        json.optString("serverUrl", "").takeIf { it.isNotBlank() }?.let { parts.add("Login: $it") }
        json.optJSONArray("favoriteChannelIds")?.length()?.takeIf { it > 0 }?.let { parts.add("$it favorite channel${if (it == 1) "" else "s"}") }
        json.optJSONArray("extraServers")?.length()?.takeIf { it > 0 }?.let { parts.add("$it extra provider${if (it == 1) "" else "s"}") }
        json.optJSONArray("mergedFavorites")?.length()?.takeIf { it > 0 }?.let { parts.add("$it other-provider favorites") }
        json.optJSONObject("vodProgress")?.length()?.takeIf { it > 0 }?.let { parts.add("$it movie${if (it == 1) "" else "s"} in progress") }
        json.optJSONObject("seriesProgress")?.length()?.takeIf { it > 0 }?.let { parts.add("$it show${if (it == 1) "" else "s"} in progress") }
        if (parts.isEmpty()) "Settings only (no favorites/providers/progress in this file)" else parts.joinToString("\n") { "• $it" }
    } catch (e: Exception) {
        "Couldn't read this backup's contents: ${e.message}"
    }

    private fun showBackupFileActionDialog(file: File) {
        val dateFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        AlertDialog.Builder(this)
            .setTitle(dateFmt.format(Date(file.lastModified())))
            .setItems(arrayOf("Restore this backup", "Share / export a copy", "Delete")) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this)
                        .setTitle("Restore this backup?")
                        .setMessage("This will overwrite your current login, favorites, and settings with:\n\n${backupContentSummary(file)}")
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

    /** Shows this calendar month's network usage per provider — see BandwidthUsageEntity kdoc
     * for how it's recorded. Only providers with an actual recorded row are listed (a provider
     * never played this month has nothing to show, rather than a misleading "0 B" row). */
    private fun showDataUsageDialog() {
        lifecycleScope.launch {
            val yearMonth = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())
            val usage = db.bandwidthUsageDao().getUsageForMonth(yearMonth)
            if (usage.isEmpty()) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Data Usage — This Month")
                    .setMessage("No playback recorded yet this month.")
                    .setPositiveButton("Close", null)
                    .show()
                return@launch
            }
            val primaryNick = prefs.serverNickname.first().ifBlank { "Primary" }
            val extraNicks = prefs.getExtraServersWithNick().map { it.getOrElse(3) { "" } }
            val message = usage.joinToString("\n") { row ->
                val nick = if (row.serverIndex == -1) primaryNick
                    else extraNicks.getOrNull(row.serverIndex).takeUnless { it.isNullOrBlank() } ?: "Provider ${row.serverIndex + 1}"
                "$nick: ${com.iptvapp.util.RecordingFileUtils.formatBytes(row.bytesTransferred)}"
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Data Usage — This Month")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    /** Feature C: Bandwidth Budget Mode — set (or clear) a single global monthly data cap in GB
     * across every provider combined. Warn-only: see BandwidthBudgetManager kdoc, no bitrate/
     * quality changes are ever made from this. 0/blank clears the cap. */
    /** Feature C: per-provider monthly data cap (GB) — presents one provider at a time via a
     * picker, then an EditText for that provider's cap, mirroring showDataUsageDialog's
     * per-provider granularity (primaryNick/extraNicks lookup). Caps are stored under
     * PreferencesManager's bandwidth_cap_gb_<serverIndex> key scheme; 0/blank disables the cap
     * for that provider. WARN-ONLY — see BandwidthBudgetManager, never touches playback quality. */
    private fun showBandwidthBudgetDialog() {
        lifecycleScope.launch {
            val primaryNick = prefs.serverNickname.first().ifBlank { "Primary" }
            val extraNicks = prefs.getExtraServersWithNick().map { it.getOrElse(3) { "" } }
            val providers = buildList {
                add(-1 to primaryNick)
                extraNicks.forEachIndexed { i, nick ->
                    add(i to (nick.takeUnless { it.isBlank() } ?: "Provider ${i + 1}"))
                }
            }
            val labels = providers.map { it.second }.toTypedArray()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Monthly Data Cap — Choose Provider")
                .setItems(labels) { _, which ->
                    val (serverIndex, nick) = providers[which]
                    showBandwidthCapEditDialog(serverIndex, nick)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showBandwidthCapEditDialog(serverIndex: Int, nickname: String) {
        lifecycleScope.launch {
            val current = prefs.getBandwidthCapGb(serverIndex)
            val input = android.widget.EditText(this@SettingsActivity).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(if (current > 0) current.toString() else "")
                hint = "e.g. 500"
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("$nickname — Monthly Data Cap (GB)")
                .setMessage("Get warned at 80% and 100% of this cap for this provider only. This only shows a warning — video quality is never changed. Leave blank to disable.")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val gb = input.text.toString().trim().toIntOrNull() ?: 0
                    lifecycleScope.launch { prefs.setBandwidthCapGb(serverIndex, gb) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Provider Health Weather Map — rolling hour-of-day view of playback error/rebuffer rate
     * per provider, built from ProviderHourlyStatsEntity (fed by the same PlayerActivity hook
     * that feeds Ghost Channel Radar's per-channel reliability). 100% local/Room, no Firestore. */
    private fun showProviderWeatherDialog() {
        lifecycleScope.launch {
            val providers = com.iptvapp.util.ProviderHealth.buildWeatherMap(db, prefs)
            if (providers.isEmpty()) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Provider Health Weather Map")
                    .setMessage("No playback recorded yet — this fills in as you watch live TV.")
                    .setPositiveButton("Close", null)
                    .show()
                return@launch
            }
            val scroll = android.widget.ScrollView(this@SettingsActivity).apply {
                addView(com.iptvapp.util.ProviderHealth.buildWeatherMapView(this@SettingsActivity, providers))
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Provider Health Weather Map")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show()
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
                        // The Shield (same Settings screen since v6.87) plays it through TvHomeActivity's
                        // mktv://play deep link, as the old TV Settings did, not the phone home.
                        if (isLargeScreenDevice()) {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("mktv://play/${channel.streamId}")))
                        } else {
                            startActivity(Intent(this, com.iptvapp.ui.home.HomeActivity::class.java).apply {
                                putExtra(com.iptvapp.ui.home.HomeActivity.EXTRA_JUMP_TO_STREAM_ID, channel.streamId)
                            })
                        }
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

    /** Shows the LAN Export dialog: starts a local HTTP server serving the same debug bundle
     * sendDebugReport() sends to Discord, and displays a QR code + raw URL for another device on
     * the same WiFi to scan/type and download it in a browser. Server lifecycle is tied to the
     * dialog — starts when the dialog opens, stops on dismiss (covers both the user tapping away
     * and the Activity going down), whichever comes first. No timeout beyond that is needed since
     * dismiss already guarantees cleanup. */
    private fun showLanExportDialog() {
        val server = LanExportServer()
        val url = server.localUrl(this)
        if (url == null) {
            Toast.makeText(this, "No local network connection found — connect to WiFi and try again", Toast.LENGTH_LONG).show()
            return
        }
        server.start {
            // bundleProvider runs on the server's accept thread, not the UI thread — DebugInfoCollector
            // itself needs a coroutine scope for its Flow.first() calls, so block here with runBlocking
            // since this is already off the main thread.
            kotlinx.coroutines.runBlocking { DebugInfoCollector.collect(this@SettingsActivity, db, prefs) }
        }

        val size = 500
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size)
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        container.addView(android.widget.ImageView(this).apply { setImageBitmap(bitmap) })
        container.addView(android.widget.TextView(this).apply {
            text = url
            setPadding(0, 24, 0, 0)
            gravity = android.view.Gravity.CENTER
            setTextIsSelectable(true)
        })

        AlertDialog.Builder(this)
            .setTitle("LAN Export")
            .setMessage("Scan this code on another device connected to the same WiFi network to download a diagnostics bundle (no credentials included).")
            .setView(container)
            .setPositiveButton("Done", null)
            .setOnDismissListener { server.stop() }
            .show()
    }

    /** Same "Receive a Cast" flow LoginActivity offers before login (see CastRelayManager kdoc)
     * — reachable here too since an already-logged-in device never sees that pre-login screen
     * again, and there's no reason your OWN second device (or anyone else's already-set-up
     * install) couldn't also be a cast receiver. castConsumed distinguishes "dialog dismissed
     * because playback started" from "dismissed by backing out", so the abandon-cleanup in
     * setOnDismissListener doesn't delete a session that's already mid-use. */
    private fun showReceiveCastDialog() {
        var castConsumed = false
        var sessionCode: String? = null
        lifecycleScope.launch {
            val code = try {
                castRelay.createSession()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Couldn't start: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            sessionCode = code

            val size = 480
            val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) for (y in 0 until size)
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)

            val container = android.widget.LinearLayout(this@SettingsActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 24, 48, 24)
            }
            container.addView(android.widget.ImageView(this@SettingsActivity).apply { setImageBitmap(bitmap) })
            container.addView(android.widget.TextView(this@SettingsActivity).apply {
                text = "Waiting for a stream to be cast here..."
                setPadding(0, 24, 0, 0)
                gravity = android.view.Gravity.CENTER
            })

            val dialog = AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Receive a Cast")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setOnDismissListener {
                    castListenerRegistration?.remove()
                    castListenerRegistration = null
                    if (!castConsumed) {
                        val abandonedCode = sessionCode
                        sessionCode = null
                        if (abandonedCode != null) lifecycleScope.launch { castRelay.endSession(abandonedCode) }
                    }
                }
                .show()

            castListenerRegistration = castRelay.listen(code) { payload ->
                castConsumed = true
                startActivity(Intent(this@SettingsActivity, com.iptvapp.ui.player.PlayerActivity::class.java).apply {
                    putExtra("stream_url", payload.url)
                    putExtra("stream_title", payload.title)
                    // Session stays open — PlayerActivity attaches its own listener (see
                    // attachCastSessionListener) so casting a different channel later doesn't
                    // require a brand new QR code, and owns ending the session on its own exit.
                    putExtra("cast_session_code", code)
                    // Carry the channel pack so the receiver can change channel on its own.
                    putExtra("cast_channels", com.iptvapp.sync.CastRelayManager.encodePack(payload.channels))
                })
                dialog.dismiss()
            }
        }
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
                    App: v${pInfo.versionName} (${pInfo.versionCodeCompat})
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
                // Used to cram the whole thing into an embed's description field via
                // fullDebug.take(3900) — Discord embed descriptions cap at 4096 characters, a
                // hard platform limit, not something raising the number fixes. Combined with
                // getCrashLog()'s own now-larger budget, that truncation could still cut a crash
                // entry's tail off, or (since recent entries sort last in that budget while
                // take() keeps only the FRONT of the combined text) drop the newest crash
                // entirely in favor of an older one. Sent as a plain-text file attachment
                // instead — Discord webhooks accept those well past this size, and the whole
                // report goes through untruncated regardless of how large the crash log gets.
                val payloadJson = JSONObject().apply {
                    put("username", "Captain Hook")
                    put("content", "**$reportTitle**")
                }
                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("payload_json", payloadJson.toString())
                    .addFormDataPart(
                        "files[0]", "debug_report.txt",
                        fullDebug.toRequestBody("text/plain".toMediaType())
                    )
                    .build()
                binding.tvReportStatus.text = "Sending report..."
                val response = withContext(Dispatchers.IO) {
                    OkHttpClient().newCall(
                        Request.Builder()
                            .url(AppConstants.DISCORD_WEBHOOK)
                            .post(multipartBody)
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
                binding.btnSendDebugReport.text = "Send"
                binding.btnSendDebugReport.isEnabled = true
            }
        }
    }

    private fun liveReconnectSpeedLabel(speed: String) = when (speed) {
        "aggressive" -> "Aggressive"
        "patient" -> "Patient"
        else -> "Normal"
    }

    private fun refreshHiddenChannelsCount() {
        lifecycleScope.launch {
            val n = repository.getHiddenChannels().first().size + repository.getHiddenMergedChannels().first().size
            binding.tvHiddenChannelsValue.text = if (n == 0) "None" else "$n hidden"
        }
    }

    /** Every hidden channel (primary + other providers), ticked to unhide. Nothing else in the app
     * could unhide one, though the "hidden" toast has always said to come here. */
    private fun showHiddenChannelsDialog() {
        lifecycleScope.launch {
            val primary = repository.getHiddenChannels().first()
            val merged = repository.getHiddenMergedChannels().first()
            if (primary.isEmpty() && merged.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "No hidden channels", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val primaryNick = prefs.serverNickname.first().ifBlank { "Primary" }
            val labels = (primary.map { "${it.name}  ·  $primaryNick" } +
                merged.map { "${it.name}  ·  ${it.serverNickname}" }).toTypedArray()
            val checked = BooleanArray(labels.size)
            suspend fun unhide(indices: List<Int>) {
                indices.forEach { idx ->
                    if (idx < primary.size) db.channelDao().setHidden(primary[idx].streamId, false)
                    else merged[idx - primary.size].let { repository.unhideMergedChannel(it.serverIndex, it.streamId) }
                }
                Toast.makeText(this@SettingsActivity, "${indices.size} channel${if (indices.size == 1) "" else "s"} unhidden", Toast.LENGTH_SHORT).show()
                refreshHiddenChannelsCount()
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Hidden channels")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("Unhide selected") { _, _ ->
                    val picked = checked.indices.filter { checked[it] }
                    if (picked.isNotEmpty()) lifecycleScope.launch { unhide(picked) }
                }
                .setNeutralButton("Unhide all") { _, _ -> lifecycleScope.launch { unhide(labels.indices.toList()) } }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun zapSpeedLabel(ms: Int) = when (ms) {
        150 -> "Fast (150ms)"
        300 -> "Medium (300ms)"
        500 -> "Slow (500ms)"
        else -> "Instant"
    }

    // A rapid D-pad channel-up/down press still switches the OSD preview instantly either way —
    // this only delays the actual network resolve + player reload after the last press, so
    // mashing the button settles on one real channel switch. See PlayerActivity.playChannel.
    private fun showChannelZapSpeedDialog() {
        lifecycleScope.launch {
            val options = arrayOf("Instant (no delay)", "Fast (150ms)", "Medium (300ms)", "Slow (500ms)")
            val values = intArrayOf(0, 150, 300, 500)
            val selIdx = values.indexOf(prefs.channelZapDebounceMs.first()).coerceAtLeast(0)
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Channel Change Speed")
                .setSingleChoiceItems(options, selIdx) { dialog, which ->
                    val ms = values[which]
                    lifecycleScope.launch {
                        prefs.setChannelZapDebounceMs(ms)
                        binding.tvChannelZapSpeedValue.text = zapSpeedLabel(ms)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showLiveReconnectSpeedDialog() {
        lifecycleScope.launch {
            val current = prefs.liveReconnectSpeed.first()
            val options = arrayOf("Aggressive (1s steps, 10s ceiling)", "Normal (2s steps, 30s ceiling)", "Patient (3s steps, 60s ceiling)")
            val values = arrayOf("aggressive", "normal", "patient")
            val selIdx = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Live Reconnect Speed")
                .setSingleChoiceItems(options, selIdx) { dialog, which ->
                    val speed = values[which]
                    lifecycleScope.launch {
                        prefs.setLiveReconnectSpeed(speed)
                        binding.tvLiveReconnectSpeedValue.text = liveReconnectSpeedLabel(speed)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun reminderLeadTimeLabel(minutes: Int) = if (minutes <= 0) "At start time" else "$minutes min before"

    private suspend fun refreshShowAlertsValue() {
        val n = prefs.getShowAlertKeywords().size
        binding.tvShowAlertsValue.text = when (n) { 0 -> "Off"; 1 -> "1 word"; else -> "$n words" }
    }

    /** Show alerts (util/ShowAlerts): the words list — tap one to remove it, or add another. */
    private fun showShowAlertsDialog() {
        lifecycleScope.launch {
            val keywords = prefs.getShowAlertKeywords().toMutableList()
            val labels = (listOf("＋ Add a word or show") + keywords.map { "✕  $it" }).toTypedArray()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(if (keywords.isEmpty()) "Show alerts" else "Show alerts — tap a word to remove it")
                .setItems(labels) { _, which ->
                    if (which == 0) {
                        promptAddShowAlert(keywords)
                    } else {
                        lifecycleScope.launch {
                            keywords.removeAt(which - 1)
                            prefs.setShowAlertKeywords(keywords)
                            refreshShowAlertsValue()
                            showShowAlertsDialog()
                        }
                    }
                }
                .setNegativeButton("Done", null)
                .show()
        }
    }

    // Show alerts asked for notification permission (promptAddShowAlert): once allowed, run the
    // check the new word was promised.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 4711 || grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        lifecycleScope.launch {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.iptvapp.util.ShowAlerts.scan(applicationContext, db, prefs)
            }
            if (found > 0) Toast.makeText(this@SettingsActivity, "$found matching show(s) in the guide now", Toast.LENGTH_LONG).show()
        }
    }

    private fun promptAddShowAlert(current: List<String>) {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. Yankees, Chicago P.D."
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Alert me about")
            .setMessage("You'll get a notification when a show with this in its title is in the guide in the next two days.")
            .setView(android.widget.FrameLayout(this).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                addView(input)
            })
            .setPositiveButton("Add") { _, _ ->
                val word = input.text.toString().trim()
                if (word.length < 3) {
                    Toast.makeText(this, "Use at least 3 letters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    prefs.setShowAlertKeywords(current + word)
                    refreshShowAlertsValue()
                    if (!com.iptvapp.util.ShowAlerts.canNotify(this@SettingsActivity)) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Added — turn on notifications for MKTV to get show alerts",
                            Toast.LENGTH_LONG
                        ).show()
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4711)
                        }
                        showShowAlertsDialog()
                        return@launch
                    }
                    // Check the guide that's already loaded right away, not at the next refresh.
                    val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.iptvapp.util.ShowAlerts.scan(applicationContext, db, prefs)
                    }
                    Toast.makeText(
                        this@SettingsActivity,
                        if (found > 0) "Added — $found matching show(s) in the guide now" else "Added — you'll be notified when it shows up",
                        Toast.LENGTH_LONG
                    ).show()
                    showShowAlertsDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReminderLeadTimeDialog() {
        lifecycleScope.launch {
            val current = prefs.reminderLeadMinutes.first()
            val options = arrayOf("At start time", "1 min before", "5 min before", "10 min before", "15 min before")
            val values = intArrayOf(0, 1, 5, 10, 15)
            val selIdx = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Remind Me Lead Time")
                .setSingleChoiceItems(options, selIdx) { dialog, which ->
                    val minutes = values[which]
                    lifecycleScope.launch {
                        prefs.setReminderLeadMinutes(minutes)
                        binding.tvReminderLeadTimeValue.text = reminderLeadTimeLabel(minutes)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun autoClearContinueWatchingLabel(days: Int) = if (days <= 0) "Never" else "After $days days"

    private fun showAutoClearContinueWatchingDialog() {
        lifecycleScope.launch {
            val current = prefs.autoClearContinueWatchingDays.first()
            val options = arrayOf("Never", "After 7 days", "After 30 days", "After 90 days")
            val values = intArrayOf(0, 7, 30, 90)
            val selIdx = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Auto-Clear Continue Watching")
                .setSingleChoiceItems(options, selIdx) { dialog, which ->
                    val days = values[which]
                    lifecycleScope.launch {
                        prefs.setAutoClearContinueWatchingDays(days)
                        binding.tvAutoClearContinueWatchingValue.text = autoClearContinueWatchingLabel(days)
                        if (days > 0) {
                            val request = androidx.work.PeriodicWorkRequestBuilder<com.iptvapp.worker.ContinueWatchingCleanupWorker>(1, java.util.concurrent.TimeUnit.DAYS).build()
                            androidx.work.WorkManager.getInstance(this@SettingsActivity).enqueueUniquePeriodicWork(
                                com.iptvapp.worker.ContinueWatchingCleanupWorker.WORK_NAME,
                                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                                request
                            )
                        } else {
                            androidx.work.WorkManager.getInstance(this@SettingsActivity).cancelUniqueWork(com.iptvapp.worker.ContinueWatchingCleanupWorker.WORK_NAME)
                        }
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
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
                val installedCode = packageManager.getPackageInfo(packageName, 0).versionCodeCompat
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
                val savedEpgUrl = com.iptvapp.AppConstants.currentEpgUrl(prefs.epgUrl.first())
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
                binding.tvAutoClearContinueWatchingValue.text = autoClearContinueWatchingLabel(prefs.autoClearContinueWatchingDays.first())
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
                binding.switchPreferReliableCopy.isChecked = prefs.preferReliableCopy.first()
                binding.switchExtraBuffering.isChecked = prefs.extraBufferingEnabled.first()
                binding.tvLiveReconnectSpeedValue.text = liveReconnectSpeedLabel(prefs.liveReconnectSpeed.first())
                binding.tvChannelZapSpeedValue.text = zapSpeedLabel(prefs.channelZapDebounceMs.first())
                binding.tvReminderLeadTimeValue.text = reminderLeadTimeLabel(prefs.reminderLeadMinutes.first())
                refreshShowAlertsValue()
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
                currentAccentColor = prefs.accentColor.first()
                currentAccentColorEnd = prefs.accentColorEnd.first()
                setupAccentPicker()
                // Also sets the Sort channels label, with its value in the accent.
                applyAccentToSettings()
                setupSubtitleSettings()
                updateLastRefreshText()
                updateCacheAgeText()
                binding.tvStatusFormat.text = prefs.preferredFormat.first().uppercase(Locale.US)
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

    // Also feeds the status strip's GUIDE readout: the time for a refresh today, otherwise the date.
    private suspend fun updateLastRefreshText() {
        val time = prefs.lastEpgRefreshTime.first()
        if (time == 0L) {
            binding.tvLastEpgRefresh.text = "Not refreshed yet"
            binding.tvStatusGuide.text = "Never"
            return
        }
        val date = Date(time)
        binding.tvLastEpgRefresh.text = "Last refreshed " + SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(date)
        binding.tvStatusGuide.text = if (android.text.format.DateUtils.isToday(time))
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        else
            SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
    }

    private suspend fun updateCacheAgeText() {
        val newest = db.epgDao().getNewestEpgStopTimestamp()
        val nowSeconds = System.currentTimeMillis() / 1000
        binding.tvEpgCacheAge.text = when {
            newest == null -> "Guide data: unknown"
            newest < nowSeconds -> "Guide data has run out"
            else -> "Guide data covers the next ~${(newest - nowSeconds) / 3600} h"
        }
    }

    private val extraServers = mutableListOf<List<String>>()

    private fun setupServers() {
        lifecycleScope.launch {
            extraServers.clear()
            extraServers.addAll(prefs.getExtraServersWithNick())
            updateServerList()
        }
        binding.btnAddServer.setOnClickListener { showAddSourceTypeDialog() }
    }

    // Counting each provider's channels / movies / series takes a few seconds on a big catalog (57k
    // channels on the Shield), and the list used to sit empty meanwhile — it looked like there were
    // no providers, only "Add provider". Now: "Loading providers…" on first open, the old cards stay
    // up during a refresh, and a newer refresh cancels an older one so cards never double up.
    private var serverListJob: kotlinx.coroutines.Job? = null

    private fun updateServerList() {
        val ll = binding.llServers
        if (ll.childCount == 0) ll.addView(android.widget.TextView(this).apply {
            text = "Loading providers…"
            setTextColor(getColor(R.color.rack_text_muted))
            textSize = 15f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, (12 * resources.displayMetrics.density).toInt())
        })
        // The cards left up during a rebuild hold provider indices from before it (a removal shifts
        // them), so they're greyed out and every control on them is switched off until replaced.
        fun disableTree(v: View) {
            v.isEnabled = false
            v.isFocusable = false
            if (v is ViewGroup) for (c in 0 until v.childCount) disableTree(v.getChildAt(c))
        }
        if (ll.childCount > 0) { disableTree(ll); ll.alpha = 0.5f }
        serverListJob?.cancel()
        serverListJob = lifecycleScope.launch {
            val creds = prefs.credentials.first()
            val activeIndex = prefs.activeServerIndex.first()
            val primaryNick = prefs.serverNickname.first().ifEmpty { creds.username }

            // Real per-provider counts and sync status — part of the same OLED/cyan reskin as
            // the channel list (v6.69) and player (v6.70), continued here. Primary's counts come
            // straight from its own tables (channels/vod_streams/series are primary-only —
            // secondary/merged providers live in the separate merged_* tables, see
            // MergedChannelEntity's own kdoc), so no per-server filtering is needed for it.
            val primaryChannelCount = try { db.channelDao().getCount() } catch (_: Exception) { 0 }
            val primaryVodCount = try { db.vodDao().getCount() } catch (_: Exception) { 0 }
            val primarySeriesCount = try { db.seriesDao().getCount() } catch (_: Exception) { 0 }
            val primaryLastSync = prefs.lastChannelsFetchTime.first()
            // Merged tables' own getServerSummaries() DAOs return (serverIndex, nickname, count)
            // for every configured secondary provider in one query each — exactly what each
            // provider's own stat row needs, keyed for O(1) lookup instead of a query per card.
            val mergedChannelCounts = db.mergedChannelDao().getServerSummaries().first().associate { it.serverIndex to it.channelCount }
            val mergedVodCounts = db.mergedVodDao().getServerSummaries().first().associate { it.serverIndex to it.vodCount }
            val mergedSeriesCounts = db.mergedSeriesDao().getServerSummaries().first().associate { it.serverIndex to it.seriesCount }
            // One shared timestamp for every secondary provider — refreshMergedChannels() stamps
            // it on completion whether it refreshed one targeted provider or all of them (see its
            // own kdoc), so this is genuinely accurate for "last time this provider's data was
            // written," not an approximation, even though it isn't tracked separately per index.
            val mergedLastSync = prefs.lastMergedChannelsRefresh.first()
            // "Synced Xm ago" / "Not yet synced" — same wording and 0L-means-never handling as
            // the Compose preview's SyncStatusBar, so the two don't drift into different phrasing
            // for the same underlying concept. Only accurate for a timestamp that's genuinely
            // success-gated (primary's lastChannelsFetchTime is — set inside fetchLiveStreams()
            // after its upsert succeeds, never reached if the fetch itself failed).
            fun syncLabel(lastSyncMs: Long): String {
                if (lastSyncMs <= 0L) return "Not yet synced"
                val elapsedMin = (System.currentTimeMillis() - lastSyncMs) / 60_000
                return if (elapsedMin < 1) "Synced just now" else "Synced ${elapsedMin}m ago"
            }
            // Separate wording for lastMergedChannelsRefresh: unlike lastChannelsFetchTime above,
            // HomeViewModel.refreshMergedChannels() stamps this unconditionally whether the
            // refresh succeeded or every provider failed (see its own code) — "Synced" would
            // claim success a failed attempt doesn't actually have. "Refresh attempted" is
            // accurate either way; if it failed, the per-provider error is already surfaced
            // elsewhere (the Refresh Channels button's own failure toast, providersDownCount).
            fun attemptLabel(lastAttemptMs: Long): String {
                if (lastAttemptMs <= 0L) return "Not yet refreshed"
                val elapsedMin = (System.currentTimeMillis() - lastAttemptMs) / 60_000
                return if (elapsedMin < 1) "Refresh attempted just now" else "Refresh attempted ${elapsedMin}m ago"
            }

            // ── Provider cards, in the Studio Rack look (v6.80): view construction only. The data
            // above and every button's click handler below are unchanged by the restyle. ──
            val density = resources.displayMetrics.density
            fun dp(v: Int) = (v * density + 0.5f).toInt()
            val counts = java.text.NumberFormat.getIntegerInstance()
            val accent = accentColorInt()
            val condensed = rackFont(R.font.barlow_condensed_semibold)

            // Small condensed caps label (PRIMARY PROVIDER, LIVE …), same as the Rack.Eyebrow style.
            fun eyebrow(value: String) = android.widget.TextView(this@SettingsActivity).apply {
                text = value
                setTextColor(getColor(R.color.rack_text_muted))
                textSize = 12f
                typeface = condensed
                letterSpacing = 0.16f
                isAllCaps = true
                setSingleLine(true)
            }

            // One provider = one module, in the same box as the other panels' groups (edge to edge
            // on a phone, bordered on a wide screen, where RackColumns puts two side by side).
            fun providerCard(dimmed: Boolean) = android.widget.LinearLayout(this@SettingsActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.rack_group_bg)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(12) }
                // Dimmed while disabled — same "muted" visual language the app already uses rather
                // than inventing a new convention. Everything stays readable, just de-emphasized.
                alpha = if (dimmed) 0.45f else 1f
            }

            // "PROVIDER 2" + nickname on the left; ● ACTIVE (in the user's accent) or INACTIVE on
            // the right, like the status strip's PROVIDER readout.
            fun cardHeader(label: String, nickname: String, active: Boolean) = android.widget.LinearLayout(this@SettingsActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.TOP
                addView(android.widget.LinearLayout(this@SettingsActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(eyebrow(label))
                    addView(android.widget.TextView(this@SettingsActivity).apply {
                        text = nickname
                        setTextColor(getColor(R.color.rack_text))
                        textSize = 18f
                        typeface = rackFont(R.font.barlow_semibold)
                        setSingleLine(true)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                })
                addView(android.widget.LinearLayout(this@SettingsActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.marginStart = dp(12) }
                    if (active) addView(View(this@SettingsActivity).apply {
                        tag = TAG_ACCENT_DOT
                        background = accentDot(accent, accentEndColorInt())
                        layoutParams = android.widget.LinearLayout.LayoutParams(dp(12), dp(12)).also { it.marginEnd = dp(6) }
                    })
                    addView(android.widget.TextView(this@SettingsActivity).apply {
                        text = if (active) "ACTIVE" else "INACTIVE"
                        if (active) tag = TAG_ACCENT_TEXT
                        setTextColor(if (active) accent else getColor(R.color.rack_text_muted))
                        textSize = 13f
                        typeface = rackFont(R.font.barlow_condensed_bold)
                        letterSpacing = 0.12f
                    })
                })
            }

            // Server URL / playlist source — one line, middle-ellipsized so both the host and the
            // port/path stay visible.
            fun detailLine(value: String) = android.widget.TextView(this@SettingsActivity).apply {
                text = value
                setTextColor(getColor(R.color.rack_text_muted))
                textSize = 13f
                typeface = rackFont(R.font.barlow_regular)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(6) }
            }

            // LIVE / MOVIES / SERIES as readouts between hairlines, like the status strip. null =
            // a kind of content this source type never has (M3U is live-only): that readout is
            // left out instead of showing a permanent 0 that would read like missing data.
            fun statsRow(live: Int, movies: Int?, series: Int?) = android.widget.LinearLayout(this@SettingsActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.rack_strip_bg)
                dividerDrawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@SettingsActivity, R.drawable.rack_cell_divider)
                showDividers = android.widget.LinearLayout.SHOW_DIVIDER_MIDDLE
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(12) }
                listOfNotNull("LIVE" to live, movies?.let { "MOVIES" to it }, series?.let { "SERIES" to it })
                    .forEachIndexed { idx, (label, value) ->
                        addView(android.widget.LinearLayout(this@SettingsActivity).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            setPadding(if (idx == 0) 0 else dp(12), dp(9), dp(8), dp(9))
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            addView(eyebrow(label))
                            addView(android.widget.TextView(this@SettingsActivity).apply {
                                text = counts.format(value.toLong())
                                setTextColor(getColor(R.color.rack_text))
                                textSize = 17f
                                typeface = rackFont(R.font.barlow_semibold)
                                fontFeatureSettings = "tnum"
                                setSingleLine(true)
                            })
                        })
                    }
            }

            fun statusLine(value: String) = android.widget.TextView(this@SettingsActivity).apply {
                text = value
                setTextColor(getColor(R.color.rack_text_secondary))
                textSize = 13f
                typeface = rackFont(R.font.barlow_regular)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(10) }
            }

            // Card buttons come from small layout files so they share activity_settings.xml's exact
            // button styles (D-pad focus and disabled states included) instead of re-creating them
            // by hand here.
            fun cardButton(layoutRes: Int, parent: ViewGroup, label: String) =
                (layoutInflater.inflate(layoutRes, parent, false) as com.google.android.material.button.MaterialButton)
                    .apply { text = label }

            // Cards are collected with a rank and added at the end, so the active provider comes
            // first, then the other enabled ones, then disabled ones (each group in its usual order).
            // Only the on-screen order changes: every card's buttons still act on its own index.
            val cards = mutableListOf<Pair<Int, View>>()
            fun rank(active: Boolean, enabled: Boolean) = when { active -> 0; enabled -> 1; else -> 2 }
            val primaryRow = providerCard(dimmed = false).apply {
                addView(cardHeader("PRIMARY PROVIDER", primaryNick, active = activeIndex == -1))
                // Extra providers already show their URL directly (added earlier so a stray
                // space/typo could be spotted) — the primary row never did, making it impossible
                // to verify the nickname you see actually matches the credentials really in use.
                addView(detailLine(creds.serverUrl))
                addView(statsRow(primaryChannelCount, primaryVodCount, primarySeriesCount))
                addView(statusLine(syncLabel(primaryLastSync)))
            }
            cardButton(R.layout.settings_provider_btn_secondary, primaryRow, "Edit").apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                ).also { it.topMargin = dp(12) }
                setOnClickListener { showEditPrimaryDialog(creds, primaryNick) }
                primaryRow.addView(this)
            }
            cards.add(rank(active = activeIndex == -1, enabled = true) to primaryRow)

            extraServers.forEachIndexed { i, server ->
                val url = server[0]; val user = server[1]
                val nick = server.getOrElse(3) { "" }.ifEmpty { user }
                val enabled = server.getOrElse(5) { "true" }.toBoolean()
                val isM3u = server.getOrElse(6) { "xtream" } == "m3u"
                val row = providerCard(dimmed = !enabled)
                row.addView(cardHeader("PROVIDER ${i + 2}", nick, active = activeIndex == i))
                // An m3u-type row's url/user/pass slots are blank placeholders (see
                // importM3uAsSecondarySource) — there's no Xtream credential URL to show, so
                // show what this source actually is instead: PLAYLIST FILE for a pasted-text
                // import (index 7, m3uUrl, is blank), or the URL it was imported from.
                row.addView(detailLine(
                    if (isM3u) {
                        val m3uUrl = server.getOrElse(7) { "" }
                        if (m3uUrl.isBlank()) "M3U PLAYLIST · FILE" else "M3U PLAYLIST · $m3uUrl"
                    } else url
                ))
                // M3U sources never have VOD/series (M3uParser is live-channels-only — see its own
                // kdoc), so their stats only ever show what's actually real for them (see statsRow).
                row.addView(
                    if (isM3u) statsRow(mergedChannelCounts[i] ?: 0, null, null)
                    else statsRow(mergedChannelCounts[i] ?: 0, mergedVodCounts[i] ?: 0, mergedSeriesCounts[i] ?: 0)
                )
                // M3U sources are explicitly excluded from refreshMergedChannels() (see its
                // own kdoc) — mergedLastSync wouldn't reflect anything real for one, so this
                // says what's actually true instead of borrowing a timestamp that isn't theirs.
                // attemptLabel, not syncLabel: see its own kdoc for why "Synced" would
                // overclaim here in a way it doesn't for the primary card.
                row.addView(
                    if (isM3u) statusLine("Not auto-refreshed (M3U)")
                    else statusLine(attemptLabel(mergedLastSync))
                )
                android.widget.Switch(this@SettingsActivity).apply {
                    text = if (enabled) "Enabled" else "Disabled"
                    isChecked = enabled
                    setTextColor(getColor(R.color.rack_text))
                    textSize = 16f
                    typeface = rackFont(R.font.barlow_medium)
                    // Same square thumb/track as every other Settings toggle (styles_settings.xml);
                    // applyRackAccent below tints it. Unlike those, this one is its own focus stop
                    // (it isn't inside a toggle row), so it carries the row focus background.
                    setThumbResource(R.drawable.rack_switch_thumb)
                    setTrackResource(R.drawable.rack_switch_track)
                    setBackgroundResource(R.drawable.rack_row_bg)
                    setPadding(dp(8), 0, dp(8), 0)
                    minHeight = dp(48)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = dp(6); it.marginStart = -dp(8); it.marginEnd = -dp(8) }
                    setOnCheckedChangeListener { _, checked ->
                        val updated = extraServers[i].toMutableList()
                        while (updated.size < 6) updated.add("true")
                        updated[5] = checked.toString()
                        extraServers[i] = updated
                        lifecycleScope.launch {
                            prefs.saveExtraServersWithNick(extraServers)
                            // Previously called clearAll() here unconditionally (every toggle of
                            // ANY provider wiped EVERY configured provider's merged favorites/
                            // folders — channels, VOD, and series alike). That not only affected
                            // unrelated providers, it also destroyed the exact isFavorite/
                            // favoriteFolderId state that refreshMergedChannels()'s "prev" lookup
                            // needs to restore favorites on the next refresh — so re-enabling a
                            // disabled provider could never get its favorites back either.
                            // Disabled providers are already excluded from every browsing/refresh
                            // path via allConfiguredServers() — the aggregate Favorites view is
                            // the one place that reads merged_channels directly without going
                            // through that filter, so it's fixed to filter by enabled servers
                            // instead (see HomeViewModel.startCombinedLiveCategories). Nothing
                            // needs to be cleared here at all: the data just sits untouched until
                            // the provider is re-enabled and refreshed again.
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
                    ).also { it.topMargin = dp(8) }
                }
                cardButton(R.layout.settings_provider_btn_secondary, btnRow, "Edit").apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(44), 1f)
                        .also { it.marginEnd = dp(8) }
                    // An m3u row has no credentials to edit (its url/user/pass slots are blank
                    // placeholders — see importM3uAsSecondarySource), just a nickname.
                    setOnClickListener { if (isM3u) showRenameM3uSourceDialog(i, nick) else showEditServerDialog(i) }
                    btnRow.addView(this)
                }
                // Switch (promote to primary) assumes Xtream credentials to promote — an m3u
                // source has none, and "become the primary login" isn't something this source
                // type supports. Skipped entirely rather than shown disabled/confusing.
                if (!isM3u) cardButton(R.layout.settings_provider_btn_primary, btnRow, "Switch").apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(44), 1f)
                        .also { it.marginEnd = dp(8) }
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
                            // Guide URLs swap with the logins: the promoted provider's own XMLTV URL becomes
                            // the primary's, and the old primary keeps its URL in the slot it moves into
                            // (both used to be dropped — the old primary's guide kept feeding the new one).
                            val promotedEpgUrl = extraServers[i].getOrElse(4) { "" }
                            updated[i] = listOf(primary.serverUrl, primary.username, primary.password,
                                prefs.serverNickname.first(), prefs.epgUrl.first(), "true", "xtream", "")
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
                            repository.clearMergedProviderData(i)
                            // The old primary now lives in that slot with nothing cached — mark merged data stale so
                            // the restarted home screen reloads it instead of waiting out the 6-hour window.
                            prefs.setLastMergedChannelsRefresh(0L)
                            prefs.saveCredentials(url, user, newPass)
                            prefs.setServerNickname(newNick)
                            prefs.setEpgUrl(promotedEpgUrl)
                            prefs.setActiveServerIndex(-1)
                            // The Shield uses this Settings screen too (v6.87), so restart into its own home.
                            val home = if (isLargeScreenDevice()) com.iptvapp.ui.home.TvHomeActivity::class.java
                                else com.iptvapp.ui.home.HomeActivity::class.java
                            val intent = Intent(this@SettingsActivity, home)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                        }
                    }
                    btnRow.addView(this)
                }
                // Same "danger" treatment as Logout / Clear / Trakt Disconnect: a dark red fill
                // with red text, not a solid red block.
                cardButton(R.layout.settings_provider_btn_danger, btnRow, "Remove").apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(44), 1f)
                    setOnClickListener {
                        extraServers.removeAt(i)
                        lifecycleScope.launch {
                            prefs.saveExtraServersWithNick(extraServers)
                            // NonCancellable — lifecycleScope cancels on exactly the things a
                            // user does in the middle of tapping a button and moving on
                            // (navigating away, backgrounding, rotating). Letting this land
                            // mid-way through the clear-then-reindex sequence below produced a
                            // real, reported SQLiteException("SQL logic error") from
                            // clearForServer on a real device — see XtreamRepository.
                            // refreshMergedChannels' matching comment for the full reasoning.
                            withContext(NonCancellable) {
                                // merged_channels: delete just this server's own rows, then shift
                                // every later server's rows down one index in place, instead of the
                                // old clearAll() + rely-on-next-refresh-to-repopulate-everyone. That
                                // was always a little wasteful for Xtream providers (refetches
                                // everyone on the next refresh instead of just re-indexing), but for
                                // an m3u-type source it was real data loss — refreshMergedChannels()
                                // never repopulates those (see its kdoc), so removing ANY provider
                                // silently emptied every M3U source in the list, not just the one
                                // actually being removed. See decrementServerIndicesAfter's own kdoc.
                                db.mergedChannelDao().clearForServer(i)
                                db.mergedChannelDao().decrementServerIndicesAfter(i)
                                // VOD/series are always Xtream-sourced (M3U import only ever carries
                                // live channels — see M3uParser), so the old wipe-and-let-the-next-
                                // refresh-repopulate approach doesn't lose anything permanently here,
                                // just costs a re-fetch. Left as-is rather than widening this fix.
                                db.mergedVodDao().clearAll()
                                db.mergedSeriesDao().clearAll()
                            }
                        }
                        updateServerList()
                    }
                    btnRow.addView(this)
                }
                row.addView(btnRow)
                // Per-provider live-channel refresh — deliberately separate from the Movies/
                // Series refresh buttons in the Display section, and from Home's "Refresh All
                // Providers" (which touches every configured server at once). This only
                // re-fetches THIS provider's live channels/categories. Hidden for m3u rows:
                // refreshMergedChannels() intentionally skips them (see its own kdoc) — there's
                // no Xtream API to re-fetch from, and showing a button that always silently does
                // nothing is worse than not showing it.
                if (!isM3u) cardButton(R.layout.settings_provider_btn_secondary, row, "Refresh Channels").apply {
                    icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@SettingsActivity, R.drawable.ic_settings_refresh)
                    iconSize = dp(16)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                    ).also { it.topMargin = dp(8) }
                    setOnClickListener {
                        isEnabled = false
                        val originalText = text
                        text = "Refreshing…"
                        lifecycleScope.launch {
                            val errors = repository.refreshMergedChannels(i)
                            // This call went straight to the repository, bypassing
                            // HomeViewModel.refreshMergedChannels() entirely — which was the only
                            // place stamping lastMergedChannelsRefresh, so a refresh triggered from
                            // here never used to count toward that timestamp at all. Stamped here
                            // too now, same as HomeViewModel does: unconditionally, attempt or not,
                            // since that's this field's existing meaning everywhere else it's
                            // read (see PreferencesManager.lastMergedChannelsRefresh's callers) —
                            // not changing that meaning here, just making this entry point
                            // contribute to it like the other one already does.
                            prefs.setLastMergedChannelsRefresh(System.currentTimeMillis())
                            isEnabled = true
                            text = originalText
                            val msg = errors[i]?.let { err -> "Failed: $err" } ?: "Channels refreshed"
                            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
                            // The count/sync-status lines added in this same pass were fetched
                            // once via first() when the screen first loaded — without this, a
                            // successful refresh changed the real data but the card kept showing
                            // the old numbers until the screen was left and reopened.
                            updateServerList()
                        }
                    }
                    row.addView(this)
                }
                cards.add(rank(active = activeIndex == i, enabled = enabled) to row)
            }
            ll.removeAllViews()
            cards.sortedBy { it.first }.forEach { ll.addView(it.second) }
            ll.isEnabled = true
            ll.alpha = 1f

            // The status strip's PROVIDER / CHANNELS readouts follow whichever provider is active.
            val activeServer = extraServers.getOrNull(activeIndex)
            binding.tvStatusProvider.text = (activeServer?.let { s -> s.getOrElse(3) { "" }.ifEmpty { s.getOrElse(1) { "" } } } ?: primaryNick)
                .ifEmpty { "—" }
            binding.tvStatusChannels.text = counts.format(
                (if (activeServer == null) primaryChannelCount else mergedChannelCounts[activeIndex] ?: 0).toLong()
            )
            // The cards were built with the accent as it was a moment ago; if loadSettings has
            // read a different saved one since, this brings them in line.
            applyRackAccent(ll)
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
                        // A URL/username change here means this is really a different provider,
                        // not just a password fix — without clearing the OLD primary's
                        // channels/categories/VOD/series/EPG, stale favorited channels from that
                        // provider (wrong streamIds, wrong cached streamUrl) linger forever and
                        // silently fail to play under the new primary's credentials.
                        val isProviderSwap = url != creds.serverUrl || user != creds.username
                        if (isProviderSwap) repository.clearPrimaryProviderData()
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

    // "Add Source" used to always mean "Add Provider" (Xtream). Now offers a second, genuinely
    // different kind of source — an M3U playlist that coexists as its own toggleable entry
    // (Settings > Providers) rather than replacing your primary login the way LoginActivity's
    // M3U import does — see XtreamRepository.importM3uAsSecondarySource kdoc.
    private fun showAddSourceTypeDialog() {
        val options = arrayOf("Xtream Provider", "M3U Playlist")
        AlertDialog.Builder(this)
            .setTitle("Add Source")
            .setItems(options) { _, which ->
                if (which == 0) showAddServerDialog() else showAddM3uSourceDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddM3uSourceDialog() {
        val etNick = android.widget.EditText(this).apply {
            hint = "Nickname (e.g. UK Sports)"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("M3U Playlist")
            .setView(etNick)
            .setPositiveButton("Next") { _, _ ->
                val nick = etNick.text.toString().trim().ifBlank { "M3U Playlist" }
                val options = arrayOf("Enter M3U URL", "Choose file")
                AlertDialog.Builder(this)
                    .setTitle(nick)
                    .setItems(options) { _, which ->
                        if (which == 0) {
                            showM3uSourceUrlDialog(nick)
                        } else {
                            pendingM3uSourceNickname = nick
                            m3uSourceFileLauncher.launch(arrayOf("*/*", "text/*", "audio/x-mpegurl"))
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showM3uSourceUrlDialog(nickname: String) {
        val input = android.widget.EditText(this).apply {
            hint = "http://example.com/playlist.m3u"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("M3U URL")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val result = repository.importM3uAsSecondarySourceFromUrl(nickname, url)
                    handleM3uSourceImportResult(nickname, result)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // m3u rows have no credentials for the normal Edit flow (showEditServerDialog) to edit —
    // just a nickname, so this is deliberately a smaller dialog rather than reusing that one.
    private fun showRenameM3uSourceDialog(index: Int, currentNick: String) {
        val input = android.widget.EditText(this).apply {
            setText(currentNick)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Source")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newNick = input.text.toString().trim().ifBlank { currentNick }
                val updated = extraServers[index].toMutableList()
                while (updated.size < 4) updated.add("")
                updated[3] = newNick
                extraServers[index] = updated
                lifecycleScope.launch {
                    prefs.saveExtraServersWithNick(extraServers)
                    updateServerList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun importM3uSource(nickname: String, sourceUrl: String?, content: String) {
        handleM3uSourceImportResult(nickname, repository.importM3uAsSecondarySource(nickname, sourceUrl, content))
    }

    private fun handleM3uSourceImportResult(nickname: String, result: Resource<Int>) {
        when (result) {
            is Resource.Success -> {
                lifecycleScope.launch {
                    extraServers.clear()
                    extraServers.addAll(prefs.getExtraServersWithNick())
                    updateServerList()
                }
                Toast.makeText(this@SettingsActivity, "Imported ${result.data} channels as \"$nickname\"", Toast.LENGTH_SHORT).show()
            }
            is Resource.Error -> Toast.makeText(this@SettingsActivity, "Import failed: ${result.message}", Toast.LENGTH_SHORT).show()
            else -> {}
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
                val lastSent = traktManager.lastScrobbleSent.value
                binding.tvTraktStatus.text = when {
                    lastError != null -> "✓ Connected — last scrobble failed: $lastError"
                    // A scrobble that "succeeded" but never crossed Trakt's own ~80% watched
                    // threshold produces zero watched-history entries with no error anywhere —
                    // showing what was actually sent makes that silent case diagnosable.
                    lastSent != null -> "✓ Connected — last sent to Trakt: $lastSent"
                    else -> "✓ Connected — scrobbling your watch activity"
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
        binding.btnJoinWatchParty.setOnClickListener { showJoinWatchPartyDialog() }
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
        binding.switchPreferReliableCopy.setOnCheckedChangeListener { _, enabled ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setPreferReliableCopy(enabled) }
        }
        binding.switchExtraBuffering.setOnCheckedChangeListener { _, enabled ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setExtraBufferingEnabled(enabled) }
        }
        binding.rowLiveReconnectSpeed.setOnClickListener { showLiveReconnectSpeedDialog() }
        binding.rowChannelZapSpeed.setOnClickListener { showChannelZapSpeedDialog() }
        binding.rowHiddenChannels.setOnClickListener { showHiddenChannelsDialog() }
        refreshHiddenChannelsCount()
        binding.rowReminderLeadTime.setOnClickListener { showReminderLeadTimeDialog() }
        binding.rowShowAlerts.setOnClickListener { showShowAlertsDialog() }
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

    /** Resolves an entered watch-party code and launches PlayerActivity with the party doc's
     * content extras plus watch_party_code, so PlayerActivity attaches the member listener in
     * onCreate instead of starting a fresh unsynced playback. */
    private fun showJoinWatchPartyDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. ab12cd34"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Join Watch Party")
            .setMessage("Enter the code shown on the host's screen.")
            .setView(input)
            .setPositiveButton("Join") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    val state = watchPartyManager.joinParty(code)
                    if (state == null) {
                        Toast.makeText(this@SettingsActivity, "Party not found", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    launchPlayerForWatchParty(state)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun launchPlayerForWatchParty(state: com.iptvapp.sync.WatchPartyState) {
        val content = state.content
        val intent = Intent(this@SettingsActivity, com.iptvapp.ui.player.PlayerActivity::class.java)
        intent.putExtra("watch_party_code", state.code)
        intent.putExtra("stream_title", content.title)
        // A party can be created paused, waiting for people to join before the host presses play
        // (see WatchPartyManager.startParty) — join at the party's actual current position/state
        // instead of always auto-playing from wherever this device's own catalog happens to start.
        if (content.contentType != "LIVE") {
            intent.putExtra("resume_ms", state.positionMs)
            intent.putExtra("watch_party_start_paused", !state.isPlaying)
        }
        when (content.contentType) {
            "LIVE" -> {
                intent.putExtra("is_vod", false)
                intent.putExtra("stream_id", content.streamId)
                intent.putExtra("server_index", content.serverIndex)
                intent.putExtra("merged_stream_id", content.mergedStreamId)
                val url = try {
                    if (content.serverIndex != -1) repository.getMergedLiveStreamUrl(content.serverIndex, content.mergedStreamId)
                    else repository.getLiveStreamUrl(content.streamId)
                } catch (e: Exception) {
                    Toast.makeText(this@SettingsActivity, "Couldn't load party's channel", Toast.LENGTH_SHORT).show()
                    return
                }
                // Building a URL never fails for a catalog id that simply doesn't exist on this
                // device's own provider (XtreamUrlBuilder is pure string construction, no upfront
                // validation) -- launching PlayerActivity on a URL like that used to just sit in
                // its generic buffering/retry loop with zero Watch-Party-specific error, since a
                // request that keeps returning SOME response (even a provider error page) never
                // hits a hard connection exception the retry ladder would treat as a give-up.
                // checkStreamHealth actually probes the URL first so this is caught immediately,
                // before ever launching a player that would otherwise just hang.
                if (!repository.checkStreamHealth(url)) {
                    Toast.makeText(this@SettingsActivity, "Couldn't join Watch Party — this channel isn't available on your provider", Toast.LENGTH_LONG).show()
                    return
                }
                intent.putExtra("stream_url", url)
            }
            "EPISODE" -> {
                val url = if (content.episodeId.isBlank()) null else try {
                    repository.getSeriesEpisodeUrl(content.episodeId, content.containerExtension)
                } catch (e: Exception) { null }
                val healthy = url != null && repository.checkStreamHealth(url)
                if (!healthy) {
                    // Same reasoning as the VOD branch below — the host's exact episodeId/
                    // seriesId doesn't exist on this device's own provider, so fall back to
                    // searching this device's own series catalog by name before giving up.
                    offerWatchPartyEpisodeTitleFallback(state)
                    return
                }
                intent.putExtra("is_vod", true)
                intent.putExtra("stream_url", url)
                intent.putExtra("series_id", content.seriesId)
                intent.putExtra("season_num", content.seasonNum)
                intent.putExtra("episode_num", content.episodeNum)
            }
            else -> { // VOD
                val url = try {
                    if (content.serverIndex != -1) repository.getMergedVodStreamUrl(content.serverIndex, content.mergedStreamId, content.containerExtension)
                    else repository.getVodStreamUrl(content.streamId, content.containerExtension)
                } catch (e: Exception) { null }
                val healthy = url != null && repository.checkStreamHealth(url)
                if (!healthy) {
                    // The host's exact catalog id doesn't exist on this device's own provider —
                    // common when both accounts carry the same movie under a different id (e.g.
                    // one provider prefixes titles with a language code the other doesn't, or one
                    // has extra text appended). Fall back to searching this device's own catalogs
                    // by title before giving up entirely.
                    offerWatchPartyVodTitleFallback(state)
                    return
                }
                intent.putExtra("is_vod", true)
                intent.putExtra("stream_id", content.streamId)
                intent.putExtra("server_index", content.serverIndex)
                intent.putExtra("merged_stream_id", content.mergedStreamId)
                intent.putExtra("stream_url", url)
            }
        }
        startActivity(intent)
    }

    /** Watch Party VOD fallback: the host's exact catalog id isn't on this device's own provider,
     * so search this device's own catalogs by (normalized) title instead. Shows a picker when more
     * than one match comes back — e.g. the same movie exists on both a primary and a merged
     * provider, or under a couple of near-duplicate listings — rather than guessing which to play. */
    private suspend fun offerWatchPartyVodTitleFallback(state: com.iptvapp.sync.WatchPartyState) {
        val hostTitle = state.content.title
        val matches = try {
            repository.findWatchPartyVodMatches(hostTitle)
        } catch (_: Exception) {
            emptyList()
        }
        if (matches.isEmpty()) {
            Toast.makeText(this@SettingsActivity, "Couldn't join Watch Party — \"$hostTitle\" isn't available on your provider", Toast.LENGTH_LONG).show()
            return
        }
        if (matches.size == 1) {
            launchWatchPartyVodMatch(state, matches[0])
            return
        }
        // setMessage + setItems on the same AlertDialog.Builder are mutually exclusive — Android
        // silently drops the items list and shows only the message text when both are set, which
        // is exactly why the picker never appeared (the "N close matches" text showed with no
        // actual choices). The match count goes in the title instead so setItems alone renders.
        val labels = matches.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which \"$hostTitle\"? (${matches.size} matches)")
            .setItems(labels) { _, which -> launchWatchPartyVodMatch(state, matches[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchWatchPartyVodMatch(state: com.iptvapp.sync.WatchPartyState, match: com.iptvapp.data.repository.XtreamRepository.WatchPartyVodMatch) {
        lifecycleScope.launch {
            val url = try {
                if (match.serverIndex != -1) repository.getMergedVodStreamUrl(match.serverIndex, match.streamId, match.containerExtension)
                else repository.getVodStreamUrl(match.streamId, match.containerExtension)
            } catch (e: Exception) { null }
            if (url == null || !repository.checkStreamHealth(url)) {
                Toast.makeText(this@SettingsActivity, "Couldn't load \"${match.title}\"", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val intent = Intent(this@SettingsActivity, com.iptvapp.ui.player.PlayerActivity::class.java)
            intent.putExtra("watch_party_code", state.code)
            intent.putExtra("watch_party_content_substituted", true)
            intent.putExtra("stream_title", state.content.title)
            intent.putExtra("resume_ms", state.positionMs)
            intent.putExtra("watch_party_start_paused", !state.isPlaying)
            intent.putExtra("is_vod", true)
            intent.putExtra("stream_id", match.streamId)
            intent.putExtra("server_index", match.serverIndex)
            intent.putExtra("merged_stream_id", if (match.serverIndex != -1) match.streamId else -1)
            intent.putExtra("stream_url", url)
            startActivity(intent)
        }
    }

    /** Episode equivalent of offerWatchPartyVodTitleFallback — the host's exact seriesId/
     * episodeId isn't on this device's own provider, so search this device's own series catalog
     * by name, then look up the matching season/episode within each series match. */
    private suspend fun offerWatchPartyEpisodeTitleFallback(state: com.iptvapp.sync.WatchPartyState) {
        val content = state.content
        val seriesName = content.seriesName.ifBlank { content.title }
        val matches = try {
            repository.findWatchPartyEpisodeMatch(seriesName, content.seasonNum, content.episodeNum)
        } catch (_: Exception) {
            emptyList()
        }
        if (matches.isEmpty()) {
            Toast.makeText(this@SettingsActivity, "Couldn't join Watch Party — \"$seriesName\" S${content.seasonNum}E${content.episodeNum} isn't available on your provider", Toast.LENGTH_LONG).show()
            return
        }
        if (matches.size == 1) {
            launchWatchPartyEpisodeMatch(state, matches[0])
            return
        }
        val labels = matches.map { it.seriesTitle }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which \"$seriesName\"? (${matches.size} matches)")
            .setItems(labels) { _, which -> launchWatchPartyEpisodeMatch(state, matches[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchWatchPartyEpisodeMatch(state: com.iptvapp.sync.WatchPartyState, match: com.iptvapp.data.repository.XtreamRepository.WatchPartyEpisodeMatch) {
        lifecycleScope.launch {
            val url = try {
                if (match.serverIndex != -1) repository.getMergedSeriesEpisodeUrl(match.serverIndex, match.episodeId, match.containerExtension)
                else repository.getSeriesEpisodeUrl(match.episodeId, match.containerExtension)
            } catch (e: Exception) { null }
            if (url == null || !repository.checkStreamHealth(url)) {
                Toast.makeText(this@SettingsActivity, "Couldn't load \"${match.seriesTitle}\"", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val intent = Intent(this@SettingsActivity, com.iptvapp.ui.player.PlayerActivity::class.java)
            intent.putExtra("watch_party_code", state.code)
            intent.putExtra("watch_party_content_substituted", true)
            intent.putExtra("stream_title", state.content.title)
            intent.putExtra("resume_ms", state.positionMs)
            intent.putExtra("watch_party_start_paused", !state.isPlaying)
            intent.putExtra("is_vod", true)
            intent.putExtra("series_id", match.seriesId)
            intent.putExtra("season_num", state.content.seasonNum)
            intent.putExtra("episode_num", state.content.episodeNum)
            intent.putExtra("stream_url", url)
            startActivity(intent)
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
                // TCP/HTTP only prove the server itself answers — neither ever requests a real
                // stream, so a provider can "ping" fine here while actual playback is broken
                // (rate-limited account, blocked subscription, misconfigured stream endpoint).
                // This line is the only one that actually tries to play something.
                val streamStr = when (r.streamPlayable) {
                    true -> "Stream Test: ✓ playable"
                    false -> "Stream Test: ✗ NOT playable — server responds but streams don't work"
                    null -> "Stream Test: no channel to test (catalog empty)"
                }
                // Only present when streamPlayable was true (see measureStreamThroughputMbps's
                // call site kdoc) — a real download-speed measurement in addition to the above
                // "does it respond at all" checks, which say nothing about whether it's fast
                // enough to actually stream without buffering.
                val speedStr = r.throughputMbps?.let { "\nDownload Speed: ${"%.1f".format(it)} Mbps" } ?: ""
                val errorLine = r.error?.let { "\n$it" } ?: ""
                "${r.nickname}\n$tcpStr\n$httpStr\n$streamStr$speedStr\nServer: ${r.host}$errorLine"
            }
        } catch (e: Exception) {
            binding.tvSpeedTestResult.text = "Error: ${e.message}"
        } finally {
            binding.btnSpeedTest.isEnabled = true
        }
    }

    companion object {
        private const val AUTO_EPG_WORK_NAME = "auto_epg_refresh_work"

        // android:tag values the Rack styles put on views (styles_settings.xml has the full list),
        // read by applyRackAccent / wireToggleRows.
        private const val TAG_TOGGLE_ROW = "toggleRow"
        private const val TAG_TOGGLE = "toggle"
        private const val TAG_SEGMENT = "segment"
        private const val TAG_ACCENT_FILL = "accentFill"
        private const val TAG_ACCENT_OUTLINE = "accentOutline"
        private const val TAG_ACCENT_ICON = "accentIcon"
        private const val TAG_ACCENT_TEXT = "accentText"
        private const val TAG_ACCENT_DOT = "accentDot"
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
            put("preferReliableCopy", prefs.preferReliableCopy.first())
            put("showAlertKeywords", org.json.JSONArray(prefs.getShowAlertKeywords()))
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
                        // Previously omitted — restoring a backup silently re-enabled every
                        // provider that had been disabled, since getOrElse(5) { "true" } defaults
                        // to enabled when this field is missing from the restored row.
                        put("enabled", s.getOrElse(5) { "true" })
                        // Previously omitted too — an m3u-type source (see ConfiguredServer.type)
                        // restored without these silently became an Xtream provider with blank
                        // credentials, since applyBackupJson's own getOrElse defaults type to
                        // "xtream" when the field is missing. See applyBackupJson for how a
                        // restored m3u entry actually gets its channels back (re-imported from
                        // m3uUrl, not written directly — there's nothing else here to restore
                        // MergedChannelEntity rows from, this JSON was never a full DB dump).
                        put("type", s.getOrElse(6) { "xtream" }); put("m3uUrl", s.getOrElse(7) { "" })
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
        if (json.has("preferReliableCopy")) prefs.setPreferReliableCopy(json.optBoolean("preferReliableCopy", true))
        json.optJSONArray("showAlertKeywords")?.let { arr -> prefs.setShowAlertKeywords((0 until arr.length()).map { arr.optString(it) }) }
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
            val entries = (0 until extraServersArray.length()).map { extraServersArray.getJSONObject(it) }
            // Xtream entries restore directly, same as before — their credentials are all this
            // JSON ever needed to fully recover them (channels come back from the next refresh).
            val xtreamRestored = entries.filter { it.optString("type", "xtream") != "m3u" }.map { obj ->
                listOf(
                    obj.optString("url", ""), obj.optString("user", ""), obj.optString("pass", ""),
                    obj.optString("nick", ""), obj.optString("epg", ""),
                    // Previously missing — a restore always defaulted every provider back to
                    // enabled regardless of what it actually was at backup time, since
                    // getOrElse(5) { "true" } reads a missing index as enabled.
                    obj.optString("enabled", "true")
                )
            }
            prefs.saveExtraServersWithNick(xtreamRestored)
            extraServers.clear(); extraServers.addAll(xtreamRestored)
            db.mergedChannelDao().clearAll()
            db.mergedVodDao().clearAll()
            db.mergedSeriesDao().clearAll()

            // m3u entries can't be restored the same way this JSON restores everything else —
            // it was never a full DB dump, so there's no stored copy of their channels here to
            // write back. One imported from a URL can genuinely recover by re-fetching that URL
            // right now (importM3uAsSecondarySourceFromUrl appends it fresh, after the xtream
            // entries just restored above, so it gets a correct new index same as adding one
            // normally would). One imported by pasting text has nothing left to recover from and
            // is skipped — restoring it as a permanently-empty toggle would be worse than not
            // restoring it at all, since there'd be no way to ever populate it short of removing
            // and re-adding it anyway.
            val m3uEntries = entries.filter { it.optString("type", "xtream") == "m3u" }
            if (m3uEntries.isNotEmpty()) {
                var recovered = 0; var failed = 0; var skipped = 0
                m3uEntries.forEach { obj ->
                    val nick = obj.optString("nick", "").ifBlank { "M3U Playlist" }
                    val m3uUrl = obj.optString("m3uUrl", "")
                    val wasEnabled = obj.optString("enabled", "true")
                    if (m3uUrl.isBlank()) {
                        skipped++
                    } else when (repository.importM3uAsSecondarySourceFromUrl(nick, m3uUrl)) {
                        is Resource.Success -> {
                            recovered++
                            // importM3uAsSecondarySourceInternal always appends a freshly-
                            // imported source as enabled — a playlist that was deliberately
                            // disabled at backup time needs that state re-applied here, or every
                            // restore silently re-enables it. Always appended last (see that
                            // function's own kdoc on index allocation), so it's the last entry
                            // right after this call, before any other m3u entry is processed.
                            if (wasEnabled != "true") {
                                val fresh = prefs.getExtraServersWithNick().toMutableList()
                                val lastIndex = fresh.size - 1
                                val updated = fresh[lastIndex].toMutableList()
                                while (updated.size < 6) updated.add("true")
                                updated[5] = wasEnabled
                                fresh[lastIndex] = updated
                                prefs.saveExtraServersWithNick(fresh)
                            }
                        }
                        else -> failed++
                    }
                }
                extraServers.clear(); extraServers.addAll(prefs.getExtraServersWithNick())
                val parts = mutableListOf<String>()
                if (recovered > 0) parts.add("$recovered M3U playlist${if (recovered == 1) "" else "s"} re-imported")
                if (failed > 0) parts.add("$failed failed to re-fetch")
                if (skipped > 0) parts.add("$skipped need re-adding manually (not imported from a URL)")
                if (parts.isNotEmpty()) {
                    Toast.makeText(this@SettingsActivity, parts.joinToString(", "), Toast.LENGTH_LONG).show()
                }
            }
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
