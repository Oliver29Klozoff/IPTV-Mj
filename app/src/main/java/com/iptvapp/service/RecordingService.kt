package com.iptvapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotif(name), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotif(name))
        }

        job = scope.launch {
            if (recordingId != -1) database.recordingDao().updateStatus(recordingId, "RECORDING")
            val ok = runCatching {
                val file = File(path)
                file.parentFile?.mkdirs()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.connect()
                val input = conn.inputStream
                val output = file.outputStream()
                val buf = ByteArray(8192)
                val t0 = System.currentTimeMillis()
                while (durationMs == 0L || System.currentTimeMillis() - t0 < durationMs) {
                    val n = input.read(buf)
                    if (n == -1) break
                    output.write(buf, 0, n)
                }
                output.flush()
                output.close()
                input.close()
                conn.disconnect()
            }.isSuccess
            if (recordingId != -1) {
                database.recordingDao().updateStatus(recordingId, if (ok) "DONE" else "FAILED")
            }
            stopSelf()
        }
        return START_NOT_STICKY
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
