package com.iptvapp.remote

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * The TV's side of the phone remote, for every MKTV screen at once: whichever one is in front
 * takes the phone's button presses and asks about pairing — Settings, recordings and movie
 * pages included, not only the home screen and the player.
 */
object RemoteControlHooks {

    /** A screen that can put a channel on. Returns false when it can't right now (the player
     * showing a movie, say), and the TV home screen is brought back to do it instead. */
    interface Tuner {
        suspend fun remoteTune(name: String): Boolean
    }

    private var installed = false
    @Volatile private var front: WeakReference<Activity>? = null
    private var dialog: AlertDialog? = null
    private var expiryJob: Job? = null
    private val scope = MainScope()

    /** True while an MKTV screen is in front on the TV, so the phone can be told when it isn't. */
    val isInFront: Boolean get() = front?.get() != null

    /** Main thread, once (RemoteControlServer.start). */
    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                front = WeakReference(activity)
                showPairingNumber(RemoteControlServer.pairingNumber.value)
            }
            override fun onActivityPaused(activity: Activity) {
                if (front?.get() === activity) {
                    front = null
                    dismissDialog()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        scope.launch {
            RemoteControlServer.commands.collect { command ->
                when (command) {
                    // Not while the pairing question is up: only the TV's own remote may answer it,
                    // so an already paired phone can't let another one in.
                    is RemoteControlServer.Command.Key -> if (dialog == null) front?.get()?.let { pressKey(it, command.keyCode) }
                    is RemoteControlServer.Command.Tune -> tune(app, command.name)
                    is RemoteControlServer.Command.Home -> if (dialog == null) front?.get()?.let { activity ->
                        if (activity is com.iptvapp.ui.home.TvHomeActivity) {
                            // Movies / Series browsing are views inside this screen, not screens of their own.
                            activity.remoteHome()
                        } else {
                            activity.startActivity(
                                Intent(activity, com.iptvapp.ui.home.TvHomeActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    .putExtra(com.iptvapp.ui.home.TvHomeActivity.EXTRA_REMOTE_HOME, true)
                            )
                        }
                    }
                }
            }
        }
        scope.launch { RemoteControlServer.pairingNumber.collect { showPairingNumber(it) } }
    }

    private suspend fun tune(app: Application, name: String) {
        val activity = front?.get()
        if ((activity as? Tuner)?.remoteTune(name) == true) return
        // Back to the TV home screen (closing whatever is over it), which plays it on resuming.
        RemoteControlServer.pendingTune = name
        val context = activity ?: app
        context.startActivity(
            Intent(context, com.iptvapp.ui.home.TvHomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    // ── Pairing ─────────────────────────────────────────────────────────────

    /** Asks the person to compare the number with the phone's and pick Pair — with the TV's own
     * remote, so only someone in the room can let a phone in. */
    private fun showPairingNumber(prompt: RemoteControlServer.PairingPrompt?) {
        dismissDialog()
        if (prompt == null) return
        val number = prompt.number
        val activity = front?.get() ?: return
        val numberView = TextView(activity).apply {
            text = number
            textSize = 56f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 24)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle("Pair a phone?")
            .setMessage("Pair only if the phone shows this same number:")
            .setView(numberView)
            .setPositiveButton("Pair") { _, _ -> RemoteControlServer.answerPairing(prompt.id, true) }
            .setNegativeButton("Cancel") { _, _ -> RemoteControlServer.answerPairing(prompt.id, false) }
            .setOnCancelListener { RemoteControlServer.answerPairing(prompt.id, false) }
            .show()
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(RemoteControlServer.PAIRING_TTL_MS)
            if (RemoteControlServer.pairingNumber.value == prompt) RemoteControlServer.answerPairing(prompt.id, false, expired = true)
        }
    }

    private fun dismissDialog() {
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    // ── Buttons ─────────────────────────────────────────────────────────────

    /** Presses [keyCode] in whichever of the app's windows has focus — a dialog or menu open over
     * the screen included — as the TV remote would. Apps can't inject real key presses, even into
     * their own windows, so the key goes to that window's root view; an arrow nothing used then
     * moves focus, which is what the system does with a real one. */
    private fun pressKey(activity: Activity, keyCode: Int) {
        val root = focusedRootView() ?: activity.window.decorView
        val t = SystemClock.uptimeMillis()
        val used = root.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        root.dispatchKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
        if (used) return
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return
        }
        val focused = root.findFocus()
        if (focused == null) root.requestFocus(direction)
        else focused.focusSearch(direction)?.requestFocus(direction)
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
