package com.iptvapp.ui.vod

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.iptvapp.databinding.ActivityTrailerBinding
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener

// Embedded trailer playback for VOD/Series — youtube_trailer from the Xtream API is a bare
// video ID (see VodInfoDetail/SeriesInfoDetail kdoc), not a full URL, which is exactly what
// YouTubePlayer.loadVideo expects. WebView-based IFrame player (android-youtube-player library)
// rather than the official YouTube Android Player API — no Google API key/quota needed.
class TrailerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrailerBinding
    private lateinit var videoId: String

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrailerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val extraVideoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        if (extraVideoId.isNullOrBlank()) {
            finish()
            return
        }
        videoId = extraVideoId

        binding.btnTrailerBack.setOnClickListener { finish() }
        binding.btnWatchOnYoutube.setOnClickListener { openInYoutube() }

        lifecycle.addObserver(binding.youtubePlayerView)
        binding.youtubePlayerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                youTubePlayer.loadVideo(videoId, 0f)
            }

            // The uploader disabled playback in third-party embedded players — this is a
            // per-video restriction set on YouTube's end (same thing youtube.com's own site
            // shows), not something a different player library or retry would fix. Offer to
            // open it in the real YouTube app/site instead of leaving a dead black screen.
            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                if (error == PlayerConstants.PlayerError.VIDEO_NOT_FOUND ||
                    error == PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER
                ) {
                    binding.tvTrailerError.visibility = View.VISIBLE
                    binding.btnWatchOnYoutube.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this@TrailerActivity, "Couldn't play trailer", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun openInYoutube() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "No app available to open this link", Toast.LENGTH_SHORT).show() }
    }
}
