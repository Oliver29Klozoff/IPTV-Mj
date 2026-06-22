package com.iptvapp.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.iptvapp.databinding.ActivityHomeBinding
import com.iptvapp.ui.guide.GuideAdapter
import com.iptvapp.ui.player.PlayerActivity
import com.iptvapp.ui.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import com.iptvapp.update.UpdateChecker

@AndroidEntryPoint
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var vodAdapter: VodAdapter
    private lateinit var seriesAdapter: SeriesAdapter
    private lateinit var guideAdapter: GuideAdapter

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
                    1 -> viewModel.selectVodCategory(category.categoryId)
                }
            },
            onCategoryLongClick = { category ->
                if (binding.tabLayout.selectedTabPosition == 0) {
                    viewModel.toggleLiveCategoryFavorite(category.categoryId)
                    Toast.makeText(
                        this,
                        "Favorite updated: ",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                lifecycleScope.launch {
                    val url = viewModel.getLiveStreamUrl(channel.streamId)
                    openPlayer(url, channel.name, channel.streamId)
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
                    openPlayer(url, vod.name, vod.streamId)
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
                    1 -> showVod()
                    2 -> showSeries()
                    3 -> showFavorites()
                    4 -> showGuide()
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
        viewModel.reloadCurrentLiveCategory()

        lifecycleScope.launch {
            viewModel.liveCategories.collect {
                if (binding.tabLayout.selectedTabPosition != 3) {
                    categoryAdapter.submitList(it)
                }
            }
        }
    }

    private fun showVod() {
        binding.rvCategories.visibility = View.VISIBLE
        binding.rvCategories.adapter = categoryAdapter
        binding.rvChannels.adapter = vodAdapter

        lifecycleScope.launch {
            viewModel.vodCategories.collect { cats ->
                categoryAdapter.submitList(cats)
                if (cats.isNotEmpty()) {
                    viewModel.selectVodCategory(cats.first().categoryId)
                }
            }
        }
    }

    private fun showSeries() {
        binding.rvCategories.visibility = View.GONE
        binding.rvChannels.adapter = seriesAdapter
        lifecycleScope.launch {
            viewModel.series.collect {
                seriesAdapter.submitList(it)
            }
        }
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

    private fun openPlayer(url: String, title: String, streamId: Int) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("stream_url", url)
            putExtra("stream_title", title)
            putExtra("stream_id", streamId)
        }
        startActivity(intent)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.loading.collect {
                binding.progressBar.visibility = if (it) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.liveCategories.collect {
                categoryAdapter.submitList(it)
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
                vodAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.series.collect {
                seriesAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.guideRows.collect {
                guideAdapter.submitList(it)
            }
        }
        lifecycleScope.launch {
            viewModel.favoriteLiveCategories.collect { favs ->
                categoryAdapter.submitFavoriteCategoryIds(favs.map { it.categoryId }.toSet())
            }
        }
        lifecycleScope.launch {
            viewModel.channelEpgText.collect {
                channelAdapter.submitEpgText(it)
            }
        }
    }
}