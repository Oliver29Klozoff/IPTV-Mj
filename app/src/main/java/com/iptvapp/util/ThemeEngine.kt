package com.iptvapp.util

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.R
import com.iptvapp.data.theme.AppBaseTheme

/**
 * Paints the base theme (Settings → Appearance → Base theme) onto a screen in place, with no
 * restart. Layouts paint their own near-black chrome (#000000 to #282828); each such panel is
 * given the theme's colour for its role (AppBaseTheme.surfaceFor), and its own colour is kept in
 * a tag so a later theme starts from the original again. A RecyclerView also themes each row as
 * it is attached, so rows that scroll in or load later match. Video surfaces and the player view
 * stay black, and a subtree tagged [R.id.theme_engine_skip] (a theme preview) is left alone.
 */
object ThemeEngine {

    /** The window, status and navigation bars and everything under [root]. */
    fun apply(activity: Activity, root: View, theme: AppBaseTheme) {
        applyWindowTheme(activity, theme)
        applySurfaceTheme(root, theme)
    }

    @Suppress("DEPRECATION")
    fun applyWindowTheme(activity: Activity, theme: AppBaseTheme) {
        val window = activity.window
        window.decorView.setBackgroundColor(theme.backgroundColor)
        // Ignored where Android draws edge to edge (the decor colour shows through there).
        window.statusBarColor = theme.backgroundColor
        window.navigationBarColor = theme.backgroundColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    /** [isScreen]: [root] is the whole screen (always the theme's background), not a part of it. */
    fun applySurfaceTheme(root: View, theme: AppBaseTheme, isScreen: Boolean = true) {
        paint(root, theme, isRoot = isScreen)
    }

    private fun paint(view: View, theme: AppBaseTheme, isRoot: Boolean = false) {
        if (view.getTag(R.id.theme_engine_skip) == true) return
        if (view is SurfaceView || view is TextureView) return
        if (view.javaClass.name.endsWith("PlayerView")) {
            // Letterbox bars beside the picture stay black like a TV's, not the theme's colour
            // the panel around the player gets.
            if (view.background == null) view.setBackgroundColor(android.graphics.Color.BLACK)
            return
        }
        // The root is the screen itself, whatever its own colour.
        val thin = !isRoot && isThin(view)
        when (val bg = view.background) {
            is ColorDrawable -> themed(view, bg.color, theme, thin, isRoot)?.let { view.setBackgroundColor(it) }
            is GradientDrawable -> bg.color?.defaultColor?.let { current ->
                themed(view, current, theme, thin, isRoot)?.let { bg.mutate(); (bg as GradientDrawable).setColor(it) }
            }
            null -> if (isRoot) view.setBackgroundColor(theme.backgroundColor)
        }
        if (view is CardView) {
            themed(view, view.cardBackgroundColor.defaultColor, theme, thin, isRoot)?.let { view.setCardBackgroundColor(it) }
        }
        if (view is RecyclerView) watchRows(view, theme)
        if (view is ViewGroup) for (i in 0 until view.childCount) paint(view.getChildAt(i), theme)
    }

    /**
     * The colour to give [view], whose background is [current] now. The tag remembers the
     * layout's own colour and the one last painted: if the view still shows what was painted, the
     * original is themed again; if something repainted it since (a row rebound, a focus change),
     * that new colour is the original from now on.
     */
    private fun themed(view: View, current: Int, theme: AppBaseTheme, thin: Boolean, isRoot: Boolean): Int? {
        val saved = view.getTag(R.id.theme_engine_original) as? Pair<*, *>
        val original = if (saved != null && saved.second == current) saved.first as Int else current
        val target = if (isRoot) theme.surfaceFor(original)?.let { theme.backgroundColor } else theme.surfaceFor(original, thin)
        if (target == null) {
            view.setTag(R.id.theme_engine_original, null)
            return null
        }
        view.setTag(R.id.theme_engine_original, original to target)
        return target.takeIf { it != current }
    }

    private fun isThin(view: View): Boolean {
        val limit = 3 * view.resources.displayMetrics.density
        val lp = view.layoutParams ?: return false
        return (lp.height in 1..limit.toInt()) || (lp.width in 1..limit.toInt())
    }

    /**
     * Rows of [list] are themed as they attach (scrolled in, loaded later), and the rows on
     * screen again after each layout pass, since a row rebound in place (a refresh, a changed
     * item) can paint its layout colour back without detaching. Repainting a row already in the
     * theme only compares tags.
     */
    private fun watchRows(list: RecyclerView, theme: AppBaseTheme) {
        val watching = list.getTag(R.id.theme_engine_theme) != null
        list.setTag(R.id.theme_engine_theme, theme)
        if (watching) return
        fun current() = list.getTag(R.id.theme_engine_theme) as? AppBaseTheme
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(child: View) {
                current()?.let { paint(child, it) }
            }
            override fun onChildViewDetachedFromWindow(child: View) {}
        })
        // Registered only while the list is on screen, so recycled row lists leave none behind.
        val relayout = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val t = current() ?: return@OnGlobalLayoutListener
            for (i in 0 until list.childCount) paint(list.getChildAt(i), t)
        }
        if (list.isAttachedToWindow) list.viewTreeObserver.addOnGlobalLayoutListener(relayout)
        list.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.viewTreeObserver.addOnGlobalLayoutListener(relayout) }
            override fun onViewDetachedFromWindow(v: View) { v.viewTreeObserver.removeOnGlobalLayoutListener(relayout) }
        })
    }
}
