package com.iptvapp.ui.home

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.R
import com.iptvapp.data.home.HomeLayout
import com.iptvapp.data.home.HomeLayoutStore
import com.iptvapp.data.home.TopFavorites
import com.iptvapp.data.home.TopFavoritesStore
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityHomeCollectionBinding
import com.iptvapp.databinding.ItemHomeSectionEditBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The items of a row someone made (HomeLayout): what is in it, in the row's own order, and a
 * search to add more. Order and membership live only in the Home layout, so nothing here changes
 * favorites or the catalog's order. Channels can come from any provider; movies and series from
 * the main one. An item that is no longer in the catalog stays listed, marked, until removed.
 *
 * Opened for Top Favorites (HomeLayout.Builtin.TOP_FAVORITES) it edits that list instead
 * (TopFavoritesStore): channels only, its saved manual order.
 */
@AndroidEntryPoint
class HomeCollectionActivity : AppCompatActivity() {

    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var db: IptvDatabase

    private lateinit var binding: ActivityHomeCollectionBinding
    private val tv by lazy { intent.getBooleanExtra(EXTRA_TV, false) }
    private val sectionId by lazy { intent.getStringExtra(EXTRA_SECTION).orEmpty() }
    private val top by lazy { sectionId == HomeLayout.Builtin.TOP_FAVORITES.id }
    private val limit get() = if (top) TopFavorites.MAX else HomeLayout.MAX_ITEMS
    private val touch by lazy { resources.configuration.touchscreen != android.content.res.Configuration.TOUCHSCREEN_NOTOUCH }

    /** What is listed, in order, and of what kind (Top Favorites: channels). */
    private var picked: List<HomeLayout.ItemRef> = emptyList()
    private var content: HomeLayout.Content? = null
    private var names: Map<HomeLayout.ItemRef, Pair<String, String>> = emptyMap()
    private var results: List<Result> = emptyList()
    private var searchJob: Job? = null
    private var primaryName = "Primary"
    private val itemsAdapter = ItemsAdapter()
    private val resultsAdapter = ResultsAdapter()
    private lateinit var dragHelper: ItemTouchHelper

