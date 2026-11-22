package com.iptvapp.ui

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.util.AccessibilityContextWrapper

/**
 * Every MKTV screen. It is built at the text size chosen in Settings → Accessibility, and
 * remembers which settings it was built with ([builtWith]) so AccessibilityEngine can rebuild it
 * once when those change while it was in the background.
 */
abstract class MktvActivity : AppCompatActivity() {

    /**
     * False on screens that are playing something (player, multi-view, mosaic): rebuilding them
     * would lose the channel and position, so they get a new text size the next time they open.
     */
    open val rebuildsForTextSize: Boolean get() = true

    /** AccessibilitySettings.screenSignature this screen was built with. */
    var builtWith: String? = null
        private set

    override fun attachBaseContext(newBase: Context) {
        val settings = try {
            PreferencesManager(newBase.applicationContext ?: newBase).accessibilityNow()
        } catch (_: Exception) {
            null
        }
        builtWith = settings?.screenSignature
        super.attachBaseContext(AccessibilityContextWrapper.wrap(newBase, settings?.textSize?.scale ?: 1.0f))
    }
}
