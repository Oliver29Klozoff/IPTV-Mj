package com.iptvapp.ui.smart

import android.view.View

/** Puts D-pad focus back on the row that opened a dialog, once that row is still on screen. */
object DialogFocus {
    fun restore(anchor: View?) {
        val target = anchor ?: return
        target.post {
            if (target.isAttachedToWindow) target.requestFocus()
        }
    }
}