    private data class Result(val ref: HomeLayout.ItemRef, val name: String, val detail: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeCollectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val base = binding.root.paddingTop
        val side = binding.root.paddingLeft
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                    androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            v.setPadding(side + bars.left, base + bars.top, side + bars.right, bars.bottom)
            insets
        }
        binding.rvCollectionItems.layoutManager = LinearLayoutManager(this)
        binding.rvCollectionItems.adapter = itemsAdapter
        binding.rvCollectionItems.itemAnimator = null
        binding.rvCollectionResults.layoutManager = LinearLayoutManager(this)
        binding.rvCollectionResults.adapter = resultsAdapter
        dragHelper = ItemTouchHelper(DragCallback())
        if (touch) dragHelper.attachToRecyclerView(binding.rvCollectionItems)
        binding.btnCollectionDone.setOnClickListener { finish() }
        binding.btnCollectionDone.contentDescription = "Done. Changes are already saved."
        binding.etCollectionSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = search(s?.toString().orEmpty())
        })
        lifecycleScope.launch {
            primaryName = prefs.serverNickname.first().ifBlank { "Primary" }
            load()
            binding.etCollectionSearch.requestFocus()
        }
    }

    private suspend fun load() {
        if (top) {
            picked = TopFavoritesStore.load(prefs).items
            content = HomeLayout.Content.CHANNELS
            names = describe(picked)
            binding.tvCollectionTitle.text = HomeLayout.Builtin.TOP_FAVORITES.title
            binding.tvCollectionHint.text = "Pick channels from any provider. The order here is the row's manual order. Your regular favorites don't change."
            render()
            return
        }
        val s = HomeLayoutStore.load(prefs, tv).section(sectionId)
        if (s == null || !s.isCustom || s.isDynamic) {
            finish()
            return
        }
        picked = s.items
        content = s.contentType
        names = describe(s.items)
        binding.tvCollectionTitle.text = s.displayTitle
        binding.tvCollectionHint.text = when (s.contentType) {
            HomeLayout.Content.CHANNELS -> "Pick channels from any provider. The order here is the row's order."
            HomeLayout.Content.MOVIES -> "Pick movies. The order here is the row's order."
            else -> "Pick series. The order here is the row's order."
        }
        render()
    }

    private fun render(focus: Pair<HomeLayout.ItemRef, Int>? = null) {
        if (content == null) return
        binding.tvCollectionInRow.text = "IN THIS ROW · ${picked.size}"
        binding.tvCollectionEmpty.visibility = if (picked.isEmpty()) View.VISIBLE else View.GONE
        itemsAdapter.items = picked.toMutableList()
        @Suppress("NotifyDataSetChanged")
        itemsAdapter.notifyDataSetChanged()
        @Suppress("NotifyDataSetChanged")
        resultsAdapter.notifyDataSetChanged()
        if (focus != null) {
            val pos = picked.indexOf(focus.first)
            val rv = binding.rvCollectionItems
            if (pos >= 0) {
                rv.scrollToPosition(pos)
                rv.post { rv.findViewHolderForAdapterPosition(pos)?.itemView?.findViewById<View>(focus.second)?.requestFocus() }
            }
        }
    }

    /** Saves one change to the list being edited (the row's items, or Top Favorites) and redraws. */
    private fun change(
        focus: Pair<HomeLayout.ItemRef, Int>? = null,
        topChange: (TopFavorites.State) -> TopFavorites.State,
        change: (HomeLayout.Layout) -> HomeLayout.Layout,
    ) {
        lifecycleScope.launch {
            val saved = if (top) TopFavoritesStore.update(prefs, topChange).items
            else HomeLayoutStore.update(prefs, tv, change).section(sectionId)?.items ?: return@launch finish()
            val added = saved.filter { it !in names }
            if (added.isNotEmpty()) names = names + describe(added)
            picked = saved
            render(focus)
        }
    }

    private fun moveItem(r: HomeLayout.ItemRef, to: Int, focus: Int? = null) =
        change(focus?.let { r to it }, { TopFavorites.moveTo(it, r, to) }) { HomeLayout.moveItem(it, sectionId, r, to) }

    private fun removeItem(r: HomeLayout.ItemRef) =
        change(null, { TopFavorites.remove(it, r) }) { HomeLayout.removeItem(it, sectionId, r) }

    private fun addItem(r: HomeLayout.ItemRef) =
        change(null, { TopFavorites.add(it, r) }) { HomeLayout.addItem(it, sectionId, r) }

    /** Names and provider for each item, read by id; one missing from the catalog has no entry. */
    private suspend fun describe(items: List<HomeLayout.ItemRef>): Map<HomeLayout.ItemRef, Pair<String, String>> {
        val out = HashMap<HomeLayout.ItemRef, Pair<String, String>>()
        val nicknames = try {
            repository.getMergedServerSummaries().first().associate { it.serverIndex to it.serverNickname }
        } catch (_: Exception) { emptyMap() }
        for ((server, refs) in items.groupBy { it.content to it.serverIndex }) {
            val ids = refs.map { it.id }
            when (server.first) {
                HomeLayout.Content.CHANNELS -> if (server.second == -1) {
                    ids.chunked(500).flatMap { db.channelDao().getChannelsByIds(it) }.forEach {
                        out[HomeLayout.ItemRef(HomeLayout.Content.CHANNELS, -1, it.streamId)] = it.name to primaryName
                    }
                } else {
                    ids.chunked(500).flatMap { db.mergedChannelDao().getByServerAndIds(server.second, it) }.forEach {
                        out[HomeLayout.ItemRef(HomeLayout.Content.CHANNELS, it.serverIndex, it.streamId)] =
                            it.name to (nicknames[it.serverIndex] ?: it.serverNickname)
                    }
                }
                HomeLayout.Content.MOVIES -> ids.chunked(500).flatMap { db.vodDao().getVodByIds(it) }.forEach {
                    out[HomeLayout.ItemRef(HomeLayout.Content.MOVIES, -1, it.streamId)] = it.name to "Movie"
                }
                HomeLayout.Content.SERIES -> ids.chunked(500).flatMap { db.seriesDao().getSeriesByIds(it) }.forEach {
                    out[HomeLayout.ItemRef(HomeLayout.Content.SERIES, -1, it.seriesId)] = it.name to "Series"
                }
                else -> Unit
            }
        }
        return out
    }

    private fun search(text: String) {
        searchJob?.cancel()
        val q = text.trim()
        if (q.length < 2) {
            results = emptyList()
            @Suppress("NotifyDataSetChanged")
            resultsAdapter.notifyDataSetChanged()
            return
        }
        val content = content ?: return
        searchJob = lifecycleScope.launch {
            delay(300)
            results = try {
                when (content) {
                    HomeLayout.Content.CHANNELS -> {
                        val enabled = prefs.enabledExtraServerIndices.first()
                        repository.searchChannels(q).first().filter { !it.isHidden }.take(SEARCH_LIMIT).map {
                            Result(HomeLayout.ItemRef(content, -1, it.streamId), it.name, primaryName)
                        } + repository.searchMergedChannels(q).first()
                            .filter { !it.isHidden && it.serverIndex in enabled }.take(SEARCH_LIMIT).map {
                                Result(HomeLayout.ItemRef(content, it.serverIndex, it.streamId), it.name, it.serverNickname)
                            }
                    }
                    HomeLayout.Content.MOVIES -> repository.searchVod(q).first().take(SEARCH_LIMIT).map {
                        Result(HomeLayout.ItemRef(content, -1, it.streamId), it.name, "Movie")
                    }
                    HomeLayout.Content.SERIES -> repository.searchSeries(q).first().filter { !it.isHidden }.take(SEARCH_LIMIT).map {
                        Result(HomeLayout.ItemRef(content, -1, it.seriesId), it.name, "Series")
                    }
                    else -> emptyList()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
            names = names + results.associate { it.ref to (it.name to it.detail) }
            @Suppress("NotifyDataSetChanged")
            resultsAdapter.notifyDataSetChanged()
        }
    }

    private inner class Holder(val b: ItemHomeSectionEditBinding) : RecyclerView.ViewHolder(b.root)

    /** What is in the row: Move up, Move down, Remove (and drag on a touch screen). */
    private inner class ItemsAdapter : RecyclerView.Adapter<Holder>() {
        var items: MutableList<HomeLayout.ItemRef> = mutableListOf()

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val h = Holder(ItemHomeSectionEditBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            h.b.tvSectionHandle.visibility = if (touch) View.VISIBLE else View.GONE
            h.b.tvSectionHandle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) dragHelper.startDrag(h)
                false
            }
            h.b.btnSectionEdit.visibility = View.GONE
            h.b.btnSectionVisible.text = "✕ REMOVE"
            fun current() = items.getOrNull(h.bindingAdapterPosition)
            h.b.btnSectionUp.setOnClickListener {
                val i = h.bindingAdapterPosition
                current()?.let { r -> moveItem(r, i - 1, R.id.btnSectionUp) }
            }
            h.b.btnSectionDown.setOnClickListener {
                val i = h.bindingAdapterPosition
                current()?.let { r -> moveItem(r, i + 1, R.id.btnSectionDown) }
            }
            h.b.btnSectionVisible.setOnClickListener {
                current()?.let { r -> removeItem(r) }
            }
            return h
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val ref = items[position]
            val known = names[ref]
            val name = known?.first ?: "No longer available"
            h.b.tvSectionTitle.text = name
            h.b.tvSectionTitle.alpha = if (known == null) 0.55f else 1f
            h.b.tvSectionDetail.text = known?.second ?: "It's not in the catalog now. Remove it, or it shows again if it comes back."
            h.b.btnSectionUp.alpha = if (position == 0) 0.4f else 1f
            h.b.btnSectionDown.alpha = if (position == items.lastIndex) 0.4f else 1f
            h.b.btnSectionUp.contentDescription = "Move $name up, now ${position + 1} of ${items.size}"
            h.b.btnSectionDown.contentDescription = "Move $name down, now ${position + 1} of ${items.size}"
            h.b.btnSectionVisible.contentDescription = if (top) "Remove $name from Top Favorites" else "Remove $name from this row"
        }
    }

    /** Search results: Add, or Added (pressing again takes it out). */
    private inner class ResultsAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = results.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val h = Holder(ItemHomeSectionEditBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            h.b.tvSectionHandle.visibility = View.GONE
            h.b.btnSectionUp.visibility = View.GONE
            h.b.btnSectionDown.visibility = View.GONE
            h.b.btnSectionEdit.visibility = View.GONE
            h.b.btnSectionVisible.setOnClickListener {
                val r = results.getOrNull(h.bindingAdapterPosition) ?: return@setOnClickListener
                val inRow = r.ref in picked
                if (inRow) removeItem(r.ref)
                else if (picked.size >= limit) {
                    android.widget.Toast.makeText(this@HomeCollectionActivity, "A row holds up to $limit.", android.widget.Toast.LENGTH_SHORT).show()
                } else addItem(r.ref)
            }
            return h
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = results[position]
            val inRow = r.ref in picked
            h.b.tvSectionTitle.text = r.name
            h.b.tvSectionDetail.text = r.detail
            h.b.btnSectionVisible.text = if (inRow) "✓ ADDED" else "+ ADD"
            h.b.btnSectionVisible.contentDescription = if (inRow) "${r.name} is in this row. Remove it" else "Add ${r.name}, ${r.detail}"
        }
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        private var dragged: HomeLayout.ItemRef? = null

        override fun isLongPressDragEnabled() = false

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            dragged = dragged ?: itemsAdapter.items[a]
            val moved = itemsAdapter.items.removeAt(a)
            itemsAdapter.items.add(b, moved)
            itemsAdapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            val r = dragged ?: return
            dragged = null
            val to = itemsAdapter.items.indexOf(r)
            if (to >= 0) moveItem(r, to)
        }
    }

    companion object {
        const val EXTRA_TV = "home_layout_tv"
        const val EXTRA_SECTION = "home_section_id"
        private const val SEARCH_LIMIT = 40
    }
}
