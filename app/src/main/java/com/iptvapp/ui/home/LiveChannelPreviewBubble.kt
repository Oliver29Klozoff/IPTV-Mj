package com.iptvapp.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Floating "peek" popup for the phone press-and-hold live preview — NOT used on TV, where the
 * D-pad focus preview embeds directly into the row's own thumbnail (see ChannelAdapter's
 * playerChannelPreview) without any obstruction problem, since focus doesn't require physical
 * contact with the screen.
 *
 * On phone, the thumbnail being held is exactly where a finger sits, so a preview rendered THERE
 * is invisible for the entire duration you're pressing it, and vanishes the instant you lift up
 * to look — the original v6.45-v6.49 design. This bubble instead renders in a small floating
 * window positioned above (or below, if too close to the top of the screen) the row being held,
 * clear of the touch point, so it's actually visible while your finger is still down.
 *
 * One shared popup + PlayerView for the whole app (mirrors LiveChannelPreviewPlayer's one-shared-
 * ExoPlayer design) — only one preview is ever active at a time regardless of which adapter
 * triggered it.
 */
object LiveChannelPreviewBubble {
    private const val WIDTH_DP = 260
    private const val HEIGHT_DP = 146
    private const val MARGIN_DP = 8

    private var popupWindow: PopupWindow? = null
    private var playerView: PlayerView? = null

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    private fun ensureViews(context: Context): Pair<PopupWindow, PlayerView> {
        popupWindow?.let { pw -> playerView?.let { pv -> return pw to pv } }
        val app = context.applicationContext
        val pv = PlayerView(app).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dpToPx(app, 8).toFloat()
                setStroke(dpToPx(app, 1), Color.parseColor("#444444"))
            }
            clipToOutline = true
        }
        val pw = PopupWindow(pv, dpToPx(app, WIDTH_DP), dpToPx(app, HEIGHT_DP)).apply {
            isTouchable = false
            isFocusable = false
            isOutsideTouchable = false
            // Elevation needs a non-null background on the PopupWindow itself (not just its
            // content view) to actually cast a shadow on API 21+.
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            elevation = dpToPx(app, 6).toFloat()
        }
        popupWindow = pw
        playerView = pv
        return pw to pv
    }

    /** Shows (or repositions) the bubble above [anchor], falling back to below it if there isn't
     * enough room above, and returns its PlayerView for LiveChannelPreviewPlayer to attach to. */
    fun playerViewFor(context: Context, anchor: View): PlayerView {
        val (popup, pv) = ensureViews(context)
        val widthPx = dpToPx(context, WIDTH_DP)
        val heightPx = dpToPx(context, HEIGHT_DP)
        val marginPx = dpToPx(context, MARGIN_DP)

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val anchorX = loc[0]
        val anchorTop = loc[1]
        val anchorBottom = loc[1] + anchor.height

        val screenWidth = anchor.resources.displayMetrics.widthPixels
        val x = anchorX.coerceIn(0, (screenWidth - widthPx).coerceAtLeast(0))
        val y = if (anchorTop - heightPx - marginPx >= 0) {
            anchorTop - heightPx - marginPx
        } else {
            anchorBottom + marginPx
        }

        if (popup.isShowing) {
            popup.update(x, y, widthPx, heightPx)
        } else {
            popup.width = widthPx
            popup.height = heightPx
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
        return pv
    }

    fun dismiss() {
        popupWindow?.let { if (it.isShowing) it.dismiss() }
    }
}
