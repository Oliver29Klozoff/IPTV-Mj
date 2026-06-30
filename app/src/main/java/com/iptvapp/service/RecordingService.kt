package com.iptvapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.iptvapp.data.local.IptvDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var database: IptvDatabase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    companion object {
        const val CHANNEL_ID = "recording_notifications"
        const val NOTIF_ID = 2001
        const val EXTRA_RECORDING_ID = "recording_id"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_OUTPUT_PATH = "output_path"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Recordings", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recordingId = intent?.getIntExtra(EXTRA_RECORDING_ID, -1) ?: -1
        val url = intent?.getStringExtra(EXTRA_STREAM_URL) ?: return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Channel"
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        val target = intent.getStringExtra(EXTRA_OUTPUT_PATH) ?: return START_NOT_STICKY

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotif(name), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, buildNotif(name))
        }

        job = scope.launch {
            if (recordingId != -1) database.recordingDao().updateStatus(recordingId, "RECORDING")

            val ok = runCatching {
                openRecordingOutput(target).use { out ->
                    val bytes = recordStream(url, out, durationMs)
                    if (bytes < 1024) throw IOException("Recording wrote only $bytes bytes")
                }
            }.isSuccess

            finalizeTarget(target, ok)

            if (recordingId != -1) {
                database.recordingDao().updateStatus(recordingId, if (ok) "DONE" else "FAILED")
            }

            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun openRecordingOutput(target: String): OutputStream {
        return if (target.startsWith("content://")) {
            contentResolver.openOutputStream(Uri.parse(target), "w")
                ?: throw IOException("Unable to open recording output")
        } else {
            val file = File(target).also { it.parentFile?.mkdirs() }
            file.outputStream()
        }
    }

    private fun finalizeTarget(target: String, success: Boolean) {
        if (!target.startsWith("content://")) {
            if (!success) runCatching { File(target).delete() }
            return
        }

        val uri = Uri.parse(target)
        if (success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, values, null, null)
        }

        if (!success) {
            runCatching { contentResolver.delete(uri, null, null) }
        }
    }

    private fun recordStream(streamUrl: String, output: OutputStream, durationMs: Long): Long {
        val lower = streamUrl.lowercase(Locale.US)
        return if (lower.contains(".m3u8")) {
            recordHls(streamUrl, output, durationMs)
        } else {
            recordDirectStream(streamUrl, output, durationMs)
        }
    }

    private fun recordDirectStream(streamUrl: String, output: OutputStream, durationMs: Long): Long {
        val started = System.currentTimeMillis()
        var written = 0L
        val conn = URL(streamUrl).openConnection() as HttpURLConnection

        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()

            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode}")
            }

            val buffer = ByteArray(128 * 1024)
            conn.inputStream.use { input ->
                while (durationMs == 0L || System.currentTimeMillis() - started < durationMs) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    written += n
                }
            }
        } finally {
            conn.disconnect()
        }

        output.flush()
        return written
    }

    private fun recordHls(playlistUrl: String, output: OutputStream, durationMs: Long): Long {
        val started = System.currentTimeMillis()
        val seenSegments = linkedSetOf<String>()
        var written = 0L

        while (durationMs == 0L || System.currentTimeMillis() - started < durationMs) {
            val masterText = fetchText(playlistUrl)

            if (!masterText.trimStart().startsWith("#EXTM3U")) {
                return recordDirectStream(playlistUrl, output, durationMs)
            }

            val mediaPlaylistUrl = if (masterText.contains("#EXT-X-STREAM-INF")) {
                val variantLine = masterText.lines()
                    .firstOrNull { !it.startsWith("#") && it.isNotBlank() }
                    ?: break
                resolveUrl(playlistUrl, variantLine)
            } else {
                playlistUrl
            }

            val mediaText = if (mediaPlaylistUrl == playlistUrl) {
                masterText
            } else {
                fetchText(mediaPlaylistUrl)
            }

            val targetDuration = mediaText.lines()
                .firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
                ?.removePrefix("#EXT-X-TARGETDURATION:")
                ?.trim()
                ?.toLongOrNull()
                ?: 6L

            for (line in mediaText.lines()) {
                if (line.isBlank() || line.startsWith("#")) continue

                val segmentUrl = resolveUrl(mediaPlaylistUrl, line.trim())
                if (!seenSegments.add(segmentUrl)) continue

                written += downloadSegment(segmentUrl, output)

                if (durationMs > 0 && System.currentTimeMillis() - started >= durationMs) {
                    output.flush()
                    return written
                }
            }

            if (mediaText.contains("#EXT-X-ENDLIST")) break

            val waitMs = (targetDuration * 500L).coerceIn(2000L, 8000L)
            Thread.sleep(waitMs)
        }

        output.flush()
        return written
    }

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.connect()

            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode}")
            }

            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadSegment(url: String, output: OutputStream): Long {
        val conn = URL(url).openConnection() as HttpURLConnection
        var written = 0L

        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()

            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode}")
            }

            val buffer = ByteArray(128 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    written += n
                }
            }
        } finally {
            conn.disconnect()
        }

        return written
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        if (relative.startsWith("/")) {
            val afterScheme = base.indexOf("//") + 2
            val slashAfterHost = base.indexOf("/", afterScheme)
            return if (slashAfterHost == -1) base + relative else base.substring(0, slashAfterHost) + relative
        }
        return "${base.substringBeforeLast("/")}/$relative"
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotif(name: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording: $name")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
}