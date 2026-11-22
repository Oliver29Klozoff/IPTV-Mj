package com.iptvapp.util

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.R
import com.iptvapp.data.accessibility.AccessibilitySettings
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.ui.MktvActivity

/**
 * Settings → Accessibility on every screen, as each comes forward:
 *  - a screen built at another text size (changed while it was in the background) is rebuilt once;
 *  - High-contrast focus puts a bold white ring with a dark edge on whatever the remote is on,
 *    over the screen's own focus look, so it shows on any picture or colour;
 *  - Senior Mode gives buttons, switches and list rows at least 52 dp of height on touch screens
 *    (phones, car boxes), never on a remote-only TV.
 */
object AccessibilityEngine {

    fun install(app: Application, prefs: PreferencesManager) {
        val touch = app.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val settings = prefs.accessibilityNow()
                if (needsRebuild(activity, settings)) {
                    activity.recreate()
                    return
                }
                watchFocus(activity, prefs)
                refreshFocus(activity, settings.highContrastFocus)
                if (touch && settings.minTouchTargetDp > 0) {
                    val content = activity.findViewById<View>(android.R.id.content) ?: return
                    enlargeTouchTargets(content, (settings.minTouchTargetDp * activity.resources.displayMetrics.density).toInt())
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** The ring on, or off, what [activity] has focused now (the setting just changed). */
    fun refreshFocus(activity: Activity, enabled: Boolean) {
        val focused = activity.currentFocus ?: return
        if (enabled && !focused.isInTouchMode) FocusRing.add(focused) else FocusRing.remove(focused)
    }

    /** Built with other settings, and not playing in a corner where a rebuild would interrupt. */
    internal fun needsRebuild(activity: Activity, settings: AccessibilitySettings): Boolean {
        if (activity !is MktvActivity || !activity.rebuildsForTextSize) return false
        val built = activity.builtWith ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) return false
        return built != settings.screenSignature
    }

    private fun watchFocus(activity: Activity, prefs: PreferencesManager) {
        val decor = activity.window?.decorView ?: return
        if (!watchWindow(decor, prefs)) return
        // A dialog or menu opens in a window of its own and takes the focus from the screen's:
        // watch it too (Android 10 and later can list the app's windows; older ones go without).
        decor.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) decor.post {
                for (window in android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    if (window === decor || !watchWindow(window, prefs)) continue
                    window.findFocus()?.let { if (!it.isInTouchMode && prefs.highContrastFocusNow()) FocusRing.add(it) }
                }
            }
        }
    }

    /** Rings follow the focus in the window under [decor]; false if it was already watched. */
    private fun watchWindow(decor: View, prefs: PreferencesManager): Boolean {
        if (decor.getTag(R.id.a11y_focus_watch) != null) return false
        decor.setTag(R.id.a11y_focus_watch, true)
        decor.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            old?.let { FocusRing.remove(it) }
            if (new != null && !new.isInTouchMode && prefs.highContrastFocusNow()) FocusRing.add(new)
        }
        return true
    }

    /** Buttons, switches, chips and clickable list rows that size to their content grow to [minPx]. */
    internal fun enlargeTouchTargets(view: View, minPx: Int) {
        when (view) {
            is RecyclerView -> watchRows(view, minPx)
            is ImageButton -> {
                grow(view, minPx)
                if (view.layoutParams?.width == ViewGroup.LayoutParams.WRAP_CONTENT && view.minimumWidth < minPx) view.minimumWidth = minPx
            }
            is Button, is CompoundButton -> grow(view, minPx)
        }
        if (view is ViewGroup && view !is RecyclerView) {
            for (i in 0 until view.childCount) enlargeTouchTargets(view.getChildAt(i), minPx)
        }
    }

    private fun grow(view: View, minPx: Int) {
        if (view.layoutParams?.height != ViewGroup.LayoutParams.WRAP_CONTENT) return
        if (view is TextView) {
            if (view.minHeight < minPx) view.minHeight = minPx
        } else if (view.minimumHeight < minPx) {
            view.minimumHeight = minPx
        }
    }

    private fun watchRows(list: RecyclerView, minPx: Int) {
        if (list.getTag(R.id.a11y_rows_watch) != null) return
        list.setTag(R.id.a11y_rows_watch, true)
        val enlarge = { row: View ->
            if (row.isClickable) grow(row, minPx)
            enlargeTouchTargets(row, minPx)
        }
        for (i in 0 until list.childCount) enlarge(list.getChildAt(i))
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = enlarge(view)
            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    /** The bold ring, drawn in the view's overlay so it never changes the view's own background. */
    internal object FocusRing {
        private const val EDGE_DP = 2f
        private const val RADIUS_DP = 8f

        fun add(view: View) {
            if (view.getTag(R.id.a11y_focus_ring) != null) return
            // Something filling the screen (the video itself) is not outlined.
            val root = view.rootView
            if (root != null && root.width > 0 && view.width >= root.width * 0.9f && view.height >= root.height * 0.9f) return
            val ring = ring(view)
            ring.setBounds(0, 0, view.width, view.height)
            view.overlay.add(ring)
            val relayout = View.OnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> ring.setBounds(0, 0, r - l, b - t); v.invalidate() }
            view.addOnLayoutChangeListener(relayout)
            view.setTag(R.id.a11y_focus_ring, ring)
            view.setTag(R.id.a11y_focus_ring_layout, relayout)
        }

        fun remove(view: View) {
            (view.getTag(R.id.a11y_focus_ring) as? Drawable)?.let { view.overlay.remove(it) }
            (view.getTag(R.id.a11y_focus_ring_layout) as? View.OnLayoutChangeListener)?.let { view.removeOnLayoutChangeListener(it) }
            view.setTag(R.id.a11y_focus_ring, null)
            view.setTag(R.id.a11y_focus_ring_layout, null)
        }

        fun isShowing(view: View): Boolean = view.getTag(R.id.a11y_focus_ring) != null

        private fun ring(view: View): Drawable {
            val d = view.resources.displayMetrics.density
            val stroke = (AccessibilitySettings.HIGH_CONTRAST_FOCUS_STROKE_DP * d).toInt().coerceAtLeast(1)
            val edge = (EDGE_DP * d).toInt().coerceAtLeast(1)
            // A dark edge outside the white, so the ring shows on white posters as well as black.
            val shadow = GradientDrawable().apply {
                cornerRadius = RADIUS_DP * d
                setColor(Color.TRANSPARENT)
                setStroke(edge, 0xB3000000.toInt())
            }
            val white = GradientDrawable().apply {
                cornerRadius = (RADIUS_DP - EDGE_DP) * d
                setColor(Color.TRANSPARENT)
                setStroke(stroke, Color.WHITE)
            }
            return LayerDrawable(arrayOf(shadow, white)).apply { setLayerInset(1, edge, edge, edge, edge) }
        }
    }
}
