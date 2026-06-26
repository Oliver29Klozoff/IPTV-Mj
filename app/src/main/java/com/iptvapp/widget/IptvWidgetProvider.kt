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

            val openIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, HomeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.lvWidgetChannels, openIntent)
            views.setOnClickPendingIntent(R.id.tvWidgetUpdated, openIntent)

            manager.updateAppWidget(widgetId, views)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.lvWidgetChannels)
        }
    }
}
