package com.iptvapp.util

import android.graphics.Color
import android.view.View
import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object ThemeUtils {
    /** Forces a pure-black (#000000) root background for OLED screens when the user has
     * enabled it in Display settings; otherwise leaves the layout's own background untouched. */
    fun applyAmoledIfEnabled(rootView: View, prefs: PreferencesManager) {
        val enabled = runBlocking { prefs.amoledBlack.first() }
        if (enabled) rootView.setBackgroundColor(Color.BLACK)
    }
}
