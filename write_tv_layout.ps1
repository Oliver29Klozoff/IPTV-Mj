Set-Location "D:\Android studio apps\IPTVNative"

# This script adds a separate Android TV Settings screen.
# It does not modify phone SettingsActivity.kt.

# -----------------------------
# 1. TV focus button background
# -----------------------------
New-Item -ItemType Directory -Force "app\src\main\res\drawable" | Out-Null

$buttonBg = @"
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_focused="true">
        <shape>
            <solid android:color="#008CFF" />
            <stroke android:width="3dp" android:color="#7CCBFF" />
            <corners android:radius="8dp" />
        </shape>
    </item>
    <item android:state_selected="true">
        <shape>
            <solid android:color="#005BFF" />
            <stroke android:width="2dp" android:color="#008CFF" />
            <corners android:radius="8dp" />
        </shape>
    </item>
    <item>
        <shape>
            <solid android:color="#1A1F2B" />
            <stroke android:width="1dp" android:color="#30384A" />
            <corners android:radius="8dp" />
        </shape>
    </item>
</selector>
"@

[System.IO.File]::WriteAllText(
    "app\src\main\res\drawable\tv_settings_button.xml",
    $buttonBg,
    [System.Text.UTF8Encoding]::new($false)
)

# -----------------------------
# 2. Separate TV Settings Activity
# -----------------------------
New-Item -ItemType Directory -Force "app\src\main\java\com\iptvapp\ui\settings" | Out-Null

$activity = @"
package com.iptvapp.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.R
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.ui.login.LoginActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@Suppress("DEPRECATION")
@AndroidEntryPoint
class TvSettingsActivity : AppCompatActivity() {
    @Inject lateinit var prefs: PreferencesManager

