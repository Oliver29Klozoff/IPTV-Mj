package com.iptvapp.ui.vod

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityMergedVodDetailBinding
import com.iptvapp.ui.player.PlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Merged-provider equivalent of VodDetailActivity — long-press a merged movie tile to get
 * here, single-tap still plays directly (see MergedVodAdapter's onItemClick). No per-item
 * network detail fetch like primary VOD's fetchVodInfo — MergedVodEntity only ever caches
 * name/rating/cover/category (no plot/cast/director/duration), so this only shows what's
 * already cached plus resume/favorite/clear-progress, all of which merged VOD tracks the same
 * way primary VOD does. */
@AndroidEntryPoint
class MergedVodDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMergedVodDetailBinding

    @Inject lateinit var repository: XtreamRepository

    private var serverIndex: Int = -1
    private var streamId: Int = -1
    private var vodName: String = ""
    private var containerExtension: String = "mp4"
    private var isFavorite: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMergedVodDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverIndex = intent.getIntExtra("server_index", -1)
        streamId = intent.getIntExtra("vod_stream_id", -1)
        vodName = intent.getStringExtra("vod_name") ?: ""
        containerExtension = intent.getStringExtra("vod_container_extension") ?: "mp4"
        isFavorite = intent.getBooleanExtra("vod_is_favorite", false)
        val cover = intent.getStringExtra("vod_cover")
        val rating = intent.getStringExtra("vod_rating")
        val serverNickname = intent.getStringExtra("server_nickname") ?: ""

        binding.tvVodTitle.text = vodName
        binding.tvVodRating.text = if (!rating.isNullOrBlank()) "★ $rating" else ""
        binding.tvServerNickname.text = serverNickname
        Glide.with(binding.ivVodCover).load(cover)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .error(android.R.drawable.ic_menu_gallery)
            .into(binding.ivVodCover)

        updateFavoriteButton()
        binding.btnFavorite.setOnClickListener {
            isFavorite = !isFavorite
            lifecycleScope.launch {
                val vod = repository.getMergedVodByIndexAndId(serverIndex, streamId)
                if (vod != null) repository.setMergedVodFavorite(vod, isFavorite)
                updateFavoriteButton()
            }
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlay.setOnClickListener { launchPlayer() }

        if (serverIndex != -1 && streamId != -1) {
            loadResumeProgress()
        }
    }

    private fun updateFavoriteButton() {
        binding.btnFavorite.text = if (isFavorite) "★  Remove from Favorites" else "☆  Add to Favorites"
    }

    private fun loadResumeProgress() {
        lifecycleScope.launch {
            val (watchedMs, durationMs) = repository.getMergedVodProgress(serverIndex, streamId)
            if (watchedMs > 0 && durationMs > 0) {
                val pct = ((watchedMs * 100) / durationMs).coerceIn(0, 100).toInt()
                binding.progressVod.progress = pct
                binding.progressVod.visibility = View.VISIBLE
                binding.btnPlay.text = "▶  Resume ($pct%)"
                binding.btnClearProgress.visibility = View.VISIBLE
                binding.btnClearProgress.setOnClickListener {
                    lifecycleScope.launch {
                        repository.saveMergedVodProgress(serverIndex, streamId, 0L, 0L)
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
            try {
                val url = repository.getMergedVodStreamUrl(serverIndex, streamId, containerExtension)
                val (watchedMs, _) = repository.getMergedVodProgress(serverIndex, streamId)
                startActivity(Intent(this@MergedVodDetailActivity, PlayerActivity::class.java).apply {
                    putExtra("stream_url", url)
                    putExtra("stream_title", vodName)
                    putExtra("stream_id", -1)
                    putExtra("is_vod", true)
                    putExtra("server_index", serverIndex)
                    putExtra("merged_stream_id", streamId)
                    if (watchedMs > 0) putExtra("resume_ms", watchedMs)
                })
            } catch (_: Exception) {
                android.widget.Toast.makeText(this@MergedVodDetailActivity, "Couldn't load this movie", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
