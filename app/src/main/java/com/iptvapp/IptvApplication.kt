package com.iptvapp

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
        setupCrashHandler()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()
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
    }
}