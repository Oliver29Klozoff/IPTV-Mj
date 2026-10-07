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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
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
                while (true) {
                    show(withContext(Dispatchers.IO) { SportsFinder.find(db) }, accent.start)
                    kotlinx.coroutines.delay(60_000L)
                }
            }
        }
    }

    private var shownSignature: String? = null

    private fun show(games: List<SportsFinder.Game>, accent: Int) {
        val items = buildItems(games)
        val now = System.currentTimeMillis()
        // What's on screen depends on these: which rows, in which section, live or not.
        val signature = items.joinToString("|") {
            if (it is SportsFinder.Game) "${it.title}@${it.startMs}:${it.channel.streamId}:${it.isLive(now)}" else it.toString()
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
        val first = binding.rvSports.adapter == null
        // Keep the remote's place across an update.
        val focusedPos = binding.rvSports.focusedChild?.let { binding.rvSports.getChildAdapterPosition(it) } ?: -1
        binding.rvSports.adapter = SportsAdapter(items, accent) { pick(it) }
        val target = if (first) 1 else focusedPos.coerceAtMost(items.size - 1)
        if (target >= 0) binding.rvSports.post {
            (binding.rvSports.findViewHolderForAdapterPosition(target)?.itemView
                ?: binding.rvSports.getChildAt(1))?.requestFocus()
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

    private fun pick(game: SportsFinder.Game) {
        val ch = game.channel
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
        private val items: List<Any>,
        private val accent: Int,
        private val onPick: (SportsFinder.Game) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private class Holder(view: View) : RecyclerView.ViewHolder(view)

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
                game.channel.name + if (game.otherChannels > 0) "  (+${game.otherChannels} more)" else ""
            v.setOnClickListener { onPick(game) }
        }
    }
}
