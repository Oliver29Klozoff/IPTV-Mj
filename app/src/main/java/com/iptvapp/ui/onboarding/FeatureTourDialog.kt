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

    private val steps = listOf(
        Step("📺", "Welcome to MKTV", "Stream live TV from your IPTV provider. Browse channels, movies, series, and more."),
        Step("⭐", "Favorites", "Long-press any channel to favorite it. Your favorites load first every time you open the app."),
        Step("📅", "TV Guide", "Tap the Guide tab to see what's on now and next. Tap any show to replay it (if your provider supports catch-up)."),
        Step("▶", "Mini Player", "Switch categories while still watching. The mini player keeps your stream running in the background."),
        Step("🔴", "Buffer Health", "The dot in the player shows stream quality: green = good, yellow = buffering, red = weak signal."),
        Step("⚙", "Settings", "Back up your favorites, auto-refresh EPG, switch DNS providers, and check for updates.")
    )

    fun showIfNeeded(activity: AppCompatActivity) {
        val shown = activity.getSharedPreferences("tour_prefs", Context.MODE_PRIVATE)
            .getBoolean("tour_shown", false)
        if (shown) return
        show(activity) {
            activity.getSharedPreferences("tour_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("tour_shown", true).apply()
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
