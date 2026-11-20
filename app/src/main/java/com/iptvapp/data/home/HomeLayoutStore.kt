package com.iptvapp.data.home

import com.iptvapp.data.local.PreferencesManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads and changes the saved Home layouts. Every change reads the latest saved layout, applies
 * one HomeLayout change, and writes it back under one lock, so the row editor and the item editor
 * never write over each other, and a change still lands if its screen closes meanwhile.
 */
object HomeLayoutStore {
    private val lock = Mutex()

    suspend fun load(prefs: PreferencesManager, tv: Boolean): HomeLayout.Layout =
        HomeLayout.parse(prefs.homeLayoutJson(tv).first())

    /** Applies [change] to the saved layout and returns what was saved. */
    suspend fun update(
        prefs: PreferencesManager,
        tv: Boolean,
        change: (HomeLayout.Layout) -> HomeLayout.Layout
    ): HomeLayout.Layout = withContext(NonCancellable) {
        lock.withLock {
            val before = load(prefs, tv)
            val after = change(before)
            if (after != before) {
                prefs.setHomeLayoutJson(tv, HomeLayout.toJson(after))
            }
            after
        }
    }

    /**
     * Applies a viewing persona (PersonaPresets) in one write, under the same lock as [update]:
     * the rows and start screen, and on a TV the Home tab, sidebar and mini player. Custom changes
     * nothing; it only marks the settings as the viewer's own. Returns the layout now saved.
     */
    suspend fun applyPersona(
        prefs: PreferencesManager,
        tv: Boolean,
        persona: ViewingPersona,
    ): HomeLayout.Layout = withContext(NonCancellable) {
        lock.withLock {
            val before = load(prefs, tv)
            val preset = PersonaPresets.get(persona)
            if (preset == null) {
                prefs.clearViewingPersona(tv)
                return@withLock before
            }
            val after = PersonaPresets.layout(before, preset, tv)
            prefs.applyViewingPersona(
                tv = tv,
                persona = persona,
                layoutJson = HomeLayout.toJson(after),
                sidebar = if (tv) PersonaPresets.sidebar(preset) else null,
                miniMode = if (tv) preset.miniMode.key else null,
            )
            after
        }
    }
}
