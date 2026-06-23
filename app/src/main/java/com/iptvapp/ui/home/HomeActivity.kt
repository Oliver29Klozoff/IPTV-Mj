package com.iptvapp.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.iptvapp.databinding.ActivityHomeBinding
import com.iptvapp.ui.guide.GuideAdapter
import com.iptvapp.ui.player.PlayerActivity
import com.iptvapp.ui.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.iptvapp.update.UpdateChecker
import com.iptvapp.data.local.entities.ChannelEntity

@AndroidEntryPoint
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var vodAdapter: VodAdapter
    private lateinit var seriesAdapter: SeriesAdapter
    private lateinit var guideAdapter: GuideAdapter

    private var miniPlayer: ExoPlayer? = null
    private var currentMiniStreamId: Int = -1
    private var currentMiniUrl: String = ""
    private var currentMiniTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateChecker(this).check(lifecycleScope)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRecyclerViews()
        setupTabs()
        setupSearch()
        setupMenu()
        observeViewModel()
        viewModel.loadAll()
        observeTabVisibility()
    }

    override fun onStart() {
        super.onStart()
        if (miniPlayer == null) {
            initMiniPlayer()
        } else {
            miniPlayer?.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        miniPlayer?.release()
        miniPlayer = null
    }

    override fun onStop() {
        super.onStop()
        // Only pause if truly going to background, not when opening another activity
        if (!isChangingConfigurations) {
            miniPlayer?.pause()
        }
    }

    private fun initMiniPlayer() {
        miniPlayer = ExoPlayer.Builder(this).build().also { player ->
            binding.miniPlayerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.miniPlayerProgress.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }
            })
        }
        binding.miniPlayerView.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId)
            }
        }
        binding.btnFullscreen.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                val currentPos = miniPlayer?.currentPosition ?: 0L
                val isVodStream = currentMiniUrl.contains(Regex("movie|vod", RegexOption.IGNORE_CASE))
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = isVodStream, resumeMs = currentPos)
            }
        }
                loadLastWatchedChannel()
    }

    private fun loadLastWatchedChannel() {
        lifecycleScope.launch {
            val recent = viewModel.getRecentChannel()
            if (recent != null) {
                playInMiniPlayer(recent)
            }
        }
    }

    private fun playInMiniPlayer(channel: ChannelEntity) {
        lifecycleScope.launch {
            val url = viewModel.getLiveStreamUrl(channel.streamId)
            currentMiniUrl = url
            currentMiniTitle = channel.name
            currentMiniStreamId = channel.streamId
            binding.tvMiniChannelName.text = channel.name
            miniPlayer?.let {
                it.setMediaItem(MediaItem.fromUri(url))
                it.prepare()
                it.playWhenReady = true
            }
            val epg = viewModel.getEpgText(channel.streamId)
            binding.tvMiniEpg.text = epg
        }
    }

    private fun setupMenu() {
        binding.btnMenu.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupRecyclerViews() {
        categoryAdapter = CategoryAdapter(
            onCategoryClick = { category ->
                when (binding.tabLayout.selectedTabPosition) {
                    0 -> viewModel.selectLiveCategory(category.categoryId)
                    1 -> viewModel.selectFavCategory(category.categoryId)
                    2 -> viewModel.selectVodCategory(category.categoryId)
                }
            },
            onCategoryLongClick = { category ->
                if (binding.tabLayout.selectedTabPosition == 0) {
                    viewModel.toggleLiveCategoryFavorite(category.categoryId)
                    Toast.makeText(this, "Category favorite updated", Toast.LENGTH_SHORT).show()
                }
            }
        )

        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                lifecycleScope.launch {
                    playInMiniPlayer(channel)
                    viewModel.markChannelWatched(channel.streamId)
                    viewModel.setCurrentlyPlaying(channel.streamId)
                }
            },
            onChannelDoubleClick = { channel ->
                lifecycleScope.launch {
                    val url = viewModel.getLiveStreamUrl(channel.streamId)
                    val currentIds = viewModel.channels.value.map { it.streamId }.toIntArray()
                    openPlayer(url, channel.name, channel.streamId, currentIds)
                }
            },
            onFavoriteClick = { channel ->
                viewModel.toggleChannelFavorite(channel.streamId)
                val msg = if (channel.isFavorite) "Removed from favorites" else "Added to favorites"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        )

        vodAdapter = VodAdapter(
            onVodClick = { vod ->
                lifecycleScope.launch {
                    val url = viewModel.getVodStreamUrl(vod.streamId, vod.containerExtension)
                    val progress = viewModel.getVodProgress(vod.streamId)
                    currentMiniUrl = url
                    currentMiniTitle = vod.name
                    currentMiniStreamId = vod.streamId
                    binding.tvMiniChannelName.text = vod.name
                    miniPlayer?.let {
                        it.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                        it.prepare()
                        it.playWhenReady = true
                    }
                    // Store VOD info for fullscreen button
                    binding.btnFullscreen.setOnClickListener {
            if (currentMiniUrl.isNotEmpty()) {
                val currentPos = miniPlayer?.currentPosition ?: 0L
                val isVodStream = currentMiniUrl.contains(Regex("movie|vod", RegexOption.IGNORE_CASE))
                openPlayer(currentMiniUrl, currentMiniTitle, currentMiniStreamId, isVod = isVodStream, resumeMs = currentPos)
            }
        }
                }
            },
            onFavoriteClick = {}
        )

        seriesAdapter = SeriesAdapter(
            onSeriesClick = { series ->
                Toast.makeText(this, series.name, Toast.LENGTH_SHORT).show()
            }
        )

        guideAdapter = GuideAdapter(
            onChannelClick = { row ->
                lifecycleScope.launch {
                    playInMiniPlayer(row.channel)
                    val url = viewModel.getLiveStreamUrl(row.channel.streamId)
                    openPlayer(url, row.channel.name, row.channel.streamId)
                }
            }
        )

        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.layoutManager = LinearLayoutManager(this)
        binding.rvChannels.adapter = channelAdapter
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showLive()
                    1 -> showFavCategories()
                    2 -> showVod()
                    3 -> showSeries()
                    4 -> showFavorites()
                    5 -> showGuide()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            viewModel.searchChannels(binding.etSearch.text.toString())
            true
        }
    }

    private fun showLive() {
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = channelAdapter
        val cats = viewModel.liveCategories.value
        categoryAdapter.resetSelection()
        categoryAdapter.submitList(cats)
        if (cats.isNotEmpty()) {
            viewModel.selectLiveCategory(cats.first().categoryId)
        } else {
            viewModel.reloadCurrentLiveCategory()
        }
    }

    private fun showFavCategories() {
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = channelAdapter
        val favCats = viewModel.favoriteLiveCategories.value
        categoryAdapter.submitList(favCats)
        if (favCats.isNotEmpty()) {
            viewModel.selectFavCategory(favCats.first().categoryId)
        } else {
            channelAdapter.submitList(emptyList())
        }
    }

    private fun showVod() {
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = vodAdapter
        val cats = viewModel.vodCategories.value
        categoryAdapter.submitList(cats)
        if (cats.isNotEmpty()) viewModel.selectVodCategory(cats.first().categoryId)
    }

    private fun showSeries() {
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = seriesAdapter
        seriesAdapter.submitList(viewModel.series.value)
    }

    private fun showFavorites() {
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = channelAdapter
        viewModel.showFavoriteChannels()
    }

    private fun showGuide() {
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = guideAdapter
        viewModel.loadGuide()
    }

    private fun openPlayer(url: String, title: String, streamId: Int, streamIds: IntArray = viewModel.channels.value.map { it.streamId }.toIntArray(), isVod: Boolean = false, resumeMs: Long = 0L) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_url", url)
            putExtra("stream_title", title)
            putExtra("stream_id", streamId)
            putExtra("stream_ids", streamIds)
            putExtra("is_vod", isVod)
            putExtra("resume_ms", resumeMs)
        }
        startActivity(intent)
    }

    private fun observeTabVisibility() {
        lifecycleScope.launch {
            viewModel.showMovies.collect { show: Boolean ->
                val tab = binding.tabLayout.getTabAt(2)
                tab?.view?.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.showSeries.collect { show: Boolean ->
                val tab = binding.tabLayout.getTabAt(3)
                tab?.view?.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.loading.collect {
                binding.progressBar.visibility = if (it) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.liveCategories.collect {
                if (binding.tabLayout.selectedTabPosition == 0) categoryAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.favoriteLiveCategories.collect { favs ->
                categoryAdapter.submitFavoriteCategoryIds(favs.map { it.categoryId }.toSet())
                if (binding.tabLayout.selectedTabPosition == 1) {
                    categoryAdapter.submitList(favs)
                    if (favs.isNotEmpty()) viewModel.selectFavCategory(favs.first().categoryId)
                    else channelAdapter.submitList(emptyList())
                }
            }
        }
        lifecycleScope.launch {
            viewModel.channels.collect {
                channelAdapter.submitList(it)
                viewModel.loadEpgForChannels(it)
            }
        }
        lifecycleScope.launch {
            viewModel.vod.collect {
                if (binding.tabLayout.selectedTabPosition == 2) vodAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.series.collect {
                if (binding.tabLayout.selectedTabPosition == 3) seriesAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.guideRows.collect {
                guideAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.currentlyPlayingStreamId.collect { streamId ->
                channelAdapter.setCurrentlyPlayingStreamId(streamId)
            }
        }
        lifecycleScope.launch {
            viewModel.channelEpgText.collect {
                channelAdapter.submitEpgText(it)
            }
        }
        lifecycleScope.launch {
            viewModel.vodCategories.collect {
                if (binding.tabLayout.selectedTabPosition == 2) categoryAdapter.submitList(it)
            }
        }
    }
}