    private lateinit var content: LinearLayout

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) lifecycleScope.launch { writeBackup(uri) }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) lifecycleScope.launch { restoreBackup(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        showStream()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#05080E"))
            setPadding(32, 32, 32, 32)
        }

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 24, 0)
        }

        root.addView(menu, LinearLayout.LayoutParams(320, LinearLayout.LayoutParams.MATCH_PARENT))

        val title = TextView(this).apply {
            text = "Settings"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 32f
            setPadding(0, 0, 0, 22)
        }
        menu.addView(title)

        addMenuButton(menu, "Stream") { showStream() }
        addMenuButton(menu, "Display") { showDisplay() }
        addMenuButton(menu, "EPG") { showEpg() }
        addMenuButton(menu, "Backup") { showBackup() }
        addMenuButton(menu, "Servers") { showServers() }
        addMenuButton(menu, "Account") { showAccount() }

        val panelFrame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0D111A"))
            setPadding(32, 32, 32, 32)
        }

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        scroll.addView(content)
        panelFrame.addView(scroll)

        root.addView(panelFrame, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)
    }

    private fun addMenuButton(menu: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.tv_settings_button)
            isFocusable = true
            setOnClickListener { action() }
            setOnFocusChangeListener { view, hasFocus ->
                view.animate()
                    .scaleX(if (hasFocus) 1.05f else 1.0f)
                    .scaleY(if (hasFocus) 1.05f else 1.0f)
                    .setDuration(120)
                    .start()
            }
        }

        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 72)
        params.setMargins(0, 0, 0, 12)
        menu.addView(button, params)
    }

    private fun clearPanel(title: String) {
        content.removeAllViews()

        val heading = TextView(this).apply {
            text = title
            textSize = 30f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, 24)
        }

        content.addView(heading)
    }

    private fun addInfo(textValue: String) {
        val view = TextView(this).apply {
            text = textValue
            textSize = 20f
            setTextColor(android.graphics.Color.parseColor("#B8C5D6"))
            setPadding(0, 0, 0, 18)
        }
        content.addView(view)
    }

    private fun addAction(label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.tv_settings_button)
            isFocusable = true
            setOnClickListener { action() }
        }

        val params = LinearLayout.LayoutParams(380, 72)
        params.setMargins(0, 0, 0, 14)
        content.addView(button, params)
    }

    private fun showStream() {
        clearPanel("Stream")

        lifecycleScope.launch {
            val creds = prefs.credentials.first()
            addInfo("Server: " + creds.serverUrl.ifBlank { "Not set" })
            addInfo("User: " + creds.username.ifBlank { "Not set" })
            addInfo("Current format: " + prefs.preferredFormat.first())

            addAction("Use M3U8") {
                lifecycleScope.launch {
                    prefs.setPreferredFormat("m3u8")
                    toast("Format set to M3U8")
                    showStream()
                }
            }

            addAction("Use TS") {
                lifecycleScope.launch {
                    prefs.setPreferredFormat("ts")
                    toast("Format set to TS")
                    showStream()
                }
            }
        }
    }

    private fun showDisplay() {
        clearPanel("Display")

        lifecycleScope.launch {
            addSwitch("USA only channels", prefs.usaOnlyChannels.first()) { prefs.setUsaOnlyChannels(it) }
            addSwitch("Show movies", prefs.showMovies.first()) { prefs.setShowMovies(it) }
            addSwitch("Show series", prefs.showSeries.first()) { prefs.setShowSeries(it) }
        }
    }

    private fun addSwitch(label: String, checked: Boolean, save: suspend (Boolean) -> Unit) {
        val view = Switch(this).apply {
            text = label
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            isChecked = checked
            isFocusable = true
            setPadding(0, 0, 0, 12)
            setOnCheckedChangeListener { _, value ->
                lifecycleScope.launch { save(value) }
            }
        }

        content.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 72))
    }

    private fun showEpg() {
        clearPanel("EPG")

        lifecycleScope.launch {
            val epgInput = EditText(this@TvSettingsActivity).apply {
                hint = "EPG URL"
                textSize = 20f
                setSingleLine(true)
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(android.graphics.Color.parseColor("#7C8799"))
                setText(prefs.epgUrl.first())
            }

            content.addView(epgInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 72))

            addAction("Save EPG URL") {
                lifecycleScope.launch {
                    prefs.setEpgUrl(epgInput.text.toString().trim())
                    toast("EPG URL saved")
                }
            }

            addInfo("Auto refresh hours: " + prefs.epgAutoRefreshHours.first().toString())
            addInfo("Missing-only refresh: " + if (prefs.epgRefreshMissingOnly.first()) "On" else "Off")
        }
    }

    private fun showBackup() {
        clearPanel("Backup and Restore")

        addAction("Backup") {
            backupLauncher.launch("iptv_backup.json")
        }

        addAction("Restore") {
            restoreLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
    }

    private fun showServers() {
        clearPanel("Servers")

        lifecycleScope.launch {
            val servers = prefs.getExtraServers()

            if (servers.isEmpty()) {
                addInfo("No extra servers saved.")
            } else {
                servers.forEachIndexed { index, server ->
                    addInfo((index + 1).toString() + ". " + server.first + "\n   " + server.second)
                }
            }

            addAction("Add Server") { showAddServerDialog() }
        }
    }

    private fun showAccount() {
        clearPanel("Account")

        addAction("Logout") {
            lifecycleScope.launch {
                prefs.clearCredentials()
                startActivity(
                    Intent(this@TvSettingsActivity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
        }
    }

    private fun showAddServerDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val etUrl = EditText(this).apply { hint = "Server URL" }
        val etUser = EditText(this).apply { hint = "Username" }
        val etPass = EditText(this).apply { hint = "Password" }

        layout.addView(etUrl)
        layout.addView(etUser)
        layout.addView(etPass)

        AlertDialog.Builder(this)
            .setTitle("Add Server")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                lifecycleScope.launch {
                    val current = prefs.getExtraServers().toMutableList()
                    current.add(
                        Triple(
                            etUrl.text.toString().trim(),
                            etUser.text.toString().trim(),
                            etPass.text.toString().trim()
                        )
                    )
                    prefs.saveExtraServers(current)
                    toast("Server added")
                    showServers()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun writeBackup(uri: Uri) {
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
        }

        contentResolver.openOutputStream(uri)?.use { output ->
            output.write(json.toString(2).toByteArray())
        }

        toast("Backup saved")
    }

    private suspend fun restoreBackup(uri: Uri) {
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

        json.optString("epgUrl", "").takeIf { it.isNotEmpty() }?.let { prefs.setEpgUrl(it) }
        json.optString("preferredFormat", "").takeIf { it.isNotEmpty() }?.let { prefs.setPreferredFormat(it) }

        if (json.has("epgAutoRefreshHours")) prefs.setEpgAutoRefreshHours(json.optInt("epgAutoRefreshHours", 0))
        if (json.has("epgRefreshMissingOnly")) prefs.setEpgRefreshMissingOnly(json.optBoolean("epgRefreshMissingOnly", false))
        if (json.has("usaOnlyChannels")) prefs.setUsaOnlyChannels(json.optBoolean("usaOnlyChannels", true))
        if (json.has("showMovies")) prefs.setShowMovies(json.optBoolean("showMovies", true))
        if (json.has("showSeries")) prefs.setShowSeries(json.optBoolean("showSeries", true))

        toast("Restore complete")
        showStream()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
"@

[System.IO.File]::WriteAllText(
    "app\src\main\java\com\iptvapp\ui\settings\TvSettingsActivity.kt",
    $activity,
    [System.Text.UTF8Encoding]::new($false)
)

# -----------------------------
# 3. Register TV activity
# -----------------------------
$manifestPath = "app\src\main\AndroidManifest.xml"
$manifest = Get-Content -Raw $manifestPath

if ($manifest -notmatch '\.ui\.settings\.TvSettingsActivity') {
    $entry = @"

        <activity
            android:name=".ui.settings.TvSettingsActivity"
            android:exported="false"
            android:screenOrientation="landscape" />

"@

    $appClose = $manifest.LastIndexOf("</application>")
    if ($appClose -lt 0) { throw "Could not find </application> in AndroidManifest.xml" }

    $manifest = $manifest.Insert($appClose, $entry)

    [System.IO.File]::WriteAllText(
        $manifestPath,
        $manifest,
        [System.Text.UTF8Encoding]::new($false)
    )
}

# -----------------------------
# 4. Route Android TV devices to TV Settings
# -----------------------------
$homePath = "app\src\main\java\com\iptvapp\ui\home\HomeActivity.kt"
$home = Get-Content -Raw $homePath

if ($home -notmatch 'import com\.iptvapp\.ui\.settings\.TvSettingsActivity') {
    $home = $home -replace 'import com\.iptvapp\.ui\.settings\.SettingsActivity', "import com.iptvapp.ui.settings.SettingsActivity`r`nimport com.iptvapp.ui.settings.TvSettingsActivity"
}

if ($home -match 'startActivity\(Intent\(this, SettingsActivity::class\.java\)\)') {
    $route = @"
val settingsClass = if (
                packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) ||
                packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION)
            ) {
                TvSettingsActivity::class.java
            } else {
                SettingsActivity::class.java
            }
            startActivity(Intent(this, settingsClass))
"@

    $home = $home -replace 'startActivity\(Intent\(this, SettingsActivity::class\.java\)\)', $route

    [System.IO.File]::WriteAllText(
        $homePath,
        $home,
        [System.Text.UTF8Encoding]::new($false)
    )
}

Write-Host "TV Settings files created. Phone SettingsActivity.kt was not changed."