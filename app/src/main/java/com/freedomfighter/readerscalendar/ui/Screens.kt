package com.freedomfighter.readerscalendar.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freedomfighter.readerscalendar.App
import com.freedomfighter.readerscalendar.R
import com.freedomfighter.readerscalendar.data.Align
import com.freedomfighter.readerscalendar.data.CalendarInfo
import com.freedomfighter.readerscalendar.data.EventDetails
import com.freedomfighter.readerscalendar.data.FontChoice
import com.freedomfighter.readerscalendar.data.Occurrence
import com.freedomfighter.readerscalendar.data.TextSize
import com.freedomfighter.readerscalendar.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

sealed class Screen {
    data object Agenda : Screen()
    data class Month(val month: YearMonth) : Screen()
    data class Week(val start: LocalDate) : Screen()
    data class Day(val date: LocalDate) : Screen()
    data class Event(val id: Long) : Screen()
    /** id 0 = new event on [date]. */
    data class Edit(val id: Long, val date: LocalDate = LocalDate.now()) : Screen()
    data object Calendars : Screen()
    data object Settings : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Agenda)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
    /** Refresh counter: bump after a write so lists re-query. */
    var version by mutableIntStateOf(0)
}

// ---------------------------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------------------------

@Composable
fun timeFmt(): DateTimeFormatter {
    val ctx = LocalContext.current
    return remember(DateFormat.is24HourFormat(ctx)) { if (DateFormat.is24HourFormat(ctx)) DateTimeFormatter.ofPattern("HH:mm") else DateTimeFormatter.ofPattern("h:mm a") }
}

fun dayLabel(date: LocalDate, today: LocalDate, ctxToday: String, ctxTomorrow: String): String {
    val base = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM")).lowercase()
    return when (date) { today -> "$ctxToday · $base"; today.plusDays(1) -> "$ctxTomorrow · $base"; else -> if (date.year != today.year) "$base ${date.year}" else base }
}

@Composable
fun whenLine(o: Occurrence, allDayText: String): String {
    val f = timeFmt(); val zone = ZoneId.systemDefault()
    if (o.allDay) return allDayText
    val s = Instant.ofEpochMilli(o.begin).atZone(zone).toLocalDateTime(); val e = Instant.ofEpochMilli(o.end).atZone(zone).toLocalDateTime()
    return s.format(f) + " – " + (if (e.toLocalDate() == s.toLocalDate()) e.format(f) else e.format(DateTimeFormatter.ofPattern("d MMM ")) + e.format(f))
}

// ---------------------------------------------------------------------------------------------
// Agenda: the continuous list, grouped by day
// ---------------------------------------------------------------------------------------------

