package com.iptvapp.util

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.work.WorkManager
import com.iptvapp.IptvApplication
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.worker.EpgRefreshWorker
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared "what to include" logic for both sendDebugReport() (SettingsActivity/TvSettingsActivity,
 * which POST this to a Discord webhook) and the LAN Export feature (which serves it unauthenticated
 * over the local network). Deliberately excludes Xtream username/password — server URL is reduced
 * to host:port only, same as the original sendDebugReport() implementations this was extracted
 * from — and redacts the crash log via LogSanitizer for the same reason. */
object DebugInfoCollector {

    suspend fun collect(context: Context, db: IptvDatabase, prefs: PreferencesManager): String {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
        // Host:port only — never the raw serverUrl, which for Xtream can carry creds in the path.
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
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramFree = "%.1f GB".format(memInfo.availMem / 1e9)
        val ramTotal = "%.1f GB".format(memInfo.totalMem / 1e9)
        val stat = StatFs(Environment.getDataDirectory().path)
        val storageFree = "%.1f GB".format(stat.availableBlocksLong * stat.blockSizeLong / 1e9)
        val dm = context.resources.displayMetrics
        val screen = "${dm.widthPixels}x${dm.heightPixels} (${dm.densityDpi}dpi)"
        val epgWorkState = try {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(EpgRefreshWorker.UNIQUE_WORK_NAME).get()
                .firstOrNull()?.state?.name ?: "None"
        } catch (_: Exception) { "Unknown" }
        // Defense in depth: the crash handler already redacts before writing to disk,
        // but redact again here too in case anything else ever lands in this log.
        val crashLog = LogSanitizer.redactCredentials(IptvApplication.getCrashLog(context))
        val debugText = """
            App: v${pInfo.versionName} (${pInfo.versionCodeCompat})
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
        return debugText + "\n\n=== CRASH LOG ===\n" + crashLog
    }
}
