package com.iptvapp.util

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.flow.first

object ThemeUtils {
    /** Forces a pure-black (#000000) background for OLED screens when the user has enabled
     * it in Display settings. Setting the root's background alone left almost every screen
     * looking unchanged, since nearly all of this app's layouts paint their own explicit
     * dark-gray "chrome" colors (cards, panels, sidebars — #0F0F0F/#111111/#141414/#161616/
     * #1A1A1A/#202020/#222222) directly on top of the root. This walks the view tree and
     * flattens any near-black neutral gray it finds to pure black too, while leaving real
     * colors (accent blue, favorite-star red/gray, etc.) untouched.
     *
     * suspend rather than a runBlocking DataStore read — this used to block onCreate's main
     * thread on every single launch (of every Activity that called it) for a synchronous disk
     * read before the first frame drew. Callers now launch this from their own coroutine scope;
     * the tree-walk itself still has to run on the main thread (it mutates Views), it's just no
     * longer blocking on the preference read to get there. */
    suspend fun applyAmoledIfEnabled(rootView: View, prefs: PreferencesManager) {
        val enabled = prefs.amoledBlack.first()
        if (!enabled) return
        rootView.setBackgroundColor(Color.BLACK)
        blackenNearBlackBackgrounds(rootView)
    }

    private fun blackenNearBlackBackgrounds(view: View) {
        (view.background as? ColorDrawable)?.let { drawable ->
            if (isNearBlackGray(drawable.color)) view.setBackgroundColor(Color.BLACK)
        }
        if (view is CardView) {
            val current = view.cardBackgroundColor.defaultColor
            if (isNearBlackGray(current)) view.setCardBackgroundColor(Color.BLACK)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) blackenNearBlackBackgrounds(view.getChildAt(i))
        }
    }

    // Roughly-neutral dark gray (R/G/B close together, all quite low) — matches this app's
    // chrome colors without touching real accent/status colors.
    private fun isNearBlackGray(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val maxC = maxOf(r, g, b); val minC = minOf(r, g, b)
        return maxC in 1..40 && (maxC - minC) <= 10
    }
}
