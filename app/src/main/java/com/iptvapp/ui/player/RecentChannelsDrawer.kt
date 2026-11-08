package com.iptvapp.ui.player

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.data.local.entities.ChannelEntity
import com.iptvapp.data.local.entities.MergedChannelEntity
import com.iptvapp.data.repository.XtreamRepository
import com.iptvapp.databinding.ActivityPlayerBinding
import com.iptvapp.databinding.ItemRecentChannelCardBinding
import com.iptvapp.util.QuickPeek
import com.iptvapp.util.RecentChannel
import com.iptvapp.util.RecentChannels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recent Channels, the quick-switcher drawer over live TV (layout: layout_recent_channels_drawer,
 * list: util/RecentChannels). It shows the last channels watched, newest first, without the one
 * on now. Everything it reads is on the device (the saved list, Room, the stored guide), and it
 * never touches the player: the picture keeps playing until a card is picked, which goes to the
 * player through [Host.tune].
 */
class RecentChannelsDrawer(
    private val activity: AppCompatActivity,
    private val b: ActivityPlayerBinding,
    private val repository: XtreamRepository,
    private val db: IptvDatabase,
    private val prefs: PreferencesManager,
    private val host: Host,
) {
    interface Host {
        /** (serverIndex, streamId) of the channel on now, or null when the drawer doesn't apply. */
        fun playing(): Pair<Int, Int>?
        /** Plays [primary] or [merged] through the player's normal channel change. */
        fun tune(primary: ChannelEntity?, merged: MergedChannelEntity?)
        /** The drawer is about to show: the player's controls and Quick Peek step aside. */
        fun onOpening()
    }

    /** One card: the saved entry, what it plays now in Room, and the show on now (null = none in the guide). */
    private class Card(val recent: RecentChannel, val primary: ChannelEntity?, val merged: MergedChannelEntity?, val now: String?)

    private val panel get() = b.recentDrawer.root
    private val list get() = b.recentDrawer.rvRecentChannels
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = CardAdapter()
    private var loadJob: Job? = null
    private val idleClose = Runnable { close() }

    /** True from the moment it is asked to open (while it loads) until it closes. */
    var isOpen = false
        private set

    init {
        list.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        list.adapter = adapter
        list.itemAnimator = null
    }

    fun toggle() = if (isOpen) close() else open()

    fun open() {
        if (isOpen) return
        val playing = host.playing() ?: return
        isOpen = true
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            val cards = try { withContext(Dispatchers.IO) { load(playing) } } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
            if (!isOpen) return@launch
            if (cards.isEmpty()) {
                isOpen = false
                Toast.makeText(activity, R.string.recent_channels_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            host.onOpening()
            adapter.submit(cards)
            panel.animate().cancel()
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.post {
                if (!isOpen) return@post
                panel.translationY = panel.height.toFloat().coerceAtLeast(1f)
                panel.animate().alpha(1f).translationY(0f).setDuration(ANIM_MS).start()
                list.scrollToPosition(0)
                list.post { if (isOpen) list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
            keepAlive()
        }
    }

    /** Slides it away. Nothing is tuned; the picture never stopped. */
    fun close(animate: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        loadJob?.cancel()
        handler.removeCallbacks(idleClose)
        if (panel.hasFocus()) panel.findFocus()?.clearFocus()
        panel.animate().cancel()
        if (animate && panel.visibility == View.VISIBLE) {
            panel.animate().alpha(0f).translationY(panel.height.toFloat()).setDuration(ANIM_MS).withEndAction {
                if (!isOpen) panel.visibility = View.GONE
            }.start()
        } else {
            panel.visibility = View.GONE
        }
    }

    /** onStop, Lock, PiP: closes without waiting on an animation. */
    fun stop() = close(animate = false)

    /** Keys while it is open. False leaves the key to the player (CH +/-, volume, media). */
    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isOpen) return false
        keepAlive()
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_DOWN -> { close(); true }
            KeyEvent.KEYCODE_DPAD_UP -> true
            KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }
            // A focused card takes OK itself (its click); this is OK with no card focused yet.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if ((event?.repeatCount ?: 0) == 0) adapter.cards.getOrNull(focusedIndex().coerceAtLeast(0))?.let { pick(it) }
                true
            }
            else -> false
        }
    }

    /** A touch outside the drawer closes it. True when it did (the touch then goes no further). */
    fun touchOutside(rawX: Float, rawY: Float): Boolean {
        if (!isOpen) return false
        val r = android.graphics.Rect()
        if (panel.visibility == View.VISIBLE && panel.getGlobalVisibleRect(r) && r.contains(rawX.toInt(), rawY.toInt())) {
            keepAlive()
            return false
        }
        close()
        return true
    }

    private fun focusedIndex(): Int {
        val f = list.focusedChild ?: return -1
        return list.getChildAdapterPosition(f)
    }

    /** Left / Right between the cards; at either end focus stays put rather than leaving the drawer. */
    private fun step(delta: Int) {
        val count = adapter.itemCount
        if (count == 0) return
        val from = focusedIndex()
        val to = if (from < 0) 0 else (from + delta).coerceIn(0, count - 1)
        val holder = list.findViewHolderForAdapterPosition(to)
        if (holder != null) holder.itemView.requestFocus()
        else {
            list.scrollToPosition(to)
            list.post { if (isOpen) list.findViewHolderForAdapterPosition(to)?.itemView?.requestFocus() }
        }
    }

    private fun pick(card: Card) {
        if (!isOpen) return
        close()
        host.tune(card.primary, card.merged)
    }

    private fun keepAlive() {
        handler.removeCallbacks(idleClose)
        if (isOpen) handler.postDelayed(idleClose, IDLE_CLOSE_MS)
    }

    // ── Loading (all on the device) ────────────────────────────────────

    private suspend fun load(playing: Pair<Int, Int>): List<Card> {
        val saved = prefs.getRecentChannels()
        val candidates = RecentChannels.available(saved, playing.first, playing.second, max = RecentChannels.CAPACITY)
        val found = ArrayList<Pair<RecentChannel, Any>>()
        val gone = ArrayList<Pair<Int, Int>>()
        for (r in candidates) {
            val entity: Any? = if (r.serverIndex == -1) {
                // Main-provider channels play from the main list only (the player's CH +/- and LAST
                // follow those); one still in the Providers list (slot -1) is kept but not shown.
                repository.getChannelById(r.streamId)
                    ?: if (repository.getMergedChannelByIndexAndId(-1, r.streamId) != null) continue else null
            } else repository.getMergedChannelByIndexAndId(r.serverIndex, r.streamId)
            if (entity == null) gone += r.serverIndex to r.streamId else found += r to entity
        }
        // The provider dropped these since: they leave the list for good.
        if (gone.isNotEmpty()) prefs.removeRecentChannels(gone)
        val shown = found.take(RecentChannels.SHOWN)
        val titles = nowTitles(shown.map { it.first })
        return shown.map { (r, entity) ->
            val primary = entity as? ChannelEntity
            val merged = entity as? MergedChannelEntity
            // Names and logos as the provider has them now; the saved copy is only the fallback.
            val fresh = r.copy(
                name = primary?.name ?: merged?.name ?: r.name,
                iconUrl = (primary?.streamIcon ?: merged?.streamIcon)?.takeIf { it.isNotBlank() } ?: r.iconUrl,
                serverNickname = if (r.serverIndex >= 0) merged?.serverNickname ?: r.serverNickname else null,
            )
            Card(fresh, primary, merged, titles[r.serverIndex to r.streamId])
        }
    }

    /** The show on now for each card, from the stored guide; one read per provider. */
    private suspend fun nowTitles(recents: List<RecentChannel>): Map<Pair<Int, Int>, String> {
        val now = System.currentTimeMillis()
        val out = HashMap<Pair<Int, Int>, String>()
        recents.groupBy { it.serverIndex }.forEach { (server, group) ->
            val programs = try { db.epgDao().getProgramsBetween(group.map { it.streamId }, server, now, now + 1) } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
            programs.filter { QuickPeek.ms(it.startTimestamp) <= now && QuickPeek.ms(it.stopTimestamp) > now }
                .forEach { p -> if (p.title.isNotBlank()) out.putIfAbsent(server to p.streamId, p.title) }
        }
        return out
    }

    // ── Cards ──────────────────────────────────────────────────────────

    private inner class CardAdapter : RecyclerView.Adapter<CardAdapter.Holder>() {
        var cards: List<Card> = emptyList()
            private set

        fun submit(list: List<Card>) {
            cards = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        inner class Holder(val v: ItemRecentChannelCardBinding) : RecyclerView.ViewHolder(v.root)

        override fun getItemCount() = cards.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = ItemRecentChannelCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val density = parent.resources.displayMetrics.density
            v.root.setOnFocusChangeListener { view, focused ->
                // Focused: bigger, lifted and brighter; at rest: flat and muted.
                view.animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(120).start()
                view.elevation = if (focused) 6 * density else 0f
                v.tvRecentName.setTextColor(if (focused) 0xFFFFFFFF.toInt() else 0xFF9FB0CC.toInt())
                v.tvRecentNow.setTextColor(if (focused) 0xFFC9D6EA.toInt() else 0xFF7F90AD.toInt())
                if (focused) keepAlive()
            }
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val card = cards[position]
            val r = card.recent
            val v = holder.v
            v.tvRecentName.text = r.name
            v.tvRecentInitials.text = RecentChannels.initials(r.name)
            val now = card.now ?: activity.getString(R.string.recent_channels_live)
            v.tvRecentNow.text = now
            val nick = r.serverNickname?.takeIf { r.serverIndex >= 0 && it.isNotBlank() }
            v.tvRecentProvider.text = nick.orEmpty()
            v.tvRecentProvider.visibility = if (nick != null) View.VISIBLE else View.GONE
            v.root.contentDescription = activity.getString(R.string.recent_channels_card_description, r.name, now)
            // The initials show until a logo arrives, and stay when there is none or it fails.
            v.tvRecentInitials.visibility = View.VISIBLE
            Glide.with(v.ivRecentLogo).clear(v.ivRecentLogo)
            v.ivRecentLogo.setImageDrawable(null)
            if (!r.iconUrl.isNullOrBlank()) {
                Glide.with(v.ivRecentLogo).load(r.iconUrl)
                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                        override fun onLoadFailed(
                            e: com.bumptech.glide.load.engine.GlideException?, model: Any?,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean,
                        ) = false
                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable, model: Any,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                            dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean,
                        ): Boolean {
                            v.tvRecentInitials.visibility = View.INVISIBLE
                            return false
                        }
                    })
                    .into(v.ivRecentLogo)
            }
            v.root.setOnClickListener { pick(card) }
        }
    }

    companion object {
        private const val IDLE_CLOSE_MS = 6_000L
        private const val ANIM_MS = 180L
    }
}
