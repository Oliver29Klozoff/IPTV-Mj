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
import com.iptvapp.data.home.CardDensity
import com.iptvapp.data.home.HomeLayout
import com.iptvapp.data.home.HomeLayoutStore
import com.iptvapp.data.home.SmartRows
import com.iptvapp.data.home.TopFavorites
import com.iptvapp.data.home.TopFavoritesStore
import com.iptvapp.data.home.TvHomeMiniMode
import com.iptvapp.data.home.ViewingPersona
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityHomeCustomizeBinding
import com.iptvapp.databinding.ItemHomeSectionEditBinding
import com.iptvapp.databinding.ItemPersonaCardBinding
import com.iptvapp.util.isLargeScreenDevice
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.NonCancellable
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
    private var topFavorites = TopFavorites.State()
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
        smartRules = prefs.smartRowsNow()
        binding.btnCustomizeSmartRows.setOnClickListener { showSmartRowsDialog() }
        binding.btnCustomizeSmartRows.contentDescription = "Smart Rows: show or hide groups of rows, empty rows, finished items and cards per row"
        binding.btnCustomizeStart.setOnClickListener { pickStart() }
        binding.btnCustomizeReset.setOnClickListener { confirmReset() }
        if (tv) {
            binding.btnCustomizeMiniPlayer.visibility = View.VISIBLE
            binding.btnCustomizeMiniPlayer.setOnClickListener { pickMiniPlayer() }
            showMiniPlayerMode(TvHomeMiniMode.of(prefs.tvHomeMiniPlayerModeNow()))
            // The sidebar's Home: a press switches it. Hiding it moves a Home start to Favorites.
            binding.btnCustomizeHomeTab.visibility = View.VISIBLE
            binding.btnCustomizeHomeTab.setOnClickListener {
                val show = !layout.showHomeTab
                // Customize Sidebar may have hidden Favorites, Guide and Live: Home is then the last way in.
                val sidebar = com.iptvapp.data.home.TvSidebar.withHome(prefs.tvSidebarConfigNow(), true)
                if (!show && !com.iptvapp.data.home.TvSidebar.canHide(sidebar, com.iptvapp.data.home.TvSidebar.Entry.HOME.id)) {
                    Toast.makeText(this, "Keep at least one of Home, Favorites, Guide or Live in the sidebar. Show another in Customize Sidebar first.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                change { HomeLayout.setShowHomeTab(it, show) }
                if (!show) Toast.makeText(this, "Home is hidden from the sidebar. Customize Home is in Settings → Display.", Toast.LENGTH_LONG).show()
            }
            // The rest of the sidebar: its order and which entries show.
            binding.btnCustomizeSidebar.visibility = View.VISIBLE
            binding.btnCustomizeSidebar.setOnClickListener { TvSidebarCustomizeActivity.open(this) }
        }
        buildPersonaCards()
        setupDensity()
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
            topFavorites = TopFavoritesStore.load(prefs)
            layout = HomeLayoutStore.load(prefs, tv)
            render(focus = null)
            // Customize Sidebar (opened from here) may have made the settings Custom.
            refreshPersona()
            refreshTopDetail()
            // A container (the button bar) can take first focus on TV; start on DONE instead.
            if (currentFocus?.isClickable != true) binding.btnCustomizeDone.requestFocus()
        }
    }

    // ── Changes ─────────────────────────────────────────────────────────

    /** Saves [change] and redraws, keeping focus on [focusButton] of row [focusId] (it may have moved). */
    private fun change(focusId: String? = null, focusButton: Int = 0, change: (HomeLayout.Layout) -> HomeLayout.Layout) {
        lifecycleScope.launch {
            layout = HomeLayoutStore.update(prefs, tv, change)
            render(if (focusId != null) focusId to focusButton else null)
            // A change by hand makes the settings Custom (PreferencesManager.setHomeLayoutJson).
            refreshPersona()
        }
    }

    // ── Viewing style ───────────────────────────────────────────────────

    private var persona = ViewingPersona.CUSTOM
    private val personaCards = LinkedHashMap<ViewingPersona, ItemPersonaCardBinding>()

    /** One card per viewing style, left to right; Down goes to the buttons, Up from them to the style in use. */
    private fun buildPersonaCards() {
        val parent = binding.personaCards
        persona = prefs.viewingPersonaNow(tv)
        // A short phone held sideways: titles only, so the rows keep room.
        val short = resources.configuration.screenHeightDp < 480
        for (p in ViewingPersona.entries) {
            val card = ItemPersonaCardBinding.inflate(layoutInflater, parent, false)
            card.root.id = View.generateViewId()
            card.tvPersonaBadge.text = p.badge
            card.tvPersonaTitle.setText(p.titleRes)
            card.tvPersonaDesc.text = getString(if (tv) p.tvDescRes else p.phoneDescRes)
            if (short) card.tvPersonaDesc.visibility = View.GONE
            card.root.setOnClickListener { pickPersona(p) }
            parent.addView(card.root)
            personaCards[p] = card
        }
        renderPersona()
    }

    private suspend fun refreshPersona() {
        persona = prefs.viewingPersona(tv).first()
        renderPersona()
    }

    private fun renderPersona() {
        for ((p, card) in personaCards) {
            val active = p == persona
            card.root.isActivated = active
            card.tvPersonaActive.visibility = if (active) View.VISIBLE else View.GONE
            card.root.contentDescription = getString(
                if (active) R.string.persona_card_description_active else R.string.persona_card_description,
                getString(p.titleRes), card.tvPersonaDesc.text,
            )
        }
        linkFocus()
    }

    // ── Card Density & Size ─────────────────────────────────────────────

    private var density = CardDensity.STANDARD
    private val densityChips by lazy {
        linkedMapOf(
            CardDensity.COMPACT to binding.btnDensityCompact,
            CardDensity.STANDARD to binding.btnDensityStandard,
            CardDensity.EXPANDED to binding.btnDensityExpanded,
        )
    }
    /** A short screen (a phone held sideways) gets the CARDS chip instead of the three choices, so the rows keep room. */
    private val shortScreen by lazy { resources.configuration.screenHeightDp < 480 }

    private fun setupDensity() {
        density = prefs.cardDensityNow()
        if (shortScreen) {
            binding.densityBlock.visibility = View.GONE
            binding.btnCustomizeDensity.visibility = View.VISIBLE
            binding.btnCustomizeDensity.setOnClickListener { pickDensityDialog() }
        }
        for ((d, chip) in densityChips) chip.setOnClickListener { setDensity(d) }
        renderDensity()
    }

    private fun setDensity(d: CardDensity) {
        if (d == density) return
        density = d
        renderDensity()
        // Saved even if Done is pressed straight away; Home rebuilds its cards when it shows again.
        lifecycleScope.launch(NonCancellable) { prefs.setCardDensity(d) }
    }

    private fun describe(d: CardDensity): String {
        val text = getString(d.descResId)
        return if (tv) text + " " + getString(R.string.density_tv_grid, d.gridSpan()) else text
    }

    private fun renderDensity() {
        for ((d, chip) in densityChips) {
            val selected = d == density
            chip.isActivated = selected
            chip.contentDescription = getString(
                if (selected) R.string.density_option_selected else R.string.density_option,
                getString(d.titleResId), describe(d),
            )
        }
        binding.tvDensityDescription.text = describe(density)
        val name = getString(density.titleResId)
        binding.btnCustomizeDensity.text = getString(R.string.density_chip, name.uppercase())
        binding.btnCustomizeDensity.contentDescription = getString(R.string.density_option_selected, name, describe(density))
        linkFocus()
    }

    private fun pickDensityDialog() {
        val options = CardDensity.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.density_dialog_title)
            .setSingleChoiceItems(options.map { getString(it.titleResId) }.toTypedArray(), options.indexOf(density)) { dlg, which ->
                dlg.dismiss()
                setDensity(options[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The remote's Up and Down between the viewing styles, the card sizes and the button row: Down
     * from a style goes to the selected size, Down from a size to the buttons; Up comes back the
     * same way, each time to the one selected.
     */
    private fun linkFocus() {
        val styleId = personaCards[persona]?.root?.id ?: View.NO_ID
        val sizeId = if (shortScreen) View.NO_ID else densityChips[density]?.id ?: View.NO_ID
        for (card in personaCards.values) card.root.nextFocusDownId = if (sizeId != View.NO_ID) sizeId else R.id.btnCustomizeDone
        for (chip in densityChips.values) {
            chip.nextFocusUpId = styleId
            chip.nextFocusDownId = R.id.btnCustomizeDone
        }
        val up = if (sizeId != View.NO_ID) sizeId else styleId
        val bar = binding.btnCustomizeDone.parent as ViewGroup
        for (i in 0 until bar.childCount) bar.getChildAt(i).nextFocusUpId = up
    }

    private fun pickPersona(p: ViewingPersona) {
        if (p == persona) return
        if (p == ViewingPersona.CUSTOM) {
            applyPersona(p)
            Toast.makeText(this, R.string.persona_custom_kept, Toast.LENGTH_SHORT).show()
            return
        }
        val name = getString(p.titleRes)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.persona_apply_title, name))
            .setMessage(if (tv) R.string.persona_apply_message_tv else R.string.persona_apply_message_phone)
            .setPositiveButton(R.string.persona_apply) { _, _ ->
                applyPersona(p)
                Toast.makeText(this, getString(R.string.persona_applied, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    /** Saves the persona in one write, then redraws everything it may have changed. */
    private fun applyPersona(p: ViewingPersona) {
        lifecycleScope.launch {
            layout = HomeLayoutStore.applyPersona(prefs, tv, p)
            render(focus = null)
            if (tv) showMiniPlayerMode(TvHomeMiniMode.of(prefs.tvHomeMiniPlayerMode.first()))
            refreshPersona()
            personaCards[persona]?.root?.requestFocus()
        }
    }

    private fun render(focus: Pair<String, Int>?) {
        adapter.submit(layout.sections)
        binding.btnCustomizeStart.text = "START ON: ${layout.opensOn.label.uppercase()}"
        binding.btnCustomizeStart.contentDescription = "MKTV opens on ${layout.opensOn.label}. Change it"
        binding.btnCustomizeHomeTab.text = if (layout.showHomeTab) "HOME TAB: SHOWN" else "HOME TAB: HIDDEN"
        binding.btnCustomizeHomeTab.contentDescription =
            if (layout.showHomeTab) "Home is in the sidebar. Hide it" else "Home is hidden from the sidebar. Show it"
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
            s.builtin == HomeLayout.Builtin.TOP_FAVORITES ->
                parts += "Built in · Channels you pick · ${topFavorites.items.size} · ${topFavorites.sort.label}"
            !s.isCustom -> parts += "Built in"
            f?.gone == true -> parts += "Provider removed"
            f != null -> parts += "$what from ${f.categoryName} (${providerName(f.serverIndex)})"
            else -> parts += "${what ?: "Items"} you pick · ${s.items.size}"
        }
        if (HomeLayout.styles(s.contentType).size > 1) parts += s.style.label
        if (SmartRows.keepsEmpty(smartRules, s)) parts += "Shown when empty"
        if (!s.visible) parts += "Hidden"
        return parts.joinToString(" · ")
    }

    /** Top Favorites' line counts its channels, which live outside the layout, so the diff can't see them change. */
    private fun refreshTopDetail() {
        val pos = adapter.items.indexOfFirst { it.builtin == HomeLayout.Builtin.TOP_FAVORITES }
        if (pos >= 0) adapter.notifyItemChanged(pos)
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
        if (s.builtin == HomeLayout.Builtin.TOP_FAVORITES) {
            labels += "Choose channels (${topFavorites.items.size})"; actions += { openItems(s.id) }
            labels += "Order: ${topFavorites.sort.label}"
            actions += {
                TopFavoritesMenu.pickSort(this, prefs, topFavorites.sort) {
                    lifecycleScope.launch {
                        topFavorites = TopFavoritesStore.load(prefs)
                        refreshTopDetail()
                        render(s.id to R.id.btnSectionEdit)
                    }
                }
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
        if (smartRules.autoCollapseEmpty) {
            // Smart Rows' rule wins over the row's own choice while it is on.
            labels += getString(R.string.smart_rows_when_empty_overridden)
            actions += { showSmartRowsDialog() }
        } else {
            labels += if (s.showWhenEmpty) "When empty: show a hint" else "When empty: hide the row"
            actions += { change(s.id, R.id.btnSectionEdit) { HomeLayout.setShowWhenEmpty(it, s.id, !s.showWhenEmpty) } }
        }
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
        // TV: the last channel full screen and the guide grid too; Home only while its tab shows.
        val options = if (tv) {
            HomeLayout.StartScreen.entries.filter { it != HomeLayout.StartScreen.HOME || layout.showHomeTab }
        } else HomeLayout.StartScreen.entries.filter { it != HomeLayout.StartScreen.FULLSCREEN_LIVE }
        AlertDialog.Builder(this)
            .setTitle("Open MKTV on")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(layout.opensOn)) { d, which ->
                d.dismiss()
                change { HomeLayout.setStart(it, options[which]) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMiniPlayerMode(mode: TvHomeMiniMode) {
        binding.btnCustomizeMiniPlayer.text = "PLAYER: ${mode.name}"
        binding.btnCustomizeMiniPlayer.contentDescription = "Home Mini Player Display: ${mode.label}. Change it"
    }

    /** TV Home's mini player: Hero, Compact or Off. Saved at once; Home applies it when it shows again. */
    private fun pickMiniPlayer() {
        val options = TvHomeMiniMode.entries
        val current = TvHomeMiniMode.of(prefs.tvHomeMiniPlayerModeNow())
        AlertDialog.Builder(this)
            .setTitle("Home Mini Player Display")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(current)) { d, which ->
                d.dismiss()
                val mode = options[which]
                showMiniPlayerMode(mode)
                lifecycleScope.launch {
                    prefs.setTvHomeMiniPlayerMode(mode.key)
                    refreshPersona()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Smart Rows ──────────────────────────────────────────────────────

    private var smartRules = SmartRows.Settings()

    /**
     * Smart Rows: Show all / Hide all for the live TV rows and for the movies and series rows,
     * the empty-row and finished-item rules, and cards per row. Choices are held until Save, then
     * saved together; Cancel changes nothing. A group choice changes only those rows' Shown/Hidden.
     */
    private fun showSmartRowsDialog() {
        val b = com.iptvapp.databinding.DialogSmartRowsBinding.inflate(layoutInflater)
        var rules = smartRules
        var live: Boolean? = null
        var vod: Boolean? = null
        b.tvSmartRowsLiveList.text = HomeLayout.LIVE_BUILTINS.joinToString(", ") { it.title }
        b.tvSmartRowsVodList.text = HomeLayout.VOD_BUILTINS.joinToString(", ") { it.title }
        val limits = listOf(b.btnSmartLimit10, b.btnSmartLimit20, b.btnSmartLimit30).zip(SmartRows.LIMITS)

        fun groupChip(chip: View, group: String, label: Int, chosen: Boolean) {
            chip.isActivated = chosen
            val text = getString(R.string.smart_rows_group_description, group, getString(label))
            chip.contentDescription = if (chosen) getString(R.string.smart_rows_group_chosen, text) else text
        }
        fun render() {
            groupChip(b.btnSmartLiveShow, "Live TV", R.string.smart_rows_show_all, live == true)
            groupChip(b.btnSmartLiveHide, "Live TV", R.string.smart_rows_hide_all, live == false)
            groupChip(b.btnSmartVodShow, "Movies and series", R.string.smart_rows_show_all, vod == true)
            groupChip(b.btnSmartVodHide, "Movies and series", R.string.smart_rows_hide_all, vod == false)
            b.btnSmartCollapse.isActivated = rules.autoCollapseEmpty
            b.btnSmartCollapse.setText(if (rules.autoCollapseEmpty) R.string.smart_rows_collapse_on else R.string.smart_rows_collapse_off)
            b.btnSmartCollapse.contentDescription = getString(R.string.smart_rows_collapse_description,
                getString(if (rules.autoCollapseEmpty) R.string.smart_rows_on else R.string.smart_rows_off))
            b.btnSmartHideFinished.isActivated = rules.hideCompleted
            b.btnSmartHideFinished.setText(if (rules.hideCompleted) R.string.smart_rows_finished_on else R.string.smart_rows_finished_off)
            b.btnSmartHideFinished.contentDescription = getString(R.string.smart_rows_finished_description,
                getString(if (rules.hideCompleted) R.string.smart_rows_on else R.string.smart_rows_off))
            for ((chip, n) in limits) {
                chip.isActivated = n == rules.limit
                chip.text = getString(if (n == SmartRows.DEFAULT_LIMIT) R.string.smart_rows_limit_default else R.string.smart_rows_limit, n)
                chip.contentDescription = getString(R.string.smart_rows_limit_description, n) +
                    if (n == rules.limit) ", selected" else ""
            }
        }
        // A group chip pressed again clears the choice: that group stays as it is.
        b.btnSmartLiveShow.setOnClickListener { live = if (live == true) null else true; render() }
        b.btnSmartLiveHide.setOnClickListener { live = if (live == false) null else false; render() }
        b.btnSmartVodShow.setOnClickListener { vod = if (vod == true) null else true; render() }
        b.btnSmartVodHide.setOnClickListener { vod = if (vod == false) null else false; render() }
        b.btnSmartCollapse.setOnClickListener { rules = rules.copy(autoCollapseEmpty = !rules.autoCollapseEmpty); render() }
        b.btnSmartHideFinished.setOnClickListener { rules = rules.copy(hideCompleted = !rules.hideCompleted); render() }
        for ((chip, n) in limits) chip.setOnClickListener { rules = rules.copy(limit = n); render() }
        render()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.smart_rows_title)
            .setView(b.root)
            .setPositiveButton(R.string.smart_rows_save) { _, _ -> saveSmartRows(rules, live, vod) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // The remote starts on the first choice, not on the dialog's buttons.
        b.btnSmartLiveShow.requestFocus()
        dialog.setOnDismissListener { binding.btnCustomizeSmartRows.requestFocus() }
    }

    private fun saveSmartRows(rules: SmartRows.Settings, live: Boolean?, vod: Boolean?) {
        val rulesChanged = rules != smartRules
        smartRules = rules
        if (live != null || vod != null) {
            change {
                var l = it
                if (live != null) l = HomeLayout.setGroupVisibility(l, HomeLayout.LIVE_BUILTINS, live)
                if (vod != null) l = HomeLayout.setGroupVisibility(l, HomeLayout.VOD_BUILTINS, vod)
                l
            }
        }
        // The rows' detail lines mention the empty-row rule, which the diff of the rows can't see.
        if (rulesChanged) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        if (rulesChanged) lifecycleScope.launch(NonCancellable) { prefs.setSmartRows(rules) }
        Toast.makeText(this, R.string.smart_rows_updated, Toast.LENGTH_SHORT).show()
    }

    private fun confirmReset() {
        val made = layout.sections.count { it.isCustom }
        val extra = if (made > 0) " The ${if (made == 1) "row" else "$made rows"} you made will be removed from Home." else ""
        AlertDialog.Builder(this)
            .setTitle("Reset Home?")
            .setMessage("Home goes back to MKTV's usual rows, order, card size and Smart Rows rules.$extra Your channels, favorites, movies, series, recordings and watch progress are not touched.")
            .setPositiveButton("Reset") { _, _ ->
                change { HomeLayout.reset(it) }
                // The card size and the Smart Rows rules are part of how Home ships too.
                setDensity(CardDensity.STANDARD)
                if (smartRules != SmartRows.Settings()) {
                    smartRules = SmartRows.Settings()
                    lifecycleScope.launch(NonCancellable) { prefs.setSmartRows(SmartRows.Settings()) }
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
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
