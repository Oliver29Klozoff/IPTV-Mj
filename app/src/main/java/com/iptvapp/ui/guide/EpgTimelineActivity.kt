package com.iptvapp.ui.guide

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.entities.EpgEntity
import com.iptvapp.databinding.ActivityEpgTimelineBinding
import com.iptvapp.ui.home.HomeViewModel
import com.iptvapp.util.RackAccent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private fun epgMs(ts: Long) = if (ts < 100_000_000_000L) ts * 1000L else ts

/** Identifies one program block across rebinds (selection survives the minute refresh). */
private fun programKey(row: GuideRow, program: EpgEntity) =
    "${row.serverIndex}:${row.streamId}:${program.startTimestamp}"

@AndroidEntryPoint
class EpgTimelineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEpgTimelineBinding
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: TimelineAdapter

    @Inject lateinit var db: IptvDatabase
    @Inject lateinit var prefs: com.iptvapp.data.local.PreferencesManager

    // 4dp per minute — 30min=120dp, 1hr=240dp
    private val dpPerMin = 4f
    // Show from 3 hours ago to 9 hours from now
    private val hoursBack = 3
    private val hoursAhead = 9

    // 0 = today, +1 = tomorrow, -1 = yesterday, etc. The window itself always starts
    // hoursBack before "now" ON that day, same shape as before — paging days just shifts
    // which day "now" is computed relative to, rather than changing the window's length.
    // Whether tomorrow/yesterday actually has any programs depends entirely on how far out
    // this device's configured EPG sources reach — get_short_epg typically only covers a few
    // hours ahead, so most users will only see real data for today+part of tomorrow unless
    // they have an XMLTV source configured with a longer guide. Paging still works either
    // way; a day with no cached data just renders an empty grid, same as a live EPG app would.
    private var dayOffset = 0

    // v6.91 redesign state: the accent (chips, Watch button, live blocks), the genre pill that's
    // on, the primary provider's category names (genres are guessed from them), and the program
    // the detail panel describes.
    private var accent = RackAccent.Accent(0xFF06B6D4.toInt(), null)
    private var genre = GENRE_ALL
    private var categoryNames: Map<String, String> = emptyMap()
    private var selected: Pair<GuideRow, EpgEntity>? = null

    private val nowMs get() = System.currentTimeMillis()
    // Fixed when the grid is drawn (and on a day change) — not recomputed on every read, which
    // moved the now-line an hour off the header and blocks once the clock crossed the hour.
    private var startMs = computeStartMs()

    private fun computeStartMs(): Long = run {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, dayOffset)
        cal.add(java.util.Calendar.HOUR_OF_DAY, -hoursBack)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemUi()
        applyCompactLayout()

        adapter = TimelineAdapter(
            dpPerMin = dpPerMin,
            startMs = startMs,
            onScrollChanged = { scrollX -> syncScroll(scrollX) },
            onChannelClick = { row -> playChannel(row) },
            onProgramClick = { row, program -> onProgramTapped(row, program) },
            onProgramLongPress = { row, program -> showTimerDialog(row, program) },
            onProgramFocused = { row, program -> select(row, program) }
        )

        // Opening the guide shouldn't pop the keyboard up: on a touch screen the search box was the
        // first thing able to take focus. The screen takes it instead (on a remote, focus still
        // starts on the back button).
        if (!usesRemote) {
            binding.root.isFocusableInTouchMode = true
            binding.root.requestFocus()
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        binding.rvTimeline.layoutManager = LinearLayoutManager(this)
        binding.rvTimeline.adapter = adapter

        binding.btnTimelineBack.setOnClickListener { finish() }
        binding.btnTimelineRefresh.setOnClickListener {
            rebaseIfStale()
            Toast.makeText(this, "Refreshing guide…", Toast.LENGTH_SHORT).show()
            viewModel.loadGuide(forceRefresh = true)
        }
        binding.etTimelineSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { applyFilters() }
        })

        buildChips()
        buildTimeHeader()
        lifecycleScope.launch {
            accent = RackAccent.load(prefs)
            adapter.setAccent(accent)
            RackAccent.paintFillButton(binding.btnDetailPrimary, accent)
            categoryNames = try {
                db.categoryDao().getCategoriesByType("live").first().associate { it.categoryId to it.categoryName }
            } catch (_: Exception) { emptyMap() }
            buildChips()
            applyFilters()
        }
        lifecycleScope.launch {
            prefs.lastEpgRefreshTime.collect { t ->
                binding.tvGuideUpdated.text = when {
                    t <= 0L -> "Guide not updated yet"
                    android.text.format.DateUtils.isToday(t) -> "Guide updated " + SimpleDateFormat("h:mm a", Locale.US).format(Date(t))
                    else -> "Guide updated " + SimpleDateFormat("MMM d", Locale.US).format(Date(t))
                }
            }
        }
        observeGuide()
        showEpgDiffAlerts()
        startClock()
    }

    /** Feature B: shows any pending (unshown) EPG diff alerts for favorite channels — schedule
     * changes/pulls detected during the most recent EPG refresh(es), see
     * XtreamRepository.recordFavoriteEpgDiffs. Surfaced here (the Guide/EPG screen) rather than
     * as a push notification, per spec — a single combined toast per open, then every shown
     * alert is marked so it never surfaces again. */
    private fun showEpgDiffAlerts() {
        lifecycleScope.launch {
            val pending = try { db.epgDiffAlertDao().getUnshown() } catch (_: Exception) { emptyList() }
            if (pending.isEmpty()) return@launch
            // A pile this big is the bogus backlog from before v7.16 (every past show counted as
            // "pulled"), not real schedule changes — clear it without showing it.
            if (pending.size > 40) {
                db.epgDiffAlertDao().markShownUpTo(pending.maxOf { it.id })
                return@launch
            }
            val lines = pending.take(5).map { alert ->
                if (alert.newTitle != null)
                    "${alert.channelName}: \"${alert.oldTitle}\" changed to \"${alert.newTitle}\""
                else
                    "${alert.channelName}: \"${alert.oldTitle}\" was pulled from the schedule"
            }
            val extra = if (pending.size > 5) "\n+${pending.size - 5} more change(s)" else ""
            AlertDialog.Builder(this@EpgTimelineActivity)
                .setTitle("Guide Changes — Favorites")
                .setMessage(lines.joinToString("\n") + extra)
                .setPositiveButton("OK", null)
                .show()
            db.epgDiffAlertDao().markShownUpTo(pending.maxOf { it.id })
        }
    }

    // ── Day chips + genre pills (v6.91) ──

    private fun dp(v: Float) = dpToPx(v).toInt()

    /** A chip/pill: accent fill when it's the chosen one ([filled]), a light outline when it's
     * the chosen filter ([outlined]), a grey outline otherwise; D-pad focus adds the white ring. */
    private fun chip(label: String, filled: Boolean, outlined: Boolean, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = if (filled) 14f else 13f
            typeface = ResourcesCompat.getFont(this@EpgTimelineActivity,
                if (filled) R.font.barlow_condensed_bold else R.font.barlow_condensed_semibold)
            letterSpacing = 0.08f
            isAllCaps = true
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12f), 0, dp(12f), 0)
            isClickable = true
            isFocusable = true
            fun shape(fill: IntArray, stroke: Int?, strokeDp: Float) =
                android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, fill).apply {
                    cornerRadius = dpToPx(4f)
                    if (stroke != null) setStroke(dp(strokeDp), stroke)
                }
            val rest = when {
                filled -> shape(accent.stops, null, 0f)
                outlined -> shape(intArrayOf(getColor(R.color.rack_control_off), getColor(R.color.rack_control_off)), getColor(R.color.rack_text), 1f)
                else -> shape(intArrayOf(0, 0), getColor(R.color.rack_control_edge), 1f)
            }
            val focused = if (filled) shape(accent.stops, getColor(R.color.rack_focus_ring), 2f)
                else shape(intArrayOf(getColor(R.color.rack_focus_fill), getColor(R.color.rack_focus_fill)), getColor(R.color.rack_focus_ring), 2f)
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), rest)
            }
            setTextColor(when {
                filled -> accent.onAccent
                outlined -> getColor(R.color.rack_text)
                else -> getColor(R.color.rack_text_secondary)
            })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(if (filled || !outlined) 36f else 32f)).apply {
                marginEnd = dp(6f)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            setOnClickListener { onClick() }
        }

    // The Shield / a remote-only device: focus starts on the selected (live) show instead of the screen.
    private val usesRemote by lazy {
        resources.configuration.touchscreen == android.content.res.Configuration.TOUCHSCREEN_NOTOUCH
    }
    private var remoteFocusPlaced = false

    /** First data on a remote device: put focus on the selected block once it's laid out. */
    private fun placeRemoteFocus() {
        if (!usesRemote || remoteFocusPlaced) return
        val (row, program) = selected ?: return
        val key = programKey(row, program)
        // The selected channel may be below the visible rows: bring its row in first, then focus.
        val pos = adapter.positionOf(row)
        if (pos >= 0) binding.rvTimeline.scrollToPosition(pos)
        binding.rvTimeline.postDelayed({
            val v = binding.rvTimeline.findViewWithTag<View>(key)
            if (v != null && v.requestFocus()) remoteFocusPlaced = true
        }, 300)
    }

    // Short screens (a phone on its side, ~360dp tall): the detail panel goes on one line beside its
    // buttons and the genre pills share the day-chip row, or the grid would have under one row left.
    private val compact by lazy { resources.configuration.screenHeightDp < 500 }

    private fun applyCompactLayout() {
        if (!compact) return
        binding.genreBar.visibility = View.GONE
        binding.tvDetailMeta.visibility = View.GONE
        binding.guideDetailPanel.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(paddingLeft, dp(8f), paddingRight, dp(8f))
        }
        binding.detailText.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        binding.detailButtons.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(12f)
        }
        listOf(binding.btnDetailPrimary, binding.btnDetailSecondary).forEach { b ->
            b.layoutParams = (b.layoutParams as LinearLayout.LayoutParams).apply { width = dp(132f); weight = 0f }
        }
    }

    /** Day chips and genre pills; on a short screen both go in the day-chip row. */
    private fun buildChips() {
        buildDayChips()
        buildGenreChips()
    }

    /** After a chip row is rebuilt, puts remote focus back on the chip with that label, if one had it. */
    private fun refocusChip(row: ViewGroup, label: String?) {
        if (label == null) return
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i)
            if (v is TextView && v.text.toString().equals(label, ignoreCase = true)) { v.requestFocus(); return }
        }
    }

    private fun focusedChipLabel(): String? =
        (currentFocus as? TextView)?.takeIf { it.parent === binding.dayChipRow || it.parent === binding.genreChipRow }?.text?.toString()

    private fun buildDayChips() {
        val row = binding.dayChipRow
        val focused = focusedChipLabel()
        row.removeAllViews()
        for (offset in -1..4) {
            val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_MONTH, offset) }
            val day = SimpleDateFormat("EEE d", Locale.US).format(cal.time)
            val label = when (offset) {
                -1 -> "Yesterday"
                0 -> "Today · $day"
                1 -> "Tomorrow"
                else -> day
            }
            val on = offset == dayOffset
            row.addView(chip(label, filled = on, outlined = false) {
                if (offset == dayOffset) { if (offset == 0) { rebaseIfStale(); scrollToNow() } } else changeDay(offset - dayOffset)
            })
        }
        refocusChip(row, focused)
    }

    private fun buildGenreChips() {
        val focused = focusedChipLabel()
        binding.genreChipRow.removeAllViews()
        val row = if (compact) binding.dayChipRow else binding.genreChipRow
        if (compact) {
            for (i in row.childCount - 1 downTo 0) if (row.getChildAt(i).getTag(R.id.guide_genre_chip) == true) row.removeViewAt(i)
            row.addView(View(this).apply {
                setTag(R.id.guide_genre_chip, true)
                setBackgroundColor(getColor(R.color.rack_border))
                layoutParams = LinearLayout.LayoutParams(dp(1f), dp(24f)).apply {
                    marginEnd = dp(8f); marginStart = dp(2f); gravity = android.view.Gravity.CENTER_VERTICAL
                }
            })
        }
        val all = rows()
        val available = listOf(GENRE_FAVORITES, "Sports", "News", "Movies", "Kids").filter { g -> all.any { matchesGenre(it, g) } }
        if (genre != GENRE_ALL && genre !in available) genre = GENRE_ALL
        (listOf(GENRE_ALL) + available).forEach { g ->
            row.addView(chip(g, filled = false, outlined = g == genre) {
                genre = g
                buildGenreChips()
                applyFilters()
            }.apply { setTag(R.id.guide_genre_chip, true) })
        }
        refocusChip(row, focused)
    }

    // Genres are guessed from the category and channel names the providers give (e.g. "US| SPORTS",
    // "ESPN 2"); a channel can count as more than one, and anything unmatched shows under All only.
    private fun matchesGenre(row: GuideRow, g: String): Boolean {
        if (g == GENRE_ALL) return true
        if (g == GENRE_FAVORITES) return row.channel?.isFavorite == true || row.mergedChannel?.isFavorite == true
        val cat = row.channel?.categoryId?.let { categoryNames[it] } ?: row.mergedChannel?.categoryName ?: ""
        val text = "$cat ${row.name}".lowercase(Locale.US)
        val words = when (g) {
            "Sports" -> SPORTS_WORDS
            "News" -> NEWS_WORDS
            "Movies" -> MOVIE_WORDS
            "Kids" -> KIDS_WORDS
            else -> return false
        }
        return words.any { it in text }
    }

    // Rebuilding both the time header (its labels are computed from startMs) and the
    // adapter's own copy of startMs (each program block's position depends on it) keeps
    // everything in sync — changing just one and not the other would silently misalign the
    // program blocks with the header's time labels.
    private fun changeDay(delta: Int) {
        dayOffset += delta
        startMs = computeStartMs()
        buildChips()
        buildTimeHeader()
        adapter.updateStartMs(startMs)
        applyFilters()
        binding.rvTimeline.post {
            if (dayOffset == 0) {
                scrollToNow()
            } else {
                binding.timeHeaderScroll.scrollTo(0, 0)
                adapter.scrollAllTo(0)
            }
            updateNowIndicator()
        }
    }

    // Previously only matched the CHANNEL name — searching a show/program title (e.g. "NFL")
    // found nothing unless the channel itself happened to be named that, even though every
    // GuideRow already carries its full programs list. Now matches either, within the genre pill.
    private fun applyFilters() {
        val q = binding.etTimelineSearch.text?.toString()?.trim().orEmpty()
        val filtered = rows().filter { row ->
            matchesGenre(row, genre) && (q.isBlank() ||
                row.name.contains(q, ignoreCase = true) ||
                row.programs.any { it.title.contains(q, ignoreCase = true) })
        }
        adapter.submitList(filtered)
        // The panel follows what's shown: a filter that hides the selected channel moves the
        // selection to a visible one (or hides the panel), so Watch / Remind can't act on a hidden row.
        reconcileSelection(filtered)
    }

    private fun observeGuide() {
        binding.timelineProgress.visibility = View.VISIBLE
        viewModel.loadGuide()
        lifecycleScope.launch {
            viewModel.guideRows.collect { rows ->
                if (rows.isNotEmpty()) {
                    binding.timelineProgress.visibility = View.GONE
                    binding.tvTimelineEmpty?.visibility = View.GONE
                    buildGenreChips()
                    applyFilters()
                    if (dayOffset == 0) binding.rvTimeline.post { scrollToNow() }
                    placeRemoteFocus()
                }
            }
        }
        lifecycleScope.launch {
            viewModel.loading.collect { loading ->
                if (rows().isEmpty()) {
                    // Plain indeterminate spinner only while there's no real progress info yet
                    // (syncProgress starts emitting once the fetch loop actually begins) — once it
                    // does, timelineSyncProgressContainer takes over with real "N/Total" numbers.
                    binding.timelineProgress.visibility =
                        if (loading && viewModel.syncProgress.value == null) View.VISIBLE else View.GONE
                    // Cold-start-with-no-connectivity case (see loadGuide kdoc): nothing was ever
                    // cached, the fetch just finished (successfully or not) and there's still
                    // nothing to show — silently staying blank looked identical to a bug.
                    binding.tvTimelineEmpty?.visibility = if (!loading) View.VISIBLE else View.GONE
                }
            }
        }
        lifecycleScope.launch {
            viewModel.syncProgress.collect { progress ->
                if (progress == null) {
                    binding.timelineSyncProgressContainer.visibility = View.GONE
                } else {
                    val (text, percent) = progress
                    binding.timelineProgress.visibility = View.GONE
                    binding.timelineSyncProgressContainer.visibility = View.VISIBLE
                    binding.tvTimelineSyncStatus.text = text
                    binding.timelineSyncProgressBar.progress = percent
                }
            }
        }
    }

    // Merged/secondary-provider rows are included alongside primary now — recording/timeshift/
    // "Remind Me" simply aren't offered for them (see showTimerDialog/handleProgramClick),
    // matching the same primary-only gating the Guide list view already uses.
    private fun rows() = viewModel.guideRows.value

    private fun buildTimeHeader() {
        val container = binding.timeHeaderContent
        container.removeAllViews()
        val totalMinutes = (hoursBack + hoursAhead) * 60
        val slotMinutes = 30
        val slotCount = totalMinutes / slotMinutes
        val slotWidthDp = dpPerMin * slotMinutes
        for (i in 0 until slotCount) {
            val slotStartMs = startMs + i * slotMinutes * 60_000L
            val label = SimpleDateFormat("h:mm a", Locale.US).format(Date(slotStartMs))
            val tv = TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.rack_text_muted))
                textSize = 12f
                typeface = ResourcesCompat.getFont(this@EpgTimelineActivity, R.font.barlow_condensed_semibold)
                letterSpacing = 0.06f
                layoutParams = LinearLayout.LayoutParams(dpToPx(slotWidthDp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                setPadding(dpToPx(6f).toInt(), 0, 0, 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            container.addView(tv)
            // Divider
            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(1f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(getColor(R.color.rack_border))
            }
            container.addView(div)
        }
        // Sync header scroll with row scrolls
        binding.timeHeaderScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            adapter.setSharedScrollX(scrollX, sourceView = binding.timeHeaderScroll)
        }
    }

    private fun syncScroll(scrollX: Int) {
        binding.timeHeaderScroll.scrollTo(scrollX, 0)
        updateNowIndicator()
    }

    private fun scrollToNow() {
        val offsetMs = nowMs - startMs
        val offsetMin = offsetMs / 60_000f
        val offsetPx = dpToPx(offsetMin * dpPerMin).toInt() - dpToPx(120f).toInt()
        binding.timeHeaderScroll.smoothScrollTo(offsetPx.coerceAtLeast(0), 0)
        adapter.scrollAllTo(offsetPx.coerceAtLeast(0))
    }

    // ── The red now-line + NOW pill (v6.91) ──

    /** Positions the now-line over the grid and the NOW pill on the ruler at the current time,
     * following the shared horizontal scroll; hidden on other days or when now is scrolled off. */
    private fun updateNowIndicator() {
        val needle = binding.viewNowNeedle
        val pill = binding.tvNowPill
        val col = resources.getDimension(R.dimen.guide_channel_col) + dpToPx(1f)
        val x = col + dpToPx((nowMs - startMs) / 60_000f * dpPerMin) - adapter.scrollX
        val show = dayOffset == 0 && x >= col && x <= binding.guideGridArea.width
        needle.visibility = if (show) View.VISIBLE else View.GONE
        pill.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        needle.translationX = x - dpToPx(1f)
        pill.text = "NOW " + SimpleDateFormat("h:mm", Locale.US).format(Date(nowMs))
        pill.post {
            val maxX = (binding.timelineHeader.width - pill.width).toFloat()
            pill.translationX = (x - pill.width / 2f).coerceIn(col, maxX.coerceAtLeast(col))
        }
    }

    override fun onResume() {
        super.onResume()
        rebaseIfStale()
    }

    /** Today's window is fixed when drawn; after hours open (or in the background) "now" can run off
     * its end. Redraws the grid around the current time when it has. */
    private fun rebaseIfStale() {
        if (dayOffset != 0) return
        val windowEnd = startMs + (hoursBack + hoursAhead) * 60 * 60_000L
        if (nowMs in startMs until windowEnd - 60 * 60_000L) return
        startMs = computeStartMs()
        buildTimeHeader()
        adapter.updateStartMs(startMs)
        applyFilters()
        binding.rvTimeline.post { scrollToNow() }
    }

    /** Moves the now-line every 30 s and redraws the blocks each minute (LIVE NOW, minutes
     * left, progress) while the screen is open. */
    private fun startClock() {
        lifecycleScope.launch {
            var lastMinute = -1L
            // Only while the guide is on screen; coming back after time has passed catches up at once
            // (lastMinute is kept across stops, so the first tick refreshes the blocks).
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    rebaseIfStale()
                    updateNowIndicator()
                    val minute = nowMs / 60_000L
                    if (lastMinute != -1L && minute != lastMinute) {
                        // Rebinding rebuilds every block; put remote focus back on the same program.
                        val focusedKey = currentFocus?.tag as? String
                        adapter.refresh()
                        renderDetail()
                        if (focusedKey != null) binding.rvTimeline.post {
                            binding.rvTimeline.findViewWithTag<View>(focusedKey)?.requestFocus()
                        }
                    }
                    lastMinute = minute
                    delay(30_000)
                }
            }
        }
    }

    fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    // ── Detail panel (v6.91) ──

    /** A tap selects a block (the detail panel shows it with its actions); tapping the selected
     * block again does what a tap always did (watch / remind / replay). D-pad focus selects, and
     * OK on the focused block acts — focus already selected it. */
    private fun onProgramTapped(row: GuideRow, program: EpgEntity) {
        val current = selected
        if (current != null && programKey(current.first, current.second) == programKey(row, program)) {
            handleProgramClick(row, program)
        } else {
            select(row, program)
        }
    }

    private fun select(row: GuideRow, program: EpgEntity) {
        selected = row to program
        adapter.setSelectedKey(programKey(row, program))
        renderDetail()
    }

    /** After a guide refresh, points the selection at the same program in the new data (its old
     * copy could have a stale title or times for the panel's Remind / Record), else at whatever is on
     * that channel now, else at the first live show. */
    private fun reconcileSelection(rows: List<GuideRow>) {
        // Only shows inside the day being displayed count; on another day nothing is picked for you.
        val windowEnd = startMs + (hoursBack + hoursAhead) * 60 * 60_000L
        fun inWindow(p: EpgEntity) = epgMs(p.stopTimestamp) > startMs && epgMs(p.startTimestamp) < windowEnd
        fun fallback() {
            selected = null
            if (dayOffset == 0) selectFirstLive(rows)
            if (selected == null) { adapter.clearSelection(); renderDetail() }
        }
        val (oldRow, oldProgram) = selected ?: run { fallback(); return }
        val row = rows.firstOrNull { it.serverIndex == oldRow.serverIndex && it.streamId == oldRow.streamId }
        val program = row?.programs?.firstOrNull { it.startTimestamp == oldProgram.startTimestamp && inWindow(it) }
            ?: if (dayOffset == 0) row?.programs?.firstOrNull { epgMs(it.startTimestamp) <= nowMs && nowMs < epgMs(it.stopTimestamp) } else null
        if (row != null && program != null) select(row, program) else fallback()
    }

    private fun selectFirstLive(rows: List<GuideRow>) {
        val now = nowMs
        for (row in rows) {
            val live = row.programs.firstOrNull { epgMs(it.startTimestamp) <= now && now < epgMs(it.stopTimestamp) }
            if (live != null) { select(row, live); return }
        }
    }

    private fun renderDetail() {
        val (row, p) = selected ?: run { binding.guideDetailPanel.visibility = View.GONE; return }
        binding.guideDetailPanel.visibility = View.VISIBLE
        val now = nowMs
        val s = epgMs(p.startTimestamp)
        val e = epgMs(p.stopTimestamp)
        val time = SimpleDateFormat("h:mm a", Locale.US)
        val num = row.channel?.num?.takeIf { it > 0 }
        val channel = listOfNotNull(num?.toString(), row.name).joinToString(" ")
        val ch = row.channel
        fun minutes(ms: Long): String {
            val m = ((ms + 59_999) / 60_000).toInt()
            return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
        }
        binding.tvDetailTitle.text = p.title
        val desc = p.description.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        binding.tvDetailMeta.text = listOfNotNull("${time.format(Date(s))} – ${time.format(Date(e))}", desc).joinToString(" · ")

        val primary = binding.btnDetailPrimary
        val secondary = binding.btnDetailSecondary
        secondary.visibility = View.VISIBLE
        when {
            s <= now && now < e -> {
                binding.tvDetailEyebrow.text = "On now · $channel · ${minutes(e - now)} left"
                primary.text = "Watch"
                primary.setOnClickListener { playChannel(row) }
                val next = row.programs.firstOrNull { epgMs(it.startTimestamp) >= e }
                if (ch != null && next != null) {
                    val nextStart = epgMs(next.startTimestamp)
                    val set = ChannelTimerScheduler.isScheduled(this, ch.streamId, nextStart)
                    secondary.text = if (set) "Reminder set" else "Remind next"
                    secondary.setOnClickListener {
                        if (!set) {
                            ChannelTimerScheduler.schedule(this, ch.streamId, ch.name, next.title, nextStart)
                            Toast.makeText(this, "Reminder set for ${next.title}", Toast.LENGTH_SHORT).show()
                            adapter.refresh()
                            renderDetail()
                        }
                    }
                } else secondary.visibility = View.GONE
            }
            s > now -> {
                binding.tvDetailEyebrow.text = "$channel · " +
                    if (s - now < 24 * 60 * 60_000L) "starts in ${minutes(s - now)}"
                    else SimpleDateFormat("EEE MMM d", Locale.US).format(Date(s))
                if (ch != null) {
                    val set = ChannelTimerScheduler.isScheduled(this, ch.streamId, s)
                    primary.text = if (set) "Cancel reminder" else "Remind me"
                    primary.setOnClickListener {
                        if (set) {
                            ChannelTimerScheduler.cancel(this, ch.streamId)
                            Toast.makeText(this, "Reminder cancelled", Toast.LENGTH_SHORT).show()
                        } else {
                            ChannelTimerScheduler.schedule(this, ch.streamId, ch.name, p.title, s)
                            Toast.makeText(this, "Reminder set for ${time.format(Date(s))}", Toast.LENGTH_SHORT).show()
                        }
                        adapter.refresh()
                        renderDetail()
                    }
                    secondary.text = "Record"
                    secondary.setOnClickListener {
                        startActivity(
                            Intent(this, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java)
                                .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_STREAM_ID, ch.streamId)
                                .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_START_MS, s)
                                .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_DURATION_MS, (e - s).coerceAtLeast(60_000L))
                        )
                    }
                } else {
                    // Reminders / recordings are primary-provider only (see showTimerDialog).
                    primary.text = "Watch channel"
                    primary.setOnClickListener { playChannel(row) }
                    secondary.visibility = View.GONE
                }
            }
            else -> {
                binding.tvDetailEyebrow.text = "$channel · ended"
                if (row.canReplay(p)) {
                    primary.text = "Replay"
                    primary.setOnClickListener { handleProgramClick(row, p) }
                    secondary.text = "Watch live"
                    secondary.setOnClickListener { playChannel(row) }
                } else {
                    primary.text = "Watch live"
                    primary.setOnClickListener { playChannel(row) }
                    secondary.visibility = View.GONE
                }
            }
        }
    }

    // Record/Remind Me are primary-provider-only concepts (RecordingSchedulerActivity/
    // ChannelTimerScheduler have no serverIndex support) — a merged row only ever gets the
    // plain "OK" dialog with no actionable buttons, same spirit as the Guide list view gating
    // onReplayClick off row.supportsReplay for merged rows.
    private fun showTimerDialog(row: GuideRow, program: EpgEntity) {
        val startSec = if (program.startTimestamp < 100_000_000_000L) program.startTimestamp else program.startTimestamp / 1000L
        val startMs = startSec * 1000L
        if (startMs <= nowMs) return
        val stopMs = if (program.stopTimestamp < 100_000_000_000L) program.stopTimestamp * 1000L else program.stopTimestamp
        val durationMs = (stopMs - startMs).coerceAtLeast(60_000L)
        val timeStr = SimpleDateFormat("h:mm a", Locale.US).format(Date(startMs))
        val ch = row.channel
        val builder = AlertDialog.Builder(this)
            .setTitle("\"${program.title}\"")
            .setMessage("${row.name} · $timeStr")
            .setNegativeButton("Cancel", null)
        if (ch != null) {
            builder.setPositiveButton("Record") { _, _ ->
                startActivity(
                    Intent(this, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_STREAM_ID, ch.streamId)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_START_MS, startMs)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_DURATION_MS, durationMs)
                )
            }
            builder.setNeutralButton("Remind Me") { _, _ ->
                ChannelTimerScheduler.schedule(this, ch.streamId, ch.name, program.title, startMs)
                android.widget.Toast.makeText(this, "Reminder set for $timeStr", android.widget.Toast.LENGTH_SHORT).show()
                adapter.refresh()
                renderDetail()
            }
        }
        builder.show()
    }

    private fun playChannel(row: GuideRow) {
        val mergedCh = row.mergedChannel
        if (mergedCh != null) {
            setResult(RESULT_OK, Intent()
                .putExtra("stream_id", -1)
                .putExtra("server_index", mergedCh.serverIndex)
                .putExtra("merged_stream_id", mergedCh.streamId))
        } else {
            setResult(RESULT_OK, Intent().putExtra("stream_id", row.channel!!.streamId))
        }
        finish()
    }

    private fun handleProgramClick(row: GuideRow, program: EpgEntity) {
        val nowMs = System.currentTimeMillis()
        val pStartMs = if (program.startTimestamp < 100_000_000_000L) program.startTimestamp * 1000L else program.startTimestamp
        val pStopMs = if (program.stopTimestamp < 100_000_000_000L) program.stopTimestamp * 1000L else program.stopTimestamp
        val ch = row.channel

        when {
            pStartMs <= nowMs && pStopMs > nowMs -> {
                // Currently airing — return to home and play in mini player
                playChannel(row)
            }
            pStartMs > nowMs -> {
                // Upcoming — offer to set a reminder (stay in grid)
                showTimerDialog(row, program)
            }
            ch != null && row.canReplay(program, nowMs) -> {
                // Past with replay archive — return to home and play timeshift in mini player.
                // Timeshift is primary-only (merged channels have no tvArchive), so this branch
                // never applies to a merged row — ch is smart-cast non-null here.
                lifecycleScope.launch {
                    val startSec = if (program.startTimestamp < 100_000_000_000L) program.startTimestamp
                    else program.startTimestamp / 1000L
                    val durationMin = ((pStopMs - pStartMs) / 60_000L).toInt().coerceAtLeast(1)
                    val url = viewModel.getTimeshiftUrl(ch.streamId, startSec, durationMin)
                    setResult(RESULT_OK, Intent()
                        .putExtra("stream_id", ch.streamId)
                        .putExtra("timeshift_url", url)
                        .putExtra("timeshift_title", "${ch.name} — ${program.title}")
                        .putExtra("catchup_start_sec", startSec)
                        .putExtra("catchup_duration_min", durationMin)
                        .putExtra("catchup_channel_name", ch.name))
                    finish()
                }
            }
            else -> {
                // Past and not replayable. This used to tune the channel live without a word, which
                // looked like the replay was broken — say why and stay in the guide (the detail
                // panel still offers Watch live).
                val why = when {
                    ch == null -> "Replays aren't available for other-provider channels"
                    ch.tvArchive != 1 -> "${ch.name} doesn't offer replays"
                    else -> "That's older than the ${ch.tvArchiveDuration.takeIf { it > 0 } ?: 3} days ${ch.name} keeps"
                }
                Toast.makeText(this, why, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private companion object {
        const val GENRE_ALL = "All"
        const val GENRE_FAVORITES = "★ Favorites"
        val SPORTS_WORDS = listOf("sport", "espn", "nfl", "nba", "mlb", "nhl", "ncaa", "ufc", "wwe", "ppv", "golf", "tennis", "bein", "dazn", "racing", "soccer", "football")
        val NEWS_WORDS = listOf("news", "cnn", "msnbc", "cnbc", "bloomberg", "weather", "c-span")
        val MOVIE_WORDS = listOf("movie", "cinema", "film", "hbo", "showtime", "starz", "cinemax", "mgm", "epix", "tcm", "amc", "flix")
        val KIDS_WORDS = listOf("kid", "family", "cartoon", "disney", "nick", "junior", "boomerang", "baby")
    }
}

class TimelineAdapter(
    private val dpPerMin: Float,
    startMs: Long,
    private val onScrollChanged: (Int) -> Unit,
    private val onChannelClick: (GuideRow) -> Unit,
    private val onProgramClick: (GuideRow, EpgEntity) -> Unit,
    private val onProgramLongPress: (GuideRow, EpgEntity) -> Unit,
    private val onProgramFocused: (GuideRow, EpgEntity) -> Unit
) : RecyclerView.Adapter<TimelineAdapter.ViewHolder>() {

    // Mutable so day-paging can shift the whole grid's time window without recreating the
    // adapter (and losing its RecyclerView scroll-sync state) — each ViewHolder reads the
    // current value at bind time via the enclosing instance, not a constructor-captured copy.
    private var startMs: Long = startMs

    private var rows: List<GuideRow> = emptyList()
    private var sharedScrollX = 0
    private val scrollViews = mutableListOf<HorizontalScrollView>()
    private var isSyncing = false

    /** The rows' shared horizontal scroll, for EpgTimelineActivity's now-line. */
    val scrollX: Int get() = sharedScrollX

    fun submitList(list: List<GuideRow>) {
        rows = list
        notifyDataSetChanged()
    }

    fun updateStartMs(newStartMs: Long) {
        startMs = newStartMs
        notifyDataSetChanged()
    }

    /** Rebinds every row so time-dependent bits (LIVE NOW, minutes left, progress, reminders)
     * catch up. */
    fun refresh() = notifyDataSetChanged()

    fun setSharedScrollX(x: Int, sourceView: View? = null) {
        if (isSyncing) return
        isSyncing = true
        sharedScrollX = x
        scrollViews.forEach { sv -> if (sv !== sourceView) sv.scrollTo(x, 0) }
        onScrollChanged(x)
        isSyncing = false
    }

    fun scrollAllTo(x: Int) {
        sharedScrollX = x
        scrollViews.forEach { it.smoothScrollTo(x, 0) }
        onScrollChanged(x)
    }

    // v6.85: accent for the show that's on now. Null until EpgTimelineActivity loads it.
    private var accent: com.iptvapp.util.RackAccent.Accent? = null

    fun setAccent(value: com.iptvapp.util.RackAccent.Accent) {
        accent = value
        notifyDataSetChanged()
    }

    // v6.91: the block the detail panel describes, drawn with the same white outline as focus.
    private var selectedKey: String? = null

    // Updated in place, not by rebinding: rebinding would destroy the block that holds D-pad focus.
    fun clearSelection() {
        selectedKey = null
        scrollViews.forEach { sv ->
            val row = sv.getChildAt(0) as? ViewGroup ?: return@forEach
            for (i in 0 until row.childCount) row.getChildAt(i).isSelected = false
        }
    }

    fun setSelectedKey(key: String) {
        if (key == selectedKey) return
        selectedKey = key
        scrollViews.forEach { sv ->
            val row = sv.getChildAt(0) as? ViewGroup ?: return@forEach
            for (i in 0 until row.childCount) row.getChildAt(i).let { it.isSelected = it.tag == key }
        }
    }

    fun positionOf(row: GuideRow) = rows.indexOfFirst { it.serverIndex == row.serverIndex && it.streamId == row.streamId }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_epg_timeline_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun onViewRecycled(holder: ViewHolder) {
        scrollViews.remove(holder.scrollView)
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivLogo: ImageView = view.findViewById(R.id.ivTimelineChannelLogo)
        private val tvName: TextView = view.findViewById(R.id.tvTimelineChannelName)
        private val tvNum: TextView = view.findViewById(R.id.tvTimelineChannelNum)
        private val tvQuality: TextView = view.findViewById(R.id.tvTimelineQuality)
        private val tvProviderLabel: TextView? = view.findViewById(R.id.tvTimelineProviderLabel)
        val scrollView: HorizontalScrollView = view.findViewById(R.id.programRowScroll)
        private val container: LinearLayout = view.findViewById(R.id.programRowContainer)

        private val providerStripe: View? = view.findViewById(R.id.viewTimelineProviderStripe)

        fun bind(row: GuideRow) {
            tvName.text = row.name
            tvName.setOnClickListener { onChannelClick(row) }
            // Channel number (primary provider only — a merged row's num is just its list
            // position) and the quality tag the name carries (HD / FHD / 4K), as in the channel list.
            val num = row.channel?.num?.takeIf { it > 0 }
            tvNum.visibility = if (num != null) View.VISIBLE else View.GONE
            tvNum.text = num?.toString() ?: ""
            val quality = com.iptvapp.util.ChannelQualityTag.labelFor(row.name)
            tvQuality.visibility = if (quality != null) View.VISIBLE else View.GONE
            tvQuality.text = quality ?: ""
            tvProviderLabel?.text = row.providerLabel
            providerStripe?.setBackgroundColor(providerColorFor(row.serverIndex) ?: 0x00000000)
            if (!row.streamIcon.isNullOrBlank()) {
                ivLogo.visibility = View.VISIBLE
                Glide.with(itemView.context).load(row.streamIcon)
                    .placeholder(android.R.drawable.ic_media_play)
                    .error(android.R.drawable.ic_media_play)
                    .into(ivLogo)
                ivLogo.setOnClickListener { onChannelClick(row) }
            } else {
                ivLogo.visibility = View.GONE
            }

            container.removeAllViews()
            buildProgramBlocks(row, container, itemView.context)

            if (!scrollViews.contains(scrollView)) scrollViews.add(scrollView)
            scrollView.scrollTo(sharedScrollX, 0)
            scrollView.setOnScrollChangeListener { _, x, _, _, _ ->
                setSharedScrollX(x, scrollView)
            }
        }

        private fun buildProgramBlocks(row: GuideRow, container: LinearLayout, ctx: Context) {
            val nowMs = System.currentTimeMillis()
            val endMs = startMs + (12 * 60 * 60_000L)
            val dpPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpPerMin, ctx.resources.displayMetrics)

            // Each block is placed at its real start time (v6.91): a gap fills any hole before it,
            // and a show that began before the window, or overlaps the previous one, is clipped to
            // what's visible. Blocks used to be laid end to end at full length, so one early or
            // missing slot pushed everything after it out of line with the clock (the now-line
            // made that obvious).
            var cursorMs = startMs
            row.programs.sortedBy { toMs(it.startTimestamp) }.forEach { program ->
                val pStartMs = toMs(program.startTimestamp)
                val pStopMs = toMs(program.stopTimestamp)
                val visStart = maxOf(pStartMs, cursorMs)
                val visEnd = minOf(pStopMs, endMs)
                if (visEnd - visStart < 60_000L) return@forEach
                if (visStart > cursorMs) container.addView(makeGap(((visStart - cursorMs) / 60_000f * dpPx).toInt(), ctx))
                val widthPx = ((visEnd - visStart) / 60_000f * dpPx).toInt()
                container.addView(programBlock(row, program, pStartMs, pStopMs, nowMs, widthPx, ctx))
                cursorMs = visEnd
            }
        }

        /** One program (v6.91): a label line (● LIVE NOW + minutes left / time + bell for a
         * reminder / REPLAY), the title, and a progress bar for the show on now. Narrow blocks
         * drop the label line, then the progress bar. */
        private fun programBlock(row: GuideRow, program: EpgEntity, pStartMs: Long, pStopMs: Long, nowMs: Long, widthPx: Int, ctx: Context): View {
            val isNow = pStartMs <= nowMs && pStopMs > nowMs
            val isPast = pStopMs <= nowMs
            val isReplay = isPast && row.canReplay(program, nowMs)
            val reminder = !isNow && !isPast && row.channel != null &&
                ChannelTimerScheduler.isScheduled(ctx, row.streamId, pStartMs)
            val a = accent ?: com.iptvapp.util.RackAccent.Accent(ctx.getColor(R.color.oled_cyan_primary), null)
            val condensed = ResourcesCompat.getFont(ctx, R.font.barlow_condensed_semibold)
            val time = SimpleDateFormat("h:mm", Locale.US)

            val block = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dpToPx(7f, ctx).toInt(), dpToPx(4f, ctx).toInt(), dpToPx(7f, ctx).toInt(), dpToPx(4f, ctx).toInt())
                // Studio Rack blocks (v6.85): square-cornered modules with a hairline edge;
                // the show on now gets a wash and edge in the user's accent (a gradient wash
                // for a gradient accent); catch-up replay keeps its green tint.
                background = programBlockBackground(ctx, isNow, isReplay)
                isClickable = true
                isFocusable = true
                tag = programKey(row, program)
                isSelected = programKey(row, program) == selectedKey
                // The 1 + 2 dp side margins come out of the width, so blocks stay on the clock.
                val sides = dpToPx(1f, ctx).toInt() + dpToPx(2f, ctx).toInt()
                layoutParams = LinearLayout.LayoutParams((widthPx - sides).coerceAtLeast(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    setMargins(dpToPx(1f, ctx).toInt(), dpToPx(4f, ctx).toInt(), dpToPx(2f, ctx).toInt(), dpToPx(4f, ctx).toInt())
                }
                setOnClickListener { onProgramClick(row, program) }
                setOnLongClickListener {
                    onProgramLongPress(row, program)
                    true
                }
                setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onProgramFocused(row, program) }
            }

            if (widthPx >= dpToPx(64f, ctx)) {
                val labelRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val label = TextView(ctx).apply {
                    textSize = 11f
                    typeface = condensed
                    letterSpacing = 0.08f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                labelRow.addView(label)
                when {
                    isNow -> {
                        label.text = "● LIVE NOW"
                        com.iptvapp.util.AccentText.apply(label, a.start, a.end)
                        if (widthPx >= dpToPx(150f, ctx)) labelRow.addView(TextView(ctx).apply {
                            text = "${((pStopMs - nowMs + 59_999) / 60_000)} MIN LEFT"
                            textSize = 11f
                            typeface = condensed
                            letterSpacing = 0.06f
                            maxLines = 1
                            setTextColor(ctx.getColor(R.color.rack_text_secondary))
                        })
                    }
                    isReplay -> {
                        label.text = "REPLAY"
                        label.setTextColor(0xFF6FCF97.toInt())
                        labelRow.addView(icon(ctx, R.drawable.ic_guide_replay))
                    }
                    else -> {
                        label.text = "${time.format(Date(pStartMs))}–${time.format(Date(pStopMs))}"
                        label.setTextColor(ctx.getColor(R.color.rack_text_muted))
                        if (reminder) labelRow.addView(icon(ctx, R.drawable.ic_guide_bell))
                    }
                }
                block.addView(labelRow)
            }

            block.addView(TextView(ctx).apply {
                text = program.title
                textSize = 13f
                typeface = ResourcesCompat.getFont(ctx, R.font.barlow_semibold)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ctx.getColor(if (isPast && !isReplay) R.color.rack_text_secondary else R.color.rack_text))
                setPadding(0, dpToPx(2f, ctx).toInt(), 0, dpToPx(2f, ctx).toInt())
            })

            if (isNow && widthPx >= dpToPx(40f, ctx)) {
                block.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = (((nowMs - pStartMs) * 100) / (pStopMs - pStartMs).coerceAtLeast(1)).toInt().coerceIn(0, 100)
                    com.iptvapp.util.RackAccent.paintProgress(this, a)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(3f, ctx).toInt())
                })
            }
            return block
        }

        private fun icon(ctx: Context, res: Int) = ImageView(ctx).apply {
            setImageResource(res)
            layoutParams = LinearLayout.LayoutParams(dpToPx(12f, ctx).toInt(), dpToPx(12f, ctx).toInt()).apply {
                marginStart = dpToPx(4f, ctx).toInt()
            }
        }

        private fun makeGap(widthPx: Int, ctx: Context): View = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        private fun programBlockBackground(ctx: Context, isNow: Boolean, isReplay: Boolean): android.graphics.drawable.Drawable {
            val density = ctx.resources.displayMetrics.density
            fun shape(fill: IntArray, edge: Int) = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, fill
            ).apply {
                cornerRadius = 3 * density
                setStroke((1 * density + 0.5f).toInt(), edge)
            }
            fun wash(c: Int, alpha: Int) = android.graphics.Color.argb(alpha, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
            val surface = ctx.getColor(R.color.rack_surface)
            val rest = when {
                isNow -> {
                    val a = accent ?: com.iptvapp.util.RackAccent.Accent(ctx.getColor(R.color.oled_cyan_primary), null)
                    shape(a.stops.map { wash(it, 70) }.toIntArray(), a.start)
                }
                isReplay -> shape(intArrayOf(0xFF0E1A10.toInt(), 0xFF0E1A10.toInt()), 0xFF1F3A24.toInt())
                else -> shape(intArrayOf(surface, surface), ctx.getColor(R.color.rack_border))
            }
            // Focused (D-pad) and selected (the detail panel's block) share the light outline.
            fun ring() = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 3 * density
                setColor(ctx.getColor(R.color.rack_focus_fill))
                setStroke((2 * density + 0.5f).toInt(), ctx.getColor(R.color.rack_focus_ring))
            }
            return android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ring())
                addState(intArrayOf(android.R.attr.state_selected), ring())
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 3 * density
                    setColor(ctx.getColor(R.color.rack_pressed))
                })
                addState(intArrayOf(), rest)
            }
        }

        private fun toMs(ts: Long) = if (ts < 100_000_000_000L) ts * 1000L else ts
        private fun dpToPx(dp: Float, ctx: Context) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.resources.displayMetrics)
    }
}
