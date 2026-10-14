package com.iptvapp.ui.search

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.R
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.data.search.SearchHit
import com.iptvapp.data.search.UniversalSearch
import com.iptvapp.databinding.ActivitySearchBinding
import com.iptvapp.databinding.ItemSearchResultBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
class SearchActivity : AppCompatActivity() {

    @Inject lateinit var search: UniversalSearch
    @Inject lateinit var repository: XtreamRepository

    private lateinit var binding: ActivitySearchBinding
    private val adapter = ResultAdapter { onPick(it) }
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
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                runSearch(binding.etSearch.text.toString(), debounce = false)
                // On a TV remote: from the box to the first result.
                binding.rvSearchResults.post { binding.rvSearchResults.getChildAt(0)?.requestFocus() }
                true
            } else false
        }
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = runSearch(s.toString(), debounce = true)
        })
        val initial = intent.getStringExtra(EXTRA_QUERY)
        if (initial != null) binding.etSearch.setText(initial) else runSearch("", debounce = false)
        binding.etSearch.requestFocus()
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
        adapter.submitList(visible)
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

    private fun onPick(hit: SearchHit) {
        when (hit) {
            is SearchHit.Channel -> returnChannel(hit.channel?.streamId, hit.merged)
            is SearchHit.Program -> when (hit.kind) {
                SearchHit.Kind.ON_NOW -> returnChannel(hit.channel?.streamId, hit.merged)
                SearchHit.Kind.CATCH_UP -> returnReplay(hit)
                else -> offerUpcoming(hit)
            }
            is SearchHit.Movie -> openMovie(hit)
            is SearchHit.Series -> openSeries(hit, null)
            is SearchHit.Episode -> openSeries(hit.series, hit)
            is SearchHit.Recording -> openRecording(hit)
        }
    }

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

    private fun returnReplay(hit: SearchHit.Program) {
        val channel = hit.channel ?: return
        lifecycleScope.launch {
            val startSec = hit.startMs / 1000L
            val durationMin = ((hit.stopMs - hit.startMs) / 60_000L).toInt().coerceAtLeast(1)
            val url = try { repository.getTimeshiftUrl(channel.streamId, startSec, durationMin) } catch (_: Exception) {
                Toast.makeText(this@SearchActivity, R.string.search_replay_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            setResult(RESULT_OK, Intent()
                .putExtra("stream_id", channel.streamId)
                .putExtra("timeshift_url", url)
                .putExtra("timeshift_title", "${channel.name} — ${hit.programTitle}")
                .putExtra("catchup_start_sec", startSec)
                .putExtra("catchup_duration_min", durationMin)
                .putExtra("catchup_channel_name", channel.name))
            finish()
        }
    }

    // Upcoming: a reminder (and a recording, for the main provider, as the guide offers), or the
    // channel now.
    private fun offerUpcoming(hit: SearchHit.Program) {
        val when_ = formatWhen(hit.startMs, past = false)
        val builder = AlertDialog.Builder(this)
            .setTitle("\"${hit.programTitle}\"")
            .setMessage("${hit.channelName} · $when_")
            .setNegativeButton(R.string.search_watch_channel_now) { _, _ -> returnChannel(hit.channel?.streamId, hit.merged) }
            .setNeutralButton(R.string.search_remind_me) { _, _ ->
                val merged = hit.merged
                if (merged != null) {
                    com.iptvapp.ui.guide.ChannelTimerScheduler.schedule(this, merged.streamId, merged.name, hit.programTitle, hit.startMs, merged.serverIndex)
                } else {
                    val ch = hit.channel ?: return@setNeutralButton
                    com.iptvapp.ui.guide.ChannelTimerScheduler.schedule(this, ch.streamId, ch.name, hit.programTitle, hit.startMs)
                }
                Toast.makeText(this, getString(R.string.search_reminder_set, when_), Toast.LENGTH_SHORT).show()
            }
        val ch = hit.channel
        if (ch != null) {
            builder.setPositiveButton(R.string.search_record) { _, _ ->
                startActivity(
                    Intent(this, com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_STREAM_ID, ch.streamId)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_START_MS, hit.startMs)
                        .putExtra(com.iptvapp.ui.recordings.RecordingSchedulerActivity.EXTRA_PREFILL_DURATION_MS, (hit.stopMs - hit.startMs).coerceAtLeast(60_000L))
                )
            }
        }
        builder.show()
    }

    private fun openMovie(hit: SearchHit.Movie) {
        val vod = hit.vod
        val merged = hit.merged
        if (vod != null) {
            startActivity(Intent(this, com.iptvapp.ui.vod.VodDetailActivity::class.java).apply {
                putExtra("vod_stream_id", vod.streamId)
                putExtra("vod_name", vod.name)
                putExtra("vod_container_extension", vod.containerExtension)
                putExtra("vod_cover", vod.streamIcon)
                putExtra("vod_rating", vod.rating)
            })
        } else if (merged != null) {
            startActivity(Intent(this, com.iptvapp.ui.vod.MergedVodDetailActivity::class.java).apply {
                putExtra("server_index", merged.serverIndex)
                putExtra("vod_stream_id", merged.streamId)
                putExtra("vod_name", merged.name)
                putExtra("vod_container_extension", merged.containerExtension)
                putExtra("vod_cover", merged.streamIcon)
                putExtra("vod_rating", merged.rating)
                putExtra("vod_is_favorite", merged.isFavorite)
                putExtra("server_nickname", merged.serverNickname)
            })
        }
    }

    private fun openSeries(hit: SearchHit.Series, episode: SearchHit.Episode?) {
        val series = hit.series
        val merged = hit.merged
        startActivity(Intent(this, com.iptvapp.ui.series.SeriesDetailActivity::class.java).apply {
            putExtra("series_id", series?.seriesId ?: merged?.seriesId ?: return)
            putExtra("series_name", series?.name ?: merged?.name)
            putExtra("series_cover", series?.cover ?: merged?.cover)
            putExtra("series_genre", series?.genre ?: merged?.genre)
            putExtra("series_rating", series?.rating ?: merged?.rating)
            putExtra("series_plot", series?.plot ?: merged?.plot)
            if (merged != null) putExtra("server_index", merged.serverIndex)
            if (episode != null) {
                putExtra(com.iptvapp.ui.series.SeriesDetailActivity.EXTRA_FOCUS_SEASON, episode.season)
                putExtra(com.iptvapp.ui.series.SeriesDetailActivity.EXTRA_FOCUS_EPISODE, episode.episode)
            }
        })
    }

    private fun openRecording(hit: SearchHit.Recording) {
        val rec = hit.recording
        when (rec.status) {
            "DONE" -> com.iptvapp.util.RecordingFileUtils.playInApp(this, rec)
            "RECORDING" -> Toast.makeText(this, R.string.search_recording_now, Toast.LENGTH_SHORT).show()
            "SCHEDULED" -> Toast.makeText(this, getString(R.string.search_recording_scheduled, formatWhen(rec.scheduledStartMs, past = false)), Toast.LENGTH_SHORT).show()
            "FAILED" -> Toast.makeText(this, rec.failureReason ?: getString(R.string.search_recording_failed), Toast.LENGTH_LONG).show()
            // COMPRESSING, or any later in-progress state: not playable yet, not failed.
            else -> Toast.makeText(this, R.string.search_recording_finishing, Toast.LENGTH_SHORT).show()
        }
    }

    /** "8:00 PM" today, "Tue 8:00 PM" within a week, else "Oct 21 8:00 PM". */
    private fun formatWhen(ms: Long, past: Boolean): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val withinWeek = kotlin.math.abs(ms - now.timeInMillis) < 6L * 86_400_000L
        val pattern = when {
            sameDay -> "h:mm a"
            withinWeek -> "EEE h:mm a"
            else -> "MMM d h:mm a"
        }
        val text = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
        return if (sameDay && past) getString(R.string.search_today_at, text) else text
    }

    private fun detailOf(hit: SearchHit): String {
        val parts = mutableListOf<String>()
        when (hit) {
            is SearchHit.Channel -> {}
            is SearchHit.Program -> {
                parts += hit.channelName
                parts += when (hit.kind) {
                    SearchHit.Kind.ON_NOW -> getString(R.string.search_until, formatWhen(hit.stopMs, past = false))
                    SearchHit.Kind.CATCH_UP -> getString(R.string.search_aired, formatWhen(hit.startMs, past = true))
                    else -> formatWhen(hit.startMs, past = false)
                }
            }
            is SearchHit.Movie -> (hit.vod?.rating ?: hit.merged?.rating)?.takeIf { it.isNotBlank() }?.let { parts += "★ $it" }
            is SearchHit.Series -> (hit.series?.rating ?: hit.merged?.rating)?.takeIf { it.isNotBlank() }?.let { parts += "★ $it" }
            is SearchHit.Episode -> {
                parts += hit.series.title
                parts += "S${hit.season}E${hit.episode}"
            }
            is SearchHit.Recording -> {
                parts += hit.recording.channelName
                parts += formatWhen(hit.recording.scheduledStartMs, past = true)
                if (hit.recording.status != "DONE") parts += hit.recording.status.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        hit.provider?.let { parts += it }
        return parts.joinToString(" · ")
    }

    private fun imageOf(hit: SearchHit): String? = when (hit) {
        is SearchHit.Channel -> hit.icon
        is SearchHit.Program -> hit.icon
        is SearchHit.Movie -> hit.poster
        is SearchHit.Series -> hit.cover
        is SearchHit.Episode -> hit.series.cover
        is SearchHit.Recording -> null
    }

    private inner class ResultAdapter(private val onClick: (SearchHit) -> Unit) :
        ListAdapter<SearchHit, ResultAdapter.Holder>(Diff) {

        inner class Holder(val b: ItemSearchResultBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

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
        }
    }

    private object Diff : DiffUtil.ItemCallback<SearchHit>() {
        override fun areItemsTheSame(a: SearchHit, b: SearchHit) = a.kind == b.kind && a == b
        override fun areContentsTheSame(a: SearchHit, b: SearchHit) = a == b
    }

    companion object {
        /** Optional: a query to search for straight away. */
        const val EXTRA_QUERY = "search_query"
    }
}
