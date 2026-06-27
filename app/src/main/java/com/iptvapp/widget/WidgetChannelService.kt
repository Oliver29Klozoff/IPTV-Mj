package com.iptvapp.widget

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
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ChannelWidgetFactory(applicationContext)
}

class ChannelWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class WidgetRow(val streamId: Int, val name: String, val epgTitle: String)

    private var rows: List<WidgetRow> = emptyList()

    override fun onCreate() { load() }
    override fun onDataSetChanged() { load() }
    override fun onDestroy() {}

    private fun load() {
        runBlocking(Dispatchers.IO) {
            val db = Room.databaseBuilder(context, IptvDatabase::class.java, IptvDatabase.DATABASE_NAME)
                .addMigrations(
                    IptvDatabase.MIGRATION_2_3,
                    IptvDatabase.MIGRATION_3_4,
                    IptvDatabase.MIGRATION_4_5,
                    IptvDatabase.MIGRATION_5_6
                )
                .build()
            val channels = db.channelDao().getFavoriteChannelsBlocking()
            val nowSec = System.currentTimeMillis() / 1000  // EPG timestamps are in seconds
            rows = channels.take(10).map { ch ->
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
            val fillIn = Intent().apply { putExtra("stream_id", row.streamId) }
            setOnClickFillInIntent(R.id.tvWidgetChannelName, fillIn)
            setOnClickFillInIntent(R.id.tvWidgetEpgNow, fillIn)
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.streamId?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}
