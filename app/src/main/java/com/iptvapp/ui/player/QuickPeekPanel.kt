package com.iptvapp.ui.player

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityPlayerBinding
import com.iptvapp.util.CategoryFilters
import com.iptvapp.util.QuickPeek
import com.iptvapp.util.RackAccent
import com.iptvapp.util.isLargeScreenDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Peek, the mini guide over live TV (layout: activity_player's guideContainer; logic:
 * util/QuickPeek). It browses the provider that is playing, from what is already stored on the
 * device: channel lists from Room, and now / next from the stored guide for just the rows around
 * the screen. Opening, browsing and the 30-second clock never touch the network or the player;
 * only a pick goes to the player, through [Host.tune].
 */
class QuickPeekPanel(
    private val activity: AppCompatActivity,
    private val b: ActivityPlayerBinding,
    private val repository: XtreamRepository,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager,
    private val host: Host,
) {
    interface Host {
        /** The channel making the picture and sound now, or null when there is none to browse from. */
        fun playingKey(): QuickPeek.Key?
        /** Plays [primary] or [merged] through the player's normal channel change. [zapList] is the list it was picked from. */
        fun tune(primary: ChannelEntity?, merged: MergedChannelEntity?, zapList: List<Any>)
        fun openFullGuide()
        /** The panel is about to show: the player's own controls step aside. */
        fun onOpening()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val selection = QuickPeek.Selection()
    private val adapter = QuickPeekAdapter(onPick = ::pick, onHighlight = ::onHighlight)
    private val layoutManager = LinearLayoutManager(activity)

    var isOpen = false
        private set

    private var sources: List<QuickPeek.Source> = emptyList()
    private var sourceIndex = 0
    private var loadedGeneration = -1
    private var highlightIndex = -1
    // Parallel to the adapter's rows: what Host.tune needs for a pick.
    private var entities: List<Any> = emptyList()
    private val guide = HashMap<QuickPeek.Key, QuickPeek.NowNext>()

    private var listJob: Job? = null
    private var guideJob: Job? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!isOpen) return
            refreshVisibleGuide()
            handler.postDelayed(this, TICK_MS)
        }
    }
    private val guideAfterScroll = Runnable { loadVisibleGuide() }
    private val idleClose = Runnable { if (isOpen) close() }

    init {
        b.rvQuickPeek.layoutManager = layoutManager
        b.rvQuickPeek.adapter = adapter
        b.rvQuickPeek.itemAnimator = null
        b.rvQuickPeek.setHasFixedSize(true)
        b.rvQuickPeek.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0) return
                handler.removeCallbacks(guideAfterScroll)
                handler.postDelayed(guideAfterScroll, SCROLL_SETTLE_MS)
                keepAlive()
            }
        })
        b.btnPeekPrevList.setOnClickListener { keepAlive(); changeSource(-1) }
        b.btnPeekNextList.setOnClickListener { keepAlive(); changeSource(1) }
        b.tvPeekList.setOnClickListener { keepAlive(); pickSource() }
        b.btnPeekFullGuide.setOnClickListener { close(); host.openFullGuide() }
        b.btnCloseGuide.setOnClickListener { close() }
    }

    fun setAccent(accent: RackAccent.Accent) = adapter.setAccent(accent)

    fun toggle() = if (isOpen) close() else open()

    fun open() {
        if (isOpen) return
        val playing = host.playingKey() ?: return
        syncPlaying()
        selection.clearPending()
        selection.highlight(null)
        host.onOpening()
        isOpen = true
        b.tvPeekHint.visibility = if (activity.isLargeScreenDevice()) View.VISIBLE else View.GONE
        b.guideContainer.visibility = View.VISIBLE
        b.guideContainer.alpha = 0f
        b.guideContainer.translationX = 24 * activity.resources.displayMetrics.density
        b.guideContainer.animate().alpha(1f).translationX(0f).setDuration(ANIM_MS).start()
        handler.postDelayed(tick, TICK_MS)
        keepAlive()
        val generation = selection.generation
        listJob?.cancel()
        listJob = activity.lifecycleScope.launch {
            if (loadedGeneration != generation || sources.isEmpty()) {
                guide.clear()
                showLoading()
                val category = try { playingCategory(playing) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                val loaded = try { withContext(Dispatchers.IO) { loadSources(playing.serverIndex, category) } } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emptyList()
                }
                if (!isOpen || selection.generation != generation) return@launch
                sources = loaded
                sourceIndex = QuickPeek.startSource(loaded, category)
                loadedGeneration = generation
            }
            showSource(sourceIndex)
        }
    }

    /** Hides the panel. Nothing is tuned; the picture never stopped. */
    fun close(animate: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        listJob?.cancel()
        guideJob?.cancel()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(guideAfterScroll)
        handler.removeCallbacks(idleClose)
        val panel = b.guideContainer
        panel.animate().cancel()
        if (animate) {
            panel.animate().alpha(0f).setDuration(ANIM_MS).withEndAction {
                if (!isOpen) panel.visibility = View.GONE
            }.start()
        } else {
            panel.visibility = View.GONE
        }
        // Focus on a row about to vanish would otherwise stay with it for one more key press.
        if (b.guideContainer.hasFocus()) b.guideContainer.findFocus()?.clearFocus()
    }

    /** The player changed channel (a pick, CH +/-, LAST, a failover): moves the PLAYING mark. */
    fun syncPlaying() {
        val key = host.playingKey()
        val before = selection.generation
        selection.playingNow(key)
        if (selection.generation != before) {
            // Another provider: its lists are different, and so are its stream ids.
            sources = emptyList()
            guide.clear()
            if (isOpen) {
                close(animate = false)
                return
            }
        }
        adapter.setPlaying(key)
    }

    /** Keys while the panel is open. False leaves the key to the player (CH +/-, volume, media). */
    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isOpen) return false
        keepAlive()
        val repeat = event?.repeatCount ?: 0
        val inHeader = headerHasFocus()
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { close(); true }
            KeyEvent.KEYCODE_GUIDE -> { close(); host.openFullGuide(); true }
            // MENU opened it, so MENU closes it.
            KeyEvent.KEYCODE_MENU -> { close(); true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                when {
                    inHeader -> Unit
                    highlightIndex <= 0 -> b.tvPeekList.requestFocus()
                    else -> move(if (repeat >= FAST_REPEAT) -FAST_STEP else -1)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (inHeader) focusRow(highlightIndex.coerceAtLeast(0), center = false)
                else move(if (repeat >= FAST_REPEAT) FAST_STEP else 1)
                true
            }
            KeyEvent.KEYCODE_PAGE_UP -> { move(-pageSize()); true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { move(pageSize()); true }
            // In the header Left / Right walk its buttons; on the list they change the list.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (inHeader) false else { if (repeat == 0) changeSource(-1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (inHeader) false else { if (repeat == 0) changeSource(1); true }
            // A focused row takes OK itself (its click); this is OK with nothing focused.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (inHeader) return false
                adapter.items.getOrNull(highlightIndex)?.let { pick(it) }
                true
            }
            else -> false
        }
    }

    /** A touch outside the panel closes it, as Back does. */
    fun touchOutside(rawX: Float, rawY: Float): Boolean {
        if (!isOpen) return false
        val r = android.graphics.Rect()
        if (!b.guideContainer.getGlobalVisibleRect(r)) return false
        if (r.contains(rawX.toInt(), rawY.toInt())) {
            keepAlive()
            return false
        }
        close()
        return true
    }

    /** onStop, Lock, PiP: closes without waiting on an animation. */
    fun stop() = close(animate = false)

    // ── Lists ──────────────────────────────────────────────────────────

    private suspend fun playingCategory(key: QuickPeek.Key): String? = withContext(Dispatchers.IO) {
        if (key.serverIndex == -1) repository.getChannelById(key.streamId)?.categoryId
        else repository.getMergedChannelByIndexAndId(key.serverIndex, key.streamId)?.let { it.categoryId ?: NO_CATEGORY }
    }

    private suspend fun loadSources(server: Int, current: String?): List<QuickPeek.Source> {
        val usaOnly = prefs.usaOnlyChannels.first()
        val categories = if (server == -1) {
            repository.getLiveCategories().first()
                .filter { !usaOnly || CategoryFilters.isUsCategory(it.categoryName) }
                .map { it.categoryId to it.categoryName }
        } else {
            repository.getMergedCategorySummaries(server).first()
                .filter { !usaOnly || CategoryFilters.isUsCategory(it.categoryName) }
                .map { (it.categoryId ?: NO_CATEGORY) to (it.categoryName ?: "Other") }
        }
        return QuickPeek.sources(categories, current)
    }

    /** One list's channels, in the order the rest of MKTV shows them. */
    private suspend fun loadRows(server: Int, source: QuickPeek.Source): Pair<List<QuickPeek.Row>, List<Any>> =
        withContext(Dispatchers.IO) {
            if (server == -1) {
                val list = when (source.kind) {
                    QuickPeek.Kind.FAVORITES -> repository.getFavoriteChannels().first()
                    QuickPeek.Kind.CATEGORY -> repository.getChannelsByCategory(source.id ?: "").first()
                    QuickPeek.Kind.ALL -> {
                        val all = repository.getAllChannels().first()
                        // USA Only: just the channels in the lists the category picker offers, as Home shows them.
                        if (!prefs.usaOnlyChannels.first()) all else {
                            val allowed = sources.filter { it.kind == QuickPeek.Kind.CATEGORY }.mapNotNull { it.id }.toSet()
                            all.filter { it.categoryId in allowed }
                        }
                    }
                }
                list.map { primaryRow(it) } to list
            } else {
                val list = when (source.kind) {
                    QuickPeek.Kind.FAVORITES -> repository.getMergedAllFavorites().first().filter { it.serverIndex == server }
                    QuickPeek.Kind.CATEGORY -> repository.getMergedChannelsByCategory(
                        server, source.id.takeIf { it != NO_CATEGORY }
                    ).first()
                    QuickPeek.Kind.ALL -> {
                        // The lists the category picker offers, so USA Only hides the same channels here.
                        val allowed = sources.filter { it.kind == QuickPeek.Kind.CATEGORY }.map { it.id }.toSet()
                        db.mergedChannelDao().getAllForServer(server)
                            .filter { !it.isHidden && (it.categoryId ?: NO_CATEGORY) in allowed }
                            .sortedBy { it.num }
                    }
                }
                list.map { mergedRow(it) } to list
            }
        }

    private fun primaryRow(c: ChannelEntity) = QuickPeek.Row(
        QuickPeek.Key(-1, c.streamId), c.customNum ?: c.num, c.name, c.streamIcon, c.isFavorite, c.categoryId
    )

    private fun mergedRow(c: MergedChannelEntity) = QuickPeek.Row(
        QuickPeek.Key(c.serverIndex, c.streamId), c.num, c.name, c.streamIcon, c.isFavorite, c.categoryId
    )

    private fun showLoading() {
        b.tvPeekList.text = activity.getString(R.string.quick_peek_loading)
        b.tvPeekEmpty.visibility = View.GONE
        b.peekDetail.visibility = View.GONE
    }

    private fun showSource(index: Int) {
        val source = sources.getOrNull(index) ?: return
        val playing = host.playingKey()
        val server = playing?.serverIndex ?: return
        val generation = selection.generation
        val keep = selection.highlighted
        listJob?.cancel()
        listJob = activity.lifecycleScope.launch {
            b.tvPeekList.text = source.label
            val (rows, list) = try { loadRows(server, source) } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList<QuickPeek.Row>() to emptyList()
            }
            if (!isOpen || selection.generation != generation) return@launch
            entities = list
            adapter.submit(rows, host.playingKey())
            b.tvPeekList.text = activity.getString(R.string.quick_peek_list, source.label, rows.size)
            b.tvPeekList.contentDescription = activity.getString(R.string.quick_peek_list_description, source.label, rows.size)
            if (rows.isEmpty()) {
                highlightIndex = -1
                selection.highlight(null)
                b.tvPeekEmpty.text = activity.getString(
                    if (source.kind == QuickPeek.Kind.FAVORITES) R.string.quick_peek_empty_favorites else R.string.quick_peek_empty
                )
                b.tvPeekEmpty.visibility = View.VISIBLE
                b.peekDetail.visibility = View.GONE
                b.tvPeekList.requestFocus()
                return@launch
            }
            b.tvPeekEmpty.visibility = View.GONE
            focusRow(QuickPeek.focusIndex(rows, keep, host.playingKey()), center = true)
            b.rvQuickPeek.post { loadVisibleGuide() }
        }
    }

    private fun changeSource(delta: Int) {
        if (sources.isEmpty()) return
        sourceIndex = QuickPeek.cycle(sourceIndex, delta, sources.size)
        showSource(sourceIndex)
    }

    /** Every list at once, for a touch screen or a long way round on the remote. */
    private fun pickSource() {
        if (sources.isEmpty()) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.quick_peek_pick_list)
            .setSingleChoiceItems(sources.map { it.label }.toTypedArray(), sourceIndex) { d, which ->
                d.dismiss()
                if (!isOpen) return@setSingleChoiceItems
                sourceIndex = which
                showSource(which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Focus and picking ──────────────────────────────────────────────

    private fun headerHasFocus(): Boolean {
        val f = activity.currentFocus ?: return false
        return f === b.tvPeekList || f === b.btnPeekPrevList || f === b.btnPeekNextList ||
            f === b.btnPeekFullGuide || f === b.btnCloseGuide
    }

    private fun pageSize(): Int {
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        return if (first >= 0 && last >= first) (last - first).coerceAtLeast(1) else 5
    }

    private fun move(delta: Int) {
        val size = adapter.itemCount
        if (size == 0) return
        val from = if (highlightIndex in 0 until size) highlightIndex else 0
        focusRow(QuickPeek.step(from, delta, size), center = false)
    }

    private fun focusRow(index: Int, center: Boolean) {
        if (index < 0 || index >= adapter.itemCount) return
        highlightIndex = index
        adapter.items.getOrNull(index)?.let { selection.highlight(it.key); showDetail(it) }
        val rv = b.rvQuickPeek
        val holder = rv.findViewHolderForAdapterPosition(index)
        if (holder != null && !center) {
            holder.itemView.requestFocus()
            return
        }
        layoutManager.scrollToPositionWithOffset(index, if (center) rv.height / 3 else 0)
        rv.post { if (isOpen) rv.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    private fun onHighlight(row: QuickPeek.Row, position: Int) {
        highlightIndex = position
        selection.highlight(row.key)
        showDetail(row)
        keepAlive()
    }

    private fun pick(row: QuickPeek.Row) {
        if (!isOpen) return
        when (val decision = selection.select(row.key)) {
            QuickPeek.Selection.Decision.Close -> close()
            QuickPeek.Selection.Decision.Ignore -> Unit
            is QuickPeek.Selection.Decision.Tune -> {
                val position = adapter.positionOf(decision.key)
                val target = entities.getOrNull(position)
                val list = entities
                close()
                when (target) {
                    is ChannelEntity -> host.tune(target, null, list)
                    is MergedChannelEntity -> host.tune(null, target, list)
                    else -> selection.clearPending()
                }
            }
        }
    }

    // ── Guide (local only) ─────────────────────────────────────────────

    /** Reads the stored guide for the rows around the screen that have none loaded yet. */
    private fun loadVisibleGuide() {
        if (!isOpen) return
        val rows = adapter.items
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (rows.isEmpty() || first < 0) return
        // The cache outlives a close and a scroll away: a show that has ended since is read again.
        QuickPeek.dropStale(rows, first, last, READ_AHEAD, guide, System.currentTimeMillis())
        val want = QuickPeek.toLoad(rows, first, last, READ_AHEAD, guide.keys)
        if (want.isEmpty()) {
            adapter.updateGuide(HashMap(guide), first, last)
            return
        }
        val server = want.first().serverIndex
        guideJob?.cancel()
        guideJob = activity.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val found = try {
                withContext(Dispatchers.IO) {
                    want.map { it.streamId }.chunked(CHUNK).flatMap { ids ->
                        db.epgDao().getProgramsBetween(ids, server, now, now + LOOK_AHEAD_MS)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
            if (!isOpen) return@launch
            val byStream = found.groupBy { it.streamId }
            if (guide.size > CACHE_LIMIT) guide.clear()
            for (k in want) {
                val airings = byStream[k.streamId].orEmpty().map {
                    QuickPeek.Airing(it.title, QuickPeek.ms(it.startTimestamp), QuickPeek.ms(it.stopTimestamp), it.description)
                }
                guide[k] = QuickPeek.nowNext(airings, now)
            }
            val f = layoutManager.findFirstVisibleItemPosition()
            val l = layoutManager.findLastVisibleItemPosition()
            adapter.updateGuide(HashMap(guide), f, l)
            adapter.items.getOrNull(highlightIndex)?.let { showDetail(it) }
        }
    }

    /** The 30-second clock: progress and time left move on; a show that ended is read again, locally. */
    private fun refreshVisibleGuide() {
        val rows = adapter.items
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (rows.isEmpty() || first < 0) return
        val dropped = QuickPeek.dropStale(rows, first, last, 0, guide, System.currentTimeMillis())
        if (dropped) loadVisibleGuide() else adapter.updateGuide(HashMap(guide), first, last)
        adapter.items.getOrNull(highlightIndex)?.let { showDetail(it) }
    }

    private fun showDetail(row: QuickPeek.Row) {
        val entry = adapter.guideFor(row.key)
        val show = entry?.now
        if (show == null) {
            b.peekDetail.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        b.tvPeekDetailTitle.text = show.title
        b.tvPeekDetailTime.text = "${adapter.clock(show.startMs)} – ${adapter.clock(show.stopMs)}  ·  " +
            QuickPeek.remainingLabel(QuickPeek.minutesLeft(show.stopMs, now))
        val desc = show.description.trim()
        b.tvPeekDetailDesc.text = desc
        b.tvPeekDetailDesc.visibility = if (desc.isEmpty()) View.GONE else View.VISIBLE
        val next = entry.next
        if (next != null) {
            b.tvPeekDetailNext.text = activity.getString(R.string.quick_peek_next, adapter.clock(next.startMs), next.title)
            b.tvPeekDetailNext.visibility = View.VISIBLE
        } else {
            b.tvPeekDetailNext.visibility = View.GONE
        }
        b.peekDetail.visibility = View.VISIBLE
    }

    private fun keepAlive() {
        handler.removeCallbacks(idleClose)
        if (isOpen) handler.postDelayed(idleClose, IDLE_CLOSE_MS)
    }

    companion object {
        /** Stands in for a provider's channels with no category (merged rows can have none). */
        const val NO_CATEGORY = "\u0000none"
        private const val TICK_MS = 30_000L
        private const val IDLE_CLOSE_MS = 45_000L
        private const val SCROLL_SETTLE_MS = 120L
        private const val ANIM_MS = 150L
        private const val READ_AHEAD = 12
        private const val CHUNK = 500
        private const val CACHE_LIMIT = 4_000
        private const val LOOK_AHEAD_MS = 12 * 60 * 60_000L
        private const val FAST_REPEAT = 8
        private const val FAST_STEP = 3
    }
}
