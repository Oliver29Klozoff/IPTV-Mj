package com.iptvapp.ui.series

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.iptvapp.R
import com.iptvapp.data.api.Episode
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivitySeriesDetailBinding
import com.iptvapp.databinding.ItemEpisodeBinding
import com.iptvapp.ui.player.PlayerActivity
import com.iptvapp.util.Resource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SeriesDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeriesDetailBinding
    private lateinit var episodeAdapter: EpisodeAdapter

    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var db: com.iptvapp.data.local.IptvDatabase

    private var allEpisodes: Map<String, List<Episode>> = emptyMap()
    private var currentSeasonEpisodes: List<Episode> = emptyList()
    private var seriesNameField: String = ""
    private var seriesIdField: Int = -1
    // (season, episode) pairs marked watched — currently only ever populated by a Trakt
    // history sync-back, since the app has no other way yet to know about episodes watched
    // outside itself (or before this device's own play history began).
    private var watchedEpisodes: Set<Pair<Int, Int>> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Targeting SDK 35 (Android 15+) makes edge-to-edge content mandatory — the season
        // tab row was drawing underneath the status bar with nothing pushing it down,
        // making the tabs behind the status bar/notification area untappable. Pad the top
        // of the season tabs by exactly the system bar inset instead of guessing a fixed
        // dp value that wouldn't match every device/notch.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // Grow the row's height by the inset (rather than just padding within its fixed
            // 48dp), so the tabs themselves keep their full tappable height below the
            // status bar instead of being squeezed into whatever's left over.
            binding.tabSeasons.updatePadding(top = bars.top)
            binding.tabSeasons.updateLayoutParams<ViewGroup.LayoutParams> {
                height = (48 * resources.displayMetrics.density).toInt() + bars.top
            }
            insets
        }

        val seriesId = intent.getIntExtra("series_id", -1)
        seriesIdField = seriesId
        val seriesName = intent.getStringExtra("series_name") ?: ""
        seriesNameField = seriesName
        val seriesCover = intent.getStringExtra("series_cover")
        val seriesGenre = intent.getStringExtra("series_genre")
        val seriesRating = intent.getStringExtra("series_rating")
        val seriesPlot = intent.getStringExtra("series_plot")

        binding.tvSeriesTitle.text = seriesName
        binding.tvSeriesGenre.text = seriesGenre ?: ""
        binding.tvSeriesRating.text = if (!seriesRating.isNullOrBlank()) "★ $seriesRating" else ""
        binding.tvSeriesPlot.text = seriesPlot ?: ""
        Glide.with(binding.ivSeriesCover).load(seriesCover)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .into(binding.ivSeriesCover)

        binding.btnBack.setOnClickListener { finish() }

        episodeAdapter = EpisodeAdapter { episode ->
            launchEpisode(episode)
        }
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        binding.rvEpisodes.adapter = episodeAdapter

        if (seriesId != -1) loadSeriesInfo(seriesId)
    }

    private fun loadSeriesInfo(seriesId: Int) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            // watchedAt == 0 is a progress-only sentinel (saved by the player mid-episode,
            // via ensureRow) — it must not count as "watched" for the completed-episode dot,
            // only a real completion (Trakt import, cross-device sync) sets watchedAt.
            val episodeProgress = db.episodeWatchedDao().getForSeries(seriesId)
                .associateBy { it.season to it.episode }
            watchedEpisodes = episodeProgress.filterValues { it.watchedAt > 0 }.keys
            when (val result = repository.fetchSeriesInfo(seriesId)) {
                is Resource.Success -> {
                    binding.progressBar.visibility = View.GONE
                    val info = result.data
                    allEpisodes = info.episodes ?: emptyMap()

                    info.info?.let { detail ->
                        if (!detail.plot.isNullOrBlank()) binding.tvSeriesPlot.text = detail.plot
                        if (!detail.genre.isNullOrBlank()) binding.tvSeriesGenre.text = detail.genre
                        if (!detail.rating.isNullOrBlank()) binding.tvSeriesRating.text = "★ ${detail.rating}"
                    }

                    val seasons = allEpisodes.keys.sortedBy { it.toIntOrNull() ?: 0 }
                    if (seasons.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                        return@launch
                    }

                    binding.tabSeasons.removeAllTabs()
                    seasons.forEach { season ->
                        binding.tabSeasons.addTab(
                            binding.tabSeasons.newTab().setText("S$season")
                        )
                    }

                    fun showSeason(seasonKey: String) {
                        val episodes = allEpisodes[seasonKey].orEmpty()
                            .sortedBy { it.episodeNum }
                        currentSeasonEpisodes = episodes
                        episodeAdapter.submitList(episodes)
                        binding.tvEmpty.visibility = if (episodes.isEmpty()) View.VISIBLE else View.GONE
                    }

                    showSeason(seasons.first())

                    binding.tabSeasons.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                        override fun onTabSelected(tab: TabLayout.Tab?) {
                            val idx = tab?.position ?: 0
                            if (idx < seasons.size) showSeason(seasons[idx])
                        }
                        override fun onTabUnselected(tab: TabLayout.Tab?) {}
                        override fun onTabReselected(tab: TabLayout.Tab?) {}
                    })
                }
                is Resource.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.tvEmpty.text = "Failed to load: ${result.message}"
                }
                else -> {}
            }
        }
    }

    fun launchEpisode(episode: Episode) {
        val episodes = currentSeasonEpisodes.ifEmpty { return }
        val index = episodes.indexOfFirst { it.id == episode.id }.takeIf { it >= 0 } ?: return
        lifecycleScope.launch {
            val url = repository.getSeriesEpisodeUrl(episode.id, episode.containerExtension)
            val resumeMs = if (seriesIdField != -1)
                db.episodeWatchedDao().getWatchedMs(seriesIdField, episode.season, episode.episodeNum) ?: 0L
            else 0L
            startActivity(Intent(this@SeriesDetailActivity, PlayerActivity::class.java).apply {
                putExtra("stream_url", url)
                putExtra("stream_title", "S${episode.season}E${episode.episodeNum} ${episode.title}")
                putExtra("stream_id", episode.id.hashCode())
                putExtra("is_vod", true)
                putExtra("series_id", seriesIdField)
                putExtra("series_name", seriesNameField)
                putExtra("season_num", episode.season)
                putExtra("episode_num", episode.episodeNum)
                putExtra("ep_index", index)
                putExtra("resume_ms", resumeMs)
                putStringArrayListExtra("ep_ids",      ArrayList(episodes.map { it.id }))
                putStringArrayListExtra("ep_titles",   ArrayList(episodes.map { "S${it.season}E${it.episodeNum} ${it.title}" }))
                putStringArrayListExtra("ep_exts",     ArrayList(episodes.map { it.containerExtension }))
            })
        }
    }

    inner class EpisodeAdapter(
        private val onEpisodeClick: (Episode) -> Unit
    ) : ListAdapter<Episode, EpisodeAdapter.VH>(object : DiffUtil.ItemCallback<Episode>() {
        override fun areItemsTheSame(a: Episode, b: Episode) = a.id == b.id
        override fun areContentsTheSame(a: Episode, b: Episode) = a == b
    }) {
        inner class VH(val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = getItem(position)
            val watched = (ep.season to ep.episodeNum) in watchedEpisodes
            holder.binding.tvEpisodeNum.text = "E${ep.episodeNum}"
            holder.binding.tvEpisodeTitle.text = if (watched) "✓ ${ep.title}" else ep.title
            holder.binding.tvEpisodeAdded.text = ep.added ?: ""
            holder.binding.root.alpha = if (watched) 0.6f else 1f
            holder.binding.root.setOnClickListener { onEpisodeClick(ep) }
        }
    }
}
