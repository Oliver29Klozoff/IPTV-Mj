package com.iptvapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.iptvapp.util.LogSanitizer
import com.iptvapp.worker.ReminderWorker
import com.google.android.gms.cast.framework.CastContext
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class IptvApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // The app's theme is DayNight, but every custom screen hardcodes a dark palette
        // regardless of system setting. Left on DayNight, only stock components (AlertDialog,
        // DatePickerDialog, etc.) would follow the system's light/dark setting — on a device
        // in light mode that meant dialogs with a light background and our hardcoded white
        // text became invisible. Force dark everywhere so stock dialogs always match.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        setupCrashHandler()
        createNotificationChannels()
        try { CastContext.getSharedInstance(this) } catch (_: Exception) {}
        // Marks which build is actually running the current process — an OTA update installs
        // the new APK but a process already alive keeps running the old code until it's fully
        // restarted, which otherwise silently defeats any logging added in the new version.
        try {
            val v = packageManager.getPackageInfo(packageName, 0)
            logPlaybackEvent(this, "APP STARTED: v${v.versionName} (${v.longVersionCode})")
        } catch (_: Exception) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    ReminderWorker.CHANNEL_ID,
                    "EPG Reminders",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Alerts for upcoming TV programs you've bookmarked" }
            )
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                // Xtream stream URLs embed the account's plaintext username/password in their
                // path, and network/player exceptions routinely include the failing URL in
                // their message — redact before this ever touches disk, not just at the point
                // where it's later uploaded via "Send Debug Report".
                val stackTrace = LogSanitizer.redactCredentials(sw.toString())
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val logEntry = "=== CRASH $timestamp ===\nThread: ${thread.name}\n$stackTrace\n\n"
                val logFile = File(filesDir, "crash_log.txt")
                // Keep only last 50KB of logs
                val existing = if (logFile.exists()) logFile.readText() else ""
                val trimmed = if (existing.length > 50000) existing.takeLast(40000) else existing
                logFile.writeText(trimmed + logEntry)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun getCrashLog(context: Context): String {
            return try {
                val logFile = File(context.filesDir, "crash_log.txt")
                if (logFile.exists()) logFile.readText().takeLast(3000)
                else "No crash logs found"
            } catch (e: Exception) {
                "Could not read crash log: ${e.message}"
            }
        }

        // Non-fatal playback events (player errors, retry attempts) never throw, so the
        // uncaught-exception handler above never sees them — without this, a black-screen
        // retry loop leaves zero trace in "Send Debug Report". Appends to the same log file
        // so a single report captures both crashes and this kind of silent, handled failure.
        fun logPlaybackEvent(context: Context, message: String) {
            try {
                val sanitized = LogSanitizer.redactCredentials(message)
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val logFile = File(context.filesDir, "crash_log.txt")
                val existing = if (logFile.exists()) logFile.readText() else ""
                val trimmed = if (existing.length > 50000) existing.takeLast(40000) else existing
                logFile.writeText(trimmed + "[$timestamp] $sanitized\n")
            } catch (_: Exception) {}
        }
    }
}