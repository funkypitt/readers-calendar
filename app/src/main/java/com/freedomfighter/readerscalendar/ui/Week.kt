package com.freedomfighter.readerscalendar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freedomfighter.readerscalendar.App
import com.freedomfighter.readerscalendar.R
import com.freedomfighter.readerscalendar.data.Occurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun isLandscape(): Boolean = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

fun weekStart(date: LocalDate, monday: Boolean): LocalDate {
    val first = if (monday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
    return date.minusDays(((date.dayOfWeek.value - first.value + 7) % 7).toLong())
}

/**
 * The week. Portrait: seven days as a list, one block per day. Landscape: the classic time
 * grid, seven columns over the hours of the day, events as outlined boxes, today's column
 * headed in inverse and the present moment as a line.
 */
@Composable
fun WeekScreen(nav: Nav, app: App, start: LocalDate) {
    val settings by app.prefs.settings.collectAsState()
    val typo = LocalTypo.current
    val colors = LocalColors.current
    val zone = ZoneId.systemDefault()
    BackHandler { nav.pop() }
    val days = remember(start) { (0 until 7).map { start.plusDays(it.toLong()) } }
    val occurrences by produceState<List<Occurrence>>(emptyList(), start, nav.version, settings.hiddenCalendars) {
        value = withContext(Dispatchers.IO) {
            app.calendars.occurrences(start.atStartOfDay(zone).toInstant().toEpochMilli(), start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(), settings.hiddenCalendars)
        }
    }
    val today = LocalDate.now()
    val title = start.format(DateTimeFormatter.ofPattern("d MMM")).lowercase() + " – " + start.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM yyyy")).lowercase()
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(title, onBack = { nav.pop() }, trailing = stringResource(R.string.go_today), onTrailing = { nav.stack[nav.stack.size - 1] = Screen.Week(weekStart(today, settings.weekStartsMonday)) })
            Row(Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 6.dp)) {
                T("‹", Modifier.noRippleClickable { nav.stack[nav.stack.size - 1] = Screen.Week(start.minusWeeks(1)) }, size = typo.title, align = TextAlign.Start)
                Box(Modifier.weight(1f))
                T("›", Modifier.noRippleClickable { nav.stack[nav.stack.size - 1] = Screen.Week(start.plusWeeks(1)) }, size = typo.title, align = TextAlign.End)
            }
            if (isLandscape()) WeekGrid(days, occurrences, today, Modifier.weight(1f)) { nav.push(Screen.Event(it.eventId)) }
            else WeekList(days, occurrences, today, Modifier.weight(1f), onDay = { nav.push(Screen.Day(it)) }) { nav.push(Screen.Event(it.eventId)) }
        }
    }
}

@Composable
private fun WeekList(days: List<LocalDate>, occurrences: List<Occurrence>, today: LocalDate, modifier: Modifier, onDay: (LocalDate) -> Unit, onEvent: (Occurrence) -> Unit) {
    val colors = LocalColors.current
    val allDayText = stringResource(R.string.all_day); val t1 = stringResource(R.string.today); val t2 = stringResource(R.string.tomorrow)
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        for (d in days) {
            val list = occurrences.filter { it.date == d }
            item(key = "h$d") {
                Small(dayLabel(d, today, t1, t2), Modifier.fillMaxWidth().padding(horizontal = rowPadH).padding(top = 16.dp, bottom = 2.dp).noRippleClickable { onDay(d) }, color = if (d == today) colors.fg else colors.dim)
            }
            if (list.isEmpty()) item(key = "e$d") { Small("—", Modifier.padding(horizontal = rowPadH, vertical = 4.dp), color = colors.rule) }
            items(list, key = { it.key }) { o -> OccurrenceRow(o, allDayText) { onEvent(o) } }
        }
    }
}

