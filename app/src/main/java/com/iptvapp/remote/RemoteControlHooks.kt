package com.iptvapp.remote

import android.app.AlertDialog
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The TV screens' side of the phone remote: whichever of them is in front takes the phone's
 * commands and shows the pairing code. Collected only while resumed, so a command reaches exactly
 * one screen — the full-screen player when it's up, the TV home screen otherwise.
 */
object RemoteControlHooks {

    /** [onTune] plays a channel by name; button presses are handled here, as key presses. */
    fun attach(activity: ComponentActivity, onTune: suspend (String) -> Unit) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    RemoteControlServer.commands.collect { command ->
                        when (command) {
                            is RemoteControlServer.Command.Tune -> onTune(command.name)
                            is RemoteControlServer.Command.Key -> pressKey(activity, command.keyCode)
                        }
                    }
                }
                var dialog: AlertDialog? = null
                try {
                    RemoteControlServer.pairingCode.collect { code ->
                        dialog?.dismiss()
                        dialog = null
                        if (code == null) return@collect
                        val codeView = TextView(activity).apply {
                            text = code
                            textSize = 56f
                            letterSpacing = 0.3f
                            gravity = Gravity.CENTER
                            setTextColor(Color.WHITE)
                            setPadding(0, 24, 0, 24)
                        }
                        dialog = AlertDialog.Builder(activity)
                            .setTitle("Pair your phone")
                            .setMessage("Enter this code on your phone:")
                            .setView(codeView)
                            .setPositiveButton("Cancel") { _, _ -> RemoteControlServer.cancelPairing() }
                            .setOnCancelListener { RemoteControlServer.cancelPairing() }
                            .show()
                        launch {
                            delay(RemoteControlServer.CODE_TTL_MS)
                            if (RemoteControlServer.pairingCode.value == code) RemoteControlServer.cancelPairing()
                        }
                    }
                } finally {
                    dialog?.dismiss()
                }
            }
        }
    }

    /** Presses [keyCode] in whichever of the app's windows has focus — a dialog or menu open over
     * the screen included — exactly as the TV remote would. (Apps can't inject real key presses,
     * even into their own windows, so the key is handed to that window's root view.) */
    private fun pressKey(activity: ComponentActivity, keyCode: Int) {
        val target = focusedRootView() ?: activity.window.decorView
        val t = SystemClock.uptimeMillis()
        target.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        target.dispatchKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** The app window with focus, from the window manager's own list of the app's windows; null
     * when that can't be read, and the screen gets the key. */
    @Suppress("UNCHECKED_CAST", "PrivateApi", "DiscouragedPrivateApi")
    private fun focusedRootView(): View? = try {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val instance = wmg.getMethod("getInstance").invoke(null)
        val views = wmg.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>
        views.lastOrNull { it.hasWindowFocus() }
    } catch (e: Throwable) {
        null
    }
}
