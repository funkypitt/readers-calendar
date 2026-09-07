package com.freedomfighter.readerscalendar.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

data class CalendarInfo(val id: Long, val name: String, val account: String, val writable: Boolean)

/** One occurrence of an event, as shown in lists. */
data class Occurrence(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?
) {
    /** Local date the occurrence starts on. */
    val date: LocalDate get() = Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).toLocalDate()
    val key: String get() = "$eventId-$begin"
}

/** Everything editable about an event. */
data class EventDetails(
    val id: Long = 0L,
    val calendarId: Long,
    val title: String = "",
    val allDay: Boolean = false,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val location: String = "",
    val description: String = "",
    /** Minutes before the start; null = no reminder. */
    val reminderMinutes: Int? = null,
    /** "", "DAILY", "WEEKLY", "MONTHLY", "YEARLY". */
    val repeat: String = ""
)

/**
 * The phone's own calendars through CalendarContract: what the accounts sync (Google, kSync,
 * DAVx5…) is what this app reads and writes. No network of its own.
 */
class CalendarStore(private val context: Context) {
    private val cr get() = context.contentResolver

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val out = ArrayList<CalendarInfo>()
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        cr.query(CalendarContract.Calendars.CONTENT_URI, proj, "${CalendarContract.Calendars.VISIBLE}=1", null, "${CalendarContract.Calendars.ACCOUNT_NAME},${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME}")?.use { c ->
            while (c.moveToNext()) out += CalendarInfo(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
        }
        return out
    }

    /** Occurrences between two instants, of the calendars not hidden, soonest first. */
    fun occurrences(from: Long, to: Long, hidden: Set<Long>): List<Occurrence> {
        if (!hasPermission()) return emptyList()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from); ContentUris.appendId(builder, to)
        val proj = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION)
        val out = ArrayList<Occurrence>()
        cr.query(builder.build(), proj, "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED})", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val calId = c.getLong(1)
                if (calId in hidden) continue
                val allDay = c.getInt(5) == 1
                var b = c.getLong(3); var e = c.getLong(4)
                if (allDay) { val off = TimeZone.getDefault().getOffset(b); b -= off; e -= off }
                out += Occurrence(c.getLong(0), calId, c.getString(2)?.ifBlank { null } ?: "(untitled)", b, e, allDay, c.getString(6))
            }
        }
        return out.sortedWith(compareBy({ it.begin }, { !it.allDay }))
    }

    fun event(id: Long): EventDetails? {
        if (!hasPermission()) return null
        val proj = arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE, CalendarContract.Events.ALL_DAY, CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.RRULE, CalendarContract.Events.DURATION)
        val zone = ZoneId.systemDefault()
        val details = cr.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), proj, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val allDay = c.getInt(2) == 1
            val dtstart = c.getLong(3)
            var dtend = if (c.isNull(4)) 0L else c.getLong(4)
            if (dtend == 0L) dtend = dtstart + parseDuration(c.getString(8))
            val start = if (allDay) Instant.ofEpochMilli(dtstart).atZone(ZoneOffset.UTC).toLocalDateTime() else Instant.ofEpochMilli(dtstart).atZone(zone).toLocalDateTime()
            val end = if (allDay) Instant.ofEpochMilli(dtend).atZone(ZoneOffset.UTC).toLocalDateTime().minusDays(1) else Instant.ofEpochMilli(dtend).atZone(zone).toLocalDateTime()
            EventDetails(id, c.getLong(0), c.getString(1) ?: "", allDay, start, end, c.getString(5) ?: "", c.getString(6) ?: "", null, repeatOf(c.getString(7)))
        } ?: return null
        val reminder = cr.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES), "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(id.toString()), null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else null
        }
        return details.copy(reminderMinutes = reminder)
    }

    private fun parseDuration(d: String?): Long {
        if (d.isNullOrBlank()) return 3600_000L
        val m = Regex("P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").find(d) ?: return 3600_000L
        val (w, dd, h, mi, s) = m.destructured
        return ((w.toLongOrNull() ?: 0) * 7 * 86400 + (dd.toLongOrNull() ?: 0) * 86400 + (h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000
    }

    private fun repeatOf(rrule: String?): String = when {
        rrule.isNullOrBlank() -> ""
        "FREQ=DAILY" in rrule -> "DAILY"
        "FREQ=WEEKLY" in rrule -> "WEEKLY"
        "FREQ=MONTHLY" in rrule -> "MONTHLY"
        "FREQ=YEARLY" in rrule -> "YEARLY"
        else -> "OTHER"
    }

    /** Insert or update; returns the event id. A repeating event is written as a whole series. */
    fun save(e: EventDetails): Long {
        val zone = ZoneId.systemDefault()
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, e.calendarId)
            put(CalendarContract.Events.TITLE, e.title.trim())
            put(CalendarContract.Events.EVENT_LOCATION, e.location.trim())
            put(CalendarContract.Events.DESCRIPTION, e.description.trim())
            put(CalendarContract.Events.ALL_DAY, if (e.allDay) 1 else 0)
            if (e.allDay) {
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                val s = e.start.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                val en = e.end.toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                put(CalendarContract.Events.DTSTART, s)
                if (e.repeat.isEmpty() || e.repeat == "OTHER") { put(CalendarContract.Events.DTEND, en); putNull(CalendarContract.Events.DURATION) }
                else { putNull(CalendarContract.Events.DTEND); put(CalendarContract.Events.DURATION, "P${((en - s) / 86400_000L).coerceAtLeast(1)}D") }
            } else {
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                val s = e.start.atZone(zone).toInstant().toEpochMilli()
                val en = e.end.atZone(zone).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, s)
                if (e.repeat.isEmpty() || e.repeat == "OTHER") { put(CalendarContract.Events.DTEND, en); putNull(CalendarContract.Events.DURATION) }
                else { putNull(CalendarContract.Events.DTEND); put(CalendarContract.Events.DURATION, "PT${((en - s) / 60_000L).coerceAtLeast(1)}M") }
            }
            if (e.repeat.isNotEmpty() && e.repeat != "OTHER") put(CalendarContract.Events.RRULE, "FREQ=${e.repeat}")
            else if (e.repeat.isEmpty()) putNull(CalendarContract.Events.RRULE)
            put(CalendarContract.Events.HAS_ALARM, if (e.reminderMinutes != null) 1 else 0)
        }
        val id = if (e.id == 0L) {
            ContentUris.parseId(cr.insert(CalendarContract.Events.CONTENT_URI, v) ?: throw IllegalStateException("insert failed"))
        } else {
            cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id), v, null, null); e.id
        }
        cr.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(id.toString()))
        e.reminderMinutes?.let { m ->
            cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id); put(CalendarContract.Reminders.MINUTES, m); put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        return id
    }

    fun delete(id: Long) { cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) }
}
