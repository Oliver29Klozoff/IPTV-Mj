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
}
