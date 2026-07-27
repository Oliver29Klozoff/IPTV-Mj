package com.iptvapp.ui.vod

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.iptvapp.databinding.ActivityTrailerBinding
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener

// Embedded trailer playback for VOD/Series — youtube_trailer from the Xtream API is a bare
// video ID (see VodInfoDetail/SeriesInfoDetail kdoc), not a full URL, which is exactly what
// YouTubePlayer.loadVideo expects. WebView-based IFrame player (android-youtube-player library)
// rather than the official YouTube Android Player API — no Google API key/quota needed.
class TrailerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrailerBinding

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrailerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        if (videoId.isNullOrBlank()) {
            finish()
            return
        }

        binding.btnTrailerBack.setOnClickListener { finish() }

        lifecycle.addObserver(binding.youtubePlayerView)
        binding.youtubePlayerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                youTubePlayer.loadVideo(videoId, 0f)
            }
        })
    }
}
