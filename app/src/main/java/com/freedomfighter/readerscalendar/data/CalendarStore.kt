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
    val location: String?,
    /** ARGB of the event as its calendar shows it (the event's own colour, else the calendar's). */
    val color: Int = 0
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
    /** "", "DAILY", "WEEKLY", "MONTHLY", "YEARLY", "OTHER". */
    val repeat: String = "",
    /** The rule as the calendar holds it (days of the week, an end, an interval…): written back
     *  untouched unless [repeat] was changed in the form. */
    val rrule: String = ""
)

/** What a change to one occurrence of a series is for. */
enum class Scope { THIS, FOLLOWING, SERIES }

/**
 * The phone's own calendars through CalendarContract: what the accounts sync (Google, kSync,
 * DAVx5…) is what this app reads and writes. No network of its own.
 */
class CalendarStore(private val context: Context) {
    private val cr get() = context.contentResolver

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** The account a calendar belongs to, with the service it syncs through: two "family"
     *  calendars — one at Google, one synced by DAVx⁵ — must not be taken for each other, even
     *  when both accounts carry the same address. */
    private fun accountLabel(name: String, type: String): String {
        val service = when (type) {
            "com.google" -> "Google"
            "bitfire.at.davdroid", "at.bitfire.davdroid" -> "DAVx⁵"
            "org.dmfs.caldav.account" -> "CalDAV-Sync"
            "", CalendarContract.ACCOUNT_TYPE_LOCAL -> ""
            // another sync app: its own name, read out of the account type ("com.example.sync" → Example)
            else -> type.split('.').filter { it.lowercase() !in setOf("com", "org", "net", "ch", "at", "de", "fr", "io", "app", "android", "account", "accounts", "sync", "calendar", "caldav") }
                .maxByOrNull { it.length }?.replaceFirstChar { it.uppercase() } ?: ""
        }
        return listOf(name, service).filter { it.isNotBlank() && it != CalendarContract.ACCOUNT_TYPE_LOCAL }.joinToString(" · ")
    }

    fun calendars(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val out = ArrayList<CalendarInfo>()
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.ACCOUNT_TYPE)
        cr.query(CalendarContract.Calendars.CONTENT_URI, proj, "${CalendarContract.Calendars.VISIBLE}=1", null, "${CalendarContract.Calendars.ACCOUNT_NAME},${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME}")?.use { c ->
            while (c.moveToNext()) out += CalendarInfo(c.getLong(0), c.getString(1) ?: "", accountLabel(c.getString(2) ?: "", c.getString(4) ?: ""), c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
        }
        return out
    }

