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
            try {
                val request = Request.Builder().url(versionJsonUrl).build()
                val body = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@launch
                    response.body?.string() ?: return@launch
                }
                val json = JSONObject(body)

                val latestCode = json.getLong("versionCode")
                val latestName = json.optString("versionName", "")
                val apkUrl = json.getString("apkUrl")
                val notes = buildString {
                    val arr = json.optJSONArray("changelog")
                    if (arr != null && arr.length() > 0) {
                        for (i in 0 until arr.length()) append("• ${arr.getString(i)}\n")
                    } else {
                        append(json.optString("notes", ""))
                    }
                }.trimEnd()

                val installedCode = getInstalledVersionCode()

                if (latestCode > installedCode) {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(latestName, notes, apkUrl)
                    }
                }
            } catch (_: Exception) {
                // Silent fail on launch check.
            }
        }
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

    private fun showUpdateDialog(versionName: String, notes: String, apkUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("MKTV $versionName available")
            .setMessage(
                buildString {
                    if (notes.isNotBlank()) {
                        appendLine("What's new:")
                        appendLine()
                        append(notes)
                    }
                }
            )
            .setPositiveButton("Update now") { _, _ ->
                downloadAndInstall(apkUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun canInstallUnknownSources(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun downloadAndInstall(apkUrl: String) {
        if (!canInstallUnknownSources()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            Toast.makeText(context, "Please allow installing from unknown sources", Toast.LENGTH_LONG).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(apkUrl).build()
                val apkFile = File(context.externalCacheDir ?: context.cacheDir, "IPTV-update.apk")

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $apkUrl")
                    response.body?.byteStream()?.use { input ->
                        apkFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("Empty APK response")
                }

                withContext(Dispatchers.Main) {
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Update failed: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(apkFile: File) {
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
}