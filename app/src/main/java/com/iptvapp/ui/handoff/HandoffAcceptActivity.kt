package com.iptvapp.ui.handoff

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptvapp.handoff.ContentKind
import com.iptvapp.handoff.Handoff
import com.iptvapp.handoff.HandoffText
import com.iptvapp.handoff.Incoming
import com.iptvapp.handoff.InState
import com.iptvapp.handoff.Resolution
import com.iptvapp.handoff.StartPlan
import com.iptvapp.handoff.Target
import com.iptvapp.ui.player.PlayerActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Continue watching from Pixel?" — the receiver's answer to an incoming handoff. Shown over whatever
 * was on screen (it doesn't touch it until the person accepts). Play here, choose among several
 * matches, map the sender's provider to one of this device's, or decline. Back declines.
 */
class HandoffAcceptActivity : AppCompatActivity() {
    private var dialog: AlertDialog? = null
    private var txId: String = ""
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        txId = intent.getStringExtra(EXTRA_TX).orEmpty()
        lifecycleScope.launch {
            Handoff.dismissPrompts.collect { if (it == txId) { answered = true; finish() } }
        }
        show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        txId = intent.getStringExtra(EXTRA_TX).orEmpty()
        show()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        // Leaving without an answer (Back, Home) is a decline, so the sender isn't left waiting.
        if (!answered && !isChangingConfigurations) Handoff.receiver(this).decline(txId)
        super.onDestroy()
    }

    private fun incoming(): Incoming? = Handoff.receiver(this).get(txId)?.takeIf { it.state == InState.AWAITING_APPROVAL }

    private fun show() {
        dialog?.dismiss()
        val inc = incoming()
        if (inc == null) {
            Toast.makeText(this, "That request has expired", Toast.LENGTH_SHORT).show()
            answered = true
            finish()
            return
        }
        val here = if (Handoff.isTv(this)) "this TV" else "this phone"
        val replace = if (Handoff.playerActive) "\n\nThis replaces what's playing here." else ""
        val title = "Continue watching from ${inc.peerName}?"
        val b = AlertDialog.Builder(this).setTitle(title).setOnCancelListener { decline() }
        when (val r = inc.resolution) {
            is Resolution.Playable -> {
                b.setMessage(describe(inc, r.target) + replace)
                    .setPositiveButton("Play on $here") { _, _ -> accept(null) }
                    .setNegativeButton("Decline") { _, _ -> decline() }
            }
            is Resolution.Choose -> {
                b.setTitle("Which one? (from ${inc.peerName})")
                    .setItems(r.options.map { it.display }.toTypedArray()) { _, i -> accept(r.options[i]) }
                    .setNegativeButton("Decline") { _, _ -> decline() }
            }
            is Resolution.ProviderMissing -> {
                // The sender's provider isn't one of this device's. The person may say which one is
                // the same service (remembered for that device); nothing is assumed.
                val names = r.local.map { it.label }.toTypedArray()
                b.setTitle("${inc.peerName} uses \"${r.senderLabel.ifBlank { "another provider" }}\". Which provider here is the same?")
                    .setItems(names) { _, i -> mapProvider(inc, r.local[i].serverIndex) }
                    .setNegativeButton("Decline") { _, _ -> decline() }
            }
            is Resolution.NotFound, is Resolution.Unavailable -> {
                answered = true
                finish()
                return
            }
        }
        dialog = b.show().also { it.setOnDismissListener { if (!isFinishing && dialog === it) finishIfIdle() } }
    }

    private fun finishIfIdle() {
        // After an answer the activity is finishing by itself (an accepted launch runs elsewhere).
        if (answered) return
        if (incoming() == null) { answered = true; finish() }
    }

    private fun describe(inc: Incoming, t: Target): String {
        val d = inc.descriptor
        return when (t.kind) {
            ContentKind.LIVE -> "Live channel: ${t.title}\nStarts at the live picture."
            ContentKind.EPISODE -> "${t.display}\nFrom ${HandoffText.clock(d.positionMs)}"
            ContentKind.MOVIE -> "Movie: ${t.title}\nFrom ${HandoffText.clock(d.positionMs)}"
        }
    }

    private fun decline() {
        if (answered) return
        answered = true
        Handoff.receiver(this).decline(txId)
        finish()
    }

    private fun accept(choice: Target?) {
        if (answered) return
        val plan = Handoff.receiver(this).accept(txId, choice)
        answered = true
        if (plan == null) {
            Toast.makeText(this, "That request has expired", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // In the process scope, not this screen's: closing the old player and fetching the
        // address may take a moment, and this prompt goes away now.
        val app = applicationContext
        Handoff.runOnMain { HandoffLauncher.open(app, plan) }
        finish()
    }

    private fun mapProvider(inc: Incoming, slot: Int) {
        val reg = Handoff.registry(this)
        reg.update(inc.peerId) { it.copy(providerMap = it.providerMap + (inc.descriptor.providerTag to slot)) }
        val peer = reg.byId(inc.peerId) ?: return
        lifecycleScope.launch {
            Handoff.receiver(this@HandoffAcceptActivity).remap(peer, txId)
            show()
        }
    }

    companion object {
        const val EXTRA_TX = "handoff_tx"
    }
}

/** Opens an accepted handoff in this device's own player, the way its own screens open things. */
object HandoffLauncher {
    suspend fun open(context: Context, plan: StartPlan) {
        val app = context.applicationContext
        val rx = Handoff.receiver(app)
        // The new playback takes over: close any player first and give the provider a moment to
        // free the connection (single-connection accounts), as the home screen does.
        if (Handoff.playerActive) {
            Handoff.requestClosePlayers("*")
            var waited = 0
            while (Handoff.playerActive && waited < 3_000) { delay(100); waited += 100 }
            delay(1_000)
        }
        fun stillWanted() = rx.get(plan.txId)?.state == com.iptvapp.handoff.InState.PREPARING
        if (!stillWanted()) return
        val repo = Handoff.deps(app).repository()
        val t = plan.target
        val d = plan.descriptor
        val url = try {
            when (t.kind) {
                ContentKind.MOVIE -> if (t.serverIndex == -1) repo.getVodStreamUrl(t.streamId, t.ext) else repo.getMergedVodStreamUrl(t.serverIndex, t.streamId, t.ext)
                ContentKind.LIVE -> if (t.serverIndex == -1) repo.getLiveStreamUrl(t.streamId) else repo.getMergedLiveStreamUrl(t.serverIndex, t.streamId)
                ContentKind.EPISODE -> {
                    val ep = t.episode!!
                    if (t.serverIndex == -1) repo.getSeriesEpisodeUrl(ep.id, ep.ext) else repo.getMergedSeriesEpisodeUrl(t.serverIndex, ep.id, ep.ext)
                }
            }
        } catch (_: Exception) {
            null
        }
        if (url.isNullOrBlank()) {
            rx.onPlayerFailed(plan.txId, "Couldn't open it on this device")
            return
        }
        val intent = Intent(app, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            putExtra("stream_url", url)
            putExtra(HandoffAcceptActivity.EXTRA_TX, plan.txId)
            d.speed?.let { putExtra("handoff_speed", it) }
            d.audioLang?.let { putExtra("handoff_audio_lang", it) }
            d.subtitleLang?.let { putExtra("handoff_sub_lang", it) }
            d.subtitlesOn?.let { putExtra("handoff_subs", it) }
            val streamIdExtra = if (t.serverIndex == -1) t.streamId else -1
            when (t.kind) {
                ContentKind.MOVIE -> {
                    putExtra("stream_title", t.title)
                    putExtra("stream_id", streamIdExtra)
                    putExtra("is_vod", true)
                    putExtra("server_index", t.serverIndex)
                    putExtra("merged_stream_id", if (t.serverIndex == -1) -1 else t.streamId)
                    putExtra("resume_ms", plan.startPositionMs)
                }
                ContentKind.LIVE -> {
                    putExtra("stream_title", t.title)
                    putExtra("stream_id", streamIdExtra)
                    putExtra("is_vod", false)
                    putExtra("server_index", t.serverIndex)
                    putExtra("merged_stream_id", if (t.serverIndex == -1) -1 else t.streamId)
                }
                ContentKind.EPISODE -> {
                    val ep = t.episode!!
                    val season = t.seasonEpisodes
                    putExtra("stream_title", "S${ep.season}E${ep.episode} ${ep.title}")
                    putExtra("stream_id", ep.id.hashCode())
                    putExtra("is_vod", true)
                    // As the series screen does: watched rows only for the main provider's series.
                    putExtra("series_id", if (t.serverIndex == -1) t.seriesId else -1)
                    putExtra("series_name", t.seriesTitle)
                    putExtra("season_num", ep.season)
                    putExtra("episode_num", ep.episode)
                    putExtra("ep_index", season.indexOfFirst { it.id == ep.id })
                    putStringArrayListExtra("ep_ids", ArrayList(season.map { it.id }))
                    putStringArrayListExtra("ep_titles", ArrayList(season.map { "S${it.season}E${it.episode} ${it.title}" }))
                    putStringArrayListExtra("ep_exts", ArrayList(season.map { it.ext }))
                    putExtra("sync_server_index", t.serverIndex)
                    putExtra("sync_episode_id", ep.id)
                    putExtra("handoff_series_id", t.seriesId)
                    putExtra("resume_ms", plan.startPositionMs)
                }
            }
        }
        // The sender may have cancelled while the old player closed or the address was fetched.
        if (!stillWanted()) return
        try {
            app.startActivity(intent)
        } catch (_: Exception) {
            rx.onPlayerFailed(plan.txId, "Couldn't open the player")
        }
    }
}
