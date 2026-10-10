package com.iptvapp.ui.recordings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.iptvapp.service.RecordingDeps
import com.iptvapp.service.RecordingRecovery
import com.iptvapp.ui.guide.ChannelTimerScheduler
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms recordings and reminders when their alarms may be gone: after a reboot, after an app
 * update, and when the user grants or revokes "Alarms & reminders" (exact alarms are then set as
 * exact, or fall back to inexact). Exported only because the system delivers these broadcasts;
 * all three are protected (only the system can send them), any other action is ignored, and
 * nothing is read from the intent — recovery only re-arms what is already in the database.
 * App start runs the same recovery (IptvApplication), which covers force-stop.
 */
class RecordingRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val app = context.applicationContext
        val db = EntryPointAccessors.fromApplication(app, RecordingDeps::class.java).database()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { RecordingRecovery.run(app, db) }
                runCatching { ChannelTimerScheduler.recover(app) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )
    }
}
