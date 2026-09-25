package com.freedomfighter.readerscalendar.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.freedomfighter.readerscalendar.data.DefaultView
import com.freedomfighter.readerscalendar.data.CalendarInfo
import com.freedomfighter.readerscalendar.data.EventDetails
import com.freedomfighter.readerscalendar.data.Scope
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import java.time.temporal.ChronoUnit
import java.util.Locale

sealed class Screen {
    data object Agenda : Screen()
    data class Month(val month: YearMonth) : Screen()
    data class Week(val start: LocalDate, val workdays: Boolean = false) : Screen()
    data class Day(val date: LocalDate) : Screen()
    /** [begin]: the start of the occurrence that was tapped, 0 when it is not known. */
    data class Event(val id: Long, val begin: Long = 0L) : Screen()
    /** id 0 = new event on [date], at [time] when it comes from a tap on the time grid. */
    data class Edit(val id: Long, val date: LocalDate = LocalDate.now(), val time: LocalTime? = null, val begin: Long = 0L, val scope: Scope = Scope.SERIES) : Screen()
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
// Moving an event by dragging it in a grid
// ---------------------------------------------------------------------------------------------

/**
 * What a drop in a grid asks for: the event shifted by whole days and minutes. A series asks
 * first, since every occurrence moves with it; an event of a read-only calendar is refused.
 * The grids keep the block where it was dropped until the lists come back refreshed, so a
 * refused or cancelled move snaps back on the refresh.
 */
class Mover(private val app: App, private val nav: Nav, private val scope: CoroutineScope) {
    class Pending(val id: Long, val begin: Long, val days: Int, val minutes: Int)
    var pending by mutableStateOf<Pending?>(null)
        private set
    private var applied = false

    /** True when the event will move — now, or once the series is confirmed. */
    fun move(o: Occurrence, days: Int, minutes: Int): Boolean {
        if (days == 0 && minutes == 0) return false
        if (app.calendars.calendars().firstOrNull { it.id == o.calendarId }?.writable != true) return false
        if (app.calendars.repeats(o.eventId)) { applied = false; pending = Pending(o.eventId, o.begin, days, minutes); return true }
        apply(o.eventId, days, minutes); return true
    }

    fun apply(id: Long, days: Int, minutes: Int) { applied = true; runCatching { app.calendars.shift(id, days, minutes) }; nav.version++ }

    /** One occurrence of a series, or the series from it on. */
    fun apply(p: Pending, scope: Scope) {
        applied = true
        runCatching { if (scope == Scope.SERIES) app.calendars.shift(p.id, p.days, p.minutes) else app.calendars.shiftOccurrence(p.id, p.begin, p.days, p.minutes, scope) }
        nav.version++
    }

    fun isFirst(p: Pending) = app.calendars.isFirst(p.id, p.begin)