@Composable
fun AgendaScreen(nav: Nav, app: App) {
    val context = LocalContext.current
    val settings by app.prefs.settings.collectAsState()
    val typo = LocalTypo.current
    val colors = LocalColors.current
    val today = LocalDate.now()
    var days by remember { mutableIntStateOf(60) }
    var menu by remember { mutableStateOf(false) }
    val permitted = app.calendars.hasPermission()
    val zone = ZoneId.systemDefault()
    val occurrences by produceState<List<Occurrence>>(emptyList(), nav.version, days, settings.hiddenCalendars, permitted) {
        value = withContext(Dispatchers.IO) {
            val from = today.atStartOfDay(zone).toInstant().toEpochMilli(); val to = today.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            app.calendars.occurrences(from, to, settings.hiddenCalendars)
        }
    }
    val byDay = remember(occurrences) { occurrences.groupBy { it.date }.toSortedMap() }
    val systemDark = isSystemInDarkTheme()
    val allDayText = stringResource(R.string.all_day); val t1 = stringResource(R.string.today); val t2 = stringResource(R.string.tomorrow)

    val landscape = isLandscape()
    val marked = remember(occurrences) { occurrences.map { it.date }.toSet() }
    val list: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(
                title = if (landscape) stringResource(R.string.agenda) else today.format(DateTimeFormatter.ofPattern("MMMM yyyy")).lowercase() + "  ▾",
                onBack = null, trailing = "⋯", onTrailing = { menu = true },
                onTitle = { nav.push(Screen.Month(YearMonth.from(today))) }
            )
            if (!permitted) TextRow(stringResource(R.string.permission_needed), size = typo.title) { (context as? com.freedomfighter.readerscalendar.MainActivity)?.askPermission() }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                if (permitted && byDay.isEmpty()) item { Small(stringResource(R.string.nothing_planned), Modifier.padding(horizontal = rowPadH, vertical = rowPadV)) }
                for ((date, list) in byDay) {
                    item(key = "d$date") {
                        Small(dayLabel(date, today, t1, t2), Modifier.padding(horizontal = rowPadH).padding(top = 18.dp, bottom = 2.dp).noRippleClickable { nav.push(Screen.Day(date)) },
                            color = if (date == today) colors.fg else colors.dim)
                    }
                    items(list, key = { it.key }) { o -> OccurrenceRow(o, allDayText) { nav.push(Screen.Event(o.eventId)) } }
                }
                item { TextRow(stringResource(R.string.more_days), size = typo.small) { days += 60 } }
            }
            Rule()
            TextRow(stringResource(R.string.new_event), size = typo.title) { nav.push(Screen.Edit(0L, today)) }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
    Page {
        if (landscape) TwoPane(left = {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp)) {
                Small(today.format(DateTimeFormatter.ofPattern("MMMM yyyy")).lowercase(), Modifier.padding(horizontal = rowPadH, vertical = 6.dp).noRippleClickable { nav.push(Screen.Month(YearMonth.from(today))) })
                MonthGrid(YearMonth.from(today), settings.weekStartsMonday, marked, null) { nav.push(Screen.Day(it)) }
                Box(Modifier.weight(1f))
                TextRow(stringResource(R.string.week_view), size = typo.title) { nav.push(Screen.Week(weekStart(today, settings.weekStartsMonday))) }
            }
        }, right = list) else list()
        if (menu) TextMenu(
            title = null,
            items = buildList {
                add(MenuItem(stringResource(R.string.go_today)) { nav.home() })
                add(MenuItem(stringResource(R.string.week_view)) { nav.push(Screen.Week(weekStart(today, settings.weekStartsMonday))) })
                add(MenuItem(stringResource(R.string.month_view)) { nav.push(Screen.Month(YearMonth.from(today))) })
                add(MenuItem(stringResource(R.string.calendars)) { nav.push(Screen.Calendars) })
                add(MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(systemDark) })
                add(MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) })
            },
            onDismiss = { menu = false }
        )
    }
}

@Composable
fun OccurrenceRow(o: Occurrence, allDayText: String, onClick: () -> Unit) {
    val colors = LocalColors.current
    Column(Modifier.fillMaxWidth().noRippleClickable(onClick = onClick).padding(horizontal = rowPadH, vertical = rowPadV * 0.6f)) {
        T(o.title, maxLines = 1)
        Small(whenLine(o, allDayText) + (if (!o.location.isNullOrBlank()) " · " + o.location else ""), maxLines = 1, color = colors.dim)
    }
}

// ---------------------------------------------------------------------------------------------
// Month: a grid of numbers; days with events carry a dot; today is inverted
// ---------------------------------------------------------------------------------------------

@Composable
fun MonthGrid(month: YearMonth, weekStartsMonday: Boolean, marked: Set<LocalDate>, selected: LocalDate?, onDay: (LocalDate) -> Unit) {
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val today = LocalDate.now()
    val first = if (weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    val start = month.atDay(1).let { d -> d.minusDays(((d.dayOfWeek.value - first.value + 7) % 7).toLong()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val dow = first.plus(i.toLong())
                Small(dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()).lowercase(), Modifier.weight(1f), align = TextAlign.Center, maxLines = 1)
            }
        }
        var d = start
        while (d.isBefore(month.atEndOfMonth().plusDays(1)) || d.dayOfWeek != first) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val inMonth = YearMonth.from(d) == month
                    val isToday = d == today
                    val day = d
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).noRippleClickable { onDay(day) }
                            .padding(3.dp)
                            .then(if (isToday) Modifier.background(colors.fg) else if (day == selected) Modifier.border(1.dp, colors.fg) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            T(d.dayOfMonth.toString(), size = typo.title, align = TextAlign.Center,
                                color = if (isToday) colors.bg else if (inMonth) colors.fg else colors.rule)
                            T(if (day in marked) "•" else " ", size = typo.small * 0.8f, align = TextAlign.Center, color = if (isToday) colors.bg else colors.fg, lineHeightMul = 0.9f)
                        }
                    }
                    d = d.plusDays(1)
                }
            }
            if (d.isAfter(month.atEndOfMonth()) && d.dayOfWeek == first) break
        }
    }
}


