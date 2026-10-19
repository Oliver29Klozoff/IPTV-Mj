@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.iptvapp.ui.player

import com.iptvapp.R
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.util.Log
import android.widget.Toast
import com.iptvapp.service.AudioOnlyService
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import com.iptvapp.util.ChannelAudioDelay
import com.iptvapp.util.ChannelAudioDelayState
import com.iptvapp.util.ExternalSubtitles
import com.iptvapp.util.OpenSubtitlesClient
import com.iptvapp.util.PictureMode
import com.iptvapp.util.SmartCatchUp
import com.iptvapp.util.SportsMode
import com.iptvapp.util.isLargeScreenDevice
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
import com.iptvapp.ui.player.timeshift.LiveTimeshiftSession
import com.iptvapp.ui.player.timeshift.TimeshiftCopy
import com.iptvapp.ui.player.timeshift.TimeshiftEpg
import com.iptvapp.ui.player.timeshift.TimeshiftLimits
import com.iptvapp.ui.player.timeshift.TimeshiftLoop
import com.iptvapp.ui.player.timeshift.TimeshiftNavigator
import com.iptvapp.ui.player.timeshift.TimeshiftPlaybackState
import com.iptvapp.ui.player.timeshift.TimeshiftSessionGate
import com.iptvapp.ui.player.timeshift.TimeshiftSource
import com.iptvapp.ui.player.timeshift.TimeshiftStore
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import okhttp3.OkHttpClient
import javax.inject.Inject

@AndroidEntryPoint
class PlayerActivity : AppCompatActivity(), com.iptvapp.remote.RemoteControlHooks.Tuner {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private lateinit var quickPeek: QuickPeekPanel
    // Opened the full guide from Quick Peek: on coming back, onStart holds the stream until the
    // guide's answer arrives, so a pick doesn't open the old channel first (one connection).
    private var fullGuidePending = false
    private var heldStartForGuide = false
    // The rest of a touch whose press closed Quick Peek, so it doesn't also act on the picture.
    private var peekSwallowGesture = false
    // A touch that started on Quick Peek: its views get it, the player's swipe and double-tap don't.
    private var peekOwnsGesture = false

    // v6.93: the user's accent (play button, Sleep countdown, Quick Zap) — cyan until it loads.
    private var playerAccent = com.iptvapp.util.RackAccent.Accent(0xFF06B6D4.toInt(), null)
    // Lock: touches are ignored (except the unlock button) so the screen can't be bumped.
    private var isLocked = false
    private val hideUnlockRunnable = Runnable { binding.btnUnlock.visibility = View.GONE }

    private var isOverlayVisible = false
    private var isHealthBadgeActive = false

    private val hideRunnable = Runnable {
        isOverlayVisible = false
        binding.liveProgramBar.visibility = View.GONE
        binding.osdTopBar.visibility = View.GONE
        binding.playerTopScrim.visibility = View.GONE
        binding.playerBottomScrim.visibility = View.GONE
        binding.epgOverlay.visibility = View.GONE
        binding.btnBack.visibility = View.GONE
        binding.btnGuide.visibility = View.GONE
        binding.btnPlayPause.visibility = View.GONE
        binding.bottomControls.visibility = View.GONE
        binding.btnDvrRewind.visibility = View.GONE
        binding.btnDvrLive.visibility = View.GONE
        binding.btnLiveFwd10.visibility = View.GONE
        stopTimeshiftTicker()
        binding.btnRecordDot.visibility = View.GONE
        binding.btnVodBack.visibility = View.GONE
        binding.btnVodFwd.visibility = View.GONE
        binding.btnVodFwdBig.visibility = View.GONE
        binding.btnCast.visibility = View.GONE
        binding.bufferHealthBadge.visibility = View.GONE
        // VOD's seek bar/elapsed-remaining time used to be deliberately exempt from auto-hide
        // (see startSeekBarUpdater's original comment) so it stayed visible the whole time like
        // some video apps do — explicitly asked to auto-hide with everything else instead, same
        // as every other transient control here.
        binding.vodSeekContainer.visibility = View.GONE
        placeSportsChrome(false)
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
    // "Last Channel" recall (like a cable remote's LAST/RECALL button) — a single previous-
    // channel slot, snapshotted right before playChannel/playMergedChannel overwrite the current
    // identity. Deliberately in-memory only (not persisted to PreferencesManager like
    // LAST_PLAYED_STREAM_ID/SERVER_INDEX) — recall only makes sense within a live session; a
    // stale previous channel surviving a cold app restart would be confusing, not useful. Only
    // ever set for LIVE channel changes (playChannel/playMergedChannel), never VOD — recalling
    // "back to the movie you were watching" doesn't fit the same button as channel-surfing.
    private var previousServerIndex: Int = -1
    private var previousStreamId: Int = -1
    // The channel whose list position zapping goes by: the one asked for, which differs from
    // streamId when a more reliable copy is playing in its place. Swapped with LAST like the rest.
    private var currentZapStreamId: Int = -1
    private var previousZapStreamId: Int = -1
    private var previousTitle: String = ""
    private var isVod: Boolean = false
    // A locally recorded file played back from the Recordings screen — always launched with
    // is_vod=true too (a finished recording behaves like VOD: seekable, no live-edge concept, and
    // Trakt-scrobblable via the existing isVod movie-title path using programTitle/channelName as
    // stream_title). This flag exists only to pick a different ExoPlayer data source in
    // buildPlayer(), since a local file:// / content:// path isn't an HTTP resource.
    private var isRecordingPlayback: Boolean = false
    // This channel's sound offset. The video renderer reads it on each frame.
    private val channelAudioDelay = ChannelAudioDelayState()
    private var appliedAudioDelayKey: String? = null
    // A subtitle added for this movie or episode, when the file itself had none.
    private var externalSubtitle: MediaItem.SubtitleConfiguration? = null
    private var externalSubtitlePath: String? = null
    private var externalSubtitleMime: String = MimeTypes.APPLICATION_SUBRIP
    private var externalSubtitleLanguage: String = "en"
    private var subtitleOfferShown: Boolean = false
    private var subtitleGeneration: Int = 0
    private var subtitleJob: kotlinx.coroutines.Job? = null
    // Captured when the file picker opens, so a programme change while it is open is rejected.
    private var subtitlePickGeneration: Int = -1
    private var subtitlePickUrl: String? = null
    private var subtitlePickerPending: Boolean = false
    private var resumePausedAfterPicker: Boolean = false
    private val pickSubtitleFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSubtitleFile(uri)
    }
    // Live timeshift (pause / rewind / go live). NONE means the player is on the provider URL
    // with no local buffer. NATIVE means that URL already has a usable DVR window. LOCAL means
    // the player is reading a bounded temp playlist and LiveTimeshiftSession holds the only
    // provider connection. Not a recording: files stay in cacheDir/timeshift.
    private val timeshiftGate = TimeshiftSessionGate()
    private var timeshiftSession: LiveTimeshiftSession? = null
    private var timeshiftMode = TimeshiftSource.NONE
    private var timeshiftState = TimeshiftPlaybackState.LIVE
    private var timeshiftSeeking = false
    private var timeshiftReturning = false
    // D-pad seeks have no touch-up. Hold the thumb until the player position catches up.
    private var liveSeekHoldUntil = 0L
    private var liveSeekBound = false
    private var timeshiftDirectFallback = false
    private var timeshiftFrozen = false
    /** Wall time of the retained window's end. Zero while the pump is still following live. */
    private var timeshiftFrozenAtMs = 0L
    private var timeshiftStarting = false
    private var timeshiftStartSettled = false
    private var timeshiftAutoplay = true
    /** Behind-live offset to restore after a sports rebuild of a native DVR window. TIME_UNSET means none. */
    private var pendingNativeBehindMs = C.TIME_UNSET
    private var timeshiftRepairs = 0
    private var timeshiftWatchdog: Job? = null
    private var timeshiftEpg: List<TimeshiftEpg.Show> = emptyList()
    private var liveEdgeEpgText: String = ""
    private var liveSkipPendingMs = 0L
    private var liveSkipLong = false
    private var timeshiftUiRunning = false
    // Catch-up: a past programme replayed from the provider's archive. It runs as VOD (seek bar,
    // pause, skip buttons) but the provider serves it from a timeshift URL that begins at a whole
    // minute, so positions here are relative to the programme start: catchupOffsetSec is where the
    // current URL begins, and a seek ExoPlayer can't do inside that URL reopens it at the target
    // minute (see vodSeekTo). Movie-only features — resume progress, Trakt, watch party, finding
    // the "same movie" on another provider — stay off.
    // Audio only: the activity's own player is released and AudioOnlyService plays the channel
    // (see enterAudioOnly). Live channels only.
    private var audioOnly = false
    private var isCatchup = false
    private var catchupStartSec = 0L
    private var catchupDurationMin = 0
    private var catchupOffsetSec = 0L
    private var catchupChannelName = ""
    private var catchupLiveUrl = ""
    // Seconds-precise remainder of a seek that reopened the URL at a whole minute; applied once
    // the new URL is ready if the stream turns out to be seekable.
    private var catchupPendingSeekMs = 0L
    // Position to come back to after onStop released the player (backgrounding, screen off).
    private var catchupResumeMs = 0L
    private var catchupReloadJob: kotlinx.coroutines.Job? = null
    // Start Over: the live channel was switched in place to a replay of the show on now (see
    // startOverCurrentShow); LIVE / the show ending returns to the broadcast (backToLive).
    private var startedOverFromLive = false
    private var liveTitleBeforeStartOver = ""
    // The show on now from the overlay's guide lookup, tied to the channel it was looked up for.
    private data class LiveShow(val streamId: Int, val startMs: Long, val stopMs: Long, val title: String)
    private var liveNowShow: LiveShow? = null
    // Sports Mode for this viewing. Null follows the saved setting. The fast buffer is never
    // the default for ordinary live TV.
    private var sportsSessionOn: Boolean? = null
    private var scoreOverlaySession: Boolean? = null
    private var sportsLoadControlOn = false
    /** How far behind the newest local timeshift piece live plays. Fixed with the load control. */
    private var localLiveCushionMs = 0L
    private var sportsRebuildAfterReady = false
    private var appliedDisplayModeId = 0
    // Catch-up offer is per airing. Watch Live remembers the programme start and does not seek.
    private var smartCatchupDismissedStartMs = 0L
    private var smartCatchupArchive: Boolean? = null
    // Whose archive flag smartCatchupArchive is: (serverIndex, that provider's stream id).
    private var smartCatchupArchiveKey: Pair<Int, Int>? = null

    /** A channel that keeps past shows, on any provider: its name. */
    private data class ArchivedChannel(val name: String)

    /** [server]/[id]'s channel when its provider keeps an archive for it, else null. */
    private suspend fun archivedChannel(server: Int, id: Int): ArchivedChannel? =
        if (server == -1) repository.getChannelById(id)?.takeIf { it.tvArchive == 1 }?.let { ArchivedChannel(it.name) }
        else repository.getMergedChannelByIndexAndId(server, id)?.takeIf { it.tvArchive == 1 }?.let { ArchivedChannel(it.name) }

    /** The playing channel's id on its own provider: streamId on the main one, mergedStreamId elsewhere. */
    private fun liveCopyId(): Int = if (serverIndex == -1) streamId else mergedStreamId
    // Where a debounced reopen is headed, so further skips add on to it rather than to the old
    // position (-1 = none pending).
    private var catchupPendingTargetMs = -1L
    // True while the seek bar thumb is held, so the 1 s updater doesn't drag it back.
    private var seekBarDragging = false
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

    // Feed scores and the automatic backup feed: see FeedTracker and XtreamRepository.findBackupFeed.
    private val feedTracker = FeedTracker()
    /** Copies already left behind on this channel, so a backup never bounces back to them. */
    private val backupFeedsTried = mutableSetOf<Pair<Int, Int>>()
    /** True while loadStream runs for a backup switch, so the tracker keeps its switch budget. */
    private var backupSwitchOpening = false
    private var backupSwitchJob: kotlinx.coroutines.Job? = null
    /** A seek rebuffers by design. It is not the feed stalling. */
    private var lastSeekAtMs = 0L
    private var feedFlushJob: kotlinx.coroutines.Job? = null

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
    @Inject lateinit var castRelay: com.iptvapp.sync.CastRelayManager