    /** The sheet closed; unless a choice was made just after, the lists refresh and the block goes back. */
    fun dismiss() { pending = null; scope.launch { if (!applied) nav.version++ } }
}

@Composable
fun rememberMover(nav: Nav, app: App): Mover { val scope = rememberCoroutineScope(); return remember { Mover(app, nav, scope) } }

/**
 * The question every calendar asks of a repeating event: only this one, this one and those after
 * it, or all of them. On the first occurrence "from this one on" is the whole series, and is left out.
 */
@Composable
fun scopeItems(first: Boolean, onChoice: (Scope) -> Unit): List<MenuItem> = buildList {
    add(MenuItem(stringResource(R.string.scope_this)) { onChoice(Scope.THIS) })
    if (!first) add(MenuItem(stringResource(R.string.scope_following)) { onChoice(Scope.FOLLOWING) })
    add(MenuItem(stringResource(R.string.scope_series)) { onChoice(Scope.SERIES) })
}

/** The sheet shown when a dropped event repeats. */
@Composable
fun MoveConfirm(m: Mover) {
    val p = m.pending ?: return
    TextMenu(title = stringResource(R.string.move), items = scopeItems(m.isFirst(p)) { m.apply(p, it) }, onDismiss = { m.dismiss() })
}

// ---------------------------------------------------------------------------------------------
// The views menu, the same from every screen: new event first, then the views, then the look.
// ---------------------------------------------------------------------------------------------

@Composable
fun ViewsMenu(nav: Nav, app: App, onDismiss: () -> Unit, first: List<MenuItem> = emptyList(), newEventDate: LocalDate = LocalDate.now()) {
    val settings by app.prefs.settings.collectAsState()
    val colors = LocalColors.current
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val today = LocalDate.now()
    TextMenu(
        title = null,
        items = buildList {
            addAll(first)
            add(MenuItem(stringResource(R.string.new_event)) { nav.push(Screen.Edit(0L, newEventDate)) })
            add(MenuItem(stringResource(R.string.today_list)) { nav.home() })
            add(MenuItem(stringResource(R.string.today_day)) { nav.push(Screen.Day(today)) })
            add(MenuItem(stringResource(R.string.week_view)) { nav.push(Screen.Week(weekStart(today, settings.weekStartsMonday))) })
            add(MenuItem(stringResource(R.string.workdays_view)) { nav.push(Screen.Week(weekStart(today, true), workdays = true)) })
            add(MenuItem(stringResource(R.string.month_view)) { nav.push(Screen.Month(YearMonth.from(today))) })
            add(MenuItem(stringResource(R.string.calendars)) { nav.push(Screen.Calendars) })
        },
        footer = listOf(
            MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(systemDark) },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) }
        ),
        onDismiss = onDismiss
    )
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
                        Small(dayLabel(date, today, t1, t2), Modifier.padding(horizontal = rowPadH).padding(top = 18.dp, bottom = 2.dp).pressable(onClick = { nav.push(Screen.Day(date)) }, onLongPress = { nav.push(Screen.Edit(0L, date)) }),
                            color = if (date == today) colors.fg else colors.dim)
                    }
                    items(list, key = { it.key }) { o -> OccurrenceRow(o, allDayText) { nav.push(Screen.Event(o.eventId, o.begin)) } }
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
                MonthGrid(YearMonth.from(today), settings.weekStartsMonday, marked, null, onLongDay = { nav.push(Screen.Edit(0L, it)) }) { nav.push(Screen.Day(it)) }
                Box(Modifier.weight(1f))
                TextRow(stringResource(R.string.week_view), size = typo.title) { nav.push(Screen.Week(weekStart(today, settings.weekStartsMonday))) }
                TextRow(stringResource(R.string.workdays_view), size = typo.title) { nav.push(Screen.Week(weekStart(today, true), workdays = true)) }
            }
        }, right = list) else list()
        if (menu) ViewsMenu(nav, app, onDismiss = { menu = false })
    }
}

@Composable
fun OccurrenceRow(o: Occurrence, allDayText: String, onClick: () -> Unit) {
    val colors = LocalColors.current
    Column(Modifier.fillMaxWidth().noRippleClickable(onClick = onClick).padding(horizontal = rowPadH, vertical = rowPadV * 0.6f)) {
        if (LocalColoured.current && o.color != 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 10.dp).size(10.dp).background(eventColors(o.color).first, CircleShape))
            T(o.title, maxLines = 1)
        } else T(o.title, maxLines = 1)
        Small(whenLine(o, allDayText) + (if (!o.location.isNullOrBlank()) " · " + o.location else ""), maxLines = 1, color = colors.dim)
    }
}

// ---------------------------------------------------------------------------------------------
// Month: a grid of numbers; days with events carry a dot; today is inverted
// ---------------------------------------------------------------------------------------------