@Composable
fun MonthScreen(nav: Nav, app: App, month: YearMonth) {
    val settings by app.prefs.settings.collectAsState()
    val typo = LocalTypo.current
    val zone = ZoneId.systemDefault()
    BackHandler { nav.pop() }
    val marked by produceState<Set<LocalDate>>(emptySet(), month, nav.version, settings.hiddenCalendars) {
        value = withContext(Dispatchers.IO) {
            val from = month.atDay(1).minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(); val to = month.atEndOfMonth().plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
            app.calendars.occurrences(from, to, settings.hiddenCalendars).map { it.date }.toSet()
        }
    }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")).lowercase(), onBack = { nav.pop() })
            Row(Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 10.dp)) {
                T("‹", Modifier.noRippleClickable { nav.stack[nav.stack.size - 1] = Screen.Month(month.minusMonths(1)) }, size = typo.title, align = TextAlign.Start)
                Box(Modifier.weight(1f))
                T("›", Modifier.noRippleClickable { nav.stack[nav.stack.size - 1] = Screen.Month(month.plusMonths(1)) }, size = typo.title, align = TextAlign.End)
            }
            MonthGrid(month, settings.weekStartsMonday, marked, null) { nav.push(Screen.Day(it)) }
        }
    }
}

@Composable
fun DayScreen(nav: Nav, app: App, date: LocalDate) {
    val settings by app.prefs.settings.collectAsState()
    val typo = LocalTypo.current
    val zone = ZoneId.systemDefault()
    BackHandler { nav.pop() }
    val list by produceState<List<Occurrence>>(emptyList(), date, nav.version, settings.hiddenCalendars) {
        value = withContext(Dispatchers.IO) {
            app.calendars.occurrences(date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), settings.hiddenCalendars)
                .filter { it.date == date || it.allDay }
        }
    }
    val allDayText = stringResource(R.string.all_day)
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(dayLabel(date, LocalDate.now(), stringResource(R.string.today), stringResource(R.string.tomorrow)), onBack = { nav.pop() })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                if (list.isEmpty()) item { Small(stringResource(R.string.nothing_planned), Modifier.padding(horizontal = rowPadH, vertical = rowPadV)) }
                items(list, key = { it.key }) { o -> OccurrenceRow(o, allDayText) { nav.push(Screen.Event(o.eventId)) } }
            }
            Rule()
            TextRow(stringResource(R.string.new_event), size = typo.title) { nav.push(Screen.Edit(0L, date)) }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Event: the details, with the calendar named in small dim text
// ---------------------------------------------------------------------------------------------

