package com.iptvapp.ui.series

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    private var allEpisodes: Map<String, List<Episode>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val seriesId = intent.getIntExtra("series_id", -1)
        val seriesName = intent.getStringExtra("series_name") ?: ""
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
            lifecycleScope.launch {
                val url = repository.getSeriesEpisodeUrl(episode.id, episode.containerExtension)
                startActivity(Intent(this@SeriesDetailActivity, PlayerActivity::class.java).apply {
                    putExtra("stream_url", url)
                    putExtra("stream_title", "S${episode.season}E${episode.episodeNum} ${episode.title}")
                    putExtra("stream_id", episode.id.hashCode())
                    putExtra("is_vod", true)
                })
            }
        }
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        binding.rvEpisodes.adapter = episodeAdapter

        if (seriesId != -1) loadSeriesInfo(seriesId)
    }

    private fun loadSeriesInfo(seriesId: Int) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
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
            holder.binding.tvEpisodeNum.text = "E${ep.episodeNum}"
            holder.binding.tvEpisodeTitle.text = ep.title
            holder.binding.tvEpisodeAdded.text = ep.added ?: ""
            holder.binding.root.setOnClickListener { onEpisodeClick(ep) }
        }
    }
}
