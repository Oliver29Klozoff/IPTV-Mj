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
        // VOD's seek bar/elapsed-remaining time used to be deliberately exempt from auto-hide
        // (see startSeekBarUpdater's original comment) so it stayed visible the whole time like
        // some video apps do — explicitly asked to auto-hide with everything else instead, same
        // as every other transient control here.
        if (isVod) binding.vodSeekContainer.visibility = View.GONE
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
    // A locally recorded file played back from the Recordings screen — always launched with
    // is_vod=true too (a finished recording behaves like VOD: seekable, no live-edge concept, and
    // Trakt-scrobblable via the existing isVod movie-title path using programTitle/channelName as
    // stream_title). This flag exists only to pick a different ExoPlayer data source in
    // buildPlayer(), since a local file:// / content:// path isn't an HTTP resource.
    private var isRecordingPlayback: Boolean = false
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
    // Ghost Channel Radar / Provider Health Weather Map — reset in buildPlayer() (each fresh
    // channel-start/retry is a new "attempt" worth recording once), guards STATE_READY so a
    // mid-playback seek's READY doesn't get double-counted as a second success.
    private var outcomeRecordedForThisPlayback = false
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
    // matches the screen's aspect ratio (the common case: 16:9 IPTV on a 16:9 TV), RESIZE_MODE_
    // ZOOM and RESIZE_MODE_FILL produce the identical crop before any extra scale is even
    // applied, so a "Zoom" and "Stretch" pair kept looking the same as each other no matter what
    // scale was added on top — confirmed via testing through two earlier attempts (13 steps, then
    // 3 with a uniform +15% crop on both). Down to 2 modes: Best Fit (unchanged) and a plain
    // +15% crop-in via view scale, which is guaranteed to look different from Best Fit on any
    // stream since it doesn't depend on the resize-mode/container-aspect-ratio interaction at all.
    private data class ResizeStep(val mode: Int, val scale: Float, val label: String)
    private val resizeSteps = listOf(
        ResizeStep(AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.0f, "Best Fit"),
        ResizeStep(AspectRatioFrameLayout.RESIZE_MODE_FIT, 1.15f, "Zoom In")
    )
    private var resizeModeIndex = 0

    @Inject lateinit var repository: XtreamRepository
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var prefs: com.iptvapp.data.local.PreferencesManager
    @Inject lateinit var watchPartyManager: com.iptvapp.sync.WatchPartyManager
    @Inject lateinit var rewatchNotesManager: com.iptvapp.sync.RewatchNotesManager
    @Inject lateinit var communityHealthManager: com.iptvapp.sync.CommunityHealthManager
    @Inject lateinit var db: com.iptvapp.data.local.IptvDatabase
    private var bandwidthTracker: BandwidthTracker? = null

    // ─── Watch Party ────────────────────────────────────────────────────────
    private var partyCode: String = ""
    private var isPartyHost: Boolean = false
    private var isPartyMember: Boolean = false
    // Guards every programmatic seek/play/pause the party listener applies on a member's player,
    // so the resulting Player.Listener callbacks (onIsPlayingChanged etc.) don't get mistaken
    // for a fresh "real user action" and re-written back to Firestore — the classic sync
    // feedback loop. Set true immediately before the call, cleared right after.
    private var isApplyingRemoteUpdate: Boolean = false
    private var partyListenerReg: com.google.firebase.firestore.ListenerRegistration? = null
    private var partyMemberCount: Int = 0
    private val partyHeartbeatHandler = Handler(Looper.getMainLooper())
    private var partyLaunchCode: String = ""
    private val partyHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (isPartyHost && partyCode.isNotEmpty() && player?.isPlaying == true) {
                watchPartyManager.writeState(partyCode, true, player?.currentPosition ?: 0L)
            }
            partyHeartbeatHandler.postDelayed(this, 12_000L)
        }
    }

    // ─── Group Watch Voting ─────────────────────────────────────────────────
    private var pollListenerReg: com.google.firebase.firestore.ListenerRegistration? = null
    private var pollAutoCloseJob: Job? = null
    private var latestPollState: com.iptvapp.sync.PollState? = null
    private var pollDialog: AlertDialog? = null

    // ─── Time Capsule Rewatch Notes ─────────────────────────────────────────
    private var rewatchNotesListenerReg: com.google.firebase.firestore.ListenerRegistration? = null
    private var latestRewatchNotes: List<com.iptvapp.sync.RewatchNote> = emptyList()

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
    // Cached rather than re-read from DataStore on every scheduleRetry() call (which can fire
    // repeatedly in a short window on a flaky stream) — refreshed once in onCreate, matching the
    // read-once-at-launch pattern already used for extraBufferingEnabled/tunneledPlaybackEnabled.
    private var liveReconnectSpeed: String = "normal"
    private var retryJob: Job? = null
    private var channelSwitchJob: Job? = null
    private var bufferWatchdog: Runnable? = null

    // A WiFi<->cellular handoff (or any switch to a genuinely different network) silently kills
    // the in-flight HTTP connection at the OS level without necessarily surfacing as a
    // PlaybackException right away — until now the player had no idea the network even changed
    // and just waited for the existing error/stall-triggered scheduleRetry() path to eventually
    // notice. This reacts immediately instead of waiting for that.
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var lastKnownNetwork: android.net.Network? = null

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

    // Shows "Skip to Next Episode" once the player enters the last minute of a series episode
    // (see skipToNextEpisode kdoc) — a minute is comfortably longer than most shows' end credits
    // without being so early it interrupts the episode's actual final scene.
    private val skipNextHandler = Handler(Looper.getMainLooper())
    private val skipNextRunnable = object : Runnable {
        override fun run() {
            updateSkipToNextEpisodeVisibility()
            skipNextHandler.postDelayed(this, 2000)
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
        isRecordingPlayback = intent.getBooleanExtra("is_recording", false)
        resumePositionMs = intent.getLongExtra("resume_ms", 0L)
        epIds    = intent.getStringArrayListExtra("ep_ids")    ?: emptyList()
        epTitles = intent.getStringArrayListExtra("ep_titles") ?: emptyList()
        epExts   = intent.getStringArrayListExtra("ep_exts")   ?: emptyList()
        epIndex  = intent.getIntExtra("ep_index", -1)
        traktSeriesName = intent.getStringExtra("series_name") ?: ""
        traktSeason  = intent.getIntExtra("season_num", -1)
        traktEpisode = intent.getIntExtra("episode_num", -1)
        episodeSeriesId = intent.getIntExtra("series_id", -1)
        partyLaunchCode = intent.getStringExtra("watch_party_code") ?: ""

        setupWatchPartyButton()
        if (partyLaunchCode.isNotEmpty()) joinWatchParty(partyLaunchCode, showToast = false)
        updateRewatchNotesButtonVisibility()

        setupChannelZones()
        setupGestureDetector()
        setupNetworkChangeReconnect()
        binding.tvChannelTitle.text = streamTitle
        binding.btnBack.setOnClickListener { finish() }

        if (!isVod) {
            lifecycleScope.launch { prefs.setLivePlaybackActive(serverIndex) }
            lifecycleScope.launch { liveReconnectSpeed = prefs.liveReconnectSpeed.first() }
        }

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

    // Data for whichever advance action is actually available right now (next episode in the
    // current list, or next season's first episode) — resolved once and shared by both the
    // end-of-stream Up Next card and the manual "Skip to Next Episode" button, so the two never
    // disagree about what "next" means.
    private data class NextEpisodeAction(val title: String, val advance: () -> Unit)

    private suspend fun resolveNextEpisodeAction(): NextEpisodeAction? {
        val nextIndex = epIndex + 1
        if (epIds.isEmpty()) return null

        if (nextIndex < epIds.size) {
            val title = epTitles.getOrElse(nextIndex) { "Next Episode" }
            return NextEpisodeAction(title) { playNextEpisode(nextIndex) }
        }

        // Last episode of the season the player was launched with — epIds/epTitles/epExts
        // only ever contain ONE season's episodes (see SeriesDetailActivity.launchEpisode,
        // which passes currentSeasonEpisodes), so reaching the end of that array doesn't
        // mean the series itself is over. Fetch the next season's episode list fresh and
        // splice it in, rather than leaving Up Next silently unavailable at every season
        // finale. Only possible for primary-provider series (episodeSeriesId == -1 for
        // merged/other-provider series, which have no season/episode-int metadata to look
        // this up from — see PlayerActivity's merged-series comment near saveVodProgress).
        if (episodeSeriesId == -1) return null
        val currentSeasonEpisode = traktManager.parseSeasonEpisode(epTitles.lastOrNull() ?: return null)
            ?: return null
        val nextSeasonNum = currentSeasonEpisode.first + 1
        val info = (repository.fetchSeriesInfo(episodeSeriesId) as? com.iptvapp.util.Resource.Success)?.data ?: return null
        val nextSeasonEpisodes = info.episodes?.get(nextSeasonNum.toString())
            ?.sortedBy { it.episodeNum } ?: return null
        if (nextSeasonEpisodes.isEmpty()) return null

        val title = "S$nextSeasonNum E${nextSeasonEpisodes.first().episodeNum} ${nextSeasonEpisodes.first().title}"
        return NextEpisodeAction(title) { playNextSeason(nextSeasonEpisodes) }
    }

    private fun showUpNextIfAvailable() {
        lifecycleScope.launch {
            if (!prefs.autoplayNextEpisodeEnabled.first()) return@launch
            val action = resolveNextEpisodeAction() ?: return@launch
            showUpNextCard(action.title, action.advance)
        }
    }

    // Lets you skip the trailing credits of an episode instead of waiting for STATE_ENDED —
    // unlike the Up Next card, this jumps straight to the next episode with no countdown, since
    // tapping it is already the explicit "I'm done, move on" action.
    private fun skipToNextEpisode() {
        binding.btnSkipToNextEpisode.visibility = View.GONE
        lifecycleScope.launch {
            resolveNextEpisodeAction()?.advance?.invoke()
        }
    }

    private fun showUpNextCard(nextTitle: String, onAdvance: () -> Unit) {
        binding.tvUpNextTitle.text = nextTitle
        binding.upNextCard.visibility = View.VISIBLE

        val totalMs = 10_000L
        upNextJob = lifecycleScope.launch {
            val start = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - start
                val remaining = (totalMs - elapsed).coerceAtLeast(0L)
                binding.upNextProgress.progress = ((remaining.toFloat() / totalMs) * 100).toInt()
                if (remaining == 0L) { onAdvance(); break }
                kotlinx.coroutines.delay(100)
            }
        }

        binding.btnUpNextPlay.setOnClickListener {
            upNextJob?.cancel()
            onAdvance()
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

    // Season-boundary equivalent of playNextEpisode — same restart-based advance, but swaps in
    // the NEXT season's full episode list (so Up Next continues to work all the way through that
    // season too) instead of continuing to index into the now-exhausted current-season array.
    private fun playNextSeason(nextSeasonEpisodes: List<com.iptvapp.data.api.Episode>) {
        binding.upNextCard.visibility = View.GONE
        lifecycleScope.launch {
            val first = nextSeasonEpisodes.first()
            val url = repository.getSeriesEpisodeUrl(first.id, first.containerExtension)
            val resumeMs = repository.getEpisodeProgress(episodeSeriesId, first.season, first.episodeNum).first
            val intent = Intent(this@PlayerActivity, PlayerActivity::class.java).apply {
                putExtra("stream_url", url)
                putExtra("stream_title", "S${first.season}E${first.episodeNum} ${first.title}")
                putExtra("stream_id", first.id.hashCode())
                putExtra("is_vod", true)
                putExtra("series_id", episodeSeriesId)
                putExtra("ep_index", 0)
                putExtra("resume_ms", resumeMs)
                putStringArrayListExtra("ep_ids", ArrayList(nextSeasonEpisodes.map { it.id }))
                putStringArrayListExtra("ep_titles", ArrayList(nextSeasonEpisodes.map { "S${it.season}E${it.episodeNum} ${it.title}" }))
                putStringArrayListExtra("ep_exts", ArrayList(nextSeasonEpisodes.map { it.containerExtension }))
                putExtra("series_name", traktSeriesName)
                putExtra("season_num", first.season)
                putExtra("episode_num", first.episodeNum)
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
        setupRewatchNotesButton()
    }

    // ─── Watch Party ────────────────────────────────────────────────────────

    private fun currentWatchPartyContent(): com.iptvapp.sync.WatchPartyContent {
        // streamUrl's own extension is reused as the container extension for VOD/episode — it's
        // exactly what this device requested playback with, and rebuilding the URL for a member
        // on their own account just needs the same extension against their own credentials.
        val ext = streamUrl.substringAfterLast('.', "mp4")
        return when {
            !isVod -> com.iptvapp.sync.WatchPartyContent(
                contentType = "LIVE", streamId = streamId, serverIndex = serverIndex,
                mergedStreamId = mergedStreamId, title = streamTitle
            )
            episodeSeriesId != -1 && traktSeason >= 0 && traktEpisode >= 0 -> com.iptvapp.sync.WatchPartyContent(
                contentType = "EPISODE", seriesId = episodeSeriesId, seasonNum = traktSeason,
                episodeNum = traktEpisode, title = streamTitle, containerExtension = ext,
                episodeId = epIds.getOrNull(epIndex) ?: ""
            )
            else -> com.iptvapp.sync.WatchPartyContent(
                contentType = "VOD", streamId = streamId, serverIndex = serverIndex,
                mergedStreamId = mergedStreamId, title = streamTitle, containerExtension = ext
            )
        }
    }

    private fun setupWatchPartyButton() {
        binding.btnWatchParty.setOnClickListener {
            if (partyCode.isEmpty()) showStartOrJoinPartyDialog() else showPartyStatusDialog()
            resetHideTimer()
        }
        binding.tvWatchPartyBadge.setOnClickListener {
            if (partyCode.isNotEmpty()) showPartyStatusDialog()
        }
    }

    private fun showStartOrJoinPartyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Watch Party")
            .setItems(arrayOf("Start Watch Party", "Join Watch Party")) { _, which ->
                if (which == 0) startWatchParty() else showJoinPartyCodeDialog()
            }
            .show()
    }

    private fun showJoinPartyCodeDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. ab12cd34"
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Join Watch Party")
            .setView(input)
            .setPositiveButton("Join") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) joinWatchParty(code)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startWatchParty() {
        lifecycleScope.launch {
            try {
                val code = watchPartyManager.startParty(currentWatchPartyContent())
                partyCode = code
                isPartyHost = true
                isPartyMember = false
                attachPartyListener(code)
                startPartyHeartbeat()
                updateWatchPartyBadge()
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("Watch Party Started")
                    .setMessage("Share this code with others:\n\n${code.uppercase()}")
                    .setPositiveButton("Copy Code") { _, _ ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Watch Party Code", code.uppercase()))
                        Toast.makeText(this@PlayerActivity, "Code copied", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "Couldn't start Watch Party: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun joinWatchParty(code: String, showToast: Boolean = true) {
        lifecycleScope.launch {
            try {
                val state = watchPartyManager.joinParty(code)
                if (state == null) {
                    if (showToast) Toast.makeText(this@PlayerActivity, "Party not found", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                partyCode = state.code
                isPartyHost = false
                isPartyMember = true
                attachPartyListener(state.code)
                updateWatchPartyBadge()
                if (showToast) Toast.makeText(this@PlayerActivity, "Joined Watch Party", Toast.LENGTH_SHORT).show()
                applyRemotePartyState(state)
            } catch (e: Exception) {
                if (showToast) Toast.makeText(this@PlayerActivity, "Couldn't join Watch Party: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun attachPartyListener(code: String) {
        partyListenerReg?.remove()
        partyListenerReg = watchPartyManager.listen(code) { state ->
            if (state == null) {
                // Host ended the party (doc deleted) — clear local party state either way.
                if (isPartyMember) {
                    runOnUiThread { Toast.makeText(this, "Watch Party ended", Toast.LENGTH_SHORT).show() }
                }
                clearWatchPartyState()
                return@listen
            }
            partyMemberCount = state.memberCount
            runOnUiThread { updateWatchPartyBadge() }
            // Members apply the host's state; the host ignores its own echoed writes (it's the
            // source of truth, not a follower).
            if (isPartyMember && !isPartyHost) {
                runOnUiThread { applyRemotePartyState(state) }
            }
        }
    }

    private fun startPartyHeartbeat() {
        partyHeartbeatHandler.removeCallbacks(partyHeartbeatRunnable)
        partyHeartbeatHandler.postDelayed(partyHeartbeatRunnable, 12_000L)
    }

    /** Member-side sync: drift-compensated seek + play/pause mirroring for VOD/EPISODE, or a
     * fresh reload on channel change for LIVE. 2.5s tolerance — comfortably above normal
     * network/Firestore round-trip jitter (typically well under a second) while still feeling
     * "in sync" to a viewer; smaller would cause seek-fighting on every minor drift, much larger
     * would let members visibly lag the host. */
    private fun applyRemotePartyState(state: com.iptvapp.sync.WatchPartyState) {
        val p = player ?: return
        if (state.content.contentType == "LIVE") {
            val sameChannel = if (state.content.serverIndex != -1)
                state.content.serverIndex == serverIndex && state.content.mergedStreamId == mergedStreamId
            else state.content.streamId == streamId
            if (!sameChannel) {
                isApplyingRemoteUpdate = true
                streamId = state.content.streamId
                bandwidthTracker?.updateServerIndex(state.content.serverIndex)
                serverIndex = state.content.serverIndex
                mergedStreamId = state.content.mergedStreamId
                streamTitle = state.content.title
                binding.tvChannelTitle.text = streamTitle
                lifecycleScope.launch {
                    try {
                        val url = if (state.content.serverIndex != -1)
                            repository.getMergedLiveStreamUrl(state.content.serverIndex, state.content.mergedStreamId)
                        else repository.getLiveStreamUrl(state.content.streamId)
                        loadStream(url)
                    } catch (_: Exception) {
                        // Most likely cause: this member's own provider doesn't carry the channel
                        // the host switched to (Watch Party syncs channel identity, not a URL —
                        // see WatchPartyContent kdoc). Surface it rather than silently freezing.
                        Toast.makeText(
                            this@PlayerActivity,
                            "Couldn't follow host's channel — not available on your provider",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    isApplyingRemoteUpdate = false
                }
            }
            return
        }

        // VOD/EPISODE: if this device isn't already playing the exact title the party is on
        // (e.g. right after joinParty() calls this with nothing loaded yet, or the host changed
        // titles mid-party), resolve and load it on THIS member's own account first — same
        // "sync identity, not a URL" approach LIVE already uses above. Previously this branch
        // only ever seeked/played-paused whatever the player already happened to have loaded,
        // which is a no-op (stuck buffering forever) the moment a member joins with nothing
        // loaded — this was a real bug, not the "different provider" case the LIVE toast covers.
        val sameContent = if (state.content.contentType == "EPISODE") {
            state.content.seriesId == episodeSeriesId && state.content.seasonNum == traktSeason &&
                state.content.episodeNum == traktEpisode
        } else {
            if (state.content.serverIndex != -1)
                state.content.serverIndex == serverIndex && state.content.mergedStreamId == mergedStreamId
            else state.content.streamId == streamId
        }
        if (!sameContent) {
            isApplyingRemoteUpdate = true
            streamTitle = state.content.title
            binding.tvChannelTitle.text = streamTitle
            lifecycleScope.launch {
                try {
                    val url = if (state.content.contentType == "EPISODE") {
                        episodeSeriesId = state.content.seriesId
                        traktSeason = state.content.seasonNum
                        traktEpisode = state.content.episodeNum
                        repository.getSeriesEpisodeUrl(state.content.episodeId, state.content.containerExtension)
                    } else {
                        streamId = state.content.streamId
                        serverIndex = state.content.serverIndex
                        mergedStreamId = state.content.mergedStreamId
                        if (state.content.serverIndex != -1)
                            repository.getMergedVodStreamUrl(state.content.serverIndex, state.content.mergedStreamId, state.content.containerExtension)
                        else repository.getVodStreamUrl(state.content.streamId, state.content.containerExtension)
                    }
                    loadStream(url)
                    if (state.positionMs > 0L) player?.seekTo(state.positionMs)
                } catch (_: Exception) {
                    // Most likely cause: this member's own provider doesn't carry this title —
                    // same reasoning as the LIVE branch's toast above.
                    Toast.makeText(
                        this@PlayerActivity,
                        "Couldn't load \"${state.content.title}\" — not available on your provider",
                        Toast.LENGTH_LONG
                    ).show()
                }
                isApplyingRemoteUpdate = false
            }
            return
        }

        // Already on the right title — drift-compensate position using server-clock elapsed time
        // since the host's last write, then reconcile play/pause state.
        val expectedPosition = if (state.isPlaying)
            state.positionMs + (System.currentTimeMillis() - state.updatedAtMs)
        else state.positionMs

        isApplyingRemoteUpdate = true
        if (kotlin.math.abs(p.currentPosition - expectedPosition) > 2500L) {
            p.seekTo(expectedPosition.coerceAtLeast(0L))
        }
        if (p.isPlaying != state.isPlaying) {
            if (state.isPlaying) p.play() else p.pause()
            updatePlayPauseButton()
        }
        isApplyingRemoteUpdate = false
    }

    /** Host-only write hook — call at every real user-initiated seek/play/pause/channel-change
     * site. No-ops for members and for the host's own remote-applied changes (isApplyingRemoteUpdate). */
    private fun notifyPartyStateChange() {
        if (!isPartyHost || partyCode.isEmpty() || isApplyingRemoteUpdate) return
        val p = player ?: return
        watchPartyManager.writeState(partyCode, p.isPlaying, p.currentPosition)
    }

    private fun notifyPartyChannelChange() {
        if (!isPartyHost || partyCode.isEmpty() || isApplyingRemoteUpdate) return
        watchPartyManager.writeChannelChange(partyCode, currentWatchPartyContent())
    }

    private fun updateWatchPartyBadge() {
        if (partyCode.isEmpty()) {
            binding.tvWatchPartyBadge.visibility = View.GONE
            return
        }
        binding.tvWatchPartyBadge.visibility = View.VISIBLE
        binding.tvWatchPartyBadge.text = "Watch Party (${partyMemberCount.coerceAtLeast(1)})"
    }

    private fun showPartyStatusDialog() {
        val roleLabel = if (isPartyHost) "Hosting" else "Member"
        val builder = AlertDialog.Builder(this)
            .setTitle("Watch Party")
            .setMessage("$roleLabel — code ${partyCode.uppercase()}\n${partyMemberCount.coerceAtLeast(1)} watching")
            .setNegativeButton("Close", null)
            .setNeutralButton("Vote: What's Next?") { _, _ -> startOrShowPoll() }
        if (isPartyHost) {
            builder.setPositiveButton("End Party") { _, _ -> endWatchParty() }
        } else {
            builder.setPositiveButton("Leave") { _, _ -> leaveWatchParty() }
        }
        builder.show()
    }

    // ─── Group Watch Voting ─────────────────────────────────────────────────

    /** Entry point for the "Vote" button — any party member can trigger this. If a poll is
     * already active on the party, just (re)opens the live results dialog instead of starting a
     * second one. */
    private fun startOrShowPoll() {
        if (partyCode.isEmpty()) return
        attachPollListener(partyCode)
        if (latestPollState?.active == true) {
            showPollDialog()
            return
        }
        lifecycleScope.launch {
            try {
                watchPartyManager.startPoll(partyCode, currentWatchPartyContent())
                showPollDialog()
                schedulePollAutoClose()
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "Couldn't start poll: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun attachPollListener(code: String) {
        if (pollListenerReg != null) return
        pollListenerReg = watchPartyManager.listenPoll(code) { state ->
            latestPollState = state
            if (state != null && state.active) {
                runOnUiThread { refreshPollDialog() }
            } else {
                runOnUiThread { pollDialog?.dismiss(); pollDialog = null }
            }
        }
    }

    /** Fixed 20s auto-close timer — simpler and avoids needing extra host-only "close poll" UI.
     * Only the member who actually started the poll schedules the close call, so it isn't raced
     * by every device independently trying to close/tally the same poll. */
    private fun schedulePollAutoClose() {
        pollAutoCloseJob?.cancel()
        pollAutoCloseJob = lifecycleScope.launch {
            delay(20_000L)
            val code = partyCode
            if (code.isEmpty()) return@launch
            try {
                val winner = watchPartyManager.closePoll(code)
                if (winner != null) {
                    // Same content-switch mechanism a host's manual channel change already uses —
                    // members' players auto-tune with no separate switch logic. Any member can
                    // apply the winner here since Firestore rules (added externally) will govern
                    // write access the same way a channel change would.
                    watchPartyManager.writeChannelChange(code, winner)
                    if (isPartyHost) {
                        isApplyingRemoteUpdate = true
                        applyRemotePartyState(
                            com.iptvapp.sync.WatchPartyState(
                                code = code, hostUid = "", content = winner, isPlaying = true,
                                positionMs = 0L, updatedAtMs = System.currentTimeMillis(),
                                memberCount = partyMemberCount, active = true
                            )
                        )
                        isApplyingRemoteUpdate = false
                    }
                    runOnUiThread { Toast.makeText(this@PlayerActivity, "Poll closed — switching to \"${winner.title}\"", Toast.LENGTH_SHORT).show() }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun showPollDialog() {
        if (pollDialog?.isShowing == true) { refreshPollDialog(); return }
        val builder = AlertDialog.Builder(this)
            .setTitle("What should we watch next?")
            .setMessage("Loading…")
            .setNegativeButton("Close", null)
            .setNeutralButton("Propose Current") { _, _ -> proposeCurrentToPoll() }
        pollDialog = builder.show()
        refreshPollDialog()
    }

    private fun proposeCurrentToPoll() {
        val code = partyCode
        if (code.isEmpty()) return
        lifecycleScope.launch {
            try {
                watchPartyManager.proposeOption(code, currentWatchPartyContent())
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshPollDialog() {
        val dialog = pollDialog ?: return
        val state = latestPollState ?: return
        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        val sb = StringBuilder()
        state.options.forEachIndexed { i, opt ->
            val votes = state.votes.values.count { it == i }
            val mine = if (state.votes[myUid] == i) " ✓ your vote" else ""
            sb.append("${i + 1}. ${opt.content.title.ifBlank { "(untitled)" }} — $votes vote${if (votes == 1) "" else "s"}$mine\n")
        }
        if (state.options.isEmpty()) sb.append("No options yet.")
        dialog.setMessage(sb.toString().trim())
        // Positive button re-purposed as a numbered vote picker (avoids building a whole custom
        // list-adapter dialog for what's normally 2-4 options) — tapping opens a simple item list.
        if (state.options.isNotEmpty()) {
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Vote…") { _, _ -> showVotePicker(state) }
        }
    }

    private fun showVotePicker(state: com.iptvapp.sync.PollState) {
        val labels = state.options.map { it.content.title.ifBlank { "(untitled)" } }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Vote for")
            .setItems(labels) { _, which ->
                val code = partyCode
                if (code.isEmpty()) return@setItems
                lifecycleScope.launch {
                    try { watchPartyManager.castVote(code, which) } catch (_: Exception) {}
                }
            }
            .show()
    }

    private fun endWatchParty() {
        val code = partyCode
        lifecycleScope.launch { watchPartyManager.endParty(code) }
        clearWatchPartyState()
    }

    private fun leaveWatchParty() {
        val code = partyCode
        lifecycleScope.launch { watchPartyManager.leaveParty(code) }
        clearWatchPartyState()
    }

    private fun clearWatchPartyState() {
        partyListenerReg?.remove()
        partyListenerReg = null
        partyHeartbeatHandler.removeCallbacks(partyHeartbeatRunnable)
        pollListenerReg?.remove()
        pollListenerReg = null
        pollAutoCloseJob?.cancel()
        pollDialog?.dismiss()
        pollDialog = null
        partyCode = ""
        isPartyHost = false
        isPartyMember = false
        partyMemberCount = 0
        runOnUiThread { updateWatchPartyBadge() }
    }

    // ─── Time Capsule Rewatch Notes ─────────────────────────────────────────
    // VOD/EPISODE only — leaving a timestamped note on a live channel doesn't make sense the
    // same way a live broadcast has no fixed position to anchor a note to for a later viewer.

    private fun updateRewatchNotesButtonVisibility() {
        binding.btnRewatchNotes.visibility = if (isVod) View.VISIBLE else View.GONE
    }

    private fun setupRewatchNotesButton() {
        binding.btnRewatchNotes.setOnClickListener {
            showRewatchNotesSheet()
            resetHideTimer()
        }
    }

    private fun rewatchContentKey(): String = rewatchNotesManager.contentKeyFor(currentWatchPartyContent())

    private fun showRewatchNotesSheet() {
        if (!isVod) return
        val key = rewatchContentKey()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rewatch Notes")
            .setMessage("Loading…")
            .setPositiveButton("Add Note Here") { _, _ -> showAddNoteDialog() }
            .setNegativeButton("Close", null)
            .show()
        lifecycleScope.launch {
            try {
                val notes = rewatchNotesManager.getNotes(key)
                latestRewatchNotes = notes
                renderRewatchNotes(dialog, notes)
            } catch (e: Exception) {
                dialog.setMessage("Couldn't load notes: ${e.message}")
            }
        }
        rewatchNotesListenerReg?.remove()
        rewatchNotesListenerReg = rewatchNotesManager.listenNotes(key) { notes ->
            latestRewatchNotes = notes
            runOnUiThread { if (dialog.isShowing) renderRewatchNotes(dialog, notes) }
        }
        dialog.setOnDismissListener {
            rewatchNotesListenerReg?.remove()
            rewatchNotesListenerReg = null
        }
    }

    private fun renderRewatchNotes(dialog: AlertDialog, notes: List<com.iptvapp.sync.RewatchNote>) {
        if (notes.isEmpty()) {
            dialog.setMessage("No notes yet — be the first to leave one.")
            return
        }
        val sb = StringBuilder()
        notes.forEachIndexed { i, n ->
            val mm = (n.positionMs / 60000)
            val ss = (n.positionMs / 1000) % 60
            sb.append(String.format("%d. [%02d:%02d] %s: %s\n", i + 1, mm, ss, n.authorLabel, n.text))
        }
        dialog.setMessage(sb.toString().trim())
        // Simple list-with-timestamps is the v1 here (no custom seek-bar marker rendering) — tap
        // "Jump to Note" to pick one by number and seek, same "good enough v1" scope call the
        // task spec calls out explicitly.
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Jump to Note") { _, _ -> showJumpToNoteDialog(notes) }
    }

    private fun showJumpToNoteDialog(notes: List<com.iptvapp.sync.RewatchNote>) {
        val labels = notes.map {
            val mm = (it.positionMs / 60000)
            val ss = (it.positionMs / 1000) % 60
            String.format("[%02d:%02d] %s: %s", mm, ss, it.authorLabel, it.text)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Jump to")
            .setItems(labels) { _, which ->
                player?.seekTo(notes[which].positionMs)
                notifyPartyStateChange()
            }
            .show()
    }

    private fun showAddNoteDialog() {
        val p = player ?: return
        val positionMs = p.currentPosition
        val input = android.widget.EditText(this).apply {
            hint = "What's happening here?"
            setPadding(32, 16, 32, 16)
        }
        val mm = (positionMs / 60000)
        val ss = (positionMs / 1000) % 60
        AlertDialog.Builder(this)
            .setTitle(String.format("Note at %02d:%02d", mm, ss))
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val key = rewatchContentKey()
                    lifecycleScope.launch {
                        try {
                            rewatchNotesManager.addNote(key, positionMs, text)
                        } catch (e: Exception) {
                            Toast.makeText(this@PlayerActivity, "Couldn't save note: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

        // Real-stream HDR/Dolby Vision detection — unlike ChannelQualityTag's SD/HD/FHD/4K tag
        // (parsed from the channel's name text before playback even starts), this reads the
        // actual decoded video format, so it only ever shows for a genuinely HDR/DV stream, not
        // whatever a provider happened to label the channel. Dolby Vision isn't a colorTransfer
        // value on its own — it's identified by the video mimeType instead, same check
        // dv7FallbackEnabled already uses above for the DV7-to-HEVC decoder redirect.
        val hdrLabel = when {
            vf?.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION -> "DV"
            vf?.colorInfo?.let { androidx.media3.common.ColorInfo.isTransferHdr(it) } == true -> "HDR"
            else -> null
        }
        if (hdrLabel != null) {
            binding.tvHdrBadge.text = hdrLabel
            binding.tvHdrBadge.visibility = View.VISIBLE
        } else {
            binding.tvHdrBadge.visibility = View.GONE
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

    private var skipNextPollerStarted = false

    private fun startSkipNextPoller() {
        if (skipNextPollerStarted) return
        skipNextPollerStarted = true
        binding.btnSkipToNextEpisode.setOnClickListener { skipToNextEpisode() }
        skipNextHandler.postDelayed(skipNextRunnable, 2000)
    }

    private fun updateSkipToNextEpisodeVisibility() {
        val p = player ?: return
        val duration = p.duration
        if (duration == androidx.media3.common.C.TIME_UNSET || duration <= 0L) return
        val remaining = duration - p.currentPosition
        val withinLastMinute = remaining in 0..60_000L
        // Hidden once the Up Next card itself is already showing (STATE_ENDED fired) — at that
        // point the countdown card is the relevant control, not this one.
        val shouldShow = withinLastMinute && binding.upNextCard.visibility != View.VISIBLE
        binding.btnSkipToNextEpisode.visibility = if (shouldShow) View.VISIBLE else View.GONE
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
                notifyPartyStateChange()
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
                notifyPartyStateChange()
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
        outcomeRecordedForThisPlayback = false
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
        // Locally recorded files (content:// or file:// paths, played back via the Recordings
        // screen's "Play in App" action so they can scrobble to Trakt) aren't HTTP resources at
        // all — OkHttpDataSource can't read them, so this case needs the plain platform data
        // source instead of the IPTV-CDN-tuned OkHttp one used for every live/VOD stream.
        val upstreamDataSourceFactory = if (isRecordingPlayback) {
            androidx.media3.datasource.DefaultDataSource.Factory(this)
        } else {
            OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("MKTV/${com.iptvapp.BuildConfig.VERSION_NAME} (Linux;Android ${Build.VERSION.RELEASE}) ExoPlayerLib/1.4.1")
        }
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
        // Transparently serves a completed offline download's bytes from Media3's own
        // SimpleCache (keyed by the same source URL DownloadRepository.startDownload downloaded
        // it under) instead of hitting the network, when one exists — CacheDataSource checks the
        // cache first and only falls through to upstreamDataSourceFactory on a miss, so live
        // channels and never-downloaded VOD/episodes are completely unaffected.
        // setCacheWriteDataSinkFactory(null) is load-bearing, not optional: the Factory DEFAULTS
        // to writing everything played back into the cache, and this cache uses NoOpCacheEvictor
        // (downloads must never be LRU-evicted) — without the explicit null, every minute of
        // live TV was teed onto disk and NOTHING ever deleted it (caught on-device: 102MB of
        // live-playback garbage in files/downloads with zero downloads ever started). Writing
        // during a real *download* is Media3's own job inside MediaDownloadService, not this
        // playback path's.
        val cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(com.iptvapp.download.DownloadUtil.getDownloadCache(this))
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Per-provider bandwidth tracking (Settings > Data Usage) — wraps the final factory so
        // real network transfers (cache hits from offline downloads are excluded, see
        // BandwidthTracker kdoc) get attributed to whichever provider serverIndex is currently set
        // to. Re-created per buildPlayer() call since serverIndex can change (failover, channel
        // switch reusing this same Activity).
        bandwidthTracker?.stop()
        // Feature C: warn-only per-provider bandwidth budget check re-evaluated after every flush
        // (~7s during active playback) — see BandwidthBudgetManager kdoc.
        bandwidthTracker = BandwidthTracker(db.bandwidthUsageDao(), serverIndex, lifecycleScope) {
            com.iptvapp.ui.player.BandwidthBudgetManager(db, prefs).checkAndWarn(this@PlayerActivity, serverIndex)
        }.also { it.startPeriodicFlush() }
        val trackedDataSourceFactory = bandwidthTracker!!.wrap(cacheDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(trackedDataSourceFactory)

        val tunnelingEnabled = kotlinx.coroutines.runBlocking { prefs.tunneledPlaybackEnabled.first() }
        val dv7FallbackEnabled = kotlinx.coroutines.runBlocking { prefs.dv7FallbackEnabled.first() }
        val audioPassthroughFallbackEnabled = kotlinx.coroutines.runBlocking { prefs.audioPassthroughFallbackEnabled.first() }

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

        // Audio passthrough fallback: some TV boxes report E-AC3/DTS passthrough support
        // (AudioCapabilities queries the device/HDMI sink) but produce total silence or crash
        // when no AVR/receiver is actually connected to decode it. Forcing AudioCapabilities.
        // DEFAULT (stereo PCM only, no passthrough encodings) makes DefaultAudioSink always
        // transcode instead of passing through, at the cost of losing surround sound on setups
        // that genuinely do have a receiver — same opt-in, device-specific-workaround shape as
        // the DV7 fallback above.
        val renderersFactory = if (audioPassthroughFallbackEnabled) {
            object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): androidx.media3.exoplayer.audio.AudioSink {
                    return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .build()
                }
            }.setMediaCodecSelector(codecSelector)
        } else {
            androidx.media3.exoplayer.DefaultRenderersFactory(this)
                .setMediaCodecSelector(codecSelector)
        }

        val subtitlesEnabled = kotlinx.coroutines.runBlocking { prefs.subtitlesEnabled.first() }
        val preferredAudioLanguage = kotlinx.coroutines.runBlocking { prefs.preferredAudioLanguage.first() }
        val preferredSubtitleLanguage = kotlinx.coroutines.runBlocking { prefs.preferredSubtitleLanguage.first() }
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .apply { if (tunnelingEnabled) setTunnelingEnabled(true) }
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !subtitlesEnabled)
                .setSelectUndeterminedTextLanguage(subtitlesEnabled)
                .apply { if (preferredAudioLanguage.isNotBlank()) setPreferredAudioLanguage(preferredAudioLanguage) }
                .apply { if (subtitlesEnabled && preferredSubtitleLanguage.isNotBlank()) setPreferredTextLanguage(preferredSubtitleLanguage) }
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
                    notifyPartyStateChange()
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

                    // Refreshes the PiP window's aspect ratio once the real video dimensions
                    // are known (e.g. entering PiP before the first frame decodes, or a channel
                    // switch mid-PiP to a different-shaped stream) — see pipAspectRatio kdoc.
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
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
                                // Ghost Channel Radar / Provider Health Weather Map — a real
                                // successful playback start, recorded once per channel-start (not
                                // re-fired on every READY, e.g. after a user seek) via the same
                                // guard already used below for the Trakt scrobble-start call.
                                if (!outcomeRecordedForThisPlayback) {
                                    outcomeRecordedForThisPlayback = true
                                    recordPlaybackOutcome(success = true)
                                    maybeShowCommunityHealthBanner()
                                }
                                if (isVod) startSeekBarUpdater()
                                if (isVod && epIds.isNotEmpty()) startSkipNextPoller()
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
                                        if (!outcomeRecordedForThisPlayback) {
                                            outcomeRecordedForThisPlayback = true
                                            recordPlaybackOutcome(success = false)
                                        }
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
                        if (!outcomeRecordedForThisPlayback) {
                            outcomeRecordedForThisPlayback = true
                            recordPlaybackOutcome(success = false)
                        }
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

    /** Registers a live network-change listener so a WiFi<->cellular handoff (or losing/gaining
     * any network) triggers an immediate reconnect attempt instead of waiting for the stream to
     * eventually error out or stall on its own — same underlying reconnect (scheduleRetry) the
     * error/stall paths already use, just triggered proactively. `onAvailable` fires for the
     * new default network once the handoff completes; comparing against lastKnownNetwork avoids
     * reacting to the very first callback (registration itself always fires once immediately)
     * or to capability-only changes on the same network (e.g. signal strength ticking) that
     * registerDefaultNetworkCallback does NOT fire for anyway (it's Network-identity-based, not
     * capability-based). */
    private fun setupNetworkChangeReconnect() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val previous = lastKnownNetwork
                lastKnownNetwork = network
                // First callback on registration is just "here's the current network" — not an
                // actual change to react to.
                if (previous == null) return
                if (previous == network) return
                com.iptvapp.IptvApplication.logPlaybackEvent(
                    applicationContext,
                    "NETWORK CHANGED: streamId=$streamId reconnecting proactively"
                )
                runOnUiThread {
                    retryJob?.cancel()
                    retryCount = 0
                    scheduleRetry()
                }
            }
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    // Ghost Channel Radar / Provider Health Weather Map — single shared recording point for both
    // real playback attempts (STATE_READY = success) and failures (onPlayerError / the 20s
    // buffer-stall watchdog, both of which funnel into scheduleRetry). Live-channel only for the
    // per-channel radar (ChannelReliabilityEntity is meaningless for VOD/one-off content), but the
    // per-provider hourly weather map records for both, since a flaky provider is just as flaky
    // serving a movie as a live channel. Fire-and-forget on lifecycleScope — must never block or
    // crash playback over a local DB write.
    private fun recordPlaybackOutcome(success: Boolean) {
        lifecycleScope.launch {
            try {
                if (!isVod && streamId != -1) {
                    repository.recordChannelOutcome(streamId, success)
                }
                repository.recordProviderHourlyOutcome(serverIndex, success)
            } catch (_: Exception) {
                // best-effort, matches BandwidthTracker.flush()'s swallow-and-continue
            }
            // Community Stream Health Feed (opt-in, default OFF) — only a genuine failure is
            // worth reporting to the crowd-sourced feed (a lone success tells other users
            // nothing); gated on the toggle here so this is the one and only Firestore write
            // call site, and it's a no-op with the toggle off.
            if (!success && !isVod) {
                reportCommunityHealthEventIfEnabled("PLAYBACK_ERROR")
            }
        }
    }

    /** Fire-and-forget, opt-in-gated write to the Community Stream Health Feed — see
     * CommunityHealthManager kdoc. Every call site funnels through here so the
     * communityHealthSharingEnabled check can never be forgotten at a new call site. */
    private fun reportCommunityHealthEventIfEnabled(errorType: String) {
        lifecycleScope.launch {
            try {
                if (!prefs.communityHealthSharingEnabled.first()) return@launch
                val hostHash = communityHealthManager.hashProviderHost(resolveActiveServerUrl()) ?: return@launch
                communityHealthManager.reportEvent(hostHash, streamTitle.ifBlank { "Unknown channel" }, errorType)
            } catch (_: Exception) {
                // fire-and-forget, never affects playback
            }
        }
    }

    /** Best-effort "N other users reported issues with this channel recently" banner — read-side
     * of the Community Stream Health Feed, also opt-in-gated (no point reading if the user isn't
     * opted into sharing, and it avoids a Firestore read for users who never touched the toggle).
     * Informational only, never blocks or delays playback. */
    private fun maybeShowCommunityHealthBanner() {
        if (isVod || streamId == -1) return
        lifecycleScope.launch {
            try {
                if (!prefs.communityHealthSharingEnabled.first()) return@launch
                val hostHash = communityHealthManager.hashProviderHost(resolveActiveServerUrl()) ?: return@launch
                val channelName = streamTitle.ifBlank { return@launch }
                val count = communityHealthManager.recentEventCount(hostHash, channelName)
                if (count > 0) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "$count other ${if (count == 1) "user" else "users"} reported issues with this channel recently",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: Exception) {
                // informational only
            }
        }
    }

    /** The server URL of whichever provider is currently active (serverIndex == -1 -> primary,
     * else the matching extra server) — same serverIndex convention used throughout the app
     * (ChannelEntity/BandwidthUsageEntity/etc). Used only to derive a privacy-safe host hash,
     * never uploaded itself. */
    private suspend fun resolveActiveServerUrl(): String {
        return if (serverIndex == -1) {
            prefs.credentials.first().serverUrl
        } else {
            prefs.getExtraServersWithNick().getOrNull(serverIndex)?.getOrNull(0) ?: ""
        }
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
            // A member's own provider not having this VOD/episode's catalog ID looks identical to
            // any other dead-stream give-up here (getMergedVodStreamUrl/getVodStreamUrl just build
            // a URL string with no upfront validation), so the generic status label above is the
            // only signal — easy to miss, and it can take a while to appear since it's behind
            // maxRetries attempts. Watch Party members deserve the same immediate, specific message
            // the LIVE channel-follow path already shows on failure, not a silent multi-attempt wait.
            if (isPartyMember && !isApplyingRemoteUpdate) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Couldn't load \"$streamTitle\" — not available on your provider",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        // Live channels previously had no give-up path at all here — retry just kept ramping up
        // to its ceiling and holding there forever, even against a genuinely dead provider. Before
        // finally giving up, try failing over to the same real channel on another configured
        // provider (matched by normalized name — see ChannelNameMatcher kdoc for why exact provider
        // IDs can't be compared across panels) rather than just showing an error the user would
        // have to manually work around by picking the other provider's copy themselves.
        if (!isVod && retryCount >= maxRetries) {
            retryJob?.cancel()
            retryJob = lifecycleScope.launch {
                val match = try {
                    repository.findFailoverChannel(streamTitle, serverIndex)
                } catch (_: Exception) { null }
                if (match != null) {
                    val (matchServerIndex, matchStreamId, matchName) = match
                    com.iptvapp.IptvApplication.logPlaybackEvent(
                        applicationContext,
                        "FAILOVER: streamId=$streamId ($streamTitle) on serverIndex=$serverIndex -> " +
                            "streamId=$matchStreamId ($matchName) on serverIndex=$matchServerIndex, after $maxRetries failed attempts"
                    )
                    Toast.makeText(
                        this@PlayerActivity,
                        "Switched provider for \"$matchName\" — the original had an error",
                        Toast.LENGTH_LONG
                    ).show()
                    bandwidthTracker?.updateServerIndex(matchServerIndex)
                    serverIndex = matchServerIndex
                    streamId = matchStreamId
                    mergedStreamId = if (matchServerIndex == -1) -1 else matchStreamId
                    streamUrl = try {
                        if (matchServerIndex == -1) repository.getLiveStreamUrl(matchStreamId)
                        else repository.getMergedLiveStreamUrl(matchServerIndex, matchStreamId)
                    } catch (_: Exception) {
                        binding.tvRetryStatus.text = "Stream unavailable after $maxRetries attempts"
                        binding.tvRetryStatus.visibility = View.VISIBLE
                        return@launch
                    }
                    retryCount = 0
                    binding.tvRetryStatus.visibility = View.GONE
                    player?.let {
                        it.setMediaItem(MediaItem.fromUri(streamUrl))
                        it.prepare()
                        it.playWhenReady = true
                    }
                } else {
                    binding.tvRetryStatus.text = "Stream unavailable after $maxRetries attempts"
                    binding.tvRetryStatus.visibility = View.VISIBLE
                    com.iptvapp.IptvApplication.logPlaybackEvent(
                        applicationContext,
                        "RETRY GIVE UP: isVod=$isVod streamId=$streamId url=$streamUrl attempts=$retryCount (no failover match for \"$streamTitle\")"
                    )
                }
            }
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
                // Live: ramps up then holds at a ceiling — both the step size and ceiling scale
                // with the user's chosen reconnect speed. "normal" is the original hardcoded
                // behavior (2s steps, 30s ceiling), unchanged for anyone who hasn't touched the
                // new setting.
                val (stepMs, ceilingMs) = when (liveReconnectSpeed) {
                    "aggressive" -> 1000L to 10_000L
                    "patient" -> 3000L to 60_000L
                    else -> 2000L to 30_000L
                }
                (stepMs * (retryCount + 1)).coerceAtMost(ceilingMs)
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
                val proxy = com.iptvapp.cast.IptvCastProxy(localIp, appContext = applicationContext).also {
                    it.start()
                    castProxy = it
                }
                when {
                    // A recorded file is a local content:// or file:// path, meaningless to the
                    // Cast receiver on its own — proxyLocalFile actually reads and serves its
                    // bytes (with Range support for seeking), unlike proxyUrl which just forwards
                    // an upstream HTTP request.
                    isRecordingPlayback -> proxy.proxyLocalFile(directUrl)
                    isRawTsLive -> proxy.proxyLiveUrl(directUrl, "ExoPlayerLib/1.4.1 (Linux; Android)")
                    else -> proxy.proxyUrl(directUrl)
                }
            } else {
                directUrl
            }

            Log.d("CastDebug", "localIp=$localIp castUrl=$castUrl")

            val contentType = when {
                isRecordingPlayback && directUrl.contains(".ts", ignoreCase = true) -> "video/mp2t"
                isRecordingPlayback -> "video/mp4"
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
                if (fromUser) {
                    player?.seekTo(progress.toLong())
                    notifyPartyStateChange()
                }
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
        // Mirrors hideRunnable's isVod branch — the seek bar auto-hides with the rest of the
        // transient controls now, so it needs to come back here too, not just once at playback
        // start (startSeekBarUpdater already set it VISIBLE that one time, but every hide/show
        // cycle after that needs to re-show it explicitly, same as every other control below).
        if (isVod) binding.vodSeekContainer.visibility = View.VISIBLE
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
            // 0 by default (instant, unchanged behavior). A non-zero Channel Change Speed
            // setting waits here for that long — if another zap comes in before it elapses,
            // channelSwitchJob?.cancel() above kills this job before it ever touches streamId/
            // currentIndex or resolves a URL, so mashing D-pad up/down settles on one real
            // network resolve + player reload instead of firing one per press.
            val debounceMs = prefs.channelZapDebounceMs.first()
            if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs.toLong())
            streamId = channel.streamId
            streamTitle = channel.name
            // Zapping via the channel changer (D-pad/on-screen zones) previously never updated
            // "last played" — only the channel HomeActivity originally launched into did — so a
            // cold boot after zapping around would revert to whatever was playing before you
            // started changing channels, not where you actually ended up.
            prefs.setLastPlayedChannel(-1, channel.streamId)
            prefs.setLivePlaybackActive(-1)
            val url = repository.getLiveStreamUrl(channel.streamId)
            binding.tvChannelTitle.text = streamTitle
            val idx = channels.indexOfFirst { it.streamId == channel.streamId }
            if (idx >= 0) currentIndex = idx
            loadStream(url)
            notifyPartyChannelChange()
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
                val debounceMs = prefs.channelZapDebounceMs.first()
                if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs.toLong())
                bandwidthTracker?.updateServerIndex(channel.serverIndex)
                serverIndex = channel.serverIndex
                mergedStreamId = channel.streamId
                streamTitle = "${channel.name} · ${channel.serverNickname}"
                // Same fix as playChannel() above — zapping through a merged provider's channel
                // changer must update "last played" too, or a cold boot after zapping reverts to
                // whichever channel was playing before you started changing channels.
                prefs.setLastPlayedChannel(channel.serverIndex, channel.streamId)
                prefs.setLivePlaybackActive(channel.serverIndex)
                val url = repository.getMergedLiveStreamUrl(channel.serverIndex, channel.streamId)
                binding.tvChannelTitle.text = streamTitle
                val idx = mergedChannels.indexOfFirst { it.streamId == channel.streamId }
                if (idx >= 0) mergedCurrentIndex = idx
                loadStream(url)
                notifyPartyChannelChange()
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
            // Fast-forward/rewind only fires when the overlay is CLOSED (a bare Left/Right press
            // with nothing else on screen, so it's unambiguous the user means "skip"). With the
            // overlay open, Left/Right instead move focus freely between its buttons/seek bar,
            // same as Up/Down already do above — explicitly requested, since seeking on every
            // Left/Right while the overlay (and its own focusable seek bar) was open made it
            // impossible to D-pad over to Back/CC/Stats without also skipping the movie.
            // For Live TV (not VOD), Left/Right never opens the overlay either — only OK/Enter
            // does that; a bare Left/Right with the overlay hidden is simply swallowed.
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                isOverlayVisible -> { resetHideTimer(); super.onKeyDown(keyCode, event) }
                isVod -> { resetHideTimer(); player?.seekTo(((player?.currentPosition ?: 0L) - nextVodSkipAmountMs()).coerceAtLeast(0L)); notifyPartyStateChange(); true }
                else -> true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                isOverlayVisible -> { resetHideTimer(); super.onKeyDown(keyCode, event) }
                isVod -> { resetHideTimer(); player?.seekTo(((player?.currentPosition ?: 0L) + nextVodSkipAmountMs()).coerceAtMost(player?.duration ?: Long.MAX_VALUE)); notifyPartyStateChange(); true }
                else -> true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (player?.isPlaying == true) player?.pause() else player?.play()
                updatePlayPauseButton(); notifyPartyStateChange(); true
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

    // Previously always Rational(16, 9) regardless of the actual stream — a 4:3 SD channel or
    // portrait content would get force-fit into a 16:9 PiP window instead of matching its real
    // shape. Falls back to 16:9 only when the player doesn't know the video size yet (very early
    // in playback) or reports something outside Android's supported PiP aspect ratio range
    // (roughly 1:2.39 to 2.39:1) — PictureInPictureParams.Builder.setAspectRatio throws
    // IllegalArgumentException outside that range, so this must be clamped defensively.
    private fun pipAspectRatio(): Rational {
        val videoSize = player?.videoSize
        val width = videoSize?.width ?: 0
        val height = videoSize?.height ?: 0
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height.toFloat()
        if (ratio < 1f / 2.39f || ratio > 2.39f) return Rational(16, 9)
        return Rational(width, height)
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(pipAspectRatio())
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
        skipNextHandler.removeCallbacks(skipNextRunnable)
        stopHealthBadge()
        if (!isChangingConfigurations) {
            player?.release()
            player = null
            bandwidthTracker?.stop()
            // Fire-and-forget on a process-wide scope: lifecycleScope may already be cancelling
            // by the time onStop runs, same reasoning as the Trakt stop-scrobble call elsewhere
            // in this Activity — a plain Room write from GlobalScope is safe here.
            val tracker = bandwidthTracker
            if (tracker != null) kotlinx.coroutines.GlobalScope.launch { tracker.flush() }
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
        if (!isChangingConfigurations) {
            partyListenerReg?.remove()
            partyHeartbeatHandler.removeCallbacks(partyHeartbeatRunnable)
            pollListenerReg?.remove()
            pollAutoCloseJob?.cancel()
            rewatchNotesListenerReg?.remove()
            val code = partyCode
            if (code.isNotEmpty()) {
                if (isPartyHost) {
                    kotlinx.coroutines.GlobalScope.launch { watchPartyManager.endParty(code) }
                } else if (isPartyMember) {
                    kotlinx.coroutines.GlobalScope.launch { watchPartyManager.leaveParty(code) }
                }
            }
        }
        if (!isVod && !isChangingConfigurations) {
            // Fire-and-forget: this Activity is finishing, so there's no lifecycleScope left to
            // await, but a plain DataStore write from a short-lived GlobalScope launch is safe.
            kotlinx.coroutines.GlobalScope.launch { prefs.clearLivePlaybackActive() }
        }
        upNextJob?.cancel()
        castProxy?.stop()
        castProxy = null
        hideHandler.removeCallbacks(hideRunnable)
        osdHandler.removeCallbacks(hideOsdRunnable)
        indicatorHandler.removeCallbacks(hideBrightnessRunnable)
        indicatorHandler.removeCallbacks(hideVolumeRunnable)
        recordBlinkAnimator?.cancel()
        unregisterPipActionReceiver()
        networkCallback?.let { cb ->
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
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
