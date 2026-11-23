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
import androidx.lifecycle.repeatOnLifecycle
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
class HomeCustomizeActivity : com.iptvapp.ui.MktvActivity() {

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
        binding.tvHomeRowsHelp.setText(if (tv) R.string.studio_home_rows_help_tv else R.string.studio_home_rows_help_phone)
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
                if (!show) Toast.makeText(this, "Home is hidden from the sidebar. Personalize MKTV is in Settings → Personalize.", Toast.LENGTH_LONG).show()
            }
            // The rest of the sidebar: its order and which entries show.
            binding.btnCustomizeSidebar.visibility = View.VISIBLE
            binding.btnCustomizeSidebar.setOnClickListener { TvSidebarCustomizeActivity.open(this) }
        }
        buildPersonaCards()
        setupDensity()
        setupLook()
        setupRemote()
        setupAccessProfiles()
        binding.btnCustomizeReset.contentDescription = "Reset Home to how MKTV ships it"
        setupTabs(PersonalizeStudio.Tab.of(
            savedInstanceState?.getInt(STATE_TAB) ?: intent.getIntExtra(EXTRA_INITIAL_TAB, 0)
        ))
        watchTextSize()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab.ordinal)
    }

    // ── Tabs ────────────────────────────────────────────────────────────

    private var tab = PersonalizeStudio.Tab.LAYOUT

    private fun setupTabs(first: PersonalizeStudio.Tab) {
        PersonalizeStudio.tabs(binding).forEachIndexed { i, t ->
            t.setOnClickListener { selectTab(PersonalizeStudio.Tab.entries[i], requestFocus = false) }
        }
        selectTab(first, requestFocus = !touch)
    }

    /** Opens [t]'s panel; on a remote, focus goes to its tab so Down enters the panel. */
    private fun selectTab(t: PersonalizeStudio.Tab, requestFocus: Boolean) {
        tab = t
        PersonalizeStudio.show(binding, t)
        paintTabs()
        // Up from the top of the open panel goes back to its own tab.
        val tabId = PersonalizeStudio.tabs(binding)[t.ordinal].id
        for (card in personaCards.values) card.root.nextFocusUpId = tabId
        if (requestFocus) PersonalizeStudio.tabs(binding)[t.ordinal].requestFocus()
    }

    /** The open tab in the accent (outlined, accent text); the others plain. */
    private fun paintTabs() {
        val accent = com.iptvapp.ui.theme.ThemePicker.accentOf(accentStart, accentEnd)
        PersonalizeStudio.tabs(binding).forEachIndexed { i, v ->
            val t = v as android.widget.TextView
            val open = i == tab.ordinal
            t.isActivated = open
            if (open) com.iptvapp.util.AccentText.apply(t, accent.start, accent.end)
            else com.iptvapp.util.AccentText.plain(t, getColorStateList(R.color.rack_text))
            t.contentDescription = getString(
                if (open) R.string.studio_tab_description_open else R.string.studio_tab_description, t.text
            )
        }
    }

    /**
     * The remote: Down from a tab goes into its panel (to the first thing in it), and Back from
     * inside a panel returns to the open tab; Back on the tabs closes the screen.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && !binding.root.isInTouchMode) {
            val panel = PersonalizeStudio.panels(binding)[tab.ordinal]
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (binding.studioTabBar.hasFocus()) {
                    focusFirstIn(panel)?.let { it.requestFocus(); return true }
                }
                android.view.KeyEvent.KEYCODE_BACK -> if (panel.hasFocus()) {
                    PersonalizeStudio.tabs(binding)[tab.ordinal].requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** The first focusable, shown view under [view] in layout order (the chosen style on the Layout tab). */
    private fun focusFirstIn(view: View): View? {
        if (view === binding.panelLayout) personaCards[persona]?.root?.let { return it }
        if (view !is ViewGroup || view.visibility != View.VISIBLE) return null
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            // Scrolling containers are focusable too: look inside them, never stop on one.
            if (child.isFocusable && child.isEnabled && child !is android.widget.FrameLayout) return child
            focusFirstIn(child)?.let { return it }
        }
        return null
    }

    /** The line under the title: screen, profile, viewing style and theme. */
    private fun refreshSummary() {
        val profile = runCatching { com.iptvapp.data.profile.ProfileStore.activeNow(prefs) }.getOrNull()
        binding.tvCustomizeSubtitle.text = PersonalizeStudio.summary(
            getString(if (tv) R.string.studio_screen_tv else R.string.studio_screen_phone),
            profile?.name, getString(persona.titleRes), getString(prefs.baseThemeNow().titleRes),
        )
    }

    /**
     * Senior Mode and the text size rebuild every screen at the new size. This one rebuilds at
     * once (on the tab that was open), as Settings does; others when they next show.
     */
    private fun watchTextSize() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                prefs.accessibility.collect { s ->
                    renderAccess(s)
                    com.iptvapp.util.AccessibilityEngine.refreshFocus(this@HomeCustomizeActivity, s.highContrastFocus)
                    if (builtWith != null && s.screenSignature != builtWith) {
                        kotlinx.coroutines.withTimeoutOrNull(1_000) {
                            while (prefs.accessibilityNow() != s) kotlinx.coroutines.delay(16)
                        }
                        intent.putExtra(EXTRA_INITIAL_TAB, tab.ordinal)
                        recreate()
                    }
                }
            }
        }
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
            // Back from the profile switcher or Settings: the profile and accent may have changed.
            renderProfile()
            renderLcn()
            // A container (the button bar) can take first focus on TV; start on the open tab instead.
            if (!touch && currentFocus?.isClickable != true) PersonalizeStudio.tabs(binding)[tab.ordinal].requestFocus()
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
        refreshSummary()
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
    private fun setupDensity() {
        density = prefs.cardDensityNow()
        for ((d, chip) in densityChips) chip.setOnClickListener { setDensity(d) }
        renderDensity()
    }

    private fun setDensity(d: CardDensity) {
        if (d == density) return
        density = d
        renderDensity()
        paintPreview()
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
    }

    /**
     * The remote on the Layout tab: Down from a viewing style goes to the button row, and Up from
     * any button back to the style in use (Up from a style goes to the tab: selectTab).
     */
    private fun linkFocus() {
        val styleId = personaCards[persona]?.root?.id ?: View.NO_ID
        for (card in personaCards.values) card.root.nextFocusDownId = R.id.btnCustomizeStart
        val bar = binding.layoutOptionsBar
        for (i in 0 until bar.childCount) bar.getChildAt(i).nextFocusUpId = styleId
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

    // ── Look & Feel ─────────────────────────────────────────────────────

    private var theme = com.iptvapp.data.theme.AppBaseTheme.OLED_BLACK
    private var accentStart = com.iptvapp.util.AccentColors.DEFAULT_HEX
    private var accentEnd = ""

    /**
     * The base theme, the curated accents (Settings has the custom color too) and the card sizes,
     * each saved as it is pressed, with the sample card painted from all three.
     */
    private fun setupLook() {
        theme = prefs.baseThemeNow()
        lifecycleScope.launch {
            accentStart = com.iptvapp.util.AccentColors.validOr(prefs.accentColor.first(), com.iptvapp.util.AccentColors.DEFAULT_HEX)
            accentEnd = com.iptvapp.util.AccentColors.validOr(prefs.accentColorEnd.first(), "")
            renderLook(focus = null)
            paintTabs()
        }
        renderLook(focus = null)
    }

    /** Rebuilds the theme cards and accent chips (focus back on [focus]'s replacement) and the sample. */
    private fun renderLook(focus: Any?) {
        val accent = com.iptvapp.ui.theme.ThemePicker.accentOf(accentStart, accentEnd)
        val themes = binding.studioThemes
        com.iptvapp.ui.theme.ThemePicker.buildBaseThemes(themes, theme, accent) { picked ->
            if (picked == theme) return@buildBaseThemes
            theme = picked
            com.iptvapp.util.ThemeEngine.apply(this, binding.root, picked)
            lifecycleScope.launch(NonCancellable) { prefs.setBaseTheme(picked) }
            renderLook(focus = picked)
            refreshSummary()
        }
        val accents = binding.studioAccents
        accents.removeAllViews()
        accents.addView(com.iptvapp.ui.theme.ThemePicker.buildCuratedAccents(this, accentStart, accentEnd) { preset ->
            accentStart = preset.start
            accentEnd = preset.end ?: ""
            lifecycleScope.launch(NonCancellable) {
                if (accentEnd.isNotEmpty()) prefs.setAccentGradient(accentStart, accentEnd) else prefs.setAccentColor(accentStart)
            }
            renderLook(focus = preset)
            paintTabs()
        })
        paintPreview()
        // The rebuilt cards and chips replace the focused one: keep the remote on the new choice.
        when (focus) {
            is com.iptvapp.data.theme.AppBaseTheme ->
                themes.getChildAt(com.iptvapp.data.theme.AppBaseTheme.entries.indexOf(focus))?.requestFocus()
            is com.iptvapp.data.theme.ThemePalette.AccentPreset ->
                (accents.getChildAt(0) as? ViewGroup)?.getChildAt(
                    com.iptvapp.data.theme.ThemePalette.PRESET_ACCENTS.indexOf(focus)
                )?.requestFocus()
        }
    }

    /** The sample card in the chosen theme and accent, at the chosen card size. */
    private fun paintPreview() {
        val preview = binding.livePreviewContainer.root
        val accent = com.iptvapp.ui.theme.ThemePicker.accentOf(accentStart, accentEnd)
        com.iptvapp.ui.theme.ThemePicker.paintPreview(preview, theme, accent,
            com.iptvapp.ui.theme.ThemePicker.accentName(this, accentStart, accentEnd))
        val d = resources.displayMetrics.density
        val logo = binding.livePreviewContainer.themePreviewLogo
        val side = (52 * density.scaleMultiplier * d).toInt()
        logo.layoutParams = logo.layoutParams.apply { width = side; height = side }
        logo.textSize = 20f * density.scaleMultiplier
        val pad = (density.cardPaddingDp * d).toInt()
        binding.livePreviewContainer.themePreviewCard.setPadding(pad, pad, pad, pad)
        binding.livePreviewContainer.themePreviewTitle.textSize = density.titleSp
        binding.livePreviewContainer.themePreviewSubtitle.textSize = density.lineSp
        preview.contentDescription = "${preview.contentDescription} ${getString(density.titleResId)}"
    }

    // ── Remote & Navigation ─────────────────────────────────────────────

    private val lcnOptions = LinkedHashMap<com.iptvapp.data.channel.LcnMode, android.widget.TextView>()

    private fun setupRemote() {
        for (mode in com.iptvapp.data.channel.LcnMode.entries) {
            val option = layoutInflater.inflate(R.layout.item_studio_option, binding.lcnModeOptions, false) as android.widget.TextView
            option.id = View.generateViewId()
            option.setText(mode.titleResId)
            option.setOnClickListener {
                if (mode == prefs.lcnModeNow()) return@setOnClickListener
                lifecycleScope.launch {
                    prefs.setLcnMode(mode)
                    renderLcn(mode)
                }
            }
            binding.lcnModeOptions.addView(option)
            lcnOptions[mode] = option
        }
        binding.btnDirectTuning.setOnClickListener {
            val on = !prefs.directNumberTuningEnabledNow()
            lifecycleScope.launch {
                prefs.setDirectNumberTuning(on)
                renderLcn()
            }
        }
        binding.btnStudioAutoNumber.setOnClickListener { autoNumberFavorites() }
        if (tv) {
            binding.studioTvNavBlock.visibility = View.VISIBLE
            binding.btnStudioSidebar.setOnClickListener { TvSidebarCustomizeActivity.open(this) }
        }
        renderLcn()
    }

    private fun renderLcn(mode: com.iptvapp.data.channel.LcnMode = prefs.lcnModeNow()) {
        for ((m, option) in lcnOptions) {
            val chosen = m == mode
            option.isActivated = chosen
            val name = getString(m.titleResId)
            option.contentDescription = (if (chosen) getString(R.string.studio_option_selected, name) else name) +
                ". " + getString(m.descResId)
        }
        binding.tvLcnModeDescription.setText(mode.descResId)
        val direct = prefs.directNumberTuningEnabledNow()
        binding.btnDirectTuning.isActivated = direct
        binding.btnDirectTuning.setText(if (direct) R.string.studio_direct_tuning_on else R.string.studio_direct_tuning_off)
    }

    /** Auto-Number Favorites, as in Settings: favorites become 1, 2, 3… in their own order. */
    private fun autoNumberFavorites() {
        lifecycleScope.launch {
            val count = repository.getFavoriteChannels().first().size
            if (count == 0) {
                Toast.makeText(this@HomeCustomizeActivity, R.string.lcn_auto_number_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@HomeCustomizeActivity)
                .setTitle(getString(R.string.lcn_auto_number_confirm_title, count))
                .setMessage(getString(R.string.lcn_auto_number_confirm_msg, count))
                .setPositiveButton(R.string.lcn_auto_number_go) { _, _ ->
                    val app = applicationContext
                    lifecycleScope.launch {
                        // Finishes even if this screen closes: half a renumbering would leave clashing numbers.
                        val n = kotlinx.coroutines.withContext(NonCancellable) { repository.backfillSequentialFavoriteNumbers() }
                        Toast.makeText(
                            app,
                            if (n == 0) app.getString(R.string.lcn_auto_number_none) else app.getString(R.string.lcn_auto_number_done, n),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ── Accessibility & Profiles ────────────────────────────────────────

    @Inject lateinit var profileSwitcher: com.iptvapp.data.profile.ProfileSwitcher

    private val textSizeChips by lazy {
        linkedMapOf(
            com.iptvapp.data.accessibility.TextSizeLevel.STANDARD to binding.btnTextStandard,
            com.iptvapp.data.accessibility.TextSizeLevel.LARGE to binding.btnTextLarge,
            com.iptvapp.data.accessibility.TextSizeLevel.EXTRA_LARGE to binding.btnTextExtraLarge,
        )
    }

    /** Each choice is saved at once; a new text size rebuilds this screen (watchTextSize). */
    private fun setupAccessProfiles() {
        binding.btnStudioSwitchProfile.setOnClickListener {
            com.iptvapp.ui.profile.ProfileUi.showSwitcher(this, prefs, profileSwitcher)
        }
        binding.btnSeniorMode.setOnClickListener {
            val on = !prefs.accessibilityNow().seniorMode
            lifecycleScope.launch { prefs.setSeniorMode(on) }
            Toast.makeText(this, if (on) R.string.a11y_senior_on else R.string.a11y_senior_off, Toast.LENGTH_SHORT).show()
        }
        for ((level, chip) in textSizeChips) chip.setOnClickListener {
            if (prefs.accessibilityNow().textSize != level) lifecycleScope.launch { prefs.setUiFontScale(level) }
        }
        binding.btnBannerStandard.setOnClickListener { lifecycleScope.launch { prefs.setExtendedOsdTimeout(false) } }
        binding.btnBannerExtended.setOnClickListener { lifecycleScope.launch { prefs.setExtendedOsdTimeout(true) } }
        binding.btnHighContrastFocus.setOnClickListener {
            val on = !prefs.accessibilityNow().highContrastFocus
            lifecycleScope.launch { prefs.setHighContrastFocus(on) }
        }
        renderProfile()
        renderAccess(prefs.accessibilityNow())
    }

    private fun renderProfile() {
        val profile = runCatching { com.iptvapp.data.profile.ProfileStore.activeNow(prefs) }.getOrNull() ?: return
        com.iptvapp.ui.profile.ProfileUi.bindAvatar(binding.tvStudioAvatar, profile)
        binding.tvStudioProfileName.text = profile.name
        binding.tvStudioProfileNote.setText(if (profile.isKids) R.string.studio_profile_kids else R.string.studio_profile_adult)
        refreshSummary()
    }

    private fun renderAccess(s: com.iptvapp.data.accessibility.AccessibilitySettings) {
        binding.btnSeniorMode.isActivated = s.seniorMode
        binding.btnSeniorMode.setText(if (s.seniorMode) R.string.studio_senior_on else R.string.studio_senior_off)
        for ((level, chip) in textSizeChips) {
            val chosen = level == s.textSize
            chip.isActivated = chosen
            val name = getString(level.labelRes)
            chip.contentDescription = getString(R.string.studio_text_size_heading) + ": " +
                if (chosen) getString(R.string.studio_option_selected, name) else name
        }
        fun banner(chip: android.widget.TextView, chosen: Boolean, label: Int) {
            chip.isActivated = chosen
            val name = getString(label)
            chip.contentDescription = getString(R.string.studio_banner_heading) + ": " +
                if (chosen) getString(R.string.studio_option_selected, name) else name
        }
        banner(binding.btnBannerStandard, !s.extendedOsd, R.string.a11y_banner_standard)
        banner(binding.btnBannerExtended, s.extendedOsd, R.string.a11y_banner_extended)
        binding.btnHighContrastFocus.isActivated = s.highContrastFocus
        binding.btnHighContrastFocus.setText(if (s.highContrastFocus) R.string.studio_focus_on else R.string.studio_focus_off)
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
        /** The tab to open on (PersonalizeStudio.Tab ordinal): 0 Layout, 1 Look & Feel, 2 Remote, 3 Accessibility & Profiles. */
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"
        private const val STATE_TAB = "studio_tab"

        fun open(context: Context, tv: Boolean, initialTab: Int = 0) {
            context.startActivity(intent(context, tv, initialTab))
        }

        fun intent(context: Context, tv: Boolean, initialTab: Int = 0): Intent =
            Intent(context, HomeCustomizeActivity::class.java)
                .putExtra(EXTRA_TV, tv)
                .putExtra(EXTRA_INITIAL_TAB, initialTab)
    }
}