@Composable
fun WeekGrid(days: List<LocalDate>, occurrences: List<Occurrence>, today: LocalDate, modifier: Modifier, onEvent: (Occurrence) -> Unit) {
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val zone = ZoneId.systemDefault()
    val hourHeight = 44.dp
    val gutter = 44.dp
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) { scroll.scrollTo(with(density) { (hourHeight * 7).roundToPx() }) }
    val allDay = occurrences.filter { it.allDay }
    val timed = occurrences.filter { !it.allDay }
    Column(modifier.fillMaxWidth()) {
        // Day headers
        Row(Modifier.fillMaxWidth().padding(end = 8.dp)) {
            Box(Modifier.width(gutter))
            for (d in days) {
                val isToday = d == today
                Column(Modifier.weight(1f).padding(horizontal = 2.dp).then(if (isToday) Modifier.background(colors.fg) else Modifier).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Small(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).lowercase().trimEnd('.'), color = if (isToday) colors.bg else colors.dim, align = TextAlign.Center, maxLines = 1)
                    T(d.dayOfMonth.toString(), size = typo.title, color = if (isToday) colors.bg else colors.fg, align = TextAlign.Center, maxLines = 1)
                }
            }
        }
        // All-day strip
        if (allDay.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp)) {
                Box(Modifier.width(gutter))
                for (d in days) {
                    Column(Modifier.weight(1f).padding(horizontal = 2.dp)) {
                        allDay.filter { it.date <= d && Instant.ofEpochMilli(it.end).atZone(zone).toLocalDate() > d }.forEach { o ->
                            Box(Modifier.fillMaxWidth().padding(bottom = 2.dp).background(colors.fg).noRippleClickable { onEvent(o) }.padding(horizontal = 4.dp, vertical = 2.dp)) {
                                Small(o.title, color = colors.bg, maxLines = 1, align = TextAlign.Start)
                            }
                        }
                    }
                }
            }
        }
        Rule(Modifier.padding(top = 4.dp))
        // Hours
        Row(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 8.dp)) {
            Column(Modifier.width(gutter).height(hourHeight * 24)) {
                for (h in 0 until 24) Box(Modifier.height(hourHeight).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    Small("%02d".format(h), Modifier.padding(end = 6.dp).offset(y = (-7).dp), color = colors.dim, maxLines = 1, align = TextAlign.End)
                }
            }
            for (d in days) {
                val dayEvents = timed.filter { it.date == d }
                Box(Modifier.weight(1f).height(hourHeight * 24)) {
                    val rule = colors.rule
                    Canvas(Modifier.fillMaxSize()) {
                        val hh = hourHeight.toPx()
                        for (h in 0..24) drawLine(rule, Offset(0f, h * hh), Offset(size.width, h * hh), 1f)
                        drawLine(rule, Offset(0f, 0f), Offset(0f, size.height), 1f)
                    }
                    for (o in dayEvents) {
                        val s = Instant.ofEpochMilli(o.begin).atZone(zone).toLocalTime()
                        val endSameDay = Instant.ofEpochMilli(o.end).atZone(zone).toLocalDate() == d
                        val e = if (endSameDay) Instant.ofEpochMilli(o.end).atZone(zone).toLocalTime() else LocalTime.MAX
                        val top = hourHeight * (s.toSecondOfDay() / 3600f)
                        val h = (hourHeight * ((e.toSecondOfDay() - s.toSecondOfDay()).coerceAtLeast(1500) / 3600f))
                        Column(
                            Modifier.offset(y = top).padding(horizontal = 2.dp).fillMaxWidth().height(h)
                                .background(colors.bg).border(1.dp, colors.fg).noRippleClickable { onEvent(o) }.padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Small(o.title, color = colors.fg, maxLines = 2, align = TextAlign.Start)
                            if (h > 40.dp) Small(s.format(DateTimeFormatter.ofPattern("HH:mm")), color = colors.dim, maxLines = 1, align = TextAlign.Start)
                        }
                    }
                    if (d == today) {
                        val now = LocalTime.now()
                        val fg = colors.fg
                        Canvas(Modifier.fillMaxSize()) {
                            val y = hourHeight.toPx() * (now.toSecondOfDay() / 3600f)
                            drawLine(fg, Offset(0f, y), Offset(size.width, y), 2f)
                            drawCircle(fg, 4f, Offset(0f, y))
                        }
                    }
                }
            }
        }
    }
}

/** Landscape agenda: month grid on the left, the list on the right. */
@Composable
fun TwoPane(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    val colors = LocalColors.current
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.42f).fillMaxHeight()) { left() }
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.rule))
        Box(Modifier.weight(0.58f).fillMaxHeight()) { right() }
    }
}
