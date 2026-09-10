package com.freedomfighter.readerscalendar.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.freedomfighter.readerscalendar.App
import com.freedomfighter.readerscalendar.R
import com.freedomfighter.readerscalendar.data.Occurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Standard home-screen widgets for any launcher: the next event, or today and tomorrow. */
object CalendarWidgets {
    private const val PKG = "com.freedomfighter.readerscalendar"

    fun upcoming(context: Context, max: Int = 30): List<Occurrence> {
        val app = context.applicationContext as App
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val end = LocalDate.now().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return runCatching { app.calendars.occurrences(now - 6 * 3600_000L, end, app.prefs.settings.value.hiddenCalendars) }.getOrDefault(emptyList())
            .filter { it.end > now }.sortedBy { it.begin }.take(max)
    }

    fun whenLabel(context: Context, o: Occurrence): String {
        val zone = ZoneId.systemDefault()
        val d = Instant.ofEpochMilli(o.begin).atZone(zone).toLocalDate()
        val today = LocalDate.now()
        val day = when (d) { today -> context.getString(R.string.today); today.plusDays(1) -> context.getString(R.string.tomorrow); else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM")).lowercase() }
        if (o.allDay) return day + " · " + context.getString(R.string.all_day)
        val f = if (android.text.format.DateFormat.is24HourFormat(context)) DateTimeFormatter.ofPattern("HH:mm") else DateTimeFormatter.ofPattern("h:mm a")
        return day + " · " + Instant.ofEpochMilli(o.begin).atZone(zone).format(f) + " – " + Instant.ofEpochMilli(o.end).atZone(zone).format(f)
    }

    fun openEvent(id: Long): Intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)).setClassName(PKG, "$PKG.MainActivity")
    fun newEvent(): Intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).setClassName(PKG, "$PKG.MainActivity")
    fun openApp(): Intent = Intent(Intent.ACTION_MAIN).setClassName(PKG, "$PKG.MainActivity")

    fun renderLine(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_line)
        WidgetUi.paint(views, context, intArrayOf(R.id.widget_title, R.id.widget_plus), intArrayOf(R.id.widget_sub))
        val first = upcoming(context, 1).firstOrNull()
        if (first == null) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.nothing_planned))
            views.setTextViewText(R.id.widget_sub, context.getString(R.string.agenda))
            views.setOnClickPendingIntent(R.id.widget_body, WidgetUi.activity(context, openApp(), 1))
        } else {
            views.setTextViewText(R.id.widget_title, first.title)
            views.setTextViewText(R.id.widget_sub, whenLabel(context, first))
            views.setOnClickPendingIntent(R.id.widget_body, WidgetUi.activity(context, openEvent(first.eventId), 1))
        }
        views.setOnClickPendingIntent(R.id.widget_plus, WidgetUi.activity(context, newEvent(), 2))
        mgr.updateAppWidget(id, views)
    }

    fun renderList(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_list)
        WidgetUi.paint(views, context, intArrayOf(R.id.widget_plus), intArrayOf(R.id.widget_caption, R.id.widget_empty))
        views.setTextViewText(R.id.widget_caption, context.getString(R.string.agenda))
        views.setTextViewText(R.id.widget_empty, context.getString(R.string.nothing_planned))
        val svc = Intent(context, ListService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).apply { data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) }
        views.setRemoteAdapter(R.id.widget_list, svc)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        // items fill in their event URI; the template carries the target activity
        views.setPendingIntentTemplate(R.id.widget_list, WidgetUi.activity(context, Intent(Intent.ACTION_VIEW).setClassName(PKG, "$PKG.MainActivity"), 3))
        views.setOnClickPendingIntent(R.id.widget_caption, WidgetUi.activity(context, openApp(), 1))
        views.setOnClickPendingIntent(R.id.widget_plus, WidgetUi.activity(context, newEvent(), 2))
        mgr.updateAppWidget(id, views)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }

    fun refresh(context: Context) = WidgetUi.refresh(context, LineWidget::class.java, ListWidget::class.java)
}

class LineWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { CalendarWidgets.renderLine(context, mgr, it) } }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_PROVIDER_CHANGED) CalendarWidgets.refresh(context)
    }
}

class ListWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { CalendarWidgets.renderList(context, mgr, it) } }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_PROVIDER_CHANGED) CalendarWidgets.refresh(context)
    }
}

class ListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var items: List<Occurrence> = emptyList()
        override fun onCreate() {}
        override fun onDataSetChanged() { items = CalendarWidgets.upcoming(applicationContext) }
        override fun onDestroy() {}
        override fun getCount() = items.size
        override fun getViewAt(i: Int): RemoteViews {
            val o = items[i]
            val v = RemoteViews(packageName, R.layout.widget_item)
            val (_, fg, dim) = WidgetUi.colors(applicationContext)
            v.setTextViewText(R.id.item_title, o.title); v.setTextColor(R.id.item_title, fg)
            v.setTextViewText(R.id.item_sub, CalendarWidgets.whenLabel(applicationContext, o)); v.setTextColor(R.id.item_sub, dim)
            v.setOnClickFillInIntent(R.id.item_root, Intent().setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, o.eventId)))
            return v
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(i: Int) = items.getOrNull(i)?.eventId ?: i.toLong()
        override fun hasStableIds() = true
    }
}
