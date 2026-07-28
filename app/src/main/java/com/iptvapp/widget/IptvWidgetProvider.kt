package com.iptvapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.iptvapp.R
import com.iptvapp.ui.home.HomeActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IptvWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, manager, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetPrefs.clear(context, it) }
    }

    companion object {
        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_channels)

            val updated = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            views.setTextViewText(R.id.tvWidgetUpdated, updated)

            val serviceIntent = Intent(context, WidgetChannelService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.lvWidgetChannels, serviceIntent)

            // Rows can now resolve to either a live channel jump (HomeActivity), a movie
            // (PlayerActivity, needs an async stream-URL lookup first), or a series (opens its
            // detail screen) — WidgetTapReceiver is a transparent pass-through Activity that
            // decides which based on the fill-in intent's action extra.
            val tapIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, WidgetTapReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.lvWidgetChannels, tapIntent)

            val openIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, HomeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.tvWidgetUpdated, openIntent)

            manager.updateAppWidget(widgetId, views)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.lvWidgetChannels)
        }
    }
}
