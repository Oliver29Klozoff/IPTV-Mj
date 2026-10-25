package com.iptvapp.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityHomeCustomizeBinding
import com.iptvapp.databinding.ItemHomeSectionEditBinding
import com.iptvapp.util.isLargeScreenDevice
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Customize Home: the rows of Home (HomeLayout) in their order, for TV or phone. Each row has
 * Move up, Move down, Shown/Hidden and Edit as buttons, so the Shield remote can do everything;
 * on a touch screen the handle also drags. Add row makes a row of picked channels, films or
 * series, or one category. Every change is saved as it is made (HomeLayoutStore), and Home picks
 * it up when it shows again. Nothing here changes favorites, progress, or the catalog.
 */
@AndroidEntryPoint
class HomeCustomizeActivity : AppCompatActivity() {

    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var repository: XtreamRepository

    private lateinit var binding: ActivityHomeCustomizeBinding
    private val tv by lazy { intent.getBooleanExtra(EXTRA_TV, isLargeScreenDevice()) }
    private val touch by lazy { resources.configuration.touchscreen != android.content.res.Configuration.TOUCHSCREEN_NOTOUCH }
    private var layout = HomeLayout.default()
    private var primaryName = "Primary"
    private var providerNames: Map<Int, String> = emptyMap()
    private val adapter = SectionAdapter()
    private lateinit var dragHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeCustomizeBinding.inflate(layoutInflater)
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
        binding.tvCustomizeSubtitle.text = if (tv) {
            "TV layout (this TV and other large screens). Changes are saved as you make them."
        } else {
            "Phone and tablet layout. Changes are saved as you make them. Drag ≡ to move a row."
        }
        binding.rvCustomizeSections.layoutManager = LinearLayoutManager(this)
        binding.rvCustomizeSections.adapter = adapter
        binding.rvCustomizeSections.itemAnimator = null
        dragHelper = ItemTouchHelper(DragCallback())
        if (touch) dragHelper.attachToRecyclerView(binding.rvCustomizeSections)

