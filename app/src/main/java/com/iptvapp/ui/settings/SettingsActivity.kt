package com.iptvapp.ui.settings

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
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var workManager: WorkManager
    private var currentEpgWorkId: UUID? = null

    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var db: IptvDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        workManager = WorkManager.getInstance(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnWhatsNew.setOnClickListener { showChangelog() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }

        loadSettings()
        observeEpgRefreshWork()
        setupBackupRestore()
        setupSectionToggles()

        binding.btnSaveEpg.setOnClickListener {
            lifecycleScope.launch {
                prefs.setEpgUrl(binding.etEpgUrl.text.toString().trim())
                Toast.makeText(this@SettingsActivity, "EPG URL saved", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRefreshEpg.setOnClickListener { startEpgRefresh() }

        binding.btnCancelEpgRefresh.setOnClickListener {
            workManager.cancelUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME)
            binding.tvEpgRefreshStatus.text = "EPG refresh canceled."
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
            arrow.text = "▼"
        } else {
            section.visibility = View.VISIBLE
            arrow.text = "▲"
        }
    }

    private fun setupSectionToggles() {
        binding.headerStream.setOnClickListener { toggleSection(binding.sectionStream, binding.arrowStream) }
        binding.headerDisplay.setOnClickListener { toggleSection(binding.sectionDisplay, binding.arrowDisplay) }
        binding.headerEpg.setOnClickListener { toggleSection(binding.sectionEpg, binding.arrowEpg) }
        binding.headerUpdates.setOnClickListener { toggleSection(binding.sectionUpdates, binding.arrowUpdates) }
        binding.headerBackup.setOnClickListener { toggleSection(binding.sectionBackup, binding.arrowBackup) }
    }

    private fun setupBackupRestore() {
        binding.btnBackupSettings.setOnClickListener { backupSettings() }
        binding.btnRestoreSettings.setOnClickListener { restoreSettings() }
        binding.btnSendDebugReport.setOnClickListener { sendDebugReport() }
    }

    private fun backupSettings() {
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
                binding.tvBackupStatus.text = "Backup failed: ${e.message}"
            }
        }
    }

    private fun restoreSettings() {
        lifecycleScope.launch {
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val file = File(dir, "mktv_settings_backup.txt")
                if (!file.exists()) {
                    binding.tvBackupStatus.text = "No backup found in Documents/"
                    return@launch
                }
                val json = JSONObject(file.readText())
                prefs.setEpgUrl(json.optString("epgUrl", ""))
                prefs.setPreferredFormat(json.optString("preferredFormat", "m3u8"))
                prefs.setUsaOnlyChannels(json.optBoolean("usaOnlyChannels", true))
                prefs.setShowMovies(json.optBoolean("showMovies", true))
                prefs.setShowSeries(json.optBoolean("showSeries", true))
                prefs.setEpgRefreshMissingOnly(json.optBoolean("epgRefreshMissingOnly", false))
                prefs.setEpgAutoRefreshHours(json.optInt("epgAutoRefreshHours", 0))
                val serverUrl = json.optString("serverUrl", "")
                val username = json.optString("username", "")
                val password = json.optString("password", "")
                if (serverUrl.isNotEmpty()) prefs.saveCredentials(serverUrl, username, password)
                val favCatArray = json.optJSONArray("favoriteCategoryIds")
                if (favCatArray != null) {
                    val ids = (0 until favCatArray.length()).map { favCatArray.getString(it) }.toSet()
                    prefs.setFavoriteLiveCategoryIds(ids)
                }
                val favChanArray = json.optJSONArray("favoriteChannelIds")
                if (favChanArray != null) {
                    val ids = (0 until favChanArray.length()).map { favChanArray.getInt(it) }
                    db.channelDao().clearAllFavorites()
                    ids.forEach { db.channelDao().setFavorite(it, true) }
                }
                binding.tvBackupStatus.text = "Settings restored successfully"
                loadSettings()
            } catch (e: Exception) {
                binding.tvBackupStatus.text = "Restore failed: ${e.message}"
            }
        }
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
                    val backupFile = File(dir, "MKTV_${timestamp}.json")
                    backupFile.writeText(prettyJson)
                    binding.tvBackupStatus.text = "Saved to Documents/MKTV_${timestamp}.json"
                } catch (e: Exception) {
                    binding.tvBackupStatus.text = "Save failed: ${e.message}"
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun sendDebugReport() {
        binding.tvReportStatus.text = "Collecting info..."
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
                val channelCount = db.channelDao().getCount()
                val favCount = db.channelDao().getFavoriteCount()
                val vodCount = db.vodDao().getCount()
                val epgCount = db.epgDao().getEpgCount()
                val format = prefs.preferredFormat.first()
                val usaOnly = prefs.usaOnlyChannels.first()
                val crashLog = IptvApplication.getCrashLog(this@SettingsActivity)
                val debugText = """
                    App: v${pInfo.versionName} (${pInfo.longVersionCode})
                    Device: ${Build.MANUFACTURER} ${Build.MODEL}
                    Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                    Network: $netType
                    Channels: $channelCount | Favorites: $favCount
                    VOD: $vodCount | EPG: $epgCount
                    Format: $format | USA Only: $usaOnly
                """.trimIndent()
                val fullDebug = debugText + "\n\n=== CRASH LOG ===\n" + crashLog
                val title = "Debug Report - v${pInfo.versionName}.${pInfo.longVersionCode} - ${Build.MODEL}"
                val body = "## Device Debug Report\n\n```\n$fullDebug\n```"
                val json = JSONObject().apply {
                    put("title", title)
                    put("body", body)
                    put("labels", JSONArray().put("debug-report"))
                }
                binding.tvReportStatus.text = "Sending..."
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
                    binding.tvReportStatus.text = "Report sent! Issue #$issueNumber"
                } else {
                    binding.tvReportStatus.text = "Failed: ${response.code}"
                }
            } catch (e: Exception) {
                binding.tvReportStatus.text = "Error: ${e.message}"
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
                    binding.tvUpdateStatus.text = "Update available: v$latestName"
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Update Available")
                        .setMessage("Version $latestName is available. Download and install now?")
                        .setPositiveButton("Download") { _, _ -> downloadAndInstall(apkUrl, latestName) }
                        .setNegativeButton("Later", null)
                        .show()
                } else {
                    binding.tvUpdateStatus.text = "You are up to date (v$latestName)"
                }
            } catch (e: Exception) {
                binding.tvUpdateStatus.text = "Check failed: ${e.message}"
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
        binding.tvUpdateStatus.text = "Downloading update..."
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
                        binding.tvUpdateStatus.text = "Downloading... $pct%"
                    }
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        progressHandler.removeCallbacks(this)
                        binding.progressEpgRefresh.progress = 100
                        binding.tvUpdateStatus.text = "Download complete. Installing..."
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
            binding.tvUpdateStatus.text = "Allow installs from this source, then try again."
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
            binding.tvUpdateStatus.text = "Install error: ${e.message}"
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
            when (prefs.epgAutoRefreshHours.first()) {
                6 -> binding.rbAuto6.isChecked = true
                12 -> binding.rbAuto12.isChecked = true
                24 -> binding.rbAuto24.isChecked = true
                else -> binding.rbAutoOff.isChecked = true
            }
            updateLastRefreshText()
            updateCacheAgeText()
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersion.text = "v${pInfo.versionName}.${pInfo.longVersionCode}"
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
            binding.tvEpgRefreshStatus.text = "EPG refresh queued..."
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
        binding.tvLastEpgRefresh.text = if (time == 0L) "Last EPG Refresh: Never"
        else "Last EPG Refresh: ${SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(time))}"
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

    companion object {
        private const val AUTO_EPG_WORK_NAME = "auto_epg_refresh_work"
    }
}
