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

            // Whatever the specific error code, the embedded player has already failed and
            // there's nothing left to retry within it — every PlayerError case (not-found,
            // embedding-disabled, invalid request, the generic "HTML5 player error", etc.) ends
            // up needing the same escape hatch, so always offer it rather than only for the two
            // cases originally guessed at.
            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                binding.tvTrailerError.visibility = View.VISIBLE
                binding.btnWatchOnYoutube.visibility = View.VISIBLE
            }
        })
    }

    private fun openInYoutube() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "No app available to open this link", Toast.LENGTH_SHORT).show() }
    }
}
