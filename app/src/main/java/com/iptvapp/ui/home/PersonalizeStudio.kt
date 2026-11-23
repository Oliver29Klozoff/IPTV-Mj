package com.iptvapp.ui.home

import android.view.View
import com.iptvapp.databinding.ActivityHomeCustomizeBinding

/**
 * Personalize MKTV (HomeCustomizeActivity): its four tabs, which panel each shows, and the line
 * under the title. Kept apart from the screen so the tests can check them.
 */
object PersonalizeStudio {

    enum class Tab {
        /** Viewing styles, Home's rows, what MKTV opens on, the TV mini player and sidebar. */
        LAYOUT,
        /** Base theme, accent and card size, with a live sample card. */
        LOOK,
        /** Channel numbers and typing a number on the remote. */
        REMOTE,
        /** Senior Mode and text size, and the household's profiles. */
        ACCESS_PROFILES;

        companion object {
            /** The tab an Intent asked for (EXTRA_INITIAL_TAB); anything unknown opens the first. */
            fun of(index: Int): Tab = entries.getOrElse(index) { LAYOUT }
        }
    }

    fun panels(b: ActivityHomeCustomizeBinding): List<View> =
        listOf(b.panelLayout, b.panelAppearance, b.panelRemote, b.panelAccessibilityProfiles)

    fun tabs(b: ActivityHomeCustomizeBinding): List<View> =
        listOf(b.tabLayout, b.tabAppearance, b.tabRemote, b.tabAccessibilityProfiles)

    /** Shows [tab]'s panel only and marks its tab as the one open (the others are hidden, not just covered). */
    fun show(b: ActivityHomeCustomizeBinding, tab: Tab) {
        panels(b).forEachIndexed { i, panel -> panel.visibility = if (i == tab.ordinal) View.VISIBLE else View.GONE }
        tabs(b).forEachIndexed { i, t -> t.isActivated = i == tab.ordinal }
    }

    /** The line under the title: "TV · Emjay · Classic Cable · OLED Black", skipping anything unknown. */
    fun summary(screen: String, profile: String?, persona: String?, theme: String?): String =
        listOf(screen, profile, persona, theme).filter { !it.isNullOrBlank() }.joinToString(" · ")
}