@Composable
fun MonthGrid(month: YearMonth, weekStartsMonday: Boolean, marked: Set<LocalDate>, selected: LocalDate?, onLongDay: ((LocalDate) -> Unit)? = null, onDay: (LocalDate) -> Unit) {
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
                        Modifier.weight(1f).aspectRatio(1f).then(if (onLongDay != null) Modifier.pressable(onClick = { onDay(day) }, onLongPress = { onLongDay(day) }) else Modifier.noRippleClickable { onDay(day) })
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


/** An event line being dragged across the board (or just dropped, until the refresh): from which day, over which day. */
private data class BoardDrag(val o: Occurrence, val from: LocalDate, val target: LocalDate)

/**
 * The month as a board: six weeks of days that fill the screen, and in every day the events
 * themselves — a line each, the all-day ones first, then "09:00 title" — instead of a dot. What
 * does not fit shows as "+2". Today's number is inverted, the other month's days are dim. Tap a
 * day for its grid, long press for a new event, swipe for the next or previous month. A long
 * press on an event line lifts it: the day under the finger is framed and shows the line, and
 * on release [onMove] gets the day shift (answering whether the move is taken).
 */
@Composable
fun MonthBoard(month: YearMonth, weekStartsMonday: Boolean, occurrences: List<Occurrence>, onDay: (LocalDate) -> Unit, onLongDay: (LocalDate) -> Unit, onSwipe: (Int) -> Unit, onMove: ((Occurrence, Int) -> Boolean)? = null) {
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tick = rememberTick()
    val first = if (weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    val start = month.atDay(1).let { d -> d.minusDays(((d.dayOfWeek.value - first.value + 7) % 7).toLong()) }
    val weeks = run { var n = 0; var d = start; while (d.isBefore(month.atEndOfMonth().plusDays(1)) || d.dayOfWeek != first) { d = d.plusDays(7); n++ }; n }
    val last = start.plusDays(weeks * 7L - 1)
    var drag by remember { mutableStateOf<BoardDrag?>(null) }
    var settled by remember { mutableStateOf<BoardDrag?>(null) }
    LaunchedEffect(occurrences) { settled = null }
    // every day an event touches, in the order they start; an all-day event ends the day before its end
    val byDay = remember(occurrences) {
        val m = HashMap<LocalDate, MutableList<Occurrence>>()
        for (o in occurrences) {
            val a = o.date
            val b = Instant.ofEpochMilli(o.end).atZone(zone).toLocalDate().let { if (o.allDay || Instant.ofEpochMilli(o.end).atZone(zone).toLocalTime() == LocalTime.MIDNIGHT) it.minusDays(1) else it }
            var d = a
            while (!d.isAfter(maxOf(a, b))) { m.getOrPut(d) { mutableListOf() } += o; d = d.plusDays(1) }
        }
        m
    }
    var dragged by remember { mutableFloatStateOf(0f) }
    val lineSize = typo.small * 0.72f
    Column(Modifier.fillMaxSize().pointerInput(month) {
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = { if (abs(dragged) > 80.dp.toPx()) onSwipe(if (dragged < 0) 1 else -1) },
            onDragCancel = { dragged = 0f }
        ) { _, dx -> dragged += dx }
    }) {
        // No ‹ ›: the swipe turns the month, and the 56 dp they took go to the days.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until 7) {
                val dow = first.plus(i.toLong())
                Small(dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()).lowercase().trimEnd('.'), Modifier.weight(1f), align = TextAlign.Center, maxLines = 1)
            }
        }
        Rule()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val rowH = maxHeight / weeks
            // A phone-wide cell has room for a title or a time, not both: the title says more.
            val withTime = maxWidth / 7 >= 110.dp
            // lines of events a day can hold under its number (the number takes ~1.4 lines of small)
            val lines = ((rowH - typo.small.value.dp * 1.5f) / (lineSize.value.dp * 1.25f)).toInt().coerceIn(1, 8)
            val cellWPx = with(density) { ((maxWidth - 8.dp) / 7).toPx() }
            val rowHPx = with(density) { rowH.toPx() }
            val ghost = drag ?: settled
            val coloured = LocalColoured.current
            Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                var d = start
                repeat(weeks) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        for (i in 0 until 7) {
                            val day = d
                            val inMonth = YearMonth.from(day) == month
                            val isToday = day == today
                            // the lifted line shows in the day under the finger, first, and stays dim where it came from
                            val landing = ghost != null && ghost.target == day && ghost.from != day
                            val list = (if (landing) listOf(ghost!!.o) else emptyList()) + byDay[day].orEmpty()
                            val shown = if (list.size > lines) lines - 1 else list.size
                            Column(
                                Modifier.weight(1f).fillMaxHeight().pressable(onClick = { onDay(day) }, onLongPress = { onLongDay(day) })
                                    .then(if (landing) Modifier.border(1.5.dp, colors.fg) else Modifier.border(0.5.dp, colors.rule)).padding(horizontal = 2.dp, vertical = 1.dp)
                            ) {
                                Box(Modifier.then(if (isToday) Modifier.background(colors.fg) else Modifier).padding(horizontal = 3.dp)) {
                                    T(day.dayOfMonth.toString(), size = typo.small, color = if (isToday) colors.bg else if (inMonth) colors.fg else colors.rule, align = TextAlign.Start, maxLines = 1)
                                }
                                for ((n, o) in list.take(shown).withIndex()) {
                                    val text = if (o.allDay || o.date != day || !withTime) o.title else Instant.ofEpochMilli(o.begin).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")) + " " + o.title
                                    val lifted = ghost != null && ghost.o.key == o.key && !(landing && n == 0)
                                    val gesture = if (onMove != null && !(landing && n == 0)) Modifier.dragAfterLongPress(
                                        listOf(o, day, cellWPx, rowHPx),
                                        onTap = { onDay(day) },
                                        onStart = { tick(); drag = BoardDrag(o, day, day) },
                                        onDrag = { off ->
                                            val t = day.plusDays((off.x / cellWPx).roundToInt() + 7L * (off.y / rowHPx).roundToInt())
                                            drag = drag?.copy(target = if (t < start) start else if (t > last) last else t)
                                        },
                                        onDrop = { released ->
                                            val g = drag; drag = null
                                            if (g != null && released && g.target != g.from && onMove(g.o, ChronoUnit.DAYS.between(g.from, g.target).toInt())) settled = g
                                        }
                                    ) else Modifier
                                    if (coloured) {
                                        // the calendar's colour as a band behind the line, as on the desktop
                                        val (fill, ink) = eventColors(o.color)
                                        T(text, Modifier.fillMaxWidth().padding(bottom = 1.dp).background(if (lifted) fill.copy(alpha = 0.45f) else fill).then(gesture).padding(horizontal = 2.dp), size = lineSize, color = ink, align = TextAlign.Start, maxLines = 1, softWrap = false, lineHeightMul = 1.2f)
                                    } else T(text, Modifier.fillMaxWidth().then(gesture), size = lineSize, color = if (lifted) colors.rule else if (inMonth) colors.fg else colors.dim, align = TextAlign.Start, maxLines = 1, softWrap = false, lineHeightMul = 1.25f)
                                }
                                if (list.size > shown) T("+${list.size - shown}", size = lineSize, color = colors.dim, align = TextAlign.Start, maxLines = 1, lineHeightMul = 1.25f)
                            }
                            d = d.plusDays(1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MonthScreen(nav: Nav, app: App, month: YearMonth) {
    val settings by app.prefs.settings.collectAsState()
    val typo = LocalTypo.current
    val zone = ZoneId.systemDefault()
    BackHandler { nav.pop() }
    val occurrences by produceState<List<Occurrence>>(emptyList(), month, nav.version, settings.hiddenCalendars) {
        value = withContext(Dispatchers.IO) {
            val from = month.atDay(1).minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(); val to = month.atEndOfMonth().plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
            app.calendars.occurrences(from, to, settings.hiddenCalendars)
        }
    }
    var menu by remember { mutableStateOf(false) }
    val mover = rememberMover(nav, app)
    fun go(m: YearMonth) { nav.stack[nav.stack.size - 1] = Screen.Month(m) }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")).lowercase(), onBack = { nav.pop() }, trailing = "⋯", onTrailing = { menu = true })
            Box(Modifier.weight(1f)) {
                MonthBoard(month, settings.weekStartsMonday, occurrences, onDay = { nav.push(Screen.Day(it)) }, onLongDay = { nav.push(Screen.Edit(0L, it)) }, onSwipe = { go(month.plusMonths(it.toLong())) },
                    onMove = { o, days -> mover.move(o, days, 0) })
            }
            Rule()
            TextRow(stringResource(R.string.new_event), size = typo.title) { nav.push(Screen.Edit(0L, if (month == YearMonth.from(LocalDate.now())) LocalDate.now() else month.atDay(1))) }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) ViewsMenu(nav, app, onDismiss = { menu = false }, first = listOf(MenuItem(stringResource(R.string.go_today)) { go(YearMonth.from(LocalDate.now())) }), newEventDate = if (month == YearMonth.from(LocalDate.now())) LocalDate.now() else month.atDay(1))
        MoveConfirm(mover)
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
    fun go(d: LocalDate) { nav.stack[nav.stack.size - 1] = Screen.Day(d) }
    var menu by remember { mutableStateOf(false) }
    val mover = rememberMover(nav, app)
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(dayLabel(date, LocalDate.now(), stringResource(R.string.today), stringResource(R.string.tomorrow)), onBack = { nav.pop() }, trailing = "⋯", onTrailing = { menu = true })
            TimeGrid(
                listOf(date), list, LocalDate.now(), Modifier.weight(1f), compact = false,
                onEvent = { nav.push(Screen.Event(it.eventId, it.begin)) },
                onSlot = { d, t -> nav.push(Screen.Edit(0L, d, t)) },
                onSwipe = { go(date.plusDays(it.toLong())) },
                onMove = mover::move
            )
            Rule()
            TextRow(stringResource(R.string.new_event), size = typo.title) { nav.push(Screen.Edit(0L, date)) }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) ViewsMenu(nav, app, onDismiss = { menu = false }, first = if (date != LocalDate.now()) listOf(MenuItem(stringResource(R.string.go_today)) { go(LocalDate.now()) }) else emptyList(), newEventDate = date)
        MoveConfirm(mover)
    }
}

