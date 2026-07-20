package com.iptvapp.ui.onboarding

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.iptvapp.ui.home.HomeActivity
import com.iptvapp.ui.home.TvHomeActivity
import com.iptvapp.util.isLargeScreenDevice

/**
 * Entry point for the app's onboarding tour — kept as the same object/API the rest of the app
 * already calls (showIfNeeded from HomeActivity/TvHomeActivity), but now drives
 * SpotlightTourController against the real running UI instead of showing a static AlertDialog
 * carousel disconnected from the actual screen. Steps are platform-specific (phone tabs vs TV
 * sidebar buttons are completely different views), built by buildPhoneSteps/buildTvSteps below.
 */
object FeatureTourDialog {

    // Bump this whenever the tour content changes meaningfully — showIfNeeded() compares
    // against a stored version number (not just a shown/not-shown flag), so existing users
    // who already dismissed an older tour see the refreshed one once too, instead of it
    // silently staying stuck on whatever was current when they first installed.
    private const val TOUR_VERSION = 3

    const val EXTRA_START_TOUR = "start_feature_tour"

    /** The tour points at real Home-screen tabs/buttons, so it can't run in place over
     * Settings (or any other screen) — this finishes the calling activity and relaunches
     * Home with a flag telling it to start the tour immediately once it's back on screen. */
    fun startFromSettings(activity: AppCompatActivity) {
        val homeClass = if (activity.isLargeScreenDevice())
            TvHomeActivity::class.java else HomeActivity::class.java
        activity.startActivity(Intent(activity, homeClass).apply {
            putExtra(EXTRA_START_TOUR, true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        activity.finish()
    }

    fun showIfNeeded(activity: AppCompatActivity) {
        val prefs = activity.getSharedPreferences("tour_prefs", Context.MODE_PRIVATE)
        if (prefs.getInt("tour_shown_version", 0) >= TOUR_VERSION) return
        show(activity) {
            prefs.edit().putInt("tour_shown_version", TOUR_VERSION).apply()
        }
    }

    fun show(activity: AppCompatActivity, onDone: (() -> Unit)? = null) {
        val steps = when (activity) {
            is TvHomeActivity -> buildTvSteps()
            is HomeActivity -> buildPhoneSteps()
            else -> return
        }
        SpotlightTourController.start(activity, steps, onDone)
    }

    private fun buildPhoneSteps(): List<SpotlightStep> = listOf(
        SpotlightStep(
            "⭐", "Favorites",
            "Long-press any channel to favorite it, or check its recent reliability (e.g. \"7/10 succeeded recently\") right from that same menu.",
            resolveTarget = { a -> (a as? HomeActivity)?.binding?.tabLayout?.getTabAt(HomeActivity.TAB_FAVORITES)?.view },
            onBeforeShow = { a -> (a as? HomeActivity)?.binding?.tabLayout?.getTabAt(HomeActivity.TAB_FAVORITES)?.select() }
        ),
        SpotlightStep(
            "▶", "Movies",
            "Browse movies here — tap the sort button in the top bar to reorder by rating, year, or recently added, and titles you've started watching automatically float to the top.",
            resolveTarget = { a -> (a as? HomeActivity)?.binding?.tabLayout?.getTabAt(HomeActivity.TAB_MOVIES)?.view },
            onBeforeShow = { a -> (a as? HomeActivity)?.binding?.tabLayout?.getTabAt(HomeActivity.TAB_MOVIES)?.select() }
        ),
        SpotlightStep(
            "↕", "Sort Movies & Series",
            "Tap here to reorder the current tab by rating, year, or recently added.",
            resolveTarget = { a ->
                (a as? HomeActivity)?.binding?.btnVodSort?.takeIf { it.visibility == android.view.View.VISIBLE }
            },
            onBeforeShow = { a -> (a as? HomeActivity)?.binding?.tabLayout?.getTabAt(HomeActivity.TAB_MOVIES)?.select() }
        ),
        SpotlightStep(
            "📅", "Guide",
            "Shows what's on now and next per channel. Long-press \"What's On\" for a single feed of what's coming up across all your favorites at once.",
            resolveTarget = { a -> (a as? HomeActivity)?.binding?.tabLayout?.getTabAt(HomeActivity.TAB_GUIDE)?.view },
            onBeforeShow = { a -> (a as? HomeActivity)?.binding?.tabLayout?.getTabAt(HomeActivity.TAB_GUIDE)?.select() }
        ),
        SpotlightStep(
            "▶", "Mini Player",
            "Keep browsing while a stream plays here — tap it to go fullscreen, and resuming picks up right where you left off.",
            resolveTarget = { a ->
                (a as? HomeActivity)?.binding?.miniPlayerContainer?.takeIf { it.visibility == android.view.View.VISIBLE }
            }
        )
    )

    private fun buildTvSteps(): List<SpotlightStep> = listOf(
        SpotlightStep(
            "⭐", "Favorites",
            "Long-press OK on any channel to favorite it, or check its recent reliability right from that same menu.",
            resolveTarget = { a -> (a as? TvHomeActivity)?.binding?.btnTvFavorites }
        ),
        SpotlightStep(
            "🎬", "Movies",
            "Browse movies here — titles you've started watching automatically float to the top of the list.",
            resolveTarget = { a -> (a as? TvHomeActivity)?.binding?.btnTvMovies }
        ),
        SpotlightStep(
            "📺", "Series",
            "Browse series and resume any episode right where you left off, even across devices if you use Sync.",
            resolveTarget = { a -> (a as? TvHomeActivity)?.binding?.btnTvSeries }
        ),
        SpotlightStep(
            "📅", "Guide",
            "Shows what's on now and next per channel, with day-paging and search.",
            resolveTarget = { a -> (a as? TvHomeActivity)?.binding?.btnTvGuide }
        ),
        SpotlightStep(
            "⚙", "Settings",
            "Use \"🔎 Find in Settings\" here to jump straight to any setting instead of hunting through sections.",
            resolveTarget = { a -> (a as? TvHomeActivity)?.binding?.btnTvSettings }
        )
    )
}
