package com.iptvapp.ui.settings
import com.iptvapp.BuildConfig

import com.iptvapp.util.enableTvFocusHighlight
import com.iptvapp.util.isLargeScreenDevice

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
import android.view.View
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
        if (uri != null) {
            lifecycleScope.launch {
                writeBackupToUri(uri)
            }
        }
    }

    private val restoreFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                restoreBackupFromUri(uri)
            }
        }
    }

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var workManager: WorkManager
    private var currentEpgWorkId: UUID? = null

    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var db: IptvDatabase
    @Inject lateinit var repository: com.iptvapp.data.repository.XtreamRepository
    @Inject lateinit var syncManager: com.iptvapp.sync.SyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.enableTvFocusHighlight()
workManager = WorkManager.getInstance(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnLogout.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
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

        loadSettings()
        observeEpgRefreshWork()
        setupBackupRestore()
        setupSectionToggles()
        setupServers()
        setupSyncSection()

        binding.btnSaveEpg.setOnClickListener {
            lifecycleScope.launch {
                prefs.setEpgUrl(binding.etEpgUrl.text.toString().trim())
                Toast.makeText(this@SettingsActivity, "EPG URL saved", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRefreshEpg.setOnClickListener { startEpgRefresh() }

        binding.btnCancelEpgRefresh.setOnClickListener {
            workManager.cancelUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME)
            binding.tvEpgRefreshStatus.text = ""
            binding.btnRefreshEpg.isEnabled = true
            binding.btnCancelEpgRefresh.visibility = View.GONE
        }

        binding.cbRefreshMissingOnly.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setEpgRefreshMissingOnly(isChecked) }
        }

        binding.cbUsaOnlyChannels.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setUsaOnlyChannels(isChecked) }
        }

        binding.cbShowMovies.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setShowMovies(isChecked) }
        }

        binding.cbShowSeries.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { prefs.setShowSeries(isChecked) }
        }

        binding.cbShowWatching.setOnCheckedChangeListener { _, isChecked ->
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
                android.widget.Toast.makeText(this@SettingsActivity, msg, android.widget.Toast.LENGTH_LONG).show()
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
                android.widget.Toast.makeText(this@SettingsActivity, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        binding.rgAutoEpgRefresh.setOnCheckedChangeListener { _, checkedId ->
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
            lifecycleScope.launch {
                val format = when (checkedId) {
                    binding.rbTs.id -> "ts"
                    else -> "m3u8"
                }
                prefs.setPreferredFormat(format)
                Toast.makeText(this@SettingsActivity, "Format set to $format", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleSection(section: View, arrow: android.widget.TextView) {
        if (section.visibility == View.VISIBLE) {
            section.visibility = View.GONE
            arrow
        } else {
            section.visibility = View.VISIBLE
            arrow
        }
    }

    private fun setupSectionToggles() {
        val panels = listOf(binding.sectionStream, binding.sectionDisplay, binding.sectionUpdates, binding.sectionBackup, binding.sectionServers, binding.sectionSync)
        val navButtons = listOf(binding.headerStream, binding.headerDisplay, binding.headerUpdates, binding.headerBackup, binding.headerServers, binding.headerSync)
        fun selectPanel(index: Int) {
            panels.forEachIndexed { i, panel -> panel.visibility = if (i == index) android.view.View.VISIBLE else android.view.View.GONE }
            navButtons.forEachIndexed { i, btn ->
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(if (i == index) android.graphics.Color.parseColor("#1A3A5C") else android.graphics.Color.parseColor("#1A1A1A"))
                btn.setTextColor(if (i == index) android.graphics.Color.parseColor("#008CFF") else android.graphics.Color.parseColor("#AAAAAA"))
            }
        }
        navButtons.forEachIndexed { i, btn -> btn.setOnClickListener { selectPanel(i) } }
        selectPanel(0)
    }

    private fun setupBackupRestore() {
        binding.btnBackupSettings.setOnClickListener { backupSettings() }
        binding.btnRestoreSettings.setOnClickListener { restoreSettings() }
        binding.btnSendDebugReport.setOnClickListener { sendDebugReport() }
    }

    private fun backupSettings() {
        if (isLargeScreenDevice()) {
            lifecycleScope.launch {
                try {
                    val creds = prefs.credentials.first()
                    val favCategoryIds = prefs.favoriteLiveCategoryIds.first()
                    val favChannels = db.channelDao().getFavoriteChannelIds()
                    val json = JSONObject().apply {
                        put("epgUrl", prefs.epgUrl.first())
                        put("preferredFormat", prefs.preferredFormat.first())
                        put("usaOnlyChannels", prefs.usaOnlyChannels.first())
                        put("showMovies", prefs.showMovies.first())
                        put("showSeries", prefs.showSeries.first())
                        put("showWatching", prefs.showWatching.first())
                        put("epgRefreshMissingOnly", prefs.epgRefreshMissingOnly.first())
                        put("epgAutoRefreshHours", prefs.epgAutoRefreshHours.first())
                        put("serverUrl", creds.serverUrl)
                        put("username", creds.username)
                        put("password", creds.password)
                        put("favoriteCategoryIds", JSONArray(favCategoryIds.toList()))
                        put("favoriteChannelIds", JSONArray(favChannels))
                    }
                    showQrCode(json.toString(), json.toString(2))
                } catch (e: Exception) {
                    binding.tvBackupStatus.text = "Backup failed"
                }
            }
        } else {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            backupFileLauncher.launch("MKTV_backup_$timestamp.json")
        }
    }

    private fun restoreSettings() {
        restoreFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun generateQrBitmap(content: String, size: Int = 600): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        try {
            val logoBitmap = BitmapFactory.decodeResource(resources, R.drawable.splash_logo)
            val logoSize = size / 3
            val scaledLogo = Bitmap.createScaledBitmap(logoBitmap, logoSize, logoSize, true)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.WHITE }
            val center = size / 2f
            canvas.drawCircle(center, center, logoSize / 2f + 14, paint)
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
                    binding.tvBackupStatus.text = ""
                } catch (e: Exception) {
                    binding.tvBackupStatus.text = ""
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun sendDebugReport() {
        binding.btnSendDebugReport.isEnabled = false
        binding.btnSendDebugReport.isEnabled = false
        binding.btnSendDebugReport.text = "Collecting..."
                binding.tvReportStatus.text = "Step 1: collecting device info..."
        binding.tvReportStatus.text = ""
        lifecycleScope.launch {
            try {
                val token = assets.open("gh_token.txt").bufferedReader().use { it.readText().trim() }
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
                val serverUrl = try { prefs.credentials.first().serverUrl.let { url ->
                    java.net.URI(url).let { "${it.host}:${it.port}" }
                } } catch (_: Exception) { "unknown" }
                val lastRefresh = prefs.lastEpgRefreshTime.first().let { t ->
                    if (t == 0L) "Never" else java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(t))
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
                        .getWorkInfosForUniqueWork(com.iptvapp.worker.EpgRefreshWorker.UNIQUE_WORK_NAME).get()
                        .firstOrNull()?.state?.name ?: "None"
                } catch (_: Exception) { "Unknown" }
                binding.tvReportStatus.text = "Reading crash log..."
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
                binding.tvReportStatus.text = ""
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
                    binding.tvReportStatus.text = ""
                } else {
                    binding.tvReportStatus.text = ""
                }
            } catch (e: Exception) {
                binding.tvReportStatus.text = ""
            }
        }
    }

    private fun checkForUpdate() {
        binding.tvUpdateStatus.text = ""
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
                    binding.tvUpdateStatus.text = ""
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Update Available")
                        .setMessage("Version $latestName is available. Download and install now?")
                        .setPositiveButton("Download") { _, _ -> downloadAndInstall(apkUrl, latestName) }
                        .setNegativeButton("Later", null)
                        .show()
                } else {
                    binding.tvUpdateStatus.text = ""
                }
            } catch (e: Exception) {
                binding.tvUpdateStatus.text = ""
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
        binding.tvUpdateStatus.text = ""
        binding.progressEpgRefresh.visibility = View.VISIBLE
        binding.progressEpgRefresh.progress = 0
        lifecycleScope.launch {
            val resolvedUrl = withContext(Dispatchers.IO) { resolveRedirect(apkUrl) }
            android.util.Log.d("UPDATE", "Resolved URL: $resolvedUrl")
            android.util.Log.d("UPDATE", "Resolved URL: $resolvedUrl")
            downloadFromUrl(resolvedUrl, versionName)
        }
    }

    private fun downloadFromUrl(apkUrl: String, versionName: String) {
        binding.tvUpdateStatus.text = ""
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
                        binding.progressEpgRefresh.progress = pct
                        binding.tvUpdateStatus.text = ""
                    }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        progressHandler.removeCallbacks(this)
                        binding.progressEpgRefresh.progress = 100
                        binding.tvUpdateStatus.text = ""
                        installApk(file)
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
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply { data = Uri.parse("package:$packageName") })
            binding.tvUpdateStatus.text = ""
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
            binding.tvUpdateStatus.text = ""
        }
    }

    private fun showChangelog() {
        val text = try {
            assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        } catch (e: Exception) { "Changelog not available." }
        AlertDialog.Builder(this).setTitle("What's New").setMessage(text).setPositiveButton("Close", null).show()
    }

    private fun loadSettings() {
        lifecycleScope.launch {
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
            when (prefs.epgAutoRefreshHours.first()) {
                6 -> binding.rbAuto6.isChecked = true
                12 -> binding.rbAuto12.isChecked = true
                24 -> binding.rbAuto24.isChecked = true
                else -> binding.rbAutoOff.isChecked = true
            }
            updateLastRefreshText()
            updateCacheAgeText()
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
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
            binding.tvEpgRefreshStatus.text = ""
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
                ?: "EPG refresh state: ${info.state}"
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
                val status = info.progress.getString(EpgRefreshWorker.KEY_STATUS) ?: ""
                binding.progressEpgRefresh.visibility = View.VISIBLE
                binding.progressEpgRefresh.progress = progress
                if (status.isNotBlank()) binding.tvEpgRefreshStatus.text = status
                binding.btnRefreshEpg.isEnabled = false
                binding.btnCancelEpgRefresh.visibility = View.VISIBLE
            }
    }

    private fun scheduleAutoEpgRefresh(hours: Int) {
        if (hours == 0) { workManager.cancelUniqueWork(AUTO_EPG_WORK_NAME); return }
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
            newest < nowSeconds -> "EPG Cache Age: Expired"
            else -> "EPG Cache: covers about ${(newest - nowSeconds) / 3600} hours ahead"
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

            // Primary server row
            val primaryRow = android.widget.LinearLayout(this@SettingsActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
                setPadding(24, 20, 24, 20)
                val lp = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = 12
                layoutParams = lp
            }
            android.widget.TextView(this@SettingsActivity).apply {
                text = "PRIMARY"
                setTextColor(android.graphics.Color.parseColor("#777777"))
                textSize = 10f
                primaryRow.addView(this)
            }
            android.widget.TextView(this@SettingsActivity).apply {
                text = primaryNick
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                primaryRow.addView(this)
            }
            android.widget.TextView(this@SettingsActivity).apply {
                text = if (activeIndex == -1) "● ACTIVE" else "INACTIVE"
                setTextColor(if (activeIndex == -1) android.graphics.Color.parseColor("#008CFF") else android.graphics.Color.parseColor("#555555"))
                textSize = 12f
                primaryRow.addView(this)
            }
            ll.addView(primaryRow)

            // Extra server rows
            extraServers.forEachIndexed { i, server ->
                val url = server[0]; val user = server[1]
                val nick = server.getOrElse(3) { "" }.ifEmpty { user }
                val row = android.widget.LinearLayout(this@SettingsActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
                    setPadding(24, 20, 24, 20)
                    val lp = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.bottomMargin = 12
                    layoutParams = lp
                }
                android.widget.TextView(this@SettingsActivity).apply {
                    text = "SERVER ${i + 2}"
                    setTextColor(android.graphics.Color.parseColor("#777777"))
                    textSize = 10f
                    row.addView(this)
                }
                android.widget.TextView(this@SettingsActivity).apply {
                    text = nick
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 14f
                    row.addView(this)
                }
                android.widget.TextView(this@SettingsActivity).apply {
                    text = if (activeIndex == i) "● ACTIVE" else "INACTIVE"
                    setTextColor(if (activeIndex == i) android.graphics.Color.parseColor("#008CFF") else android.graphics.Color.parseColor("#555555"))
                    textSize = 12f
                    row.addView(this)
                }
                val btnRow = android.widget.LinearLayout(this@SettingsActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    val lp = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = 12
                    layoutParams = lp
                }
                android.widget.Button(this@SettingsActivity).apply {
                    text = "Switch"
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor("#1976D2"))
                    val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    lp.marginEnd = 8
                    layoutParams = lp
                    setOnClickListener {
                        lifecycleScope.launch {
                            val primary = prefs.credentials.first()
                            val newPass = extraServers[i][2]
                            val updated = extraServers.toMutableList()
                            updated[i] = listOf(primary.serverUrl, primary.username, primary.password, prefs.serverNickname.first())
                            prefs.saveExtraServersWithNick(updated)
                            withContext(kotlinx.coroutines.Dispatchers.IO) { db.clearAllTables() }
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
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor("#CC0000"))
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
        val etUrl = android.widget.EditText(this).apply { hint = "Server URL (http://...)" }
        val etUser = android.widget.EditText(this).apply { hint = "Username" }
        val etPass = android.widget.EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etNick)
        layout.addView(etUrl)
        layout.addView(etUser)
        layout.addView(etPass)
        AlertDialog.Builder(this)
            .setTitle("Add Server")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val url = etUrl.text.toString().trim()
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                if (url.isNotEmpty() && user.isNotEmpty()) {
                    lifecycleScope.launch {
                        val fresh = prefs.getExtraServersWithNick().toMutableList()
                        val nick = etNick.text.toString().trim()
                        fresh.add(listOf(url, user, pass, nick))
                        extraServers.clear()
                        extraServers.addAll(fresh)
                        prefs.saveExtraServersWithNick(extraServers)
                        Toast.makeText(this@SettingsActivity, "Server added", Toast.LENGTH_SHORT).show()
                    }
                    updateServerList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSyncSection() {
        lifecycleScope.launch {
            binding.switchSyncEnabled.isChecked = prefs.syncEnabled.first()
            binding.tvSyncStatus.text = syncManager.getLastSyncSummary()
        }
        binding.switchSyncEnabled.setOnCheckedChangeListener { _, enabled ->
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

        contentResolver.openOutputStream(uri)?.use { output ->
            output.write(json.toString(2).toByteArray())
        }

        android.widget.Toast.makeText(this, "Backup saved", android.widget.Toast.LENGTH_SHORT).show()
    }

    private suspend fun restoreBackupFromUri(uri: Uri) {
        val jsonText = contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return

        val json = JSONObject(jsonText)

        val serverUrl = json.optString("serverUrl", "")
        val username = json.optString("username", "")
        val password = json.optString("password", "")

        if (serverUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()) {
            prefs.saveCredentials(serverUrl, username, password)
        }

        json.optString("epgUrl", "").takeIf { it.isNotEmpty() }?.let {
            prefs.setEpgUrl(it)
            binding.etEpgUrl.setText(it)
        }

        json.optString("preferredFormat", "").takeIf { it.isNotEmpty() }?.let {
            prefs.setPreferredFormat(it)
        }

        if (json.has("epgAutoRefreshHours")) {
            prefs.setEpgAutoRefreshHours(json.optInt("epgAutoRefreshHours", 0))
        }

        if (json.has("epgRefreshMissingOnly")) {
            prefs.setEpgRefreshMissingOnly(json.optBoolean("epgRefreshMissingOnly", false))
        }

        // Restore favorite categories
        val favCatArray = json.optJSONArray("favoriteCategoryIds")
        if (favCatArray != null) {
            val ids = (0 until favCatArray.length()).map { favCatArray.getString(it) }.toSet()
            prefs.setFavoriteLiveCategoryIds(ids)
        }

        // Restore favorite channels
        val favChanArray = json.optJSONArray("favoriteChannelIds")
        if (favChanArray != null) {
            val ids = (0 until favChanArray.length()).map { favChanArray.getInt(it) }
            val existingIds = db.channelDao().getAllChannelIds().toSet()
            db.channelDao().clearAllFavorites()
            ids.filter { it in existingIds }.forEach { db.channelDao().setFavorite(it, true) }
            // Save pending IDs for channels not yet loaded
            val missingIds = ids.filter { it !in existingIds }.toSet()
            if (missingIds.isNotEmpty()) prefs.setPendingFavoriteChannelIds(missingIds)
        }

        binding.tvBackupStatus.text = "Restored successfully"
        loadSettings()

        if (json.has("usaOnlyChannels")) {
            prefs.setUsaOnlyChannels(json.optBoolean("usaOnlyChannels", true))
        }

        if (json.has("showMovies")) {
            prefs.setShowMovies(json.optBoolean("showMovies", true))
        }

        if (json.has("showSeries")) {
            prefs.setShowSeries(json.optBoolean("showSeries", true))
        }

        if (json.has("showWatching")) {
            prefs.setShowWatching(json.optBoolean("showWatching", true))
        }

        android.widget.Toast.makeText(this, "Restore complete", android.widget.Toast.LENGTH_SHORT).show()
    }
}