// ---------------------------------------------------------------------------------------------
// Event: the details, with the calendar named in small dim text
// ---------------------------------------------------------------------------------------------

@Composable
fun EventScreen(nav: Nav, app: App, id: Long, begin: Long = 0L) {
    val typo = LocalTypo.current
    val colors = LocalColors.current
    BackHandler { nav.pop() }
    val event by produceState<EventDetails?>(null, id, nav.version) { value = withContext(Dispatchers.IO) { app.calendars.event(id, begin) } }
    val calendars by produceState<List<CalendarInfo>>(emptyList()) { value = withContext(Dispatchers.IO) { app.calendars.calendars() } }
    var confirmDelete by remember { mutableStateOf(false) }
    var askEdit by remember { mutableStateOf(false) }
    // one occurrence of a series, whose start is known: a change asks what it is for
    val occurrence = begin > 0L && event?.repeat?.isNotEmpty() == true
    val first = remember(id, begin, occurrence) { occurrence && app.calendars.isFirst(id, begin) }
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
                if (cal != null) Small(cal.name + (if (cal.account.isNotBlank()) " · " + cal.account else ""), Modifier.padding(top = 14.dp), color = colors.dim)
            }
            val body: @Composable () -> Unit = {
                // the place and the notes are live text: a number dials, an address opens the map,
                // a link opens the browser, and everything can be selected and copied
                if (e.location.isNotBlank()) { LinkedText(e.location, size = typo.title, wholeAs = placeUri(e.location)); Rule(Modifier.padding(vertical = 14.dp)) }
                if (e.description.isNotBlank()) LinkedText(e.description, size = typo.title, lineHeightMul = 1.4f)
                if (e.location.isBlank() && e.description.isBlank()) Small("—", color = colors.rule)
            }
            // long press anywhere on the page selects text to copy. The weight is on a box of the
            // column, not on the selection container: given to the container it was ignored, the
            // page took the whole height and the edit / delete row under it was pushed off the screen.
            Box(Modifier.weight(1f)) { SelectionContainer {
                if (isLandscape()) Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(0.45f).verticalScroll(rememberScrollState()).padding(horizontal = rowPadH, vertical = 20.dp)) { head() }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.rule))
                    Column(Modifier.weight(0.55f).verticalScroll(rememberScrollState()).padding(horizontal = rowPadH, vertical = 20.dp)) { body() }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = rowPadH, vertical = 20.dp)) {
                    head(); if (e.location.isNotBlank() || e.description.isNotBlank()) Rule(Modifier.padding(vertical = 14.dp)); body()
                }
            } }
            Rule()
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.edit), size = typo.title) { if (occurrence) askEdit = true else nav.push(Screen.Edit(id)) } }
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.delete), size = typo.title) { confirmDelete = true } }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (askEdit) TextMenu(title = stringResource(R.string.edit), items = scopeItems(first) { nav.push(Screen.Edit(id, begin = begin, scope = it)) }, onDismiss = { askEdit = false })
        if (confirmDelete && occurrence) TextMenu(
            title = stringResource(R.string.delete),
            items = scopeItems(first) { if (it == Scope.SERIES) app.calendars.delete(id) else app.calendars.deleteOccurrence(id, begin, it); nav.version++; nav.pop() },
            onDismiss = { confirmDelete = false }
        ) else if (confirmDelete) TextMenu(
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
fun EditScreen(nav: Nav, app: App, id: Long, date: LocalDate, time: LocalTime? = null, begin: Long = 0L, scope: Scope = Scope.SERIES) {
    val context = LocalContext.current
    val settings by app.prefs.settings.collectAsState()
    val typo = LocalTypo.current
    val colors = LocalColors.current
    val f = timeFmt()
    BackHandler { nav.pop() }
    val calendars by produceState<List<CalendarInfo>>(emptyList()) { value = withContext(Dispatchers.IO) { app.calendars.calendars().filter { it.writable } } }
    var loaded by remember { mutableStateOf(id == 0L) }
    val nextHour = time ?: LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
    var e by remember { mutableStateOf(EventDetails(calendarId = settings.defaultCalendar, start = LocalDateTime.of(date, nextHour), end = LocalDateTime.of(date, nextHour).plusHours(1), reminderMinutes = settings.defaultReminderMinutes.takeIf { it >= 0 })) }
    // one occurrence, or the series from it on: the form opens on THAT day; the whole series, on the day it began
    var fromCalendar by remember { mutableStateOf(0L) }          // the calendar the event is in now
    LaunchedEffect(id) { if (id != 0L) { withContext(Dispatchers.IO) { app.calendars.event(id, if (scope == Scope.SERIES) 0L else begin) }?.let { e = it; fromCalendar = it.calendarId }; loaded = true } }
    // one occurrence alone stays with its series, in the series' calendar
    val calendarFixed = id != 0L && scope == Scope.THIS && begin > 0L
    LaunchedEffect(calendars) { if (e.calendarId == 0L || calendars.none { it.id == e.calendarId }) calendars.firstOrNull()?.let { e = e.copy(calendarId = it.id) } }
    var prompt by remember { mutableStateOf<String?>(null) }      // "title" | "location" | "description" | "startTime" | "endTime"
    var datePick by remember { mutableStateOf<String?>(null) }    // "start" | "end"
    var choose by remember { mutableStateOf<String?>(null) }      // "calendar" | "reminder" | "repeat"
    var error by remember { mutableStateOf<String?>(null) }
    // A new event starts with its title: the keyboard is up as soon as the screen opens.
    LaunchedEffect(Unit) { if (id == 0L) prompt = "title" }

    fun save() {
        if (e.title.isBlank()) { prompt = "title"; return }
        if (!e.end.isAfter(e.start) && !e.allDay) { error = context.getString(R.string.end_before_start); return }
        if (e.allDay && e.end.toLocalDate().isBefore(e.start.toLocalDate())) { error = context.getString(R.string.end_before_start); return }
        if (id != 0L && scope != Scope.SERIES && begin > 0L) {
            // the page behind showed the occurrence as it was: back to the list it came from
            runCatching { app.calendars.saveOccurrence(e, begin, scope) }.onSuccess { nav.version++; nav.pop(); nav.pop() }.onFailure { error = it.message ?: "error" }
            return
        }
        if (id != 0L && fromCalendar != 0L && e.calendarId != fromCalendar) {
            // a new event in the other calendar: the page behind showed the old one
            runCatching { app.calendars.moveToCalendar(e) }.onSuccess { nav.version++; nav.pop(); nav.pop(); nav.push(Screen.Event(it)) }.onFailure { error = it.message ?: "error" }
            return
        }
        runCatching { app.calendars.save(e) }.onSuccess { nav.version++; nav.pop(); if (id == 0L) { nav.push(Screen.Event(it)) } }
            .onFailure { error = it.message ?: "error" }
    }

    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(if (id == 0L) stringResource(R.string.new_event_title) else stringResource(R.string.edit), onBack = { nav.pop() }, trailing = stringResource(R.string.save), onTrailing = { save() })
            if (!loaded) { Small("…", Modifier.padding(rowPadH)); return@Column }
            val whenPart: @Composable () -> Unit = {
                // The common case first: the day, the start, the end. The end date only when it differs.
                TextRow(e.start.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")).lowercase(), secondary = stringResource(R.string.starts), size = typo.title) { datePick = "start" }
                // One row for the times: it asks the start, then the end, in two quick prompts.
                if (!e.allDay) TextRow(e.start.format(f) + " – " + e.end.format(f), secondary = stringResource(R.string.start_time) + " · " + stringResource(R.string.end_time), size = typo.title) { prompt = "startTime" }
                TextRow(stringResource(R.string.all_day_setting, if (e.allDay) stringResource(R.string.on) else stringResource(R.string.off)), size = typo.title) { e = e.copy(allDay = !e.allDay) }
                if (e.allDay || e.end.toLocalDate() != e.start.toLocalDate()) TextRow(e.end.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")).lowercase(), secondary = stringResource(R.string.ends), size = typo.title) { datePick = "end" }
                else Small(stringResource(R.string.ends_another_day), Modifier.padding(horizontal = rowPadH, vertical = 8.dp).noRippleClickable { datePick = "end" })
            }
            val whatPart: @Composable () -> Unit = {
                val cal = calendars.firstOrNull { it.id == e.calendarId }
                TextRow(cal?.name ?: "…", secondary = stringResource(R.string.calendar) + (cal?.account?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), size = typo.title, onClick = if (calendarFixed) null else ({ choose = "calendar" }))
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
            "startTime" -> TimePrompt(stringResource(R.string.start_time), e.start.toLocalTime(), onDone = { t -> val d = java.time.Duration.between(e.start, e.end); val s = LocalDateTime.of(e.start.toLocalDate(), t); e = e.copy(start = s, end = s.plus(d)); prompt = "endTime" }, onCancel = { prompt = null })
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
            "calendar" -> TextMenu(stringResource(R.string.calendar), calendars.map { c -> MenuItem(c.name, c.account.takeIf { it.isNotBlank() }) { e = e.copy(calendarId = c.id) } }, onDismiss = { choose = null })
            "reminder" -> TextMenu(stringResource(R.string.reminder), listOf(null, 0, 10, 30, 60, 120, 1440, 2880).map { m -> MenuItem(reminderLabel(m)) { e = e.copy(reminderMinutes = m) } }, onDismiss = { choose = null })
            "repeat" -> TextMenu(stringResource(R.string.repeat), listOf("", "DAILY", "WEEKLY", "MONTHLY", "YEARLY").map { r -> MenuItem(repeatLabel(r)) { e = e.copy(repeat = r) } }, onDismiss = { choose = null })
        }
    }
}

/**
 * A time typed as digits around a ":" that is always there: "1830", "18.30", "18,30", "18h30",
 * "9h15", "930" all read as hours and minutes. A separator typed by hand fixes where the hour
 * ends; otherwise it is guessed ("18" → 18:, "9" → 9:, "230" → 2:30, "1830" → 18:30). Backspace
 * over the ":" removes the hour's last digit, so the separator never goes away.
 */
class TimeMask(initial: String) {
    private var digits = initial.filter { it.isDigit() }.take(4)
    /** The hour's length when the user typed a separator, else guessed. */
    private var cut: Int? = null

    private fun hourLen(): Int {
        val d = digits
        cut?.let { return minOf(it, d.length) }
        return when (d.length) {
            0, 1 -> d.length
            2 -> if (d.toInt() <= 23) 2 else 1
            3 -> if (d.drop(1).toInt() <= 59) 1 else 2
            else -> 2
        }
    }

    val text: String get() { val h = hourLen(); return digits.take(h) + ":" + digits.drop(h) }

    /** What the field should show after a keystroke that left it as [typed]. */
    fun apply(typed: String): String {
        val nd = typed.filter { it.isDigit() }
        val seps = typed.count { it in ":.,hH" }
        if (nd == digits) {
            if (seps == 0 && digits.isNotEmpty()) {          // the ":" was deleted: the hour loses a digit
                val h = hourLen(); digits = digits.take(h - 1) + digits.drop(h); cut = null
            } else if (seps > 1 && digits.isNotEmpty()) {   // a separator typed: the hour ends here
                cut = minOf(digits.length, 2)
            }
        } else {
            if (!nd.startsWith(digits)) cut = null              // replaced, not extended: guess again
            digits = nd.take(cut?.plus(2) ?: 4)
            if (cut != null && digits.length < cut!!) cut = null
        }
        return text
    }

    companion object {
        /** The hour and minute of a masked text, or null. A lone minute digit is tens: 18:3 → 18:30. */
        fun parse(text: String): LocalTime? {
            val i = text.indexOf(':'); if (i < 0) return null
            val h = text.substring(0, i).toIntOrNull() ?: return null
            val ms = text.substring(i + 1)
            val m = if (ms.isEmpty()) 0 else ms.padEnd(2, '0').toIntOrNull() ?: return null
            return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
        }
    }
}

/** The time prompt: the suggested time opens selected (the first digit replaces it), the ":" stays whatever is typed. */
@Composable
fun TimePrompt(title: String, initial: LocalTime, onDone: (LocalTime) -> Unit, onCancel: () -> Unit) {
    var bad by remember { mutableStateOf(false) }
    val mask = remember(initial) { TimeMask(initial.format(DateTimeFormatter.ofPattern("HH:mm"))) }
    TextPrompt(title + (if (bad) "  (hh:mm)" else ""), mask.text, keyboard = androidx.compose.ui.text.input.KeyboardType.Number, selectAll = true, normalize = mask::apply, onDone = { text ->
        TimeMask.parse(text)?.let(onDone) ?: run { bad = true }
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
                TextRow(stringResource(R.string.setting_default_view, when (s.defaultView) { DefaultView.AGENDA -> stringResource(R.string.today_list); DefaultView.WEEK -> stringResource(R.string.week_view); DefaultView.WORKDAYS -> stringResource(R.string.workdays_view); DefaultView.DAY -> stringResource(R.string.day_view); DefaultView.MONTH -> stringResource(R.string.month_view) }), size = typo.title) {
                    app.prefs.setDefaultView(when (s.defaultView) { DefaultView.AGENDA -> DefaultView.WEEK; DefaultView.WEEK -> DefaultView.WORKDAYS; DefaultView.WORKDAYS -> DefaultView.DAY; DefaultView.DAY -> DefaultView.MONTH; DefaultView.MONTH -> DefaultView.AGENDA })
                }
                Rule(Modifier.padding(vertical = 8.dp))
                val themeName = when (s.theme) { ThemeMode.DARK -> stringResource(R.string.theme_dark); ThemeMode.LIGHT -> stringResource(R.string.theme_light); ThemeMode.SYSTEM -> stringResource(R.string.theme_system) }
                TextRow(stringResource(R.string.setting_theme, themeName), size = typo.title) { app.prefs.setTheme(next(s.theme)) }
                TextRow(stringResource(R.string.setting_font, when (s.font) { FontChoice.SERIF -> "serif"; FontChoice.SANS -> "sans"; FontChoice.MONO -> "mono" }), size = typo.title) { app.prefs.setFont(next(s.font)) }
                TextRow(stringResource(R.string.setting_text_size, when (s.textSize) { TextSize.SMALL -> stringResource(R.string.size_small); TextSize.MEDIUM -> stringResource(R.string.size_medium); TextSize.LARGE -> stringResource(R.string.size_large) }), size = typo.title) { app.prefs.setTextSize(next(s.textSize)) }
                TextRow(stringResource(R.string.setting_align, if (s.align == Align.LEFT) stringResource(R.string.align_left) else stringResource(R.string.align_center)), size = typo.title) { app.prefs.setAlign(next(s.align)) }
                TextRow(stringResource(R.string.setting_haptics, if (s.haptics) on else off), size = typo.title) { app.prefs.setHaptics(!s.haptics) }
                TextRow(stringResource(R.string.setting_events, stringResource(if (s.colouredEvents) R.string.events_coloured else R.string.events_plain)), size = typo.title) { app.prefs.setColouredEvents(!s.colouredEvents) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(stringResource(R.string.about, com.freedomfighter.readerscalendar.BuildConfig.VERSION_NAME), size = typo.title, secondary = stringResource(R.string.about_line)) { }
                TextRow(stringResource(R.string.credits), size = typo.title) { }
            }
        }
        when (pick) {
            "calendar" -> TextMenu(stringResource(R.string.calendar), calendars.map { c -> MenuItem(c.name, c.account) { app.prefs.setDefaultCalendar(c.id) } }, onDismiss = { pick = null })
            "reminder" -> TextMenu(stringResource(R.string.reminder), listOf(null, 0, 10, 30, 60, 120, 1440).map { m -> MenuItem(reminderLabel(m)) { app.prefs.setDefaultReminder(m ?: -1) } }, onDismiss = { pick = null })
        }
    }
}

private inline fun <reified E : Enum<E>> next(e: E): E { val all = enumValues<E>(); return all[(e.ordinal + 1) % all.size] }
