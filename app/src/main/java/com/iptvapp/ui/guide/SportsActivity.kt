package com.iptvapp.ui.guide

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.databinding.ActivitySportsBinding
import com.iptvapp.util.LiveScores
import com.iptvapp.util.SportsFinder
import com.iptvapp.util.SportsMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Sports Now (v7.13): every game on now and coming up in the next 18 hours, across all providers,
 * grouped by league (util/SportsFinder). Picking one hands back the same result the guide grid
 * does (stream_id, or server_index + merged_stream_id), so the home screens tune it in the mini
 * player with the code they already use for the guide.
 */
@AndroidEntryPoint
class SportsActivity : AppCompatActivity() {

    @Inject lateinit var db: IptvDatabase
    @Inject lateinit var prefs: PreferencesManager
    @Inject lateinit var repository: com.iptvapp.data.repository.XtreamRepository
    @Inject lateinit var okHttpClient: okhttp3.OkHttpClient

    private lateinit var binding: ActivitySportsBinding

    /** The mini player (v7.75): a pick plays here, so the list stays put if it's the wrong game. */
    private var mini: androidx.media3.exoplayer.ExoPlayer? = null
    private var playing: SportsFinder.Channel? = null
    private var playJob: kotlinx.coroutines.Job? = null
    private var query = ""
    private var latestEvents: List<LiveScores.Event> = emptyList()
    private var accentColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySportsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpMiniPlayer(savedInstanceState)
        // Edge-to-edge (target SDK 35): keep the header and list clear of the status bar, cutout
        // and navigation bar.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnSportsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.etSportsSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString().orEmpty()
                if (accentColor != 0) show(latestGames, latestEvents, accentColor)
            }
        })
        binding.rvSports.layoutManager = LinearLayoutManager(this)
        lifecycleScope.launch {
            val accent = com.iptvapp.util.RackAccent.load(prefs)
            binding.tvSportsTitle.setTextColor(accent.start)
            // Games come from the stored guide: an old one hides most of them, so say so.
            val refreshed = prefs.lastEpgRefreshTime.first()
            if (System.currentTimeMillis() - refreshed > 24 * 3_600_000L) {
                binding.tvSportsSubtitle.text = if (refreshed == 0L) "The guide hasn't been refreshed yet, so games are missing. Refresh it in Settings > Stream & EPG."
                    else "The guide was last refreshed " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(refreshed)) +
                        ", so games may be missing. Refresh it in Settings > Stream & EPG, or turn on Auto refresh."
                binding.tvSportsSubtitle.setTextColor(getColor(R.color.rack_danger_text))
            }
            // Games start and end while this is open: look again every minute while it's on screen
            // (and on coming back to it), updating only when the list actually changed.
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // A new preferred network reshuffles the feeds at once, not at the next minute.
                kotlinx.coroutines.flow.merge(
                    prefs.sportsPreferredNetworks,
                    kotlinx.coroutines.flow.flow { while (true) { emit(""); kotlinx.coroutines.delay(60_000L) } },
                ).collectLatest {
                    val preferred = SportsFinder.decodePreferred(prefs.sportsPreferredNetworks.first())
                    // The guide's games at once with the scores already held, then again with fresh
                    // scores (and any games only today's team names reveal).
                    val held = LiveScores.cached()
                    val heldTeams = LiveScores.teams(held)
                    var games = withContext(Dispatchers.IO) { SportsFinder.find(db, preferredNetworks = preferred, teams = heldTeams) }
                    show(games, held, accent.start)
                    val fresh = withContext(Dispatchers.IO) { LiveScores.events() }
                    val freshTeams = LiveScores.teams(fresh)
                    if (freshTeams != heldTeams) games = withContext(Dispatchers.IO) { SportsFinder.find(db, preferredNetworks = preferred, teams = freshTeams) }
                    show(games, fresh, accent.start)
                }
            }
        }
    }

    private var shownSignature: String? = null
    private var shownTeams: String? = null
    private var latestGames: List<SportsFinder.Game> = emptyList()

    private fun show(all: List<SportsFinder.Game>, events: List<LiveScores.Event>, accent: Int) {
        latestGames = all
        latestEvents = events
        accentColor = accent
        lifecycleScope.launch { bindTeamShortcuts(prefs.favoriteTeams.first(), accent) }
        val matched = all.mapNotNull { g -> LiveScores.match(g, events)?.let { g to it } }.toMap()
        val games = all.filter { matchesQuery(it, matched[it]) }
        val scores = matched.filterValues { it.state != "pre" }
        val now0 = System.currentTimeMillis()
        val replays = games.filter { it.isLive(now0) && it !in scores && LiveScores.likelyReplay(it, events) }.toSet()
        val items = buildItems(games, scores, replays)
        val now = System.currentTimeMillis()
        // What's on screen depends on these: which rows, in which section, live or not, the score —
        // and the date, which decides the "Tmrw" labels.
        val signature = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now)) + "|$query|" + items.joinToString("|") {
            if (it is SportsFinder.Game) "${it.title}@${it.startMs}:${it.channel.serverIndex}:${it.channel.streamId}:${it.isLive(now)}:${scores[it]?.label}:${it in replays}" else it.toString()
        }
        if (signature == shownSignature) return
        shownSignature = signature
        if (games.isEmpty()) {
            binding.rvSports.visibility = View.GONE
            binding.tvSportsEmpty.visibility = View.VISIBLE
            binding.tvSportsEmpty.text = if (all.isNotEmpty()) "No games match “${query.trim()}”."
                else "No games found in the guide right now. If the guide is empty or old, refresh it in Settings > Stream & EPG and look again."
            return
        }
        binding.tvSportsEmpty.visibility = View.GONE
        binding.rvSports.visibility = View.VISIBLE
        val existing = binding.rvSports.adapter as? SportsAdapter
        existing?.scores = scores
        existing?.replays = replays
        if (existing == null) {
            binding.rvSports.adapter = SportsAdapter(items, accent, onPick = { pick(current(it)) }, onHold = { gameView(current(it)) })
                .also { it.scores = scores; it.replays = replays }
            binding.rvSports.post { binding.rvSports.getChildAt(1)?.requestFocus() }
            return
        }
        // An update while browsing: same adapter, same scroll, and focus back on the same game
        // (by identity — rows above it may have come or gone), wherever it now sits.
        val focused = binding.rvSports.focusedChild
            ?.let { binding.rvSports.getChildAdapterPosition(it) }
            ?.let { existing.itemAt(it) as? SportsFinder.Game }
        val scroll = binding.rvSports.layoutManager?.onSaveInstanceState()
        existing.update(items)
        binding.rvSports.layoutManager?.onRestoreInstanceState(scroll)
        if (focused != null) {
            val pos = items.indexOfFirst {
                it is SportsFinder.Game && it.title == focused.title && it.startMs == focused.startMs &&
                    it.channel.serverIndex == focused.channel.serverIndex && it.channel.streamId == focused.channel.streamId
            }
            if (pos >= 0) binding.rvSports.post {
                binding.rvSports.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    ?: run { binding.rvSports.scrollToPosition(pos); binding.rvSports.post {
                        binding.rvSports.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    } }
            }
        }
    }

    /** "On now" first (every league, so what you can watch right away is at the top; close games
     * first, likely replays last), then what's coming up, grouped by league. */
    private fun buildItems(games: List<SportsFinder.Game>, scores: Map<SportsFinder.Game, LiveScores.Event>, replays: Set<SportsFinder.Game>): List<Any> {
        val items = mutableListOf<Any>()
        val now = System.currentTimeMillis()
        val live = games.filter { it.isLive(now) }
            .sortedWith(compareBy<SportsFinder.Game> { it in replays }.thenByDescending { scores[it]?.close == true })
        if (live.isNotEmpty()) {
            items += "On now"
            items.addAll(live)
        }
        var league: String? = null
        for (g in games.filterNot { it.isLive(now) }) {
            if (g.league != league) {
                league = g.league
                items += g.league
            }
            items += g
        }
        return items
    }

    private fun bindTeamShortcuts(stored: String, accent: Int) {
        if (stored == shownTeams) return
        shownTeams = stored
        val teams = SportsMode.parseTeams(stored)
        binding.teamShortcuts.removeAllViews()
        binding.teamShortcutRow.visibility = if (teams.isEmpty()) View.GONE else View.VISIBLE
        val d = resources.displayMetrics.density
        for (team in teams) {
            val chip = TextView(this).apply {
                text = team
                contentDescription = "$team. Play the game. Long press to remove."
                setTextColor(accent)
                typeface = ResourcesCompat.getFont(this@SportsActivity, R.font.barlow_condensed_bold)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setBackgroundResource(R.drawable.rack_row_bg)
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * d).toInt() }
                setOnClickListener { tuneTeam(team) }
                setOnLongClickListener {
                    lifecycleScope.launch {
                        val next = SportsMode.removeFavorite(prefs.favoriteTeams.first(), team)
                        prefs.setFavoriteTeams(next)
                        shownTeams = null
                        bindTeamShortcuts(next, accent)
                    }
                    true
                }
            }
            binding.teamShortcuts.addView(chip)
        }
    }

    private fun tuneTeam(team: String) {
        val game = SportsMode.gameForTeam(latestGames, team, System.currentTimeMillis())
        if (game == null) {
            Toast.makeText(this, "No $team game in the guide right now", Toast.LENGTH_SHORT).show()
            return
        }
        pick(game)
    }

    /**
     * Game View: every feed carrying the game, best first, each with its quality and rating, to
     * play any of them; a preferred network for the game's league; and the live games in Mosaic.
     */
    private fun gameView(game: SportsFinder.Game) {
        val now = System.currentTimeMillis()
        val feeds = game.feeds
        val lines = feeds.map { feedLine(it) }.toMutableList()
        val preferAt = lines.size
        lines += "Prefer a network for ${game.league}…"
        // Only games on now: this one first when it is live, never an upcoming one.
        val others = latestGames.filter { it.isLive(now) && !sameGame(it, game) }
        val grid = ((if (game.isLive(now)) listOf(game) else emptyList()) + others).take(6)
        val mosaicAt = if (grid.size >= 2) lines.size.also { lines += "Watch ${grid.size} live games in Mosaic" } else -1
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(game.title)
            .setItems(lines.toTypedArray()) { _, which ->
                when (which) {
                    in feeds.indices -> pick(game, feeds[which])
                    preferAt -> choosePreferredNetwork(game)
                    mosaicAt -> openInMosaic(grid)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** A row's game as of the latest refresh: a refresh that only changed its other feeds or
     * their ratings leaves the row on screen holding the older copy. */
    private fun current(held: SportsFinder.Game): SportsFinder.Game =
        latestGames.firstOrNull { sameGame(it, held) } ?: held

    /**
     * The same game across refreshes, which build new Game objects: same title and start, and a
     * feed in common (two regional games with one generic title air on different channels).
     */
    private fun sameGame(a: SportsFinder.Game, b: SportsFinder.Game): Boolean =
        a.title == b.title && a.startMs == b.startMs &&
            a.feeds.any { f -> b.feeds.any { it.serverIndex == f.serverIndex && it.streamId == f.streamId } }

    /** "ESPN HD · T-Rex · FHD · Excellent" */
    private fun feedLine(feed: SportsFinder.Channel): String {
        val parts = mutableListOf(feed.name)
        com.iptvapp.util.ProviderDashboard.qualityOf(feed.network)?.let { parts += it }
        parts += com.iptvapp.util.ProviderDashboard.scoreLabel(feed.score ?: com.iptvapp.util.FeedScore.UNKNOWN, feed.score != null)
        return parts.joinToString(" · ")
    }

    private fun choosePreferredNetwork(game: SportsFinder.Game) {
        lifecycleScope.launch {
            val saved = SportsFinder.decodePreferred(prefs.sportsPreferredNetworks.first())
            // Networks by their own name, once each ("ESPN" whether it comes from one provider or three).
            val networks = game.feeds.map { it.network }.distinctBy { com.iptvapp.util.ChannelNameMatcher.normalize(it) }
            val current = saved[game.league]
            val labels = networks.map { if (current != null && com.iptvapp.util.ChannelNameMatcher.matches(it, current)) "$it  ✓" else it } +
                listOf(if (current == null) "No preference  ✓" else "No preference")
            androidx.appcompat.app.AlertDialog.Builder(this@SportsActivity)
                .setTitle("${game.league}: which network first?")
                .setItems(labels.toTypedArray()) { _, which ->
                    val next = if (which < networks.size) saved + (game.league to networks[which]) else saved - game.league
                    lifecycleScope.launch {
                        prefs.setSportsPreferredNetworks(SportsFinder.encodePreferred(next))
                        Toast.makeText(
                            this@SportsActivity,
                            if (which < networks.size) "${game.league} games open on ${networks[which]} first" else "${game.league}: no preferred network",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /**
     * Live games in a Mosaic grid, the chosen one first when it is live, each on its best feed. Opens
     * as a one-off grid: the viewer's saved layouts and their last layout are left as they were.
     */
    private fun openInMosaic(games: List<SportsFinder.Game>) {
        val size = when {
            games.size <= 2 -> 2
            games.size <= 4 -> 4
            else -> 6
        }
        val tiles = games.map { g ->
            com.iptvapp.ui.mosaic.MosaicLayout.Tile(g.channel.serverIndex, g.channel.streamId, g.channel.network)
        } + List(size - games.size) { null }
        startActivity(Intent(this, com.iptvapp.ui.mosaic.MosaicActivity::class.java).apply {
            putExtra(
                com.iptvapp.ui.mosaic.MosaicActivity.EXTRA_LAYOUT,
                com.iptvapp.ui.mosaic.MosaicLayouts.encode(listOf(com.iptvapp.ui.mosaic.MosaicLayout("Live games", size, tiles)))
            )
        })
    }

    /** All words of the search in the game's title, league, channels or teams. */
    private fun matchesQuery(game: SportsFinder.Game, event: LiveScores.Event?): Boolean {
        val words = query.trim().lowercase(Locale.getDefault()).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = buildString {
            append(game.title).append(' ').append(game.league)
            game.feeds.forEach { append(' ').append(it.name) }
            event?.let { e -> (listOf(e.home.abbr, e.away.abbr) + e.home.names + e.away.names).forEach { append(' ').append(it) } }
        }.lowercase(Locale.getDefault())
        return words.all { hay.contains(it) }
    }

    /**
     * A pick plays in the mini player and the list stays. Picking what is already playing (or the
     * picture, or Full screen) goes full screen through Home, which owns the full-screen player.
     */
    private fun pick(game: SportsFinder.Game, feed: SportsFinder.Channel = game.channel) {
        val now = playing
        if (now != null && now.serverIndex == feed.serverIndex && now.streamId == feed.streamId) {
            handBack(feed, fullScreen = true)
            return
        }
        preview(game, feed)
    }

    private fun setUpMiniPlayer(saved: Bundle?) {
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape (car box, TV): the picture beside the list, not above it.
            binding.sportsBody.orientation = LinearLayout.HORIZONTAL
            binding.sportsMiniPane.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.45f)
            binding.sportsListPane.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.55f)
        }
        binding.btnSportsFullScreen.setOnClickListener { playing?.let { handBack(it, fullScreen = true) } }
        binding.sportsMiniPlayer.setOnClickListener { playing?.let { handBack(it, fullScreen = true) } }
        binding.btnSportsMiniClose.setOnClickListener { stopMini() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            // Leaving with a game on keeps it on, in Home's mini player.
            override fun handleOnBackPressed() {
                val now = playing
                if (now != null) handBack(now, fullScreen = false) else finish()
            }
        })
        // Turning the phone keeps the game on.
        val server = saved?.getInt("mini_server", Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (server != Int.MIN_VALUE) {
            val channel = SportsFinder.Channel(server, saved!!.getInt("mini_stream"), saved.getString("mini_name").orEmpty(), false)
            preview(null, channel, saved.getString("mini_title").orEmpty())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        playing?.let {
            outState.putInt("mini_server", it.serverIndex)
            outState.putInt("mini_stream", it.streamId)
            outState.putString("mini_name", it.name)
            outState.putString("mini_title", binding.tvSportsMiniTitle.tag as? String)
        }
    }

    private fun preview(game: SportsFinder.Game?, feed: SportsFinder.Channel, title: String = game?.title.orEmpty()) {
        playing = feed
        binding.sportsMiniPane.visibility = View.VISIBLE
        binding.tvSportsMiniTitle.tag = title
        binding.tvSportsMiniTitle.text = if (title.isBlank()) feed.name else "$title\n${feed.name}"
        binding.sportsMiniPlayer.post {
            val w = binding.sportsMiniPlayer.width
            if (w > 0) binding.sportsMiniPlayer.layoutParams = binding.sportsMiniPlayer.layoutParams.apply {
                height = minOf(w * 9 / 16, (resources.displayMetrics.heightPixels * 0.4f).toInt())
            }
        }
        playJob?.cancel()
        playJob = lifecycleScope.launch {
            val url = try {
                withContext(Dispatchers.IO) {
                    if (feed.serverIndex == -1) repository.getLiveStreamUrl(feed.streamId)
                    else repository.getMergedLiveStreamUrl(feed.serverIndex, feed.streamId)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { "" }
            if (url.isBlank()) { miniFailed(); return@launch }
            val player = miniPlayer()
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    /** Same data source as Mosaic and the home mini player: some provider CDNs refuse ExoPlayer's
     * default User-Agent on the stream itself. */
    private fun miniPlayer(): androidx.media3.exoplayer.ExoPlayer = mini ?: run {
        val upstream = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("MKTV/${com.iptvapp.BuildConfig.VERSION_NAME} (Linux;Android ${android.os.Build.VERSION.RELEASE}) ExoPlayerLib/1.4.1")
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this).setDataSourceFactory(upstream)
        androidx.media3.exoplayer.ExoPlayer.Builder(this).setMediaSourceFactory(sources).build().also { p ->
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) = miniFailed()
            })
            binding.sportsMiniPlayer.player = p
            mini = p
        }
    }

    private fun miniFailed() {
        val title = binding.tvSportsMiniTitle.tag as? String
        binding.tvSportsMiniTitle.text = listOfNotNull(title?.takeIf { it.isNotBlank() }, playing?.name).joinToString("\n") +
            "\nThis channel didn't play. Hold the game for its other channels."
    }

    private fun stopMini() {
        playJob?.cancel()
        playing = null
        mini?.release()
        mini = null
        binding.sportsMiniPlayer.player = null
        binding.sportsMiniPane.visibility = View.GONE
    }

    override fun onStart() {
        super.onStart()
        // Coming back: the game picks up at the live edge.
        mini?.takeIf { playing != null }?.let { it.prepare(); it.playWhenReady = true }
    }

    override fun onStop() {
        super.onStop()
        // Stopped, not paused: a paused live stream holds the provider connection.
        mini?.stop()
    }

    override fun onDestroy() {
        mini?.release()
        mini = null
        super.onDestroy()
    }

    /** Back to Home with [ch]: in its mini player, or full screen. The mini player lets go of the
     * provider connection first, for one-connection accounts. */
    private fun handBack(ch: SportsFinder.Channel, fullScreen: Boolean) {
        stopMini()
        setResult(RESULT_OK, Intent().apply {
            putExtra("full_screen", fullScreen)
            if (ch.serverIndex == -1) {
                putExtra("stream_id", ch.streamId)
            } else {
                putExtra("stream_id", -1)
                putExtra("server_index", ch.serverIndex)
                putExtra("merged_stream_id", ch.streamId)
            }
        })
        finish()
    }

    /** League headers (String) and game rows (SportsFinder.Game). Rows are focusable for a remote. */
    private class SportsAdapter(
        private var items: List<Any>,
        private val accent: Int,
        private val onPick: (SportsFinder.Game) -> Unit,
        /** Hold OK (or long-press): Game View, every feed for the game. */
        private val onHold: (SportsFinder.Game) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private class Holder(view: View) : RecyclerView.ViewHolder(view)

        /** The score of each game that has started, from LiveScores. */
        var scores: Map<SportsFinder.Game, LiveScores.Event> = emptyMap()
        /** Airings on now that are most likely reruns (LiveScores.likelyReplay). */
        var replays: Set<SportsFinder.Game> = emptySet()

        fun itemAt(position: Int): Any? = items.getOrNull(position)

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun update(newItems: List<Any>) {
            items = newItems
            notifyDataSetChanged()
        }

        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        private fun isToday(ms: Long): Boolean {
            val a = java.util.Calendar.getInstance()
            val b = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is String) 0 else 1

        private fun dp(view: View, v: Int) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), view.resources.displayMetrics).toInt()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val condensed = ResourcesCompat.getFont(ctx, R.font.barlow_condensed_bold)
            val barlow = ResourcesCompat.getFont(ctx, R.font.barlow)
            if (viewType == 0) {
                return Holder(TextView(ctx).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    typeface = condensed
                    letterSpacing = 0.14f
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(ctx.getColor(R.color.rack_text_muted))
                    setPadding(dp(this, 20), dp(this, 18), dp(this, 20), dp(this, 6))
                })
            }
            val row = LinearLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.rack_row_bg)
                setPadding(dp(this, 20), dp(this, 12), dp(this, 20), dp(this, 12))
            }
            val time = TextView(ctx).apply {
                tag = "time"
                typeface = condensed
                letterSpacing = 0.08f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                minWidth = dp(this, 76)
            }
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(row, 12)
                }
            }
            val title = TextView(ctx).apply {
                tag = "title"
                typeface = barlow
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ctx.getColor(R.color.rack_text))
                maxLines = 2
            }
            val where = TextView(ctx).apply {
                tag = "where"
                typeface = barlow
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ctx.getColor(R.color.rack_text_secondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val score = TextView(ctx).apply {
                tag = "score"
                typeface = condensed
                letterSpacing = 0.04f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
            }
            texts.addView(title)
            texts.addView(score)
            texts.addView(where)
            row.addView(time)
            row.addView(texts)
            return Holder(row)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (item is String) {
                (holder.itemView as TextView).text = item.uppercase(Locale.getDefault())
                return
            }
            val game = item as SportsFinder.Game
            val v = holder.itemView
            val ctx = v.context
            val now = System.currentTimeMillis()
            val time = v.findViewWithTag<TextView>("time")
            if (game.isLive(now)) {
                time.text = "● LIVE"
                time.setTextColor(ctx.getColor(R.color.rack_danger_text))
            } else {
                time.text = timeFormat.format(Date(game.startMs)).let { t ->
                    if (isToday(game.startMs)) t else "Tmrw\n$t"
                }
                time.setTextColor(accent)
            }
            v.findViewWithTag<TextView>("title").text = game.title
            val score = scores[game]
            val replay = game in replays
            v.findViewWithTag<TextView>("score").apply {
                visibility = if (score == null && !replay) View.GONE else View.VISIBLE
                text = score?.let { (if (it.final) "Final: " else "") + it.label + if (it.close) "  ·  CLOSE GAME" else "" }
                    ?: "Likely a replay"
                setTextColor(when {
                    score?.close == true -> ctx.getColor(R.color.rack_danger_text)
                    score == null -> ctx.getColor(R.color.rack_text_muted)
                    else -> accent
                })
            }
            // Live rows sit under "On now", so they name their league; upcoming ones are under it.
            v.findViewWithTag<TextView>("where").text = (if (game.isLive(now)) "${game.league} · " else "") +
                game.channel.name + if (game.otherChannels > 0) "  (+${game.otherChannels} more, hold for all)" else ""
            v.setOnClickListener { onPick(game) }
            v.setOnLongClickListener { onHold(game); true }
        }
    }
}
