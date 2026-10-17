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

    private lateinit var binding: ActivitySportsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySportsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Edge-to-edge (target SDK 35): keep the header and list clear of the status bar, cutout
        // and navigation bar.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnSportsBack.setOnClickListener { finish() }
        binding.rvSports.layoutManager = LinearLayoutManager(this)
        lifecycleScope.launch {
            val accent = com.iptvapp.util.RackAccent.load(prefs)
            binding.tvSportsTitle.setTextColor(accent.start)
            // Games start and end while this is open: look again every minute while it's on screen
            // (and on coming back to it), updating only when the list actually changed.
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // A new preferred network reshuffles the feeds at once, not at the next minute.
                kotlinx.coroutines.flow.merge(
                    prefs.sportsPreferredNetworks,
                    kotlinx.coroutines.flow.flow { while (true) { emit(""); kotlinx.coroutines.delay(60_000L) } },
                ).collectLatest {
                    val preferred = SportsFinder.decodePreferred(prefs.sportsPreferredNetworks.first())
                    show(withContext(Dispatchers.IO) { SportsFinder.find(db, preferredNetworks = preferred) }, accent.start)
                }
            }
        }
    }

    private var shownSignature: String? = null
    private var shownTeams: String? = null
    private var latestGames: List<SportsFinder.Game> = emptyList()

    private fun show(games: List<SportsFinder.Game>, accent: Int) {
        latestGames = games
        lifecycleScope.launch { bindTeamShortcuts(prefs.favoriteTeams.first(), accent) }
        val items = buildItems(games)
        val now = System.currentTimeMillis()
        // What's on screen depends on these: which rows, in which section, live or not — and the
        // date, which decides the "Tmrw" labels.
        val signature = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now)) + "|" + items.joinToString("|") {
            if (it is SportsFinder.Game) "${it.title}@${it.startMs}:${it.channel.serverIndex}:${it.channel.streamId}:${it.isLive(now)}" else it.toString()
        }
        if (signature == shownSignature) return
        shownSignature = signature
        if (games.isEmpty()) {
            binding.rvSports.visibility = View.GONE
            binding.tvSportsEmpty.visibility = View.VISIBLE
            binding.tvSportsEmpty.text =
                "No games found in the guide right now. If the guide is empty or old, refresh it from the Guide tab and look again."
            return
        }
        binding.tvSportsEmpty.visibility = View.GONE
        binding.rvSports.visibility = View.VISIBLE
        val existing = binding.rvSports.adapter as? SportsAdapter
        if (existing == null) {
            binding.rvSports.adapter = SportsAdapter(items, accent, onPick = { pick(it) }, onHold = { gameView(it) })
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

    /** "On now" first (every league, so what you can watch right away is at the top), then what's
     * coming up, grouped by league. */
    private fun buildItems(games: List<SportsFinder.Game>): List<Any> {
        val items = mutableListOf<Any>()
        val now = System.currentTimeMillis()
        val live = games.filter { it.isLive(now) }
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
        val live = latestGames.filter { it.isLive(now) }
        val mosaicAt = if (live.size >= 2) lines.size.also { lines += "Watch ${minOf(live.size, 6)} live games in Mosaic" } else -1
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(game.title)
            .setItems(lines.toTypedArray()) { _, which ->
                when (which) {
                    in feeds.indices -> pick(game, feeds[which])
                    preferAt -> choosePreferredNetwork(game)
                    mosaicAt -> openInMosaic(game, live)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

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
     * The live games in a Mosaic grid, this one in the first tile, each on its best feed. Opens
     * as a one-off grid: the viewer's saved layouts and their last layout are left as they were.
     */
    private fun openInMosaic(first: SportsFinder.Game, live: List<SportsFinder.Game>) {
        val games = (listOf(first) + live.filter { it !== first }).take(6)
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

    private fun pick(game: SportsFinder.Game, feed: SportsFinder.Channel = game.channel) {
        val ch = feed
        setResult(RESULT_OK, Intent().apply {
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
            texts.addView(title)
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
            // Live rows sit under "On now", so they name their league; upcoming ones are under it.
            v.findViewWithTag<TextView>("where").text = (if (game.isLive(now)) "${game.league} · " else "") +
                game.channel.name + if (game.otherChannels > 0) "  (+${game.otherChannels} more, hold for all)" else ""
            v.setOnClickListener { onPick(game) }
            v.setOnLongClickListener { onHold(game); true }
        }
    }
}