@Composable
fun EventScreen(nav: Nav, app: App, id: Long) {
    val typo = LocalTypo.current
    val colors = LocalColors.current
    BackHandler { nav.pop() }
    val event by produceState<EventDetails?>(null, id, nav.version) { value = withContext(Dispatchers.IO) { app.calendars.event(id) } }
    val calendars by produceState<List<CalendarInfo>>(emptyList()) { value = withContext(Dispatchers.IO) { app.calendars.calendars() } }
    var confirmDelete by remember { mutableStateOf(false) }
    val f = timeFmt()
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.event), onBack = { nav.pop() })
            val e = event
            if (e == null) { Small("…", Modifier.padding(rowPadH)); return@Column }
            val cal = calendars.firstOrNull { it.id == e.calendarId }
            val dateLine = if (e.start.toLocalDate() == e.end.toLocalDate()) e.start.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")).lowercase()
            else e.start.format(DateTimeFormatter.ofPattern("d MMM")).lowercase() + " – " + e.end.format(DateTimeFormatter.ofPattern("d MMM yyyy")).lowercase()
            val head: @Composable () -> Unit = {
                T(e.title.ifBlank { "(untitled)" }, size = typo.big * 0.6f, lineHeightMul = 1.15f)
                T(dateLine, Modifier.padding(top = 14.dp), size = typo.title)
                T(if (e.allDay) stringResource(R.string.all_day) else e.start.format(f) + " – " + e.end.format(f), size = typo.title)
                if (e.repeat.isNotEmpty()) Small(repeatLabel(e.repeat), Modifier.padding(top = 4.dp))
                e.reminderMinutes?.let { Small(reminderLabel(it), Modifier.padding(top = 2.dp)) }
                // The calendar the event belongs to: a quiet line of text, never a colour.
                if (cal != null) Small(cal.name + (if (cal.account.isNotBlank() && cal.account != cal.name) " · " + cal.account else ""), Modifier.padding(top = 14.dp), color = colors.dim)
            }
            val body: @Composable () -> Unit = {
                if (e.location.isNotBlank()) { T(e.location, size = typo.title); Rule(Modifier.padding(vertical = 14.dp)) }
                if (e.description.isNotBlank()) T(e.description, size = typo.title, lineHeightMul = 1.4f)
                if (e.location.isBlank() && e.description.isBlank()) Small("—", color = colors.rule)
            }
            if (isLandscape()) Row(Modifier.weight(1f)) {
                Column(Modifier.weight(0.45f).verticalScroll(rememberScrollState()).padding(horizontal = rowPadH, vertical = 20.dp)) { head() }
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.rule))
                Column(Modifier.weight(0.55f).verticalScroll(rememberScrollState()).padding(horizontal = rowPadH, vertical = 20.dp)) { body() }
            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = rowPadH, vertical = 20.dp)) {
                head(); if (e.location.isNotBlank() || e.description.isNotBlank()) Rule(Modifier.padding(vertical = 14.dp)); body()
            }
            Rule()
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.edit), size = typo.title) { nav.push(Screen.Edit(id)) } }
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.delete), size = typo.title) { confirmDelete = true } }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (confirmDelete) TextMenu(
            title = stringResource(R.string.delete_confirm) + (if (event?.repeat?.isNotEmpty() == true) " " + stringResource(R.string.whole_series) else ""),
            items = listOf(MenuItem(stringResource(R.string.delete)) { app.calendars.delete(id); nav.version++; nav.pop() }),
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
fun repeatLabel(r: String): String = when (r) {
    "DAILY" -> stringResource(R.string.repeat_daily); "WEEKLY" -> stringResource(R.string.repeat_weekly)
    "MONTHLY" -> stringResource(R.string.repeat_monthly); "YEARLY" -> stringResource(R.string.repeat_yearly)
    "OTHER" -> stringResource(R.string.repeat_other); else -> stringResource(R.string.repeat_none)
}

@Composable
fun reminderLabel(m: Int?): String = when (m) {
    null -> stringResource(R.string.reminder_none); 0 -> stringResource(R.string.reminder_at_time)
    in 1..59 -> stringResource(R.string.reminder_minutes, m); in 60..1439 -> stringResource(R.string.reminder_hours, m / 60)
    else -> stringResource(R.string.reminder_days, m / 1440)
}

// ---------------------------------------------------------------------------------------------
// Edit / create
// ---------------------------------------------------------------------------------------------

@Composable
fun EditScreen(nav: Nav, app: App, id: Long, date: LocalDate) {
    val context = LocalContext.current
    val settings by app.prefs.settings.collectAsState()
    val typo = LocalTypo.current
    val colors = LocalColors.current
    val f = timeFmt()
    BackHandler { nav.pop() }
    val calendars by produceState<List<CalendarInfo>>(emptyList()) { value = withContext(Dispatchers.IO) { app.calendars.calendars().filter { it.writable } } }
    var loaded by remember { mutableStateOf(id == 0L) }
    val nextHour = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
    var e by remember { mutableStateOf(EventDetails(calendarId = settings.defaultCalendar, start = LocalDateTime.of(date, nextHour), end = LocalDateTime.of(date, nextHour).plusHours(1), reminderMinutes = settings.defaultReminderMinutes.takeIf { it >= 0 })) }
    LaunchedEffect(id) { if (id != 0L) { withContext(Dispatchers.IO) { app.calendars.event(id) }?.let { e = it }; loaded = true } }
    LaunchedEffect(calendars) { if (e.calendarId == 0L || calendars.none { it.id == e.calendarId }) calendars.firstOrNull()?.let { e = e.copy(calendarId = it.id) } }
    var prompt by remember { mutableStateOf<String?>(null) }      // "title" | "location" | "description" | "startTime" | "endTime"
    var datePick by remember { mutableStateOf<String?>(null) }    // "start" | "end"
    var choose by remember { mutableStateOf<String?>(null) }      // "calendar" | "reminder" | "repeat"
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (e.title.isBlank()) { prompt = "title"; return }
        if (!e.end.isAfter(e.start) && !e.allDay) { error = context.getString(R.string.end_before_start); return }
        if (e.allDay && e.end.toLocalDate().isBefore(e.start.toLocalDate())) { error = context.getString(R.string.end_before_start); return }
        runCatching { app.calendars.save(e) }.onSuccess { nav.version++; nav.pop(); if (id == 0L) { nav.push(Screen.Event(it)) } }
            .onFailure { error = it.message ?: "error" }
    }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(if (id == 0L) stringResource(R.string.new_event_title) else stringResource(R.string.edit), onBack = { nav.pop() }, trailing = stringResource(R.string.save), onTrailing = { save() })
            if (!loaded) { Small("…", Modifier.padding(rowPadH)); return@Column }
            val whenPart: @Composable () -> Unit = {
                TextRow(stringResource(R.string.all_day_setting, if (e.allDay) stringResource(R.string.on) else stringResource(R.string.off)), size = typo.title) { e = e.copy(allDay = !e.allDay) }
                TextRow(e.start.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")).lowercase(), secondary = stringResource(R.string.starts), size = typo.title) { datePick = "start" }
                if (!e.allDay) TextRow(e.start.format(f), secondary = stringResource(R.string.start_time), size = typo.title) { prompt = "startTime" }
                TextRow(e.end.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")).lowercase(), secondary = stringResource(R.string.ends), size = typo.title) { datePick = "end" }
                if (!e.allDay) TextRow(e.end.format(f), secondary = stringResource(R.string.end_time), size = typo.title) { prompt = "endTime" }
            }
            val whatPart: @Composable () -> Unit = {
                TextRow(calendars.firstOrNull { it.id == e.calendarId }?.name ?: "…", secondary = stringResource(R.string.calendar), size = typo.title) { choose = "calendar" }
                TextRow(reminderLabel(e.reminderMinutes), secondary = stringResource(R.string.reminder), size = typo.title) { choose = "reminder" }
                TextRow(repeatLabel(e.repeat), secondary = stringResource(R.string.repeat), size = typo.title) { choose = "repeat" }
                Rule()
                TextRow(e.location.ifBlank { stringResource(R.string.location) }, secondary = if (e.location.isBlank()) null else stringResource(R.string.location), size = typo.title) { prompt = "location" }
                TextRow(e.description.ifBlank { stringResource(R.string.description) }, secondary = if (e.description.isBlank()) null else stringResource(R.string.description), size = typo.title) { prompt = "description" }
                error?.let { Small(it, Modifier.padding(horizontal = rowPadH, vertical = 8.dp), color = colors.fg) }
            }
            TextRow(e.title.ifBlank { stringResource(R.string.title_prompt) }, secondary = null, size = typo.tile) { prompt = "title" }
            Rule()
            if (isLandscape()) Row(Modifier.weight(1f)) {
                Column(Modifier.weight(0.5f).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) { whenPart() }
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.rule))
                Column(Modifier.weight(0.5f).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) { whatPart() }
            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) { whenPart(); Rule(); whatPart() }
            Rule()
            TextRow(stringResource(R.string.save), inverted = e.title.isNotBlank(), size = typo.title) { save() }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        when (prompt) {
            "title" -> TextPrompt(stringResource(R.string.title_prompt), e.title, onDone = { e = e.copy(title = it); prompt = null }, onCancel = { prompt = null })
            "location" -> TextPrompt(stringResource(R.string.location), e.location, onDone = { e = e.copy(location = it); prompt = null }, onCancel = { prompt = null })
            "description" -> TextPrompt(stringResource(R.string.description), e.description, onDone = { e = e.copy(description = it); prompt = null }, onCancel = { prompt = null })
            "startTime" -> TimePrompt(stringResource(R.string.start_time), e.start.toLocalTime(), onDone = { t -> val d = java.time.Duration.between(e.start, e.end); val s = LocalDateTime.of(e.start.toLocalDate(), t); e = e.copy(start = s, end = s.plus(d)); prompt = null }, onCancel = { prompt = null })
            "endTime" -> TimePrompt(stringResource(R.string.end_time), e.end.toLocalTime(), onDone = { t -> e = e.copy(end = LocalDateTime.of(e.end.toLocalDate(), t)); prompt = null }, onCancel = { prompt = null })
        }
        datePick?.let { which ->
            DatePickSheet(if (which == "start") e.start.toLocalDate() else e.end.toLocalDate(), settings.weekStartsMonday, onDone = { d ->
                e = if (which == "start") { val dur = java.time.Duration.between(e.start, e.end); val s = LocalDateTime.of(d, e.start.toLocalTime()); e.copy(start = s, end = s.plus(dur)) }
                else e.copy(end = LocalDateTime.of(d, e.end.toLocalTime()))
                datePick = null
            }, onCancel = { datePick = null })
        }
        when (choose) {
            "calendar" -> TextMenu(stringResource(R.string.calendar), calendars.map { c -> MenuItem(c.name, c.account.takeIf { it.isNotBlank() && it != c.name }) { e = e.copy(calendarId = c.id) } }, onDismiss = { choose = null })
            "reminder" -> TextMenu(stringResource(R.string.reminder), listOf(null, 0, 10, 30, 60, 120, 1440, 2880).map { m -> MenuItem(reminderLabel(m)) { e = e.copy(reminderMinutes = m) } }, onDismiss = { choose = null })
            "repeat" -> TextMenu(stringResource(R.string.repeat), listOf("", "DAILY", "WEEKLY", "MONTHLY", "YEARLY").map { r -> MenuItem(repeatLabel(r)) { e = e.copy(repeat = r) } }, onDismiss = { choose = null })
        }
    }
}

