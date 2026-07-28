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

    private data class WidgetRow(val streamId: Int, val name: String, val epgTitle: String)

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
            rows = filteredChannels.map { ch ->
                val epg = db.epgDao().getCurrentProgramForWidget(ch.streamId, nowSec)
                WidgetRow(ch.streamId, ch.name, epg?.title ?: "")
            }
            db.close()
        }
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_channel_row)
        return RemoteViews(context.packageName, R.layout.widget_channel_row).apply {
            setTextViewText(R.id.tvWidgetChannelName, row.name)
            setTextViewText(R.id.tvWidgetEpgNow, row.epgTitle)
            // Tapping a widget row previously didn't actually open that channel — it used
            // "stream_id" here, but HomeActivity.handleJumpToChannelExtra() (the actual
            // jump-to-channel entry point, also used by Settings > Provider Health's "Play
            // This Channel") reads EXTRA_JUMP_TO_STREAM_ID ("jump_to_stream_id") instead, so the
            // extra was silently dropped and the widget just opened the app generically.
            val fillIn = Intent().apply {
                putExtra(com.iptvapp.ui.home.HomeActivity.EXTRA_JUMP_TO_STREAM_ID, row.streamId)
            }
            setOnClickFillInIntent(R.id.tvWidgetChannelName, fillIn)
            setOnClickFillInIntent(R.id.tvWidgetEpgNow, fillIn)
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.streamId?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}
