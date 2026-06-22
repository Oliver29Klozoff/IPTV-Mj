package com.iptvapp.ui.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ActivitySettingsBinding
import com.iptvapp.worker.EpgRefreshWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.provider.Settings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var workManager: WorkManager
    private var currentEpgWorkId: UUID? = null

    @Inject
    lateinit var prefs: PreferencesManager

    @Inject
    lateinit var db: IptvDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnWhatsNew.setOnClickListener { showChangelog() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }

        workManager = WorkManager.getInstance(this)

        binding.btnBack.setOnClickListener { finish() }

        loadSettings()
        observeEpgRefreshWork()
        setupDebug()

        binding.btnSaveEpg.setOnClickListener {
            lifecycleScope.launch {
                prefs.setEpgUrl(binding.etEpgUrl.text.toString().trim())
                Toast.makeText(this@SettingsActivity, "EPG URL saved", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRefreshEpg.setOnClickListener {
            startEpgRefresh()
        }

        binding.btnCancelEpgRefresh.setOnClickListener {
            workManager.cancelUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME)
            binding.tvEpgRefreshStatus.text = "EPG refresh canceled."
            binding.btnRefreshEpg.isEnabled = true
            binding.btnCancelEpgRefresh.visibility = View.GONE
        }

        binding.cbRefreshMissingOnly.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                prefs.setEpgRefreshMissingOnly(isChecked)
            }
        }

        binding.cbUsaOnlyChannels.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                prefs.setUsaOnlyChannels(isChecked)
            }
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

    private fun checkForUpdate() {
        binding.tvUpdateStatus.text = "Checking..."
        binding.btnCheckUpdate.isEnabled = false
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    URL("https://raw.githubusercontent.com/Oliver29Klozoff/IPTV-Mj/main/version.json")
                        .readText()
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
                        .setPositiveButton("Download") { _, _ ->
                            downloadAndInstall(apkUrl, latestName)
                        }
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
            downloadFromUrl(resolvedUrl, versionName)
        }
    }

    private fun downloadFromUrl(apkUrl: String, versionName: String) {
        binding.tvUpdateStatus.text = "Downloading update..."
        val fileName = "IPTV-update-$versionName.apk"
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("IPTV Update v$versionName")
            .setDescription("Downloading update...")
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setAllowedOverRoaming(true)
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
                        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            FileProvider.getUriForFile(this@SettingsActivity, "${packageName}.provider", file)
                        } else { Uri.fromFile(file) }
                        val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
                        if (!canInstall) {
                            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply { data = Uri.parse("package:$packageName") })
                            binding.tvUpdateStatus.text = "Allow installs, then tap Check for Updates again."
                        } else {
                            val install = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try { startActivity(install) } catch (e: Exception) {
                                binding.tvUpdateStatus.text = "Error: ${e.message}"
                            }
                        }
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
                    binding.progressEpgRefresh.progress = 100
                    binding.tvUpdateStatus.text = "Download complete. Installing..."
                    Toast.makeText(this@SettingsActivity, "Receiver fired! canInstall=${packageManager.canRequestPackageInstalls()}", Toast.LENGTH_LONG).show()
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        FileProvider.getUriForFile(
                            this@SettingsActivity,
                            "${packageName}.provider",
                            file
                        )
                    } else {
                        Uri.fromFile(file)
                    }
                    val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
                        if (!canInstall) {
                            val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(permIntent)
                            binding.tvUpdateStatus.text = "Allow installs from this source, then tap Check for Updates again."
                        } else {
                            val install = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                startActivity(install)
                                binding.tvUpdateStatus.text = "Install prompt opened."
                            } catch (e: Exception) {
                                binding.tvUpdateStatus.text = "Error: ${e.message}"
                                Toast.makeText(this@SettingsActivity, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
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

    private fun showChangelog() {
        val text = try {
            assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Changelog not available."
        }
        AlertDialog.Builder(this)
            .setTitle("What's New")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .show()
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
            val vName = pInfo.versionName
            val vCode = pInfo.longVersionCode
            binding.tvVersion.text = "v$vName.$vCode"
        }
    }

    private fun startEpgRefresh() {
        lifecycleScope.launch {
            val missingOnly = prefs.epgRefreshMissingOnly.first()

            val request = OneTimeWorkRequestBuilder<EpgRefreshWorker>()
                .setInputData(
                    workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to missingOnly)
                )
                .build()

            currentEpgWorkId = request.id

            binding.progressEpgRefresh.visibility = View.VISIBLE
            binding.progressEpgRefresh.progress = 0
            binding.tvEpgRefreshStatus.text = "EPG refresh queued..."
            binding.btnRefreshEpg.isEnabled = false
            binding.btnCancelEpgRefresh.visibility = View.VISIBLE

            workManager.enqueueUniqueWork(
                EpgRefreshWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )

            observeCurrentEpgWork(request.id)
        }
    }

    private fun observeCurrentEpgWork(workId: UUID) {
        android.util.Log.d("EPG_DEBUG", "Observing work ID: $workId")
        workManager.getWorkInfoByIdLiveData(workId).observe(this) { info ->
            if (info == null) { android.util.Log.d("EPG_DEBUG", "WorkInfo is null"); return@observe }
            android.util.Log.d("EPG_DEBUG", "State=${info.state} progress=${info.progress.getInt(EpgRefreshWorker.KEY_PROGRESS,0)} tags=${info.tags}")

            val progress = info.progress.getInt(EpgRefreshWorker.KEY_PROGRESS, 0)
            val status = info.progress.getString(EpgRefreshWorker.KEY_STATUS)
                ?: info.outputData.getString(EpgRefreshWorker.KEY_STATUS)
                ?: "EPG refresh state: ${info.state}"

            binding.progressEpgRefresh.visibility = View.VISIBLE
            binding.progressEpgRefresh.progress = progress
            binding.tvEpgRefreshStatus.text = status

            val running =
                info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.ENQUEUED

            binding.btnRefreshEpg.isEnabled = !running
            binding.btnCancelEpgRefresh.visibility =
                if (running) View.VISIBLE else View.GONE

            if (info.state.isFinished) {
                lifecycleScope.launch {
                    updateLastRefreshText()
            updateCacheAgeText()
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val vName = pInfo.versionName
            val vCode = pInfo.longVersionCode
            binding.tvVersion.text = "v$vName.$vCode"
                }
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

                if (status.isNotBlank()) {
                    binding.tvEpgRefreshStatus.text = status
                }

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
            .setInputData(
                workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to true)
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            AUTO_EPG_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private suspend fun updateLastRefreshText() {
        val time = prefs.lastEpgRefreshTime.first()

        binding.tvLastEpgRefresh.text = if (time == 0L) {
            "Last EPG Refresh: Never"
        } else {
            val formatted = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
                .format(Date(time))
            "Last EPG Refresh: $formatted"
        }
    }

    private suspend fun updateCacheAgeText() {
        val oldest = db.epgDao().getOldestEpgStartTimestamp()
        val newest = db.epgDao().getNewestEpgStopTimestamp()
        val nowSeconds = System.currentTimeMillis() / 1000

        binding.tvEpgCacheAge.text = when {
            oldest == null || newest == null -> "EPG Cache Age: Unknown"
            newest < nowSeconds -> "EPG Cache Age: Expired"
            else -> {
                val hoursAhead = (newest - nowSeconds) / 3600
                "EPG Cache: covers about $hoursAhead hours ahead"
            }
        }
    }

    private fun setupDebug() {
        refreshDebugInfo()
        binding.btnRefreshDebug.setOnClickListener { refreshDebugInfo() }
        binding.btnCopyDebug.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("debug", binding.tvDebugInfo.text))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshDebugInfo() {
        lifecycleScope.launch {
            val sb = StringBuilder()
            try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                sb.appendLine("=== APP ===")
                sb.appendLine("Version: ${pInfo.versionName} (${pInfo.longVersionCode})")
                sb.appendLine("Package: $packageName")
                sb.appendLine("")
                sb.appendLine("=== DEVICE ===")
                sb.appendLine("Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                sb.appendLine("")
                sb.appendLine("=== NETWORK ===")
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(network)
                val netType = when {
                    caps == null -> "No network"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "Unknown"
                }
                sb.appendLine("Connection: $netType")
                sb.appendLine("")
                sb.appendLine("=== DATABASE ===")
                val channelCount = db.channelDao().getCount()
                val vodCount = db.vodDao().getCount()
                val epgCount = db.epgDao().getEpgCount()
                val favCount = db.channelDao().getFavoriteCount()
                sb.appendLine("Channels: $channelCount")
                sb.appendLine("Favorites: $favCount")
                sb.appendLine("VOD: $vodCount")
                sb.appendLine("EPG entries: $epgCount")
                sb.appendLine("")
                sb.appendLine("=== PREFERENCES ===")
                val format = prefs.preferredFormat.first()
                val usaOnly = prefs.usaOnlyChannels.first()
                val lastRefresh = prefs.lastEpgRefreshTime.first()
                val autoHours = prefs.epgAutoRefreshHours.first()
                sb.appendLine("Stream format: $format")
                sb.appendLine("USA only: $usaOnly")
                sb.appendLine("Auto EPG refresh: ${if (autoHours == 0) "off" else "every ${autoHours}h"}")
                val lastRefreshStr = if (lastRefresh == 0L) "Never" else
                    java.text.SimpleDateFormat("MMM d yyyy h:mm a", java.util.Locale.getDefault()).format(java.util.Date(lastRefresh))
                sb.appendLine("Last EPG refresh: $lastRefreshStr")
            } catch (e: Exception) {
                sb.appendLine("Error collecting debug info: ${e.message}")
            }
            binding.tvDebugInfo.text = sb.toString()
        }
    }

    companion object {
        private const val AUTO_EPG_WORK_NAME = "auto_epg_refresh_work"
    }
}
