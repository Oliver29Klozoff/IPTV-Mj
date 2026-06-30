package com.iptvapp.ui.settings
import com.iptvapp.BuildConfig

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.iptvapp.IptvApplication
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

    private val backupFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) lifecycleScope.launch { writeBackupToUri(uri) }
    }

    private val restoreFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) lifecycleScope.launch { restoreBackupFromUri(uri) }
    }

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

    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var db: IptvDatabase
    @Inject lateinit var repository: com.iptvapp.data.repository.XtreamRepository
    @Inject lateinit var syncManager: com.iptvapp.sync.SyncManager

    private val sortLabels = listOf("Default", "A-Z", "Popular", "Recent")
    private var currentSortIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        workManager = WorkManager.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("This will clear all data and return to the login screen. Continue?")
                .setPositiveButton("Logout") { _, _ ->
                    lifecycleScope.launch {
                        repository.logout()
                        val intent = Intent(this@SettingsActivity, com.iptvapp.ui.login.LoginActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnWhatsNew.setOnClickListener { showChangelog() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }

        // Quick Actions — sort cycles, others launch intents back to home
        binding.btnSettingsSort.setOnClickListener {
            currentSortIndex = (currentSortIndex + 1) % sortLabels.size
            binding.btnSettingsSort.text = "⇅  Sort Channels: ${sortLabels[currentSortIndex]}"
            lifecycleScope.launch { prefs.setChannelSortMode(currentSortIndex) }
        }
        binding.btnSettingsWhatsOn.setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra("action", "whats_on"))
            finish()
        }
        binding.btnSettingsMosaic.setOnClickListener {
            startActivity(Intent(this, com.iptvapp.ui.mosaic.MosaicActivity::class.java))
        }
        binding.btnFeatureTour.setOnClickListener {
            FeatureTourDialog.show(this)
        }

        binding.btnSaveEpg.setOnClickListener {
            lifecycleScope.launch {
                prefs.setEpgUrl(binding.etEpgUrl.text.toString().trim())
                Toast.makeText(this@SettingsActivity, "EPG URL saved", Toast.LENGTH_SHORT).show()
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
                    if (i == index) Color.parseColor("#008CFF") else Color.parseColor("#AAAAAA")
                )
            }
        }
        navButtonViews.forEachIndexed { i, btn -> btn.setOnClickListener { selectPanel(i) } }
        binding.headerRecordings.setOnClickListener {
            startActivity(Intent(this, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java))
        }
        selectPanel(0)
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
        binding.btnRestoreSettings.setOnClickListener {
            restoreFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        binding.btnSendDebugReport.setOnClickListener { sendDebugReport() }

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
        if (enabled) {
            val dir = getExternalFilesDir(null) ?: filesDir
            binding.tvAutoBackupPath.text = "Saves to: ${dir.absolutePath}"
        } else {
            binding.tvAutoBackupPath.text = ""
        }
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

    private fun backupSettings() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        backupFileLauncher.launch("MKTV_backup_$timestamp.json")
    }

    private fun generateQrBitmap(content: String, size: Int = 800): Bitmap {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H,
            com.google.zxing.EncodeHintType.MARGIN to 1
        )
        val writer = QRCodeWriter()
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        try {
            val logoBitmap = BitmapFactory.decodeResource(resources, R.drawable.splash_logo)
            val logoSize = size / 5
            val scaledLogo = Bitmap.createScaledBitmap(logoBitmap, logoSize, logoSize, true)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.WHITE }
            val center = size / 2f
            canvas.drawCircle(center, center, logoSize / 2f + 10, paint)
            canvas.drawBitmap(scaledLogo, center - logoSize / 2f, center - logoSize / 2f, null)
            scaledLogo.recycle()
            logoBitmap.recycle()
        } catch (_: Exception) {}
        return bitmap
    }

    private fun showQrCode(content: String, prettyJson: String) {
        val bitmap = generateQrBitmap(content)
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            setPadding(32, 32, 32, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Backup QR Code")
            .setMessage("Scan to restore on another device")
            .setView(imageView)
            .setPositiveButton("Save to File") { _, _ ->
                try {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    dir.mkdirs()
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val backupFile = File(dir, "MKTV_backup_${timestamp}.json")
                    backupFile.writeText(prettyJson)
                    binding.tvBackupStatus.text = "✓ Saved to Documents"
                } catch (e: Exception) {
                    binding.tvBackupStatus.text = "Save failed: ${e.message}"
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun sendDebugReport() {
        binding.btnSendDebugReport.isEnabled = false
        binding.btnSendDebugReport.text = "Collecting..."
        binding.tvReportStatus.text = "Collecting device info..."
        lifecycleScope.launch {
            try {
                val token = prefs.githubToken.first().takeIf { it.isNotBlank() }
                    ?: BuildConfig.GH_TOKEN_B64
                if (token.isBlank()) {
                    binding.tvReportStatus.text = "⚠ No GitHub token — add GH_TOKEN_B64 to local.properties and rebuild"
                    binding.btnSendDebugReport.isEnabled = true
                    binding.btnSendDebugReport.text = "Send Debug Report"
                    return@launch
                }
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
                val crashLog = IptvApplication.getCrashLog(this@SettingsActivity)
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
                val title = "Debug Report - v${pInfo.versionName}.${pInfo.longVersionCode} - ${Build.MODEL}"
                val body = "## Device Debug Report\n\n```\n$fullDebug\n```"
                val json = JSONObject().apply {
                    put("title", title)
                    put("body", body)
                    put("labels", JSONArray().put("debug-report"))
                }
                binding.tvReportStatus.text = "Sending report..."
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://api.github.com/repos/Oliver29Klozoff/IPTV-Mj/issues")
                    .addHeader("Authorization", "token $token")
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                if (response.isSuccessful) {
                    val responseJson = JSONObject(response.body?.string() ?: "")
                    val issueNumber = responseJson.getInt("number")
                    binding.tvReportStatus.text = "✓ Report sent (Issue #$issueNumber)"
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
                        .setPositiveButton("Update now") { _, _ -> downloadAndInstall(apkUrl, latestName) }
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

    private fun downloadAndInstall(apkUrl: String, versionName: String) {
        binding.tvUpdateStatus.text = "Resolving download URL..."
        lifecycleScope.launch {
            val resolvedUrl = withContext(Dispatchers.IO) { resolveRedirect(apkUrl) }
            downloadFromUrl(resolvedUrl, versionName)
        }
    }

    private fun downloadFromUrl(apkUrl: String, versionName: String) {
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
                        binding.tvUpdateStatus.text = "Download complete — installing..."
                        installApk(file)
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
                    installApk(file)
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

    private fun installApk(file: File) {
        val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
        if (!canInstall) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            })
            binding.tvUpdateStatus.text = "Allow installs from unknown sources, then retry"
            return
        }
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
            isLoadingSettings = true
            try {
                binding.etEpgUrl.setText(prefs.epgUrl.first())
                when (prefs.preferredFormat.first()) {
                    "ts" -> binding.rbTs.isChecked = true
                    else -> binding.rbM3u8.isChecked = true
                }
                binding.cbRefreshMissingOnly.isChecked = prefs.epgRefreshMissingOnly.first()
                binding.cbUsaOnlyChannels.isChecked = prefs.usaOnlyChannels.first()
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
                setTextColor(if (activeIndex == -1) Color.parseColor("#008CFF") else Color.parseColor("#555555"))
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
                    setTextColor(if (activeIndex == i) Color.parseColor("#008CFF") else Color.parseColor("#555555"))
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
        val etNick = android.widget.EditText(this).apply { hint = "Nickname (optional)" }
        val etUrl  = android.widget.EditText(this).apply { hint = "Server URL (http://...)" }
        val etUser = android.widget.EditText(this).apply { hint = "Username" }
        val etPass = android.widget.EditText(this).apply {
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

    private fun setupSyncSection() {
        binding.tvSyncStatus.text = ""
        lifecycleScope.launch {
            binding.tvSyncStatus.text = syncManager.getLastSyncSummary()
        }
        binding.switchSyncEnabled.setOnCheckedChangeListener { _, enabled ->
            if (isLoadingSettings) return@setOnCheckedChangeListener
            lifecycleScope.launch { prefs.setSyncEnabled(enabled) }
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

    private suspend fun writeBackupToUri(uri: Uri) {
        val creds = prefs.credentials.first()
        val json = JSONObject().apply {
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
            val favCategoryIds = prefs.favoriteLiveCategoryIds.first()
            put("favoriteCategoryIds", JSONArray(favCategoryIds.toList()))
            val favChannels = db.channelDao().getFavoriteChannelIds()
            put("favoriteChannelIds", JSONArray(favChannels))
        }
        contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
        binding.tvBackupStatus.text = "✓ Backup saved"
        Toast.makeText(this, "Backup saved", Toast.LENGTH_SHORT).show()
    }

    private suspend fun restoreBackupFromUri(uri: Uri) {
        val jsonText = contentResolver.openInputStream(uri)
            ?.bufferedReader()?.use { it.readText() } ?: return
        val json = JSONObject(jsonText)

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

        // Reload UI after all prefs are set
        loadSettings()
        binding.tvBackupStatus.text = "✓ Restored successfully"
        Toast.makeText(this, "Restore complete", Toast.LENGTH_SHORT).show()
    }
}
