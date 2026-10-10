package com.iptvapp.ui.recordings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.iptvapp.service.RecordingAlarms
import com.iptvapp.service.RecordingDeps
import com.iptvapp.service.RecordingNotifications
import com.iptvapp.service.RecordingService
import com.iptvapp.service.RecordingStarter
import com.iptvapp.service.RecordingUrls
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A recording's alarm. What to record, where, and for how long comes from the app's own records
 * (RecordingStarter), looked up by the recording id. Alarms set by v7.22 and earlier also carry the
 * stream URL they were scheduled with; it is kept as the recording's saved stream. Not exported.
 */
class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recordingId = intent.getIntExtra(RecordingService.EXTRA_RECORDING_ID, -1)
        if (recordingId < 0) return
        val resume = intent.getBooleanExtra(RecordingAlarms.EXTRA_RESUME, false)
        val app = context.applicationContext
        // An alarm set by v7.22 or earlier carries the stream it was scheduled for — the right
        // provider even if a slot has changed since. Keep it as this recording's saved stream.
        // (This receiver isn't exported: only the app's own alarms reach it.)
        intent.getStringExtra(RecordingService.EXTRA_STREAM_URL)?.takeIf { it.isNotBlank() }?.let { legacyUrl ->
            if (RecordingUrls.get(app, recordingId) == null) RecordingUrls.put(app, recordingId, legacyUrl)
        }
        val deps = EntryPointAccessors.fromApplication(app, RecordingDeps::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = deps.database()
                when (RecordingStarter.start(app, db, deps.repository(), recordingId, resume)) {
                    RecordingStarter.Outcome.NOT_ALLOWED -> db.recordingDao().getById(recordingId)?.let {
                        RecordingNotifications.postTapToStart(app, it.id, it.channelName, resume)
                    }
                    RecordingStarter.Outcome.MISSED ->
                        db.recordingDao().getById(recordingId)?.let { RecordingNotifications.postMissed(app, it) }
                    else -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }
}
