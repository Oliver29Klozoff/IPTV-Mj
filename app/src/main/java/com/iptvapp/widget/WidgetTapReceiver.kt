package com.iptvapp.widget

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.ui.player.PlayerActivity
import com.iptvapp.ui.series.SeriesDetailActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

// Transparent pass-through Activity used as the widget's PendingIntentTemplate target for
// Continue Watching rows. Live-channel rows keep going straight to HomeActivity (its
// EXTRA_JUMP_TO_STREAM_ID handling already works fine with no lookup needed), but a VOD row needs
// its stream URL resolved via the repository first — that's an async/credentialed call a
// RemoteViews fill-in intent can't do on its own, so it needs a real Activity/coroutine scope to
// run in before handing off to PlayerActivity.
@AndroidEntryPoint
class WidgetTapReceiver : AppCompatActivity() {

    @Inject lateinit var repository: XtreamRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_JUMP_TO_CHANNEL -> {
                startActivity(Intent(this, com.iptvapp.ui.home.HomeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(
                        com.iptvapp.ui.home.HomeActivity.EXTRA_JUMP_TO_STREAM_ID,
                        intent.getIntExtra(EXTRA_STREAM_ID, -1)
                    )
                })
                finish()
            }
            ACTION_PLAY_VOD -> {
                val streamId = intent.getIntExtra(EXTRA_STREAM_ID, -1)
                val ext = intent.getStringExtra(EXTRA_CONTAINER_EXT) ?: "mp4"
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                if (streamId == -1) { finish(); return }
                lifecycleScope.launch {
                    val url = repository.getVodStreamUrl(streamId, ext)
                    val (watchedMs, _) = repository.getVodProgress(streamId)
                    startActivity(Intent(this@WidgetTapReceiver, PlayerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("stream_url", url)
                        putExtra("stream_title", title)
                        putExtra("stream_id", streamId)
                        putExtra("is_vod", true)
                        if (watchedMs > 0) putExtra("resume_ms", watchedMs)
                    })
                    finish()
                }
            }
            ACTION_OPEN_SERIES -> {
                startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("series_id", intent.getIntExtra(EXTRA_SERIES_ID, -1))
                    putExtra("series_name", intent.getStringExtra(EXTRA_SERIES_NAME) ?: "")
                })
                finish()
            }
            else -> finish()
        }
    }

    companion object {
        const val EXTRA_ACTION = "widget_tap_action"
        const val ACTION_JUMP_TO_CHANNEL = "jump_to_channel"
        const val ACTION_PLAY_VOD = "play_vod"
        const val ACTION_OPEN_SERIES = "open_series"
        const val EXTRA_STREAM_ID = "widget_tap_stream_id"
        const val EXTRA_CONTAINER_EXT = "widget_tap_container_ext"
        const val EXTRA_TITLE = "widget_tap_title"
        const val EXTRA_SERIES_ID = "widget_tap_series_id"
        const val EXTRA_SERIES_NAME = "widget_tap_series_name"
    }
}
