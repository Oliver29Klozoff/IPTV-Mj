package com.iptvapp.data.home

import androidx.annotation.StringRes
import com.iptvapp.R
import com.iptvapp.data.home.HomeLayout.Builtin
import com.iptvapp.data.home.HomeLayout.StartScreen
import com.iptvapp.data.home.TvSidebar.Entry
import com.iptvapp.data.home.TvSidebar.SidebarItem

/**
 * A viewing style picked in one go at the top of Customize Home. A preset sets the start screen,
 * Home's rows and, on a TV, the Home tab, the sidebar and the mini player all at once
 * (HomeLayoutStore.applyPersona). [CUSTOM] is whatever the viewer has set by hand: it is the
 * default, so an upgrade changes nothing, and any change made by hand afterwards returns to it
 * (PreferencesManager clears the saved persona whenever one of those settings changes).
 */
enum class ViewingPersona(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val tvDescRes: Int,
    @StringRes val phoneDescRes: Int,
    /** A short glyph for the card; MKTV has no icon set for these. */
    val badge: String,
) {
    CLASSIC_CABLE("classic_cable", R.string.persona_classic_cable_title, R.string.persona_classic_cable_desc_tv, R.string.persona_classic_cable_desc_phone, "📺"),
    CINEMA_HUB("cinema_hub", R.string.persona_cinema_hub_title, R.string.persona_cinema_hub_desc_tv, R.string.persona_cinema_hub_desc_phone, "🎬"),
    SPORTS_BAR("sports_bar", R.string.persona_sports_bar_title, R.string.persona_sports_bar_desc_tv, R.string.persona_sports_bar_desc_phone, "🏆"),
    CUSTOM("custom", R.string.persona_custom_title, R.string.persona_custom_desc, R.string.persona_custom_desc, "⚙");

    companion object {
        fun fromId(id: String?): ViewingPersona = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CUSTOM
    }
}

/**
 * What each preset sets. Only sidebar entries and Home rows that exist in MKTV are named; anything
 * a preset doesn't name keeps its place after the named ones, and keeps whether it shows unless
 * the preset hides it. Rows the viewer made are never removed.
 */
object PersonaPresets {

    data class Preset(
        val persona: ViewingPersona,
        val miniMode: TvHomeMiniMode,
        /** The TV's start; the phone has no full-screen start, so it uses [phoneStart]. */
        val tvStart: StartScreen,
        val phoneStart: StartScreen,
        /** TV sidebar's Home (HomeLayout.showHomeTab). */
        val showHomeTab: Boolean,
        /** Sidebar entries to put first, in this order; Settings stays pinned below the list. */
        val sidebarFirst: List<String>,
        val sidebarHidden: Set<String>,
        /** Home rows to put first, in this order, shown. */
        val rowsFirst: List<Builtin>,
        val rowsHidden: Set<Builtin>,
    )

    private val vodRows = setOf(Builtin.RECOMMENDED, Builtin.RECENT_MOVIES, Builtin.FAVORITE_MOVIES, Builtin.FAVORITE_SERIES, Builtin.NEW_EPISODES)

    val CLASSIC_CABLE = Preset(
        persona = ViewingPersona.CLASSIC_CABLE,
        miniMode = TvHomeMiniMode.OFF,
        tvStart = StartScreen.FULLSCREEN_LIVE,
        phoneStart = StartScreen.LIVE,
        showHomeTab = false,
        sidebarFirst = listOf(Entry.LIVE, Entry.GUIDE, Entry.FAVORITES, Entry.RECORDINGS, Entry.MOVIES, Entry.SERIES).map { it.id },
        sidebarHidden = emptySet(),
        rowsFirst = listOf(Builtin.ON_NOW, Builtin.RECENT, Builtin.FAVORITES, Builtin.TOP_FAVORITES, Builtin.STARTING_SOON),
        rowsHidden = vodRows,
    )

    val CINEMA_HUB = Preset(
        persona = ViewingPersona.CINEMA_HUB,
        miniMode = TvHomeMiniMode.OFF,
        tvStart = StartScreen.HOME,
        phoneStart = StartScreen.HOME,
        showHomeTab = true,
        sidebarFirst = listOf(Entry.HOME, Entry.MOVIES, Entry.SERIES, Entry.SEARCH, Entry.FAVORITES, Entry.LIVE).map { it.id },
        sidebarHidden = emptySet(),
        rowsFirst = listOf(Builtin.CONTINUE, Builtin.RECENT_MOVIES, Builtin.NEW_EPISODES, Builtin.FAVORITE_MOVIES, Builtin.FAVORITE_SERIES, Builtin.RECOMMENDED),
        rowsHidden = setOf(Builtin.ON_NOW, Builtin.STARTING_SOON),
    )

    val SPORTS_BAR = Preset(
        persona = ViewingPersona.SPORTS_BAR,
        miniMode = TvHomeMiniMode.HERO,
        tvStart = StartScreen.HOME,
        phoneStart = StartScreen.HOME,
        showHomeTab = true,
        sidebarFirst = listOf(Entry.HOME, Entry.LIVE, Entry.SPORTS, Entry.FAVORITES, Entry.GUIDE, Entry.RECORDINGS).map { it.id },
        sidebarHidden = setOf(Entry.MOVIES.id, Entry.SERIES.id),
        rowsFirst = listOf(Builtin.ON_NOW, Builtin.STARTING_SOON, Builtin.TOP_FAVORITES, Builtin.FAVORITES, Builtin.RECENT),
        rowsHidden = vodRows,
    )

    /** The preset for [persona]; null for [ViewingPersona.CUSTOM], which sets nothing. */
    fun get(persona: ViewingPersona): Preset? = when (persona) {
        ViewingPersona.CLASSIC_CABLE -> CLASSIC_CABLE
        ViewingPersona.CINEMA_HUB -> CINEMA_HUB
        ViewingPersona.SPORTS_BAR -> SPORTS_BAR
        ViewingPersona.CUSTOM -> null
    }

    /** The sidebar a preset gives: its entries first, the rest after in MKTV's order. Home follows [Preset.showHomeTab]. */
    fun sidebar(p: Preset): List<SidebarItem> {
        val first = p.sidebarFirst.filter { Entry.of(it) != null }.distinct()
        val rest = Entry.entries.map { it.id }.filter { it !in first }
        val items = (first + rest).map { SidebarItem(it, it !in p.sidebarHidden) }
        return TvSidebar.withHome(items, p.showHomeTab)
    }

    /**
     * [layout] arranged for [p]: its rows first and shown, every other row after in its current
     * order, the preset's hidden rows hidden. On a TV the Home tab is set too; the phone has none.
     */
    fun layout(layout: HomeLayout.Layout, p: Preset, tv: Boolean): HomeLayout.Layout {
        val firstIds = p.rowsFirst.map { it.id }
        val hiddenIds = p.rowsHidden.map { it.id }.toSet()
        val first = firstIds.mapNotNull { id -> layout.section(id)?.copy(visible = true) }
        val rest = layout.sections.filter { it.id !in firstIds }
            .map { if (it.id in hiddenIds) it.copy(visible = false) else it }
        var out = layout.copy(sections = first + rest)
        if (tv) out = HomeLayout.setShowHomeTab(out, p.showHomeTab)
        return HomeLayout.setStart(out, if (tv) p.tvStart else p.phoneStart)
    }
}
