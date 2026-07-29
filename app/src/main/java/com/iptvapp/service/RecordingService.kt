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
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.iptvapp.data.local.IptvDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
    @Inject lateinit var prefs: com.iptvapp.data.local.PreferencesManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Keyed by recordingId: when two recordings are scheduled concurrently, onStartCommand
    // fires twice on this same Service instance. A single shared job/wakeLock field would
    // let the second recording's start overwrite the first's wakelock, and the first
    // recording finishing would release/null out the second's wakelock out from under it.
    private val jobs = mutableMapOf<Int, Job>()
    private val wakeLocks = mutableMapOf<Int, PowerManager.WakeLock>()
    private val activeRecordingIds = mutableSetOf<Int>()

    companion object {
        const val CHANNEL_ID = "recording_notifications"
        // Separate from CHANNEL_ID above — that one is IMPORTANCE_LOW and silent by design (it's
        // just the ongoing foreground-service indicator "Recording: X"). A failure needs to
        // actually get noticed (a recording is fire-and-forget — the user isn't watching the
        // Recordings screen when it fails), so it gets its own higher-importance channel instead
        // of riding the silent one.
        const val FAILURE_CHANNEL_ID = "recording_failure_notifications"
        const val NOTIF_ID = 2001
        // Offset well clear of NOTIF_ID/foreground-service notification ids so a failure alert
        // for recordingId N never collides with (or gets silently replaced by) another
        // notification using the same raw id.
        const val NOTIF_ID_FAILURE_BASE = 3_000_000
        const val EXTRA_RECORDING_ID = "recording_id"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_OUTPUT_PATH = "output_path"
        private const val TS_PACKET_SIZE = 188
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recordings", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(FAILURE_CHANNEL_ID, "Recording Failures", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun notifyRecordingFailed(recordingId: Int, channelName: String, reason: String) {
        val tapIntent = Intent(this, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = android.app.PendingIntent.getActivity(
            this, recordingId, tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FAILURE_CHANNEL_ID)
            .setSmallIcon(com.iptvapp.R.drawable.ic_notification)
            .setContentTitle("Recording failed: $channelName")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID_FAILURE_BASE + recordingId, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recordingId = intent?.getIntExtra(EXTRA_RECORDING_ID, -1) ?: -1
        val url = intent?.getStringExtra(EXTRA_STREAM_URL) ?: return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Channel"
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        val target = intent.getStringExtra(EXTRA_OUTPUT_PATH) ?: return START_NOT_STICKY

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotif(name), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotif(name))
        }

        // Keep CPU alive for the duration of this specific recording
        wakeLocks.remove(recordingId)?.let { if (it.isHeld) it.release() }
        wakeLocks[recordingId] = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mktv:recording:$recordingId")
            .apply { acquire(durationMs + 60_000L) }

        activeRecordingIds.add(recordingId)

        jobs[recordingId] = scope.launch {
            if (recordingId != -1) database.recordingDao().updateStatus(recordingId, "RECORDING")

            val result = runCatching {
                openRecordingOutput(target).use { out ->
                    val bytes = recordStream(url, out, durationMs)
                    if (bytes < 1024) throw IOException("Recording wrote only $bytes bytes")
                }
            }
            val ok = result.isSuccess

            finalizeTarget(target, ok)
            // The raw capture is safely on disk now (or definitively failed) — nothing past
            // this point can lose the recording, so it no longer needs onDestroy's
            // kill-safety net treating it as a still-in-flight recording.
            activeRecordingIds.remove(recordingId)

            if (ok) {
                if (recordingId != -1) database.recordingDao().updateStatus(recordingId, "COMPRESSING")
                val compressedPath = runCatching { tryCompressRecording(target, name) }.getOrNull()
                val finalPath = compressedPath ?: target
                if (recordingId != -1) database.recordingDao().updatePathAndStatus(recordingId, finalPath, "DONE")
            } else {
                if (recordingId != -1) {
                    val reason = classifyFailureReason(result.exceptionOrNull())
                    database.recordingDao().updateStatusWithReason(recordingId, "FAILED", reason)
                    notifyRecordingFailed(recordingId, name, reason)
                }
            }

            wakeLocks.remove(recordingId)?.let { if (it.isHeld) it.release() }
            jobs.remove(recordingId)
            stopSelf(startId)
        }

        return START_REDELIVER_INTENT
    }

    /** Re-encodes the just-finished raw recording at a lower bitrate to shrink it, then deletes
     * the raw original. Runs only after the raw capture is confirmed safely written — never
     * live — so a transcode failure just means the recording stays at its original (larger)
     * size instead of risking the capture itself. Returns the new path, or null to keep the
     * original untouched. */
    private suspend fun tryCompressRecording(sourceTarget: String, channelName: String): String? {
        val tempFile = File(cacheDir, "compress_${System.currentTimeMillis()}.mp4")
        return try {
            val sourceUri = if (sourceTarget.startsWith("content://")) {
                Uri.parse(sourceTarget)
            } else {
                Uri.fromFile(File(sourceTarget))
            }
            val height = probeVideoHeight(sourceUri)
            val success = RecordingCompressor.compress(this, sourceUri, tempFile.absolutePath, height)
            if (!success || tempFile.length() < 1024) return null

            val finalTarget = createCompressedOutputTarget(channelName)
            openRecordingOutput(finalTarget).use { out -> tempFile.inputStream().use { it.copyTo(out) } }
            finalizeTarget(finalTarget, true)

            if (sourceTarget.startsWith("content://")) {
                runCatching { contentResolver.delete(Uri.parse(sourceTarget), null, null) }
            } else {
                runCatching { File(sourceTarget).delete() }
            }
            finalTarget
        } catch (e: Exception) {
            null
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    // recordDirectStream/recordHls only ever surface failures as a plain IOException message
    // string (no structured error-code type, unlike PlayerActivity's live-playback error path
    // which gets a real HttpDataSource.InvalidResponseCodeException) — this parses that same
    // "HTTP $code" message shape to detect the same 403/429 connection-limit-rejection pattern
    // PlayerActivity.looksLikeConnectionLimitRejection already checks for, so a FAILED recording
    // can say something more useful than just "FAILED" with no explanation.
    private fun classifyFailureReason(error: Throwable?): String {
        val message = error?.message ?: return "Recording failed (unknown error)"
        val httpCodeMatch = Regex("""HTTP (\d{3})""").find(message)
        val httpCode = httpCodeMatch?.groupValues?.get(1)?.toIntOrNull()
        return when {
            httpCode == 403 || httpCode == 429 ->
                "Provider rejected the connection — likely another stream (live viewing or another recording) was already using your account's connection limit"
            httpCode != null -> "Provider returned an error (HTTP $httpCode)"
            message.contains("only", ignoreCase = true) && message.contains("bytes", ignoreCase = true) ->
                "No data received from the provider — connection may have been rejected or the stream was unavailable"
            message.contains("Too many redirects", ignoreCase = true) -> "Stream redirected too many times"
            else -> "Network error: ${message.take(100)}"
        }
    }

    private fun probeVideoHeight(uri: Uri): Int {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 1080
        } catch (_: Exception) {
            1080
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun createCompressedOutputTarget(channelName: String): String {
        val safeName = channelName.replace(Regex("[^a-zA-Z0-9 _-]"), "_")
        val fileName = "${safeName}_${System.currentTimeMillis()}_compressed.mp4"
        val folderName = prefs.recordingFolderName.first()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_MOVIES}/$folderName")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) return uri.toString()
        }

        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), folderName)
        dir.mkdirs()
        return File(dir, fileName).absolutePath
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
        val buffer = ByteArray(128 * 1024)
        val safeUrl = com.iptvapp.util.LogSanitizer.redactCredentials(streamUrl)
        // Bytes left over from an incomplete trailing TS packet, carried into the next read
        // call (and across reconnects) so every write stays packet-aligned — see the comment
        // at the write site below for why this matters.
        var tsCarry = 0

        while (durationMs == 0L || System.currentTimeMillis() - started < durationMs) {
            val remaining = if (durationMs > 0L) durationMs - (System.currentTimeMillis() - started) else 0L
            if (remaining < 0L) break

            // HttpURLConnection's instanceFollowRedirects only auto-follows same-protocol,
            // same-host redirects (and not reliably even then for streaming responses) — some
            // providers 301 a .ts URL to a different host/CDN, which this codebase's ExoPlayer
            // live-playback path follows transparently but raw HttpURLConnection just keeps
            // re-hitting the original URL, getting the same 301, forever, until this whole
            // recording eventually gets killed by the foreground-service timeout with zero
            // bytes written. Resolve redirects manually, up to a small hop cap.
            var resolvedUrl = streamUrl
            var conn: HttpURLConnection? = null
            try {
                var hops = 0
                while (hops < 5) {
                    val c = URL(resolvedUrl).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 15_000
                    c.readTimeout = 30_000
                    android.util.Log.d("RecordingService", "recordDirectStream: connecting to ${com.iptvapp.util.LogSanitizer.redactCredentials(resolvedUrl)}")
                    c.connect()
                    android.util.Log.d("RecordingService", "recordDirectStream: connected, HTTP ${c.responseCode}")

                    if (c.responseCode in 300..399) {
                        val location = c.getHeaderField("Location")
                        c.disconnect()
                        if (location.isNullOrBlank()) throw IOException("HTTP ${c.responseCode} with no Location header")
                        resolvedUrl = URL(URL(resolvedUrl), location).toString()
                        hops++
                        continue
                    }
                    if (c.responseCode !in 200..299) {
                        val code = c.responseCode
                        c.disconnect()
                        throw IOException("HTTP $code")
                    }
                    conn = c
                    break
                }
                val activeConn = conn ?: throw IOException("Too many redirects")

                activeConn.inputStream.use { input ->
                    while (durationMs == 0L || System.currentTimeMillis() - started < durationMs) {
                        val n = input.read(buffer, tsCarry, buffer.size - tsCarry)
                        if (n == -1) break
                        val available = tsCarry + n
                        // MPEG-TS is packetized (188 bytes/packet). A reconnect that lands
                        // mid-packet — very likely here, since the previous connection can drop
                        // at any arbitrary byte offset — desyncs every packet boundary for the
                        // rest of the file for any demuxer parsing by fixed packet stride, which
                        // is exactly what was corrupting playback duration despite the full byte
                        // count landing on disk. Only ever write whole packets; carry any
                        // leftover partial packet over to the next read (across reconnects too).
                        val wholePackets = available - (available % TS_PACKET_SIZE)
                        if (wholePackets > 0) {
                            output.write(buffer, 0, wholePackets)
                            written += wholePackets
                        }
                        val leftover = available - wholePackets
                        if (leftover > 0) System.arraycopy(buffer, wholePackets, buffer, 0, leftover)
                        tsCarry = leftover
                    }
                }
                android.util.Log.d("RecordingService", "recordDirectStream: read loop ended, written=$written bytes")
            } catch (e: IOException) {
                android.util.Log.e("RecordingService", "recordDirectStream: IOException, written=$written bytes", e)
                // Brief pause before reconnect attempt — avoids hammering a broken server
                if (durationMs > 0L && System.currentTimeMillis() - started < durationMs) {
                    Thread.sleep(2000L)
                }
            } finally {
                conn?.disconnect()
            }
        }

        output.flush()
        return written
    }

    private fun recordHls(playlistUrl: String, output: OutputStream, durationMs: Long): Long {
        val started = System.currentTimeMillis()
        val seenSegments = linkedSetOf<String>()
        var written = 0L
        val deadline = if (durationMs > 0L) started + durationMs else 0L

        while (deadline == 0L || System.currentTimeMillis() < deadline) {
            val masterText = fetchText(playlistUrl)

            if (!masterText.trimStart().startsWith("#EXTM3U")) {
                return recordDirectStream(playlistUrl, output, durationMs)
            }

            val mediaPlaylistUrl = if (masterText.contains("#EXT-X-STREAM-INF")) {
                val variantLine = masterText.lines()
                    .firstOrNull { !it.startsWith("#") && it.isNotBlank() }
                if (variantLine == null) { Thread.sleep(2000L); continue }
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

                written += downloadSegment(segmentUrl, output, deadline)

                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    output.flush()
                    return written
                }
            }

            if (mediaText.contains("#EXT-X-ENDLIST")) break

            val waitMs = if (deadline > 0L) {
                (targetDuration * 500L).coerceIn(2000L, 8000L).coerceAtMost(deadline - System.currentTimeMillis()).coerceAtLeast(0L)
            } else {
                (targetDuration * 500L).coerceIn(2000L, 8000L)
            }
            if (waitMs > 0L) Thread.sleep(waitMs)
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

    private fun downloadSegment(url: String, output: OutputStream, deadline: Long = 0L): Long {
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
                while (deadline == 0L || System.currentTimeMillis() < deadline) {
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
        jobs.values.forEach { it.cancel() }
        if (activeRecordingIds.isNotEmpty()) {
            runCatching {
                // Bounded, not unbounded — this runs on the main thread during teardown
                // (service destruction, possibly reboot cleanup), so a momentarily locked
                // DB must not be able to hang it indefinitely and risk an ANR.
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(3000L) {
                        activeRecordingIds.forEach { rid ->
                            if (rid != -1) database.recordingDao().updateStatus(rid, "FAILED")
                        }
                    }
                }
            }
        }
        scope.cancel()
        wakeLocks.values.forEach { if (it.isHeld) it.release() }
        wakeLocks.clear()
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