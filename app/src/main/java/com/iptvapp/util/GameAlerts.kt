package com.iptvapp.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import com.iptvapp.data.local.PreferencesManager
import com.iptvapp.ui.guide.ReminderTapActivity
import com.iptvapp.ui.home.HomeActivity
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Game alerts: when a game with one of the viewer's favorite teams (Settings > Favorite teams)
 * starts, a notification says so and Watch tunes to its best feed, the same feed the Sports
 * screen would open (preferred network, then feed score).
 *
 * Each game is scheduled as a one-off job at its start, from the guide (after every refresh, when
 * the teams change, when the Sports screen opens). At start the job looks the game up again, so
 * a game that moved or left the guide is not announced, and each game is announced once.
 */
object GameAlerts {
    private const val CHANNEL_ID = "game_alerts"
    private const val STATE_PREFS = "game_alerts_state"
    private const val KEY_SENT = "sent"
    private const val WORK_PREFIX = "game_alert_"
    // Everything the guide already holds, about a week: with guide refresh off (the default) a
    // shorter window would leave later games unscheduled until something else ran a scan.
    const val AHEAD_HOURS = 7 * 24
    /** A game already under way is still worth an alert this soon after its start. */
    const val LATE_MS = 15 * 60_000L

    data class Planned(val key: String, val title: String, val startMs: Long, val delayMs: Long)

    /** One game is one airing: its title and start. */
    fun keyOf(game: SportsFinder.Game): String = "${game.title.lowercase()}@${game.startMs}"

    /**
     * The games to schedule: one of [teams] is in the title, not already announced, starting in
     * the next [AHEAD_HOURS], or started no more than [LATE_MS] ago (announced at once).
     */
    fun plan(games: List<SportsFinder.Game>, teams: List<String>, sent: Set<String>, nowMs: Long): List<Planned> {
        if (teams.isEmpty()) return emptyList()
        val until = nowMs + AHEAD_HOURS * 3_600_000L
        return games.asSequence()
            .filter { g -> teams.any { SportsMode.titleHasTeam(g.title, it) } }
            .filter { it.startMs <= until && nowMs - it.startMs <= LATE_MS }
            .map { g -> Planned(keyOf(g), g.title, g.startMs, (g.startMs - nowMs).coerceAtLeast(0L)) }
            .filter { it.key !in sent }
            .distinctBy { it.key }
            .toList()
    }

    /** Schedules every planned game; with the setting off or no teams, cancels them all. Never throws. */
    suspend fun schedule(context: Context, db: IptvDatabase, prefs: PreferencesManager) {
        try {
            val app = context.applicationContext
            val wm = WorkManager.getInstance(app)
            val teams = SportsMode.parseTeams(prefs.favoriteTeams.first())
            if (!prefs.gameAlerts.first() || teams.isEmpty()) {
                wm.cancelAllWorkByTag(WORK_PREFIX)
                return
            }
            val preferred = SportsFinder.decodePreferred(prefs.sportsPreferredNetworks.first())
            val games = SportsFinder.find(db, AHEAD_HOURS, preferred)
            val now = System.currentTimeMillis()
            for (p in plan(games, teams, sent(app), now)) {
                val request = OneTimeWorkRequestBuilder<com.iptvapp.worker.GameAlertWorker>()
                    .setInitialDelay(p.delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_GAME to p.key, KEY_TITLE to p.title, KEY_START to p.startMs))
                    .addTag(WORK_PREFIX)
                    .build()
                // REPLACE: a refresh that moved the start time reschedules it.
                wm.enqueueUniqueWork(WORK_PREFIX + p.key.hashCode(), ExistingWorkPolicy.REPLACE, request)
            }
        } catch (e: Exception) {
            android.util.Log.w("GameAlerts", "schedule failed: ${e.message}")
        }
    }

    const val KEY_GAME = "game"
    const val KEY_TITLE = "title"
    const val KEY_START = "start"

    /** At the game's start: the game as the guide has it now, announced once. */
    suspend fun announce(context: Context, db: IptvDatabase, prefs: PreferencesManager, key: String) {
        val app = context.applicationContext
        if (!prefs.gameAlerts.first() || key in sent(app)) return
        if (!canNotify(app)) return
        val preferred = SportsFinder.decodePreferred(prefs.sportsPreferredNetworks.first())
        val game = SportsFinder.find(db, AHEAD_HOURS, preferred).firstOrNull { keyOf(it) == key } ?: return
        // A team removed since this was scheduled no longer gets alerts.
        val teams = SportsMode.parseTeams(prefs.favoriteTeams.first())
        if (teams.none { SportsMode.titleHasTeam(game.title, it) }) return
        val now = System.currentTimeMillis()
        // Android can run a background job late on an idle device. While the game is on, it is
        // still worth telling; once it has ended, Watch would tune to something else.
        if (game.stopMs <= now) return
        post(app, game, now)
        markSent(app, key, now)
    }

    fun canNotify(context: Context): Boolean {
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm)
            if (nm.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    private fun post(context: Context, game: SportsFinder.Game, nowMs: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val feed = game.channel
        val requestCode = keyOf(game).hashCode()
        // The same tune path as Show Alerts' Watch.
        val watch = Intent(context, ReminderTapActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("mktv://tune/${feed.streamId}?server=${feed.serverIndex}&t=$requestCode")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(HomeActivity.EXTRA_JUMP_TO_STREAM_ID, feed.streamId)
            putExtra(HomeActivity.EXTRA_JUMP_SERVER_INDEX, feed.serverIndex)
        }
        val watchPi = PendingIntent.getActivity(
            context, requestCode, watch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = (if (nowMs - game.startMs > LATE_MS) "On now on " else "Starting now on ") + feed.name
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(game.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${game.league}"))
            .setContentIntent(watchPi)
            .addAction(0, "Watch", watchPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            // Gone when the game ends: Watch would tune to whatever is on by then.
            .setTimeoutAfter((game.stopMs - System.currentTimeMillis()).coerceAtLeast(1_000L))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(requestCode, notification)
    }

    private fun sent(context: Context): Set<String> =
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SENT, emptySet()).orEmpty()

    /** Remembers [key]; games that started over two days ago are forgotten so the list stays small. */
    @Synchronized
    private fun markSent(context: Context, key: String, nowMs: Long) {
        val sp = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val kept = sp.getStringSet(KEY_SENT, emptySet()).orEmpty().filter {
            val start = it.substringAfterLast('@').toLongOrNull() ?: return@filter false
            nowMs - start < 48 * 3_600_000L
        }.toSet()
        sp.edit().putStringSet(KEY_SENT, kept + key).commit()
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Game alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a game with one of your favorite teams starts"
                }
            )
        }
    }
}
