package com.iptvapp.ui.search

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.R
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.data.search.SearchFavorites
import com.iptvapp.data.search.SearchHit
import com.iptvapp.data.search.UniversalSearch
import com.iptvapp.databinding.ActivitySearchBinding
import com.iptvapp.databinding.ItemSearchResultBinding
import com.iptvapp.util.focusFirstItemWhenLaidOut
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * One search for everything (see UniversalSearch), for phones, landscape boxes and Android TV.
 *
 * A channel, an airing programme or a replay is handed back to the home screen that opened
 * this, as the guide does (stream_id, or server_index + merged_stream_id, or timeshift_url), so
 * it plays there exactly as a guide pick would. Movies, series, episodes and recordings open
 * their own screens from here.
 */
@AndroidEntryPoint
class SearchActivity : com.iptvapp.ui.MktvActivity() {

    @Inject lateinit var search: UniversalSearch
    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var prefs: com.iptvapp.data.local.PreferencesManager

    private lateinit var binding: ActivitySearchBinding
    private val adapter = ResultAdapter { onPick(it) }

    /**
     * Favorites changed on this screen, by SearchFavorites key: the results were found before,
     * so their own flags are stale. Kept across searches and filters until the screen closes.
     */
    private val favoriteChanges = HashMap<String, Boolean>()

    /** One save at a time, in tap order, so a quick double tap ends where the star shows. */
    private val favoriteSaves = kotlinx.coroutines.sync.Mutex()
    private var searchJob: Job? = null
    private var results: List<SearchHit> = emptyList()
    private var filter: Filter = Filter.ALL