    /** Occurrences between two instants, of the calendars not hidden, soonest first. */
    fun occurrences(from: Long, to: Long, hidden: Set<Long>): List<Occurrence> {
        if (!hasPermission()) return emptyList()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from); ContentUris.appendId(builder, to)
        val proj = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DISPLAY_COLOR)
        val out = ArrayList<Occurrence>()
        cr.query(builder.build(), proj, "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED})", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val calId = c.getLong(1)
                if (calId in hidden) continue
                val allDay = c.getInt(5) == 1
                var b = c.getLong(3); var e = c.getLong(4)
                if (allDay) { val off = TimeZone.getDefault().getOffset(b); b -= off; e -= off }
                out += Occurrence(c.getLong(0), calId, c.getString(2)?.ifBlank { null } ?: "(untitled)", b, e, allDay, c.getString(6), if (c.isNull(7)) 0 else c.getInt(7))
            }
        }
        return out.sortedWith(compareBy({ it.begin }, { !it.allDay }))
    }

    /**
     * [begin]: the start of the occurrence that was tapped. A series then answers with THAT
     * occurrence's day and hours, not with the day the series began.
     */
    fun event(id: Long, begin: Long = 0L): EventDetails? {
        val e = master(id) ?: return null
        if (begin <= 0L || e.repeat.isEmpty()) return e
        val zone: ZoneId = if (e.allDay) ZoneOffset.UTC else ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(begin).atZone(zone).toLocalDateTime()
        return e.copy(start = start, end = start.plus(java.time.Duration.between(e.start, e.end)))
    }

    private fun master(id: Long): EventDetails? {
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
            EventDetails(id, c.getLong(0), c.getString(1) ?: "", allDay, start, end, c.getString(5) ?: "", c.getString(6) ?: "", null, repeatOf(c.getString(7)), c.getString(7) ?: "")
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
    fun save(e: EventDetails, exdate: String? = null): Long {
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
                if (e.repeat.isEmpty()) { put(CalendarContract.Events.DTEND, en); putNull(CalendarContract.Events.DURATION) }
                else { putNull(CalendarContract.Events.DTEND); put(CalendarContract.Events.DURATION, "P${((en - s) / 86400_000L).coerceAtLeast(1)}D") }
            } else {
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                val s = e.start.atZone(zone).toInstant().toEpochMilli()
                val en = e.end.atZone(zone).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, s)
                if (e.repeat.isEmpty()) { put(CalendarContract.Events.DTEND, en); putNull(CalendarContract.Events.DURATION) }
                else { putNull(CalendarContract.Events.DTEND); put(CalendarContract.Events.DURATION, "PT${((en - s) / 60_000L).coerceAtLeast(1)}M") }
            }
            // The rule the calendar holds says more than the form shows ("every Tuesday and Thursday
            // until June"): it is written back as it is, unless the repetition itself was changed.
            if (e.repeat.isEmpty()) putNull(CalendarContract.Events.RRULE)
            else if (e.repeat != "OTHER") put(CalendarContract.Events.RRULE, if (repeatOf(e.rrule) == e.repeat) e.rrule else "FREQ=${e.repeat}")
            put(CalendarContract.Events.HAS_ALARM, if (e.reminderMinutes != null) 1 else 0)
            if (exdate != null) put(CalendarContract.Events.EXDATE, exdate)
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

    /** An event taken to another calendar. The provider would let CALENDAR_ID be rewritten, but a
     *  sync adapter (Google, DAVx5) files an event under the calendar it came from and would leave
     *  it there on the server: it is written anew in the other calendar — with the dates its series
     *  skips — and only then deleted from the first, so that a failure leaves one too many, not a
     *  hole. Occurrences changed one by one stay behind with the old series and go with it. */
    fun moveToCalendar(e: EventDetails): Long {
        val old = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id)
        val exdate = cr.query(old, arrayOf(CalendarContract.Events.EXDATE), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        val id = save(e.copy(id = 0L), exdate?.takeIf { it.isNotBlank() && e.repeat.isNotEmpty() })
        cr.delete(old, null, null)
        return id
    }

    // ---- one occurrence of a series, or the series from one occurrence on ------------------------
    // One occurrence goes through the provider's exception address: given the occurrence's
    // original start, it writes the exception the sync adapters expect (the end is never given:
    // the provider works it out of DURATION). "From this one on" is done here rather than left
    // to the provider, whose own split fails on some versions: the new series is written first,
    // then the old one is ended just before it — a failure leaves an event too many, not a hole.

    private fun exceptionValues(e: EventDetails, begin: Long): ContentValues = ContentValues().apply {
        val zone: ZoneId = if (e.allDay) ZoneOffset.UTC else ZoneId.systemDefault()
        val s = (if (e.allDay) e.start.toLocalDate().atStartOfDay() else e.start).atZone(zone).toInstant().toEpochMilli()
        val en = (if (e.allDay) e.end.toLocalDate().plusDays(1).atStartOfDay() else e.end).atZone(zone).toInstant().toEpochMilli()
        put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, begin)
        put(CalendarContract.Events.TITLE, e.title.trim())
        put(CalendarContract.Events.EVENT_LOCATION, e.location.trim())
        put(CalendarContract.Events.DESCRIPTION, e.description.trim())
        put(CalendarContract.Events.DTSTART, s)
        put(CalendarContract.Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else zone.id)
        put(CalendarContract.Events.DURATION, if (e.allDay) "P${((en - s) / 86400_000L).coerceAtLeast(1)}D" else "PT${((en - s) / 60_000L).coerceAtLeast(1)}M")
        put(CalendarContract.Events.HAS_ALARM, if (e.reminderMinutes != null) 1 else 0)
    }

    /** The rule of a series that starts at [begin]: a COUNT loses the occurrences before, and a
     *  weekly rule naming one day follows the occurrence to its new day. */
    private fun ruleForTheRest(e: EventDetails, begin: Long): String {
        var rule = if (repeatOf(e.rrule) == e.repeat && e.rrule.isNotBlank()) e.rrule else "FREQ=${e.repeat}"
        Regex("COUNT=(\\d+)", RegexOption.IGNORE_CASE).find(rule)?.let { m ->
            val before = occurrencesBefore(e.id, begin)
            rule = rule.replaceRange(m.range, "COUNT=${(m.groupValues[1].toInt() - before).coerceAtLeast(1)}")
        }
        Regex("BYDAY=([A-Z]{2})(?=;|$)").find(rule)?.let { m ->
            if ("FREQ=WEEKLY" in rule) rule = rule.replaceRange(m.range, "BYDAY=" + e.start.dayOfWeek.name.take(2))
        }
        return rule
    }

    private fun occurrencesBefore(id: Long, begin: Long): Int {
        val first = cr.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events.DTSTART), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return 0
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, first); ContentUris.appendId(it, begin - 1) }.build()
        val shown = cr.query(uri, arrayOf(CalendarContract.Instances.BEGIN), "${CalendarContract.Instances.EVENT_ID}=?", arrayOf(id.toString()), null)?.use { c ->
            var n = 0; while (c.moveToNext()) if (c.getLong(0) < begin) n++; n
        } ?: 0
        // an occurrence changed or deleted on its own is no longer an instance of the series, but
        // the rule still counts it
        val changed = cr.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.ORIGINAL_ID}=? AND ${CalendarContract.Events.ORIGINAL_INSTANCE_TIME}<?", arrayOf(id.toString(), begin.toString()), null)?.use { it.count } ?: 0
        return shown + changed
    }

    /** [e] as the form holds it, for the occurrence that began at [begin] — alone, or from it on. Returns the new event. */
    fun saveOccurrence(e: EventDetails, begin: Long, scope: Scope): Long {
        if (scope == Scope.FOLLOWING) {
            val rest = ruleForTheRest(e, begin)
            val id = save(e.copy(id = 0L, rrule = rest, repeat = repeatOf(rest)))
            endBefore(e.id, begin)
            return id
        }
        if (neverSynced(e.id)) {
            val id = save(e.copy(id = 0L, repeat = "", rrule = ""))
            skip(e.id, begin)
            return id
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, e.id)
        val id = ContentUris.parseId(cr.insert(uri, exceptionValues(e, begin)) ?: throw IllegalStateException("insert failed"))
        cr.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(id.toString()))
        e.reminderMinutes?.let { m ->
            cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id); put(CalendarContract.Reminders.MINUTES, m); put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        return id
    }

    /** The occurrence that began at [begin] moved by whole days and minutes — alone, or with those after it. */
    fun shiftOccurrence(id: Long, begin: Long, days: Int, minutes: Int, scope: Scope) {
        val e = event(id, begin) ?: return
        val m = if (e.allDay) 0L else minutes.toLong()
        saveOccurrence(e.copy(start = e.start.plusDays(days.toLong()).plusMinutes(m), end = e.end.plusDays(days.toLong()).plusMinutes(m)), begin, scope)
    }

    /** Only the occurrence that began at [begin], or that one and every one after it. */
    fun deleteOccurrence(id: Long, begin: Long, scope: Scope) {
        if (scope == Scope.THIS && neverSynced(id)) { skip(id, begin); return }
        if (scope == Scope.THIS) {
            cr.insert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, id), ContentValues().apply {
                put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, begin)
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
            })
            return
        }
        endBefore(id, begin)
    }

    /** The series ends just before the occurrence that began at [begin]: UNTIL replaces whatever ended it. */
    private fun endBefore(id: Long, begin: Long) {
        val e = master(id) ?: return
        val until = if (e.allDay) Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        else Instant.ofEpochMilli(begin - 1000).atZone(ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val rule = e.rrule.split(";").filter { it.isNotBlank() && !it.startsWith("UNTIL=", true) && !it.startsWith("COUNT=", true) }.joinToString(";") + ";UNTIL=$until"
        save(e.copy(rrule = rule))     // the whole row, as every other change of a series is written
    }

    /**
     * A series no server knows (a calendar kept on the phone only, or an event not sent yet). The
     * provider ties an exception to its series by the server's id: without one, writing an
     * exception makes every other occurrence of the series vanish. Such a series skips the date
     * instead (EXDATE), and the changed occurrence becomes an event of its own.
     */
    private fun neverSynced(id: Long): Boolean = cr.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events._SYNC_ID), null, null, null)?.use { c -> c.moveToFirst() && c.getString(0).isNullOrBlank() } ?: false

    private fun skip(id: Long, begin: Long) {
        val e = master(id) ?: return
        val had = cr.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events.EXDATE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val stamp = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        save(e, exdate = listOfNotNull(had?.takeIf { it.isNotBlank() }, stamp).joinToString(","))
    }

    /** Whether [begin] is the very first occurrence: "from this one on" is then the whole series. */
    fun isFirst(id: Long, begin: Long): Boolean = cr.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events.DTSTART), null, null, null)?.use { c -> c.moveToFirst() && c.getLong(0) == begin } ?: false

    fun delete(id: Long) { cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) }

    /** Whether the event is a series (a rule or dates of its own), so that moving it moves every occurrence. */
    fun repeats(id: Long): Boolean = cr.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), arrayOf(CalendarContract.Events.RRULE, CalendarContract.Events.RDATE), null, null, null)?.use { c ->
        c.moveToFirst() && (!c.getString(0).isNullOrBlank() || !c.getString(1).isNullOrBlank())
    } ?: false

    /**
     * Moves an event by whole days and minutes — a drag in a grid — touching nothing else: the
     * rule, the reminders, the description and the duration stay as they are. Wall-clock
     * arithmetic, so a day across a time change keeps its hour.
     */
    fun shift(id: Long, days: Int, minutes: Int) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
        val row = cr.query(uri, arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return
            Triple(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), c.getInt(2) == 1)
        } ?: return
        val (dtstart, dtend, allDay) = row
        val zone = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
        fun moved(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime().plusDays(days.toLong()).plusMinutes(if (allDay) 0L else minutes.toLong()).atZone(zone).toInstant().toEpochMilli()
        val v = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, moved(dtstart))
            if (dtend != null) put(CalendarContract.Events.DTEND, moved(dtend))
        }
        cr.update(uri, v, null, null)
    }
}
