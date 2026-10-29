package com.iptvapp.ui.home

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.data.home.HomeCard
import com.iptvapp.data.home.HomeLayout
import com.iptvapp.data.home.HomeRow
import com.iptvapp.data.home.TopFavorites
import com.iptvapp.data.home.TopFavoritesStore
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.search.SearchHit
import kotlinx.coroutines.launch

/**
 * The Top Favorites actions both home screens offer: Add/Remove on any channel (held OK on the
 * remote, a long-press on a touch screen), and on the Top Favorites row itself Move left, Move
 * right, Remove and Edit. Every change goes through TopFavoritesStore; regular favorites are
 * never touched.
 */
object TopFavoritesMenu {

    const val ADD = "Add to Top Favorites"
    const val REMOVE = "Remove from Top Favorites"

    /** The channel a Home card shows (a channel, or a programme on one), or null for films and the rest. */
    fun refOf(hit: SearchHit): HomeLayout.ItemRef? {
        val (c, m) = when (hit) {
            is SearchHit.Channel -> hit.channel to hit.merged
            is SearchHit.Program -> hit.channel to hit.merged
            else -> return null
        }
        return c?.let { TopFavorites.ref(-1, it.streamId) } ?: m?.let { TopFavorites.ref(it.serverIndex, it.streamId) }
    }

    private fun nameOf(hit: SearchHit) = when (hit) {
        is SearchHit.Program -> hit.channelName
        else -> hit.title
    }

    /** "Add to Top Favorites" or "Remove from Top Favorites" for a channel menu, from the last list read. */
    fun label(serverIndex: Int, streamId: Int) =
        if (TopFavorites.ref(serverIndex, streamId) in TopFavoritesStore.snapshot) REMOVE else ADD

    /** Adds the channel, or takes it out when it is already there. [onChanged] runs after the save. */
    fun toggle(activity: AppCompatActivity, prefs: PreferencesManager, serverIndex: Int, streamId: Int, name: String, onChanged: () -> Unit = {}) {
        val ref = TopFavorites.ref(serverIndex, streamId)
        activity.lifecycleScope.launch {
            var full = false
            var removed = false
            TopFavoritesStore.update(prefs) { s ->
                when {
                    ref in s -> { removed = true; TopFavorites.remove(s, ref) }
                    s.items.size >= TopFavorites.MAX -> { full = true; s }
                    else -> TopFavorites.add(s, ref)
                }
            }
            val msg = when {
                full -> "Top Favorites holds up to ${TopFavorites.MAX} channels. Remove one first."
                removed -> "$name removed from Top Favorites"
                else -> "$name added to Top Favorites"
            }
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            onChanged()
        }
    }

    /**
     * What holding a Home card offers. Films, series and recordings have nothing here yet, so
     * nothing opens. [onChanged] rebuilds Home after a change.
     */
    fun showForCard(activity: AppCompatActivity, prefs: PreferencesManager, tv: Boolean, row: HomeRow, card: HomeCard, onChanged: () -> Unit) {
        val ref = refOf(card.hit) ?: return
        val name = nameOf(card.hit)
        val inTopRow = row.id == HomeLayout.Builtin.TOP_FAVORITES.id
        activity.lifecycleScope.launch {
            val state = TopFavoritesStore.load(prefs)
            val labels = ArrayList<String>()
            val actions = ArrayList<() -> Unit>()
            if (inTopRow && ref in state) {
                // The cards beside this one, as the row shows them.
                val shown = row.cards.mapNotNull { refOf(it.hit) }
                val at = shown.indexOf(ref)
                if (state.sort == TopFavorites.Sort.MANUAL) {
                    if (at > 0) { labels += "◀ Move left"; actions += { move(activity, prefs, ref, shown[at - 1], after = false, onChanged) } }
                    if (at in 0 until shown.lastIndex) { labels += "Move right ▶"; actions += { move(activity, prefs, ref, shown[at + 1], after = true, onChanged) } }
                } else {
                    labels += "Ordered by Most watched · switch to Manual order"
                    actions += { setSort(activity, prefs, TopFavorites.Sort.MANUAL, onChanged) }
                }
                labels += REMOVE; actions += { toggle(activity, prefs, ref.serverIndex, ref.id, name, onChanged) }
                labels += "Edit Top Favorites…"; actions += { openEditor(activity, tv) }
                labels += "Order: ${state.sort.label}…"; actions += { pickSort(activity, prefs, state.sort, onChanged) }
            } else {
                labels += if (ref in state) REMOVE else ADD
                actions += { toggle(activity, prefs, ref.serverIndex, ref.id, name, onChanged) }
            }
            AlertDialog.Builder(activity)
                .setTitle(name)
                .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun move(activity: AppCompatActivity, prefs: PreferencesManager, ref: HomeLayout.ItemRef, neighbor: HomeLayout.ItemRef, after: Boolean, onChanged: () -> Unit) {
        activity.lifecycleScope.launch {
            TopFavoritesStore.update(prefs) { TopFavorites.moveNextTo(it, ref, neighbor, after) }
            onChanged()
        }
    }

    fun setSort(activity: AppCompatActivity, prefs: PreferencesManager, sort: TopFavorites.Sort, onChanged: () -> Unit) {
        activity.lifecycleScope.launch {
            TopFavoritesStore.update(prefs) { TopFavorites.setSort(it, sort) }
            onChanged()
        }
    }

    /** Manual order, or Most watched (by viewing time; the hand-made order is kept for switching back). */
    fun pickSort(activity: AppCompatActivity, prefs: PreferencesManager, current: TopFavorites.Sort, onChanged: () -> Unit) {
        val options = TopFavorites.Sort.entries
        val labels = options.map {
            if (it == TopFavorites.Sort.MOST_WATCHED) "${it.label} (time watched, last ${TopFavorites.WINDOW_DAYS} days)" else it.label
        }
        AlertDialog.Builder(activity)
            .setTitle("Order Top Favorites by")
            .setSingleChoiceItems(labels.toTypedArray(), options.indexOf(current)) { d, which ->
                d.dismiss()
                setSort(activity, prefs, options[which], onChanged)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The list editor: search to add from any provider, Move up/down, Remove, and drag on a touch screen. */
    fun openEditor(activity: AppCompatActivity, tv: Boolean) {
        activity.startActivity(
            Intent(activity, HomeCollectionActivity::class.java)
                .putExtra(HomeCollectionActivity.EXTRA_TV, tv)
                .putExtra(HomeCollectionActivity.EXTRA_SECTION, HomeLayout.Builtin.TOP_FAVORITES.id)
        )
    }
}
