package com.iptvapp.ui.player

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.util.Log
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import com.iptvapp.util.isLargeScreenDevice
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.iptvapp.data.local.entities.ChannelEntity
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptvapp.ui.home.ChannelAdapter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import okhttp3.OkHttpClient
import javax.inject.Inject

@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private lateinit var guideAdapter: ChannelAdapter

    private var isOverlayVisible = false
    private var isHealthBadgeActive = false

    private val hideRunnable = Runnable {
        isOverlayVisible = false
        binding.epgOverlay.visibility = View.GONE
        binding.btnBack.visibility = View.GONE
        binding.btnGuide.visibility = View.GONE
        binding.btnPlayPause.visibility = View.GONE
        binding.bottomControls.visibility = View.GONE
        binding.btnDvrRewind.visibility = View.GONE
        binding.btnDvrLive.visibility = View.GONE
        binding.btnRecordDot.visibility = View.GONE
        binding.btnCast.visibility = View.GONE
        binding.bufferHealthBadge.visibility = View.GONE
    }

    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { binding.channelOsd.visibility = View.GONE }

    private val indicatorHandler = Handler(Looper.getMainLooper())
    private val hideBrightnessRunnable = Runnable { binding.brightnessIndicator.visibility = View.GONE }
    private val hideVolumeRunnable = Runnable { binding.volumeIndicator.visibility = View.GONE }

    private lateinit var gestureDetector: GestureDetector
    private var streamUrl: String = ""
    private var streamTitle: String = ""
    private var streamId: Int = -1
    // See onCreate's intent-extra reads for the full explanation of these two fields.
    private var serverIndex: Int = -1
    private var mergedStreamId: Int = -1
    private var isVod: Boolean = false
    private var resumePositionMs: Long = 0L
    // Set only when playing a series episode (from series_id extra) — progress for episodes
    // saves into episode_watched (keyed by seriesId/season/episode), never vod_streams, since
    // streamId here is episode.id.hashCode(), not a real vod_streams row.
    private var episodeSeriesId: Int = -1

    // Trakt scrobbling (VOD only — live channels have no stable Trakt-identifiable content)
    @Inject lateinit var traktManager: com.iptvapp.trakt.TraktManager
    private var traktSeriesName: String = ""
    private var traktSeason: Int = -1
    private var traktEpisode: Int = -1
    private var traktScrobbleStarted = false
    private var suppressOverlayOnReady = false
    private val traktIoScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    // Episode playlist for auto-play next
    private var epIds: List<String> = emptyList()
    private var epTitles: List<String> = emptyList()
    private var epExts: List<String> = emptyList()
    private var epIndex: Int = -1
    private var upNextJob: kotlinx.coroutines.Job? = null

    // AspectRatioFrameLayout's Fit/Zoom/Stretch only react to a mismatch between the video's
    // *reported* dimensions and the container's — when a channel's codec metadata already
    // matches the screen's aspect ratio (the common case: 16:9 IPTV on a 16:9 TV), all three
    // modes end up visually identical, even though the actual picture can still have baked-in
    // black bars (common on SD-upscaled channels) that resize modes can't see or crop. The last
    // two steps add a plain view scale transform on top of Fit, which crops those out
    // regardless of what the codec reports.
    private data class ResizeStep(val mode: Int, val scale: Float, val label: String)
    // After the three aspect modes, zoom continues in +10% increments per press (up to +100%)
    // instead of the old fixed 15%/30% jumps, then wraps back around to Best Fit.
    private val resizeSteps = listOf(
        ResizeStep(AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.0f, "Best Fit"),
        ResizeStep(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, 1.0f, "Zoom (aspect)"),
        ResizeStep(AspectRatioFrameLayout.RESIZE_MODE_FILL, 1.0f, "Stretch")
    ) + (1..10).map { i ->
        ResizeStep(AspectRatioFrameLayout.RESIZE_MODE_FIT, 1f + i * 0.1f, "Zoom In ${i * 10}%")
    }
    private var resizeModeIndex = 0

    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var prefs: com.iptvapp.data.local.PreferencesManager

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var channels: List<ChannelEntity> = emptyList()
    private var currentIndex: Int = -1
    // Merged/secondary-provider equivalent of channels/currentIndex above — DPAD up/down and
    // the on-screen prev/next zones previously did nothing (or worse, tried to zap through the
    // stale/irrelevant primary channels list) while playing a merged channel, since only the
    // primary list was ever fetched. Populated in onCreate the same way channels is: from
    // whichever category the channel actually lives in, fetched fresh rather than passed in
    // via intent extra, since merged channels are opened from many different call sites
    // (mini player, Providers, Guide, Favorites) that don't all have a ready list on hand.
    private var mergedChannels: List<com.iptvapp.data.local.entities.MergedChannelEntity> = emptyList()
    private var mergedCurrentIndex: Int = -1

    private var retryCount = 0
    private var lastBackPressMs = 0L
    private val maxRetries = 5
    private var retryJob: Job? = null
    private var channelSwitchJob: Job? = null
    private var bufferWatchdog: Runnable? = null

    private var sleepTimer: CountDownTimer? = null
    private var isAdjustingGesture = false
    private var gestureAccumY = 0f
    private val seekHandler = Handler(Looper.getMainLooper())
    private var seekRunnable: Runnable? = null

    private var statsVisible = false
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            updateStats()
            statsHandler.postDelayed(this, 1000)
        }
    }

    private val healthHandler = Handler(Looper.getMainLooper())
    private val healthRunnable = object : Runnable {
        override fun run() {
            updateHealthBadge()
            healthHandler.postDelayed(this, 2000)
        }
    }

    private var castContext: CastContext? = null
    private var castSession: CastSession? = null
    private var castAvailable = false
    private var castProxy: com.iptvapp.cast.IptvCastProxy? = null
    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, id: String) { castSession = session; stopLocalAndCast(session) }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { castSession = session; stopLocalAndCast(session) }
        override fun onSessionEnded(session: CastSession, error: Int) {
            castSession = null
            castProxy?.stop()
            castProxy = null
            loadStream(streamUrl)
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, id: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setupFavoritesGuide()
        setupResizeButton()
        setupActionButtons()
        observeRecordingState()
        setupCast()

        streamUrl = intent.getStringExtra("stream_url") ?: ""
        streamTitle = intent.getStringExtra("stream_title") ?: ""
        streamId = intent.getIntExtra("stream_id", -1)
        // -1 = primary server (the default everywhere else this sentinel is used). Only ever
        // non-(-1) for a merged/Providers channel, which always has streamId == -1 above (no
        // DB-backed identity) — mergedStreamId carries that channel's real per-server stream id
        // instead, since streamId can't do double duty as both "primary DB key" and "merged API id".
        serverIndex = intent.getIntExtra("server_index", -1)
        mergedStreamId = intent.getIntExtra("merged_stream_id", -1)
        isVod = intent.getBooleanExtra("is_vod", false)
        resumePositionMs = intent.getLongExtra("resume_ms", 0L)
        epIds    = intent.getStringArrayListExtra("ep_ids")    ?: emptyList()
        epTitles = intent.getStringArrayListExtra("ep_titles") ?: emptyList()
        epExts   = intent.getStringArrayListExtra("ep_exts")   ?: emptyList()
        epIndex  = intent.getIntExtra("ep_index", -1)
        traktSeriesName = intent.getStringExtra("series_name") ?: ""
        traktSeason  = intent.getIntExtra("season_num", -1)
        traktEpisode = intent.getIntExtra("episode_num", -1)
        episodeSeriesId = intent.getIntExtra("series_id", -1)

        setupChannelZones()
        setupGestureDetector()
        binding.tvChannelTitle.text = streamTitle
        binding.btnBack.setOnClickListener { finish() }

        val streamIds = intent.getIntArrayExtra("stream_ids")
        if (!isVod && serverIndex != -1 && mergedStreamId != -1) {
            // Merged channel — fetch the same category list Providers/Guide/etc. would show,
            // so DPAD up/down and the on-screen zones zap through it exactly like primary does.
            lifecycleScope.launch {
                val current = repository.getMergedChannelByIndexAndId(serverIndex, mergedStreamId)
                if (current != null) {
                    mergedChannels = repository.getMergedChannelsByCategory(serverIndex, current.categoryId).first()
                    mergedCurrentIndex = mergedChannels.indexOfFirst { it.streamId == mergedStreamId }
                }
            }
        } else {
            lifecycleScope.launch {
                channels = if (streamIds != null && streamIds.isNotEmpty()) {
                    val all = repository.getAllChannels().first()
                    val idSet = streamIds.toSet()
                    val idOrder = streamIds.withIndex().associate { it.value to it.index }
                    all.filter { it.streamId in idSet }.sortedBy { idOrder[it.streamId] }
                } else {
                    repository.getAllChannels().first()
                }
                currentIndex = channels.indexOfFirst { it.streamId == streamId }
            }
        }

        if (isVod && resumePositionMs > 0L) showResumeDialog()
    }

    private fun showUpNextIfAvailable() {
        val nextIndex = epIndex + 1
        if (epIds.isEmpty() || nextIndex >= epIds.size) return
        val nextTitle = epTitles.getOrElse(nextIndex) { "Next Episode" }
        binding.tvUpNextTitle.text = nextTitle
        binding.upNextCard.visibility = View.VISIBLE

        val totalMs = 10_000L
        upNextJob = lifecycleScope.launch {
            val start = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                val remaining = (totalMs - elapsed).coerceAtLeast(0L)
                binding.upNextProgress.progress = ((remaining.toFloat() / totalMs) * 100).toInt()
                if (remaining == 0L) { playNextEpisode(nextIndex); break }
                kotlinx.coroutines.delay(100)
            }
        }

        binding.btnUpNextPlay.setOnClickListener {
            upNextJob?.cancel()
            playNextEpisode(nextIndex)
        }
        binding.btnUpNextCancel.setOnClickListener {
            upNextJob?.cancel()
            binding.upNextCard.visibility = View.GONE
        }
    }

    private fun playNextEpisode(nextIndex: Int) {
        binding.upNextCard.visibility = View.GONE
        lifecycleScope.launch {
            val url = repository.getSeriesEpisodeUrl(epIds[nextIndex], epExts[nextIndex])
            val nextSeasonEpisode = traktManager.parseSeasonEpisode(epTitles[nextIndex])
            val resumeMs = if (episodeSeriesId != -1 && nextSeasonEpisode != null)
                repository.getEpisodeProgress(episodeSeriesId, nextSeasonEpisode.first, nextSeasonEpisode.second).first
            else 0L
            val intent = Intent(this@PlayerActivity, PlayerActivity::class.java).apply {
                putExtra("stream_url", url)
                putExtra("stream_title", epTitles[nextIndex])
                putExtra("stream_id", epIds[nextIndex].hashCode())
                putExtra("is_vod", true)
                putExtra("series_id", episodeSeriesId)
                putExtra("ep_index", nextIndex)
                putExtra("resume_ms", resumeMs)
                putStringArrayListExtra("ep_ids",    ArrayList(epIds))
                putStringArrayListExtra("ep_titles", ArrayList(epTitles))
                putStringArrayListExtra("ep_exts",   ArrayList(epExts))
                putExtra("series_name", traktSeriesName)
                nextSeasonEpisode?.let { (s, e) ->
                    putExtra("season_num", s)
                    putExtra("episode_num", e)
                }
            }
            finish()
            startActivity(intent)
        }
    }

    private fun showResumeDialog() {
        val minutes = resumePositionMs / 1000 / 60
        val seconds = (resumePositionMs / 1000) % 60
        AlertDialog.Builder(this)
            .setTitle("Resume Playback")
            .setMessage("Resume from ${minutes}:${seconds.toString().padStart(2, '0')}?")
            .setPositiveButton("Resume") { _, _ -> }
            .setNegativeButton("Start Over") { _, _ -> resumePositionMs = 0L }
            .setCancelable(false)
            .show()
    }

    private fun setupActionButtons() {
        binding.btnSleep.setOnClickListener { showSleepTimerDialog() }
        binding.btnTracks.setOnClickListener { showTrackSelectorDialog() }
        binding.btnStats.setOnClickListener {
            statsVisible = !statsVisible
            if (statsVisible) {
                binding.tvStats.visibility = View.VISIBLE
                updateStats()
                statsHandler.postDelayed(statsRunnable, 1000)
            } else {
                binding.tvStats.visibility = View.GONE
                statsHandler.removeCallbacks(statsRunnable)
            }
            resetHideTimer()
        }
        binding.btnRecordDot.setOnClickListener { showRecordDialog() }
    }

    private var recordBlinkAnimator: android.animation.ObjectAnimator? = null

    /** Blinks the small record dot while this channel has an in-progress recording, and
     * dims it to a static low-alpha "idle button" look otherwise — same dot, no separate
     * always-red icon that would make every channel look like it's recording. */
    private fun observeRecordingState() {
        if (isVod || (streamId == -1 && serverIndex == -1)) return
        val effectiveStreamId = if (serverIndex == -1) streamId else mergedStreamId
        lifecycleScope.launch {
            repository.observeActiveRecording(serverIndex, effectiveStreamId).collect { recording ->
                if (recording != null) startRecordDotBlink() else stopRecordDotBlink()
            }
        }
    }

    private fun startRecordDotBlink() {
        if (recordBlinkAnimator != null) return
        binding.viewRecordDot.alpha = 1f
        recordBlinkAnimator = android.animation.ObjectAnimator.ofFloat(binding.viewRecordDot, "alpha", 1f, 0.15f).apply {
            duration = 600
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopRecordDotBlink() {
        recordBlinkAnimator?.cancel()
        recordBlinkAnimator = null
        binding.viewRecordDot.alpha = 0.4f
    }

    private fun showRecordDialog() {
        if (isVod || (streamId == -1 && serverIndex == -1)) return
        val options = arrayOf("30 minutes", "1 hour", "2 hours", "4 hours", "Custom...")
        val durationsMs = longArrayOf(30 * 60_000L, 60 * 60_000L, 120 * 60_000L, 240 * 60_000L)
        android.app.AlertDialog.Builder(this)
            .setTitle("Record \"$streamTitle\"")
            .setItems(options) { _, which ->
                if (which == options.lastIndex) {
                    showCustomRecordDurationDialog()
                } else {
                    startRecordingWithDuration(durationsMs[which], options[which])
                }
            }
            .show()
        resetHideTimer()
    }

    private fun showCustomRecordDurationDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Minutes"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("60")
            setPadding(48, 32, 48, 32)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Duration (minutes)")
            .setView(input)
            .setPositiveButton("Record") { _, _ ->
                val mins = input.text.toString().toLongOrNull()?.coerceAtLeast(1L) ?: 60L
                startRecordingWithDuration(mins * 60_000L, "$mins min")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRecordingWithDuration(durationMs: Long, label: String) {
        val targetClass = if (isLargeScreenDevice())
            com.iptvapp.ui.recordings.TvRecordingActivity::class.java
        else
            com.iptvapp.ui.recordings.RecordingSchedulerActivity::class.java
        startActivity(Intent(this, targetClass).apply {
            // "prefill_*" extra names are identical across both recording Activities'
            // companion objects (kept in sync deliberately), so one set of putExtra
            // calls works for either target.
            putExtra("prefill_stream_id", streamId)
            putExtra("prefill_start_ms", System.currentTimeMillis())
            putExtra("prefill_duration_ms", durationMs)
            putExtra("prefill_server_index", serverIndex)
            putExtra("prefill_merged_stream_id", mergedStreamId)
        })
        Toast.makeText(this, "Recording started: $label", Toast.LENGTH_SHORT).show()
    }

    private fun updateStats() {
        val p = player ?: return
        val vf = p.videoFormat
        val af = p.audioFormat
        val res = if (vf != null) "${vf.width}×${vf.height}" else "—"
        val fps = if (vf?.frameRate != null && vf.frameRate > 0) "${"%.1f".format(vf.frameRate)} fps" else ""
        val vCodec = vf?.sampleMimeType?.removePrefix("video/")?.uppercase() ?: "—"
        val aCodec = af?.sampleMimeType?.removePrefix("audio/")?.uppercase() ?: "—"
        val bitrate = when {
            vf != null && vf.bitrate > 0 -> "${"%.1f".format(vf.bitrate / 1_000_000f)} Mbps"
            else -> "—"
        }
        val bufMs = p.totalBufferedDuration
        val bufSec = bufMs / 1000
        val bufPct = p.bufferedPercentage
        binding.tvStats.text = buildString {
            appendLine("RES   $res  $fps")
            appendLine("VIDEO $vCodec")
            appendLine("AUDIO $aCodec")
            appendLine("BIT   $bitrate")
            append("BUF   ${bufSec}s  ($bufPct%)")
        }
    }

    private fun updateHealthBadge() {
        val p = player ?: return
        if (!isVod && binding.btnDvrLive.visibility == View.VISIBLE) updateDvrLiveButton()
        val bufPct = p.bufferedPercentage
        val vf = p.videoFormat
        val bitrate = if (vf != null && vf.bitrate > 0)
            "${"%.1f".format(vf.bitrate / 1_000f)}k" else ""
        val dotColor = when {
            p.playbackState == Player.STATE_BUFFERING -> android.graphics.Color.parseColor("#FF8800")
            bufPct >= 50 -> android.graphics.Color.parseColor("#00CC66")
            bufPct >= 20 -> android.graphics.Color.parseColor("#FFCC00")
            else -> android.graphics.Color.parseColor("#FF4444")
        }
        (binding.viewHealthDotPlayer.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(dotColor)
        binding.tvHealthBadge.text = buildString {
            append("$bufPct%")
            if (bitrate.isNotEmpty()) append("  $bitrate")
        }
    }

    private fun startHealthBadge() {
        isHealthBadgeActive = true
        if (isOverlayVisible) binding.bufferHealthBadge.visibility = View.VISIBLE
        updateHealthBadge()
        healthHandler.postDelayed(healthRunnable, 2000)
    }

    private fun stopHealthBadge() {
        isHealthBadgeActive = false
        healthHandler.removeCallbacks(healthRunnable)
        binding.bufferHealthBadge.visibility = View.GONE
    }

    private fun showSleepTimerDialog() {
        val labels = arrayOf("Off", "15 min", "30 min", "60 min", "90 min", "120 min")
        val mins = intArrayOf(0, 15, 30, 60, 90, 120)
        AlertDialog.Builder(this)
            .setTitle("Sleep Timer")
            .setItems(labels) { _, which ->
                sleepTimer?.cancel()
                val chosen = mins[which]
                if (chosen == 0) {
                    binding.btnSleep.text = "⏱"
                    binding.btnSleep.setTextColor(getColor(android.R.color.darker_gray))
                } else {
                    binding.btnSleep.setTextColor(0xFF00AAFF.toInt())
                    sleepTimer = object : CountDownTimer(chosen * 60_000L, 60_000L) {
                        override fun onTick(ms: Long) {
                            binding.btnSleep.text = "⏱${ms / 60_000}m"
                        }
                        override fun onFinish() {
                            player?.pause()
                            binding.btnSleep.text = "⏱"
                            binding.btnSleep.setTextColor(getColor(android.R.color.darker_gray))
                        }
                    }.start()
                    binding.btnSleep.text = "⏱${chosen}m"
                }
                resetHideTimer()
            }
            .show()
    }

    private fun showTrackSelectorDialog() {
        val p = player ?: return
        val tracks = p.currentTracks

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

        if (audioGroups.isNotEmpty()) {
            labels.add("── Audio ──")
            actions.add {}
            for (group in audioGroups) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val lang = fmt.language ?: fmt.label ?: "Track ${labels.size}"
                    val selected = group.isTrackSelected(i)
                    labels.add(if (selected) "✓  $lang" else "    $lang")
                    actions.add {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                    }
                }
            }
        }

        if (textGroups.isNotEmpty()) {
            labels.add("── Subtitles ──")
            actions.add {}
            labels.add("    Off")
            actions.add {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                lifecycleScope.launch { prefs.setSubtitlesEnabled(false) }
            }
            for (group in textGroups) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val lang = fmt.language ?: fmt.label ?: "Sub ${labels.size}"
                    val selected = group.isTrackSelected(i)
                    labels.add(if (selected) "✓  $lang" else "    $lang")
                    actions.add {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                        lifecycleScope.launch { prefs.setSubtitlesEnabled(true) }
                    }
                }
            }
        }

        if (labels.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Tracks").setMessage("No selectable tracks available.").setPositiveButton("OK", null).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Audio & Subtitles")
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    // Accelerating VOD skip: 10s per tap normally, 30s once you've tapped more than 10 times
    // in a row without pausing — mirrors how most players speed up scrubbing on repeated presses.
    private var vodSkipPressCount = 0
    private var lastVodSkipTimeMs = 0L

    private fun nextVodSkipAmountMs(): Long {
        val now = System.currentTimeMillis()
        if (now - lastVodSkipTimeMs > 1500L) vodSkipPressCount = 0
        vodSkipPressCount++
        lastVodSkipTimeMs = now
        return if (vodSkipPressCount > 10) 30_000L else 10_000L
    }

    private fun setupChannelZones() {
        binding.zonePrevious.setOnClickListener {
            if (binding.guideContainer.visibility == View.VISIBLE) return@setOnClickListener
            if (isVod) {
                val pos = (player?.currentPosition ?: 0L) - nextVodSkipAmountMs()
                player?.seekTo(pos.coerceAtLeast(0L))
                updateSeekBar()
                showOverlay()
            } else {
                previousChannel()
                showOverlay()
            }
        }
        binding.zoneNext.setOnClickListener {
            if (binding.guideContainer.visibility == View.VISIBLE) return@setOnClickListener
            if (isVod) {
                val pos = (player?.currentPosition ?: 0L) + nextVodSkipAmountMs()
                val duration = player?.duration ?: Long.MAX_VALUE
                player?.seekTo(pos.coerceAtMost(duration))
                updateSeekBar()
                showOverlay()
            } else {
                nextChannel()
                showOverlay()
            }
        }
    }

    private fun setupResizeButton() {
        binding.btnResize.setOnClickListener { cycleResizeMode() }
    }

    private fun cycleResizeMode() {
        resizeModeIndex = (resizeModeIndex + 1) % resizeSteps.size
        applyResizeStep()
        Toast.makeText(this, resizeSteps[resizeModeIndex].label, Toast.LENGTH_SHORT).show()
        resetHideTimer()
    }

    private fun applyResizeStep() {
        val step = resizeSteps[resizeModeIndex]
        binding.playerView.resizeMode = step.mode
        binding.playerView.scaleX = step.scale
        binding.playerView.scaleY = step.scale
        binding.playerView.requestLayout()
    }

    // ─── Brightness & Volume gesture helpers ────────────────────────────────

    private fun adjustBrightness(delta: Float) {
        val lp = window.attributes
        val current = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
        val newBrightness = (current + delta).coerceIn(0.01f, 1f)
        val hitEdge = (newBrightness <= 0.01f || newBrightness >= 1f) && newBrightness != current
        lp.screenBrightness = newBrightness
        window.attributes = lp
        val pct = (newBrightness * 100).toInt()
        binding.brightnessBar.progress = pct
        binding.tvBrightnessPercent.text = "$pct%"
        binding.tvBrightnessIcon.text = if (pct < 40) "🔅" else "☀"
        showIndicator(binding.brightnessIndicator, hideBrightnessRunnable)
        if (hitEdge) hapticTick()
    }

    private fun adjustVolume(delta: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val newVol = (current + (delta * max).toInt()).coerceIn(0, max)
        val hitEdge = (newVol == 0 || newVol == max) && newVol != current
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        val pct = if (max > 0) (newVol * 100 / max) else 0
        binding.volumeBar.progress = pct
        binding.tvVolumePercent.text = "$pct%"
        binding.tvVolumeIcon.text = when {
            newVol == 0 -> "🔇"
            pct < 50 -> "🔉"
            else -> "🔊"
        }
        showIndicator(binding.volumeIndicator, hideVolumeRunnable)
        if (hitEdge) hapticTick()
    }

    private fun hapticTick() {
        binding.root.performHapticFeedback(
            android.view.HapticFeedbackConstants.CLOCK_TICK,
            android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    private fun showIndicator(view: View, hideRunnable: Runnable) {
        view.visibility = View.VISIBLE
        indicatorHandler.removeCallbacks(hideRunnable)
        indicatorHandler.postDelayed(hideRunnable, 1200)
    }

    // ─── Gesture detector ───────────────────────────────────────────────────

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                isAdjustingGesture = false
                gestureAccumY = 0f
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val w = binding.root.width.toFloat()
                val h = binding.root.height.toFloat()
                val startX = e1?.x ?: return false
                if (abs(distanceY) < abs(distanceX)) return false  // horizontal scroll — ignore
                val sensitivity = 1.5f / h

                // Small dead zone before the first engagement so a slightly wobbly tap
                // (meant to toggle the controls) doesn't nudge brightness/volume by accident.
                if (!isAdjustingGesture) {
                    gestureAccumY += distanceY
                    val deadZonePx = resources.displayMetrics.density * 10f
                    if (abs(gestureAccumY) < deadZonePx) return false
                }

                return when {
                    startX < w * 0.35f -> {
                        isAdjustingGesture = true
                        adjustBrightness(-distanceY * sensitivity)
                        true
                    }
                    startX > w * 0.65f -> {
                        isAdjustingGesture = true
                        adjustVolume(-distanceY * sensitivity)
                        true
                    }
                    else -> false
                }
            }

        })

        binding.root.setOnTouchListener { _, event ->
            val handled = gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                val wasAdjusting = isAdjustingGesture
                isAdjustingGesture = false
                if (wasAdjusting) return@setOnTouchListener true
            }
            handled && isAdjustingGesture
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ─── Player ─────────────────────────────────────────────────────────────

    private fun buildPlayer(): ExoPlayer {
        // Global (applies to every server, not per-provider) — trades a slower initial
        // load/seek for fewer mid-playback stalls, since some IPTV providers are slow or
        // inconsistent enough that the default buffer runs dry mid-stream.
        val extraBufferingEnabled = kotlinx.coroutines.runBlocking { prefs.extraBufferingEnabled.first() }
        val loadControl = DefaultLoadControl.Builder()
            .apply {
                if (extraBufferingEnabled) setBufferDurationsMs(90_000, 240_000, 10_000, 15_000)
                else setBufferDurationsMs(50_000, 120_000, 5_000, 10_000)
            }
            .setPrioritizeTimeOverSizeThresholds(true)
            // Retain already-played media so live channels can rewind without a network
            // re-fetch for the last couple of minutes; older content still seeks fine via
            // the on-disk timeshift cache below.
            .setBackBuffer(120_000, true)
            .build()

        // Without an explicit User-Agent, OkHttpDataSource sends OkHttp's own default
        // ("okhttp/4.x"). The mini player uses ExoPlayer's own built-in HTTP stack instead
        // (no custom DataSource.Factory), which sends ExoPlayer's default User-Agent — some
        // Cloudflare-fronted IPTV CDNs allow that but block/reject an unrecognized one,
        // which looked exactly like "plays fine in the mini player, black screen + endless
        // reconnect loop in fullscreen" since that's the only real pipeline difference
        // between the two players for live channels.
        val upstreamDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("MKTV/${com.iptvapp.BuildConfig.VERSION_NAME} (Linux;Android ${Build.VERSION.RELEASE}) ExoPlayerLib/1.4.1")
        // Live TV used to route through an on-disk cache (ManifestBypassCacheDataSource) to
        // power a DVR/rewind buffer beyond ExoPlayer's in-memory back buffer. Confirmed by
        // A/B testing on the Shield and phone: some providers (shop4uu at least) reuse the
        // same live segment URL while overwriting its content server-side — the cache assumed
        // URLs are immutable (standard HTTP caching semantics) and kept serving stale cached
        // bytes instead of refetching, which manifested as a fullscreen-only reconnect loop
        // (PlaylistStuckException) that never happened in the mini player, which has no cache
        // at all. Rewinding on live TV is now limited to the in-memory back buffer configured
        // above (setBackBuffer, ~2 minutes) rather than a disk-backed multi-minute window —
        // a real feature reduction, but a stuck live stream is worse than a shorter rewind.
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(upstreamDataSourceFactory)

        val tunnelingEnabled = kotlinx.coroutines.runBlocking { prefs.tunneledPlaybackEnabled.first() }
        val dv7FallbackEnabled = kotlinx.coroutines.runBlocking { prefs.dv7FallbackEnabled.first() }

        // DV7 fallback: some devices lack proper Dolby Vision Profile 7 (dual-layer) decode
        // support and either fail or black-screen. When enabled, redirect DV7 content to a
        // standard HEVC decoder instead — DV7's base layer is valid HEVC on its own.
        val codecSelector = if (dv7FallbackEnabled) {
            androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                if (mimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION) {
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(
                        androidx.media3.common.MimeTypes.VIDEO_H265, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                } else {
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                }
            }
        } else androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setMediaCodecSelector(codecSelector)

        val subtitlesEnabled = kotlinx.coroutines.runBlocking { prefs.subtitlesEnabled.first() }
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .apply { if (tunnelingEnabled) setTunnelingEnabled(true) }
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !subtitlesEnabled)
                .setSelectUndeterminedTextLanguage(subtitlesEnabled)
                .build()
        }

        return ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                applyResizeStep()
                binding.playerView.useController = false
                applySubtitleStyle()

                binding.playerView.setOnClickListener {
                    if (binding.epgOverlay.visibility == View.VISIBLE) {
                        hideHandler.removeCallbacks(hideRunnable)
                        hideRunnable.run()
                    } else {
                        showOverlay()
                    }
                }

                binding.btnPlayPause.setOnClickListener {
                    val wasPlaying = exoPlayer.isPlaying
                    if (wasPlaying) exoPlayer.pause() else exoPlayer.play()
                    updatePlayPauseButton()
                    resetHideTimer()
                    if (wasPlaying) traktScrobble(::scrobblePauseCall)
                    else if (traktScrobbleStarted) traktScrobble(::scrobbleStartCall)
                }

                binding.btnDvrRewind.setOnClickListener {
                    if (!isVod) {
                        exoPlayer.seekTo((exoPlayer.currentPosition - 60_000L).coerceAtLeast(0L))
                        updateDvrLiveButton()
                        resetHideTimer()
                    }
                }

                binding.btnDvrLive.setOnClickListener {
                    if (!isVod) {
                        // seekToDefaultPosition() relies on ExoPlayer's live-window detection,
                        // which some providers' HLS playlists don't signal correctly — it was
                        // landing at the start of the buffered window instead of the live edge.
                        // Seeking straight to the timeline's current duration is a more reliable
                        // way to reach "now" regardless of whether live metadata is present.
                        val dur = exoPlayer.duration
                        if (dur != androidx.media3.common.C.TIME_UNSET && dur > 0) {
                            exoPlayer.seekTo(dur)
                        } else {
                            exoPlayer.seekToDefaultPosition()
                        }
                        exoPlayer.play()
                        updateDvrLiveButton()
                        resetHideTimer()
                    }
                }

                exoPlayer.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Keeps the PiP window's Play/Pause action icon in sync even when
                        // playback state changes for a reason other than tapping that action
                        // itself (buffering pausing/resuming playback, etc).
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                            setPictureInPictureParams(buildPipParams())
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> {
                                retryCount = 0
                                binding.progressBuffering.visibility = View.GONE
                                binding.tvRetryStatus.visibility = View.GONE
                                if (isVod) startSeekBarUpdater()
                                startHealthBadge()
                                // D-pad channel-change deliberately suppresses this so repeated
                                // up/down keeps flipping channels instead of the first change
                                // popping the overlay open and eating the next press.
                                if (suppressOverlayOnReady) suppressOverlayOnReady = false else showOverlay()
                                updatePlayPauseButton()
                                if (isVod && resumePositionMs > 0L) {
                                    exoPlayer.seekTo(resumePositionMs)
                                    resumePositionMs = 0L
                                }
                                if (isVod && !traktScrobbleStarted) {
                                    traktScrobbleStarted = true
                                    traktScrobble(::scrobbleStartCall)
                                }
                            }
                            Player.STATE_BUFFERING -> {
                                binding.progressBuffering.visibility = View.VISIBLE
                                // Neither onPlayerError nor STATE_ENDED necessarily fires if a
                                // stream just stalls indefinitely — that leaves a black screen
                                // with zero trace in the debug report. Log once if buffering
                                // hasn't cleared after 20s so a stall is at least visible.
                                bufferWatchdog?.let { hideHandler.removeCallbacks(it) }
                                bufferWatchdog = Runnable {
                                    if (player?.playbackState == Player.STATE_BUFFERING) {
                                        com.iptvapp.IptvApplication.logPlaybackEvent(
                                            applicationContext,
                                            "BUFFERING STALL: isVod=$isVod streamId=$streamId url=$streamUrl " +
                                                "stuck 20s+ with no error/ended event — forcing reconnect"
                                        )
                                        // Previously only logged/counted the stall — the player
                                        // itself was never told to do anything, so with no
                                        // onPlayerError/STATE_ENDED firing the spinner just spun
                                        // forever. scheduleRetry() already has the VOD/live-aware
                                        // backoff+give-up logic used for real errors; reuse it here.
                                        scheduleRetry()
                                    }
                                }
                                hideHandler.postDelayed(bufferWatchdog!!, 20_000L)
                            }
                            Player.STATE_ENDED -> {
                                binding.progressBuffering.visibility = View.GONE
                                if (!isVod) scheduleRetry()
                                else showUpNextIfAvailable()
                                if (isVod) traktScrobble(::scrobbleStopCall, progressOverride = 100f)
                            }
                            else -> binding.progressBuffering.visibility = View.GONE
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        binding.progressBuffering.visibility = View.GONE
                        com.iptvapp.IptvApplication.logPlaybackEvent(
                            applicationContext,
                            "PLAYER ERROR: isVod=$isVod streamId=$streamId errorCode=${error.errorCodeName} " +
                                "cause=${error.cause?.javaClass?.simpleName} message=${error.message} url=$streamUrl"
                        )
                        // scheduleRetry() already has full VOD-aware backoff/give-up logic
                        // (see below) — this used to dead-end VOD here instead of using it,
                        // so a transient network blip on a movie meant manually backing out
                        // and reopening it instead of recovering on its own like live TV does.
                        scheduleRetry(looksLikeConnectionLimitRejection(error))
                    }
                })
            }
    }

    private fun applySubtitleStyle() {
        lifecycleScope.launch {
            val s = prefs.subtitleStyle.first()
            val subtitleView = binding.playerView.subtitleView ?: return@launch
            subtitleView.setStyle(
                androidx.media3.ui.CaptionStyleCompat(
                    s.textColor,
                    s.backgroundColor,
                    android.graphics.Color.TRANSPARENT,
                    if (s.outlineEnabled) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
                    s.outlineColor,
                    if (s.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                )
            )
            subtitleView.setFractionalTextSize(
                androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.sizeScale
            )
            subtitleView.translationY = -s.verticalOffsetDp * resources.displayMetrics.density
        }
    }

    private fun scrobbleProgress(): Float {
        val p = player ?: return 0f
        val duration = p.duration.takeIf { it > 0 } ?: return 0f
        return (p.currentPosition.toFloat() / duration.toFloat() * 100f).coerceIn(0f, 100f)
    }

    private suspend fun scrobbleStartCall(progress: Float) {
        if (traktSeason >= 0 && traktEpisode >= 0 && traktSeriesName.isNotBlank()) {
            traktManager.scrobbleEpisodeStart(traktSeriesName, traktSeason, traktEpisode, progress)
        } else {
            val parsed = traktManager.parseTitle(streamTitle)
            traktManager.scrobbleMovieStart(parsed.title, parsed.year, progress)
        }
    }

    private suspend fun scrobblePauseCall(progress: Float) {
        if (traktSeason >= 0 && traktEpisode >= 0 && traktSeriesName.isNotBlank()) {
            traktManager.scrobbleEpisodePause(traktSeriesName, traktSeason, traktEpisode, progress)
        } else {
            val parsed = traktManager.parseTitle(streamTitle)
            traktManager.scrobbleMoviePause(parsed.title, parsed.year, progress)
        }
    }

    private suspend fun scrobbleStopCall(progress: Float) {
        if (traktSeason >= 0 && traktEpisode >= 0 && traktSeriesName.isNotBlank()) {
            traktManager.scrobbleEpisodeStop(traktSeriesName, traktSeason, traktEpisode, progress)
        } else {
            val parsed = traktManager.parseTitle(streamTitle)
            traktManager.scrobbleMovieStop(parsed.title, parsed.year, progress)
        }
    }

    private fun traktScrobble(call: suspend (Float) -> Unit, progressOverride: Float? = null) {
        if (!isVod) return
        val progress = progressOverride ?: scrobbleProgress()
        // Uses a process-wide scope, not lifecycleScope — finish()'s stop-scrobble call must
        // survive the activity being destroyed right after this is fired.
        traktIoScope.launch {
            try { call(progress) } catch (_: Exception) { /* best-effort — never blocks playback */ }
        }
    }

    private fun updatePlayPauseButton() {
        val isPlaying = player?.isPlaying ?: false
        binding.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    /** Dims the "● LIVE" button while behind the live edge, highlights it once caught up. */
    private fun updateDvrLiveButton() {
        if (isVod) return
        val p = player ?: return
        val offsetMs = p.currentLiveOffset
        val atLiveEdge = offsetMs == androidx.media3.common.C.TIME_UNSET || offsetMs < 5_000L
        binding.btnDvrLive.setTextColor(
            if (atLiveEdge) 0xFF555555.toInt() else 0xFFFF3B30.toInt()
        )
    }

    // Global (not per-server) — if this network/provider is stalling repeatedly in the same
    // viewing session, turning on the bigger buffer profile automatically saves a trip to
    // Settings after the fact. Only escalates once per session and only if it isn't already
    // on; the new profile applies starting with the next player rebuild (retry/channel
    // change), not instantly, since DefaultLoadControl is fixed at ExoPlayer construction.
    private val stallTimestamps = mutableListOf<Long>()
    private var autoEnabledExtraBuffering = false

    private fun noteStallEvent() {
        if (autoEnabledExtraBuffering) return
        val now = System.currentTimeMillis()
        stallTimestamps.add(now)
        stallTimestamps.removeAll { now - it > 120_000L }
        if (stallTimestamps.size < 3) return
        autoEnabledExtraBuffering = true
        lifecycleScope.launch {
            if (!prefs.extraBufferingEnabled.first()) {
                prefs.setExtraBufferingEnabled(true)
                com.iptvapp.IptvApplication.logPlaybackEvent(
                    applicationContext,
                    "AUTO-ENABLED extra buffering after ${stallTimestamps.size} stalls in 2min: streamId=$streamId url=$streamUrl"
                )
                Toast.makeText(
                    applicationContext,
                    "Repeated stalls detected — enabled Extra Buffering for future streams",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Media3's HttpDataSource surfaces a rejected/kicked connection as a plain bad-HTTP-status
     * (commonly 403, sometimes 429) with no distinguishing text of its own — indistinguishable
     * from a dead link at the error-code level. Most Xtream plans allow only one simultaneous
     * stream, so "some other channel/recording is already using the account's one connection"
     * is the single most common real-world cause of this specific error shape, and it's worth
     * checking for before assuming the stream itself is just broken. */
    private fun looksLikeConnectionLimitRejection(error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return false
        val cause = error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException ?: return false
        return cause.responseCode == 403 || cause.responseCode == 429
    }

    private var vodFormatFallbackTried = false

    /** Movies/episodes are served under their catalog-reported container extension
     * (mp4/mkv/avi/etc.), but that metadata is provider-supplied and occasionally wrong or
     * stale for a given title — while most Xtream panels also serve every VOD item over the
     * same .m3u8 HLS wrapper regardless of the "real" container, as a fallback path. If the
     * originally-requested extension is exhausted and never worked, trying .m3u8 once before
     * giving up entirely can recover a movie that would otherwise just be reported dead. */
    private fun vodFormatFallbackUrl(): String? {
        if (vodFormatFallbackTried) return null
        if (streamUrl.substringAfterLast('.', "").equals("m3u8", ignoreCase = true)) return null
        val withoutExt = streamUrl.substringBeforeLast('.')
        if (withoutExt == streamUrl) return null
        return "$withoutExt.m3u8"
    }

    private fun scheduleRetry(suspectConnectionLimit: Boolean = false) {
        noteStallEvent()
        if (isVod && retryCount >= maxRetries) {
            val fallbackUrl = vodFormatFallbackUrl()
            if (fallbackUrl != null) {
                vodFormatFallbackTried = true
                streamUrl = fallbackUrl
                retryCount = 0
                com.iptvapp.IptvApplication.logPlaybackEvent(
                    applicationContext,
                    "RETRY FORMAT FALLBACK: streamId=$streamId trying $streamUrl after $maxRetries failed attempts on the original extension"
                )
                binding.tvRetryStatus.text = "Trying an alternate stream format…"
                binding.tvRetryStatus.visibility = View.VISIBLE
                retryJob?.cancel()
                retryJob = lifecycleScope.launch {
                    delay(1000L)
                    player?.let {
                        it.setMediaItem(MediaItem.fromUri(streamUrl), it.currentPosition.takeIf { pos -> pos > 0L } ?: 0L)
                        it.prepare()
                        it.playWhenReady = true
                    }
                }
                return
            }
            binding.tvRetryStatus.text = "Stream unavailable after $maxRetries attempts"
            binding.tvRetryStatus.visibility = View.VISIBLE
            com.iptvapp.IptvApplication.logPlaybackEvent(
                applicationContext,
                "RETRY GIVE UP: isVod=$isVod streamId=$streamId url=$streamUrl attempts=$retryCount"
            )
            return
        }
        retryJob?.cancel()
        // Captured now, before the delay below — otherwise a paused/stalled player's
        // currentPosition could drift or reset by the time the retry actually reloads.
        val resumeAt = if (isVod) (player?.currentPosition ?: 0L) else 0L
        retryJob = lifecycleScope.launch {
            val backoffMs = if (isVod) {
                (2000L * (retryCount + 1)).coerceAtMost(16000L)
            } else {
                // Live: ramp up to 30s then hold there
                (2000L * (retryCount + 1)).coerceAtMost(30000L)
            }
            val attempt = retryCount + 1
            val delaySec = backoffMs / 1000
            val suffix = if (isVod) " (attempt $attempt of $maxRetries)" else ""
            // Checked on every retry (not just once) since the blocking recording could
            // start or finish while this stream keeps failing — the message should track
            // reality, not freeze on whatever was true the first time.
            val activeRecording = if (suspectConnectionLimit) {
                try { repository.getAnyActiveRecording() } catch (_: Exception) { null }
            } else null
            binding.tvRetryStatus.text = if (activeRecording != null) {
                "⏺ Can't connect — \"${activeRecording.channelName}\" is recording and your provider allows only one stream at a time"
            } else {
                "● Reconnecting in ${delaySec}s$suffix…"
            }
            binding.tvRetryStatus.visibility = View.VISIBLE
            com.iptvapp.IptvApplication.logPlaybackEvent(
                applicationContext,
                "RETRY SCHEDULED: isVod=$isVod streamId=$streamId url=$streamUrl attempt=$attempt delayMs=$backoffMs " +
                    "playerState=${player?.playbackState} suspectConnectionLimit=$suspectConnectionLimit activeRecording=${activeRecording?.channelName}"
            )
            delay(backoffMs)
            retryCount++
            player?.let {
                if (isVod && resumeAt > 0L) it.setMediaItem(MediaItem.fromUri(streamUrl), resumeAt)
                else it.setMediaItem(MediaItem.fromUri(streamUrl))
                it.prepare()
                it.playWhenReady = true
            }
        }
    }

    private fun setupCast() {
        try {
            castContext = CastContext.getSharedInstance(this)
            val selector = MediaRouteSelector.Builder()
                .addControlCategory(
                    CastMediaControlIntent.categoryForCast(
                        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                    )
                )
                .build()
            binding.btnCast.routeSelector = selector
            binding.btnCast.visibility = View.GONE  // shown only when overlay is visible
            castAvailable = true
            // Listener is managed in onResume/onPause — don't add it here too
            castSession = castContext?.sessionManager?.currentCastSession
            castSession?.let { stopLocalAndCast(it) }
        } catch (e: Exception) {
            castAvailable = false
            binding.btnCast.visibility = View.GONE
        }
    }

    private fun stopLocalAndCast(session: CastSession) {
        if (streamUrl.isBlank()) return
        val localPositionMs = if (isVod) (player?.currentPosition?.takeIf { it > 0 } ?: resumePositionMs) else 0L
        player?.stop()
        player?.clearMediaItems()
        binding.bufferHealthBadge.visibility = View.GONE
        lifecycleScope.launch {
            delay(1500)
            // streamId == -1 means this playback has no DB-backed identity (e.g. the external-
            // player-fallback path, or a merged/secondary-provider channel) — repository lookups
            // by streamId would resolve against the wrong (primary) server's credentials in that
            // case, so fall back to the already-resolved streamUrl instead.
            val directUrl = if (!isVod && streamId != -1) repository.getLiveStreamUrlForCast(streamId) else streamUrl

            // Start local CORS proxy — Chromecast Default Media Receiver runs in a browser
            // context and enforces CORS; most IPTV servers don't send CORS headers.
            castProxy?.stop()
            val localIp = getLocalIpAddress()
            // A raw .ts live channel (no HLS manifest at all — common on IPTV panels) can't be
            // handed to the receiver as-is: its video pipeline expects segmented media (HLS/
            // DASH/progressive MP4), not an infinite raw transport-stream socket — it connects
            // then silently disconnects a few seconds in with nothing ever rendered. Repackage
            // it into a live HLS presentation instead (see IptvCastProxy.proxyLiveUrl/
            // LiveHlsSession) so the receiver gets a normal-looking live stream. VOD is
            // unaffected — a VOD .ts/.mp4 file has a real, finite length the receiver can seek
            // within, so it isn't subject to the same "infinite socket" problem.
            val isRawTsLive = !isVod && directUrl.contains(".ts", ignoreCase = true) && !directUrl.contains(".m3u8", ignoreCase = true)
            val castUrl = if (localIp != null) {
                val proxy = com.iptvapp.cast.IptvCastProxy(localIp).also {
                    it.start()
                    castProxy = it
                }
                if (isRawTsLive) {
                    proxy.proxyLiveUrl(directUrl, "ExoPlayerLib/1.4.1 (Linux; Android)")
                } else {
                    proxy.proxyUrl(directUrl)
                }
            } else {
                directUrl
            }

            Log.d("CastDebug", "localIp=$localIp castUrl=$castUrl")

            val contentType = when {
                castUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
                castUrl.contains(".mpd",  ignoreCase = true) -> "application/dash+xml"
                castUrl.contains(".mp4",  ignoreCase = true) -> "video/mp4"
                else -> "application/x-mpegURL"
            }
            // Live channels use STREAM_TYPE_LIVE — BUFFERED waits for #EXT-X-ENDLIST which
            // never comes on a live stream, causing indefinite LOADING state.
            val streamType = if (isVod) MediaInfo.STREAM_TYPE_BUFFERED else MediaInfo.STREAM_TYPE_LIVE

            // Fetch current EPG program to show as subtitle on Chromecast screen
            var nowProgramTitle: String? = null
            if (!isVod && streamId != -1) {
                try {
                    repository.fetchEpg(streamId)
                    val epg = repository.getEpgForStream(streamId).first()
                    val nowMs = System.currentTimeMillis()
                    fun startMs(e: com.iptvapp.data.local.entities.EpgEntity) = if (e.startTimestamp < 100_000_000_000L) e.startTimestamp * 1000L else e.startTimestamp
                    fun stopMs(e: com.iptvapp.data.local.entities.EpgEntity) = if (e.stopTimestamp < 100_000_000_000L) e.stopTimestamp * 1000L else e.stopTimestamp
                    nowProgramTitle = epg.firstOrNull { startMs(it) <= nowMs && stopMs(it) > nowMs }?.title
                } catch (_: Exception) {}
            } else if (!isVod && serverIndex != -1) {
                try {
                    nowProgramTitle = repository.fetchMergedEpgNowNext(serverIndex, mergedStreamId)?.nowTitle
                } catch (_: Exception) {}
            }

            val metadata = MediaMetadata(if (isVod) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_TV_SHOW).apply {
                putString(MediaMetadata.KEY_TITLE, streamTitle)
                nowProgramTitle?.takeIf { it.isNotBlank() }?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            }
            // contentId must be the URL (not the title) — some receiver versions use it as the fallback src
            val mediaInfo = MediaInfo.Builder(castUrl)
                .setContentUrl(castUrl)
                .setStreamType(streamType)
                .setContentType(contentType)
                .setMetadata(metadata)
                .build()
            val loadRequest = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .apply { if (isVod && localPositionMs > 0) setCurrentTime(localPositionMs) }
                .build()

            // The Cast Default Media Receiver only polls a live manifest a handful of times
            // in the first several seconds right after load() and gives up permanently if it
            // never saw real segments in that window — calling load() immediately (as this
            // used to) raced the LiveHlsSession's own repackaging pipeline and consistently
            // lost, since the CDN's initial burst delivery means the first few genuinely
            // representative segments take a few seconds to materialize. Block here instead
            // until they exist so load() only ever happens once the receiver's very first
            // manifest fetch can already succeed with a normal-looking stream.
            if (isRawTsLive) {
                // This only sets the ceiling — the wait loop returns as soon as
                // hasEnoughGoodSegments() is true, well before the timeout in the normal case.
                // 3 good segments at ~2s each plus connection/probe overhead is comfortably
                // under this; kept generous rather than tight since timing out early just means
                // load() races the receiver's window again (the original bug), not a crash.
                withContext(Dispatchers.IO) {
                    castProxy?.awaitLiveSessionReady(castUrl, timeoutMs = 18_000)
                }
            }

            val client = session.remoteMediaClient ?: run {
                Log.e("CastDebug", "remoteMediaClient is null")
                return@launch
            }
            client.load(loadRequest).setResultCallback { result ->
                Log.d("CastDebug", "load result: success=${result.status.isSuccess} code=${result.status.statusCode}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        castContext?.sessionManager?.addSessionManagerListener(castSessionListener, CastSession::class.java)
        // If the session started while we were paused (cast picker caused onPause),
        // onSessionStarted already fired with no listener — catch it here
        val activeSession = castContext?.sessionManager?.currentCastSession
        if (activeSession != null && castSession == null) {
            Log.d("CastDebug", "Caught missed session start in onResume")
            castSession = activeSession
            stopLocalAndCast(activeSession)
        }
    }

    override fun onPause() {
        super.onPause()
        castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
    }

    private fun loadStream(url: String) {
        retryCount = 0
        vodFormatFallbackTried = false
        retryJob?.cancel()
        streamUrl = url
        val activeSession = castSession
        if (activeSession != null) {
            stopLocalAndCast(activeSession)
        } else {
            player?.let {
                it.setMediaItem(MediaItem.fromUri(url))
                it.prepare()
                it.playWhenReady = true
            }
        }
    }

    private fun saveVodProgress() {
        if (!isVod) return
        val watched = player?.currentPosition ?: return
        val duration = player?.duration ?: return
        if (duration <= 0) return
        // Episodes use episode.id.hashCode() as streamId, which isn't a real vod_streams row —
        // route episode progress into episode_watched instead, keyed by seriesId/season/episode.
        if (episodeSeriesId != -1 && traktSeason >= 0 && traktEpisode >= 0) {
            lifecycleScope.launch {
                repository.saveEpisodeProgress(episodeSeriesId, traktSeason, traktEpisode, watched, duration)
            }
        } else if (serverIndex != -1 && mergedStreamId != -1) {
            // Merged-provider VOD plays with streamId = -1 (no primary vod_streams row) —
            // resolve progress by (serverIndex, mergedStreamId) instead, same composite-key
            // convention as every other merged-provider table.
            lifecycleScope.launch { repository.saveMergedVodProgress(serverIndex, mergedStreamId, watched, duration) }
        } else {
            if (streamId < 0) return
            lifecycleScope.launch { repository.saveVodProgress(streamId, watched, duration) }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    private fun updateSeekBar() {
        if (!isVod) return
        val duration = player?.duration ?: return
        if (duration <= 0) return
        val position = player?.currentPosition ?: 0L
        binding.seekBar.max = duration.toInt()
        binding.seekBar.progress = position.toInt()
        binding.tvTimeElapsed.text = formatDuration(position)
        binding.tvTimeRemaining.text = "-" + formatDuration((duration - position).coerceAtLeast(0L))
    }

    private fun startSeekBarUpdater() {
        if (!isVod) return
        binding.vodSeekContainer.visibility = View.VISIBLE
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) player?.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) { hideHandler.removeCallbacks(hideRunnable) }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) { resetHideTimer() }
        })
        seekRunnable = object : Runnable {
            override fun run() { updateSeekBar(); seekHandler.postDelayed(this, 1000) }
        }
        seekHandler.post(seekRunnable!!)
    }

    private fun showOverlay() {
        isOverlayVisible = true
        binding.tvChannelTitle.text = streamTitle
        binding.epgOverlay.visibility = View.VISIBLE
        binding.btnBack.visibility = View.VISIBLE
        binding.btnGuide.visibility = View.VISIBLE
        binding.btnPlayPause.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.VISIBLE
        if (!isVod) {
            binding.btnDvrRewind.visibility = View.VISIBLE
            binding.btnDvrLive.visibility = View.VISIBLE
            updateDvrLiveButton()
            if (streamId != -1 || serverIndex != -1) binding.btnRecordDot.visibility = View.VISIBLE
        }
        if (castAvailable) binding.btnCast.visibility = View.VISIBLE
        if (isHealthBadgeActive) binding.bufferHealthBadge.visibility = View.VISIBLE
        updatePlayPauseButton()
        resetHideTimer()
        binding.btnPlayPause.post { binding.btnPlayPause.requestFocus() }
        if (!isVod && streamId != -1) {
            lifecycleScope.launch {
                repository.fetchEpg(streamId)
                val epg = repository.getEpgForStream(streamId).first()
                val nowMs = System.currentTimeMillis()
                fun startMs(e: com.iptvapp.data.local.entities.EpgEntity) = if (e.startTimestamp < 100_000_000_000L) e.startTimestamp * 1000L else e.startTimestamp
                fun stopMs(e: com.iptvapp.data.local.entities.EpgEntity)  = if (e.stopTimestamp  < 100_000_000_000L) e.stopTimestamp  * 1000L else e.stopTimestamp
                val now  = epg.firstOrNull { startMs(it) <= nowMs && stopMs(it) > nowMs }
                val next = epg.firstOrNull { now != null && startMs(it) > stopMs(now) }
                binding.tvEpgNow.text = if (now != null) "NOW: " + now.title else ""
                binding.tvEpgNext.text = if (next != null) "NEXT: " + next.title else ""
            }
        } else if (!isVod && serverIndex != -1) {
            lifecycleScope.launch {
                val nowNext = try { repository.fetchMergedEpgNowNext(serverIndex, mergedStreamId) } catch (_: Exception) { null }
                binding.tvEpgNow.text = if (nowNext != null) "NOW: " + nowNext.nowTitle else ""
                binding.tvEpgNext.text = if (nowNext?.nextTitle != null) "NEXT: " + nowNext.nextTitle else ""
            }
        }
    }

    private fun showChannelOsd() {
        binding.tvOsdChannelName.text = streamTitle
        binding.tvOsdEpg.text = ""
        binding.osdEpgProgress.progress = 0
        binding.channelOsd.visibility = View.VISIBLE
        osdHandler.removeCallbacks(hideOsdRunnable)
        osdHandler.postDelayed(hideOsdRunnable, 2500)
        if (streamId != -1) {
            lifecycleScope.launch {
                val epg = repository.getEpgForStream(streamId).first()
                val nowMs = System.currentTimeMillis()
                fun startMs(e: com.iptvapp.data.local.entities.EpgEntity) = if (e.startTimestamp < 100_000_000_000L) e.startTimestamp * 1000L else e.startTimestamp
                fun stopMs(e: com.iptvapp.data.local.entities.EpgEntity)  = if (e.stopTimestamp  < 100_000_000_000L) e.stopTimestamp  * 1000L else e.stopTimestamp
                val now = epg.firstOrNull { startMs(it) <= nowMs && stopMs(it) > nowMs } ?: return@launch
                binding.tvOsdEpg.text = now.title
                val start = startMs(now); val stop = stopMs(now)
                val progress = if (stop > start) ((nowMs - start) * 100 / (stop - start)).toInt().coerceIn(0, 100) else 0
                binding.osdEpgProgress.progress = progress
            }
        } else if (!isVod && serverIndex != -1) {
            lifecycleScope.launch {
                val nowNext = try { repository.fetchMergedEpgNowNext(serverIndex, mergedStreamId) } catch (_: Exception) { null } ?: return@launch
                binding.tvOsdEpg.text = nowNext.nowTitle
                val nowMs = System.currentTimeMillis()
                val progress = if (nowNext.nowStopMs > nowNext.nowStartMs)
                    ((nowMs - nowNext.nowStartMs) * 100 / (nowNext.nowStopMs - nowNext.nowStartMs)).toInt().coerceIn(0, 100)
                else 0
                binding.osdEpgProgress.progress = progress
            }
        }
    }

    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 5000)
    }

    private fun playChannel(channel: ChannelEntity) {
        channelSwitchJob?.cancel()
        channelSwitchJob = lifecycleScope.launch {
            streamId = channel.streamId
            streamTitle = channel.name
            val url = repository.getLiveStreamUrl(channel.streamId)
            binding.tvChannelTitle.text = streamTitle
            val idx = channels.indexOfFirst { it.streamId == channel.streamId }
            if (idx >= 0) currentIndex = idx
            loadStream(url)
        }
    }

    /** Merged-channel equivalent of playChannel — same shape, but resolves the stream URL
     * against that channel's own server (getMergedLiveStreamUrl) and tracks serverIndex/
     * mergedStreamId instead of streamId, matching every other merged-aware code path in this
     * Activity (saveVodProgress's serverIndex/mergedStreamId branch, MediaInfo cast metadata). */
    private fun playMergedChannel(channel: com.iptvapp.data.local.entities.MergedChannelEntity) {
        channelSwitchJob?.cancel()
        channelSwitchJob = lifecycleScope.launch {
            try {
                serverIndex = channel.serverIndex
                mergedStreamId = channel.streamId
                streamTitle = "${channel.name} · ${channel.serverNickname}"
                val url = repository.getMergedLiveStreamUrl(channel.serverIndex, channel.streamId)
                binding.tvChannelTitle.text = streamTitle
                val idx = mergedChannels.indexOfFirst { it.streamId == channel.streamId }
                if (idx >= 0) mergedCurrentIndex = idx
                loadStream(url)
            } catch (_: Exception) {
                Toast.makeText(this@PlayerActivity, "Couldn't load this channel", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun nextChannel() {
        if (serverIndex != -1) {
            if (mergedChannels.isEmpty() || mergedCurrentIndex < 0) return
            mergedCurrentIndex = (mergedCurrentIndex + 1) % mergedChannels.size
            playMergedChannel(mergedChannels[mergedCurrentIndex])
            return
        }
        if (channels.isEmpty() || currentIndex < 0) return
        currentIndex = (currentIndex + 1) % channels.size
        playChannel(channels[currentIndex])
    }

    private fun previousChannel() {
        if (serverIndex != -1) {
            if (mergedChannels.isEmpty() || mergedCurrentIndex < 0) return
            mergedCurrentIndex = if (mergedCurrentIndex == 0) mergedChannels.lastIndex else mergedCurrentIndex - 1
            playMergedChannel(mergedChannels[mergedCurrentIndex])
            return
        }
        if (channels.isEmpty() || currentIndex < 0) return
        currentIndex = if (currentIndex == 0) channels.lastIndex else currentIndex - 1
        playChannel(channels[currentIndex])
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                val now = System.currentTimeMillis()
                when {
                    isOverlayVisible && now - lastBackPressMs < 500 -> {
                        // Double back while overlay open → exit
                        finish(); true
                    }
                    isOverlayVisible -> {
                        // Single back → dismiss overlay only
                        lastBackPressMs = now
                        hideHandler.removeCallbacks(hideRunnable)
                        hideRunnable.run()
                        true
                    }
                    else -> super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> when {
                // Overlay open → D-pad should move freely between its buttons (Back/Cast/
                // Record/etc.), same as LEFT/RIGHT already do below. Channel-zap only applies
                // when the overlay is hidden and up/down has nothing else to navigate.
                isOverlayVisible -> { resetHideTimer(); super.onKeyDown(keyCode, event) }
                !isVod -> { suppressOverlayOnReady = true; nextChannel(); showChannelOsd(); true }
                else -> { showOverlay(); true }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                isOverlayVisible -> { resetHideTimer(); super.onKeyDown(keyCode, event) }
                !isVod -> { suppressOverlayOnReady = true; previousChannel(); showChannelOsd(); true }
                else -> { showOverlay(); true }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                !isOverlayVisible -> { showOverlay(); true }
                isVod -> { resetHideTimer(); player?.seekTo(((player?.currentPosition ?: 0L) - nextVodSkipAmountMs()).coerceAtLeast(0L)); true }
                else -> { resetHideTimer(); super.onKeyDown(keyCode, event) }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                !isOverlayVisible -> { showOverlay(); true }
                isVod -> { resetHideTimer(); player?.seekTo(((player?.currentPosition ?: 0L) + nextVodSkipAmountMs()).coerceAtMost(player?.duration ?: Long.MAX_VALUE)); true }
                else -> { resetHideTimer(); super.onKeyDown(keyCode, event) }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player?.isPlaying == true) player?.pause() else player?.play()
                updatePlayPauseButton(); true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (!isOverlayVisible) { showOverlay(); true }
                else { resetHideTimer(); super.onKeyDown(keyCode, event) }
            }
            KeyEvent.KEYCODE_GUIDE -> { if (!isOverlayVisible) showOverlay(); true }
            // Yellow / X / F key cycles aspect ratio without opening the overlay
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_F -> { cycleResizeMode(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // PiP's floating window has no overlay controls of its own — until now the only way to
    // pause/resume while in PiP was to exit it first. RemoteAction + a broadcast receiver is
    // the standard way to add actions to the PiP chrome itself.
    private var pipActionReceiver: android.content.BroadcastReceiver? = null
    private val pipPlayPauseAction = "com.iptvapp.PIP_PLAY_PAUSE"

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isPlaying = player?.isPlaying ?: false
            val icon = android.graphics.drawable.Icon.createWithResource(
                this, if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                this, 0, Intent(pipPlayPauseAction).setPackage(packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val action = android.app.RemoteAction(
                icon, if (isPlaying) "Pause" else "Play", if (isPlaying) "Pause" else "Play", pendingIntent
            )
            builder.setActions(listOf(action))
        }
        return builder.build()
    }

    private fun registerPipActionReceiver() {
        if (pipActionReceiver != null) return
        pipActionReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != pipPlayPauseAction) return
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setPictureInPictureParams(buildPipParams())
            }
        }
        val filter = android.content.IntentFilter(pipPlayPauseAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipActionReceiver, filter)
        }
    }

    private fun unregisterPipActionReceiver() {
        pipActionReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        pipActionReceiver = null
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerPipActionReceiver()
            enterPictureInPictureMode(buildPipParams())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // TV remotes have no reliable gesture to exit system PiP (no swipe-up/tap-X like
        // phone) — once entered there, the user could get stuck with no way back short of a
        // guessed Back-button sequence. Skip auto-entering PiP entirely on TV/large-screen
        // devices; phone keeps it since the standard Android PiP exit gestures work fine there.
        val pipAllowed = kotlinx.coroutines.runBlocking { prefs.pipEnabled.first() }
        if (!isVod && pipAllowed && !isLargeScreenDevice()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            isOverlayVisible = false
            binding.epgOverlay.visibility = View.GONE
            binding.btnBack.visibility = View.GONE
            binding.btnGuide.visibility = View.GONE
            binding.btnPlayPause.visibility = View.GONE
            binding.bottomControls.visibility = View.GONE
            binding.btnCast.visibility = View.GONE
            binding.bufferHealthBadge.visibility = View.GONE
        } else {
            unregisterPipActionReceiver()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.decorView.post {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupFavoritesGuide() {
        guideAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                binding.guideContainer.visibility = View.GONE
                playChannel(channel)
            },
            onFavoriteClick = {}
        )
        binding.rvFavoritesGuide.layoutManager = LinearLayoutManager(this)
        binding.rvFavoritesGuide.adapter = guideAdapter
        binding.btnGuide.setOnClickListener { toggleFavoritesGuide() }
        binding.btnCloseGuide.setOnClickListener { binding.guideContainer.visibility = View.GONE }
    }

    private fun toggleFavoritesGuide() {
        if (binding.guideContainer.visibility == View.VISIBLE) {
            binding.guideContainer.visibility = View.GONE
            return
        }
        hideHandler.removeCallbacks(hideRunnable)
        lifecycleScope.launch {
            val favs = repository.getFavoriteChannels().first()
            guideAdapter.submitList(favs)
            val ids = favs.map { it.streamId }
            if (ids.isNotEmpty()) {
                val epg = repository.getEpgForStreams(ids).first().groupBy { it.streamId }
                val textMap = favs.associate { ch ->
                    val now = epg[ch.streamId].orEmpty().firstOrNull()
                    val next = epg[ch.streamId].orEmpty().drop(1).firstOrNull()
                    val t = when {
                        now != null && next != null -> "NOW: ${now.title}   NEXT: ${next.title}"
                        now != null -> "NOW: ${now.title}"
                        else -> ""
                    }
                    ch.streamId to t
                }
                guideAdapter.submitEpgText(textMap)
            }
            binding.guideContainer.visibility = View.VISIBLE
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        player?.let { binding.playerView.player = it }
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            com.iptvapp.IptvApplication.logPlaybackEvent(
                applicationContext,
                "SESSION START: isVod=$isVod streamId=$streamId title=$streamTitle url=$streamUrl resumeMs=$resumePositionMs"
            )
            player = buildPlayer()
            if (isVod && resumePositionMs > 0L) {
                // Applying this as a seekTo() *after* the player had already buffered and
                // become ready at position 0 forced a mid-stream HTTP range renegotiation for
                // progressively-served files (.mkv movies) — some providers hang on that
                // instead of erroring, leaving playback stuck in STATE_BUFFERING forever with
                // no error/ended event to react to (black screen, "plays fine in mini player"
                // because the mini player never resumes at all). Passing the start position
                // at load time lets ExoPlayer request the correct range from the very first
                // request instead of re-requesting a new range after already starting at 0.
                val startPos = resumePositionMs
                resumePositionMs = 0L
                player?.setMediaItem(MediaItem.fromUri(streamUrl), startPos)
                player?.prepare()
                player?.playWhenReady = true
            } else {
                loadStream(streamUrl)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        saveVodProgress()
        sleepTimer?.cancel()
        retryJob?.cancel()
        seekRunnable?.let { seekHandler.removeCallbacks(it) }
        statsHandler.removeCallbacks(statsRunnable)
        stopHealthBadge()
        if (!isChangingConfigurations) {
            player?.release()
            player = null
        }
    }

    override fun finish() {
        setResult(Activity.RESULT_OK, android.content.Intent().apply {
            putExtra("stream_id", streamId)
            putExtra("stream_url", streamUrl)
            putExtra("stream_title", streamTitle)
            // Previously dropped entirely — HomeActivity's playerLauncher result handler had no
            // way to tell a merged-provider channel apart from a primary one on return, so
            // exiting fullscreen for a merged channel always routed back to Favorites instead
            // of the Providers tab it actually came from.
            putExtra("server_index", serverIndex)
            putExtra("merged_stream_id", mergedStreamId)
        })
        if (isVod && traktScrobbleStarted) {
            traktScrobbleStarted = false
            traktScrobble(::scrobbleStopCall)
        }
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        upNextJob?.cancel()
        castProxy?.stop()
        castProxy = null
        hideHandler.removeCallbacks(hideRunnable)
        osdHandler.removeCallbacks(hideOsdRunnable)
        indicatorHandler.removeCallbacks(hideBrightnessRunnable)
        indicatorHandler.removeCallbacks(hideVolumeRunnable)
        recordBlinkAnimator?.cancel()
        unregisterPipActionReceiver()
    }

    private fun getLocalIpAddress(): String? {
        try {
            for (iface in java.net.NetworkInterface.getNetworkInterfaces() ?: return null) {
                if (iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    if (addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        Log.d("CastProxy", "Local IP: $ip (${iface.name} up=${iface.isUp})")
                        return ip
                    }
                }
            }
            Log.e("CastProxy", "No non-loopback IPv4 address found")
        } catch (e: Exception) {
            Log.e("CastProxy", "getLocalIpAddress failed", e)
        }
        return null
    }
}
