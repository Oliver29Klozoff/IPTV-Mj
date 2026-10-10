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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
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
    // Concurrent: written on the main thread (onStartCommand) and by each recording's IO coroutine.
    private val jobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    private val wakeLocks = java.util.concurrent.ConcurrentHashMap<Int, PowerManager.WakeLock>()
    private val activeRecordingIds: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    // The newest start this service has seen. A finished recording used to call stopSelf(its own
    // startId), which stops the whole service when that start happens to be the newest — killing
    // an earlier, longer recording still running beside it. The service now stops only once no
    // recording is left (stopWhenIdle), and stopSelf(lastStartId) still won't stop it if a newer
    // start is already on its way in.
    @Volatile private var lastStartId = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun stopWhenIdle() {
        mainHandler.post { if (jobs.isEmpty()) stopSelf(lastStartId) }
    }

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
        // Continue an interrupted capture: append to the file instead of starting it over.
        const val EXTRA_RESUME = "resume"
        private const val TS_PACKET_SIZE = 188
        const val STOPPED_BY_TIME_LIMIT_REASON =
            "Stopped early: Android allows background recording for about 6 hours a day on this device. " +
                "The part recorded so far is saved."
        const val PARTIAL_REASON_PREFIX = "Partly recorded — the stream stopped before the end"
        // A failed capture with at least this much on disk is kept as a partial recording rather
        // than deleted (a network drop an hour in used to throw the whole hour away).
        private const val KEEP_PARTIAL_MIN_BYTES = 1024L * 1024L
    }

    /**
     * One running capture. stop() makes it finish promptly — it closes the open HTTP connection
     * (a blocked read returns at once instead of waiting out a 30 s read timeout) and cuts short
     * the waits between reconnects and playlist polls — so what was recorded is closed and saved.
     * Used when Android 15's time limit ends the service.
     */
    private class Capture {
        @Volatile var stopped = false
            private set
        private val connections: MutableSet<HttpURLConnection> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        fun open(conn: HttpURLConnection): HttpURLConnection {
            connections += conn
            if (stopped) runCatching { conn.disconnect() }
            return conn
        }

        fun close(conn: HttpURLConnection) {
            connections -= conn
            conn.disconnect()
        }

        fun stop() {
            stopped = true
            connections.forEach { runCatching { it.disconnect() } }
        }

        /** Thread.sleep that ends early once stop() is called. */
        fun sleep(ms: Long) {
            val until = System.currentTimeMillis() + ms
            while (!stopped) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) return
                Thread.sleep(minOf(left, 100L))
            }
        }
    }

    private val captures = java.util.concurrent.ConcurrentHashMap<Int, Capture>()
    @Volatile private var timedOut = false

    /** Counts what reaches the file, so a capture that fails part-way knows whether it has
     * anything worth keeping. */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        @Volatile var count = 0L
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recordings", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(FAILURE_CHANNEL_ID, "Recording Failures", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun notifyRecordingFailed(
        recordingId: Int,
        channelName: String,
        reason: String,
        title: String = "Recording failed: $channelName"
    ) {
        val tapIntent = Intent(this, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = android.app.PendingIntent.getActivity(
            this, recordingId, tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FAILURE_CHANNEL_ID)
            .setSmallIcon(com.iptvapp.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID_FAILURE_BASE + recordingId, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val recordingId = intent?.getIntExtra(EXTRA_RECORDING_ID, -1) ?: -1
        val url = intent?.getStringExtra(EXTRA_STREAM_URL) ?: return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Channel"
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        val target = intent.getStringExtra(EXTRA_OUTPUT_PATH) ?: return START_NOT_STICKY
        // Picking an interrupted capture back up (recovery after a restart, or Android redelivering
        // this start after the process was killed): add to what's already in the file.
        val resume = intent.getBooleanExtra(EXTRA_RESUME, false) || (flags and START_FLAG_REDELIVERY) != 0

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotif(name), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, buildNotif(name))
            }
        } catch (e: Exception) {
            // Android refused to make this a foreground service — no background-start exemption
            // (Android 12+), or Android 15's daily dataSync time is used up. Nothing has been
            // recorded. When it throws, this service isn't foreground yet, so no other recording is
            // running in it.
            if (recordingId != -1) {
                val refused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                if (refused) {
                    // Still startable: put a fresh start back to SCHEDULED and ask for a tap, which
                    // opens the app — starting from the foreground is allowed, and opening the app
                    // resets Android 15's daily time.
                    if (!resume) runCatching {
                        kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.withTimeoutOrNull(2000L) { database.recordingDao().unclaim(recordingId) }
                        }
                    }
                    RecordingNotifications.postTapToStart(this, recordingId, name, resume)
                } else {
                    val reason = "Android didn't allow the recording to start: ${e.javaClass.simpleName}"
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.withTimeoutOrNull(2000L) {
                                database.recordingDao().updateStatusWithReason(recordingId, "FAILED", reason)
                            }
                        }
                    }
                    notifyRecordingFailed(recordingId, name, reason)
                }
            }
            if (jobs.isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }

        // Already recording this one (a duplicate alarm, or recovery racing a capture that is
        // running): one capture per recording, never two writers on one file.
        if (recordingId != -1 && jobs[recordingId]?.isActive == true) return START_REDELIVER_INTENT

        // Keep CPU alive for the duration of this specific recording
        wakeLocks.remove(recordingId)?.let { if (it.isHeld) it.release() }
        wakeLocks[recordingId] = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mktv:recording:$recordingId")
            .apply { acquire(durationMs + 60_000L) }

        activeRecordingIds.add(recordingId)

        val capture = Capture()
        // Registered before it runs: a job that ends at once (nothing to record) must not remove
        // itself before it is in the map, or the map would never empty and the service never stop.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            // How long to record comes from the schedule when there is one: a redelivered start
            // still carries the duration it began with, and the window ends when it always did.
            var recordMs = durationMs
            if (recordingId != -1) {
                val rec = database.recordingDao().getById(recordingId)
                val left = rec?.let { it.scheduledStartMs + it.durationMs - System.currentTimeMillis() } ?: 0L
                // Deleted (cancelled) or already finished: nothing to record. Or the window is over
                // (a redelivery that came too late) — and 0 would mean "no limit" to the capture
                // loops; the recordings screen's clean-up settles that row as before.
                if (rec == null || (rec.status != "SCHEDULED" && rec.status != "RECORDING") || left < 5_000L) {
                    activeRecordingIds.remove(recordingId)
                    wakeLocks.remove(recordingId)?.let { if (it.isHeld) it.release() }
                    jobs.remove(recordingId)
                    captures.remove(recordingId)
                    stopWhenIdle()
                    return@launch
                }
                recordMs = left
                database.recordingDao().updateStatus(recordingId, "RECORDING")
            }

            // What a resumed capture already has on disk (an interrupted one may have nothing: the
            // process can die between the claim and the first write).
            val before = if (resume) existingSize(target) else 0L
            var written = 0L
            val result = runCatching {
                openRecordingOutput(target, append = resume).use { raw ->
                    val out = CountingOutputStream(raw)
                    try {
                        val bytes = recordStream(url, out, recordMs, capture)
                        if (before + bytes < 1024) throw IOException("Recording wrote only ${before + bytes} bytes")
                    } finally {
                        written = out.count
                    }
                }
            }
            val ok = result.isSuccess
            // The stream failed part-way, but there is a real recording on disk (this attempt's,
            // plus the earlier part of a resumed one): keep it as a partial recording instead of
            // deleting it. Too little to play is a failure, as before.
            val partial = !ok && before + written >= KEEP_PARTIAL_MIN_BYTES

            // Saving the capture and its final status runs even if the service is being torn down
            // (onDestroy cancels the scope), so a kept file never sits half-saved. Re-encoding stays
            // cancellable: teardown stops the encoder, and the raw recording is kept instead.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                finalizeTarget(target, ok || partial)
                // The raw capture is safely on disk now (or definitively failed) — nothing past
                // this point can lose the recording, so it no longer needs onDestroy's
                // kill-safety net treating it as a still-in-flight recording.
                activeRecordingIds.remove(recordingId)
            }

            if (ok || partial) {
                // Cut short by Android's time limit (onTimeout): the service has seconds left, so
                // the raw capture is kept as it is, without re-encoding.
                val stoppedEarly = timedOut && capture.stopped
                // Recording size (v7.01): Original keeps the raw capture; Compact / Standard re-encode it.
                val size = runCatching { prefs.recordingSize.first() }.getOrDefault("compact")
                val compressedPath = if (size == "original" || stoppedEarly || !isActive) null else {
                    if (recordingId != -1) runCatching { database.recordingDao().updateStatus(recordingId, "COMPRESSING") }
                    // Cancelled part-way (teardown): null, so the raw capture is what's kept.
                    runCatching { tryCompressRecording(target, name, compact = size == "compact") }.getOrNull()
                }
                val finalPath = compressedPath ?: target
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    if (recordingId != -1) database.recordingDao().updatePathAndStatus(recordingId, finalPath, "DONE")
                    if (recordingId != -1 && (stoppedEarly || partial)) {
                        val reason = if (stoppedEarly) STOPPED_BY_TIME_LIMIT_REASON
                            else "$PARTIAL_REASON_PREFIX (${classifyFailureReason(result.exceptionOrNull())})"
                        database.recordingDao().updateStatusWithReason(recordingId, "DONE", reason)
                        notifyRecordingFailed(recordingId, name, reason, title = "Recording stopped early: $name")
                    }
                    // Look for commercial breaks in the background (Skip break in the player).
                    if (recordingId != -1) com.iptvapp.worker.AdBreakWorker.enqueue(applicationContext, recordingId, com.iptvapp.worker.AdBreakWorker.uriForPath(finalPath))
                }
            } else if (recordingId != -1) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val reason = classifyFailureReason(result.exceptionOrNull())
                    database.recordingDao().updateStatusWithReason(recordingId, "FAILED", reason)
                    notifyRecordingFailed(recordingId, name, reason)
                }
            }

            wakeLocks.remove(recordingId)?.let { if (it.isHeld) it.release() }
            jobs.remove(recordingId)
            captures.remove(recordingId)
            stopWhenIdle()
        }
        captures[recordingId] = capture
        jobs[recordingId] = job
        job.start()

        return START_REDELIVER_INTENT
    }

    // Android 15+ (targetSdk 35): a dataSync foreground service may run about 6 hours in 24 — a
    // budget shared with the guide refresh worker and downloads, reset when the user opens the app.
    // When it runs out Android calls this and the service must stop within seconds, or the app is
    // crashed. Each capture is asked to stop and save what it has (Capture.stop); the service then
    // stops. A capture that can't finish in time is handled by onDestroy's kill-safety as before.
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        captures.values.forEach { it.stop() }
        val running = jobs.values.toList()
        scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(2_500L) { running.joinAll() }
            kotlinx.coroutines.withContext(Dispatchers.Main) { stopSelf() }
        }
    }

    /** Re-encodes the just-finished raw recording at a lower bitrate to shrink it, then deletes
     * the raw original. Runs only after the raw capture is confirmed safely written — never
     * live — so a transcode failure just means the recording stays at its original (larger)
     * size instead of risking the capture itself. Returns the new path, or null to keep the
     * original untouched. */
    private suspend fun tryCompressRecording(sourceTarget: String, channelName: String, compact: Boolean): String? {
        val tempFile = File(cacheDir, "compress_${System.currentTimeMillis()}.mp4")
        return try {
            val sourceUri = if (sourceTarget.startsWith("content://")) {
                Uri.parse(sourceTarget)
            } else {
                Uri.fromFile(File(sourceTarget))
            }
            val height = probeVideoHeight(sourceUri)
            val success = RecordingCompressor.compress(this, sourceUri, tempFile.absolutePath, height, compact = compact)
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

    /** Bytes already in the recording's file; 0 when it's missing or can't be read. */
    private fun existingSize(target: String): Long = runCatching {
        if (target.startsWith("content://")) {
            contentResolver.openFileDescriptor(Uri.parse(target), "r")?.use { it.statSize } ?: 0L
        } else {
            File(target).length()
        }
    }.getOrDefault(0L).coerceAtLeast(0L)

    private fun openRecordingOutput(target: String, append: Boolean = false): OutputStream {
        return if (target.startsWith("content://")) {
            contentResolver.openOutputStream(Uri.parse(target), if (append) "wa" else "w")
                ?: throw IOException("Unable to open recording output")
        } else {
            val file = File(target).also { it.parentFile?.mkdirs() }
            java.io.FileOutputStream(file, append)
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

    private fun recordStream(streamUrl: String, output: OutputStream, durationMs: Long, capture: Capture): Long {
        val lower = streamUrl.lowercase(Locale.US)
        return if (lower.contains(".m3u8")) {
            recordHls(streamUrl, output, durationMs, capture)
        } else {
            recordDirectStream(streamUrl, output, durationMs, capture)
        }
    }

    private fun recordDirectStream(streamUrl: String, output: OutputStream, durationMs: Long, capture: Capture): Long {
        val started = System.currentTimeMillis()
        var written = 0L
        val buffer = ByteArray(128 * 1024)
        val safeUrl = com.iptvapp.util.LogSanitizer.redactCredentials(streamUrl)
        // Bytes left over from an incomplete trailing TS packet, carried into the next read
        // call (and across reconnects) so every write stays packet-aligned — see the comment
        // at the write site below for why this matters.
        var tsCarry = 0

        while ((durationMs == 0L || System.currentTimeMillis() - started < durationMs) && !capture.stopped) {
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
                    val c = capture.open(URL(resolvedUrl).openConnection() as HttpURLConnection)
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 15_000
                    c.readTimeout = 30_000
                    android.util.Log.d("RecordingService", "recordDirectStream: connecting to ${com.iptvapp.util.LogSanitizer.redactCredentials(resolvedUrl)}")
                    c.connect()
                    android.util.Log.d("RecordingService", "recordDirectStream: connected, HTTP ${c.responseCode}")

                    if (c.responseCode in 300..399) {
                        val location = c.getHeaderField("Location")
                        capture.close(c)
                        if (location.isNullOrBlank()) throw IOException("HTTP ${c.responseCode} with no Location header")
                        resolvedUrl = URL(URL(resolvedUrl), location).toString()
                        hops++
                        continue
                    }
                    if (c.responseCode !in 200..299) {
                        val code = c.responseCode
                        capture.close(c)
                        throw IOException("HTTP $code")
                    }
                    conn = c
                    break
                }
                val activeConn = conn ?: throw IOException("Too many redirects")

                activeConn.inputStream.use { input ->
                    while ((durationMs == 0L || System.currentTimeMillis() - started < durationMs) && !capture.stopped) {
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
                if (durationMs > 0L && System.currentTimeMillis() - started < durationMs && !capture.stopped) {
                    capture.sleep(2000L)
                }
            } finally {
                conn?.let { capture.close(it) }
            }
        }

        output.flush()
        return written
    }

    private fun recordHls(playlistUrl: String, output: OutputStream, durationMs: Long, capture: Capture): Long {
        val started = System.currentTimeMillis()
        val seenSegments = linkedSetOf<String>()
        var written = 0L
        val deadline = if (durationMs > 0L) started + durationMs else 0L

        while ((deadline == 0L || System.currentTimeMillis() < deadline) && !capture.stopped) {
            val masterText = fetchText(playlistUrl, capture)

            if (!masterText.trimStart().startsWith("#EXTM3U")) {
                return recordDirectStream(playlistUrl, output, durationMs, capture)
            }

            val mediaPlaylistUrl = if (masterText.contains("#EXT-X-STREAM-INF")) {
                val variantLine = masterText.lines()
                    .firstOrNull { !it.startsWith("#") && it.isNotBlank() }
                if (variantLine == null) { capture.sleep(2000L); continue }
                resolveUrl(playlistUrl, variantLine)
            } else {
                playlistUrl
            }

            val mediaText = if (mediaPlaylistUrl == playlistUrl) {
                masterText
            } else {
                fetchText(mediaPlaylistUrl, capture)
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

                written += downloadSegment(segmentUrl, output, deadline, capture)

                if ((deadline > 0 && System.currentTimeMillis() >= deadline) || capture.stopped) {
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
            if (waitMs > 0L && !capture.stopped) capture.sleep(waitMs)
        }

        output.flush()
        return written
    }

    private fun fetchText(url: String, capture: Capture): String {
        val conn = capture.open(URL(url).openConnection() as HttpURLConnection)
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
            capture.close(conn)
        }
    }

    private fun downloadSegment(url: String, output: OutputStream, deadline: Long = 0L, capture: Capture): Long {
        val conn = capture.open(URL(url).openConnection() as HttpURLConnection)
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
                while ((deadline == 0L || System.currentTimeMillis() < deadline) && !capture.stopped) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    written += n
                }
            }
        } catch (e: IOException) {
            // Closed by Capture.stop(): what was written so far stays; anything else is a real failure.
            if (!capture.stopped) throw e
        } finally {
            capture.close(conn)
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