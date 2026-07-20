package com.iptvapp.ui.onboarding

import android.app.Activity
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.iptvapp.R

/**
 * Drives a sequence of [SpotlightStep]s over the real running Activity UI — dims everything
 * except a punched-out hole around each step's target view, with a caption card positioned
 * above or below the hole (whichever side has more room) and Next/Skip/Back controls.
 *
 * Unlike the old FeatureTourDialog (a static AlertDialog carousel with no connection to the
 * actual screen), this overlays the *real* activity so each step visibly points at the exact
 * button/tab/tile it's describing. Steps whose target isn't resolvable right now (e.g. a tab
 * that's part of a different section) are skipped rather than shown pointing at nothing —
 * see [SpotlightStep.resolveTarget].
 */
data class SpotlightStep(
    val icon: String,
    val title: String,
    val desc: String,
    /** Returns the current on-screen target view, or null if not applicable/visible right now
     * (e.g. this step describes a phone-only control being run on TV, or a view that's
     * temporarily gone/0-sized). A null result causes this step to be skipped. */
    val resolveTarget: (Activity) -> View?,
    /** Runs once, only when this step is about to display, e.g. switching to the tab/section
     * that contains the target view so it's actually laid out and visible on screen. */
    val onBeforeShow: ((Activity) -> Unit)? = null
)

object SpotlightTourController {

    fun start(activity: Activity, steps: List<SpotlightStep>, onDone: (() -> Unit)? = null) {
        val root = activity.window.decorView as? ViewGroup ?: return
        val contentRoot = root.findViewById<ViewGroup>(android.R.id.content) ?: root

        val overlay = SpotlightOverlayView(activity)
        val captionView = LayoutInflater.from(activity).inflate(R.layout.view_tour_caption, contentRoot, false)
        val container = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(captionView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        contentRoot.addView(container)

        val tvIcon  = captionView.findViewById<TextView>(R.id.tvTourIcon)
        val tvTitle = captionView.findViewById<TextView>(R.id.tvTourTitle)
        val tvDesc  = captionView.findViewById<TextView>(R.id.tvTourDesc)
        val tvStep  = captionView.findViewById<TextView>(R.id.tvTourStep)
        val btnSkip = captionView.findViewById<android.widget.Button>(R.id.btnTourSkip)
        val btnNext = captionView.findViewById<android.widget.Button>(R.id.btnTourNext)

        val density = activity.resources.displayMetrics.density
        val holePadding = 10 * density
        val holeRadius = 14 * density
        val captionMargin = 16 * density

        var index = -1

        fun finish() {
            contentRoot.removeView(container)
            onDone?.invoke()
        }

        fun showStep(i: Int) {
            if (i >= steps.size) { finish(); return }
            val step = steps[i]
            step.onBeforeShow?.invoke(activity)
            // Target views resolved after onBeforeShow's section switch may not be laid out
            // until the next frame — post() to give layout a pass before measuring bounds.
            captionView.post {
                val target = step.resolveTarget(activity)
                if (target == null || target.width == 0 || target.height == 0) {
                    // Nothing to point at right now (wrong platform/section) — skip forward
                    // rather than show a spotlight hole pointing at nothing.
                    index = i
                    showStep(i + 1)
                    return@post
                }
                index = i
                val rect = Rect()
                target.getGlobalVisibleRect(rect)
                val contentOffset = IntArray(2).also { contentRoot.getLocationOnScreen(it) }
                rect.offset(-contentOffset[0], -contentOffset[1])
                overlay.moveHoleTo(rect, holePadding, holeRadius)

                tvIcon.text = step.icon
                tvTitle.text = step.title
                tvDesc.text = step.desc
                tvStep.text = "${i + 1} of ${steps.size}"
                btnNext.text = if (i == steps.lastIndex) "Done" else "Next"

                captionView.post {
                    val lp = captionView.layoutParams as FrameLayout.LayoutParams
                    val spaceBelow = contentRoot.height - (rect.bottom + holePadding)
                    val spaceAbove = rect.top - holePadding
                    if (spaceBelow >= captionView.height + captionMargin || spaceBelow >= spaceAbove) {
                        lp.topMargin = (rect.bottom + holePadding + captionMargin).toInt()
                        lp.gravity = Gravity.TOP or Gravity.START
                    } else {
                        lp.topMargin = (rect.top - holePadding - captionMargin - captionView.height).toInt()
                        lp.gravity = Gravity.TOP or Gravity.START
                    }
                    val maxLeft = contentRoot.width - captionView.width - (16 * density).toInt()
                    lp.leftMargin = ((rect.left + rect.right) / 2 - captionView.width / 2)
                        .coerceIn((16 * density).toInt(), maxLeft.coerceAtLeast((16 * density).toInt()))
                    lp.topMargin = lp.topMargin.coerceIn(
                        (16 * density).toInt(),
                        (contentRoot.height - captionView.height - 16 * density).toInt().coerceAtLeast((16 * density).toInt())
                    )
                    captionView.layoutParams = lp
                }
            }
        }

        btnNext.setOnClickListener { showStep(index + 1) }
        btnSkip.setOnClickListener { finish() }

        showStep(0)
    }
}
