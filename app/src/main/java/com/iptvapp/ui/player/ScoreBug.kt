package com.iptvapp.ui.player

import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.util.LiveScores
import com.iptvapp.util.SportsFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live scores in the player (v7.75): the score of the game on the channel being watched, in the
 * bottom-left corner, and a short heads-up when another game is close late (LiveScores.close),
 * once per game, naming a channel that has it. Live channels only; off with Settings > Live scores.
 */
class ScoreBug(
    private val activity: AppCompatActivity,
    root: ConstraintLayout,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager,
) {
    /** The live channel playing: serverIndex -1 is the primary provider. */
    data class Channel(val serverIndex: Int, val streamId: Int, val name: String)

    private val density = activity.resources.displayMetrics.density
    private val box = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        isFocusable = false
    }
    private val alert = label(15f).apply { visibility = View.GONE; maxLines = 2; maxWidth = (420 * density).toInt() }
    private val score = label(18f).apply { visibility = View.GONE }
    private val alerted = HashSet<String>()
    private val poke = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val hideAlert = Runnable { alert.visibility = View.GONE; syncBox() }

    init {
        box.addView(alert)
        box.addView(score)
        (alert.layoutParams as LinearLayout.LayoutParams).bottomMargin = (8 * density).toInt()
        root.addView(box, ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            marginStart = (24 * density).toInt()
            bottomMargin = (24 * density).toInt()
        })
    }

    private fun label(sp: Float) = TextView(activity).apply {
        typeface = ResourcesCompat.getFont(activity, R.font.barlow_condensed_bold)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        letterSpacing = 0.04f
        setTextColor(activity.getColor(R.color.rack_text))
        setBackgroundResource(R.drawable.player_osd_card_bg)
        val h = (14 * density).toInt(); val v = (8 * density).toInt()
        setPadding(h, v, h, v)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun syncBox() {
        box.visibility = if (score.visibility == View.VISIBLE || alert.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    /** Runs while the player is on screen; [current] is the live channel, or null for a movie or episode. */
    fun start(current: () -> Channel?) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                merge(flow { while (true) { emit(Unit); delay(60_000L) } }, poke).collectLatest {
                    try { update(current()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
                }
            }
        }
    }

    /** The channel changed: drop the old score now and look again. */
    fun channelChanged() {
        score.visibility = View.GONE
        syncBox()
        // A beat later: the selection is shown before the player has switched to the channel.
        box.removeCallbacks(lookAgain)
        box.postDelayed(lookAgain, 3_000L)
    }

    private val lookAgain = Runnable { poke.tryEmit(Unit) }

    private suspend fun update(channel: Channel?) {
        if (channel == null || channel.streamId < 0 || !prefs.liveScores.first()) {
            score.visibility = View.GONE; alert.visibility = View.GONE; syncBox(); return
        }
        val nowMs = System.currentTimeMillis()
        val (onNow, events) = withContext(Dispatchers.IO) {
            val guide = db.epgDao().getEpgForStream(channel.streamId, channel.serverIndex).first()
            guide.firstOrNull { ms(it.startTimestamp) <= nowMs && ms(it.stopTimestamp) > nowMs } to LiveScores.events()
        }
        val teams = LiveScores.teams(events)
        val league = onNow?.let { SportsFinder.leagueOf(it.title, teams) }
        val watching = if (onNow == null || league == null) null
            else LiveScores.match(league, onNow.title, ms(onNow.startTimestamp), listOf(channel.name), events)?.takeIf { it.state != "pre" }
        score.text = watching?.let { (if (it.final) "Final: " else "") + it.label }
        score.visibility = if (watching == null) View.GONE else View.VISIBLE
        val next = events.firstOrNull { it.close && key(it) !in alerted && (watching == null || key(it) != key(watching)) }
        if (next != null) {
            alerted += key(next)
            val where = withContext(Dispatchers.IO) {
                SportsFinder.find(db, aheadHours = 1, teams = teams).firstOrNull { LiveScores.match(it, events)?.let(::key) == key(next) }
            }
            alert.text = "Close game · ${next.label}" + (where?.let { "\nOn ${it.channel.network}" } ?: "")
            alert.setTextColor(activity.getColor(R.color.rack_danger_text))
            alert.visibility = View.VISIBLE
            alert.removeCallbacks(hideAlert)
            alert.postDelayed(hideAlert, 12_000L)
        }
        syncBox()
    }

    private fun key(e: LiveScores.Event) = "${e.league}|${e.startMs}|${e.away.abbr}|${e.home.abbr}"

    private fun ms(t: Long) = if (t < 100_000_000_000L) t * 1000L else t
}
