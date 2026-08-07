package com.iptvapp.ui.vod

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityVodDetailBinding
import com.iptvapp.ui.player.PlayerActivity
import com.iptvapp.util.Resource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Long-press a movie tile to get here — mirrors SeriesDetailActivity's plot/cast/rating
 * treatment (which movies never had, despite series getting one), while keeping the existing
 * single-tap-to-play behavior on the grid untouched for anyone used to it. */
@AndroidEntryPoint
class VodDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVodDetailBinding

    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var db: IptvDatabase

    private var streamId: Int = -1
    private var vodName: String = ""
    private var containerExtension: String = "mp4"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamId = intent.getIntExtra("vod_stream_id", -1)
        vodName = intent.getStringExtra("vod_name") ?: ""
        containerExtension = intent.getStringExtra("vod_container_extension") ?: "mp4"
        val cover = intent.getStringExtra("vod_cover")
        val rating = intent.getStringExtra("vod_rating")

        binding.tvVodTitle.text = vodName
        binding.tvVodRating.text = if (!rating.isNullOrBlank()) "★ $rating" else ""
        Glide.with(binding.ivVodCover).load(cover)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .error(android.R.drawable.ic_menu_gallery)
            .into(binding.ivVodCover)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlay.setOnClickListener { launchPlayer() }
        // Without this, initial D-pad focus could land anywhere/nowhere on this screen (nothing
        // requests it by default) — Play is the natural first stop, matching where the button
        // chain (nextFocusDown/Up) starts from.
        binding.btnPlay.requestFocus()

        if (streamId != -1) {
            loadVodInfo(streamId)
            loadResumeProgress(streamId)
            loadFavoriteState(streamId)
        }
    }

    private fun loadFavoriteState(streamId: Int) {
        lifecycleScope.launch {
            val vod = repository.getVodByStreamId(streamId) ?: return@launch
            renderFavoriteButton(vod.isFavorite)
            binding.btnFavorite.setOnClickListener {
                lifecycleScope.launch {
                    val current = repository.getVodByStreamId(streamId) ?: return@launch
                    repository.setVodFavorite(streamId, !current.isFavorite)
                    renderFavoriteButton(!current.isFavorite)
                }
            }
        }
    }

    private fun renderFavoriteButton(isFavorite: Boolean) {
        binding.btnFavorite.text = if (isFavorite) "★  Remove from Favorites" else "☆  Add to Favorites"
    }

    private fun loadResumeProgress(streamId: Int) {
        lifecycleScope.launch {
            val (watchedMs, durationMs) = repository.getVodProgress(streamId)
            if (watchedMs > 0 && durationMs > 0) {
                val pct = ((watchedMs * 100) / durationMs).coerceIn(0, 100).toInt()
                binding.progressVod.progress = pct
                binding.progressVod.visibility = View.VISIBLE
                binding.btnPlay.text = "▶  Resume ($pct%)"
                binding.btnClearProgress.visibility = View.VISIBLE
                binding.btnClearProgress.setOnClickListener {
                    lifecycleScope.launch {
                        repository.saveVodProgress(streamId, 0L, 0L)
                        binding.progressVod.visibility = View.GONE
                        binding.btnPlay.text = "▶  Play"
                        binding.btnClearProgress.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun launchPlayer() {
        lifecycleScope.launch {
            val url = repository.getVodStreamUrl(streamId, containerExtension)
            val (watchedMs, _) = repository.getVodProgress(streamId)
            startActivity(Intent(this@VodDetailActivity, PlayerActivity::class.java).apply {
                putExtra("stream_url", url)
                putExtra("stream_title", vodName)
                putExtra("stream_id", streamId)
                putExtra("is_vod", true)
                if (watchedMs > 0) putExtra("resume_ms", watchedMs)
            })
        }
    }

    private fun loadVodInfo(streamId: Int) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            when (val result = repository.fetchVodInfo(streamId)) {
                is Resource.Success -> {
                    binding.progressBar.visibility = View.GONE
                    val detail = result.data.info
                    if (detail != null) {
                        if (!detail.plot.isNullOrBlank()) binding.tvVodPlot.text = detail.plot
                        if (!detail.genre.isNullOrBlank()) binding.tvVodGenre.text = detail.genre
                        if (!detail.rating.isNullOrBlank()) binding.tvVodRating.text = "★ ${detail.rating}"
                        if (!detail.releaseDate.isNullOrBlank()) binding.tvVodYear.text = detail.releaseDate.take(4)
                        if (!detail.duration.isNullOrBlank()) binding.tvVodDuration.text = detail.duration
                        if (!detail.actors.isNullOrBlank()) binding.tvVodCast.text = "Cast: ${detail.actors}"
                        if (!detail.director.isNullOrBlank()) binding.tvVodDirector.text = "Director: ${detail.director}"
                        val fullCover = detail.coverBig ?: detail.movieImage
                        if (!fullCover.isNullOrBlank()) {
                            Glide.with(binding.ivVodCover).load(fullCover)
                                .placeholder(android.R.drawable.ic_menu_gallery)
                                .into(binding.ivVodCover)
                        }
                        // Embedded in-app playback (android-youtube-player library) was tried
                        // and pulled — it failed to actually play trailers in practice. A plain
                        // external link to the YouTube app/browser is far more reliable since it
                        // reuses YouTube's own real player instead of a WebView-based reimplementation.
                        val trailerId = detail.youtubeTrailer?.trim()
                        if (!trailerId.isNullOrBlank()) {
                            binding.btnWatchTrailer.visibility = View.VISIBLE
                            binding.btnWatchTrailer.setOnClickListener {
                                val ytIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$trailerId"))
                                runCatching { startActivity(ytIntent) }
                                    .onFailure { android.widget.Toast.makeText(this@VodDetailActivity, "No app available to open this link", android.widget.Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                }
                is Resource.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvVodPlot.text = "Couldn't load details: ${result.message}"
                }
                else -> {}
            }
        }
    }
}
