package com.iptvapp.ui.guide

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.iptvapp.ui.home.HomeActivity
import com.iptvapp.ui.home.TvHomeActivity
import com.iptvapp.util.isLargeScreenDevice

// The notification opens this, not the home screen directly. Home is usually already the
// task root, and Android then brings that screen forward and drops the new extras. This
// activity is a fresh instance every tap, so it always receives the channel id, records it,
// and only then asks home to tune.
class ReminderTapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streamId = HomeActivity.streamIdFromJumpIntent(intent)
        if (streamId >= 0) {
            val serverIndex = intent.getIntExtra(HomeActivity.EXTRA_JUMP_SERVER_INDEX, -1)
            ReminderTune.mark(this, streamId, serverIndex)
            val home = if (isLargeScreenDevice()) TvHomeActivity::class.java else HomeActivity::class.java
            startActivity(Intent(this, home).apply {
                action = ACTION_TUNE
                data = Uri.parse("mktv://tune/$streamId?server=$serverIndex&t=${System.currentTimeMillis()}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(HomeActivity.EXTRA_JUMP_TO_STREAM_ID, streamId)
                putExtra(HomeActivity.EXTRA_JUMP_SERVER_INDEX, serverIndex)
            })
        }
        finish()
    }

    companion object {
        const val ACTION_TUNE = "com.iptvapp.TUNE_CHANNEL"
    }
}