    /** The filter chips: which kinds each shows. */
    enum class Filter(val label: String, val kinds: Set<SearchHit.Kind>) {
        ALL("All", SearchHit.Kind.values().toSet()),
        LIVE("Live TV", setOf(SearchHit.Kind.CHANNEL, SearchHit.Kind.ON_NOW)),
        GUIDE("Guide", setOf(SearchHit.Kind.ON_NOW, SearchHit.Kind.UPCOMING, SearchHit.Kind.CATCH_UP)),
        MOVIES("Movies", setOf(SearchHit.Kind.MOVIE)),
        SERIES("Series", setOf(SearchHit.Kind.SERIES, SearchHit.Kind.EPISODE)),
        CATCH_UP("Catch-up", setOf(SearchHit.Kind.CATCH_UP)),
        RECORDINGS("Recordings", setOf(SearchHit.Kind.RECORDING))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Edge to edge (target SDK 35): keep the bar below the status bar and the list above the
        // navigation bar and keyboard.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnSearchBack.setOnClickListener { finish() }
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = adapter
        binding.etSearch.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isSubmitAction(actionId) || enter) {
                submitSearch()
                true
            } else false
        }
        // After Back closes the keyboard: Down from the box goes straight to the first result.
        binding.etSearch.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && focusFirstResultNow()
        }
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                focusFirstResult = false
                runSearch(s.toString(), debounce = true)
            }
        })
        val initial = intent.getStringExtra(EXTRA_QUERY)
        if (initial != null) binding.etSearch.setText(initial) else runSearch("", debounce = false)
        setupVoiceSearch()
        binding.etSearch.requestFocus()
    }

    /**
     * Set by the keyboard's Search key: the first result takes focus as soon as it is shown. The
     * search runs in the background, so the results are rarely there yet when the key is pressed.
     */
    private var focusFirstResult = false

    /**
     * The keyboard's Search key (Fire TV's full-screen keyboard may send Go, Done or Next): close
     * the keyboard, search, and put the remote on the first result once it arrives. With the
     * keyboard left open, a TV's full-screen keyboard keeps covering the results.
     */
    internal fun submitSearch() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        focusFirstResult = true
        runSearch(binding.etSearch.text.toString(), debounce = false)
    }

    private fun focusFirstResultIfAsked() {
        if (!focusFirstResult || adapter.itemCount == 0) return
        focusFirstResult = false
        binding.rvSearchResults.focusFirstItemWhenLaidOut()
    }

    /** The remote onto the first result row; false when there is none on screen. */
    private fun focusFirstResultNow(): Boolean {
        val rv = binding.rvSearchResults
        val row = rv.findViewHolderForAdapterPosition(0)?.itemView ?: rv.getChildAt(0) ?: return false
        return row.requestFocus()
    }

    // ── Voice search: the mic button, or the remote's search / mic key (see VoiceSearch) ──

    private var voiceAvailable = false

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Cancelled or nothing heard: the search stays as it was.
        val spoken = VoiceSearch.spokenQuery(result.resultCode, result.data) ?: return@registerForActivityResult
        onVoiceResult(spoken)
    }

    private fun setupVoiceSearch() {
        voiceAvailable = VoiceSearch.isAvailable(this)
        binding.btnVoiceSearch.visibility = if (voiceAvailable) View.VISIBLE else View.GONE
        // Right from the box stops there when there is no mic to move to.
        if (!voiceAvailable) binding.etSearch.nextFocusRightId = View.NO_ID
        binding.btnVoiceSearch.setOnClickListener { startVoiceSearch() }
    }

    private fun startVoiceSearch() {
        try {
            voiceLauncher.launch(VoiceSearch.intent(getString(R.string.search_voice_prompt)))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.search_voice_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    /** The heard words become the search, run at once. */
    internal fun onVoiceResult(spoken: String) {
        binding.etSearch.setText(spoken)
        binding.etSearch.setSelection(binding.etSearch.text.length)
        runSearch(spoken, debounce = false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // A remote's SEARCH key (a mic button on many TV remotes) starts listening here.
        if (keyCode == KeyEvent.KEYCODE_SEARCH && voiceAvailable && (event?.repeatCount ?: 0) == 0) {
            startVoiceSearch()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun runSearch(query: String, debounce: Boolean) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            binding.searchProgress.visibility = View.GONE
            show(status = if (q.isEmpty()) getString(R.string.search_prompt) else getString(R.string.search_more_letters))
            return
        }
        searchJob = lifecycleScope.launch {
            if (debounce) delay(300)
            binding.searchProgress.visibility = View.VISIBLE
            val local = try { search.search(q) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList()
            }
            results = local
            show()
            // Episodes ask the series' provider: shown as soon as they arrive, after the rest.
            val episodes = try { search.episodes(q, local) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList()
            }
            if (episodes.isNotEmpty()) {
                results = UniversalSearch.rank(local + episodes, q)
                show()
            }
            binding.searchProgress.visibility = View.GONE
        }
    }

    private fun show(status: String? = null) {
        renderFilters()
        val visible = results.filter { it.kind in filter.kinds }
        adapter.submitList(visible) { focusFirstResultIfAsked() }
        val message = status ?: when {
            visible.isNotEmpty() -> null
            results.isEmpty() -> getString(R.string.search_nothing_found)
            else -> getString(R.string.search_nothing_in_filter, filter.label)
        }
        binding.tvSearchStatus.text = message.orEmpty()
        binding.tvSearchStatus.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    /** A chip per filter that has results (All always), with how many it holds. */
    private fun renderFilters() {
        val container = binding.searchFilters
        val hadFocus = container.hasFocus()
        container.removeAllViews()
        Filter.values().forEach { f ->
            val count = if (f == Filter.ALL) results.size else results.count { it.kind in f.kinds }
            if (f != Filter.ALL && count == 0) return@forEach
            val chip = TextView(this).apply {
                text = if (results.isEmpty()) f.label else "${f.label}  $count"
                setTextColor(getColor(if (f == filter) R.color.rack_text else R.color.rack_text_secondary))
                textSize = 15f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setBackgroundResource(if (f == filter) R.drawable.home_search_bg else R.drawable.home_tab_bg)
                isFocusable = true
                isClickable = true
                contentDescription = getString(R.string.search_filter_description, f.label, count)
                setOnClickListener {
                    filter = f
                    show()
                    container.findViewWithTag<View>(f)?.requestFocus()
                }
                tag = f
            }
            container.addView(chip, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        if (filter != Filter.ALL && container.findViewWithTag<View>(filter) == null) filter = Filter.ALL
        if (hadFocus) container.findViewWithTag<View>(filter)?.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // A channel, airing show or replay goes back to the home screen that opened search, which
    // plays it as it would a guide pick; everything else opens from here (see SearchHitUi).
    private val hitUi by lazy {
        SearchHitUi(this, repository,
            playChannel = { channel, merged -> returnChannel(channel?.streamId, merged) },
            playReplay = { replay ->
                setResult(RESULT_OK, Intent().apply {
                    // The same identity the guide sends back: stream_id on the main provider,
                    // server_index + merged_stream_id on another.
                    if (replay.serverIndex == -1) {
                        putExtra("stream_id", replay.streamId)
                    } else {
                        putExtra("stream_id", -1)
                        putExtra("server_index", replay.serverIndex)
                        putExtra("merged_stream_id", replay.streamId)
                    }
                    putExtra("timeshift_url", replay.url)
                    putExtra("timeshift_title", replay.title)
                    putExtra("catchup_start_sec", replay.startSec)
                    putExtra("catchup_duration_min", replay.durationMin)
                    putExtra("catchup_channel_name", replay.channelName)
                })
                finish()
            })
    }

    private fun onPick(hit: SearchHit) = hitUi.open(hit)

    private fun returnChannel(streamId: Int?, merged: com.iptvapp.data.local.entities.MergedChannelEntity?) {
        val data = Intent()
        if (merged != null) {
            data.putExtra("stream_id", -1).putExtra("server_index", merged.serverIndex).putExtra("merged_stream_id", merged.streamId)
        } else {
            data.putExtra("stream_id", streamId ?: return)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun detailOf(hit: SearchHit) = SearchHitUi.detailOf(this, hit)
    private fun imageOf(hit: SearchHit) = SearchHitUi.imageOf(hit)

    // ── Favorites from the results: the star, or holding a row ──

    private fun isFavorite(hit: SearchHit) = SearchFavorites.isFavorite(hit, favoriteChanges)

    /**
     * Stars [hit] (a programme stars its channel), or takes the star off. The star changes at
     * once, on this row and every other row for the same favorite; the list isn't searched again.
     */
    private fun toggleFavorite(hit: SearchHit) {
        val key = SearchFavorites.keyOf(hit) ?: return
        val favorite = !isFavorite(hit)
        favoriteChanges[key] = favorite
        adapter.repaintFavorites(key)
        val name = SearchFavorites.nameOf(hit)
        lifecycleScope.launch {
            val saved = try {
                // Finished even when a pick closes this screen straight after the tap.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    favoriteSaves.withLock { SearchFavorites.set(repository, hit, favorite) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
            if (!saved) {
                favoriteChanges[key] = !favorite
                adapter.repaintFavorites(key)
                Toast.makeText(this@SearchActivity, R.string.search_favorite_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(
                this@SearchActivity,
                getString(if (favorite) R.string.search_favorite_added else R.string.search_favorite_removed, name),
                Toast.LENGTH_SHORT
            ).show()
            // As the channel lists do: a new main-provider favorite gets its guide straight away.
            val streamId = (hit as? SearchHit.Channel)?.channel?.streamId ?: (hit as? SearchHit.Program)?.channel?.streamId
            if (favorite && streamId != null) {
                try { repository.fetchEpg(streamId) } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
        }
    }

    private fun paintStar(star: ImageView, hit: SearchHit) {
        val favorite = isFavorite(hit)
        star.setImageResource(if (favorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
        // The channel lists' colours, so a star means the same everywhere.
        star.setColorFilter(android.graphics.Color.parseColor(if (favorite) "#008CFF" else "#444444"))
        star.contentDescription = getString(favoriteLabel(hit, favorite))
    }

    private fun favoriteLabel(hit: SearchHit, favorite: Boolean = isFavorite(hit)) = when {
        hit is SearchHit.Program -> if (favorite) R.string.search_favorite_remove_channel else R.string.search_favorite_add_channel
        favorite -> R.string.search_favorite_remove
        else -> R.string.search_favorite_add
    }

    /**
     * Holding a result (a long-press, or OK held on a remote): its favorite, Top Favorites for a
     * channel, and Play or Open. Episodes and recordings have only Open, so they just open.
     */
    private fun showResultMenu(hit: SearchHit) {
        if (!SearchFavorites.canFavorite(hit)) { onPick(hit); return }
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels += getString(favoriteLabel(hit)); actions += { toggleFavorite(hit) }
        com.iptvapp.ui.home.TopFavoritesMenu.refOf(hit)?.let { ref ->
            labels += com.iptvapp.ui.home.TopFavoritesMenu.label(ref.serverIndex, ref.id)
            actions += {
                com.iptvapp.ui.home.TopFavoritesMenu.toggle(this, prefs, ref.serverIndex, ref.id, SearchFavorites.nameOf(hit))
            }
        }
        val plays = hit is SearchHit.Channel || hit.kind == SearchHit.Kind.ON_NOW
        labels += getString(if (plays) R.string.search_play else R.string.search_open); actions += { onPick(hit) }
        lifecycleScope.launch {
            // So Top Favorites reads Add or Remove from the saved list, not an old copy.
            try { com.iptvapp.data.home.TopFavoritesStore.load(prefs) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            com.iptvapp.ui.home.TopFavoritesMenu.refOf(hit)?.let { ref ->
                labels[1] = com.iptvapp.ui.home.TopFavoritesMenu.label(ref.serverIndex, ref.id)
            }
            AlertDialog.Builder(this@SearchActivity)
                .setTitle(SearchFavorites.nameOf(hit))
                .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private inner class ResultAdapter(private val onClick: (SearchHit) -> Unit) :
        ListAdapter<SearchHit, ResultAdapter.Holder>(Diff) {

        inner class Holder(val b: ItemSearchResultBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        /** Redraws only the star on rows for [key] (a channel and its programmes share one). */
        fun repaintFavorites(key: String) {
            currentList.forEachIndexed { i, hit -> if (SearchFavorites.keyOf(hit) == key) notifyItemChanged(i, PAYLOAD_FAVORITE) }
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_FAVORITE }) {
                paintStar(holder.b.btnResultFavorite, getItem(position))
            } else {
                onBindViewHolder(holder, position)
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val hit = getItem(position)
            holder.b.tvResultTitle.text = hit.title
            holder.b.tvResultDetail.text = detailOf(hit)
            holder.b.tvResultKind.text = hit.kind.label
            holder.b.root.contentDescription = "${hit.kind.label}: ${hit.title}, ${detailOf(hit)}"
            Glide.with(holder.b.ivResultImage).load(imageOf(hit))
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(holder.b.ivResultImage)
            holder.b.root.setOnClickListener { onClick(hit) }
            holder.b.root.setOnLongClickListener { showResultMenu(hit); true }
            val star = holder.b.btnResultFavorite
            if (SearchFavorites.canFavorite(hit)) {
                star.visibility = View.VISIBLE
                paintStar(star, hit)
                // Its own click: a star tap never plays or opens the result.
                star.setOnClickListener { toggleFavorite(hit) }
            } else {
                star.visibility = View.GONE
                star.setOnClickListener(null)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<SearchHit>() {
        override fun areItemsTheSame(a: SearchHit, b: SearchHit) = a.kind == b.kind && a == b
        override fun areContentsTheSame(a: SearchHit, b: SearchHit) = a == b
    }

    companion object {
        private const val PAYLOAD_FAVORITE = "favorite"

        /** Optional: a query to search for straight away. */
        const val EXTRA_QUERY = "search_query"

        /** Keyboard keys that mean "search now": Search, and the Go / Done / Next some TV keyboards show. */
        internal fun isSubmitAction(actionId: Int) = actionId == EditorInfo.IME_ACTION_SEARCH ||
            actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
            actionId == EditorInfo.IME_ACTION_NEXT
    }
}
