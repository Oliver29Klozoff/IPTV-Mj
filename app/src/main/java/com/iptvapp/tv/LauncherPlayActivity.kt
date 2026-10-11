package com.iptvapp.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.iptvapp.ui.home.TvHomeActivity

/**
 * Receives a tap on an MKTV card on the Android TV home screen (`mktv://play/...`).
 *
 * The home screen starts this, not TvHomeActivity directly: TvHomeActivity is singleTop, so a tap
 * while the full-screen player was open stacked a second home screen on top of it. From here,
 * home is brought forward with CLEAR_TOP | SINGLE_TOP, which closes the player above it and
 * hands the existing home screen the new card instead of making another one.
 *
 * Only checks the link's shape; TvHomeActivity checks it belongs to this install and the
 * provider signed in now before anything plays. Anything that isn't a card link is dropped.
 */
class LauncherPlayActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.dataString
        if (savedInstanceState == null && data != null &&
            LauncherLink.parse(intent?.action, data) !is LauncherLink.Parsed.Invalid
        ) {
            LauncherPending.mark(this, data)
            startActivity(Intent(this, TvHomeActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                this.data = Uri.parse(data)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
        finish()
    }
}
