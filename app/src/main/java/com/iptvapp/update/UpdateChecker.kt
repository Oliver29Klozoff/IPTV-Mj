package com.iptvapp.update

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateChecker(
    private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val versionJsonUrl =
        "https://raw.githubusercontent.com/Oliver29Klozoff/IPTV-Mj/main/version.json"

    fun check(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            cleanupStaleApks()
            try {
                val request = Request.Builder()
                    .url(versionJsonUrl)
                    .header("Cache-Control", "no-cache")
                    .build()
                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@launch
                    response.body?.string() ?: return@launch
                }
                val json = JSONObject(body)

                val latestCode = json.getLong("versionCode")
                val latestName = json.optString("versionName", "")
                val apkUrl = json.getString("apkUrl")
                val apkSha256 = json.optString("apkSha256", "").takeIf { it.isNotBlank() }
                val notes = buildChangelog(json)

                val installedCode = getInstalledVersionCode()
                val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                val lastSeenCode = prefs.getLong("last_seen_version_code", 0L)

                when {
                    latestCode > installedCode -> withContext(Dispatchers.Main) {
                        showUpdateDialog(latestName, notes, apkUrl, apkSha256)
                    }
                    latestCode == installedCode && installedCode > lastSeenCode -> {
                        prefs.edit().putLong("last_seen_version_code", installedCode).apply()
                        withContext(Dispatchers.Main) {
                            showWhatsNewDialog(latestName, notes)
                        }
                    }
                }
            } catch (_: Exception) {
                // Silent fail on launch check.
            }
        }
    }

    /** Update APKs are transient by design — download, install, gone. The intent-based install
     * path can't delete its APK immediately (the system installer reads the file after we hand
     * it off), so every launch sweeps whatever's left instead: the current cache-dir file plus
     * the per-version MKTV-update-*.apk files the old DownloadManager-based Settings flow used
     * to leave accumulating forever in the app's external Downloads dir. */
    private fun cleanupStaleApks() {
        try {
            // Age-gated so a launch-time sweep can't yank the file out from under a download
            // that resumeCheck() kicked off seconds earlier in the same session.
            val cacheApk = File(context.externalCacheDir ?: context.cacheDir, "IPTV-update.apk")
            if (cacheApk.exists() && System.currentTimeMillis() - cacheApk.lastModified() > 60 * 60 * 1000L) {
                cacheApk.delete()
            }
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?.listFiles { f -> f.name.endsWith(".apk") }
                ?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    // version.json's "changelog" has always been published as a single string, never a JSON
    // array — optJSONArray() silently returns null for a string field, and there's no
    // "notes" field either, so this fell through to an empty message every single release.
    fun buildChangelog(json: JSONObject): String {
        val arr = json.optJSONArray("changelog")
        if (arr != null && arr.length() > 0) {
            return buildString { for (i in 0 until arr.length()) append("• ${arr.getString(i)}\n") }.trimEnd()
        }
        val single = json.optString("changelog", "")
        if (single.isNotBlank()) return single
        return json.optString("notes", "")
    }

    private fun getInstalledVersionCode(): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun showUpdateDialog(versionName: String, notes: String, apkUrl: String, apkSha256: String?) {
        AlertDialog.Builder(context)
            .setTitle("MKTV $versionName available")
            .setMessage(buildString {
                if (notes.isNotBlank()) {
                    appendLine("What's new:")
                    appendLine()
                    append(notes)
                }
            })
            .setPositiveButton("Update now") { _, _ -> downloadAndInstall(apkUrl, apkSha256) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun showWhatsNewDialog(versionName: String, notes: String) {
        AlertDialog.Builder(context)
            .setTitle("What's new in MKTV $versionName")
            .setMessage(notes.ifBlank { "No changelog available." })
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun canInstallUnknownSources(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** Call from Activity.onResume() — resumes a pending download if permission was just granted. */
    fun resumeCheck(scope: CoroutineScope) {
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        val pendingUrl = prefs.getString("pending_apk_url", null) ?: return
        if (!canInstallUnknownSources()) return
        prefs.edit().remove("pending_apk_url").apply()
        Toast.makeText(context, "Downloading update…", Toast.LENGTH_SHORT).show()
        downloadAndInstall(pendingUrl)
    }

    // Public: SettingsActivity's manual "Check for Updates" flow calls this too, instead of
    // its old DownloadManager-based path — DownloadManager saved a per-version APK into the
    // app's external Downloads dir that nothing ever deleted, put a download notification in
    // the system tray, and choked on GitHub's S3 redirect chains ("Download failed"). This
    // path downloads to cache with a progress dialog, verifies the sha, installs, and the
    // launch-time sweep in check() removes the file afterwards.
    fun downloadAndInstall(apkUrl: String, expectedSha256: String? = null) {
        if (!canInstallUnknownSources()) {
            val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("pending_apk_url", apkUrl).apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            Toast.makeText(context, "Allow installing from unknown sources, then return to the app", Toast.LENGTH_LONG).show()
            return
        }

        // A ~20MB APK over a slow connection can take a while with zero feedback otherwise —
        // this was previously just a "Downloading update…" toast with no indication of
        // progress or whether it was still moving at all.
        val progress = ProgressDialogHolder(context)
        progress.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(apkUrl).build()
                val apkFile = File(context.externalCacheDir ?: context.cacheDir, "IPTV-update.apk")

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $apkUrl")
                    val body = response.body ?: throw Exception("Empty APK response")
                    val totalBytes = body.contentLength()
                    body.byteStream().use { input ->
                        apkFile.outputStream().use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var downloaded = 0L
                            var lastUpdateMs = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                // Throttle UI updates — one Handler post per byte chunk would
                                // spam the main thread with hundreds of updates per second.
                                val now = System.currentTimeMillis()
                                if (now - lastUpdateMs > 100) {
                                    lastUpdateMs = now
                                    withContext(Dispatchers.Main) { progress.update(downloaded, totalBytes) }
                                }
                            }
                            withContext(Dispatchers.Main) { progress.update(downloaded, totalBytes) }
                        }
                    }
                }

                if (expectedSha256 != null) {
                    withContext(Dispatchers.Main) { progress.setLabel("Verifying download…") }
                    val actual = sha256Of(apkFile)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        apkFile.delete()
                        throw Exception("checksum mismatch — download discarded")
                    }
                }

                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    Toast.makeText(context, "Update failed: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
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

    /** Thin wrapper so the download loop can report progress without caring whether the
     * context can actually show a dialog (e.g. a non-Activity context) — falls back to
     * silently doing nothing rather than crashing on BadTokenException. */
    private class ProgressDialogHolder(private val context: Context) {
        private var dialog: AlertDialog? = null
        private var progressBar: android.widget.ProgressBar? = null
        private var label: android.widget.TextView? = null

        fun show() {
            try {
                val layout = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val pad = (24 * context.resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                label = android.widget.TextView(context).apply { text = "Downloading update…" }
                progressBar = android.widget.ProgressBar(
                    context, null, android.R.attr.progressBarStyleHorizontal
                ).apply {
                    isIndeterminate = true
                    max = 100
                }
                layout.addView(label)
                layout.addView(progressBar, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (16 * context.resources.displayMetrics.density).toInt() })
                dialog = AlertDialog.Builder(context)
                    .setTitle("Updating MKTV")
                    .setView(layout)
                    .setCancelable(false)
                    .show()
            } catch (_: Exception) {
                // Non-Activity context or window gone — download still proceeds, just silently.
            }
        }

        fun setLabel(text: String) {
            label?.text = text
        }

        fun update(downloaded: Long, total: Long) {
            val pb = progressBar ?: return
            if (total > 0) {
                pb.isIndeterminate = false
                pb.progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                val mb = { b: Long -> "%.1f".format(b / 1_000_000.0) }
                label?.text = "Downloading update… ${mb(downloaded)} / ${mb(total)} MB"
            } else {
                // Server didn't send Content-Length — show bytes downloaded instead of a percent.
                label?.text = "Downloading update… %.1f MB".format(downloaded / 1_000_000.0)
            }
        }

        fun dismiss() {
            try { dialog?.dismiss() } catch (_: Exception) {}
        }
    }

    // This is the path the automatic on-launch "Update available" popup uses — it used to
    // always do a plain visible install regardless of the Silent Self-Update setting, which
    // only SettingsActivity's manual "Check for Updates" button respected. Most users only
    // ever see the automatic popup, so the toggle effectively did nothing for them.
    private fun installApk(apkFile: File) {
        val silentEnabled = kotlinx.coroutines.runBlocking {
            com.iptvapp.data.local.PreferencesManager(context).silentSelfUpdateEnabled.first()
        }
        if (silentEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            com.iptvapp.IptvApplication.logPlaybackEvent(context, "SILENT UPDATE (auto-popup path): attempting session install")
            try {
                installApkViaSession(apkFile)
                return
            } catch (e: Exception) {
                com.iptvapp.IptvApplication.logPlaybackEvent(
                    context, "SILENT UPDATE (auto-popup path): session install threw ${e.javaClass.simpleName}: ${e.message} — falling back"
                )
            }
        }
        installApkViaIntent(apkFile)
    }

    private fun installApkViaIntent(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun installApkViaSession(apkFile: File) {
        val installer = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        session.use {
            it.openWrite("update", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                it.fsync(out)
            }
            val action = "com.iptvapp.UPDATECHECKER_INSTALL_RESULT"
            // If PackageInstaller's callback intent is ever silently dropped by the OS (no
            // observed case, but nothing guarantees it can't happen), this receiver would
            // otherwise stay registered for the rest of the process's life. A timeout-based
            // unregister bounds that — safe to call twice since unregisterReceiver on an
            // already-unregistered receiver just throws, which this catches.
            var unregistered = false
            val safetyHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (unregistered) return
                    unregistered = true
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    when (val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)) {
                        android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                            com.iptvapp.IptvApplication.logPlaybackEvent(context, "SILENT UPDATE (auto-popup path): STATUS_SUCCESS")
                            // The session copied the APK's bytes into the installer; the source
                            // file is dead weight now — delete immediately rather than waiting
                            // for the next launch's stale-APK sweep.
                            try { apkFile.delete() } catch (_: Exception) {}
                            Toast.makeText(context, "MKTV updated", Toast.LENGTH_LONG).show()
                        }
                        android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            com.iptvapp.IptvApplication.logPlaybackEvent(
                                context, "SILENT UPDATE (auto-popup path): STATUS_PENDING_USER_ACTION — falling back to visible install"
                            )
                            @Suppress("DEPRECATION")
                            val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                            if (confirmIntent != null) {
                                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(confirmIntent)
                            } else {
                                installApkViaIntent(apkFile)
                            }
                        }
                        else -> {
                            val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                            com.iptvapp.IptvApplication.logPlaybackEvent(context, "SILENT UPDATE (auto-popup path) FAILED: status=$status msg=$msg — falling back")
                            installApkViaIntent(apkFile)
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, android.content.IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, android.content.IntentFilter(action))
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 0, Intent(action).setPackage(context.packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            it.commit(pendingIntent.intentSender)
            safetyHandler.postDelayed({
                if (!unregistered) {
                    unregistered = true
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                }
            }, 60_000L)
        }
    }
}