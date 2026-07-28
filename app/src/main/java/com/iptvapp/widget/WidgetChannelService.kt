package com.iptvapp.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.room.Room
import com.iptvapp.R
import com.iptvapp.data.local.IptvDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class WidgetChannelService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return ChannelWidgetFactory(applicationContext, widgetId)
    }
}

class ChannelWidgetFactory(
    private val context: Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    // A single row shape covers both modes: a live channel (name + now-playing EPG title) and a
    // Continue Watching item (title + progress). vodStreamId/seriesId/etc. are only populated for
    // the mode that produced this row; the unused ones just stay at their default.
    private data class WidgetRow(
        val streamId: Int = 0,
        val name: String = "",
        val subtitle: String = "",
        val isVod: Boolean = false,
        val containerExtension: String = "",
        val seriesId: Int = -1,
        val seriesName: String = ""
    )

    private var rows: List<WidgetRow> = emptyList()

    override fun onCreate() { load() }
    override fun onDataSetChanged() { load() }
    override fun onDestroy() {}

    private fun load() {
        runBlocking(Dispatchers.IO) {
            // Was a hand-typed, independent copy of the migration list that silently fell behind
            // the main app's (stuck at MIGRATION_17_18 while the app was on MIGRATION_24_25) —
            // now shares the single source of truth so it can never drift again. See
            // IptvDatabase.ALL_MIGRATIONS kdoc.
            val db = Room.databaseBuilder(context, IptvDatabase::class.java, IptvDatabase.DATABASE_NAME)
                .addMigrations(*IptvDatabase.ALL_MIGRATIONS)
                .build()

            rows = when (WidgetPrefs.getMode(context, widgetId)) {
                WidgetPrefs.MODE_CONTINUE_WATCHING -> {
                    val vodRows = db.vodDao().getInProgressVod().first().map { v ->
                        val pct = if (v.durationMs > 0) (v.watchedMs * 100 / v.durationMs).toInt() else 0
                        WidgetRow(
                            streamId = v.streamId,
                            name = v.name,
                            subtitle = "$pct% watched",
                            isVod = true,
                            containerExtension = v.containerExtension
                        )
                    }
                    val seriesRows = db.seriesDao().getInProgressSeries().first().map { row ->
                        WidgetRow(
                            name = row.series.name,
                            subtitle = "S${row.lastSeason}E${row.lastEpisode}",
                            isVod = false,
                            seriesId = row.series.seriesId,
                            seriesName = row.series.name
                        )
                    }
                    (vodRows + seriesRows).take(10)
                }
                else -> {
                    val channels = db.channelDao().getFavoriteChannelsBlocking()
                    // Only present once the user has actually gone through Configure (WidgetPrefs
                    // returns null otherwise) — falls back to the original "first 10 favorites"
                    // behavior for widgets added before this feature existed, or left unconfigured.
                    val selectedIds = WidgetPrefs.getSelectedStreamIds(context, widgetId)
                    val filteredChannels = if (selectedIds != null) {
                        channels.filter { it.streamId in selectedIds }
                    } else {
                        channels.take(10)
                    }
                    val nowSec = System.currentTimeMillis() / 1000  // EPG timestamps are in seconds
                    filteredChannels.map { ch ->
                        val epg = db.epgDao().getCurrentProgramForWidget(ch.streamId, nowSec)
                        WidgetRow(streamId = ch.streamId, name = ch.name, subtitle = epg?.title ?: "")
                    }
                }
            }
            db.close()
        }
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_channel_row)
        return RemoteViews(context.packageName, R.layout.widget_channel_row).apply {
            setTextViewText(R.id.tvWidgetChannelName, row.name)
            setTextViewText(R.id.tvWidgetEpgNow, row.subtitle)
            val fillIn = Intent().apply {
                when {
                    row.seriesId != -1 -> {
                        // Series has no cheap per-episode URL available without an API fetch of
                        // that season's episode list, so the tap opens the existing detail screen
                        // (which already knows how to fetch episodes and resume) rather than
                        // jumping straight into playback like a live channel or movie can.
                        putExtra(WidgetTapReceiver.EXTRA_ACTION, WidgetTapReceiver.ACTION_OPEN_SERIES)
                        putExtra(WidgetTapReceiver.EXTRA_SERIES_ID, row.seriesId)
                        putExtra(WidgetTapReceiver.EXTRA_SERIES_NAME, row.seriesName)
                    }
                    row.isVod -> {
                        putExtra(WidgetTapReceiver.EXTRA_ACTION, WidgetTapReceiver.ACTION_PLAY_VOD)
                        putExtra(WidgetTapReceiver.EXTRA_STREAM_ID, row.streamId)
                        putExtra(WidgetTapReceiver.EXTRA_CONTAINER_EXT, row.containerExtension)
                        putExtra(WidgetTapReceiver.EXTRA_TITLE, row.name)
                    }
                    else -> {
                        // Tapping a widget row previously didn't actually open that channel — it
                        // used "stream_id" here, but HomeActivity.handleJumpToChannelExtra() (the
                        // actual jump-to-channel entry point) reads EXTRA_JUMP_TO_STREAM_ID
                        // ("jump_to_stream_id") instead, so the extra was silently dropped and the
                        // widget just opened the app generically.
                        putExtra(WidgetTapReceiver.EXTRA_ACTION, WidgetTapReceiver.ACTION_JUMP_TO_CHANNEL)
                        putExtra(WidgetTapReceiver.EXTRA_STREAM_ID, row.streamId)
                    }
                }
            }
            setOnClickFillInIntent(R.id.tvWidgetChannelName, fillIn)
            setOnClickFillInIntent(R.id.tvWidgetEpgNow, fillIn)
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long =
        rows.getOrNull(position)?.let { if (it.seriesId != -1) it.seriesId.toLong() else it.streamId.toLong() }
            ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}