        binding.btnCustomizeDone.setOnClickListener { finish() }
        binding.btnCustomizeAdd.setOnClickListener { addRow() }
        binding.btnCustomizeStart.setOnClickListener { pickStart() }
        binding.btnCustomizeReset.setOnClickListener { confirmReset() }
        binding.btnCustomizeDone.contentDescription = "Done. Changes are already saved."
        binding.btnCustomizeReset.contentDescription = "Reset Home to how MKTV ships it"
    }

    override fun onResume() {
        super.onResume()
        // Back from editing a row's items: read what was saved there.
        lifecycleScope.launch {
            primaryName = prefs.serverNickname.first().ifBlank { "Primary" }
            providerNames = try {
                repository.getMergedServerSummaries().first().associate { it.serverIndex to it.serverNickname }
            } catch (_: Exception) { emptyMap() }
            layout = HomeLayoutStore.load(prefs, tv)
            render(focus = null)
            if (binding.rvCustomizeSections.findFocus() == null && !binding.root.hasFocus()) binding.btnCustomizeDone.requestFocus()
        }
    }

    // ── Changes ─────────────────────────────────────────────────────────

    /** Saves [change] and redraws, keeping focus on [focusButton] of row [focusId] (it may have moved). */
    private fun change(focusId: String? = null, focusButton: Int = 0, change: (HomeLayout.Layout) -> HomeLayout.Layout) {
        lifecycleScope.launch {
            layout = HomeLayoutStore.update(prefs, tv, change)
            render(if (focusId != null) focusId to focusButton else null)
        }
    }

    private fun render(focus: Pair<String, Int>?) {
        adapter.submit(layout.sections)
        binding.btnCustomizeStart.text = "START ON: ${layout.start.label.uppercase()}"
        binding.btnCustomizeStart.contentDescription = "Home opens on ${layout.start.label}. Change it"
        if (focus == null) return
        val pos = layout.sections.indexOfFirst { it.id == focus.first }
        if (pos < 0) return
        val rv = binding.rvCustomizeSections
        rv.scrollToPosition(pos)
        rv.post {
            val holder = rv.findViewHolderForAdapterPosition(pos) ?: return@post
            (if (focus.second != 0) holder.itemView.findViewById<View>(focus.second) else null)?.requestFocus()
                ?: holder.itemView.findViewById<View>(R.id.btnSectionEdit)?.requestFocus()
        }
    }

    private fun detailOf(s: HomeLayout.Section): String {
        val parts = ArrayList<String>(4)
        val f = s.filter
        val what = when (s.contentType) {
            HomeLayout.Content.CHANNELS -> "Channels"
            HomeLayout.Content.MOVIES -> "Movies"
            HomeLayout.Content.SERIES -> "Series"
            else -> null
        }
        when {
            !s.isCustom -> parts += "Built in"
            f?.gone == true -> parts += "Provider removed"
            f != null -> parts += "$what from ${f.categoryName} (${providerName(f.serverIndex)})"
            else -> parts += "${what ?: "Items"} you pick · ${s.items.size}"
        }
        if (HomeLayout.styles(s.contentType).size > 1) parts += s.style.label
        if (s.showWhenEmpty) parts += "Shown when empty"
        if (!s.visible) parts += "Hidden"
        return parts.joinToString(" · ")
    }

    private fun providerName(serverIndex: Int) =
        if (serverIndex == -1) primaryName else providerNames[serverIndex] ?: "Provider ${serverIndex + 2}"

    // ── Dialogs ─────────────────────────────────────────────────────────

    private fun editRow(s: HomeLayout.Section) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (s.isCustom) {
            labels += "Rename"; actions += { rename(s) }
            if (s.isDynamic) {
                labels += "Change category"; actions += { pickCategory(s.contentType, s.title.orEmpty(), s.id) }
            } else {
                labels += "Choose ${itemsWord(s.contentType)} (${s.items.size})"; actions += { openItems(s.id) }
            }
        }
        val styles = HomeLayout.styles(s.contentType)
        if (styles.size > 1) {
            labels += "Card style: ${s.style.label}"
            actions += {
                AlertDialog.Builder(this)
                    .setTitle("Card style")
                    .setSingleChoiceItems(styles.map { it.label }.toTypedArray(), styles.indexOf(s.style)) { d, which ->
                        d.dismiss()
                        change(s.id, R.id.btnSectionEdit) { HomeLayout.setStyle(it, s.id, styles[which]) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        labels += if (s.showWhenEmpty) "When empty: show a hint" else "When empty: hide the row"
        actions += { change(s.id, R.id.btnSectionEdit) { HomeLayout.setShowWhenEmpty(it, s.id, !s.showWhenEmpty) } }
        if (s.isCustom) {
            labels += "Delete row"
            actions += {
                AlertDialog.Builder(this)
                    .setTitle("Delete \"${s.displayTitle}\"?")
                    .setMessage("Only the row goes. The ${itemsWord(s.contentType)} in it, and your favorites, stay as they are.")
                    .setPositiveButton("Delete") { _, _ -> change { HomeLayout.delete(it, s.id) } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        AlertDialog.Builder(this)
            .setTitle(s.displayTitle)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun itemsWord(c: HomeLayout.Content) = when (c) {
        HomeLayout.Content.MOVIES -> "movies"
        HomeLayout.Content.SERIES -> "series"
        else -> "channels"
    }

    private fun rename(s: HomeLayout.Section) {
        askName("Rename row", s.displayTitle) { name -> change(s.id, R.id.btnSectionEdit) { HomeLayout.rename(it, s.id, name) } }
    }

    private fun askName(title: String, start: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(start)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(android.text.InputFilter.LengthFilter(HomeLayout.MAX_TITLE))
            contentDescription = "Row name"
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun addRow() {
        if (layout.sections.size >= HomeLayout.MAX_SECTIONS) {
            Toast.makeText(this, "Home has as many rows as it can hold. Delete one first.", Toast.LENGTH_LONG).show()
            return
        }
        val hidden = layout.sections.filter { !it.visible }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels += "Channels I pick"; actions += { newPicked(HomeLayout.Content.CHANNELS, "My Channels") }
        labels += "Movies I pick"; actions += { newPicked(HomeLayout.Content.MOVIES, "My Movies") }
        labels += "Series I pick"; actions += { newPicked(HomeLayout.Content.SERIES, "My Series") }
        labels += "Channels from a category"; actions += { pickCategory(HomeLayout.Content.CHANNELS, null, null) }
        labels += "Movies from a category"; actions += { pickCategory(HomeLayout.Content.MOVIES, null, null) }
        labels += "Series from a category"; actions += { pickCategory(HomeLayout.Content.SERIES, null, null) }
        for (s in hidden) {
            labels += "Show again: ${s.displayTitle}"
            actions += { change(s.id, R.id.btnSectionVisible) { HomeLayout.setVisible(it, s.id, true) } }
        }
        AlertDialog.Builder(this)
            .setTitle("Add a row")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun newPicked(content: HomeLayout.Content, suggested: String) {
        askName("Name the row", suggested) { name ->
            val id = UUID.randomUUID().toString()
            lifecycleScope.launch {
                layout = HomeLayoutStore.update(prefs, tv) { HomeLayout.addCustom(it, id, name, content) }
                render(null)
                openItems(HomeLayout.CUSTOM_PREFIX + id)
            }
        }
    }

    /**
     * A category row: the provider (channels can come from any), then the category. With [editId]
     * it changes that row's category; otherwise it asks for a name and adds the row.
     */
    private fun pickCategory(content: HomeLayout.Content, keepTitle: String?, editId: String?) {
        lifecycleScope.launch {
            if (content == HomeLayout.Content.CHANNELS && providerNames.isNotEmpty()) {
                val servers = listOf(-1) + providerNames.keys.sorted()
                AlertDialog.Builder(this@HomeCustomizeActivity)
                    .setTitle("Which provider?")
                    .setItems(servers.map { providerName(it) }.toTypedArray()) { _, which ->
                        pickCategoryOn(content, servers[which], keepTitle, editId)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                pickCategoryOn(content, -1, keepTitle, editId)
            }
        }
    }

    private fun pickCategoryOn(content: HomeLayout.Content, server: Int, keepTitle: String?, editId: String?) {
        lifecycleScope.launch {
            val categories: List<Pair<String?, String>> = try {
                when {
                    content == HomeLayout.Content.MOVIES -> repository.getVodCategories().first().map { it.categoryId to it.categoryName }
                    content == HomeLayout.Content.SERIES -> repository.getSeriesCategories().first().map { it.categoryId to it.categoryName }
                    server == -1 -> repository.getLiveCategories().first().map { it.categoryId to it.categoryName }
                    else -> repository.getMergedCategorySummaries(server).first().map { it.categoryId to (it.categoryName ?: "Other") }
                }
            } catch (_: Exception) { emptyList() }
            if (categories.isEmpty()) {
                Toast.makeText(this@HomeCustomizeActivity, "No categories yet. Refresh the provider's lists, then try again.", Toast.LENGTH_LONG).show()
                return@launch
            }
            AlertDialog.Builder(this@HomeCustomizeActivity)
                .setTitle("Which category?")
                .setItems(categories.map { it.second }.toTypedArray()) { _, which ->
                    val (catId, catName) = categories[which]
                    val filter = HomeLayout.Filter(server, catId, catName)
                    if (editId != null) {
                        change(editId, R.id.btnSectionEdit) { HomeLayout.setFilter(it, editId, filter) }
                    } else {
                        askName("Name the row", keepTitle ?: catName) { name ->
                            val id = UUID.randomUUID().toString()
                            change(HomeLayout.CUSTOM_PREFIX + id, R.id.btnSectionEdit) {
                                HomeLayout.addCustom(it, id, name, content, filter)
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun openItems(sectionId: String) {
        startActivity(
            Intent(this, HomeCollectionActivity::class.java)
                .putExtra(HomeCollectionActivity.EXTRA_TV, tv)
                .putExtra(HomeCollectionActivity.EXTRA_SECTION, sectionId)
        )
    }

    private fun pickStart() {
        val options = if (tv) {
            listOf(HomeLayout.StartScreen.HOME, HomeLayout.StartScreen.FAVORITES, HomeLayout.StartScreen.LIVE,
                HomeLayout.StartScreen.MOVIES, HomeLayout.StartScreen.SERIES)
        } else HomeLayout.StartScreen.entries.toList()
        AlertDialog.Builder(this)
            .setTitle("Open MKTV on")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(layout.start).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                change { HomeLayout.setStart(it, options[which]) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmReset() {
        val made = layout.sections.count { it.isCustom }
        val extra = if (made > 0) " The ${if (made == 1) "row" else "$made rows"} you made will be removed from Home." else ""
        AlertDialog.Builder(this)
            .setTitle("Reset Home?")
            .setMessage("Home goes back to MKTV's usual rows and order.$extra Your channels, favorites, movies, series, recordings and watch progress are not touched.")
            .setPositiveButton("Reset") { _, _ ->
                change { HomeLayout.reset(it) }
                binding.btnCustomizeReset.requestFocus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── List ────────────────────────────────────────────────────────────

    private inner class Holder(val b: ItemHomeSectionEditBinding) : RecyclerView.ViewHolder(b.root)

    private inner class SectionAdapter : RecyclerView.Adapter<Holder>() {
        var items: MutableList<HomeLayout.Section> = mutableListOf()

        fun submit(list: List<HomeLayout.Section>) {
            val old = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].id == list[n].id
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n] && o == n
            })
            items = list.toMutableList()
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val h = Holder(ItemHomeSectionEditBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            h.b.tvSectionHandle.visibility = if (touch) View.VISIBLE else View.GONE
            h.b.tvSectionHandle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) dragHelper.startDrag(h)
                false
            }
            fun current() = items.getOrNull(h.bindingAdapterPosition)
            h.b.btnSectionUp.setOnClickListener { current()?.let { s -> change(s.id, R.id.btnSectionUp) { HomeLayout.move(it, s.id, -1) } } }
            h.b.btnSectionDown.setOnClickListener { current()?.let { s -> change(s.id, R.id.btnSectionDown) { HomeLayout.move(it, s.id, 1) } } }
            h.b.btnSectionVisible.setOnClickListener {
                current()?.let { s -> change(s.id, R.id.btnSectionVisible) { HomeLayout.setVisible(it, s.id, !s.visible) } }
            }
            h.b.btnSectionEdit.setOnClickListener { current()?.let { editRow(it) } }
            return h
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val s = items[position]
            val title = s.displayTitle
            h.b.tvSectionTitle.text = title
            h.b.tvSectionTitle.alpha = if (s.visible) 1f else 0.55f
            h.b.tvSectionDetail.text = detailOf(s)
            h.b.btnSectionVisible.text = if (s.visible) "● SHOWN" else "○ HIDDEN"
            h.b.btnSectionVisible.contentDescription = if (s.visible) "$title is shown on Home. Hide it" else "$title is hidden. Show it on Home"
            h.b.btnSectionUp.alpha = if (position == 0) 0.4f else 1f
            h.b.btnSectionDown.alpha = if (position == items.lastIndex) 0.4f else 1f
            h.b.btnSectionUp.contentDescription = "Move $title up, now row ${position + 1} of ${items.size}"
            h.b.btnSectionDown.contentDescription = "Move $title down, now row ${position + 1} of ${items.size}"
            h.b.btnSectionEdit.contentDescription = "Edit $title"
            h.b.tvSectionHandle.contentDescription = "Drag to move $title"
        }
    }

    /** Touch drag by the handle: the list moves as the finger does; the new place is saved on release. */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        private var draggedId: String? = null

        override fun isLongPressDragEnabled() = false

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            draggedId = draggedId ?: adapter.items[a].id
            val moved = adapter.items.removeAt(a)
            adapter.items.add(b, moved)
            adapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            val id = draggedId ?: return
            draggedId = null
            val to = adapter.items.indexOfFirst { it.id == id }
            if (to >= 0) change { HomeLayout.moveTo(it, id, to) }
        }
    }

    companion object {
        /** True: the TV (and car box) layout; false: phone and tablet. */
        const val EXTRA_TV = "home_layout_tv"

        fun open(context: Context, tv: Boolean) {
            context.startActivity(Intent(context, HomeCustomizeActivity::class.java).putExtra(EXTRA_TV, tv))
        }
    }
}
