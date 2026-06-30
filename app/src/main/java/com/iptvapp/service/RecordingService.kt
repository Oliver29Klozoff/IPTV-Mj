package com.iptvapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
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
        val path = intent.getStringExtra(EXTRA_OUTPUT_PATH) ?: return START_NOT_STICKY

        startForeground(NOTIF_ID, buildNotif(name))

        job = scope.launch {
            if (recordingId != -1) database.recordingDao().updateStatus(recordingId, "RECORDING")
            val ok = runCatching {
                val file = File(path).also { it.parentFile?.mkdirs() }
                file.outputStream().use { out -> recordHls(url, out, durationMs) }
            }.isSuccess
            if (recordingId != -1) {
                database.recordingDao().updateStatus(recordingId, if (ok) "DONE" else "FAILED")
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun recordHls(playlistUrl: String, output: OutputStream, durationMs: Long) {
        val t0 = System.currentTimeMillis()
        val seenSegments = linkedSetOf<String>()

        while (durationMs == 0L || System.currentTimeMillis() - t0 < durationMs) {
            val masterText = fetchText(playlistUrl) ?: break

            // If it's a master playlist (multi-bitrate), follow the first stream variant
            val mediaPlaylistUrl = if (masterText.contains("#EXT-X-STREAM-INF")) {
                val variantLine = masterText.lines()
                    .firstOrNull { !it.startsWith('#') && it.isNotBlank() } ?: break
                resolveUrl(playlistUrl, variantLine)
            } else {
                playlistUrl
            }

            val mediaText = if (mediaPlaylistUrl == playlistUrl) masterText
                            else fetchText(mediaPlaylistUrl) ?: break

            val targetDuration = mediaText.lines()
                .firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
                ?.removePrefix("#EXT-X-TARGETDURATION:")?.trim()?.toLongOrNull() ?: 6L

            for (line in mediaText.lines()) {
                if (line.isBlank() || line.startsWith('#')) continue
                val segUrl = resolveUrl(mediaPlaylistUrl, line)
                if (segUrl in seenSegments) continue
                seenSegments.add(segUrl)
                downloadSegment(segUrl, output)
                if (durationMs > 0 && System.currentTimeMillis() - t0 >= durationMs) return
            }

            // VOD playlist ends with this tag — no need to re-poll
            if (mediaText.contains("#EXT-X-ENDLIST")) break

            // Wait half the target duration before re-fetching the live playlist
            val waitMs = (targetDuration * 500L).coerceIn(2000L, 8000L)
            Thread.sleep(waitMs)
        }
    }

    private fun fetchText(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.connect()
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        text
    }.getOrNull()

    private fun downloadSegment(url: String, output: OutputStream) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            val buf = ByteArray(65536)
            conn.inputStream.use { input ->
                var n: Int
                while (input.read(buf).also { n = it } != -1) output.write(buf, 0, n)
            }
            conn.disconnect()
        }
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        if (relative.startsWith("/")) {
            val afterScheme = base.indexOf("//") + 2
            val slashAfterHost = base.indexOf("/", afterScheme)
            return if (slashAfterHost == -1) base + relative else base.substring(0, slashAfterHost) + relative
        }
        return "${base.substringBeforeLast('/')}/$relative"
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
            .setContentText("Recording in progress…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
}