    // Cast-to-device (see CastRelayManager kdoc) — separate scan launcher from anything Watch
    // Party uses (that's a typed-code dialog, not a QR scan) and from LoginActivity's own QR
    // scanner (that one's for restoring a backup, a different payload/purpose entirely, and
    // stays on zxing - see CastQrScanActivity kdoc for why this one moved off it).
    private val castScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = result.data?.getStringExtra(CastQrScanActivity.EXTRA_CODE) ?: return@registerForActivityResult
        sendCastToScannedCode(code)
    }
    private val castCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCastScanner() else {
            // No camera is no longer a dead end — the words on the receiver's screen work too.
            Toast.makeText(this, "No camera access — use \"Enter code manually\" instead", Toast.LENGTH_LONG).show()
            showManualCastCodeDialog()
        }
    }
    @Inject lateinit var playbackHandoffManager: com.iptvapp.sync.PlaybackHandoffManager
    @Inject lateinit var rewatchNotesManager: com.iptvapp.sync.RewatchNotesManager
    @Inject lateinit var communityHealthManager: com.iptvapp.sync.CommunityHealthManager
    @Inject lateinit var db: com.iptvapp.data.local.IptvDatabase
    private var bandwidthTracker: BandwidthTracker? = null
    private var playbackDataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null
    private val pictureProfiles = PictureProfiles()
    // Lazy: it reads applicationContext, which an activity has only once it is attached (onCreate).
    private val zapPreloader by lazy { ZapPreloader(this) }
    private var zapPreloadJob: Job? = null
    private var pictureTitleJob: Job? = null
    /** Set when a live channel opens. Its first READY schedules the neighbor preload. */
    private var zapPreloadWhenReady = false
    private val ZAP_PRELOAD_SETTLE_MS = 5_000L
    private var zapMemoryPressure = false
    private var appliedPictureName: String? = null
    private var appliedPictureProfile: Any? = null
    private var pictureProgrammeTitle: String = ""

    // ─── Cast to a Device (see CastRelayManager kdoc) ──────────────────────────
    // Set when THIS Activity instance is the RECEIVER (launched off a scanned cast, not by the
    // user tuning in normally) — lets the same QR/session keep delivering channel changes instead
    // of being single-use, so casting a second channel doesn't require generating and rescanning
    // a brand new QR code every time.
    private var castSessionCode: String = ""
    private var castSessionListenerReg: com.google.firebase.firestore.ListenerRegistration? = null

    /** The channel pack that came with a received cast, and where we are in it. Only non-empty
     * when THIS device is the receiver — see castZap for why a receiver can't use the normal
     * channel lists. */
    private var castChannels: List<com.iptvapp.sync.CastRelayManager.CastChannel> = emptyList()
    private var castIndex: Int = 0

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
    // Set true when this device is playing a title-search fallback match instead of the host's
    // exact catalog id (see SettingsActivity/TvSettingsActivity's offerWatchPartyVodTitleFallback)
    // — the host's content identity will then never match this device's own substituted
    // streamId/serverIndex on any future Firestore update, which would otherwise make
    // applyRemotePartyState treat every single heartbeat as "content changed, reload" and
    // re-trigger the whole fallback/toast cycle in a loop. Only position/play-state sync applies
    // while this is true; the content itself is intentionally left alone.
    private var isPartyContentSubstituted: Boolean = false
    private val partyHeartbeatHandler = Handler(Looper.getMainLooper())
    private var partyLaunchCode: String = ""
    // Set from the "watch_party_start_paused" intent extra — a party joined via a fresh
    // PlayerActivity launch (SettingsActivity/TvSettingsActivity's launchPlayerForWatchParty)
    // where the host hasn't pressed play yet (see WatchPartyManager.startParty's paused-start
    // kdoc). onStart() always sets playWhenReady = true otherwise; this overrides that so the
    // member doesn't watch a flash of playback before a later sync would correct it.
    private var partyStartPaused: Boolean = false
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
    // When the armed sleep timer fires (0 = none) — handed to AudioOnlyService on Listen.
    private var sleepDeadlineMs = 0L
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
            stopCastWatch()
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
        catchupDurationMin = intent.getIntExtra("catchup_duration_min", 0)
        // A replay on any provider: the main one by streamId, another by serverIndex + mergedStreamId.
        if (catchupDurationMin > 0 && liveCopyId() != -1) {
            isCatchup = true
            isVod = true
            catchupStartSec = intent.getLongExtra("catchup_start_sec", 0L)
            catchupChannelName = intent.getStringExtra("catchup_channel_name") ?: ""
            val server = serverIndex
            val id = liveCopyId()
            lifecycleScope.launch {
                catchupLiveUrl = try {
                    if (server == -1) repository.getLiveStreamUrl(id) else repository.getMergedLiveStreamUrl(server, id)
                } catch (_: Exception) { "" }
            }
        }
        resumePositionMs = intent.getLongExtra("resume_ms", 0L)
        epIds    = intent.getStringArrayListExtra("ep_ids")    ?: emptyList()
        epTitles = intent.getStringArrayListExtra("ep_titles") ?: emptyList()
        epExts   = intent.getStringArrayListExtra("ep_exts")   ?: emptyList()
        epIndex  = intent.getIntExtra("ep_index", -1)
        traktSeriesName = intent.getStringExtra("series_name") ?: ""
        traktSeason  = intent.getIntExtra("season_num", -1)
        traktEpisode = intent.getIntExtra("episode_num", -1)
        episodeSeriesId = intent.getIntExtra("series_id", -1)
        castSessionCode = intent.getStringExtra("cast_session_code") ?: ""
        // The pack that lets a RECEIVER change channel without an account of its own.
        castChannels = com.iptvapp.sync.CastRelayManager.decodePack(intent.getStringExtra("cast_channels"))
        castIndex = castChannels.indexOfFirst { it.url == streamUrl }.takeIf { it >= 0 } ?: 0
        if (castSessionCode.isNotEmpty()) attachCastSessionListener()
        partyLaunchCode = intent.getStringExtra("watch_party_code") ?: ""
        savedInstanceState?.let { state ->
            if (state.containsKey("resume_position_ms")) {
                resumePositionMs = state.getLong("resume_position_ms")
                resumePausedAfterPicker = state.getBoolean("subtitle_picker_paused", false)
                subtitlePickerPending = true
            }
            subtitleGeneration = state.getInt("ext_sub_generation", 0)
            subtitlePickGeneration = state.getInt("ext_sub_pick_gen", -1)
            subtitlePickUrl = state.getString("ext_sub_pick_url")
            state.getString("ext_sub_path")?.let { path ->
                val file = File(path)
                if (file.isFile) {
                    externalSubtitlePath = path
                    externalSubtitleMime = state.getString("ext_sub_mime") ?: MimeTypes.APPLICATION_SUBRIP
                    externalSubtitleLanguage = state.getString("ext_sub_lang") ?: "en"
                    externalSubtitle = subtitleConfiguration(file)
                    subtitleOfferShown = true
                }
            }
        }
        isPartyContentSubstituted = intent.getBooleanExtra("watch_party_content_substituted", false)
        partyStartPaused = intent.getBooleanExtra("watch_party_start_paused", false)

        setupWatchPartyButton()
        if (isCatchup) binding.btnWatchParty.visibility = View.GONE
        if (partyLaunchCode.isNotEmpty()) joinWatchParty(partyLaunchCode, showToast = false)
        updateRewatchNotesButtonVisibility()
        binding.btnAudioOnly.visibility = if (isVod) View.GONE else View.VISIBLE
        binding.btnAudioOnly.setOnClickListener { enterAudioOnly() }
        binding.btnShowPicture.setOnClickListener { exitAudioOnly() }

        setupChannelZones()
        setupVodSkipButtons()
        setupSportsAndCatchUp()
        setupGestureDetector()
        setupNetworkChangeReconnect()
        binding.tvChannelTitle.text = streamTitle
        binding.btnBack.setOnClickListener { finish() }

        if (!isVod) {
            lifecycleScope.launch { prefs.setLivePlaybackActive(serverIndex) }
            lifecycleScope.launch { liveReconnectSpeed = prefs.liveReconnectSpeed.first() }
            reportLiveChannelForHandoff()
        }

        val streamIds = intent.getIntArrayExtra("stream_ids")
        // Home substituted a more reliable copy: zap from where the requested channel sits.
        val zapFromStreamId = intent.getIntExtra("zap_from_stream_id", -1)
        currentZapStreamId = if (zapFromStreamId != -1) zapFromStreamId else streamId
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
                currentIndex = channels.indexOfFirst { it.streamId == zapFromStreamId }
                    .takeIf { zapFromStreamId != -1 && it >= 0 }
                    ?: channels.indexOfFirst { it.streamId == streamId }
                    // Playing a more reliable copy of a channel that isn't in this list itself (Home
                    // substituted it): zap from where its same-named copy sits, or up/down would do nothing.
                    .takeIf { it >= 0 }
                    ?: repository.getChannelById(streamId)?.name?.let { playingName ->
                        channels.indexOfFirst { com.iptvapp.util.ChannelNameMatcher.matches(it.name, playingName) }
                    } ?: -1
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
        binding.btnLock.setOnClickListener { setLocked(true) }
        binding.btnUnlock.setOnClickListener { setLocked(false) }
        // Lock guards a touch screen; a remote-only device (the Shield) has nothing to lock.
        if (resources.configuration.touchscreen == android.content.res.Configuration.TOUCHSCREEN_NOTOUCH) {
            binding.btnLock.visibility = View.GONE
        }
        lifecycleScope.launch {
            playerAccent = com.iptvapp.util.RackAccent.load(prefs)
            paintPlayButton()
            if (::quickPeek.isInitialized) quickPeek.setAccent(playerAccent)
            binding.seekBar.progressTintList = android.content.res.ColorStateList.valueOf(playerAccent.start)
        }
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
                episodeId = epIds.getOrNull(epIndex) ?: "", seriesName = traktSeriesName
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
            .setItems(arrayOf("Start Watch Party", "Join Watch Party", "Cast to a Device")) { _, which ->
                when (which) {
                    0 -> startWatchParty()
                    1 -> showJoinPartyCodeDialog()
                    2 -> castToDeviceClicked()
                }
            }
            .show()
    }

    // Cast to a device with no IPTV account (see CastRelayManager kdoc) — unlike Watch Party
    // (which needs the other side to have its own account to resolve a shared content identity
    // against), this seals an already-playable link to a key the receiver showed on its screen,
    // so the real Xtream credentials never leave this device.
    // Offers resending to the same already-connected device (its session stays open — see
    // attachCastSessionListener) so changing the channel being cast doesn't mean pairing with a
    // brand new code every single time.
    private fun castToDeviceClicked() {
        val remembered = castRelay.lastSentCode
        if (remembered != null) {
            AlertDialog.Builder(this)
                .setTitle("Cast to a Device")
                .setItems(arrayOf("Cast to same device", "Scan a QR code", "Enter code manually")) { _, which ->
                    when (which) {
                        0 -> sendCastToScannedCode(remembered)
                        1 -> requestCastCameraAndScan()
                        else -> showManualCastCodeDialog()
                    }
                }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Cast to a Device")
                .setItems(arrayOf("Scan a QR code", "Enter code manually")) { _, which ->
                    if (which == 0) requestCastCameraAndScan() else showManualCastCodeDialog()
                }
                .show()
        }
    }

    /** Typing the words shown on the receiver, for when the camera won't focus on a TV screen
     * (glare, distance, or a set too big to get the whole code in frame). The words ARE the
     * shared secret for this path — see CastBox — so they're checked for plausibility here
     * rather than silently sealing a box no receiver can open. */
    private fun showManualCastCodeDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. k3m9x7qp"
            setSingleLine(true)
            // The receiver shows it in caps, but it's matched case-insensitively; autocorrect
            // would happily "fix" the name into a real word, so suggestions are turned off.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(pad * 2, pad, pad * 2, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Enter cast code")
            .setMessage("Type the code shown on the other device.")
            .setView(wrap)
            .setPositiveButton("Cast") { _, _ ->
                val typed = input.text.toString()
                val normalized = com.iptvapp.sync.CastBox.normalizePhrase(typed)
                if (!com.iptvapp.sync.CastBox.looksLikeCode(normalized)) {
                    Toast.makeText(this, "Enter the whole code shown on the other device", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                sendCastToScannedCode(com.iptvapp.sync.CastBox.manualPayload(typed))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestCastCameraAndScan() {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) launchCastScanner() else castCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    private fun launchCastScanner() {
        castScanLauncher.launch(Intent(this, CastQrScanActivity::class.java))
    }

    /**
     * Which URL to hand the receiver, and what (if anything) to warn about.
     *
     * A cast normally sends this device's own stream URL, which means the receiver opens a
     * second connection on the SAME Xtream account. Both configured accounts report
     * max_connections=1, so that second connection either gets refused or knocks this device
     * off — casting a channel would end your own viewing of it.
     *
     * So when another configured provider carries the same channel, cast THAT copy instead:
     * two providers, one connection each, both play at once. The match is the same
     * normalized-name lookup live failover already uses (see XtreamRepository
     * .findFailoverChannel — no shared channel ID exists across Xtream panels, so the name is
     * the only cross-provider signal there is).
     *
     * Falls back to this device's own URL when there's no match, or for VOD, which has no
     * cross-provider lookup — with a warning, because that's the case where the user's own
     * playback is about to stop.
     */
    /** Which provider the LAST resolveCastUrl() decided to cast through, so the channel pack
     * can be built from that same provider. Set by resolveCastUrl, read straight after. */
    private var lastCastServerIndex: Int = -1

    /** How many channels travel with a cast.
     *
     * Bounded by what the RECEIVER can afford, not by Firestore: a document can hold 1MiB and
     * 200 channels seal to roughly 27KB, so there is plenty of headroom there. The real limit is
     * that a 2015 TV decrypts in interpreted ES5, and decryption cost scales with the pack — 200
     * is around half a second on that hardware, paid once when the cast arrives (the receiver
     * caches the result rather than re-decrypting the same box on every poll).
     *
     * Raise it if you want more; just remember it is the oldest receiver that pays. */
    private val CAST_PACK_LIMIT = 200

    private suspend fun resolveCastUrl(): Pair<String, String?> {
        lastCastServerIndex = serverIndex
        // Ask the provider what it actually allows rather than assuming. An account with room
        // for a second stream can cast its own URL — same quality, no name-matching guesswork.
        // Only a single-connection account needs the cross-provider dance. Unknown counts as
        // "might be limited": the panel not answering is not permission to assume headroom.
        val limits = try { repository.accountLimits(serverIndex, forceRefresh = true) } catch (_: Exception) { null }
        val needsOtherProvider = limits == null || limits.isSingleConnection || limits.atLimit

        if (!needsOtherProvider) {
            return streamUrl to null
        }

        if (isVod) {
            return streamUrl to "Cast sent — this account allows one stream at a time, so playback here will stop"
        }
        val match = try {
            repository.findFailoverChannel(streamTitle, serverIndex)
        } catch (_: Exception) { null }
            ?: return streamUrl to "Cast sent — no other provider has this channel, so playback here will stop"

        val (matchServerIndex, matchStreamId, matchName) = match
        return try {
            val url = if (matchServerIndex == -1) repository.getLiveStreamUrl(matchStreamId)
                      else repository.getMergedLiveStreamUrl(matchServerIndex, matchStreamId)
            // The channel pack must be built from whichever provider the cast actually goes out
            // on, or the receiver could change channel back onto the account this device is
            // using and knock itself off.
            lastCastServerIndex = matchServerIndex
            com.iptvapp.IptvApplication.logPlaybackEvent(
                applicationContext,
                "CAST: \"$streamTitle\" on serverIndex=$serverIndex sent as \"$matchName\" " +
                    "(streamId=$matchStreamId) on serverIndex=$matchServerIndex, to keep both streams alive"
            )
            url to null
        } catch (_: Exception) {
            streamUrl to "Cast sent — couldn't use your other provider, so playback here may stop"
        }
    }

    /** Name and stream id of a channel destined for a cast pack, independent of which of the
     * two entity types it came from. */
    private data class PackSource(val streamId: Int, val name: String)

    /** How many programmes per channel ride along with a cast. The receiver can't fetch a guide
     * of its own, so this is all it will ever have — but every extra programme is paid for on
     * every channel, and it goes stale anyway, so it stays a short window rather than a day. */
    private val CAST_EPG_PER_CHANNEL = 3

    /**
     * Now-and-next for the channels in a cast pack, in one query rather than one per channel.
     *
     * Only programmes that haven't finished are included: sending the morning's schedule to
     * someone watching in the evening is worse than sending nothing, because the receiver would
     * confidently show the wrong thing. Timestamps are normalised to epoch milliseconds —
     * providers return seconds or milliseconds depending on the panel, and the receiver compares
     * them against its own clock.
     */
    private suspend fun castEpgFor(
        serverIdx: Int,
        streamIds: List<Int>
    ): Map<Int, List<com.iptvapp.sync.CastRelayManager.CastProgramme>> {
        if (streamIds.isEmpty()) return emptyMap()
        return try {
            val now = System.currentTimeMillis()
            fun ms(v: Long) = if (v < 100_000_000_000L) v * 1000L else v
            db.epgDao().getEpgForStreams(streamIds, serverIdx).first()
                .filter { ms(it.stopTimestamp) > now }
                .groupBy { it.streamId }
                .mapValues { (_, rows) ->
                    rows.sortedBy { ms(it.startTimestamp) }
                        .take(CAST_EPG_PER_CHANNEL)
                        .map {
                            com.iptvapp.sync.CastRelayManager.CastProgramme(
                                it.title, ms(it.startTimestamp), ms(it.stopTimestamp)
                            )
                        }
                }
        } catch (_: Exception) {
            // A cast with no guide is still a working cast.
            emptyMap()
        }
    }

    /**
     * Which channels go in the pack.
     *
     * Originally this was favourites only, which turned out to be the wrong default: a sender
     * with nothing favourited produced an empty pack, and the receiver reported "no channel list
     * to zap through" with nothing to explain why. The list you are ALREADY browsing is both the
     * more intuitive answer and almost always populated, so it comes first and favourites are
     * the fallback.
     *
     * Windowed around the current channel rather than truncated from the top — a category can
     * run to thousands, and the useful forty are the ones either side of what is playing.
     */
    private suspend fun pickCastPackSource(): List<PackSource> {
        fun window(list: List<PackSource>, around: Int): List<PackSource> {
            if (list.size <= CAST_PACK_LIMIT) return list
            val start = (around - CAST_PACK_LIMIT / 2).coerceIn(0, list.size - CAST_PACK_LIMIT)
            return list.subList(start, start + CAST_PACK_LIMIT)
        }

        // US categories only. Filling the rest of the 200 from the whole provider was
        // sending UK, CA and the rest of the lineup along with the channel being watched.
        val usIds = try {
            repository.getLiveCategories().first()
                .filter { com.iptvapp.util.CategoryFilters.isUsCategory(it.categoryName) }
                .map { it.categoryId }
                .toSet()
        } catch (_: Exception) { emptySet() }
        fun ChannelEntity.isUs() = categoryId != null && categoryId in usIds

        // Fill a pack up to the limit, in order of how relevant each source is, skipping
        // anything already included. Without this a small category sent a small pack — browsing
        // a 55-channel category meant the other end got 55 channels and nothing else, even
        // though there was room for 200.
        suspend fun topUp(seed: List<PackSource>): List<PackSource> {
            val out = seed.toMutableList()
            val seen = out.mapTo(mutableSetOf()) { it.streamId }
            if (out.size >= CAST_PACK_LIMIT) return out.take(CAST_PACK_LIMIT)

            // Favourites first — the most likely thing anyone would want next.
            try {
                for (f in db.channelDao().getFavoriteChannelsBlocking()) {
                    if (!f.isUs()) continue
                    if (out.size >= CAST_PACK_LIMIT) break
                    if (seen.add(f.streamId)) out += PackSource(f.streamId, f.name)
                }
            } catch (_: Exception) { /* keep whatever we have */ }

            // Then other US channels on the same provider, to fill the remainder.
            if (out.size < CAST_PACK_LIMIT) {
                try {
                    for (c in repository.getAllChannels().first()) {
                        if (!c.isUs()) continue
                        if (out.size >= CAST_PACK_LIMIT) break
                        if (seen.add(c.streamId)) out += PackSource(c.streamId, c.name)
                    }
                } catch (_: Exception) { /* keep whatever we have */ }
            }
            return out
        }

        // 1. The list being browsed, if it has finished loading. Foreign categories dropped.
        val browsing: List<PackSource> = if (serverIndex != -1) {
            mergedChannels
                .filter { com.iptvapp.util.CategoryFilters.isUsCategory(it.categoryName) }
                .map { PackSource(it.streamId, it.name) }
        } else {
            channels.filter { it.isUs() }.map { PackSource(it.streamId, it.name) }
        }
        if (browsing.isNotEmpty()) {
            val here = browsing.indexOfFirst {
                it.streamId == (if (serverIndex != -1) mergedStreamId else streamId)
            }
            val win = window(browsing, if (here >= 0) here else 0)
            // Only top up from the primary provider's channels — a merged-provider cast would
            // otherwise be padded with channels from the wrong account entirely.
            return if (serverIndex == -1) topUp(win) else win
        }

        // 2. Those lists are filled by a coroutine started in onCreate, so casting before it
        //    finishes would silently send an empty pack — the receiver then reports it has no
        //    channels and there is nothing on screen to connect that to. Ask the repository
        //    directly rather than trusting a field that may not be populated yet.
        try {
            val fromDb = if (serverIndex != -1) {
                val current = repository.getMergedChannelByIndexAndId(serverIndex, mergedStreamId)
                if (current != null && com.iptvapp.util.CategoryFilters.isUsCategory(current.categoryName)) {
                    repository.getMergedChannelsByCategory(serverIndex, current.categoryId).first()
                        .filter { com.iptvapp.util.CategoryFilters.isUsCategory(it.categoryName) }
                        .map { PackSource(it.streamId, it.name) }
                } else emptyList()
            } else {
                repository.getAllChannels().first()
                    .filter { it.isUs() }
                    .map { PackSource(it.streamId, it.name) }
            }
            if (fromDb.isNotEmpty()) {
                val here = fromDb.indexOfFirst {
                    it.streamId == (if (serverIndex != -1) mergedStreamId else streamId)
                }
                return window(fromDb, if (here >= 0) here else 0)
            }
        } catch (_: Exception) { /* fall through to favourites */ }

        // 3. Last resort.
        return try {
            db.channelDao().getFavoriteChannelsBlocking()
                .filter { it.isUs() }
                .take(CAST_PACK_LIMIT)
                .map { PackSource(it.streamId, it.name) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * The channel pack that travels with a cast, so the receiver can change channel instead of
     * being stuck on whatever was sent. See [pickCastPackSource] for where the channels come from.
     *
     * [viaServerIndex] is the provider the cast itself is going out on. When that differs from
     * the one being watched (the single-connection case — see resolveCastUrl), the pack has to
     * come from that same provider too, or the receiver would change channel straight back onto
     * the account this device is using and knock itself off. Resolving those in bulk matters:
     * one scan of the other catalogue rather than one per channel.
     *
     * Returns empty when nothing can be built, which means the receiver gets the single channel
     * and no list — the caller says so out loud rather than leaving it a mystery.
     */
    private suspend fun buildCastChannelPack(viaServerIndex: Int): List<com.iptvapp.sync.CastRelayManager.CastChannel> {
        return try {
            val favs = pickCastPackSource()
            if (favs.isEmpty()) return emptyList()

            if (viaServerIndex == serverIndex) {
                // Same provider we're watching on: its own URLs are already correct.
                val urls = repository.buildLiveStreamUrls(serverIndex, favs.map { it.streamId })
                val guide = castEpgFor(serverIndex, favs.map { it.streamId })
                favs.mapNotNull { ch ->
                    urls[ch.streamId]?.let {
                        com.iptvapp.sync.CastRelayManager.CastChannel(
                            ch.name, it, guide[ch.streamId].orEmpty()
                        )
                    }
                }
            } else {
                val matches = repository.findFailoverChannels(favs.map { it.name }, serverIndex)
                // Group by the provider each match landed on, so each one's credentials are
                // resolved once rather than once per channel.
                val hits = favs.mapNotNull { ch ->
                    matches[com.iptvapp.util.ChannelNameMatcher.normalize(ch.name)]
                }
                val urlsByServer = hits.groupBy { it.first }
                    .mapValues { (idx, list) -> repository.buildLiveStreamUrls(idx, list.map { it.second }) }
                // The guide has to come from the provider the cast is going out on — the other
                // provider's stream ids are its own, and its schedule can differ.
                val guideByServer = hits.groupBy { it.first }
                    .mapValues { (idx, list) -> castEpgFor(idx, list.map { it.second }) }
                hits.mapNotNull { (idx, sid, name) ->
                    urlsByServer[idx]?.get(sid)?.let {
                        com.iptvapp.sync.CastRelayManager.CastChannel(
                            name, it, guideByServer[idx]?.get(sid).orEmpty()
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun sendCastToScannedCode(code: String) {
        if (streamUrl.isBlank()) {
            Toast.makeText(this, "Nothing playing to cast", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Casting...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val (castUrl, warning) = resolveCastUrl()
            val pack = if (isVod) emptyList() else buildCastChannelPack(lastCastServerIndex)
            when (castRelay.send(code, castUrl, streamTitle, pack)) {
                is com.iptvapp.sync.CastSendResult.Sent -> {
                    castRelay.lastSentCode = code
                    // The warning, when there is one, IS the success message — it says the cast
                    // went out and that this screen is the one about to lose the stream.
                    // An empty pack is worth saying out loud too: otherwise the only clue is the
                    // receiver complaining it has no channel list, with nothing to connect it to.
                    val msg = warning
                        ?: if (pack.isEmpty() && !isVod)
                            "Cast sent — but no channel list went with it, so they can't change channel"
                        else "Cast sent — ${pack.size} channels they can flip through"
                    Toast.makeText(
                        this@PlayerActivity,
                        msg,
                        if (warning != null || pack.isEmpty()) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                    ).show()
                }
                is com.iptvapp.sync.CastSendResult.InvalidCode -> {
                    // The receiver's session is gone (closed the waiting screen, or it's simply a
                    // stale remembered code from an earlier receiver) — forget it so the next tap
                    // on "Cast to a Device" offers scanning fresh instead of repeating the same
                    // dead code.
                    if (castRelay.lastSentCode == code) castRelay.lastSentCode = null
                    Toast.makeText(this@PlayerActivity, "That code doesn't match a waiting device", Toast.LENGTH_LONG).show()
                }
                is com.iptvapp.sync.CastSendResult.ReceiverTooOld -> {
                    // Its QR carried no session key, so there is no way to send without putting
                    // the credential-bearing URL into Firestore in the clear. Refuse and say so.
                    if (castRelay.lastSentCode == code) castRelay.lastSentCode = null
                    Toast.makeText(this@PlayerActivity, "Update the app on that device before casting to it", Toast.LENGTH_LONG).show()
                }
                is com.iptvapp.sync.CastSendResult.SendFailed ->
                    Toast.makeText(this@PlayerActivity, "Couldn't send the cast — try again", Toast.LENGTH_LONG).show()
            }
        }
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
                // Party starts paused (see WatchPartyManager.startParty kdoc) — pause the host's
                // own player to match, right at the same moment, so playback doesn't keep running
                // ahead while the host is still sharing the code / waiting for people to join.
                // isApplyingRemoteUpdate guards this so the pause itself doesn't get mistaken for
                // a "real user action" and re-written back over the isPlaying=false already sent.
                isApplyingRemoteUpdate = true
                requestTimeshiftPlayback(false)
                updatePlayPauseButton()
                isApplyingRemoteUpdate = false
                val startPositionMs = if (isVod) player?.currentPosition ?: 0L else 0L
                val code = watchPartyManager.startParty(currentWatchPartyContent(), startPositionMs)
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

    /** Only attached when this Activity instance is a cast RECEIVER (see castSessionCode kdoc).
     * The very first payload is what launched this Activity (its url == streamUrl already), so
     * that delivery is a no-op here — only a LATER, different url (the sender casting a new
     * channel to the same still-open session) actually swaps playback. Reuses loadStream() so a
     * live Chromecast session on this receiving device (unlikely but not impossible) is handled
     * the same way any other channel change here already is. */
    private fun attachCastSessionListener() {
        castSessionListenerReg?.remove()
        castSessionListenerReg = castRelay.listen(castSessionCode) { payload ->
            if (payload.url == streamUrl) return@listen
            runOnUiThread {
                streamTitle = payload.title
                binding.tvChannelTitle.text = streamTitle
                // A later cast can carry a fresh pack (the sender's favourites may have changed,
                // or it may now be going out on a different provider) — take it, but never let
                // an absent one wipe a pack we already have.
                if (payload.channels.isNotEmpty()) castChannels = payload.channels
                castIndex = castChannels.indexOfFirst { it.url == payload.url }
                    .takeIf { it >= 0 } ?: 0
                loadStream(payload.url)
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
                        // getMerged/LiveStreamUrl is pure string building against a catalog id that
                        // may simply not exist on this device's own provider — it never throws for
                        // that case, so the catch block below was structurally unable to ever catch
                        // "not on your provider" (only a genuine connection/config exception).
                        // checkStreamHealth actually probes the URL first so this member gets an
                        // immediate, correct message instead of silently sitting on the old channel.
                        if (!repository.checkStreamHealth(url)) {
                            Toast.makeText(
                                this@PlayerActivity,
                                "Couldn't follow host's channel — not available on your provider",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            loadStream(url)
                        }
                    } catch (_: Exception) {
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
        // A title-search fallback match (see isPartyContentSubstituted kdoc) will never match the
        // host's own catalog id on any future update — that's expected and correct here, not a
        // real content change to chase. Treat it as "already on the right title" permanently for
        // this session so only position/play-state syncs, the actual content is left alone.
        val sameContent = if (isPartyContentSubstituted) {
            true
        } else if (state.content.contentType == "EPISODE") {
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
                    // Same reasoning as the LIVE branch above: URL building never throws for a
                    // catalog id that doesn't exist on this member's own provider, so this needs an
                    // actual probe, not just a catch block, to catch the real "not on your
                    // provider" case instead of only genuine connection/config exceptions.
                    if (!repository.checkStreamHealth(url)) {
                        isApplyingRemoteUpdate = false
                        when (state.content.contentType) {
                            "VOD" -> offerWatchPartyVodTitleFallback(state)
                            "EPISODE" -> offerWatchPartyEpisodeTitleFallback(state)
                            else -> Toast.makeText(
                                this@PlayerActivity,
                                "Couldn't load \"${state.content.title}\" — not available on your provider",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@launch
                    }
                    loadStream(url)
                    if (state.positionMs > 0L) player?.seekTo(state.positionMs)
                    // loadStream() always sets playWhenReady = true — if the party is actually
                    // paused (e.g. the host just started it and hasn't pressed play yet), correct
                    // that immediately instead of letting the member watch a moment of playback
                    // before the trailing position/play-state sync below would otherwise catch it.
                    if (!state.isPlaying) {
                        requestTimeshiftPlayback(false)
                        updatePlayPauseButton()
                    }
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
            requestTimeshiftPlayback(state.isPlaying)
            updatePlayPauseButton()
        }
        isApplyingRemoteUpdate = false
    }

    /** In-player Watch Party VOD fallback — same reasoning/behavior as SettingsActivity's
     * offerWatchPartyVodTitleFallback, just operating on the already-open player instead of
     * launching a new PlayerActivity. Sets isPartyContentSubstituted so future Firestore updates
     * don't keep re-triggering this (see that field's kdoc). */
    private fun offerWatchPartyVodTitleFallback(state: com.iptvapp.sync.WatchPartyState) {
        val hostTitle = state.content.title
        lifecycleScope.launch {
            val matches = try {
                repository.findWatchPartyVodMatches(hostTitle)
            } catch (_: Exception) {
                emptyList()
            }
            if (matches.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "Couldn't join Watch Party — \"$hostTitle\" isn't available on your provider", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (matches.size == 1) {
                playWatchPartyVodMatch(state, matches[0])
                return@launch
            }
            // setMessage + setItems on the same AlertDialog.Builder are mutually exclusive —
            // Android silently drops the items list and shows only the message text when both
            // are set. The match count goes in the title instead so setItems alone renders.
            val labels = matches.map { it.title }.toTypedArray()
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("Which \"$hostTitle\"? (${matches.size} matches)")
                .setItems(labels) { _, which -> playWatchPartyVodMatch(state, matches[which]) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun playWatchPartyVodMatch(state: com.iptvapp.sync.WatchPartyState, match: com.iptvapp.data.repository.XtreamRepository.WatchPartyVodMatch) {
        lifecycleScope.launch {
            val url = try {
                if (match.serverIndex != -1) repository.getMergedVodStreamUrl(match.serverIndex, match.streamId, match.containerExtension)
                else repository.getVodStreamUrl(match.streamId, match.containerExtension)
            } catch (e: Exception) { null }
            if (url == null || !repository.checkStreamHealth(url)) {
                Toast.makeText(this@PlayerActivity, "Couldn't load \"${match.title}\"", Toast.LENGTH_SHORT).show()
                return@launch
            }
            isPartyContentSubstituted = true
            isApplyingRemoteUpdate = true
            isVod = true
            streamId = match.streamId
            serverIndex = match.serverIndex
            mergedStreamId = if (match.serverIndex != -1) match.streamId else -1
            streamTitle = state.content.title
            binding.tvChannelTitle.text = streamTitle
            loadStream(url)
            // Same reasoning as the exact-catalog-match path above — loadStream() always sets
            // playWhenReady = true, so land on the party's actual current position/play-state
            // instead of momentarily auto-playing from 0 before a later sync corrects it.
            if (state.positionMs > 0L) player?.seekTo(state.positionMs)
            if (!state.isPlaying) {
                requestTimeshiftPlayback(false)
                updatePlayPauseButton()
            }
            isApplyingRemoteUpdate = false
        }
    }

    /** VOD/series failover on plain (non-party) retry exhaustion — the movie/episode equivalent
     * of the live-channel by-name failover a few lines up. Reuses findWatchPartyVodMatches /
     * findWatchPartyEpisodeMatch (title-normalized search across every provider this device is
     * configured for) even though this isn't a party — that machinery already does exactly "find
     * this same title under a different catalog id elsewhere", the Watch Party framing was
     * incidental. Silently no-ops (leaves the existing "Stream unavailable" status showing) if no
     * match is found or the match itself doesn't play — a failed failover shouldn't look like a
     * separate second failure to the user. */
    private fun attemptVodFailover() {
        lifecycleScope.launch {
            val isEpisode = episodeSeriesId != -1 && traktSeason >= 0 && traktEpisode >= 0
            if (isEpisode) {
                val seriesName = traktSeriesName.ifBlank { streamTitle }
                val match = try {
                    repository.findWatchPartyEpisodeMatch(seriesName, traktSeason, traktEpisode).firstOrNull()
                } catch (_: Exception) { null } ?: return@launch
                val url = try {
                    if (match.serverIndex != -1) repository.getMergedSeriesEpisodeUrl(match.serverIndex, match.episodeId, match.containerExtension)
                    else repository.getSeriesEpisodeUrl(match.episodeId, match.containerExtension)
                } catch (_: Exception) { null }
                if (url == null || !repository.checkStreamHealth(url)) return@launch
                com.iptvapp.IptvApplication.logPlaybackEvent(
                    applicationContext,
                    "VOD FAILOVER: episode seriesId=$episodeSeriesId ($seriesName) S${traktSeason}E$traktEpisode -> " +
                        "seriesId=${match.seriesId} on serverIndex=${match.serverIndex}, after $maxRetries failed attempts"
                )
                Toast.makeText(this@PlayerActivity, "Switched provider for \"${match.seriesTitle}\" — the original had an error", Toast.LENGTH_LONG).show()
                episodeSeriesId = match.seriesId
                retryCount = 0
                binding.tvRetryStatus.visibility = View.GONE
                loadStream(url)
            } else {
                val match = try {
                    repository.findWatchPartyVodMatches(streamTitle).firstOrNull()
                } catch (_: Exception) { null } ?: return@launch
                val url = try {
                    if (match.serverIndex != -1) repository.getMergedVodStreamUrl(match.serverIndex, match.streamId, match.containerExtension)
                    else repository.getVodStreamUrl(match.streamId, match.containerExtension)
                } catch (_: Exception) { null }
                if (url == null || !repository.checkStreamHealth(url)) return@launch
                com.iptvapp.IptvApplication.logPlaybackEvent(
                    applicationContext,
                    "VOD FAILOVER: streamId=$streamId ($streamTitle) on serverIndex=$serverIndex -> " +
                        "streamId=${match.streamId} on serverIndex=${match.serverIndex}, after $maxRetries failed attempts"
                )
                Toast.makeText(this@PlayerActivity, "Switched provider for \"${match.title}\" — the original had an error", Toast.LENGTH_LONG).show()
                streamId = match.streamId
                serverIndex = match.serverIndex
                mergedStreamId = if (match.serverIndex != -1) match.streamId else -1
                retryCount = 0
                binding.tvRetryStatus.visibility = View.GONE
                loadStream(url)
            }
        }
    }

    /** Episode equivalent of offerWatchPartyVodTitleFallback/playWatchPartyVodMatch — see
     * SettingsActivity's matching kdoc for the series-name-search + season/episode-lookup
     * reasoning. */
    private fun offerWatchPartyEpisodeTitleFallback(state: com.iptvapp.sync.WatchPartyState) {
        val seriesName = state.content.seriesName.ifBlank { state.content.title }
        lifecycleScope.launch {
            val matches = try {
                repository.findWatchPartyEpisodeMatch(seriesName, state.content.seasonNum, state.content.episodeNum)
            } catch (_: Exception) {
                emptyList()
            }
            if (matches.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "Couldn't join Watch Party — \"$seriesName\" S${state.content.seasonNum}E${state.content.episodeNum} isn't available on your provider", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (matches.size == 1) {
                playWatchPartyEpisodeMatch(state, matches[0])
                return@launch
            }
            val labels = matches.map { it.seriesTitle }.toTypedArray()
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("Which \"$seriesName\"? (${matches.size} matches)")
                .setItems(labels) { _, which -> playWatchPartyEpisodeMatch(state, matches[which]) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun playWatchPartyEpisodeMatch(state: com.iptvapp.sync.WatchPartyState, match: com.iptvapp.data.repository.XtreamRepository.WatchPartyEpisodeMatch) {
        lifecycleScope.launch {
            val url = try {
                if (match.serverIndex != -1) repository.getMergedSeriesEpisodeUrl(match.serverIndex, match.episodeId, match.containerExtension)
                else repository.getSeriesEpisodeUrl(match.episodeId, match.containerExtension)
            } catch (e: Exception) { null }
            if (url == null || !repository.checkStreamHealth(url)) {
                Toast.makeText(this@PlayerActivity, "Couldn't load \"${match.seriesTitle}\"", Toast.LENGTH_SHORT).show()
                return@launch
            }
            isPartyContentSubstituted = true
            isApplyingRemoteUpdate = true
            isVod = true
            episodeSeriesId = match.seriesId
            traktSeason = state.content.seasonNum
            traktEpisode = state.content.episodeNum
            streamTitle = state.content.title
            binding.tvChannelTitle.text = streamTitle
            loadStream(url)
            if (state.positionMs > 0L) player?.seekTo(state.positionMs)
            if (!state.isPlaying) {
                requestTimeshiftPlayback(false)
                updatePlayPauseButton()
            }
            isApplyingRemoteUpdate = false
        }
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

    // Cross-device live-TV handoff (see PlaybackHandoffManager kdoc) — completely independent of
    // Watch Party (no code, no party membership required), so this fires unconditionally for
    // every live-channel tune-in, not gated on isPartyHost/partyCode like the two functions
    // above. Live channels only, matching the scope this feature was built for — VOD/episode
    // playback already has its own cross-device concept (Continue Watching's watchedMs/durationMs
    // sync via SyncManager) that a live "position" wouldn't map onto anyway.
    private fun reportLiveChannelForHandoff() {
        if (isVod) return
        lifecycleScope.launch {
            playbackHandoffManager.reportLiveChannel(streamId, serverIndex, mergedStreamId, streamTitle)
        }
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
        binding.btnRewatchNotes.visibility = if (isVod && !isCatchup) View.VISIBLE else View.GONE
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
                sleepDeadlineMs = 0L
                val chosen = mins[which]
                if (chosen == 0) {
                    binding.btnSleep.text = "Sleep"
                    binding.btnSleep.setTextColor(getColor(R.color.rack_text))
                } else {
                    binding.btnSleep.setTextColor(playerAccent.start)
                    armSleepTimer(chosen * 60_000L)
                }
                resetHideTimer()
            }
            .show()
    }

    /** Pauses playback after [totalMs]; the Sleep chip counts down the minutes left. */
    private fun armSleepTimer(totalMs: Long) {
        sleepTimer?.cancel()
        sleepDeadlineMs = System.currentTimeMillis() + totalMs
        binding.btnSleep.setTextColor(playerAccent.start)
        sleepTimer = object : CountDownTimer(totalMs, 60_000L) {
            override fun onTick(ms: Long) {
                binding.btnSleep.text = "Sleep ${(ms + 59_999L) / 60_000}m"
            }
            override fun onFinish() {
                requestTimeshiftPlayback(false)
                sleepDeadlineMs = 0L
                binding.btnSleep.text = "Sleep"
                binding.btnSleep.setTextColor(getColor(R.color.rack_text))
            }
        }.start()
        binding.btnSleep.text = "Sleep ${(totalMs + 59_999L) / 60_000}m"
    }

    private fun channelAudioKey(): String? {
        if (!ChannelAudioDelay.offersFor(isVod, isCatchup, isRecordingPlayback)) return null
        return ChannelAudioDelay.key(serverIndex, liveCopyId())
    }

    /** Loads the saved offset when the channel changes. A nudge on this channel is left as it is. */
    private fun applyChannelAudioDelay() {
        val key = channelAudioKey()
        if (key == appliedAudioDelayKey) return
        appliedAudioDelayKey = key
        channelAudioDelay.offsetMs = if (key == null) 0 else prefs.channelAudioDelayNow(key)
    }

    private fun showAudioDelayDialog() {
        val key = channelAudioKey() ?: return
        // A message hides the item list, so the hint stays in the title.
        AlertDialog.Builder(this)
            .setTitle(
                "${ChannelAudioDelay.label(channelAudioDelay.offsetMs)}\n" +
                    "This channel remembers it. Earlier, if you hear the words after the picture."
            )
            .setItems(arrayOf("Earlier", "Later", "Reset")) { _, which ->
                val next = when (which) {
                    0 -> ChannelAudioDelay.nudge(channelAudioDelay.offsetMs, -1)
                    1 -> ChannelAudioDelay.nudge(channelAudioDelay.offsetMs, 1)
                    else -> 0
                }
                channelAudioDelay.offsetMs = next
                lifecycleScope.launch { prefs.setChannelAudioDelay(key, next) }
                showAudioDelayDialog()
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showTrackSelectorDialog() {
        val p = player ?: return
        val tracks = p.currentTracks

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (channelAudioKey() != null) {
            labels.add("── Sound timing ──")
            actions.add {}
            labels.add("    ${ChannelAudioDelay.label(channelAudioDelay.offsetMs)}")
            actions.add { showAudioDelayDialog() }
        }

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

        if (externalSubtitleChoices() && (textGroups.isEmpty() || externalSubtitle != null)) {
            if (textGroups.isEmpty()) {
                labels.add("── Subtitles ──")
                actions.add {}
            }
            labels.add("    Search OpenSubtitles")
            actions.add { searchOpenSubtitles() }
            labels.add("    Open a subtitle file")
            actions.add { openSubtitleFile() }
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

    /** Start Over: switches this live channel, in place, to a replay of the show on now from its
     * first minute — the same catch-up mode a guide replay uses (seek bar, pause, skip). LIVE, or
     * the replay running out, comes back to the broadcast (backToLive). Catch-up channels only. */
    private fun startOverCurrentShow() {
        val show = liveNowShow?.takeIf { it.streamId == liveCopyId() } ?: return
        val startMs = show.startMs
        val stopMs = show.stopMs
        val title = show.title
        if (partyCode.isNotEmpty()) {
            Toast.makeText(this, "Leave the watch party to start over", Toast.LENGTH_LONG).show()
            return
        }
        if (isVod || liveCopyId() == -1 || castSession != null || audioOnly) return
        // Any provider with an archive: the main one's channel row, or an extra provider's.
        val server = serverIndex
        val sid = liveCopyId()
        // Tracked as catchupReloadJob so LIVE (backToLive) can cancel a start that's still loading.
        catchupReloadJob?.cancel()
        catchupReloadJob = lifecycleScope.launch {
            val ch = archivedChannel(server, sid)
            if (ch == null) {
                Toast.makeText(this@PlayerActivity, "This channel doesn't keep past shows, so it can't start over", Toast.LENGTH_LONG).show()
                return@launch
            }
            // Still the same live channel? (It can change while the lookup above runs.)
            if (isVod || serverIndex != server || liveCopyId() != sid) return@launch
            startedOverFromLive = true
            // A replay is not this live copy. Save what it has and stop scoring until back at live.
            feedFlushJob?.cancel()
            reportFeed(feedTracker.open(null, android.os.SystemClock.elapsedRealtime()))
            liveTitleBeforeStartOver = streamTitle
            catchupLiveUrl = streamUrl
            catchupChannelName = ch.name
            isCatchup = true
            isVod = true
            catchupStartSec = startMs / 1000L
            catchupDurationMin = ((stopMs - startMs) / 60_000L).toInt().coerceAtLeast(1)
            catchupOffsetSec = 0L
            catchupPendingSeekMs = 0L
            catchupPendingTargetMs = -1L
            retryJob?.cancel()
            retryCount = 0
            vodFormatFallbackTried = false
            stopTimeshift()
            clearDisplayMode()
            // Catch-up is a replay, not the live edge. A sports buffer would starve it.
            if (sportsLoadControlOn || player == null) {
                binding.playerView.player = null
                player?.release()
                player = null
                player = buildPlayer()
            }
            streamTitle = "${ch.name} — $title"
            binding.tvChannelTitle.text = streamTitle
            // The line under the title described the live broadcast ("28 min left").
            binding.tvEpgNow.text = "From the start"
            binding.tvEpgNext.visibility = View.GONE
            // Live-only controls off; showOverlay brings back the replay ones.
            hideHandler.removeCallbacks(hideRunnable)
            hideRunnable.run()
            binding.btnStartOver.visibility = View.GONE
            binding.btnAudioOnly.visibility = View.GONE
            binding.btnWatchParty.visibility = View.GONE
            updateRewatchNotesButtonVisibility()
            com.iptvapp.IptvApplication.logPlaybackEvent(
                applicationContext, "START OVER: streamId=$streamId title=$title startSec=$catchupStartSec durationMin=$catchupDurationMin"
            )
            // The replay URL is built as .ts (what the guide's replays use), not in the live
            // stream's own format — loadCatchupAt follows streamUrl's extension.
            streamUrl = ""
            loadCatchupAt(0L)
            player?.playWhenReady = true
            showOverlay()
        }
    }

    /** Back from a Start Over replay to the live broadcast. */
    private fun backToLive() {
        if (!startedOverFromLive) return
        exitStartOverState()
        loadStream(catchupLiveUrl)
        showOverlay()
    }

    /** Drops Start Over's replay state and controls without loading anything — for backToLive,
     * and for a channel change made from the replay, which loads its own channel. */
    private fun exitStartOverState() {
        // A Start Over still loading (flag not set yet) is the only reload that can be pending here.
        if (!isCatchup) catchupReloadJob?.cancel()
        if (!startedOverFromLive) return
        startedOverFromLive = false
        catchupReloadJob?.cancel()
        catchupPendingTargetMs = -1L
        catchupPendingSeekMs = 0L
        isCatchup = false
        isVod = false
        seekRunnable?.let { seekHandler.removeCallbacks(it) }
        hideHandler.removeCallbacks(hideRunnable)
        hideRunnable.run()
        binding.vodSeekContainer.visibility = View.GONE
        streamTitle = liveTitleBeforeStartOver
        binding.tvChannelTitle.text = streamTitle
        binding.btnAudioOnly.visibility = View.VISIBLE
        binding.btnWatchParty.visibility = View.VISIBLE
        updateRewatchNotesButtonVisibility()
    }

    /** Hands the live channel to AudioOnlyService: this player lets go of the stream first (one
     * connection per account on most plans), the screen may sleep, and the sound carries on in
     * the background with notification controls. */
    private fun enterAudioOnly() {
        if (isVod || audioOnly || castSession != null || streamUrl.isEmpty()) return
        quickPeek.stop()
        audioOnly = true
        // The listen service opens the channel itself. Drop the timeshift connection first.
        clearDisplayMode()
        stopTimeshift()
        retryJob?.cancel()
        hideHandler.removeCallbacks(hideRunnable)
        hideRunnable.run()
        binding.playerView.player = null
        player?.release()
        player = null
        // The listen service opens the stream itself; prepared neighbors would hold connections.
        releasePlayerPreload()
        // buildPlayer replaces the tracker on the way back, so count what it holds now.
        bandwidthTracker?.let { t ->
            t.stop()
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch { t.flush() }
        }
        bandwidthTracker = null
        // An armed sleep timer goes with the sound — this player (and its timer) is going away.
        val sleepLeftMs = (sleepDeadlineMs - System.currentTimeMillis()).takeIf { sleepTimer != null && it > 0L } ?: 0L
        sleepTimer?.cancel()
        sleepTimer = null
        sleepDeadlineMs = 0L
        binding.btnSleep.text = "Sleep"
        binding.btnSleep.setTextColor(getColor(R.color.rack_text))
        AudioOnlyService.play(this, streamUrl, streamTitle, serverIndex, sleepLeftMs)
        com.iptvapp.IptvApplication.logPlaybackEvent(applicationContext, "AUDIO ONLY START: streamId=$streamId title=$streamTitle")
        binding.tvAudioOnlyTitle.text = streamTitle
        binding.tvRetryStatus.visibility = View.GONE
        binding.progressBuffering.visibility = View.GONE
        binding.audioOnlyPanel.visibility = View.VISIBLE
        binding.btnShowPicture.requestFocus()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun exitAudioOnly() {
        if (!audioOnly) return
        // A sleep timer still running in the service comes back with the picture.
        val sleepLeftMs = AudioOnlyService.remainingSleepMs()
        AudioOnlyService.stop(this)
        leaveAudioOnlyPanel()
        if (player == null) {
            player = buildPlayer()
            loadStream(streamUrl)
        }
        if (sleepLeftMs > 0L) armSleepTimer(sleepLeftMs)
    }

    private fun leaveAudioOnlyPanel() {
        audioOnly = false
        binding.audioOnlyPanel.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupVodSkipButtons() {
        fun skip(deltaMs: Long) {
            resetHideTimer()
            vodSeekTo(vodPositionMs() + deltaMs)
            notifyPartyStateChange()
            updateSeekBar()
        }
        binding.btnVodBack.setOnClickListener {
            resetHideTimer()
            if (!isVod && timeshiftControlsOpen()) seekTimeshiftBy(-TimeshiftNavigator.REPLAY_10_MS)
            else skip(-10_000L)
        }
        binding.btnVodFwd.setOnClickListener { skip(30_000L) }
        binding.btnVodFwdBig.setOnClickListener { skip(120_000L) }
        binding.btnStartOver.setOnClickListener { resetHideTimer(); startOverCurrentShow() }
        if (isRecordingPlayback) {
            binding.btnMarks.visibility = View.VISIBLE
            // Commercial breaks (Skip break): an older recording is analyzed the first time it's played.
            if (streamId >= 0 && !com.iptvapp.util.AdBreakDetector.isAnalyzed(this, streamId)) {
                com.iptvapp.worker.AdBreakWorker.enqueue(this, streamId, streamUrl)
            }
            binding.btnSkipBreak.setOnClickListener { skipCurrentBreak() }
            binding.btnMarks.setOnClickListener { resetHideTimer(); showMarksDialog() }
        }
    }

    // Chapter marks for a recording: spots saved per file (keyed by its path) to jump straight
    // back to — the start of the game, after the ads, a scene to show someone.
    private val marksPrefs by lazy { getSharedPreferences("recording_marks", MODE_PRIVATE) }

    private fun loadMarks(): List<Long> =
        (marksPrefs.getString(streamUrl, "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.sorted()

    private fun saveMarks(marks: List<Long>) {
        marksPrefs.edit().apply {
            if (marks.isEmpty()) remove(streamUrl) else putString(streamUrl, marks.distinct().sorted().joinToString(","))
        }.apply()
    }

    private fun showMarksDialog() {
        val here = vodPositionMs()
        val marks = loadMarks()
        val labels = listOf("＋ Mark this spot (${formatDuration(here)})") + marks.map { "▸ ${formatDuration(it)}" }
        val builder = AlertDialog.Builder(this)
            .setTitle("Marks")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    // Within 5 s of an existing mark counts as the same spot.
                    if (marks.none { kotlin.math.abs(it - here) < 5_000L }) saveMarks(marks + here)
                    Toast.makeText(this, "Marked ${formatDuration(here)}", Toast.LENGTH_SHORT).show()
                } else {
                    vodSeekTo(marks[which - 1])
                    updateSeekBar()
                }
            }
            .setNegativeButton("Close", null)
        if (marks.isNotEmpty()) builder.setNeutralButton("Clear all") { _, _ -> saveMarks(emptyList()) }
        builder.show()
    }

    private fun setupChannelZones() {
        binding.zonePrevious.setOnClickListener {
            if (binding.guideContainer.visibility == View.VISIBLE) return@setOnClickListener
            if (isVod) {
                vodSeekTo(vodPositionMs() - nextVodSkipAmountMs())
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
                vodSeekTo(vodPositionMs() + nextVodSkipAmountMs())
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

            // Phone has no hardware LAST/RECALL button the way a TV remote does (KEYCODE_LAST_
            // CHANNEL in onKeyDown) — double-tap the middle third of the screen as the touch
            // equivalent, since that zone is otherwise idle (the brightness/volume swipe zones
            // are the left/right thirds, and this is a tap gesture, not a scroll, so it can't
            // collide with those anyway). Live channels only, matching recallLastChannel's own
            // scope — a double-tap during VOD playback already means something else common
            // elsewhere in apps like this (nothing currently bound here, but reserving it for
            // recall only where it makes sense avoids future ambiguity).
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isVod || isOverlayVisible) return false
                val w = binding.root.width.toFloat()
                if (e.x < w * 0.35f || e.x > w * 0.65f) return false
                recallLastChannel()
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
        // Audio only: touches go to the panel (SHOW PICTURE) and nowhere else — the player's
        // gestures (double-tap channel recall, swipes) would change what the activity thinks is
        // playing while the service carries on with the original channel.
        if (audioOnly) return super.dispatchTouchEvent(ev)
        if (isLocked) {
            // Locked: only the unlock button gets touches; any other tap brings it back up.
            val b = binding.btnUnlock
            if (b.visibility == View.VISIBLE) {
                val r = android.graphics.Rect()
                b.getGlobalVisibleRect(r)
                if (r.contains(ev.rawX.toInt(), ev.rawY.toInt())) return super.dispatchTouchEvent(ev)
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP) flashUnlock()
            return true
        }
        // Quick Peek open: a tap on the picture beside it closes it, and goes no further; a touch
        // that starts on it is the panel's alone (scrolling its list is not a volume swipe, and a
        // double tap there is not LAST).
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::quickPeek.isInitialized && quickPeek.isOpen) {
            if (quickPeek.touchOutside(ev.rawX, ev.rawY)) {
                peekSwallowGesture = true
                return true
            }
            peekOwnsGesture = true
        }
        val ended = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
        if (peekSwallowGesture) {
            if (ended) peekSwallowGesture = false
            return true
        }
        if (peekOwnsGesture) {
            if (ended) peekOwnsGesture = false
            return super.dispatchTouchEvent(ev)
        }
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ─── Player ─────────────────────────────────────────────────────────────

    private fun buildPlayer(): ExoPlayer {
        applyChannelAudioDelay()
        outcomeRecordedForThisPlayback = false
        // Global (applies to every server, not per-provider) — trades a slower initial
        // load/seek for fewer mid-playback stalls, since some IPTV providers are slow or
        // inconsistent enough that the default buffer runs dry mid-stream.
        val extraBufferingEnabled = prefs.extraBufferingEnabledNow()
        // Sports Mode replaces these numbers only while it is on. Off keeps both profiles above.
        // Load control is fixed for the life of this player, so turning Sports on rebuilds it.
        val sportsBuffer = sportsActiveNow()
        sportsLoadControlOn = sportsBuffer
        val bufferProfile = SportsMode.loadControl(sportsBuffer, extraBufferingEnabled)
        // A live target offset also caps the wait before playback starts or resumes, at half the
        // offset. The local timeshift playlist used 3 s, so it resumed with 1.5 s in hand and
        // stalled on hiccups that the provider stream rode out. Sports keeps its short delay.
        localLiveCushionMs = if (sportsBuffer) SportsMode.TARGET_OFFSET_MS
        else bufferProfile.bufferForPlaybackAfterRebufferMs.toLong()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferProfile.minBufferMs,
                bufferProfile.maxBufferMs,
                bufferProfile.bufferForPlaybackMs,
                bufferProfile.bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            // In-memory rewind for the last couple of minutes. A longer pause/rewind window
            // is the separate bounded store in TimeshiftStore, not this cache.
            .setBackBuffer(bufferProfile.backBufferMs, bufferProfile.retainBackBufferFromKeyframe)
            // Shared with the preload manager (see ZapPreloader.buildPlayer). A neighbor channel gets
            // its own small byte budget, so preparing it never eats into the playing channel's buffer.
            .setPlayerTargetBufferBytes(androidx.media3.exoplayer.analytics.PlayerId.PRELOAD.name, 8 * 1024 * 1024)
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
        val playbackUserAgent = ThumbnailPreviewManager.playbackUserAgent()
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient).setUserAgent(playbackUserAgent)
        // DefaultDataSource sends file:// (the local timeshift playlist) to a file source and
        // http(s) to the same OkHttp stack live TV already used. Recording playback is unchanged.
        val upstreamDataSourceFactory = if (isRecordingPlayback) {
            androidx.media3.datasource.DefaultDataSource.Factory(this)
        } else {
            androidx.media3.datasource.DefaultDataSource.Factory(this, httpDataSourceFactory)
        }
        // Live TV used to route through an on-disk cache (ManifestBypassCacheDataSource) to
        // power a DVR/rewind buffer beyond ExoPlayer's in-memory back buffer. Confirmed by
        // A/B testing on the Shield and phone: some providers (shop4uu at least) reuse the
        // same live segment URL while overwriting its content server-side — a URL-keyed cache
        // kept serving stale bytes and the fullscreen player reconnect-looped. This download
        // cache stays read-only during playback. Timeshift stores its own segments under
        // cacheDir/timeshift, named by sequence, and only when the provider window is too
        // short to seek. Streams that already advertise a DVR window play the provider URL.
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
        playbackDataSourceFactory = trackedDataSourceFactory
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(trackedDataSourceFactory)

        val tunnelingEnabled = prefs.tunneledPlaybackEnabledNow()
        val dv7FallbackEnabled = prefs.dv7FallbackEnabledNow()
        val audioPassthroughFallbackEnabled = prefs.audioPassthroughFallbackEnabledNow()

        // Dolby Vision on a screen that cannot show it plays its base layer (HDR10, HLG, SDR),
        // automatically; the Settings switch forces that everywhere. Only profiles that have a
        // base layer go this way: profile 5 decoded as plain HEVC turns purple and green, which
        // the old switch did to every profile by mapping all Dolby Vision to HEVC.
        val codecSelector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
        val dolbyVisionBaseLayer = com.iptvapp.util.DolbyVisionFallback.useBaseLayer(
            forcedInSettings = dv7FallbackEnabled,
            displayShowsDolbyVision = displayShowsDolbyVision(),
        )

        // Audio passthrough fallback: some TV boxes report E-AC3/DTS passthrough support
        // (AudioCapabilities queries the device/HDMI sink) but produce total silence or crash
        // when no AVR/receiver is actually connected to decode it. Forcing AudioCapabilities.
        // DEFAULT (stereo PCM only, no passthrough encodings) makes DefaultAudioSink always
        // transcode instead of passing through, at the cost of losing surround sound on setups
        // that genuinely do have a receiver — same opt-in, device-specific-workaround shape as
        // the DV7 fallback above.
        val renderersFactory = pictureProfiles.renderersFactory(
            this,
            codecSelector,
            passthroughAudio = audioPassthroughFallbackEnabled,
            dolbyVisionBaseLayer = dolbyVisionBaseLayer,
            audioDelay = channelAudioDelay,
        )

        val subtitlesEnabled = prefs.subtitlesEnabledNow()
        val preferredAudioLanguage = prefs.preferredAudioLanguageNow()
        val preferredSubtitleLanguage = prefs.preferredSubtitleLanguageNow()
        val dataSaver = prefs.dataSaverEnabledNow()
        // A factory, not one selector: the preload manager makes its own from the same settings.
        val trackSelectorFactory = androidx.media3.exoplayer.trackselection.TrackSelector.Factory { ctx ->
            androidx.media3.exoplayer.trackselection.DefaultTrackSelector(ctx).apply {
            parameters = buildUponParameters()
                .apply { if (tunnelingEnabled) setTunnelingEnabled(true) }
                // Data Saver: ExoPlayer still plays the only rendition when a stream has just one
                // (exceedVideoConstraintsIfNecessary defaults true), so a single-quality channel
                // is unaffected rather than broken.
                .apply { if (dataSaver) setMaxVideoSize(854, 480).setMaxVideoBitrate(1_200_000) }
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !subtitlesEnabled)
                .setSelectUndeterminedTextLanguage(subtitlesEnabled)
                .apply { if (preferredAudioLanguage.isNotBlank()) setPreferredAudioLanguage(preferredAudioLanguage) }
                .apply { if (subtitlesEnabled && preferredSubtitleLanguage.isNotBlank()) setPreferredTextLanguage(preferredSubtitleLanguage) }
                .build()
            }
        }

        // Built with its preload manager so a prepared neighbor channel can be played: Media3
        // needs both on one playback thread. See ZapPreloader.buildPlayer.
        return zapPreloader.buildPlayer(mediaSourceFactory, renderersFactory, loadControl, trackSelectorFactory)
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer
                exoPlayer.addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        maybeOfferExternalSubtitles(tracks)
                        val f = exoPlayer.videoFormat ?: return
                        Log.i("MKTV_QUALITY", "dataSaver=$dataSaver playing ${f.width}x${f.height} @ ${f.bitrate / 1000} kbps")
                        applyFrameRateMatch()
                    }
                })
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
                    val wasPlaying = if (!isVod && timeshiftSettingOn()) timeshiftIntendedPlaying()
                        else exoPlayer.playWhenReady
                    // Resume where playback was paused. Go Live is the only jump to the edge.
                    requestTimeshiftPlayback(!wasPlaying)
                    updatePlayPauseButton()
                    notifyPartyStateChange()
                    resetHideTimer()
                    if (wasPlaying) traktScrobble(::scrobblePauseCall)
                    else if (traktScrobbleStarted) traktScrobble(::scrobbleStartCall)
                    if (!isVod) refreshTimeshiftUi()
                }

                binding.btnDvrRewind.setOnClickListener {
                    if (!isVod) seekTimeshiftBy(-TimeshiftNavigator.REPLAY_30_MS)
                }
                binding.btnDvrRewind.setOnLongClickListener {
                    if (isVod) return@setOnLongClickListener false
                    seekTimeshiftBy(-TimeshiftNavigator.REPLAY_60_MS)
                    true
                }
                binding.btnLiveFwd10.setOnClickListener {
                    if (!isVod) seekTimeshiftBy(TimeshiftNavigator.REPLAY_10_MS)
                }

                binding.btnDvrLive.setOnClickListener { goToLiveEdge() }

                exoPlayer.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Keeps the PiP window's Play/Pause action icon in sync even when
                        // playback state changes for a reason other than tapping that action
                        // itself (buffering pausing/resuming playback, etc).
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                            setPictureInPictureParams(buildPipParams())
                        }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (isPlaying) reportFeed(feedTracker.onReady(now)) else feedTracker.onNotPlaying(now)
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) lastSeekAtMs = android.os.SystemClock.elapsedRealtime()
                    }

                    // Refreshes the PiP window's aspect ratio once the real video dimensions
                    // are known (e.g. entering PiP before the first frame decodes, or a channel
                    // switch mid-PiP to a different-shaped stream) — see pipAspectRatio kdoc.
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                            setPictureInPictureParams(buildPipParams())
                        }
                        applyFrameRateMatch()
                    }

                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        restoreNativeBehindIfReady()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> {
                                retryCount = 0
                                if (zapPreloadWhenReady) {
                                    zapPreloadWhenReady = false
                                    scheduleZapPreload()
                                }
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
                                val readyAt = android.os.SystemClock.elapsedRealtime()
                                reportFeed(feedTracker.onReady(readyAt))
                                if (!exoPlayer.playWhenReady) feedTracker.onNotPlaying(readyAt)
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
                                restoreNativeBehindIfReady()
                                if (isCatchup && catchupPendingSeekMs > 0L) {
                                    val fine = catchupPendingSeekMs
                                    catchupPendingSeekMs = 0L
                                    if (exoPlayer.isCurrentMediaItemSeekable) exoPlayer.seekTo(fine)
                                }
                                if (isVod && !traktScrobbleStarted) {
                                    traktScrobbleStarted = true
                                    traktScrobble(::scrobbleStartCall)
                                }
                            }
                            Player.STATE_BUFFERING -> {
                                binding.progressBuffering.visibility = View.VISIBLE
                                val stallAt = android.os.SystemClock.elapsedRealtime()
                                if (!timeshiftSeeking && stallAt - lastSeekAtMs > 5_000L && feedTracker.onBuffering(stallAt)) {
                                    maybeSwitchToBackupFeed()
                                }
                                // Neither onPlayerError nor STATE_ENDED necessarily fires if a
                                // stream just stalls indefinitely — that leaves a black screen
                                // with zero trace in the debug report. Log once if buffering
                                // hasn't cleared after 20s so a stall is at least visible.
                                bufferWatchdog?.let { hideHandler.removeCallbacks(it) }
                                bufferWatchdog = Runnable {
                                    if (player?.playbackState == Player.STATE_BUFFERING) {
                                        if (timeshiftStarting || timeshiftSession?.running == true) {
                                            scheduleRetry()
                                            return@Runnable
                                        }
                                        if (timeshiftFrozen) {
                                            val atEdge = timeshiftReading()?.let { TimeshiftNavigator.atLiveEdge(it) } ?: true
                                            if (atEdge) returnToProviderLive(tellUser = true)
                                            return@Runnable
                                        }
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
                                // A Start Over replay ran out (the show ended or it caught up with now).
                                if (startedOverFromLive) backToLive()
                                else if (handleTimeshiftEnded()) Unit
                                else if (!isVod) scheduleRetry()
                                else showUpNextIfAvailable()
                                if (isVod) traktScrobble(::scrobbleStopCall, progressOverride = 100f)
                            }
                            else -> binding.progressBuffering.visibility = View.GONE
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        // Releasing this player for Listen can itself report an error (a release
                        // timeout on a slow device) — not the channel's fault, and nothing to retry.
                        if (audioOnly) return
                        // Stopping the outgoing item while a timeshift buffer starts, or a glitch
                        // in the local playlist, is not the channel failing.
                        if (timeshiftStarting || handleTimeshiftPlayerError(error)) return
                        binding.progressBuffering.visibility = View.GONE
                        // A busy account says nothing about this channel's own reliability.
                        if (!outcomeRecordedForThisPlayback && !com.iptvapp.util.StreamErrors.isAccountInUse(error)) {
                            outcomeRecordedForThisPlayback = true
                            recordPlaybackOutcome(success = false)
                        }
                        if (!com.iptvapp.util.StreamErrors.isAccountInUse(error)) {
                            reportFeed(feedTracker.onFailed(android.os.SystemClock.elapsedRealtime()))
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
                        scheduleRetry(
                            suspectConnectionLimit = looksLikeConnectionLimitRejection(error),
                            accountInUse = com.iptvapp.util.StreamErrors.isAccountInUse(error)
                        )
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
        if (!isVod || isCatchup) return
        val progress = progressOverride ?: scrobbleProgress()
        // Uses a process-wide scope, not lifecycleScope — finish()'s stop-scrobble call must
        // survive the activity being destroyed right after this is fired.
        traktIoScope.launch {
            try { call(progress) } catch (_: Exception) { /* best-effort — never blocks playback */ }
        }
    }

    /** Startup holds the player paused. The button follows the pending request, not that hold. */
    private fun timeshiftIntendedPlaying(): Boolean = TimeshiftNavigator.intendedPlaying(
        starting = !isVod && timeshiftStarting && !timeshiftStartSettled,
        autoplay = timeshiftAutoplay,
        playWhenReady = player?.playWhenReady == true,
    )

    private fun updatePlayPauseButton() {
        val playing = if (!isVod && timeshiftSettingOn()) timeshiftIntendedPlaying()
            else player?.isPlaying == true
        binding.btnPlayPause.setImageResource(
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        binding.btnPlayPause.contentDescription = if (!isVod && timeshiftSettingOn()) {
            TimeshiftCopy.pauseDescription(playing)
        } else {
            "Play or pause"
        }
    }

    /** Red filled LIVE at the edge; gray outlined LIVE with the offset when behind. */
    private fun updateDvrLiveButton() {
        if (isVod) return
        refreshTimeshiftUi()
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
        return cause.responseCode == 403 || cause.responseCode == 429 ||
            com.iptvapp.util.StreamErrors.isAccountInUse(error)
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

    /** [accountInUse] means the provider explicitly said the login is busy on another device
     * (see StreamErrors) — the status line names that instead of a generic reconnect/give-up. */
    private fun scheduleRetry(suspectConnectionLimit: Boolean = false, accountInUse: Boolean = false) {
        if (audioOnly) return
        // A local buffer owns the only provider connection. Repair that, and do not open
        // the channel a second time. Giving up drops the buffer and comes back at live.
        if (timeshiftStarting) return
        // The provider read already stopped. Finishing the saved window must not open a
        // second connection. At the end of that window, come back to the live edge.
        if (timeshiftFrozen) {
            val atEdge = timeshiftReading()?.let { TimeshiftNavigator.atLiveEdge(it) } ?: false
            if (atEdge) returnToProviderLive(tellUser = true)
            return
        }
        if (timeshiftSession?.running == true) {
            timeshiftRepairs++
            if (timeshiftRepairs <= 3) {
                timeshiftSession?.forceReconnect()
                repairLocalPlayer()
                return
            }
            returnToProviderLive(tellUser = true)
            return
        }
        val wasBehindLive = !isVod && (
            timeshiftState == TimeshiftPlaybackState.PAUSED ||
                timeshiftState == TimeshiftPlaybackState.BEHIND_LIVE ||
                timeshiftState == TimeshiftPlaybackState.SEEKING ||
                timeshiftState == TimeshiftPlaybackState.RETURNING_TO_LIVE
            )
        val givenUpText = if (accountInUse) com.iptvapp.util.StreamErrors.ACCOUNT_IN_USE_MESSAGE
            else "Stream unavailable after $maxRetries attempts"
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
                        it.setMediaItem(itemWithExternalSubtitle(streamUrl), it.currentPosition.takeIf { pos -> pos > 0L } ?: 0L)
                        it.prepare()
                        it.playWhenReady = true
                    }
                }
                return
            }
            binding.tvRetryStatus.text = givenUpText
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
                return
            }
            // Live channels already fail over to another configured provider by name match (see
            // below) — VOD/series had no equivalent at all, they just gave up outright even when
            // the exact same title exists under a different catalog id on another provider this
            // device is already logged into. Reuses the same title-search machinery Watch Party's
            // "different provider, same movie" fallback already relies on
            // (findWatchPartyVodMatches/findWatchPartyEpisodeMatch) — that's genuinely just a
            // by-name cross-catalog lookup with a party-flavored name; nothing here is party-
            // specific, so a solo give-up gets the identical benefit for free.
            // A replay has no "same title on another provider" to look for.
            if (!isCatchup) attemptVodFailover()
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
                    // The catalog name: an extra provider's title also carries " · nickname".
                    val ownId = if (serverIndex == -1) streamId else mergedStreamId
                    val name = repository.feedCatalogName(serverIndex, ownId) ?: streamTitle.substringBeforeLast(" · ")
                    repository.findFailoverChannel(name, serverIndex)
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
                    // streamId is the primary provider's id only; an extra provider's is mergedStreamId.
                    streamId = if (matchServerIndex == -1) matchStreamId else -1
                    mergedStreamId = if (matchServerIndex == -1) -1 else matchStreamId
                    applyChannelAudioDelay()
                    // This path plays the new copy without loadStream. Score it as the new copy.
                    reportFeed(feedTracker.open(currentFeedKey(), android.os.SystemClock.elapsedRealtime(), automatic = true))
                    rebuildZapListForCurrentCopy()
                    streamUrl = try {
                        if (matchServerIndex == -1) repository.getLiveStreamUrl(matchStreamId)
                        else repository.getMergedLiveStreamUrl(matchServerIndex, matchStreamId)
                    } catch (_: Exception) {
                        binding.tvRetryStatus.text = givenUpText
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
                    if (wasBehindLive) announceBackToLive()
                } else {
                    binding.tvRetryStatus.text = givenUpText
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
        // A native DVR window does not survive a new media item (wasBehindLive, above).
        val resumeAt = if (isVod) (player?.currentPosition ?: 0L) else 0L
        val catchupResumeAt = if (isCatchup) vodPositionMs() else 0L
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
            } else if (accountInUse) {
                "⚠ Account in use on another device — your provider allows one stream at a time. Retrying in ${delaySec}s…"
            } else {
                "● Reconnecting in ${delaySec}s$suffix…"
            }
            binding.tvRetryStatus.visibility = View.VISIBLE
            com.iptvapp.IptvApplication.logPlaybackEvent(
                applicationContext,
                "RETRY SCHEDULED: isVod=$isVod streamId=$streamId url=$streamUrl attempt=$attempt delayMs=$backoffMs " +
                    "playerState=${player?.playbackState} suspectConnectionLimit=$suspectConnectionLimit accountInUse=$accountInUse activeRecording=${activeRecording?.channelName}"
            )
            delay(backoffMs)
            retryCount++
            if (isCatchup) {
                loadCatchupAt(catchupResumeAt)
                player?.playWhenReady = true
                return@launch
            }
            player?.let {
                val item = if (isVod) itemWithExternalSubtitle(streamUrl) else MediaItem.fromUri(streamUrl)
                if (isVod && resumeAt > 0L) it.setMediaItem(item, resumeAt)
                else it.setMediaItem(item)
                it.prepare()
                it.playWhenReady = true
            }
            if (wasBehindLive && !isVod) announceBackToLive()
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
        // Cast opens the provider URL itself. The timeshift pump must let go of that connection.
        stopTimeshift()
        val localPositionMs = if (isVod) (player?.currentPosition?.takeIf { it > 0 } ?: resumePositionMs) else 0L
        player?.stop()
        player?.clearMediaItems()
        releaseZapPreload()
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
            val localIp = getLocalIpAddress(session.castDevice?.inetAddress)
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
                    // Null when the path isn't a recording location the proxy will serve.
                    isRecordingPlayback -> proxy.proxyLocalFile(directUrl) ?: directUrl
                    isRawTsLive -> proxy.proxyLiveUrl(directUrl, "ExoPlayerLib/1.4.1 (Linux; Android)")
                    else -> proxy.proxyUrl(directUrl)
                }
            } else {
                directUrl
            }

            // Never the URL itself: it carries the cast's token (proxied) or the account login (direct).
            if (com.iptvapp.BuildConfig.DEBUG) Log.d("CastDebug", "cast via ${if (castUrl == directUrl) "direct URL" else "proxy"}")

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
                .apply {
                    // Two live casts are known to be MPEG-TS with audio and video muxed together: the
                    // main provider's own Xtream .m3u8 (streamId set), and LiveHlsSession repackaging a
                    // raw .ts channel. Left unstated, the receiver guesses the segment format, and a
                    // wrong guess showed as speech a little ahead of or behind the lips for the whole
                    // cast (other apps on the same TV fine). A merged or external URL is forwarded as
                    // is and may be fMP4 or packed audio, so the receiver keeps guessing for those.
                    val knownMuxedTs = isRawTsLive || streamId != -1
                    if (!isVod && !isRecordingPlayback && knownMuxedTs && contentType == "application/x-mpegURL") {
                        setHlsSegmentFormat(com.google.android.gms.cast.HlsSegmentFormat.TS)
                        setHlsVideoSegmentFormat(com.google.android.gms.cast.HlsVideoSegmentFormat.MPEG2_TS)
                    }
                }
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
            val onLoadFailed = watchCast(client, proxied = localIp != null)
            client.load(loadRequest).setResultCallback { result ->
                Log.d("CastDebug", "load result: success=${result.status.isSuccess} code=${result.status.statusCode}")
                if (!result.status.isSuccess) onLoadFailed()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyFrameRateMatch()
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
        clearDisplayMode()
        super.onPause()
        castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
    }

    private fun sportsActiveNow(): Boolean {
        if (isVod || audioOnly || isRecordingPlayback || castSession != null) return false
        return SportsMode.active(prefs.sportsModeNow(), liveNowShow?.title, sportsSessionOn)
    }

    private fun scoreOverlayWanted(): Boolean = scoreOverlaySession ?: prefs.sportsScoreOverlayNow()

    private fun setupSportsAndCatchUp() {
        binding.btnSportsMode.setOnClickListener {
            resetHideTimer()
            sportsSessionOn = !sportsActiveNow()
            syncSportsPlayback()
        }
        binding.btnPinTeams.setOnClickListener {
            resetHideTimer()
            pinCurrentTeams()
        }
        binding.btnScoreOverlay.setOnClickListener {
            resetHideTimer()
            scoreOverlaySession = !scoreOverlayWanted()
            applySportsChrome()
        }
        binding.btnWatchLive.setOnClickListener {
            // Stays on the live stream. Does not seek.
            val start = liveNowShow?.startMs ?: return@setOnClickListener
            smartCatchupDismissedStartMs = start
            refreshSmartCatchUp()
        }
        binding.btnStartFromBeginning.setOnClickListener {
            val start = liveNowShow?.startMs ?: return@setOnClickListener
            smartCatchupDismissedStartMs = start
            refreshSmartCatchUp()
            startOverCurrentShow()
        }
    }

    private fun clearLiveProgramme() {
        liveNowShow = null
        pictureProgrammeTitle = ""
        pictureTitleJob?.cancel()
        appliedPictureName = null
        smartCatchupArchive = null
        smartCatchupArchiveKey = null
        smartCatchupDismissedStartMs = 0L
        binding.smartCatchupBar.visibility = View.GONE
        binding.tvSportsScore.visibility = View.GONE
    }

    private fun syncSportsPlayback() {
        if (isFinishing || isVod || audioOnly || castSession != null || isCatchup || startedOverFromLive) {
            applyFrameRateMatch()
            applySportsChrome()
            refreshSmartCatchUp()
            return
        }
        val want = sportsActiveNow()
        applySportsChrome()
        applyFrameRateMatch()
        val saved = prefs.timeshiftMinutesNow()
        if (!want && saved == 0 && (timeshiftSession != null || timeshiftStarting || sportsLoadControlOn)) {
            sportsRebuildAfterReady = false
            val play = timeshiftIntendedPlaying()
            // Captured before stopTimeshift clears NATIVE. A rewind in the provider window
            // has to survive the rebuild; a sports-only local pump has no provider position.
            // Assigned after the old player is released so its callbacks cannot consume it.
            val behind = nativeBehindToKeep()
            timeshiftDirectFallback = false
            stopTimeshift()
            if (player == null || sportsLoadControlOn) {
                reopenCurrent(playLocal = null, positionMs = 0L, play = play, hold = false)
                pendingNativeBehindMs = behind
            } else if (streamUrl.isNotEmpty()) {
                playProviderUrl(streamUrl, play)
                pendingNativeBehindMs = behind
            }
            return
        }
        val sessionRunning = timeshiftSession?.running == true || timeshiftStarting
        val needSession = streamUrl.isNotEmpty() && SportsMode.needsSessionBuffer(
            sportsOn = want,
            savedMinutes = saved,
            sessionRunning = sessionRunning,
            directFallback = timeshiftDirectFallback,
            nativeSettled = timeshiftMode == TimeshiftSource.NATIVE
        )
        val needRebuild = player != null && want != sportsLoadControlOn
        if (timeshiftStarting && needRebuild) {
            sportsRebuildAfterReady = true
            return
        }
        if (!needSession && !needRebuild) return
        // A paused or storage-frozen window keeps its files after the pump stops.
        val local = timeshiftSession?.sessionDir?.resolve("index.m3u8")?.takeIf {
            it.exists() && (timeshiftMode == TimeshiftSource.LOCAL || timeshiftFrozen)
        }
        val pos = player?.currentPosition ?: 0L
        val play = if (player == null) true else timeshiftIntendedPlaying()
        if (needRebuild) {
            // A native DVR seek is an offset from the live edge. The local playlist keeps
            // its own absolute position, and a new pump starts at the edge.
            val behind = if (local == null && !needSession) nativeBehindToKeep() else C.TIME_UNSET
            reopenCurrent(if (needSession) null else local, pos, play, hold = needSession)
            pendingNativeBehindMs = behind
        }
        if (needSession) {
            pendingNativeBehindMs = C.TIME_UNSET
            startTimeshift(streamUrl)
        }
    }

    /**
     * Swaps the player so a sports buffer can take effect. The timeshift pump, when one is
     * already running, keeps the provider connection. [hold] waits for [startTimeshift]
     * instead of opening the provider URL from here.
     */
    private fun reopenCurrent(playLocal: java.io.File?, positionMs: Long, play: Boolean, hold: Boolean) {
        timeshiftAutoplay = play
        binding.playerView.player = null
        player?.release()
        player = null
        releaseZapPreload()
        player = buildPlayer()
        when {
            playLocal != null -> {
                val budgetMs = replayBudgetMinutes().coerceAtLeast(1) * 60_000L
                playLocalPlaylist(playLocal, budgetMs, startMs = positionMs.coerceAtLeast(0L))
            }
            hold -> Unit
            streamUrl.isNotEmpty() -> playProviderUrl(streamUrl, play)
        }
    }

    private fun applySportsChrome() {
        val liveControls = !isVod && !audioOnly && castSession == null
        val sportsOn = sportsActiveNow()
        binding.btnSportsMode.visibility = if (liveControls) View.VISIBLE else View.GONE
        binding.btnSportsMode.text = if (sportsOn) "Sports on" else "Sports"
        binding.btnSportsMode.contentDescription = if (sportsOn) {
            "Sports mode, on. Low-latency buffer for this viewing. Go Live and instant replay stay on the playback controls."
        } else {
            "Sports mode, off. Turns on the low-latency buffer, instant replay, and Go Live for this viewing."
        }
        val teams = SportsMode.teamsInTitle(liveNowShow?.title.orEmpty())
        val showPin = sportsOn && teams.size == 2
        binding.btnPinTeams.visibility = if (showPin) View.VISIBLE else View.GONE
        if (showPin) {
            binding.btnPinTeams.contentDescription = "Pin ${teams[0]} and ${teams[1]} as favorite teams"
        }
        binding.btnScoreOverlay.visibility = if (sportsOn) View.VISIBLE else View.GONE
        val scoreOn = scoreOverlayWanted()
        binding.btnScoreOverlay.text = if (scoreOn) "Score on" else "Score"
        binding.btnScoreOverlay.contentDescription = if (scoreOn) "Hide the score" else "Show the score"
        val score = if (sportsOn && scoreOn) SportsMode.scoreInTitle(liveNowShow?.title.orEmpty()) else null
        if (score == null) {
            binding.tvSportsScore.visibility = View.GONE
        } else {
            binding.tvSportsScore.text = score
            binding.tvSportsScore.contentDescription = SportsMode.scoreDescription(score)
            binding.tvSportsScore.visibility = View.VISIBLE
        }
    }

    private fun pinCurrentTeams() {
        val teams = SportsMode.teamsInTitle(liveNowShow?.title.orEmpty())
        if (teams.isEmpty()) return
        lifecycleScope.launch {
            var stored = prefs.favoriteTeams.first()
            val added = ArrayList<String>()
            for (team in teams) {
                val next = SportsMode.addFavorite(stored, team)
                if (next != stored) added += team
                stored = next
            }
            if (added.isNotEmpty()) prefs.setFavoriteTeams(stored)
            val full = added.size < teams.size && SportsMode.parseTeams(stored).size >= SportsMode.MAX_FAVORITE_TEAMS
            val message = when {
                added.isEmpty() -> "Already in favorite teams"
                added.size == 1 && full -> "Pinned ${added[0]}. The team list is full."
                added.size == 1 -> "Pinned ${added[0]}"
                full -> "Pinned ${added.joinToString(" and ")}. The team list is full."
                else -> "Pinned ${added.joinToString(" and ")}"
            }
            Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_SHORT).show()
            applySportsChrome()
        }
    }

    /** Offset to keep when the provider URL itself is the DVR. Local files use their own position. */
    private fun nativeBehindToKeep(): Long {
        if (timeshiftMode != TimeshiftSource.NATIVE) return C.TIME_UNSET
        val reading = timeshiftReading() ?: return C.TIME_UNSET
        if (!reading.seekable || TimeshiftNavigator.atLiveEdge(reading)) return C.TIME_UNSET
        return TimeshiftNavigator.behindMs(reading)
    }

    private fun restoreNativeBehindIfReady() {
        val behind = pendingNativeBehindMs
        if (behind == C.TIME_UNSET || isVod) return
        val p = player ?: return
        val reading = timeshiftReading()
        if (reading == null || !reading.seekable) {
            if (p.playbackState == Player.STATE_READY && !p.isCurrentMediaItemSeekable) {
                pendingNativeBehindMs = C.TIME_UNSET
            }
            return
        }
        val target = TimeshiftNavigator.nativeResumeMs(
            behind, true, reading.earliestMs, reading.liveEdgeMs
        ) ?: run {
            pendingNativeBehindMs = C.TIME_UNSET
            return
        }
        pendingNativeBehindMs = C.TIME_UNSET
        p.seekTo(target)
    }

    /** Whether the screen this plays on reports Dolby Vision (an HDR10-only TV does not). */
    @Suppress("DEPRECATION")
    private fun displayShowsDolbyVision(): Boolean {
        val screen = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay)
            ?: return false
        val types = try {
            if (Build.VERSION.SDK_INT >= 34) screen.mode.supportedHdrTypes
            else screen.hdrCapabilities?.supportedHdrTypes
        } catch (_: Exception) {
            null
        } ?: return false
        return types.contains(android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayModes(): List<SportsMode.RefreshMode> {
        val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        val modes = screen?.supportedModes ?: return emptyList()
        return modes.map { SportsMode.RefreshMode(it.modeId, it.refreshRate, it.physicalWidth, it.physicalHeight) }
    }

    /**
     * Settings > Match frame rate, for what is playing now. See FrameRateMatch. Sports counts
     * as Sports Mode's buffer or, in the Auto picture mode, a live game found from its guide title,
     * so a match on live TV gets the same smooth motion as a film.
     */
    private fun frameMatchWanted(): Boolean = com.iptvapp.util.FrameRateMatch.wanted(
        setting = prefs.frameRateMatchNow(),
        sportsOn = sportsActiveNow() || (pictureLive() &&
            PictureMode.profileName(prefs.pictureModeNow(), true, programmeTitle(), false) == PictureMode.PROFILE_SPORTS),
        onDemand = isVod || isRecordingPlayback,
        casting = castSession != null,
        audioOnly = audioOnly,
    )

    private fun applyFrameRateMatch() {
        updatePictureProfile()
        if (!frameMatchWanted()) {
            setPreferredDisplayMode(0)
            return
        }
        val fps = player?.videoFormat?.frameRate ?: return
        val size = currentDisplaySize()
        setPreferredDisplayMode(SportsMode.displayModeId(fps, currentDisplayModes(), size.first, size.second))
    }

    @Suppress("DEPRECATION")
    private fun currentDisplaySize(): Pair<Int, Int> {
        val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        val mode = screen?.mode ?: return 0 to 0
        return mode.physicalWidth to mode.physicalHeight
    }

    private fun clearDisplayMode() {
        setPreferredDisplayMode(0)
    }

    private fun setPreferredDisplayMode(modeId: Int) {
        if (appliedDisplayModeId == modeId) return
        val attrs = window.attributes
        attrs.preferredDisplayModeId = modeId
        window.attributes = attrs
        appliedDisplayModeId = modeId
    }

    private fun placeSportsChrome(overlayOpen: Boolean) {
        val top = ((if (overlayOpen) 76 else 12) * resources.displayMetrics.density).toInt()
        fun margin(view: View) {
            val lp = view.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
            if (lp.topMargin == top) return
            lp.topMargin = top
            view.layoutParams = lp
        }
        margin(binding.tvSportsScore)
        margin(binding.smartCatchupBar)
    }

    private val catchupOfferRunnable = Runnable { refreshSmartCatchUp() }

    private fun refreshSmartCatchUp(nowMs: Long = System.currentTimeMillis()) {
        val show = liveNowShow
        val blocked = isVod || audioOnly || isRecordingPlayback || castSession != null ||
            partyCode.isNotEmpty() || liveCopyId() == -1
        val archive = smartCatchupArchive == true && smartCatchupArchiveKey == (serverIndex to liveCopyId())
        val offer = show != null && SmartCatchUp.shouldOffer(
            archiveEnabled = archive,
            programmeStartMs = show.startMs,
            programmeStopMs = show.stopMs,
            nowMs = nowMs,
            alreadyOnCatchup = isCatchup || startedOverFromLive,
            dismissedForStartMs = smartCatchupDismissedStartMs,
            blocked = blocked
        )
        val classicStartOver = show != null && archive && !blocked && !isCatchup && !startedOverFromLive &&
            nowMs - show.startMs > 60_000L
        if (!isVod && show != null) {
            binding.btnStartOver.visibility = if (!offer && classicStartOver) View.VISIBLE else View.GONE
        }
        if (!offer) {
            binding.smartCatchupBar.visibility = View.GONE
            seekHandler.removeCallbacks(catchupOfferRunnable)
            // Controls hide and stop the one-second ticker. A show that is not at 35 minutes
            // yet still needs a check at that moment, or the offer never appears.
            val waiting = show
            if (waiting != null) {
                val wait = SmartCatchUp.msUntilOffer(
                    archiveEnabled = archive,
                    programmeStartMs = waiting.startMs,
                    programmeStopMs = waiting.stopMs,
                    nowMs = nowMs,
                    alreadyOnCatchup = isCatchup || startedOverFromLive,
                    dismissedForStartMs = smartCatchupDismissedStartMs,
                    blocked = blocked
                )
                if (wait != null) seekHandler.postDelayed(catchupOfferRunnable, wait)
            }
            return
        }
        val programme = show ?: return
        val label = SmartCatchUp.startedLabel(programme.startMs, nowMs)
        if (binding.tvSmartCatchup.text?.toString() != label) {
            binding.tvSmartCatchup.text = label
            binding.tvSmartCatchup.contentDescription = label
        }
        binding.btnWatchLive.contentDescription = "Watch live"
        binding.btnStartFromBeginning.contentDescription = "Start from the beginning"
        binding.smartCatchupBar.visibility = View.VISIBLE
        binding.btnStartOver.visibility = View.GONE
        seekHandler.removeCallbacks(catchupOfferRunnable)
        seekHandler.postDelayed(catchupOfferRunnable, 30_000L)
    }

    private fun loadStream(url: String) {
        applyChannelAudioDelay()
        if (::quickPeek.isInitialized) quickPeek.syncPlaying()
        // Another video (or a reload): a place being chosen in the old one no longer applies.
        cancelSeekPreview()
        if (url != thumbnailSourceUrl) {
            thumbnailSourceUrl = null
            thumbnailSourceJob?.cancel()
            thumbnailManager?.setSource(null)
        }
        // Same URL is a reload (rotation, retry). A different one is a new programme.
        if (url != streamUrl) {
            subtitleGeneration += 1
            subtitleJob?.cancel()
            externalSubtitle = null
            externalSubtitlePath = null
            subtitleOfferShown = false
        }
        refreshPictureFromGuide()
        com.iptvapp.remote.RemoteControlServer.nowPlaying = streamTitle
        openFeedTracking(automatic = backupSwitchOpening)
        retryCount = 0
        vodFormatFallbackTried = false
        retryJob?.cancel()
        streamUrl = url
        pendingNativeBehindMs = C.TIME_UNSET
        timeshiftDirectFallback = false
        timeshiftAutoplay = true
        timeshiftEpg = emptyList()
        liveEdgeEpgText = ""
        val activeSession = castSession
        if (activeSession != null) {
            stopTimeshift()
            stopLocalAndCast(activeSession)
        } else {
            // The buffer is chosen when the player is built. Match it before opening the stream.
            if (player != null && sportsLoadControlOn != sportsActiveNow()) {
                reopenCurrent(playLocal = null, positionMs = 0L, play = timeshiftAutoplay, hold = true)
            }
            if (shouldBufferTimeshift()) {
                startTimeshift(url)
            } else {
                stopTimeshift()
                playProviderUrl(url, playWhenReady = true)
            }
        }
    }

    /** The live copy playing now, or null for anything feed scores do not cover. */
    private fun currentFeedKey(): FeedTracker.Key? {
        if (isVod || isCatchup || isRecordingPlayback || startedOverFromLive) return null
        val id = if (serverIndex == -1) streamId else mergedStreamId
        return if (id < 0) null else FeedTracker.Key(serverIndex, id)
    }

    /**
     * Starts tracking the copy about to open. A channel the user picked clears the copies tried;
     * a backup switch keeps them, and the tracker keeps its switch budget.
     */
    private fun openFeedTracking(automatic: Boolean) {
        if (!automatic) {
            backupSwitchJob?.cancel()
            backupFeedsTried.clear()
        }
        reportFeed(feedTracker.open(currentFeedKey(), android.os.SystemClock.elapsedRealtime(), automatic))
        feedFlushJob?.cancel()
        // Watching time is saved every few minutes, so a long evening is not lost to a crash.
        feedFlushJob = lifecycleScope.launch {
            while (true) {
                delay(5 * 60_000L)
                reportFeed(feedTracker.flush(android.os.SystemClock.elapsedRealtime()))
            }
        }
    }

    /** Fire and forget, like recordPlaybackOutcome: a DB write must never disturb playback. */
    private fun reportFeed(reports: List<FeedTracker.Report>) {
        if (reports.isEmpty()) return
        // GlobalScope like the bandwidth flush: the last report is sent from onStop, and leaving
        // the player cancels lifecycleScope before the write lands. A small Room write is safe.
        kotlinx.coroutines.GlobalScope.launch {
            for (r in reports) {
                try {
                    when (r) {
                        is FeedTracker.Report.Started ->
                            repository.recordFeedStart(r.key.serverIndex, r.key.streamId, r.played, r.tuneMs)
                        is FeedTracker.Report.Watched ->
                            repository.recordFeedWatching(r.key.serverIndex, r.key.streamId, r.minutes, r.stalls)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * After a backup switch or failover, everything outside this screen follows the new copy:
     * up/down zaps through its provider's list (rebuilt from the copy's own category), the
     * recording screens see which account is streaming, and a cold start resumes on it, the same
     * as a channel change by hand.
     */
    private suspend fun rebuildZapListForCurrentCopy() {
        try {
            prefs.setLivePlaybackActive(serverIndex)
            prefs.setLastPlayedChannel(serverIndex, if (serverIndex == -1) streamId else mergedStreamId)
            if (serverIndex != -1) {
                val current = repository.getMergedChannelByIndexAndId(serverIndex, mergedStreamId) ?: return
                mergedChannels = repository.getMergedChannelsByCategory(serverIndex, current.categoryId).first()
                mergedCurrentIndex = mergedChannels.indexOfFirst { it.streamId == mergedStreamId }
            } else {
                // The list this player opened with may be a filtered one without this copy in it.
                if (channels.none { it.streamId == streamId }) channels = repository.getAllChannels().first()
                currentIndex = channels.indexOfFirst { it.streamId == streamId }
            }
            currentZapStreamId = if (serverIndex == -1) streamId else -1
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    /**
     * The copy playing now has stalled three times in two minutes. Move to the best-scoring
     * other copy of the same channel, on any provider, and say so. Left alone while the viewer
     * is behind live in a timeshift window (a new copy would throw that window away), in a
     * watch party (the host picks the feed), while casting, or with the setting off.
     */
    /** Checked before the lookup and again just before switching: either can change while it runs. */
    private fun backupSwitchAllowed(): Boolean {
        if (!prefs.autoBackupFeedNow() || castSession != null || isPartyMember || isPartyHost || audioOnly) return false
        if (timeshiftFrozen || player?.playWhenReady != true) return false
        // Behind live in either window, the local one or the provider's own DVR. A new copy
        // opens at live and would skip what the viewer has not watched yet.
        if (timeshiftState != TimeshiftPlaybackState.LIVE) return false
        if (timeshiftMode == TimeshiftSource.LOCAL) {
            val atLive = timeshiftReading()?.let { TimeshiftNavigator.atLiveEdge(it) } ?: true
            if (!atLive) return false
        }
        return true
    }

    private fun maybeSwitchToBackupFeed() {
        val from = currentFeedKey() ?: return
        if (!backupSwitchAllowed()) return
        if (backupSwitchJob?.isActive == true) return
        val title = streamTitle
        backupSwitchJob = lifecycleScope.launch {
            // The catalog name: an extra provider's title also carries " · nickname".
            val name = try { repository.feedCatalogName(from.serverIndex, from.streamId) } catch (_: Exception) { null }
                ?: title.substringBeforeLast(" · ")
            val backup = try {
                repository.findBackupFeed(name, from.serverIndex, from.streamId, backupFeedsTried)
            } catch (_: Exception) { null }
            // The user changed channel while this was looking.
            if (currentFeedKey() != from || streamTitle != title) return@launch
            if (backup == null) {
                com.iptvapp.IptvApplication.logPlaybackEvent(
                    applicationContext,
                    "BACKUP FEED: \"$title\" serverIndex=${from.serverIndex} streamId=${from.streamId} keeps stalling, no other copy worth trying"
                )
                return@launch
            }
            val url = try {
                if (backup.serverIndex == -1) repository.getLiveStreamUrl(backup.streamId)
                else repository.getMergedLiveStreamUrl(backup.serverIndex, backup.streamId)
            } catch (_: Exception) { return@launch }
            if (currentFeedKey() != from || !backupSwitchAllowed()) return@launch
            com.iptvapp.IptvApplication.logPlaybackEvent(
                applicationContext,
                "BACKUP FEED: \"$title\" serverIndex=${from.serverIndex} streamId=${from.streamId} kept stalling -> " +
                    "\"${backup.name}\" serverIndex=${backup.serverIndex} streamId=${backup.streamId} score=${backup.score}"
            )
            backupFeedsTried += from.serverIndex to from.streamId
            bandwidthTracker?.updateServerIndex(backup.serverIndex)
            serverIndex = backup.serverIndex
            // streamId is the primary provider's id only. An extra provider's copy lives in
            // mergedStreamId, the same split playMergedChannel and the guide rely on.
            streamId = if (backup.serverIndex == -1) backup.streamId else -1
            mergedStreamId = if (backup.serverIndex == -1) -1 else backup.streamId
            streamTitle = if (backup.serverIndex == -1 || backup.providerName.isBlank()) backup.name
                else "${backup.name} · ${backup.providerName}"
            val where = if (backup.serverIndex == from.serverIndex || backup.providerName.isBlank()) "another copy of this channel"
                else backup.providerName
            Toast.makeText(this@PlayerActivity, "Switched to backup feed: $where", Toast.LENGTH_LONG).show()
            backupSwitchOpening = true
            try {
                loadStream(url)
            } finally {
                backupSwitchOpening = false
            }
            // Nothing suspends between the identity change and loadStream, so a channel picked by
            // hand cannot land in between. The list and prefs follow once the new copy is opening.
            rebuildZapListForCurrentCopy()
        }
    }

    private fun timeshiftSettingOn(): Boolean = !isVod && !audioOnly && replayBudgetMinutes() > 0

    private fun replayBudgetMinutes(): Int =
        SportsMode.replayMinutes(prefs.timeshiftMinutesNow(), sportsActiveNow())

    private fun shouldBufferTimeshift(): Boolean {
        if (!timeshiftSettingOn() || isRecordingPlayback || timeshiftDirectFallback) return false
        return TimeshiftLimits.resolve(replayBudgetMinutes(), cacheDir.usableSpace).localEnabled
    }

    /**
     * Pause and play have to be remembered while a timeshift buffer is still starting.
     * The playlist, and the direct provider fallback, apply [timeshiftAutoplay] when they
     * become ready. Pausing only the empty player would be overwritten.
     */
    private fun requestTimeshiftPlayback(play: Boolean) {
        if (!isVod) {
            timeshiftAutoplay = play
            if (!play) timeshiftReturning = false
        }
        if (play) player?.play() else player?.pause()
    }

    private fun playProviderUrl(url: String, playWhenReady: Boolean) {
        val p = player ?: return
        updatePictureProfile()
        val live = directLivePlayback()
        val mediaId = if (live) currentLiveMediaId() else null
        val item = providerMediaItem(url, mediaId, sports = live && sportsLoadControlOn)
        val preloaded = if (live && mediaId != null) zapPreloader.source(mediaId, sportsLoadControlOn) else null
        // Other preloads hold connections. Free them before this stream asks the account for one;
        // scheduleZapPreload prepares the new neighbors once this channel is playing.
        // A neighbor lookup still running belongs to the channel before this one.
        zapPreloadJob?.cancel()
        zapPreloadJob = null
        zapPreloader.keepOnly(if (preloaded != null) mediaId else null)
        if (preloaded != null) p.setMediaSource(preloaded) else p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = playWhenReady
        // Neighbors wait until this channel is playing; see onPlaybackStateChanged and scheduleZapPreload.
        zapPreloadWhenReady = live
    }

    /**
     * Drops the prepared neighbors, for a cast or a timeshift pump that needs the connection.
     * The manager stays: it belongs to the player and is released with it ([releasePlayerPreload]).
     */
    private fun releaseZapPreload() {
        zapPreloadJob?.cancel()
        zapPreloadJob = null
        zapPreloader.clear()
    }

    private fun releasePlayerPreload() {
        releaseZapPreload()
        zapPreloader.release()
    }

    private fun currentZapIndex(): Int = if (serverIndex == -1) currentIndex else mergedCurrentIndex

    private fun currentLiveMediaId(): String {
        val id = if (serverIndex == -1) streamId else mergedStreamId
        return "live:$serverIndex:$id"
    }

    /** A film, not a series episode, a catch-up replay, or a recording. */
    private fun pictureMovie(): Boolean =
        isVod && !isCatchup && episodeSeriesId == -1 && !isRecordingPlayback

    /** A live channel. Catch-up and recordings keep the picture they already had. */
    private fun pictureLive(): Boolean =
        !isVod && !isRecordingPlayback && !audioOnly && !isCatchup

    private fun programmeTitle(): String {
        // A show that has ended says nothing about what is on now; the guide lookup does.
        val guide = liveNowShow?.takeIf { it.stopMs > System.currentTimeMillis() }?.title?.takeIf { it.isNotBlank() }
            ?: pictureProgrammeTitle.takeIf { it.isNotBlank() }
        return guide ?: streamTitle
    }

    private fun directLivePlayback(): Boolean =
        !isVod && !isCatchup && !isRecordingPlayback && !audioOnly

    private fun updatePictureProfile() {
        val name = PictureMode.profileName(
            prefs.pictureModeNow(),
            pictureLive(),
            programmeTitle(),
            pictureMovie()
        )
        if (name != appliedPictureName) {
            appliedPictureName = name
            appliedPictureProfile = PictureProfiles.lookup(this, name)
        }
        pictureProfiles.apply(appliedPictureProfile)
    }

    private fun providerMediaItem(url: String, mediaId: String?, sports: Boolean): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (mediaId != null) builder.setMediaId(mediaId)
        if (sports) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(SportsMode.TARGET_OFFSET_MS)
                    .setMinOffsetMs(SportsMode.MIN_OFFSET_MS)
                    .setMaxOffsetMs(SportsMode.MAX_OFFSET_MS)
                    .setMinPlaybackSpeed(1f)
                    .setMaxPlaybackSpeed(1f)
                    .build()
            )
        }
        if (mediaId == null) externalSubtitle?.let { builder.setSubtitleConfigurations(listOf(it)) }
        return builder.build()
    }

    private fun itemWithExternalSubtitle(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        externalSubtitle?.let { builder.setSubtitleConfigurations(listOf(it)) }
        return builder.build()
    }

    private fun externalSubtitleChoices(): Boolean =
        ExternalSubtitles.offersFor(isVod, isCatchup, isRecordingPlayback) && castSession == null

    private fun maybeOfferExternalSubtitles(tracks: androidx.media3.common.Tracks) {
        if (!externalSubtitleChoices() || subtitleOfferShown || externalSubtitle != null) return
        val hasText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
        val hasPictureOrSound = tracks.groups.any {
            (it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_VIDEO) && it.length > 0
        }
        if (!hasPictureOrSound) return
        subtitleOfferShown = true
        if (hasText || isFinishing) return
        showExternalSubtitleOffer()
    }

    private fun showExternalSubtitleOffer() {
        AlertDialog.Builder(this)
            .setTitle("No subtitles in this file")
            .setMessage("Search OpenSubtitles, or open an .srt file on this device.")
            .setPositiveButton("Search") { _, _ -> searchOpenSubtitles() }
            .setNeutralButton("Open a file") { _, _ -> openSubtitleFile() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun openSubtitleFile() {
        val previousResume = resumePositionMs
        val previousPaused = resumePausedAfterPicker
        val p = player
        if (p != null && isVod && !isCatchup) {
            val pos = p.currentPosition
            if (pos > 0L) resumePositionMs = pos
            resumePausedAfterPicker = !p.playWhenReady
            subtitlePickerPending = true
        }
        subtitlePickGeneration = subtitleGeneration
        subtitlePickUrl = streamUrl
        try {
            pickSubtitleFile.launch(arrayOf("*/*"))
        } catch (_: android.content.ActivityNotFoundException) {
            resumePositionMs = previousResume
            resumePausedAfterPicker = previousPaused
            subtitlePickerPending = false
            Toast.makeText(this, "This device has no app that can open a subtitle file.", Toast.LENGTH_LONG).show()
        }
    }

    private fun searchOpenSubtitles() {
        val generation = ++subtitleGeneration
        val forUrl = streamUrl
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            val apiKey = prefs.opensubtitlesApiKey.first().trim()
            if (apiKey.isEmpty()) {
                if (generation != subtitleGeneration || forUrl != streamUrl) return@launch
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("OpenSubtitles")
                    .setMessage("Add a free API key in Settings, under Audio & subtitles. A subtitle file on this device doesn't need one.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }
            val language = prefs.preferredSubtitleLanguage.first()
            val search = ExternalSubtitles.searchFor(streamTitle, traktSeriesName, traktSeason, traktEpisode, language)
            val userAgent = "MKTV ${com.iptvapp.BuildConfig.VERSION_NAME}"
            val hits = try {
                withContext(Dispatchers.IO) {
                    val client = OpenSubtitlesClient()
                    val username = prefs.opensubtitlesUsername.first().trim()
                    val password = prefs.opensubtitlesPassword.first()
                    val token = if (username.isNotEmpty() && password.isNotEmpty()) {
                        runCatching { client.login(apiKey, userAgent, username, password) }.getOrNull()
                    } else null
                    client.search(apiKey, userAgent, ExternalSubtitles.queryParams(search), token)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ExternalSubtitles.Rejected) {
                if (generation != subtitleGeneration || forUrl != streamUrl) return@launch
                Toast.makeText(this@PlayerActivity, e.message, Toast.LENGTH_LONG).show()
                return@launch
            } catch (_: Exception) {
                if (generation != subtitleGeneration || forUrl != streamUrl) return@launch
                Toast.makeText(this@PlayerActivity, "OpenSubtitles didn't answer.", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (generation != subtitleGeneration || forUrl != streamUrl) return@launch
            if (hits.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "No subtitles found for this title.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = hits.map { ExternalSubtitles.label(it) }.toTypedArray()
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("Subtitles")
                .setItems(labels) { _, which ->
                    if (generation == subtitleGeneration && forUrl == streamUrl) {
                        downloadOpenSubtitle(hits[which].fileId, apiKey, userAgent, generation, forUrl)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun downloadOpenSubtitle(fileId: Long, apiKey: String, userAgent: String, generation: Int, forUrl: String) {
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    val client = OpenSubtitlesClient()
                    val username = prefs.opensubtitlesUsername.first().trim()
                    val password = prefs.opensubtitlesPassword.first()
                    val token = if (username.isNotEmpty() && password.isNotEmpty()) {
                        runCatching { client.login(apiKey, userAgent, username, password) }.getOrNull()
                    } else null
                    val link = client.downloadLink(apiKey, userAgent, token, fileId)
                    writeSubtitleFile(ExternalSubtitles.prepare(client.fetch(link)))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ExternalSubtitles.Rejected) {
                if (generation != subtitleGeneration || forUrl != streamUrl) return@launch
                Toast.makeText(this@PlayerActivity, e.message, Toast.LENGTH_LONG).show()
                return@launch
            } catch (_: Exception) {
                if (generation != subtitleGeneration || forUrl != streamUrl) return@launch
                Toast.makeText(this@PlayerActivity, "OpenSubtitles didn't answer.", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (generation != subtitleGeneration || forUrl != streamUrl) {
                file.delete()
                return@launch
            }
            applyExternalSubtitle(file, file.extension)
            discardOtherSubtitles(file)
            Toast.makeText(this@PlayerActivity, "Subtitles on.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSubtitleFile(uri: android.net.Uri) {
        val generation = subtitlePickGeneration
        val forUrl = subtitlePickUrl
        if (generation != subtitleGeneration || forUrl != streamUrl) return
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        var total = 0
                        while (total <= ExternalSubtitles.MAX_BYTES) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            total += n
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    } ?: throw ExternalSubtitles.Rejected("Couldn't read that file.")
                    writeSubtitleFile(ExternalSubtitles.prepare(bytes))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ExternalSubtitles.Rejected) {
                if (generation != subtitleGeneration || forUrl != streamUrl) return@launch
                Toast.makeText(this@PlayerActivity, e.message, Toast.LENGTH_LONG).show()
                return@launch
            } catch (_: Exception) {
                if (generation != subtitleGeneration || forUrl != streamUrl) return@launch
                Toast.makeText(this@PlayerActivity, "Couldn't read that file.", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (generation != subtitleGeneration || forUrl != streamUrl) {
                file.delete()
                return@launch
            }
            applyExternalSubtitle(file, file.extension)
            discardOtherSubtitles(file)
            Toast.makeText(this@PlayerActivity, "Subtitles on.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeSubtitleFile(prepared: ExternalSubtitles.Prepared): File {
        val dir = File(cacheDir, "ext-subs")
        if (!dir.isDirectory && !dir.mkdirs()) throw ExternalSubtitles.Rejected("Couldn't save that subtitle.")
        val file = File(dir, "subtitle-${System.nanoTime()}.${prepared.extension}")
        try {
            file.writeText(prepared.text, Charsets.UTF_8)
        } catch (_: Exception) {
            file.delete()
            throw ExternalSubtitles.Rejected("Couldn't save that subtitle.")
        }
        return file
    }

    private fun discardOtherSubtitles(kept: File) {
        kept.parentFile?.listFiles()?.forEach { if (it.absolutePath != kept.absolutePath) it.delete() }
    }

    private fun subtitleConfiguration(file: File): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
            .setMimeType(if (file.extension == "vtt") MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(externalSubtitleLanguage)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

    private fun applyExternalSubtitle(file: File, extension: String) {
        externalSubtitleLanguage = ExternalSubtitles.languageCode(prefs.preferredSubtitleLanguageNow())
        externalSubtitleMime = if (extension == "vtt") MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
        externalSubtitlePath = file.absolutePath
        externalSubtitle = subtitleConfiguration(file)
        subtitleOfferShown = true
        // The player may already have been released. The preference still has to stick,
        // or the next session builds with text tracks disabled.
        lifecycleScope.launch { prefs.setSubtitlesEnabled(true) }
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        val position = p.currentPosition.coerceAtLeast(0L)
        val play = p.playWhenReady
        val base = p.currentMediaItem ?: MediaItem.fromUri(streamUrl)
        p.setMediaItem(base.buildUpon().setSubtitleConfigurations(listOf(externalSubtitle!!)).build(), position)
        p.prepare()
        p.playWhenReady = play
    }

    private fun canPreloadLiveNeighbors(): Boolean {
        if (!directLivePlayback() || startedOverFromLive) return false
        if (castSession != null || castChannels.isNotEmpty()) return false
        if (timeshiftSession != null || timeshiftStarting) return false
        if (shouldBufferTimeshift()) return false
        if (zapMemoryPressure) return false
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        if (manager.isLowRamDevice) return false
        val index = currentZapIndex()
        val size = if (serverIndex == -1) channels.size else mergedChannels.size
        return index in 0 until size && size >= 2
    }

    /**
     * The guide title, when it is already stored. This does not ask the panel.
     * Auto picture mode uses it. A missing row leaves the channel name.
     */
    /**
     * [reset] false is the follow-up when a programme ends: the picture keeps its profile until
     * the next title is known, instead of dropping to Standard for a moment.
     */
    private fun refreshPictureFromGuide(reset: Boolean = true) {
        pictureTitleJob?.cancel()
        if (reset) {
            pictureProgrammeTitle = ""
            updatePictureProfile()
        }
        if (!pictureLive()) return
        val lookupServer = serverIndex
        val lookupStream = if (lookupServer == -1) streamId else mergedStreamId
        if (lookupStream == -1) return
        pictureTitleJob = lifecycleScope.launch {
            fun toMs(ts: Long) = if (ts < 100_000_000_000L) ts * 1000L else ts
            val nowMs = System.currentTimeMillis()
            val onNow = try {
                db.epgDao().getEpgForStream(lookupStream, lookupServer).first()
                    .firstOrNull { toMs(it.startTimestamp) <= nowMs && toMs(it.stopTimestamp) > nowMs }
            } catch (_: Exception) {
                null
            }
            val currentStream = if (serverIndex == -1) streamId else mergedStreamId
            if (!isActive || serverIndex != lookupServer || currentStream != lookupStream) return@launch
            val next = onNow?.title?.trim().orEmpty()
            if (next != pictureProgrammeTitle) {
                pictureProgrammeTitle = next
                applyFrameRateMatch()
            }
            // Auto follows the programme: look again when this one ends (a game, then the news).
            val endsAt = onNow?.let { toMs(it.stopTimestamp) } ?: return@launch
            delay((endsAt - System.currentTimeMillis()).coerceAtLeast(0L) + 2_000L)
            if (isActive) refreshPictureFromGuide(reset = false)
        }
    }

    private data class ZapTarget(val server: Int, val streamId: Int)

    /**
     * Prepares the next channel, then the previous one, when that account has a spare connection.
     * Timeshift already holds the stream, so this stays off while the pump is running.
     * The preloaded source is handed to the player on the next direct zap. It is not a second open.
     */
    private fun scheduleZapPreload() {
        zapPreloadJob?.cancel()
        val factory = playbackDataSourceFactory
        val playingId = currentLiveMediaId()
        val index = currentZapIndex()
        if (factory == null || !canPreloadLiveNeighbors()) {
            if (factory != null) zapPreloader.replace(serverIndex, playingId, index, emptyList())
            zapPreloadJob = null
            return
        }
        zapPreloadJob = lifecycleScope.launch {
            // Give the panel time to count the stream that just started, then ask it fresh. A cached or
            // early count can miss this stream, and a provider that evicts instead of refusing would
            // knock another viewer off the account.
            val startedFor = currentLiveMediaId()
            delay(ZAP_PRELOAD_SETTLE_MS)
            val targets = if (serverIndex == -1) {
                channels.map { ZapTarget(-1, it.streamId) }
            } else {
                mergedChannels.map { ZapTarget(it.serverIndex, it.streamId) }
            }
            val here = currentZapIndex()
            if (!isActive || here !in targets.indices) return@launch
            val order = ChannelZap.neighborOrder(here, targets.size)
            val spare = HashMap<Int, Int>()
            for (server in order.map { targets[it].server }.distinct()) {
                val limits = repository.accountLimits(server, forceRefresh = true)
                spare[server] = ChannelZap.spareWhilePlaying(
                    limits?.maxConnections,
                    limits?.activeConnections,
                    holdsConnection = server == serverIndex
                )
            }
            if (!isActive) return@launch
            val picks = ChannelZap.select(
                here,
                targets.map { it.server },
                spare,
                lowRamDevice = false,
                memoryPressure = zapMemoryPressure
            )
            val neighbors = ArrayList<ZapPreloader.Neighbor>(picks.size)
            for (pick in picks) {
                if (!isActive) return@launch
                val target = targets[pick]
                val url = try {
                    if (target.server == -1) repository.getLiveStreamUrl(target.streamId)
                    else repository.getMergedLiveStreamUrl(target.server, target.streamId)
                } catch (_: Exception) {
                    continue
                }
                val mediaId = "live:${target.server}:${target.streamId}"
                if (mediaId == currentLiveMediaId()) continue
                neighbors += ZapPreloader.Neighbor(
                    mediaId,
                    providerMediaItem(url, mediaId, sportsLoadControlOn),
                    pick,
                    sportsLoadControlOn
                )
            }
            if (!isActive || currentLiveMediaId() != startedFor) return@launch
            playbackDataSourceFactory ?: return@launch
            zapPreloader.replace(serverIndex, currentLiveMediaId(), currentZapIndex(), neighbors)
        }
    }

    private fun playLocalPlaylist(playlist: java.io.File, budgetMs: Long, startMs: Long? = null) {
        val item = MediaItem.Builder()
            .setUri(android.net.Uri.fromFile(playlist))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(localLiveCushionMs)
                    .setMinOffsetMs(1_000L)
                    .setMaxOffsetMs((budgetMs + 30_000L).coerceAtLeast(60_000L))
                    .setMinPlaybackSpeed(1f)
                    .setMaxPlaybackSpeed(1f)
                    .build()
            )
            .build()
        val p = player ?: return
        val explicit = TimeshiftNavigator.mediaStartMs(startMs)
        val dataSource = playbackDataSourceFactory
        if (dataSource != null) {
            // HLS read behavior and PID track ids. Provider HLS still uses the player factory.
            val source = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dataSource)
                .setExtractorFactory(com.iptvapp.ui.player.timeshift.TimeshiftHlsExtractorFactory())
                .createMediaSource(item)
            if (explicit != null) p.setMediaSource(source, explicit) else p.setMediaSource(source)
        } else if (explicit != null) {
            p.setMediaItem(item, explicit)
        } else {
            p.setMediaItem(item)
        }
        p.prepare()
        p.playWhenReady = timeshiftAutoplay
    }

    private fun startTimeshift(url: String) {
        stopTimeshift()
        val generation = timeshiftGate.generation
        timeshiftStarting = true
        // Drop the previous channel's audio without treating that as a channel failure.
        player?.playWhenReady = false
        player?.clearMediaItems()
        updatePlayPauseButton()
        binding.progressBuffering.visibility = View.VISIBLE
        val minutes = replayBudgetMinutes()
        val budget = TimeshiftLimits.resolve(minutes, cacheDir.usableSpace)
        if (!budget.localEnabled) {
            settleTimeshiftDirect(generation)
            return
        }
        // The pump opens the channel itself. A prepared neighbor would be a second connection.
        releaseZapPreload()
        val root = java.io.File(cacheDir, TimeshiftStore.ROOT_NAME)
        root.mkdirs()
        val store = TimeshiftStore.create(root, budget) { root.usableSpace }
        // A killed process can leave a large directory. Delete it off the main thread.
        // create() has already registered this session, so the sweep leaves it alone.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                TimeshiftStore.sweepStale(root, keep = null)
            } catch (_: Exception) {
            }
        }
        val userAgent = "MKTV/${com.iptvapp.BuildConfig.VERSION_NAME} (Linux;Android ${Build.VERSION.RELEASE}) ExoPlayerLib/1.4.1"
        val session = LiveTimeshiftSession(okHttpClient, store, userAgent, onNetworkBytes = { bytes ->
            bandwidthTracker?.addNetworkBytes(bytes)
        })
        timeshiftSession = session
        timeshiftWatchdog?.cancel()
        timeshiftWatchdog = lifecycleScope.launch {
            delay(12_000L)
            if (generation == timeshiftGate.generation && !timeshiftStartSettled) {
                session.close()
                settleTimeshiftDirect(generation)
            }
        }
        session.start(url, object : TimeshiftLoop.Listener {
            override fun onReady(playlist: java.io.File) {
                runOnUiThread {
                    if (generation != timeshiftGate.generation || timeshiftStartSettled) return@runOnUiThread
                    timeshiftStartSettled = true
                    timeshiftStarting = false
                    timeshiftWatchdog?.cancel()
                    timeshiftMode = TimeshiftSource.LOCAL
                    playLocalPlaylist(playlist, budget.durationMs)
                    if (sportsRebuildAfterReady) {
                        sportsRebuildAfterReady = false
                        syncSportsPlayback()
                    }
                }
            }

            override fun onNeedDirect() {
                runOnUiThread { settleTimeshiftDirect(generation) }
            }

            override fun onContinuityLost() {
                runOnUiThread {
                    if (generation != timeshiftGate.generation) return@runOnUiThread
                    val tell = timeshiftStartSettled || timeshiftMode == TimeshiftSource.LOCAL
                    returnToProviderLive(tellUser = tell)
                }
            }

            override fun onStorageDegraded() {
                runOnUiThread {
                    if (generation != timeshiftGate.generation) return@runOnUiThread
                    onTimeshiftStorageDegraded()
                }
            }

            override fun onGiveUp() {
                runOnUiThread {
                    if (generation != timeshiftGate.generation) return@runOnUiThread
                    val tell = timeshiftState != TimeshiftPlaybackState.LIVE || timeshiftMode == TimeshiftSource.LOCAL
                    returnToProviderLive(tellUser = tell)
                }
            }

            override fun onRetainFailed() {
                runOnUiThread {
                    if (generation != timeshiftGate.generation) return@runOnUiThread
                    returnToProviderLive(tellUser = true)
                }
            }
        })
    }

    private fun settleTimeshiftDirect(generation: Long) {
        if (generation != timeshiftGate.generation || timeshiftStartSettled) return
        timeshiftStartSettled = true
        timeshiftStarting = false
        timeshiftWatchdog?.cancel()
        timeshiftMode = TimeshiftSource.NATIVE
        playProviderUrl(streamUrl, timeshiftAutoplay)
    }

    private fun stopTimeshift() {
        timeshiftGate.channelChanged()
        timeshiftWatchdog?.cancel()
        timeshiftWatchdog = null
        val session = timeshiftSession
        timeshiftSession = null
        timeshiftMode = TimeshiftSource.NONE
        timeshiftSeeking = false
        timeshiftReturning = false
        timeshiftFrozen = false
        timeshiftFrozenAtMs = 0L
        timeshiftStarting = false
        timeshiftStartSettled = false
        timeshiftRepairs = 0
        timeshiftState = TimeshiftPlaybackState.LIVE
        session?.close()
    }

    private fun returnToProviderLive(tellUser: Boolean) {
        val url = streamUrl
        pendingNativeBehindMs = C.TIME_UNSET
        timeshiftDirectFallback = true
        timeshiftFrozen = false
        stopTimeshift()
        if (tellUser) announceBackToLive()
        playProviderUrl(url, playWhenReady = true)
    }

    private fun announceBackToLive() {
        timeshiftState = TimeshiftPlaybackState.LIVE
        timeshiftReturning = false
        timeshiftSeeking = false
        Toast.makeText(this, "Back to live", Toast.LENGTH_SHORT).show()
    }

    private fun onTimeshiftStorageDegraded() {
        // Judged while the window still has its live cushion. Frozen, the edge is the last byte.
        val atLive = timeshiftReading()?.let { TimeshiftNavigator.atLiveEdge(it) } ?: true
        timeshiftFrozen = true
        timeshiftStarting = false
        if (TimeshiftNavigator.followProviderOnStorageLoss(atLive, timeshiftIntendedPlaying())) {
            timeshiftSession?.close()
            timeshiftSession = null
            returnToProviderLive(tellUser = false)
        } else {
            // The window stops here. Guide times stay on this instant while the viewer
            // sits inside the retained buffer. Keep the session so leaving this channel,
            // or Go Live, can still delete the files.
            if (timeshiftFrozenAtMs == 0L) timeshiftFrozenAtMs = System.currentTimeMillis()
            timeshiftSession?.stopKeepingFiles()
        }
    }

    private fun repairLocalPlayer() {
        val file = timeshiftSession?.sessionDir?.resolve("index.m3u8") ?: return
        if (!file.exists()) return
        val pos = player?.currentPosition ?: 0L
        val wantPlay = player?.playWhenReady ?: true
        timeshiftAutoplay = wantPlay
        val budgetMs = replayBudgetMinutes().coerceAtLeast(1) * 60_000L
        playLocalPlaylist(file, budgetMs, startMs = pos.coerceAtLeast(0L))
        player?.playWhenReady = wantPlay
    }

    private fun handleTimeshiftEnded(): Boolean {
        if (timeshiftMode != TimeshiftSource.LOCAL && !timeshiftFrozen) return false
        if (timeshiftSession?.running == true) repairLocalPlayer()
        else returnToProviderLive(tellUser = true)
        return true
    }

    private fun handleTimeshiftPlayerError(error: PlaybackException): Boolean {
        val local = timeshiftMode == TimeshiftSource.LOCAL || timeshiftFrozen
        if (!local && timeshiftSession == null) return false
        if (!local) return false
        val behindWindow = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        if (behindWindow && timeshiftSession?.running == true) {
            val p = player ?: return true
            val stayPaused = !p.playWhenReady
            // The error leaves the player idle. Seek alone does not start it again.
            p.seekTo(0L)
            p.prepare()
            p.playWhenReady = !stayPaused
            return true
        }
        if (timeshiftSession?.running == true && timeshiftRepairs < 3) {
            timeshiftRepairs++
            timeshiftSession?.forceReconnect()
            repairLocalPlayer()
            return true
        }
        returnToProviderLive(tellUser = true)
        return true
    }

    private fun timeshiftReading(): com.iptvapp.ui.player.timeshift.TimeshiftReading? {
        val p = player ?: return null
        if (isVod) return null
        return TimeshiftNavigator.readingFromTimeline(
            isLive = p.isCurrentMediaItemLive || timeshiftMode == TimeshiftSource.LOCAL,
            seekable = p.isCurrentMediaItemSeekable,
            durationMs = p.duration.takeIf { it != C.TIME_UNSET },
            positionMs = p.currentPosition,
            liveOffsetMs = p.currentLiveOffset.takeIf { it != C.TIME_UNSET },
            playWhenReady = p.playWhenReady,
            seeking = timeshiftSeeking,
            returningToLive = timeshiftReturning,
            // A frozen window has stopped growing. It is a finished recording, played to its end.
            edgeHoldbackMs = if (timeshiftMode == TimeshiftSource.LOCAL && !timeshiftFrozen) localLiveCushionMs else 0L,
        )
    }

    private fun timeshiftControlsOpen(): Boolean {
        val reading = timeshiftReading() ?: return false
        return TimeshiftNavigator.controlsAvailable(
            timeshiftSettingOn(),
            reading.seekable,
            TimeshiftNavigator.windowMs(reading),
        )
    }

    private fun seekTimeshiftBy(deltaMs: Long) {
        val p = player ?: return
        val reading = timeshiftReading() ?: return
        if (!reading.seekable) return
        val target = if (deltaMs < 0) TimeshiftNavigator.rewind(reading, -deltaMs)
        else TimeshiftNavigator.forward(reading, deltaMs)
        timeshiftReturning = false
        timeshiftState = TimeshiftPlaybackState.SEEKING
        p.seekTo(target)
        timeshiftSeeking = false
        refreshTimeshiftUi()
        resetHideTimer()
    }

    private fun goToLiveEdge() {
        if (startedOverFromLive) {
            backToLive()
            return
        }
        if (isVod) return
        if (timeshiftFrozen || (timeshiftMode == TimeshiftSource.LOCAL && timeshiftSession?.running != true)) {
            returnToProviderLive(tellUser = false)
            return
        }
        val p = player ?: return
        val reading = timeshiftReading()
        timeshiftReturning = true
        timeshiftSeeking = false
        timeshiftState = TimeshiftPlaybackState.RETURNING_TO_LIVE
        if (reading != null && reading.seekable) {
            p.seekTo(TimeshiftNavigator.goLive(reading))
        } else {
            val dur = p.duration
            // The local playlist's default position is its live cushion, not the newest byte.
            if (timeshiftMode != TimeshiftSource.LOCAL && dur != C.TIME_UNSET && dur > 0L) p.seekTo(dur)
            else p.seekToDefaultPosition()
        }
        requestTimeshiftPlayback(true)
        refreshTimeshiftUi()
        resetHideTimer()
    }

    private fun refreshTimeshiftUi() {
        if (isVod || !isOverlayVisible) return
        val reading = timeshiftReading() ?: return
        timeshiftState = TimeshiftNavigator.state(reading)
        if (timeshiftReturning && TimeshiftNavigator.atLiveEdge(reading)) timeshiftReturning = false
        val open = TimeshiftNavigator.controlsAvailable(
            timeshiftSettingOn(), reading.seekable, TimeshiftNavigator.windowMs(reading)
        )
        applyTimeshiftControlVisibility(open)
        if (!open) {
            val liveText = TimeshiftCopy.liveButtonText(0L, true)
            if (binding.tvOsdLive.text?.toString() != liveText) {
                binding.tvOsdLive.text = liveText
                binding.tvOsdLive.setTextColor(getColor(R.color.rack_danger_text))
                binding.tvOsdLive.contentDescription = TimeshiftCopy.liveButtonDescription(0L, true)
            }
            return
        }
        // The scrubber can appear after this overlay opened, once the window is long enough.
        bindLiveSeekBar()
        val behind = TimeshiftNavigator.behindMs(reading)
        val atLive = TimeshiftNavigator.atLiveEdge(reading)
        val text = TimeshiftCopy.liveButtonText(behind, atLive)
        if (binding.btnDvrLive.text?.toString() != text) binding.btnDvrLive.text = text
        if (binding.tvOsdLive.text?.toString() != text) binding.tvOsdLive.text = text
        val description = TimeshiftCopy.liveButtonDescription(behind, atLive)
        binding.btnDvrLive.contentDescription = description
        binding.tvOsdLive.contentDescription = description
        val color = if (atLive) getColor(R.color.rack_danger_text) else getColor(R.color.rack_text_secondary)
        binding.btnDvrLive.setTextColor(color)
        binding.tvOsdLive.setTextColor(color)
        binding.btnDvrLive.setBackgroundResource(
            if (atLive) R.drawable.player_live_at_edge_bg else R.drawable.player_live_behind_bg
        )
        binding.seekBar.contentDescription = if (atLive) "Timeshift position, at the live edge"
        else "Timeshift position, ${TimeshiftCopy.spokenDuration(behind)} behind live"
        val userHoldingSeek = seekBarDragging ||
            android.os.SystemClock.elapsedRealtime() < liveSeekHoldUntil
        if (!userHoldingSeek) {
            val window = TimeshiftNavigator.windowMs(reading).coerceAtLeast(1L)
            if (window <= Int.MAX_VALUE) {
                binding.seekBar.max = window.toInt()
                binding.seekBar.keyProgressIncrement = TimeshiftNavigator.REPLAY_10_MS.toInt()
                binding.seekBar.progress = reading.positionMs.coerceIn(0L, window).toInt()
            }
            binding.tvTimeElapsed.text = TimeshiftCopy.formatClock(reading.positionMs)
            binding.tvTimeRemaining.text = if (atLive) "LIVE" else "-" + TimeshiftCopy.formatClock(behind)
        }
        refreshTimeshiftEpg(behind, atLive)
        updatePlayPauseButton()
    }

    private fun applyTimeshiftControlVisibility(open: Boolean) {
        val show = if (open) View.VISIBLE else View.GONE
        binding.btnDvrRewind.visibility = show
        binding.btnDvrLive.visibility = show
        binding.btnVodBack.visibility = show
        binding.btnLiveFwd10.visibility = show
        binding.vodSeekContainer.visibility = show
        if (open) {
            binding.btnVodBack.contentDescription = TimeshiftCopy.REPLAY_10
            binding.btnDvrRewind.contentDescription = TimeshiftCopy.REPLAY_30
            binding.btnLiveFwd10.contentDescription = TimeshiftCopy.FORWARD_10
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                binding.btnDvrRewind.tooltipText = "Replay 60 seconds"
            }
        }
    }

    private fun refreshTimeshiftEpg(behindMs: Long, atLive: Boolean) {
        val nowMs = System.currentTimeMillis()
        val frozenAt = if (timeshiftFrozen) timeshiftFrozenAtMs else 0L
        val show = TimeshiftEpg.programmeForUi(timeshiftEpg, nowMs, behindMs, atLive, frozenAt)
        if (frozenAt <= 0L && (atLive || behindMs < TimeshiftEpg.LIVE_EPG_SLACK_MS)) {
            if (liveEdgeEpgText.isNotEmpty() && binding.tvEpgNow.text?.toString() != liveEdgeEpgText) {
                binding.tvEpgNow.text = liveEdgeEpgText
            }
            // The bar was painted for the rewound show. Put it back on the live one.
            if (show != null) showLiveProgress(show.startMs, show.stopMs, nowMs)
            return
        }
        if (show == null) return
        if (binding.tvEpgNow.text?.toString() != show.title) binding.tvEpgNow.text = show.title
        val anchor = if (frozenAt > 0L) frozenAt else nowMs
        showLiveProgress(show.startMs, show.stopMs, TimeshiftEpg.playbackWallMs(anchor, behindMs))
    }

    private fun bindLiveSeekBar() {
        if (liveSeekBound) return
        liveSeekBound = true
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val reading = timeshiftReading() ?: return
                val window = TimeshiftNavigator.windowMs(reading).coerceAtLeast(1L)
                val target = TimeshiftNavigator.clamp(progress.toLong(), reading.earliestMs, reading.liveEdgeMs)
                val behind = (window - target).coerceAtLeast(0L)
                binding.tvTimeElapsed.text = TimeshiftCopy.formatClock(target)
                binding.tvTimeRemaining.text = "-" + TimeshiftCopy.formatClock(behind)
                // A finger seek lands in onStopTrackingTouch. A D-pad or keyboard change never
                // starts a touch, so SeekBar does not call that. Seek now, as the VOD bar does.
                if (!sb.isPressed) {
                    timeshiftReturning = false
                    liveSeekHoldUntil = android.os.SystemClock.elapsedRealtime() + 400L
                    player?.seekTo(target)
                }
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {
                timeshiftSeeking = true
                timeshiftState = TimeshiftPlaybackState.SEEKING
                seekBarDragging = true
                hideHandler.removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                val reading = timeshiftReading()
                if (reading != null) {
                    val target = TimeshiftNavigator.clamp(sb.progress.toLong(), reading.earliestMs, reading.liveEdgeMs)
                    timeshiftReturning = false
                    player?.seekTo(target)
                }
                timeshiftSeeking = false
                seekBarDragging = false
                refreshTimeshiftUi()
                resetHideTimer()
            }
        })
    }

    private val timeshiftUiRunnable = object : Runnable {
        override fun run() {
            refreshTimeshiftUi()
            refreshSmartCatchUp()
            if (timeshiftUiRunning) seekHandler.postDelayed(this, 1_000L)
        }
    }

    private fun startTimeshiftTicker() {
        if (timeshiftUiRunning || isVod || !timeshiftSettingOn()) return
        timeshiftUiRunning = true
        seekHandler.post(timeshiftUiRunnable)
    }

    private fun stopTimeshiftTicker() {
        timeshiftUiRunning = false
        seekHandler.removeCallbacks(timeshiftUiRunnable)
    }

    private fun saveVodProgress() {
        if (!isVod || isCatchup) return
        val watched = player?.currentPosition ?: return
        val duration = player?.duration ?: return
        if (duration <= 0) return
        // Episodes use episode.id.hashCode() as streamId, which isn't a real vod_streams row —
        // route episode progress into episode_watched instead, keyed by seriesId/season/episode.
        val seriesId = episodeSeriesId
        val season = traktSeason
        val episode = traktEpisode
        val server = serverIndex
        val merged = mergedStreamId
        val vodId = streamId
        val save: suspend () -> Unit = if (seriesId != -1 && season >= 0 && episode >= 0) {
            { repository.saveEpisodeProgress(seriesId, season, episode, watched, duration) }
        } else if (server != -1 && merged != -1) {
            // Merged-provider VOD plays with streamId = -1 (no primary vod_streams row) —
            // resolve progress by (serverIndex, mergedStreamId) instead, same composite-key
            // convention as every other merged-provider table.
            { repository.saveMergedVodProgress(server, merged, watched, duration) }
        } else {
            if (vodId < 0) return
            { repository.saveVodProgress(vodId, watched, duration) }
        }
        val app = applicationContext
        // Called from onStop. GlobalScope so the save outlives a closing player, and the TV home
        // screen's Watch Next row follows it straight away: Home on the remote goes to the
        // launcher without MKTV's own home screen ever coming back to publish it.
        kotlinx.coroutines.GlobalScope.launch {
            try {
                save()
                com.iptvapp.tv.WatchNextPublisher.refresh(app, db, prefs)
            } catch (_: Exception) {
            }
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

    /** Position/duration of what's playing as the viewer sees it — for catch-up that's the whole
     * programme, not just the timeshift URL currently open. */
    private fun vodPositionMs(): Long {
        if (isCatchup && catchupPendingTargetMs >= 0L) return catchupPendingTargetMs
        val pos = player?.currentPosition ?: 0L
        return if (isCatchup) catchupOffsetSec * 1000L + pos else pos
    }

    private fun vodDurationMs(): Long =
        if (isCatchup) catchupDurationMin * 60_000L else (player?.duration ?: C.TIME_UNSET)

    /** Every VOD seek goes through here. For catch-up, a target the open timeshift URL can't seek
     * to reopens the URL at the target's minute — rounded towards the direction of travel so
     * repeated +10s presses on an unseekable stream still move forward a minute instead of
     * snapping back to the same one — after a short pause so a run of presses opens it once. */
    private fun vodSeekTo(targetMs: Long) {
        val p = player ?: return
        if (!isCatchup) {
            val dur = p.duration
            p.seekTo(if (dur != C.TIME_UNSET && dur > 0) targetMs.coerceIn(0L, dur) else targetMs.coerceAtLeast(0L))
            return
        }
        // Direction is the request's, decided before clamping: a forward skip clamped behind the
        // current position near the end must not turn into a rewind.
        val current = vodPositionMs()
        val forward = targetMs >= current
        // Started over from live: past what has aired so far there's nothing to replay — that's live.
        // (A minute of slack: the provider's archive trails the broadcast a little.)
        if (startedOverFromLive && forward &&
            targetMs >= System.currentTimeMillis() - catchupStartSec * 1000L - 60_000L
        ) {
            backToLive()
            return
        }
        val target = targetMs.coerceIn(0L, (vodDurationMs() - 5_000L).coerceAtLeast(0L))
        if (forward && target <= current) return
        val rel = target - catchupOffsetSec * 1000L
        val segDur = p.duration
        if (catchupReloadJob?.isActive != true && rel >= 0 && p.isCurrentMediaItemSeekable &&
            segDur != C.TIME_UNSET && rel < segDur
        ) {
            retryJob?.cancel()
            p.seekTo(rel)
            return
        }
        // Near the end, a forward skip can only reopen at the last whole minute; if that isn't
        // ahead of where playback already is, it would jump backwards, so stay put instead.
        if (forward && catchupOffsetMinFor(target, roundUp = true) * 60_000L <= current) return
        // A reconnect queued before this seek would reopen the old position over it.
        retryJob?.cancel()
        catchupReloadJob?.cancel()
        catchupPendingTargetMs = target
        catchupReloadJob = lifecycleScope.launch {
            binding.tvTimeElapsed.text = formatDuration(target)
            delay(700L)
            loadCatchupAt(target, roundUp = forward)
        }
    }

    /** Reopens the programme's timeshift URL at [targetMs] (from the programme start), keeping
     * the play/pause state and the URL's format (.ts, or .m3u8 after the format fallback). */
    /** The whole minute a timeshift URL for [targetMs] starts at — rounded up for forward moves,
     * never past the programme's last minute. */
    private fun catchupOffsetMinFor(targetMs: Long, roundUp: Boolean): Int {
        val maxMin = (catchupDurationMin - 1).coerceAtLeast(0)
        val wholeMin = (targetMs / 60_000L).toInt()
        return (if (roundUp && targetMs % 60_000L != 0L) wholeMin + 1 else wholeMin).coerceIn(0, maxMin)
    }

    private suspend fun loadCatchupAt(targetMs: Long, roundUp: Boolean = false) {
        val offsetMin = catchupOffsetMinFor(targetMs, roundUp)
        val base = try {
            repository.getTimeshiftUrlFor(serverIndex, liveCopyId(), catchupStartSec + offsetMin * 60L, catchupDurationMin - offsetMin)
        } catch (_: Exception) { catchupPendingTargetMs = -1L; return }
        // Left the replay (LIVE, or a Start Over cancelled) while the URL was being built.
        if (!isCatchup) return
        val url = if (streamUrl.endsWith(".m3u8", ignoreCase = true)) base.substringBeforeLast('.') + ".m3u8" else base
        catchupOffsetSec = offsetMin * 60L
        catchupPendingSeekMs = (targetMs - offsetMin * 60_000L).coerceAtLeast(0L)
        catchupPendingTargetMs = -1L
        streamUrl = url
        com.iptvapp.IptvApplication.logPlaybackEvent(
            applicationContext, "CATCHUP OPEN: streamId=$streamId offsetMin=$offsetMin url=$url"
        )
        player?.let {
            val keepPlaying = it.playWhenReady
            it.setMediaItem(MediaItem.fromUri(url))
            it.prepare()
            it.playWhenReady = keepPlaying
        }
        updateSeekBar()
    }

    // ─── Seek preview (util/SeekPreview, ThumbnailPreviewManager) ─────────
    // Movies, episodes and recordings choose a place first and jump once: Left/Right (or a drag)
    // move a target with the time, and a picture where the video allows one; OK, Play/Pause, a
    // pause of two seconds, or letting go of the bar jumps there; Back stays put. The player is
    // not moved until then. Catch-up keeps its own reopen-at-the-minute seeking.

    private val seekSession = com.iptvapp.util.SeekPreview.Session()
    private var thumbnailManager: ThumbnailPreviewManager? = null
    private val thumbnails: ThumbnailPreviewManager
        get() = thumbnailManager ?: ThumbnailPreviewManager(applicationContext).also { thumbnailManager = it }
    // The stream the picture source was worked out for; another stream works it out again.
    private var thumbnailSourceUrl: String? = null
    private var thumbnailSourceJob: Job? = null
    private var thumbnailConnectionJob: Job? = null
    private var seekPreviewOpenedBar = false
    private var seekPreviewLayoutWatched = false
    private val seekCommitRunnable = Runnable { commitSeekPreview() }
    // Jump this long after the last press: the old one-press-one-jump feel, with time to look.
    private val SEEK_PREVIEW_IDLE_MS = 2_000L
    // Plain files a retriever can read in ranges; HLS, DASH and MPEG-TS are left to the time alone.
    private val PROGRESSIVE_EXTENSIONS = listOf(".mp4", ".m4v", ".mkv", ".mov", ".webm", ".avi")

    private fun seekPreviewApplies(): Boolean {
        if (!isVod || isCatchup || player == null) return false
        val d = vodDurationMs()
        return d != C.TIME_UNSET && d > 0
    }

    /** A Left or Right press (held keys repeat, and the step grows with them). */
    private fun nudgeSeekPreview(forward: Boolean) {
        if (!seekSession.active) beginSeekPreview()
        val target = seekSession.nudge(forward)
        showSeekPreview(target)
        seekHandler.removeCallbacks(seekCommitRunnable)
        seekHandler.postDelayed(seekCommitRunnable, SEEK_PREVIEW_IDLE_MS)
    }

    private fun beginSeekPreview() {
        seekSession.begin(vodPositionMs(), vodDurationMs())
        seekBarDragging = true
        ensureThumbnailSource()
        recheckProviderConnection()
    }

    /**
     * The provider's own file needs a spare connection now, not just when it was last checked: ask
     * again (the time alone shows meanwhile), so a cast or another device that has taken it since
     * is never cut off by a picture. Only the permission changes; the video's failures and byte
     * budget stay as they are.
     */
    private fun recheckProviderConnection() {
        val manager = thumbnailManager ?: return
        if (!manager.needsConnectionCheck) return
        manager.allowed = false
        val url = streamUrl
        val server = serverIndex
        thumbnailConnectionJob?.cancel()
        thumbnailConnectionJob = lifecycleScope.launch {
            val limits = try {
                if (castSession != null) null else repository.accountLimits(server, forceRefresh = true)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            if (streamUrl != url || thumbnailManager !== manager) return@launch
            val max = limits?.maxConnections
            val active = limits?.activeConnections
            // One stream per account: never a spare, so no more asking for this video.
            if (max == 1) { manager.setSource(null); return@launch }
            // Busy, unknown, or the panel didn't answer: the time alone this seek; the next one asks again.
            val spare = max != null && active != null && max >= 2 && active < max
            manager.allowed = spare
            if (spare && seekSession.active) showSeekPreview(seekSession.targetMs)
        }
    }

    private fun showSeekPreview(targetMs: Long) {
        val duration = seekSession.durationMs
        if (binding.vodSeekContainer.visibility != View.VISIBLE) {
            binding.vodSeekContainer.visibility = View.VISIBLE
            seekPreviewOpenedBar = true
        }
        binding.seekBar.max = duration.toInt()
        binding.seekBar.progress = targetMs.toInt()
        binding.tvTimeElapsed.text = formatDuration(targetMs)
        binding.tvTimeRemaining.text = "-" + formatDuration((duration - targetMs).coerceAtLeast(0L))
        binding.tvSeekPreviewTime.text = com.iptvapp.util.SeekPreview.clock(targetMs)
        val way = if (targetMs >= seekSession.startMs) "forward" else "back"
        binding.seekPreview.contentDescription = "Preview at ${com.iptvapp.util.SeekPreview.spoken(targetMs)}, seeking $way. Press OK to play from here."
        binding.seekPreview.visibility = View.VISIBLE
        // The card changes size when a picture comes or goes: keep it centred and on screen.
        if (!seekPreviewLayoutWatched) {
            seekPreviewLayoutWatched = true
            binding.seekPreview.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
                if (seekSession.active && (r - l) != (or - ol)) binding.seekPreview.post { positionSeekPreview(seekSession.targetMs) }
            }
        }
        val image = binding.ivSeekPreview
        if (!thumbnails.supported) {
            image.visibility = View.GONE
            image.setImageDrawable(null)
        } else {
            // The last picture stays, dimmed, until this one is ready: no spinner, never a wrong one at full strength.
            image.alpha = 0.45f
            val generation = seekSession.generation
            thumbnails.request(targetMs, duration) { _, bitmap ->
                if (!seekSession.active || seekSession.generation != generation) return@request
                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                    image.alpha = 1f
                    image.visibility = View.VISIBLE
                } else if (!thumbnails.supported) {
                    image.visibility = View.GONE
                    image.setImageDrawable(null)
                }
            }
        }
        positionSeekPreview(targetMs)
    }

    /** Over the target on the bar, inside the screen at both ends. */
    private fun positionSeekPreview(targetMs: Long) {
        val preview = binding.seekPreview
        if (preview.width == 0) {
            preview.post { if (seekSession.active) positionSeekPreview(seekSession.targetMs) }
            return
        }
        val bar = binding.seekBar
        val barStart = binding.vodSeekContainer.left + bar.left + bar.paddingLeft
        val barWidth = (bar.width - bar.paddingLeft - bar.paddingRight).coerceAtLeast(1)
        val fraction = if (seekSession.durationMs > 0) targetMs.toFloat() / seekSession.durationMs else 0f
        preview.translationX = com.iptvapp.util.SeekPreview.previewLeftPx(
            fraction, barStart.toFloat(), barWidth.toFloat(), preview.width.toFloat(),
            binding.root.width.toFloat(), 16 * resources.displayMetrics.density
        )
    }

    /** Jumps to the chosen place, once. */
    private fun commitSeekPreview() {
        seekHandler.removeCallbacks(seekCommitRunnable)
        val target = seekSession.commit() ?: return
        endSeekPreviewUi()
        vodSeekTo(target)
        notifyPartyStateChange()
        updateSeekBar()
    }

    /** Back, leaving, another video: nothing is sought. */
    private fun cancelSeekPreview() {
        seekHandler.removeCallbacks(seekCommitRunnable)
        if (!seekSession.active) return
        seekSession.cancel()
        endSeekPreviewUi()
        updateSeekBar()
    }

    private fun endSeekPreviewUi() {
        seekBarDragging = false
        binding.seekPreview.visibility = View.GONE
        binding.ivSeekPreview.setImageDrawable(null)
        binding.ivSeekPreview.visibility = View.GONE
        if (seekPreviewOpenedBar && !isOverlayVisible) binding.vodSeekContainer.visibility = View.GONE
        seekPreviewOpenedBar = false
    }

    /** Works out, once per stream, where pictures can come from (or that there are none). */
    private fun ensureThumbnailSource() {
        val url = streamUrl
        if (url == thumbnailSourceUrl) return
        thumbnailSourceUrl = url
        thumbnails.setSource(null)
        thumbnailSourceJob?.cancel()
        thumbnailSourceJob = lifecycleScope.launch {
            val source = try { thumbnailSourceFor(url) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            if (streamUrl == url && thumbnailSourceUrl == url) {
                thumbnails.setSource(source)
                if (thumbnails.needsConnectionCheck) recheckProviderConnection()
                // Still choosing: the place on screen gets its picture now, without another press.
                else if (source != null && seekSession.active) showSeekPreview(seekSession.targetMs)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun thumbnailSourceFor(url: String): ThumbnailPreviewManager.Source? {
        if (!prefs.seekPreviewPicturesNow() || !seekPreviewApplies() || url.isBlank()) return null
        if (isRecordingPlayback) {
            val uri = android.net.Uri.parse(url)
            val key = withContext(Dispatchers.IO) { ThumbnailPreviewManager.recordingKey(applicationContext, streamId, uri) } ?: return null
            return ThumbnailPreviewManager.Source.Local(uri, key)
        }
        val path = url.substringBefore('?').lowercase(java.util.Locale.US)
        // Live-style formats: a picture would mean fetching segments or a whole playlist.
        if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".ts")) return null
        val key = com.iptvapp.util.SeekPreview.keyForUrl(url, serverIndex, prefs.seekPreviewSalt())
        // A finished download: read from the download cache, no network at all.
        val downloaded = try {
            db.downloadedContentDao().getAllCompleted().first().any { it.localFilePath == url }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
        if (downloaded) {
            return ThumbnailPreviewManager.Source.Downloaded(url, com.iptvapp.download.DownloadUtil.getDownloadCache(this), key)
        }
        if (PROGRESSIVE_EXTENSIONS.none { path.endsWith(it) }) return null
        // The provider's own file needs a second connection: each seek checks for a spare one first
        // (recheckProviderConnection), so this source starts without the go-ahead.
        return ThumbnailPreviewManager.Source.Remote(url, ThumbnailPreviewManager.playbackUserAgent(), key)
    }

    private fun updateSeekBar() {
        if (!isVod) return
        if (catchupReloadJob?.isActive == true) return
        if (seekBarDragging) return
        val duration = vodDurationMs()
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = vodPositionMs().coerceAtMost(duration)
        binding.seekBar.max = duration.toInt()
        binding.seekBar.progress = position.toInt()
        binding.tvTimeElapsed.text = formatDuration(position)
        binding.tvTimeRemaining.text = "-" + formatDuration((duration - position).coerceAtLeast(0L))
        updateSkipBreak(position)
    }

    // Ad skip: the recording's commercial breaks (AdBreakDetector), re-read now and then until the
    // background analysis has stored some.
    private var adBreaks: List<LongRange> = emptyList()
    private var adBreaksCheckedAt = 0L

    private fun updateSkipBreak(positionMs: Long) {
        if (!isRecordingPlayback || streamId < 0) return
        // No room for it over a picture-in-picture window.
        if (isInPictureInPictureMode) {
            binding.btnSkipBreak.visibility = View.GONE
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (adBreaks.isEmpty() && now - adBreaksCheckedAt > 15_000L) {
            adBreaksCheckedAt = now
            adBreaks = com.iptvapp.util.AdBreakDetector.breaksFor(this, streamId)
        }
        // Not in the last few seconds of a break — it would be gone before you could tap it.
        val inBreak = adBreaks.any { positionMs >= it.first && positionMs < it.last - 3_000L }
        val shown = binding.btnSkipBreak.visibility == View.VISIBLE
        if (inBreak != shown) {
            binding.btnSkipBreak.visibility = if (inBreak) View.VISIBLE else View.GONE
            if (inBreak && !isOverlayVisible) binding.btnSkipBreak.requestFocus()
        }
    }

    private fun skipCurrentBreak() {
        val pos = player?.currentPosition ?: return
        val brk = adBreaks.firstOrNull { pos >= it.first && pos <= it.last } ?: return
        player?.seekTo(brk.last + 1)
        binding.btnSkipBreak.visibility = View.GONE
        updateSeekBar()
    }

    private fun startSeekBarUpdater() {
        if (!isVod) return
        // Start Over replaces this listener. Coming back to live must be allowed to install its own.
        liveSeekBound = false
        binding.vodSeekContainer.visibility = View.VISIBLE
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // Catch-up seeks on release: dragging would otherwise reopen the stream at
                // every minute the thumb passes. D-pad nudges (no touch) go through vodSeekTo,
                // which already waits for the presses to stop.
                if (isCatchup) {
                    binding.tvTimeElapsed.text = formatDuration(progress.toLong())
                    if (!sb.isPressed) vodSeekTo(progress.toLong())
                } else if (seekSession.active) {
                    // A drag: the preview follows the finger; the jump waits for letting go.
                    showSeekPreview(seekSession.setTarget(progress.toLong()))
                } else {
                    player?.seekTo(progress.toLong())
                    notifyPartyStateChange()
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {
                seekBarDragging = true
                hideHandler.removeCallbacks(hideRunnable)
                if (seekPreviewApplies()) {
                    seekHandler.removeCallbacks(seekCommitRunnable)
                    beginSeekPreview()
                    showSeekPreview(seekSession.setTarget(sb.progress.toLong()))
                }
            }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                seekBarDragging = false
                if (isCatchup) vodSeekTo(sb.progress.toLong())
                else if (seekSession.active) {
                    seekSession.setTarget(sb.progress.toLong())
                    commitSeekPreview()
                }
                resetHideTimer()
            }
        })
        // The bar under the remote: Left/Right choose a place with the preview (as with the
        // controls hidden), OK jumps. Live timeshift and catch-up keep the bar's own keys.
        binding.seekBar.setOnKeyListener { _, keyCode, event ->
            if (!seekPreviewApplies()) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        resetHideTimer()
                        nudgeSeekPreview(forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!seekSession.active) false else {
                        if (event.action == KeyEvent.ACTION_DOWN) commitSeekPreview()
                        true
                    }
                }
                else -> false
            }
        }
        seekRunnable = object : Runnable {
            override fun run() { updateSeekBar(); seekHandler.postDelayed(this, 1000) }
        }
        seekHandler.post(seekRunnable!!)
    }

    private fun showOverlay() {
        isOverlayVisible = true
        binding.tvChannelTitle.text = streamTitle
        // v6.93 OSD: the top bar (with the tool chips) and both scrims come and go with the controls;
        // the title line carries the name's quality tag and LIVE for live TV.
        binding.osdTopBar.visibility = View.VISIBLE
        binding.playerTopScrim.visibility = View.VISIBLE
        binding.playerBottomScrim.visibility = View.VISIBLE
        val quality = com.iptvapp.util.ChannelQualityTag.labelFor(streamTitle)
        binding.tvOsdQuality.text = quality ?: ""
        binding.tvOsdQuality.visibility = if (quality != null) View.VISIBLE else View.GONE
        binding.tvOsdLive.visibility = if (isVod) View.GONE else View.VISIBLE
        binding.epgOverlay.visibility = View.VISIBLE
        binding.btnBack.visibility = View.VISIBLE
        binding.btnGuide.visibility = if (quickPeekAvailable()) View.VISIBLE else View.GONE
        binding.btnPlayPause.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.VISIBLE
        // Mirrors hideRunnable's isVod branch — the seek bar auto-hides with the rest of the
        // transient controls now, so it needs to come back here too, not just once at playback
        // start (startSeekBarUpdater already set it VISIBLE that one time, but every hide/show
        // cycle after that needs to re-show it explicitly, same as every other control below).
        if (isVod) {
            binding.vodSeekContainer.visibility = View.VISIBLE
            binding.btnVodBack.visibility = View.VISIBLE
            binding.btnVodBack.contentDescription = "Back 10 seconds"
            binding.btnVodFwd.visibility = View.VISIBLE
            binding.btnVodFwdBig.visibility = View.VISIBLE
            binding.btnLiveFwd10.visibility = View.GONE
            // Started over from live: LIVE goes back to the broadcast (red: you're behind it).
            if (startedOverFromLive) {
                binding.btnDvrLive.visibility = View.VISIBLE
                binding.btnDvrLive.setTextColor(getColor(R.color.rack_danger_text))
                binding.btnDvrLive.contentDescription = "Go live"
            }
        }
        if (!isVod) {
            val open = timeshiftControlsOpen()
            applyTimeshiftControlVisibility(open)
            bindLiveSeekBar()
            updateDvrLiveButton()
            startTimeshiftTicker()
            if (streamId != -1 || serverIndex != -1) binding.btnRecordDot.visibility = View.VISIBLE
        }
        if (castAvailable) binding.btnCast.visibility = View.VISIBLE
        if (isHealthBadgeActive) binding.bufferHealthBadge.visibility = View.VISIBLE
        updatePlayPauseButton()
        resetHideTimer()
        placeSportsChrome(true)
        if (!isVod) {
            applySportsChrome()
            refreshSmartCatchUp()
        }
        binding.btnPlayPause.post { binding.btnPlayPause.requestFocus() }
        if (!isVod && streamId != -1) {
            lifecycleScope.launch {
                val lookupStreamId = streamId
                repository.fetchEpg(streamId)
                val epg = repository.getEpgForStream(streamId).first()
                val nowMs = System.currentTimeMillis()
                fun startMs(e: com.iptvapp.data.local.entities.EpgEntity) = if (e.startTimestamp < 100_000_000_000L) e.startTimestamp * 1000L else e.startTimestamp
                fun stopMs(e: com.iptvapp.data.local.entities.EpgEntity)  = if (e.stopTimestamp  < 100_000_000_000L) e.stopTimestamp  * 1000L else e.stopTimestamp
                val now  = epg.firstOrNull { startMs(it) <= nowMs && stopMs(it) > nowMs }
                val next = epg.firstOrNull { now != null && startMs(it) > stopMs(now) }
                binding.tvEpgNow.text = if (now != null) now.title + " · " + minutesLeftLabel(stopMs(now) - nowMs) + " left" else ""
                liveEdgeEpgText = binding.tvEpgNow.text?.toString().orEmpty()
                timeshiftEpg = epg.map { TimeshiftEpg.Show(it.title, startMs(it), stopMs(it)) }
                binding.tvEpgNext.text = if (next != null) "Next · " + clockLabel(startMs(next)) + " · " + next.title else ""
                binding.tvEpgNext.visibility = if (next != null) View.VISIBLE else View.GONE
                showLiveProgress(now?.let { startMs(it) }, now?.let { stopMs(it) })
                // The lookup is async; a channel change meanwhile makes this result stale.
                if (isVod || lookupStreamId != streamId) return@launch
                liveNowShow = now?.let { LiveShow(lookupStreamId, startMs(it), stopMs(it), it.title) }
                // One archive lookup per channel. The offer reads the cached flag after that.
                val archive = if (smartCatchupArchiveKey == (-1 to lookupStreamId) && smartCatchupArchive != null) {
                    smartCatchupArchive == true
                } else {
                    val enabled = repository.getChannelById(lookupStreamId)?.tvArchive == 1
                    if (lookupStreamId == streamId) {
                        smartCatchupArchiveKey = -1 to lookupStreamId
                        smartCatchupArchive = enabled
                    }
                    enabled
                }
                if (isVod || lookupStreamId != streamId) return@launch
                // Start Over needs catch-up on this channel and a show that's been on a minute or more.
                val canStartOver = now != null && nowMs - startMs(now) > 60_000L && archive
                binding.btnStartOver.visibility = if (canStartOver && !isVod) View.VISIBLE else View.GONE
                refreshSmartCatchUp(nowMs)
                applySportsChrome()
                syncSportsPlayback()
            }
        } else if (!isVod && serverIndex != -1) {
            lifecycleScope.launch {
                val lookupServer = serverIndex
                val lookupStream = mergedStreamId
                val nowNext = try { repository.fetchMergedEpgNowNext(lookupServer, lookupStream) } catch (_: Exception) { null }
                // Another merged channel can share a nonnegative server index. Drop a late answer.
                if (isVod || serverIndex != lookupServer || mergedStreamId != lookupStream) return@launch
                binding.tvEpgNow.text = if (nowNext != null) nowNext.nowTitle + " · " + minutesLeftLabel(nowNext.nowStopMs - System.currentTimeMillis()) + " left" else ""
                liveEdgeEpgText = binding.tvEpgNow.text?.toString().orEmpty()
                timeshiftEpg = if (nowNext != null) {
                    listOf(TimeshiftEpg.Show(nowNext.nowTitle, nowNext.nowStartMs, nowNext.nowStopMs))
                } else {
                    emptyList()
                }
                binding.tvEpgNext.text = if (nowNext?.nextTitle != null) "Next · " + clockLabel(nowNext.nowStopMs) + " · " + nowNext.nextTitle else ""
                binding.tvEpgNext.visibility = if (nowNext?.nextTitle != null) View.VISIBLE else View.GONE
                showLiveProgress(nowNext?.nowStartMs, nowNext?.nowStopMs)
                liveNowShow = nowNext?.let { LiveShow(lookupStream, it.nowStartMs, it.nowStopMs, it.nowTitle) }
                // Catch-up on other providers too: their archive flag, looked up once per channel.
                val key = lookupServer to lookupStream
                val archive = if (smartCatchupArchiveKey == key && smartCatchupArchive != null) {
                    smartCatchupArchive == true
                } else {
                    val enabled = archivedChannel(lookupServer, lookupStream) != null
                    if (isVod || serverIndex != lookupServer || mergedStreamId != lookupStream) return@launch
                    smartCatchupArchiveKey = key
                    smartCatchupArchive = enabled
                    enabled
                }
                val nowMs = System.currentTimeMillis()
                val canStartOver = nowNext != null && nowMs - nowNext.nowStartMs > 60_000L && archive
                binding.btnStartOver.visibility = if (canStartOver && !isVod) View.VISIBLE else View.GONE
                refreshSmartCatchUp(nowMs)
                applySportsChrome()
                syncSportsPlayback()
            }
        }
    }

    /** The live show's progress bar (v6.99): start — progress in the accent — end, while the overlay is
     * up and the guide knows what's on; hidden otherwise. */
    private fun showLiveProgress(startMs: Long?, stopMs: Long?, atMs: Long = System.currentTimeMillis()) {
        if (startMs == null || stopMs == null || stopMs <= startMs || !isOverlayVisible || isVod) {
            binding.liveProgramBar.visibility = View.GONE
            return
        }
        val now = atMs
        binding.tvLiveProgramStart.text = clockLabel(startMs)
        binding.tvLiveProgramEnd.text = clockLabel(stopMs)
        com.iptvapp.util.RackAccent.paintProgress(binding.liveProgramProgress, playerAccent)
        binding.liveProgramProgress.progress = (((now - startMs) * 1000) / (stopMs - startMs)).toInt().coerceIn(0, 1000)
        binding.liveProgramBar.visibility = View.VISIBLE
    }

    private fun setLocked(locked: Boolean) {
        isLocked = locked
        osdHandler.removeCallbacks(hideUnlockRunnable)
        if (locked) {
            hideHandler.removeCallbacks(hideRunnable)
            hideRunnable.run()
            quickPeek.stop()
            flashUnlock()
        } else {
            binding.btnUnlock.visibility = View.GONE
            showOverlay()
        }
    }

    private fun flashUnlock() {
        binding.btnUnlock.visibility = View.VISIBLE
        osdHandler.removeCallbacks(hideUnlockRunnable)
        osdHandler.postDelayed(hideUnlockRunnable, 3000)
    }

    /** The play / pause circle in the accent (a gradient one for a gradient preset), with the
     * light ring under remote focus; its icon black or white for contrast. */
    private fun paintPlayButton() {
        val d = resources.displayMetrics.density
        fun oval(ring: Boolean) = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, playerAccent.stops
        ).apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            if (ring) setStroke((3 * d).toInt(), getColor(R.color.rack_focus_ring))
        }
        binding.btnPlayPause.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), oval(true))
            addState(intArrayOf(), oval(false))
        }
        binding.btnPlayPause.imageTintList = android.content.res.ColorStateList.valueOf(playerAccent.onAccent)
    }

    private fun minutesLeftLabel(ms: Long): String {
        val m = ((ms.coerceAtLeast(0) + 59_999) / 60_000).toInt()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    private fun clockLabel(ms: Long): String =
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(ms))

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
        // Changing channel from a Start Over replay: the replay ends here, the new channel plays live.
        exitStartOverState()
        channelSwitchJob?.cancel()
        channelSwitchJob = lifecycleScope.launch {
            // 0 by default (instant, unchanged behavior). A non-zero Channel Change Speed
            // setting waits here for that long — if another zap comes in before it elapses,
            // channelSwitchJob?.cancel() above kills this job before it ever touches streamId/
            // currentIndex or resolves a URL, so mashing D-pad up/down settles on one real
            // network resolve + player reload instead of firing one per press.
            val debounceMs = prefs.channelZapDebounceMs.first()
            if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs.toLong())
            // Again at commit: a Start Over tapped during the Channel Change Speed delay is overtaken.
            exitStartOverState()
            // Same as Home's mini players: a channel that keeps failing plays a more reliable copy of
            // itself. The zap position stays where the requested channel sits in the list.
            val requested = channel
            val channel = if (prefs.preferReliableCopy.first()) {
                repository.findMoreReliableCopy(requested.streamId)?.also { better ->
                    Toast.makeText(
                        this@PlayerActivity,
                        "Playing \"${better.name}\" — \"${requested.name}\" has been failing lately",
                        Toast.LENGTH_LONG
                    ).show()
                } ?: requested
            } else requested
            // Snapshot the outgoing channel as the new recall target — after the guard below
            // (skip if this "change" is actually just re-selecting the same channel already
            // playing, which would otherwise make LAST just toggle in place doing nothing).
            // The show cached for Start Over belongs to the channel being left.
            clearLiveProgramme()
            binding.btnStartOver.visibility = View.GONE
            if (streamId != channel.streamId || serverIndex != -1) {
                previousServerIndex = serverIndex
                previousStreamId = streamId
                previousTitle = streamTitle
                previousZapStreamId = currentZapStreamId
            }
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
            // Position follows the channel asked for, not a substituted copy elsewhere in the list —
            // otherwise Next could jump past channels or keep landing back on the copy.
            currentZapStreamId = requested.streamId
            val idx = channels.indexOfFirst { it.streamId == requested.streamId }
            if (idx >= 0) currentIndex = idx
            loadStream(url)
            notifyPartyChannelChange()
            reportLiveChannelForHandoff()
        }
    }

    /** Merged-channel equivalent of playChannel — same shape, but resolves the stream URL
     * against that channel's own server (getMergedLiveStreamUrl) and tracks serverIndex/
     * mergedStreamId instead of streamId, matching every other merged-aware code path in this
     * Activity (saveVodProgress's serverIndex/mergedStreamId branch, MediaInfo cast metadata). */
    private fun playMergedChannel(channel: com.iptvapp.data.local.entities.MergedChannelEntity) {
        exitStartOverState()
        channelSwitchJob?.cancel()
        channelSwitchJob = lifecycleScope.launch {
            try {
                val debounceMs = prefs.channelZapDebounceMs.first()
                if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs.toLong())
                exitStartOverState()
                clearLiveProgramme()
                // Same recall-snapshot guard as playChannel — see its own kdoc.
                if (serverIndex != channel.serverIndex || mergedStreamId != channel.streamId) {
                    previousServerIndex = serverIndex
                    previousStreamId = if (serverIndex == -1) streamId else mergedStreamId
                    previousTitle = streamTitle
                    previousZapStreamId = currentZapStreamId
                }
                currentZapStreamId = -1
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
            reportLiveChannelForHandoff()
            } catch (_: Exception) {
                Toast.makeText(this@PlayerActivity, "Couldn't load this channel", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Channel changing for a device that is RECEIVING a cast.
     *
     * The normal paths resolve a stream URL from this device's own account, which a cast
     * receiver does not have — that is the whole premise of casting. So the sender's pack
     * carries ready-made URLs and zapping just walks them (see CastRelayManager.send).
     *
     * Returns true when it handled the change, so nextChannel/previousChannel can fall through
     * to their normal behaviour on a device that IS logged in.
     */
    private fun castZap(delta: Int): Boolean {
        if (castChannels.isEmpty()) return false
        castIndex = ((castIndex + delta) % castChannels.size + castChannels.size) % castChannels.size
        val ch = castChannels[castIndex]
        streamTitle = ch.name
        binding.tvChannelTitle.text = ch.name
        channelSwitchJob?.cancel()
        channelSwitchJob = lifecycleScope.launch {
            // Same debounce as playChannel, so holding the button settles on one load.
            val debounceMs = prefs.channelZapDebounceMs.first()
            if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs.toLong())
            // Again at commit: a Start Over tapped during the Channel Change Speed delay is overtaken.
            exitStartOverState()
            loadStream(ch.url)
        }
        return true
    }

    private fun nextChannel() {
        if (castZap(1)) return
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

    /** LAST/RECALL — jumps straight to whatever was playing immediately before the current
     * channel, the same "flip back and forth between two channels" behavior a cable box's LAST
     * button gives you. Deliberately resolves the target fresh via the repository rather than
     * requiring it to still be present in the currently-loaded channels/mergedChannels list —
     * the previous channel could be on a different provider, or a category the current list
     * isn't even showing, so an index-based lookup (like nextChannel/previousChannel use) isn't
     * enough here. No-ops silently if there's nothing to recall yet (fresh launch, or the
     * previous channel no longer resolves) rather than showing an error — a LAST press with
     * nothing to go back to should just do nothing, same as a real remote. */
    private fun recallLastChannel() {
        if (isVod || previousStreamId == -1) return
        val targetServerIndex = previousServerIndex
        val targetStreamId = previousStreamId
        val targetTitle = previousTitle
        val targetZapStreamId = previousZapStreamId
        channelSwitchJob?.cancel()
        channelSwitchJob = lifecycleScope.launch {
            val url = try {
                if (targetServerIndex == -1) repository.getLiveStreamUrl(targetStreamId)
                else repository.getMergedLiveStreamUrl(targetServerIndex, targetStreamId)
            } catch (_: Exception) {
                Toast.makeText(this@PlayerActivity, "Couldn't load the previous channel", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Swap current <-> previous (rather than just overwriting previous with the OLD
            // current) so a second LAST press flips right back, matching a real remote's
            // toggle-between-two-channels behavior instead of only ever going back once.
            previousServerIndex = serverIndex
            previousStreamId = if (serverIndex == -1) streamId else mergedStreamId
            previousTitle = streamTitle
            serverIndex = targetServerIndex
            previousZapStreamId = currentZapStreamId
            currentZapStreamId = if (targetServerIndex == -1) targetZapStreamId.takeIf { it != -1 } ?: targetStreamId else -1
            streamId = if (targetServerIndex == -1) targetStreamId else -1
            mergedStreamId = if (targetServerIndex == -1) -1 else targetStreamId
            streamTitle = targetTitle
            bandwidthTracker?.updateServerIndex(targetServerIndex)
            binding.tvChannelTitle.text = streamTitle
            if (targetServerIndex == -1) {
                val idx = channels.indexOfFirst { it.streamId == currentZapStreamId }
                if (idx >= 0) currentIndex = idx
            } else {
                val idx = mergedChannels.indexOfFirst { it.streamId == targetStreamId }
                if (idx >= 0) mergedCurrentIndex = idx
            }
            loadStream(url)
            notifyPartyChannelChange()
            reportLiveChannelForHandoff()
            showChannelOsd()
        }
    }

    /** A channel picked on the phone remote. Live TV switches here, found by name the way voice
     * tune finds it (the phone may use another provider). False for a movie, replay, recording,
     * audio only, a received cast or a channel on another provider: the TV home screen is brought
     * back to play it instead. */
    override suspend fun remoteTune(name: String): Boolean {
        if (isVod || isRecordingPlayback || audioOnly || castChannels.isNotEmpty()) return false
        val target = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val enabled = prefs.enabledExtraServerIndices.first()
            val merged = db.mergedChannelDao().getAll().first().filter { it.serverIndex == -1 || it.serverIndex in enabled }
            com.iptvapp.util.VoiceTuner.resolve(db, listOf(name), repository.getAllChannels().first(), merged)
        }
        // On another provider than the one playing: this player's channel lists and provider state
        // belong to this one, so the TV home screen reopens it on the right provider instead.
        if (target != null && target.serverIndex != serverIndex) return false
        // Likewise outside the list CH +/− walks here (a favorites-only list, one category of another
        // provider): reopened with its own list, so CH +/− carries on from the new channel.
        if (target != null && target.serverIndex == -1 && channels.none { it.streamId == target.streamId }) return false
        if (target != null && target.serverIndex != -1 && mergedChannels.none { it.streamId == target.streamId }) return false
        val channel = target?.let { if (it.serverIndex == -1) repository.getChannelById(it.streamId) else null }
        val merged = target?.let { if (it.serverIndex != -1) repository.getMergedChannelByIndexAndId(it.serverIndex, it.streamId) else null }
        if (channel == null && merged == null) {
            Toast.makeText(this, "Couldn't find \"$name\" on this TV", Toast.LENGTH_LONG).show()
            return true
        }
        suppressOverlayOnReady = true
        if (channel != null) playChannel(channel) else playMergedChannel(merged!!)
        // After the switch commits, so the banner names the new channel.
        channelSwitchJob?.join()
        showChannelOsd()
        return true
    }

    /** A channel key: changes channel, then shows the banner once the switch has committed (the
     * change runs in channelSwitchJob, after any Channel Change Speed delay), so it names the new
     * channel rather than the one being left. */
    private fun zapWithBanner(zap: () -> Unit) {
        suppressOverlayOnReady = true
        zap()
        val job = channelSwitchJob
        lifecycleScope.launch {
            job?.join()
            if (job == channelSwitchJob) showChannelOsd()
        }
    }

    private fun previousChannel() {
        if (castZap(-1)) return
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
        // Audio only shows one button (SHOW PICTURE); channel, number and seek keys have nothing to
        // act on, but volume and media keys must still reach the system and the media session.
        if (audioOnly) return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> true
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> true
            // Volume and play/pause fall through to the system, which sends them to the session.
            else -> super.onKeyDown(keyCode, event)
        }
        // Quick Peek open: it takes the D-pad, OK, Back and GUIDE. The rest (CH +/-, volume, media,
        // numbers) carry on below, so CH +/- still changes channel with it open.
        if (::quickPeek.isInitialized && quickPeek.isOpen) {
            if (quickPeek.onKeyDown(keyCode, event)) return true
            // Left / Right / OK among its header buttons: ordinary focus moves and clicks, not seeking.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            ) return super.onKeyDown(keyCode, event)
        }
        // Choosing a place to jump to: OK jumps, Back stays put, Left/Right keep choosing (below).
        // Anything else (Play/Pause, Up/Down, CH +/-, ...) jumps first, then does what it always did.
        if (seekSession.active) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    commitSeekPreview(); return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { cancelSeekPreview(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> Unit
                else -> commitSeekPreview()
            }
        }
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
                isVod -> {
                    resetHideTimer()
                    if (seekPreviewApplies()) nudgeSeekPreview(forward = false)
                    else { vodSeekTo(vodPositionMs() - nextVodSkipAmountMs()); notifyPartyStateChange() }
                    true
                }
                timeshiftControlsOpen() && (event?.repeatCount ?: 0) == 0 -> {
                    event?.startTracking()
                    liveSkipPendingMs = -TimeshiftNavigator.REPLAY_10_MS
                    liveSkipLong = false
                    true
                }
                else -> true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                isOverlayVisible -> { resetHideTimer(); super.onKeyDown(keyCode, event) }
                isVod -> {
                    resetHideTimer()
                    if (seekPreviewApplies()) nudgeSeekPreview(forward = true)
                    else { vodSeekTo(vodPositionMs() + nextVodSkipAmountMs()); notifyPartyStateChange() }
                    true
                }
                timeshiftControlsOpen() && (event?.repeatCount ?: 0) == 0 -> {
                    event?.startTracking()
                    liveSkipPendingMs = TimeshiftNavigator.REPLAY_10_MS
                    liveSkipLong = false
                    true
                }
                else -> true
            }
            // Dedicated channel keys (and the phone remote's CH buttons): zap even with the controls open.
            KeyEvent.KEYCODE_CHANNEL_UP -> { if (!isVod) zapWithBanner { nextChannel() }; true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (!isVod) zapWithBanner { previousChannel() }; true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                requestTimeshiftPlayback(!timeshiftIntendedPlaying())
                updatePlayPauseButton(); notifyPartyStateChange(); if (!isVod) refreshTimeshiftUi(); true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                requestTimeshiftPlayback(true)
                updatePlayPauseButton(); notifyPartyStateChange(); if (!isVod) refreshTimeshiftUi(); true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                requestTimeshiftPlayback(false)
                updatePlayPauseButton(); notifyPartyStateChange(); if (!isVod) refreshTimeshiftUi(); true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!isVod && timeshiftControlsOpen()) {
                    seekTimeshiftBy(-TimeshiftNavigator.REPLAY_10_MS)
                    showOverlay()
                    true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!isVod && timeshiftControlsOpen()) {
                    seekTimeshiftBy(TimeshiftNavigator.REPLAY_10_MS)
                    showOverlay()
                    true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                // Skip break showing over a recording: OK skips it, without opening the controls first.
                if (!isOverlayVisible && binding.btnSkipBreak.visibility == View.VISIBLE) { skipCurrentBreak(); return true }
                if (!isOverlayVisible) { showOverlay(); true }
                else { resetHideTimer(); super.onKeyDown(keyCode, event) }
            }
            // GUIDE on live TV opens Quick Peek; pressed again there, the full guide. Android TV keeps
            // GUIDE for its own Live Channels app (a global key), so there, and on the Shield, MENU
            // opens it too.
            KeyEvent.KEYCODE_GUIDE -> {
                if (quickPeekAvailable()) quickPeek.open()
                else if (!isOverlayVisible) showOverlay()
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (quickPeekAvailable()) { quickPeek.open(); true } else super.onKeyDown(keyCode, event)
            }
            // Real remotes' dedicated LAST/RECALL button — see recallLastChannel's kdoc. The OSD
            // is shown from inside recallLastChannel itself (after its async swap actually
            // updates streamTitle/streamId), not here — calling showChannelOsd() synchronously
            // right after would race the coroutine and show the OUTGOING channel's title.
            KeyEvent.KEYCODE_LAST_CHANNEL -> { if (!isOverlayVisible) recallLastChannel(); true }
            // Yellow / X / F key cycles aspect ratio without opening the overlay
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_F -> { cycleResizeMode(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (!audioOnly && !isVod && !isOverlayVisible && timeshiftControlsOpen()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    liveSkipLong = true
                    seekTimeshiftBy(-TimeshiftNavigator.REPLAY_30_MS)
                    showOverlay()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    liveSkipLong = true
                    seekTimeshiftBy(TimeshiftNavigator.REPLAY_30_MS)
                    showOverlay()
                    return true
                }
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && liveSkipPendingMs != 0L) {
            val delta = liveSkipPendingMs
            liveSkipPendingMs = 0L
            val wasLong = liveSkipLong
            liveSkipLong = false
            if (!wasLong && !isVod && !isOverlayVisible && timeshiftControlsOpen()) {
                seekTimeshiftBy(delta)
                showOverlay()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
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
            val isPlaying = if (!isVod && timeshiftSettingOn()) timeshiftIntendedPlaying()
                else player?.isPlaying == true
            val icon = android.graphics.drawable.Icon.createWithResource(
                this, if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                this, 0, Intent(pipPlayPauseAction).setPackage(packageName),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val actionLabel = when {
                !isVod && timeshiftSettingOn() && isPlaying -> "Pause live TV"
                !isVod && timeshiftSettingOn() -> "Resume"
                isPlaying -> "Pause"
                else -> "Play"
            }
            val action = android.app.RemoteAction(icon, actionLabel, actionLabel, pendingIntent)
            builder.setActions(listOf(action))
        }
        return builder.build()
    }

    private fun registerPipActionReceiver() {
        if (pipActionReceiver != null) return
        pipActionReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != pipPlayPauseAction) return
                val wantPlay = if (!isVod && timeshiftSettingOn()) !timeshiftIntendedPlaying()
                    else player?.playWhenReady != true
                requestTimeshiftPlayback(wantPlay)
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
        val pipAllowed = prefs.pipEnabledNow()
        if (!isVod && pipAllowed && !isLargeScreenDevice() && !audioOnly && !fullGuidePending && !isFinishing) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            isOverlayVisible = false
            cancelSeekPreview()
            binding.btnSkipBreak.visibility = View.GONE
            binding.liveProgramBar.visibility = View.GONE
            binding.osdTopBar.visibility = View.GONE
            binding.playerTopScrim.visibility = View.GONE
            binding.playerBottomScrim.visibility = View.GONE
            quickPeek.stop()
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

    // ─── Quick Peek (the mini guide over live TV; see QuickPeekPanel) ───────

    private fun quickPeekAvailable(): Boolean =
        !isVod && !isCatchup && !audioOnly && !isRecordingPlayback && castSession == null && castChannels.isEmpty()

    private fun setupFavoritesGuide() {
        quickPeek = QuickPeekPanel(this, binding, repository, db, prefs, object : QuickPeekPanel.Host {
            override fun playingKey(): com.iptvapp.util.QuickPeek.Key? {
                if (!quickPeekAvailable()) return null
                val id = liveCopyId()
                return if (id == -1) null else com.iptvapp.util.QuickPeek.Key(serverIndex, id)
            }

            override fun tune(primary: ChannelEntity?, merged: com.iptvapp.data.local.entities.MergedChannelEntity?, zapList: List<Any>) {
                // CH +/- carries on through the list the channel was picked from, as on the home screens.
                if (primary != null && serverIndex == -1) {
                    channels = zapList.filterIsInstance<ChannelEntity>()
                    currentIndex = channels.indexOfFirst { it.streamId == primary.streamId }
                    playChannel(primary)
                } else if (merged != null && merged.serverIndex == serverIndex) {
                    mergedChannels = zapList.filterIsInstance<com.iptvapp.data.local.entities.MergedChannelEntity>()
                    mergedCurrentIndex = mergedChannels.indexOfFirst { it.streamId == merged.streamId }
                    playMergedChannel(merged)
                }
            }

            override fun openFullGuide() = openFullGuideFromPlayer()

            override fun onOpening() {
                hideHandler.removeCallbacks(hideRunnable)
                hideRunnable.run()
            }
        })
        quickPeek.setAccent(playerAccent)
        binding.btnGuide.setOnClickListener { if (quickPeekAvailable()) quickPeek.toggle() }
    }

    private val fullGuideLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fullGuidePending = false
        // Taken now, not after the lookups below, so onStart's fallback cannot also start the old channel.
        val held = heldStartForGuide
        heldStartForGuide = false
        val data = result.data?.takeIf { result.resultCode == Activity.RESULT_OK }
        if (data == null) { carryOn(held); return@registerForActivityResult }
        val pickServer = data.getIntExtra("server_index", -1)
        val pickId = if (pickServer == -1) data.getIntExtra("stream_id", -1) else data.getIntExtra("merged_stream_id", -1)
        val replayUrl = data.getStringExtra("timeshift_url")
        if (pickId == -1) { carryOn(held); return@registerForActivityResult }
        lifecycleScope.launch {
            if (replayUrl == null && pickServer == serverIndex && !isVod) {
                // The same provider: an ordinary channel change, in place.
                val tuned = if (pickServer == -1) {
                    repository.getChannelById(pickId)?.also { ch ->
                        if (channels.none { it.streamId == ch.streamId }) {
                            channels = ch.categoryId?.let { repository.getChannelsByCategory(it).first() } ?: listOf(ch)
                            currentIndex = channels.indexOfFirst { it.streamId == ch.streamId }
                        }
                        playChannel(ch)
                    }
                } else {
                    repository.getMergedChannelByIndexAndId(pickServer, pickId)?.also { ch ->
                        if (mergedChannels.none { it.streamId == ch.streamId }) {
                            mergedChannels = repository.getMergedChannelsByCategory(pickServer, ch.categoryId).first()
                            mergedCurrentIndex = mergedChannels.indexOfFirst { it.streamId == ch.streamId }
                        }
                        playMergedChannel(ch)
                    }
                }
                if (tuned == null) carryOn(held)
                return@launch
            }
            // A replay, or another provider's channel: a player of its own, as the home screens
            // open them. Its answer goes back to whatever opened this one.
            val url = replayUrl ?: try {
                if (pickServer == -1) repository.getLiveStreamUrl(pickId) else repository.getMergedLiveStreamUrl(pickServer, pickId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Toast.makeText(this@PlayerActivity, "Couldn't load this channel", Toast.LENGTH_SHORT).show()
                carryOn(held)
                return@launch
            }
            val title = data.getStringExtra("timeshift_title")
                ?: if (pickServer == -1) repository.getChannelById(pickId)?.name.orEmpty()
                else repository.getMergedChannelByIndexAndId(pickServer, pickId)?.let { "${it.name} · ${it.serverNickname}" }.orEmpty()
            startActivity(Intent(this@PlayerActivity, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                putExtra("stream_url", url)
                putExtra("stream_title", title)
                putExtra("stream_id", if (pickServer == -1) pickId else -1)
                putExtra("server_index", pickServer)
                putExtra("merged_stream_id", if (pickServer == -1) -1 else pickId)
                if (replayUrl != null) {
                    putExtra("catchup_start_sec", data.getLongExtra("catchup_start_sec", 0L))
                    putExtra("catchup_duration_min", data.getIntExtra("catchup_duration_min", 0))
                    putExtra("catchup_channel_name", data.getStringExtra("catchup_channel_name").orEmpty())
                }
            })
            finish()
        }
    }

    /** Quick Peek's GUIDE: the full grid. The picture stops while it is open, as for any screen on top. */
    private fun openFullGuideFromPlayer() {
        fullGuidePending = true
        try {
            fullGuideLauncher.launch(
                // Our own screen on top, not the user leaving: no automatic picture-in-picture.
                Intent(this, com.iptvapp.ui.guide.EpgTimelineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
        } catch (e: Exception) {
            fullGuidePending = false
            Toast.makeText(this, "Couldn't open the guide", Toast.LENGTH_SHORT).show()
        }
    }

    /** Back from the full guide with nothing picked (or nothing playable): the channel carries on. */
    private fun resumeHeldStart() {
        val held = heldStartForGuide
        heldStartForGuide = false
        carryOn(held)
    }

    private fun carryOn(held: Boolean) {
        if (held && player != null && streamUrl.isNotEmpty()) loadStream(streamUrl)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        player?.let { binding.playerView.player = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("ext_sub_generation", subtitleGeneration)
        outState.putInt("ext_sub_pick_gen", subtitlePickGeneration)
        subtitlePickUrl?.let { outState.putString("ext_sub_pick_url", it) }
        if (subtitlePickerPending) {
            outState.putLong("resume_position_ms", resumePositionMs)
            outState.putBoolean("subtitle_picker_paused", resumePausedAfterPicker)
        }
        externalSubtitlePath?.let {
            outState.putString("ext_sub_path", it)
            outState.putString("ext_sub_mime", externalSubtitleMime)
            outState.putString("ext_sub_lang", externalSubtitleLanguage)
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            if (audioOnly) {
                // Still playing in the background: stay on the audio panel. If it was stopped from
                // the notification meanwhile, come back with the picture instead.
                if (AudioOnlyService.running.value) return
                leaveAudioOnlyPanel()
            } else {
                // A session left over from before this activity was recreated would be a second
                // stream on a one-stream account.
                AudioOnlyService.stop(this)
            }
            com.iptvapp.IptvApplication.logPlaybackEvent(
                applicationContext,
                "SESSION START: isVod=$isVod streamId=$streamId title=$streamTitle url=$streamUrl resumeMs=$resumePositionMs"
            )
            player = buildPlayer()
            if (isCatchup && catchupResumeMs > 0L) {
                // Back from the background: the old player was released in onStop, and the
                // open URL only knows its own start, so reopen the programme where it was.
                val at = catchupResumeMs
                catchupResumeMs = 0L
                lifecycleScope.launch { loadCatchupAt(at); player?.playWhenReady = true }
            } else if (isVod && resumePositionMs > 0L) {
                // Applying this as a seekTo() *after* the player had already buffered and
                // become ready at position 0 forced a mid-stream HTTP range renegotiation for
                // progressively-served files (.mkv movies) — some providers hang on that
                // instead of erroring, leaving playback stuck in STATE_BUFFERING forever with
                // no error/ended event to react to (black screen, "plays fine in mini player"
                // because the mini player never resumes at all). Passing the start position
                // at load time lets ExoPlayer request the correct range from the very first
                // request instead of re-requesting a new range after already starting at 0.
                val startPos = resumePositionMs
                val stayPaused = partyStartPaused || resumePausedAfterPicker
                resumePositionMs = 0L
                resumePausedAfterPicker = false
                subtitlePickerPending = false
                updatePictureProfile()
                player?.setMediaItem(itemWithExternalSubtitle(streamUrl), startPos)
                player?.prepare()
                player?.playWhenReady = !stayPaused
            } else if (fullGuidePending && !isVod) {
                // Back from Quick Peek's full guide: its answer is delivered next. A pick plays that
                // channel; no answer, or one that can't play, carries on with this (resumeHeldStart).
                heldStartForGuide = true
                binding.root.post { if (!fullGuidePending) resumeHeldStart() }
            } else {
                loadStream(streamUrl)
                if (partyStartPaused) requestTimeshiftPlayback(false)
                else if (resumePausedAfterPicker) player?.playWhenReady = false
                resumePausedAfterPicker = false
                subtitlePickerPending = false
            }
            if (partyStartPaused) updatePlayPauseButton()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val pressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (!pressure) return
        zapMemoryPressure = true
        zapPreloadJob?.cancel()
        zapPreloadJob = null
        val factory = playbackDataSourceFactory
        if (factory != null) {
            zapPreloader.replace(serverIndex, currentLiveMediaId(), currentZapIndex(), emptyList())
        }
    }

    override fun onStop() {
        super.onStop()
        if (::quickPeek.isInitialized) quickPeek.stop()
        // Out of sight: no place is being chosen, and no picture is being made for a player that's gone.
        cancelSeekPreview()
        thumbnailSourceUrl = null
        thumbnailSourceJob?.cancel()
        thumbnailManager?.setSource(null)
        // Out of sight is not watching. Playing resumes the count through onIsPlayingChanged.
        val stoppedAt = android.os.SystemClock.elapsedRealtime()
        feedTracker.onNotPlaying(stoppedAt)
        reportFeed(feedTracker.flush(stoppedAt))
        if (isCatchup && !isChangingConfigurations && player != null) {
            // vodPositionMs includes a queued reopen's target; the resume path in onStart takes
            // over from here, so the queued reopen must not also fire on the new player.
            catchupResumeMs = vodPositionMs()
            catchupReloadJob?.cancel()
            catchupPendingTargetMs = -1L
        }
        saveVodProgress()
        sleepTimer?.cancel()
        sleepDeadlineMs = 0L
        retryJob?.cancel()
        seekRunnable?.let { seekHandler.removeCallbacks(it) }
        statsHandler.removeCallbacks(statsRunnable)
        skipNextHandler.removeCallbacks(skipNextRunnable)
        stopHealthBadge()
        if (!isChangingConfigurations) {
            // Best-effort — a stopped/backgrounded live session shouldn't keep dangling as a
            // "continue watching" prompt on another paired device once this device is done with
            // it. Fire-and-forget on a process-wide scope for the same reason the bandwidth
            // tracker flush below is — lifecycleScope may already be cancelling by the time
            // onStop runs.
            if (!isVod) kotlinx.coroutines.GlobalScope.launch { playbackHandoffManager.clearOwnSession() }
            stopTimeshiftTicker()
            seekHandler.removeCallbacks(catchupOfferRunnable)
            stopTimeshift()
            player?.release()
            player = null
            releasePlayerPreload()
            pictureTitleJob?.cancel()
            bandwidthTracker?.stop()
            // Fire-and-forget on a process-wide scope: lifecycleScope may already be cancelling
            // by the time onStop runs, same reasoning as the Trakt stop-scrobble call elsewhere
            // in this Activity — a plain Room write from GlobalScope is safe here.
            val tracker = bandwidthTracker
            if (tracker != null) kotlinx.coroutines.GlobalScope.launch { tracker.flush() }
        }
    }

    override fun finish() {
        if (audioOnly) AudioOnlyService.stop(this)
        setResult(Activity.RESULT_OK, android.content.Intent().apply {
            putExtra("stream_id", streamId)
            // A replay hands back the channel live (or nothing to play if that URL never
            // resolved) — the timeshift URL would replay the show again as if it were live TV.
            putExtra("stream_url", if (isCatchup) catchupLiveUrl else streamUrl)
            putExtra("stream_title", if (isCatchup) catchupChannelName.ifBlank { streamTitle } else streamTitle)
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
        // Lets go of the seek preview's pictures and its worker (the disk cache stays, bounded).
        thumbnailManager?.close()
        thumbnailManager = null
        if (isChangingConfigurations) {
            // onStop keeps the session across a configuration change. Rotation is listed in
            // configChanges, so it never destroys this activity and the window stays. A change
            // that does recreate it (locale, for example) must drop the pump and the player
            // here, or the replacement opens a second provider connection.
            stopTimeshiftTicker()
            seekHandler.removeCallbacks(catchupOfferRunnable)
            stopTimeshift()
            binding.playerView.player = null
            player?.release()
            player = null
            releasePlayerPreload()
            pictureTitleJob?.cancel()
        }
        super.onDestroy()
        if (!isChangingConfigurations) {
            partyListenerReg?.remove()
            partyHeartbeatHandler.removeCallbacks(partyHeartbeatRunnable)
            pollListenerReg?.remove()
            pollAutoCloseJob?.cancel()
            rewatchNotesListenerReg?.remove()
            castSessionListenerReg?.remove()
            if (castSessionCode.isNotEmpty()) {
                val cCode = castSessionCode
                kotlinx.coroutines.GlobalScope.launch { castRelay.endSession(cCode) }
            }
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
        stopCastWatch()
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

    /** This phone's address on the [receiver]'s network (see CastNetwork.pickAddress). The first
     * IPv4 address found used to be taken, and with mobile data kept on alongside Wi-Fi that
     * could be the mobile one — a Chromecast can't reach it, and loaded forever. */
    private fun getLocalIpAddress(receiver: java.net.InetAddress?): String? {
        try {
            val candidates = mutableListOf<com.iptvapp.cast.CastNetwork.Candidate>()
            for (iface in java.net.NetworkInterface.getNetworkInterfaces() ?: return null) {
                if (iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val addr = ia.address as? java.net.Inet4Address ?: continue
                    candidates += com.iptvapp.cast.CastNetwork.Candidate(iface.name, addr, ia.networkPrefixLength.toInt())
                }
            }
            val picked = com.iptvapp.cast.CastNetwork.pickAddress(candidates, receiver)
            if (com.iptvapp.BuildConfig.DEBUG) Log.d("CastProxy", "Local IP: $picked of ${candidates.map { it.interfaceName }}")
            return picked
        } catch (e: Exception) {
            Log.e("CastProxy", "getLocalIpAddress failed", e)
        }
        return null
    }

    // Watches the receiver after a load, to say why a cast didn't start instead of leaving the
    // TV loading with no word (see CastNetwork.problemMessage).
    private val CAST_START_TIMEOUT_MS = 30_000L
    private var castWatchJob: Job? = null
    private var castWatch: Pair<com.google.android.gms.cast.framework.media.RemoteMediaClient, com.google.android.gms.cast.framework.media.RemoteMediaClient.Callback>? = null

    // Moves on whenever a watch starts or stops, so a late result from an older load is ignored.
    private var castWatchGeneration = 0

    private fun stopCastWatch() {
        castWatchGeneration++
        castWatchJob?.cancel()
        castWatchJob = null
        castWatch?.let { (client, callback) -> client.unregisterCallback(callback) }
        castWatch = null
    }

    /** Returns what the load's result callback calls on a failure; it does nothing once this
     * watch has been stopped or replaced. */
    private fun watchCast(client: com.google.android.gms.cast.framework.media.RemoteMediaClient, proxied: Boolean): () -> Unit {
        stopCastWatch()
        val generation = castWatchGeneration
        var reported = false
        fun report(why: String) {
            if (reported || generation != castWatchGeneration) return
            reported = true
            stopCastWatch()
            val proxy = castProxy
            val requests = proxy?.receiverRequests ?: 0
            val upstream = proxy?.lastUpstreamStatus
            com.iptvapp.IptvApplication.logPlaybackEvent(
                applicationContext,
                "CAST FAILED: $why proxied=$proxied receiverRequests=$requests upstreamStatus=$upstream isVod=$isVod"
            )
            Toast.makeText(this, com.iptvapp.cast.CastNetwork.problemMessage(proxied, requests, upstream), Toast.LENGTH_LONG).show()
        }
        val callback = object : com.google.android.gms.cast.framework.media.RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val status = client.mediaStatus ?: return
                when {
                    status.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING -> stopCastWatch()
                    status.playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE &&
                        status.idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_ERROR -> report("receiver error")
                }
            }
        }
        client.registerCallback(callback)
        castWatch = client to callback
        // A receiver that can't fetch the stream can sit on its loading screen without ever
        // reporting an error.
        castWatchJob = lifecycleScope.launch {
            delay(CAST_START_TIMEOUT_MS)
            val state = client.mediaStatus?.playerState
            if (state != com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING &&
                state != com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PAUSED
            ) report("not playing after ${CAST_START_TIMEOUT_MS / 1000}s")
        }
        return { report("load failed") }
    }
}