/** Time typed as text: "14:30", "1430", "9h15", "9" all work. */
@Composable
fun TimePrompt(title: String, initial: LocalTime, onDone: (LocalTime) -> Unit, onCancel: () -> Unit) {
    var bad by remember { mutableStateOf(false) }
    TextPrompt(title + (if (bad) "  (hh:mm)" else ""), initial.format(DateTimeFormatter.ofPattern("HH:mm")), onDone = { text ->
        val m = Regex("^\\s*(\\d{1,2})\\s*[:hH.]?\\s*(\\d{2})?\\s*$").find(text)
        val h = m?.groupValues?.get(1)?.toIntOrNull(); val mi = m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        if (m != null && h != null && h in 0..23 && mi in 0..59) onDone(LocalTime.of(h, mi)) else bad = true
    }, onCancel = onCancel)
}

/** A month grid to pick a date, as a bottom sheet in the app's style. */
@Composable
fun DatePickSheet(initial: LocalDate, weekStartsMonday: Boolean, onDone: (LocalDate) -> Unit, onCancel: () -> Unit) {
    val colors = LocalColors.current
    val typo = LocalTypo.current
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().background(colors.bg.copy(alpha = 0.6f)).noRippleClickable(onClick = onCancel)) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(colors.bg).noRippleClickable { }.windowInsetsPadding(WindowInsets.navigationBars)) {
            Rule(color = colors.fg)
            Row(Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                T("‹", Modifier.noRippleClickable { month = month.minusMonths(1) }, size = typo.title, align = TextAlign.Start)
                Small(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")).lowercase(), Modifier.weight(1f), align = TextAlign.Center)
                T("›", Modifier.noRippleClickable { month = month.plusMonths(1) }, size = typo.title, align = TextAlign.End)
            }
            MonthGrid(month, weekStartsMonday, emptySet(), initial) { onDone(it) }
            Box(Modifier.padding(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Calendars and settings
// ---------------------------------------------------------------------------------------------

@Composable
fun CalendarsScreen(nav: Nav, app: App) {
    val settings by app.prefs.settings.collectAsState()
    val typo = LocalTypo.current
    BackHandler { nav.pop() }
    val calendars by produceState<List<CalendarInfo>>(emptyList()) { value = withContext(Dispatchers.IO) { app.calendars.calendars() } }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.calendars), onBack = { nav.pop() })
            Small(stringResource(R.string.calendars_help), Modifier.padding(horizontal = rowPadH, vertical = 10.dp), maxLines = 3)
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(calendars, key = { it.id }) { c ->
                    val shown = c.id !in settings.hiddenCalendars
                    TextRow(c.name, inverted = shown, secondary = c.account + (if (!c.writable) " · " + stringResource(R.string.read_only) else "") + (if (c.id == settings.defaultCalendar) " · " + stringResource(R.string.default_calendar) else ""), size = typo.title) {
                        app.prefs.setHiddenCalendars(if (shown) settings.hiddenCalendars + c.id else settings.hiddenCalendars - c.id)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val typo = LocalTypo.current
    BackHandler { nav.pop() }
    val calendars by produceState<List<CalendarInfo>>(emptyList()) { value = withContext(Dispatchers.IO) { app.calendars.calendars().filter { it.writable } } }
    var pick by remember { mutableStateOf<String?>(null) }
    val on = stringResource(R.string.on); val off = stringResource(R.string.off)
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.settings), onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                TextRow(stringResource(R.string.setting_default_calendar, calendars.firstOrNull { it.id == s.defaultCalendar }?.name ?: calendars.firstOrNull()?.name ?: "…"), size = typo.title) { pick = "calendar" }
                TextRow(stringResource(R.string.setting_default_reminder, reminderLabel(s.defaultReminderMinutes.takeIf { it >= 0 })), size = typo.title) { pick = "reminder" }
                TextRow(stringResource(R.string.setting_week_start, if (s.weekStartsMonday) stringResource(R.string.monday) else stringResource(R.string.sunday)), size = typo.title) { app.prefs.setWeekStartsMonday(!s.weekStartsMonday) }
                Rule(Modifier.padding(vertical = 8.dp))
                val themeName = when (s.theme) { ThemeMode.DARK -> stringResource(R.string.theme_dark); ThemeMode.LIGHT -> stringResource(R.string.theme_light); ThemeMode.SYSTEM -> stringResource(R.string.theme_system) }
                TextRow(stringResource(R.string.setting_theme, themeName), size = typo.title) { app.prefs.setTheme(next(s.theme)) }
                TextRow(stringResource(R.string.setting_font, when (s.font) { FontChoice.SERIF -> "serif"; FontChoice.SANS -> "sans"; FontChoice.MONO -> "mono" }), size = typo.title) { app.prefs.setFont(next(s.font)) }
                TextRow(stringResource(R.string.setting_text_size, when (s.textSize) { TextSize.SMALL -> stringResource(R.string.size_small); TextSize.MEDIUM -> stringResource(R.string.size_medium); TextSize.LARGE -> stringResource(R.string.size_large) }), size = typo.title) { app.prefs.setTextSize(next(s.textSize)) }
                TextRow(stringResource(R.string.setting_align, if (s.align == Align.LEFT) stringResource(R.string.align_left) else stringResource(R.string.align_center)), size = typo.title) { app.prefs.setAlign(next(s.align)) }
                TextRow(stringResource(R.string.setting_haptics, if (s.haptics) on else off), size = typo.title) { app.prefs.setHaptics(!s.haptics) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(stringResource(R.string.about, com.freedomfighter.readerscalendar.BuildConfig.VERSION_NAME), size = typo.title, secondary = stringResource(R.string.about_line)) { }
            }
        }
        when (pick) {
            "calendar" -> TextMenu(stringResource(R.string.calendar), calendars.map { c -> MenuItem(c.name, c.account) { app.prefs.setDefaultCalendar(c.id) } }, onDismiss = { pick = null })
            "reminder" -> TextMenu(stringResource(R.string.reminder), listOf(null, 0, 10, 30, 60, 120, 1440).map { m -> MenuItem(reminderLabel(m)) { app.prefs.setDefaultReminder(m ?: -1) } }, onDismiss = { pick = null })
        }
    }
}

private inline fun <reified E : Enum<E>> next(e: E): E { val all = enumValues<E>(); return all[(e.ordinal + 1) % all.size] }
