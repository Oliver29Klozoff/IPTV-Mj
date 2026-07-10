package com.iptvapp.ui.onboarding

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.iptvapp.R

object FeatureTourDialog {

    private data class Step(val icon: String, val title: String, val desc: String)

    // Bump this whenever the tour content changes meaningfully — showIfNeeded() compares
    // against a stored version number (not just a shown/not-shown flag), so existing users
    // who already dismissed an older tour see the refreshed one once too, instead of it
    // silently staying stuck on whatever was current when they first installed.
    private const val TOUR_VERSION = 2

    private val steps = listOf(
        Step("📺", "Welcome to MKTV", "Stream live TV from your IPTV provider. Browse channels, movies, series, and more."),
        Step("⭐", "Favorites", "Long-press any channel to favorite it, or check its recent reliability (e.g. \"7/10 succeeded recently\") right from that same menu."),
        Step("📅", "TV Guide & Up Next", "The Guide tab shows what's on now and next per channel. On phone, long-press the \"What's On\" button for a single feed of what's coming up across all your favorites at once."),
        Step("▶", "Mini Player & Fullscreen", "Keep browsing while a stream plays in the mini player, or go fullscreen — resuming picks up right where you left off."),
        Step("🧭", "Landscape Navigation (phone)", "Rotate to landscape: tap Live, Categories, or Movies in the sidebar to browse categories, then tap one to drill into its channels. Tap the sidebar item again to go back."),
        Step("↕", "Sort Movies & Series", "Tap the sort button on the Movies or Series tab to reorder by rating, year, or recently added."),
        Step("🔵", "Smarter Buffering", "If a stream keeps stalling, Extra Buffering turns on automatically for future streams — no settings hunt required."),
        Step("🔗", "Trakt", "Connect your Trakt.tv account in Settings to automatically track movies and episodes you watch in fullscreen."),
        Step("⚙", "Settings Help", "Tap the \"?\" in Settings for a plain-language explanation of whatever section you're looking at — buffering, DNS, sync, and more.")
    )

    fun showIfNeeded(activity: AppCompatActivity) {
        val prefs = activity.getSharedPreferences("tour_prefs", Context.MODE_PRIVATE)
        if (prefs.getInt("tour_shown_version", 0) >= TOUR_VERSION) return
        show(activity) {
            prefs.edit().putInt("tour_shown_version", TOUR_VERSION).apply()
        }
    }

    fun show(activity: AppCompatActivity, onDone: (() -> Unit)? = null) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_feature_tour, null)
        val tvIcon  = view.findViewById<TextView>(R.id.tvTourIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvTourTitle)
        val tvDesc  = view.findViewById<TextView>(R.id.tvTourDesc)
        val tvStep  = view.findViewById<TextView>(R.id.tvTourStep)
        val btnBack = view.findViewById<Button>(R.id.btnTourBack)
        val btnNext = view.findViewById<Button>(R.id.btnTourNext)

        var current = 0

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun bind() {
            val s = steps[current]
            tvIcon.text  = s.icon
            tvTitle.text = s.title
            tvDesc.text  = s.desc
            tvStep.text  = "${current + 1} of ${steps.size}"
            btnBack.visibility = if (current == 0) View.INVISIBLE else View.VISIBLE
            btnNext.text = if (current == steps.lastIndex) "Done" else "Next"
        }

        btnBack.setOnClickListener { current--; bind() }
        btnNext.setOnClickListener {
            if (current == steps.lastIndex) {
                dialog.dismiss()
                onDone?.invoke()
            } else {
                current++
                bind()
            }
        }

        bind()
        dialog.show()
    }
}
