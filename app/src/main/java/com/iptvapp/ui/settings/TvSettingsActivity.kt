package com.iptvapp.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ActivityTvSettingsBinding
import com.iptvapp.IptvApplication
import com.iptvapp.sync.SyncManager
import com.iptvapp.ui.login.LoginActivity
import com.iptvapp.update.UpdateChecker
import com.iptvapp.util.enableTvFocusHighlight
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

    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var db: IptvDatabase
    @Inject lateinit var syncManager: SyncManager

    private val menuButtons: List<Button> get() = listOf(
        binding.btnTvStream, binding.btnTvDisplay, binding.btnTvEpg,
        binding.btnTvUpdate, binding.btnTvBackup, binding.btnTvServers, binding.btnTvAccount, binding.btnTvSync
    )
    private val panels: List<View> get() = listOf(
        binding.panelTvStream, binding.panelTvDisplay, binding.panelTvEpg,
        binding.panelTvUpdate, binding.panelTvBackup, binding.panelTvServers, binding.panelTvAccount, binding.panelTvSync
    )
    private val firstFocusable: List<View?> get() = listOf(
        binding.switchTvFormat, binding.switchTvUsaOnly, binding.btnTvAddEpg,
        binding.btnTvCheckUpdate, binding.btnTvRunBackup, binding.btnTvAddServer, binding.btnTvLogout, binding.switchTvSyncEnabled
    )

    private var activePanelIndex = 0
    private val extraServers = mutableListOf<List<String>>()
    private var currentEpgWorkId: UUID? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        workManager = WorkManager.getInstance(this)

        binding.root.enableTvFocusHighlight()

        menuButtons.forEachIndexed { i, btn -> btn.setOnClickListener { selectPanel(i) } }

        setupStreamPanel()
        setupDisplayPanel()
        setupEpgPanel()
        setupUpdatePanel()
        setupBackupPanel()
        setupServersPanel()
        setupAccountPanel()
        setupSyncPanel()
        observeEpgWork()

        selectPanel(0)
        binding.btnTvStream.requestFocus()
    }

    // ─── Panel navigation ───────────────────────────────────────────────────

    private fun selectPanel(index: Int) {
        activePanelIndex = index
        panels.forEachIndexed { i, panel ->
            panel.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        menuButtons.forEachIndexed { i, btn ->
            if (i == index) {
                btn.setBackgroundColor(Color.TRANSPARENT)
                btn.setTextColor(Color.WHITE)
                btn.textSize = 20f
            } else {
                btn.setBackgroundColor(Color.TRANSPARENT)
                btn.setTextColor(Color.parseColor("#999999"))
                btn.textSize = 18f
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (focused != null && menuButtons.contains(focused)) {
                        firstFocusable[activePanelIndex]?.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (focused != null && !menuButtons.contains(focused)) {
                        menuButtons[activePanelIndex].requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_BACK -> { finish(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ─── Stream ─────────────────────────────────────────────────────────────

    private fun setupStreamPanel() {
        lifecycleScope.launch {
            val creds = prefs.credentials.first()
            val format = prefs.preferredFormat.first()
            binding.tvTvServerSummary.text =
                "Server:  ${creds.serverUrl.ifBlank { "Not set" }}\nUser:      ${creds.username.ifBlank { "Not set" }}"
            binding.switchTvFormat.isChecked = (format == "ts")
        }
        binding.switchTvFormat.setOnCheckedChangeListener { _, isTs ->
            val fmt = if (isTs) "ts" else "m3u8"
            lifecycleScope.launch {
                prefs.setPreferredFormat(fmt)
                toast("Format set to ${fmt.uppercase()}")
            }
        }
    }

    // ─── Display ─────────────────────────────────────────────────────────────

    private fun setupDisplayPanel() {
        lifecycleScope.launch {
            binding.switchTvUsaOnly.isChecked = prefs.usaOnlyChannels.first()
            binding.switchTvMovies.isChecked = prefs.showMovies.first()
            binding.switchTvSeries.isChecked = prefs.showSeries.first()
        }
        binding.switchTvUsaOnly.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setUsaOnlyChannels(c) }
        }
        binding.switchTvMovies.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setShowMovies(c) }
        }
        binding.switchTvSeries.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setShowSeries(c) }
        }
    }

    // ─── EPG ─────────────────────────────────────────────────────────────────

    private val epgUrls = mutableListOf<String>()

    private fun setupEpgPanel() {
        lifecycleScope.launch {
            epgUrls.clear()
            epgUrls.addAll(prefs.getEpgUrls())
            renderEpgUrlList()
            binding.switchTvRefreshMissing.isChecked = prefs.epgRefreshMissingOnly.first()
            updateAutoRefreshButtons(prefs.epgAutoRefreshHours.first())
            updateLastRefreshText()
        }
        binding.btnTvAddEpg.setOnClickListener { showAddEpgDialog() }
        binding.switchTvRefreshMissing.setOnCheckedChangeListener { _, c ->
            lifecycleScope.launch { prefs.setEpgRefreshMissingOnly(c) }
        }
        binding.btnTvRefreshEpg.setOnClickListener { startEpgRefresh() }
        binding.btnTvCancelEpg.setOnClickListener {
            workManager.cancelUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME)
            binding.btnTvRefreshEpg.isEnabled = true
            binding.btnTvCancelEpg.visibility = View.GONE
            binding.tvTvEpgStatus.text = ""
        }

        val autoHourMap = listOf(
            binding.btnTvAutoOff to 0,
            binding.btnTvAuto6 to 6,
            binding.btnTvAuto12 to 12,
            binding.btnTvAuto24 to 24
        )
        autoHourMap.forEach { (btn, hours) ->
            btn.setOnClickListener {
                lifecycleScope.launch {
                    prefs.setEpgAutoRefreshHours(hours)
                    scheduleAutoEpgRefresh(hours)
                    updateAutoRefreshButtons(hours)
                    val msg = if (hours == 0) "Auto EPG refresh off" else "Auto EPG refresh every $hours hours"
                    toast(msg)
                }
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
            binding.btnTvRefreshEpg.isEnabled = false
            binding.btnTvCancelEpg.visibility = View.VISIBLE
            binding.tvTvEpgStatus.text = "Starting refresh..."
            workManager.enqueueUniqueWork(
                EpgRefreshWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            observeCurrentEpgWork(request.id)
        }
    }

    private fun observeCurrentEpgWork(workId: UUID) {
        workManager.getWorkInfoByIdLiveData(workId).observe(this) { info ->
            if (info == null) return@observe
            val status = info.progress.getString(EpgRefreshWorker.KEY_STATUS)
                ?: info.outputData.getString(EpgRefreshWorker.KEY_STATUS)
                ?: ""
            if (status.isNotBlank()) binding.tvTvEpgStatus.text = status
            val running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
            binding.btnTvRefreshEpg.isEnabled = !running
            binding.btnTvCancelEpg.visibility = if (running) View.VISIBLE else View.GONE
            if (info.state.isFinished) {
                lifecycleScope.launch { updateLastRefreshText() }
                if (info.state == WorkInfo.State.SUCCEEDED)
                    binding.tvTvEpgStatus.text = "Refresh complete"
            }
        }
    }

    private fun observeEpgWork() {
        workManager.getWorkInfosForUniqueWorkLiveData(EpgRefreshWorker.UNIQUE_WORK_NAME)
            .observe(this) { infos ->
                val info = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: return@observe
                val status = info.progress.getString(EpgRefreshWorker.KEY_STATUS) ?: ""
                if (status.isNotBlank()) binding.tvTvEpgStatus.text = status
                binding.btnTvRefreshEpg.isEnabled = false
                binding.btnTvCancelEpg.visibility = View.VISIBLE
            }
    }

    private fun scheduleAutoEpgRefresh(hours: Int) {
        if (hours == 0) {
            workManager.cancelUniqueWork(AUTO_EPG_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setInputData(workDataOf(EpgRefreshWorker.KEY_MISSING_ONLY to true))
            .build()
        workManager.enqueueUniquePeriodicWork(AUTO_EPG_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private suspend fun updateLastRefreshText() {
        val time = prefs.lastEpgRefreshTime.first()
        binding.tvTvLastRefresh.text = if (time == 0L) {
            "Last EPG Refresh: Never"
        } else {
            val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(time))
            "Last EPG Refresh: $fmt"
        }
    }

    private fun renderEpgUrlList() {
        binding.llTvEpgUrls.removeAllViews()
        epgUrls.forEachIndexed { i, url ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#0E1620"))
                setPadding(dp(20), dp(16), dp(20), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(8) }
            }
            TextView(this).apply {
                text = if (i == 0) "PRIMARY SOURCE" else "SOURCE ${i + 1}"
                setTextColor(Color.parseColor("#7BBEE0"))
                textSize = 13f
                letterSpacing = 0.1f
                row.addView(this)
            }
            TextView(this).apply {
                text = url
                setTextColor(Color.WHITE)
                textSize = 17f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                row.addView(this)
            }
            Button(this).apply {
                text = if (i == 0) "Edit" else "Remove"
                setTextColor(if (i == 0) Color.WHITE else Color.parseColor("#FF6B6B"))
                setBackgroundResource(R.drawable.tv_settings_button)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)
                ).also { it.topMargin = dp(10) }
                setOnClickListener {
                    if (i == 0) showEditEpgDialog(i)
                    else {
                        epgUrls.removeAt(i)
                        lifecycleScope.launch {
                            prefs.saveEpgUrls(epgUrls)
                            renderEpgUrlList()
                        }
                    }
                }
                row.addView(this)
            }
            binding.llTvEpgUrls.addView(row)
        }
        if (epgUrls.isEmpty()) {
            TextView(this).apply {
                text = "No EPG sources configured"
                setTextColor(Color.parseColor("#7BBEE0"))
                textSize = 17f
                setPadding(0, dp(8), 0, dp(8))
                binding.llTvEpgUrls.addView(this)
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
                        renderEpgUrlList()
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
            .setTitle("Edit EPG Source")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val url = et.text.toString().trim()
                if (url.isNotEmpty()) {
                    epgUrls[index] = url
                    lifecycleScope.launch {
                        prefs.saveEpgUrls(epgUrls)
                        renderEpgUrlList()
                        toast("EPG source updated")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateAutoRefreshButtons(hours: Int) {
        listOf(
            binding.btnTvAutoOff to 0,
            binding.btnTvAuto6 to 6,
            binding.btnTvAuto12 to 12,
            binding.btnTvAuto24 to 24
        ).forEach { (btn, h) ->
            if (h == hours) {
                btn.setBackgroundColor(Color.TRANSPARENT)
                btn.setTextColor(Color.WHITE)
                btn.textSize = 18f
            } else {
                btn.setBackgroundColor(Color.parseColor("#1A1F2B"))
                btn.setTextColor(Color.parseColor("#999999"))
                btn.textSize = 16f
            }
        }
    }

    // ─── Updates ─────────────────────────────────────────────────────────────

    private fun setupUpdatePanel() {
        lifecycleScope.launch {
            try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                binding.tvTvVersionInfo.text = "Version ${pInfo.versionName}  (build ${pInfo.longVersionCode})"
            } catch (_: Exception) {}
        }
        binding.btnTvCheckUpdate.setOnClickListener {
            toast("Checking for updates...")
            UpdateChecker(this).check(lifecycleScope)
        }
        binding.btnTvWhatsNew.setOnClickListener {
            val text = try {
                assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
            } catch (_: Exception) { "Changelog not available." }
            AlertDialog.Builder(this)
                .setTitle("What's New")
                .setMessage(text)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    // ─── Backup ──────────────────────────────────────────────────────────────

    private fun setupBackupPanel() {
        binding.btnTvRunBackup.setOnClickListener {
            lifecycleScope.launch { doQrBackup() }
        }
        binding.btnTvSendDebug.setOnClickListener { sendDebugReport() }
    }

    private suspend fun doQrBackup() {
        try {
            val creds = prefs.credentials.first()
            val favCategoryIds = prefs.favoriteLiveCategoryIds.first()
            val favChannels = db.channelDao().getFavoriteChannelIds()
            val json = JSONObject().apply {
                put("serverUrl", creds.serverUrl)
                put("username", creds.username)
                put("password", creds.password)
                put("epgUrls", JSONArray(prefs.getEpgUrls()))
                put("preferredFormat", prefs.preferredFormat.first())
                put("usaOnlyChannels", prefs.usaOnlyChannels.first())
                put("showMovies", prefs.showMovies.first())
                put("showSeries", prefs.showSeries.first())
                put("epgRefreshMissingOnly", prefs.epgRefreshMissingOnly.first())
                put("epgAutoRefreshHours", prefs.epgAutoRefreshHours.first())
                put("favoriteCategoryIds", JSONArray(favCategoryIds.toList()))
                put("favoriteChannelIds", JSONArray(favChannels))
            }
            showQrDialog(json.toString())
        } catch (_: Exception) {
            toast("Backup failed")
        }
    }

    private fun showQrDialog(content: String) {
        val size = 600
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size)
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        try {
            val logo = BitmapFactory.decodeResource(resources, R.drawable.splash_logo)
            val ls = size / 4
            val scaled = Bitmap.createScaledBitmap(logo, ls, ls, true)
            val c = Canvas(bitmap)
            val paint = Paint().apply { color = Color.WHITE }
            val center = size / 2f
            c.drawCircle(center, center, ls / 2f + 14, paint)
            c.drawBitmap(scaled, center - ls / 2f, center - ls / 2f, null)
            scaled.recycle(); logo.recycle()
        } catch (_: Exception) {}
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap); setPadding(32, 32, 32, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Backup QR Code")
            .setMessage("Scan with your phone to restore settings")
            .setView(iv)
            .setPositiveButton("Done", null)
            .show()
    }

    // ─── Servers ─────────────────────────────────────────────────────────────

    private fun setupServersPanel() {
        binding.btnTvAddServer.setOnClickListener { showAddServerDialog() }
        loadServers()
    }

    private fun loadServers() {
        lifecycleScope.launch {
            extraServers.clear()
            extraServers.addAll(prefs.getExtraServersWithNick())
            renderServerList()
        }
    }

    private fun renderServerList() {
        binding.llTvServers.removeAllViews()
        lifecycleScope.launch {
            val creds = prefs.credentials.first()
            val activeIndex = prefs.activeServerIndex.first()
            val primaryNick = prefs.serverNickname.first().ifEmpty { creds.username }
            addServerCard(binding.llTvServers, "PRIMARY", primaryNick, creds.serverUrl, isActive = activeIndex == -1, onSwitch = null)
            extraServers.forEachIndexed { i, server ->
                val nick = server.getOrElse(3) { "" }.ifEmpty { server.getOrElse(1) { "Server ${i + 2}" } }
                addServerCard(binding.llTvServers, "SERVER ${i + 2}", nick, server.getOrElse(0) { "" },
                    isActive = activeIndex == i) {
                    lifecycleScope.launch {
                        val url = server[0]; val user = server[1]; val pass = server[2]
                        val primary = prefs.credentials.first()
                        val updated = extraServers.toMutableList()
                        updated[i] = listOf(primary.serverUrl, primary.username, primary.password, prefs.serverNickname.first())
                        prefs.saveExtraServersWithNick(updated)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.clearAllTables() }
                        prefs.saveCredentials(url, user, pass)
                        prefs.setActiveServerIndex(-1)
                        startActivity(
                            Intent(this@TvSettingsActivity, com.iptvapp.ui.home.HomeActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                    }
                }
            }
        }
    }

    private fun addServerCard(
        container: LinearLayout,
        label: String,
        name: String,
        url: String,
        isActive: Boolean,
        onSwitch: (() -> Unit)?
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1620"))
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10) }
        }
        TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#7BBEE0"))
            textSize = 14f
            letterSpacing = 0.1f
            card.addView(this)
        }
        TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 20f
            card.addView(this)
        }
        if (url.isNotEmpty()) {
            TextView(this).apply {
                text = url
                setTextColor(Color.parseColor("#C0D8EE"))
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                card.addView(this)
            }
        }
        TextView(this).apply {
            text = if (isActive) "● ACTIVE" else "INACTIVE"
            setTextColor(if (isActive) Color.parseColor("#008CFF") else Color.parseColor("#999999"))
            textSize = 16f
            setPadding(0, dp(4), 0, 0)
            card.addView(this)
        }
        if (onSwitch != null) {
            Button(this).apply {
                text = "Switch to this server"
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.tv_settings_button)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(58)
                ).also { it.topMargin = dp(12) }
                setOnClickListener { onSwitch() }
                card.addView(this)
            }
        }
        container.addView(card)
    }

    private fun showAddServerDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
        }
        val etNick = EditText(this).apply { hint = "Nickname (optional)" }
        val etUrl = EditText(this).apply { hint = "Server URL (http://...)" }
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
                val url = etUrl.text.toString().trim()
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
                        renderServerList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Account ─────────────────────────────────────────────────────────────

    private fun setupAccountPanel() {
        binding.btnTvLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("This will clear all data and return to the login screen. Continue?")
                .setPositiveButton("Logout") { _, _ ->
                    lifecycleScope.launch {
                        prefs.clearCredentials()
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
    }

    // ─── Debug ───────────────────────────────────────────────────────────────

    private fun sendDebugReport() {
        binding.btnTvSendDebug.isEnabled = false
        binding.tvTvDebugStatus.text = "Collecting info..."
        lifecycleScope.launch {
            try {
                val token = com.iptvapp.BuildConfig.GH_TOKEN
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                val netType = when {
                    caps == null -> "No network"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
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
                    workManager.getWorkInfosForUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME).get()
                        .firstOrNull()?.state?.name ?: "None"
                } catch (_: Exception) { "Unknown" }
                binding.tvTvDebugStatus.text = "Reading crash log..."
                val crashLog = IptvApplication.getCrashLog(this@TvSettingsActivity)
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
                binding.tvTvDebugStatus.text = "Sending..."
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://api.github.com/repos/Oliver29Klozoff/IPTV-Mj/issues")
                    .addHeader("Authorization", "token $token")
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                if (response.isSuccessful) {
                    binding.tvTvDebugStatus.text = "Report sent successfully"
                    toast("Debug report sent")
                } else {
                    binding.tvTvDebugStatus.text = "Failed: ${response.code}"
                }
            } catch (e: Exception) {
                binding.tvTvDebugStatus.text = "Error: ${e.message}"
            } finally {
                binding.btnTvSendDebug.isEnabled = true
            }
        }
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    private fun setupSyncPanel() {
        lifecycleScope.launch {
            binding.switchTvSyncEnabled.isChecked = prefs.syncEnabled.first()
            val summary = syncManager.getLastSyncSummary()
            binding.tvTvSyncStatus.text = summary
        }
        binding.switchTvSyncEnabled.setOnCheckedChangeListener { _, enabled ->
            lifecycleScope.launch { prefs.setSyncEnabled(enabled) }
        }
        binding.btnTvSyncUp.setOnClickListener {
            binding.tvTvSyncStatus.text = "Pushing to cloud..."
            binding.btnTvSyncUp.isEnabled = false
            binding.btnTvSyncDown.isEnabled = false
            lifecycleScope.launch {
                val result = syncManager.syncUp()
                binding.tvTvSyncStatus.text = result
                binding.btnTvSyncUp.isEnabled = true
                binding.btnTvSyncDown.isEnabled = true
                toast(result)
            }
        }
        binding.btnTvSyncDown.setOnClickListener {
            binding.tvTvSyncStatus.text = "Pulling from cloud..."
            binding.btnTvSyncUp.isEnabled = false
            binding.btnTvSyncDown.isEnabled = false
            lifecycleScope.launch {
                val result = syncManager.syncDown()
                binding.tvTvSyncStatus.text = result
                binding.btnTvSyncUp.isEnabled = true
                binding.btnTvSyncDown.isEnabled = true
                toast(result)
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val AUTO_EPG_WORK = "auto_epg_refresh_work"
    }
}
