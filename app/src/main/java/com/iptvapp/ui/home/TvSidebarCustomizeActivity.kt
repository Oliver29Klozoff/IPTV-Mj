package com.iptvapp.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.R
import com.iptvapp.data.home.HomeLayout
import com.iptvapp.data.home.HomeLayoutStore
import com.iptvapp.data.home.TvSidebar
import com.iptvapp.data.home.TvSidebar.SidebarItem
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ActivityTvSidebarCustomizeBinding
import com.iptvapp.databinding.ItemTvSidebarEditBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Customize Sidebar: the TV sidebar's entries (TvSidebar) in order, each with Move up, Move down
 * and Shown/Hidden as buttons the remote can reach; on a touch screen the handle drags too.
 * Every change is saved as it is made, and the TV home screen rebuilds its sidebar from it.
 * Home's Shown/Hidden is Customize Home's HOME TAB (HomeLayout.showHomeTab), so both stay in step.
 */
@AndroidEntryPoint
class TvSidebarCustomizeActivity : AppCompatActivity() {

    @Inject lateinit var prefs: PreferencesManager

    private lateinit var binding: ActivityTvSidebarCustomizeBinding
    private val touch by lazy { resources.configuration.touchscreen != android.content.res.Configuration.TOUCHSCREEN_NOTOUCH }
    private var items: List<SidebarItem> = TvSidebar.default()
    private var offInSettings: Set<String> = emptySet()
    private val adapter = ItemAdapter()
    private lateinit var dragHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvSidebarCustomizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val base = binding.root.paddingTop
        val side = binding.root.paddingLeft
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(side + bars.left, base + bars.top, side + bars.right, bars.bottom)
            insets
        }
        binding.rvSidebarItems.layoutManager = LinearLayoutManager(this)
        binding.rvSidebarItems.adapter = adapter
        binding.rvSidebarItems.itemAnimator = null
        dragHelper = ItemTouchHelper(DragCallback())
        if (touch) dragHelper.attachToRecyclerView(binding.rvSidebarItems)
        binding.btnSidebarDone.setOnClickListener { finish() }
        binding.btnSidebarReset.setOnClickListener { confirmReset() }

        lifecycleScope.launch {
            val off = HashSet<String>()
            if (!prefs.showMovies.first()) off += TvSidebar.Entry.MOVIES.id
            if (!prefs.showSeries.first()) off += TvSidebar.Entry.SERIES.id
            if (!prefs.showProviders.first()) off += TvSidebar.Entry.PROVIDERS.id
            offInSettings = off
            items = load()
            adapter.submit(items)
            binding.btnSidebarDone.requestFocus()
        }
    }

    /** The saved sidebar with Home's state from Customize Home. */
    private suspend fun load(): List<SidebarItem> =
        TvSidebar.withHome(prefs.tvSidebarConfig.first(), HomeLayoutStore.load(prefs, true).showHomeTab)

    // ── Changes ─────────────────────────────────────────────────────────

    // Writes run NonCancellable: Done or Back right after a change must not drop it.
    /** Saves [next] and redraws, keeping focus on [focusButton] of entry [focusId] (it may have moved). */
    private fun save(next: List<SidebarItem>, focusId: String? = null, focusButton: Int = 0) {
        items = next
        adapter.submit(next)
        lifecycleScope.launch(NonCancellable) { prefs.setTvSidebarConfig(next) }
        if (focusId != null) refocus(focusId, focusButton)
    }

    private fun refocus(id: String, button: Int) {
        val pos = items.indexOfFirst { it.id == id }
        if (pos < 0) return
        val rv = binding.rvSidebarItems
        rv.scrollToPosition(pos)
        rv.post { rv.findViewHolderForAdapterPosition(pos)?.itemView?.findViewById<View>(button)?.requestFocus() }
    }

    private fun toggle(item: SidebarItem) {
        val show = !item.visible
        if (!show && !TvSidebar.canHide(items, item.id)) {
            Toast.makeText(this, "Keep at least one of Home, Favorites, Guide or Live in the sidebar.", Toast.LENGTH_LONG).show()
            return
        }
        val next = TvSidebar.setVisible(items, item.id, show)
        if (item.id == TvSidebar.Entry.HOME.id) {
            // Home's switch is Customize Home's HOME TAB: hiding it also moves a Home start to Favorites.
            lifecycleScope.launch(NonCancellable) { HomeLayoutStore.update(prefs, true) { HomeLayout.setShowHomeTab(it, show) } }
        }
        save(next, item.id, R.id.btnSidebarVisible)
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset the sidebar?")
            .setMessage("Every entry goes back to MKTV's order and is shown again, Home included. Movies, Series and Providers still follow their switches in Settings.")
            .setPositiveButton("Reset") { _, _ ->
                lifecycleScope.launch {
                    withContext(NonCancellable) {
                        prefs.setTvSidebarConfig(null)
                        HomeLayoutStore.update(prefs, true) { HomeLayout.setShowHomeTab(it, true) }
                    }
                    items = load()
                    adapter.submit(items)
                    binding.btnSidebarReset.requestFocus()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun detailOf(item: SidebarItem): String = when {
        !item.visible -> "Hidden"
        item.id in offInSettings -> "Shown here, but turned off in Settings"
        else -> "Shown"
    }

    // ── List ────────────────────────────────────────────────────────────

    private inner class Holder(val b: ItemTvSidebarEditBinding) : RecyclerView.ViewHolder(b.root)

    private inner class ItemAdapter : RecyclerView.Adapter<Holder>() {
        var list: MutableList<SidebarItem> = mutableListOf()

        fun submit(next: List<SidebarItem>) {
            val old = list
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].id == next[n].id
                // The first and last rows dim their arrows, so a move redraws by position too.
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == next[n] && o == n
            })
            list = next.toMutableList()
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount() = list.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val h = Holder(ItemTvSidebarEditBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            h.b.tvSidebarHandle.visibility = if (touch) View.VISIBLE else View.GONE
            h.b.tvSidebarHandle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) dragHelper.startDrag(h)
                false
            }
            fun current() = list.getOrNull(h.bindingAdapterPosition)
            h.b.btnSidebarUp.setOnClickListener { current()?.let { s -> save(TvSidebar.move(items, s.id, -1), s.id, R.id.btnSidebarUp) } }
            h.b.btnSidebarDown.setOnClickListener { current()?.let { s -> save(TvSidebar.move(items, s.id, 1), s.id, R.id.btnSidebarDown) } }
            h.b.btnSidebarVisible.setOnClickListener { current()?.let { toggle(it) } }
            return h
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val s = list[position]
            val entry = TvSidebar.Entry.of(s.id)
            val title = entry?.label ?: s.id
            h.b.tvSidebarIcon.text = iconOf(entry)
            h.b.tvSidebarTitle.text = title
            h.b.tvSidebarTitle.alpha = if (s.visible) 1f else 0.55f
            h.b.tvSidebarIcon.alpha = if (s.visible) 1f else 0.55f
            h.b.tvSidebarDetail.text = detailOf(s)
            h.b.btnSidebarVisible.text = if (s.visible) "● SHOWN" else "○ HIDDEN"
            h.b.btnSidebarVisible.contentDescription = if (s.visible) "$title is in the sidebar. Hide it" else "$title is hidden. Show it in the sidebar"
            h.b.btnSidebarUp.alpha = if (position == 0) 0.4f else 1f
            h.b.btnSidebarDown.alpha = if (position == list.lastIndex) 0.4f else 1f
            h.b.btnSidebarUp.contentDescription = "Move $title up, now ${position + 1} of ${list.size}"
            h.b.btnSidebarDown.contentDescription = "Move $title down, now ${position + 1} of ${list.size}"
            h.b.tvSidebarHandle.contentDescription = "Drag to move $title"
        }
    }

    private fun iconOf(e: TvSidebar.Entry?) = when (e) {
        TvSidebar.Entry.HOME -> "⌂"
        TvSidebar.Entry.FAVORITES -> "★"
        TvSidebar.Entry.PROVIDERS -> "⇄"
        TvSidebar.Entry.LIVE -> "●"
        TvSidebar.Entry.CATEGORIES -> "☰"
        TvSidebar.Entry.MOVIES -> "▶"
        TvSidebar.Entry.SERIES -> "▤"
        TvSidebar.Entry.GUIDE -> "▦"
        TvSidebar.Entry.SPORTS -> "⚑"
        TvSidebar.Entry.SEARCH -> "⌕"
        TvSidebar.Entry.VOICE -> "◉"
        TvSidebar.Entry.RECORDINGS -> "⏺"
        null -> ""
    }

    /** Touch drag by the handle: the list moves as the finger does; the new place is saved on release. */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        private var draggedId: String? = null

        override fun isLongPressDragEnabled() = false

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            draggedId = draggedId ?: adapter.list[a].id
            adapter.list.add(b, adapter.list.removeAt(a))
            adapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            val id = draggedId ?: return
            draggedId = null
            val to = adapter.list.indexOfFirst { it.id == id }
            if (to >= 0) save(TvSidebar.moveTo(items, id, to))
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, TvSidebarCustomizeActivity::class.java))
        }
    }
}
