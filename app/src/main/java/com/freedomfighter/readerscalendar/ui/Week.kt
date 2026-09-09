package com.freedomfighter.readerscalendar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.draw.clipToBounds
import kotlin.math.abs
import kotlin.math.roundToInt
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
 * The week: seven columns over the hours of the day in both orientations, the way a
 * calendar shows time rather than a list of events. Events are solid blocks, so the gaps
 * between them read as free time at a glance; today's column is headed in inverse and the
 * present moment is a line. Swipe sideways for the next or previous week.
 */
@Composable
fun WeekScreen(nav: Nav, app: App, start: LocalDate, workdays: Boolean = false) {
    val settings by app.prefs.settings.collectAsState()
    val typo = LocalTypo.current
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
    fun go(s: LocalDate) { nav.stack[nav.stack.size - 1] = Screen.Week(s, workdays) }
    var menu by remember { mutableStateOf(false) }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(title, onBack = { nav.pop() }, trailing = "⋯", onTrailing = { menu = true })
            TimeGrid(
                days, occurrences, today, Modifier.weight(1f), compact = !isLandscape(), compactWeekend = workdays,
                onEvent = { nav.push(Screen.Event(it.eventId)) },
                onSlot = { d, t -> nav.push(Screen.Edit(0L, d, t)) },
                onDay = { nav.push(Screen.Day(it)) },
                onSwipe = { go(start.plusWeeks(it.toLong())) },
                onWeekend = { nav.stack[nav.stack.size - 1] = Screen.Week(start, workdays = false) }
            )
            Rule()
            TextRow(stringResource(R.string.new_event), size = typo.title) { nav.push(Screen.Edit(0L, if (today in days) today else start)) }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) ViewsMenu(
            nav, app, onDismiss = { menu = false },
            first = listOf(MenuItem(stringResource(R.string.go_today)) { go(weekStart(today, if (workdays) true else settings.weekStartsMonday)) }),
            newEventDate = if (today in days) today else start
        )
    }
}

/** One event laid in a column: which of the [cols] side-by-side lanes it takes when events overlap. */
private class Placed(val o: Occurrence, val startMin: Int, val endMin: Int, var lane: Int = 0, var lanes: Int = 1)

/** Google-Calendar style lanes: overlapping events share the width of the column, side by side. */
private fun placeLanes(events: List<Placed>): List<Placed> {
    val sorted = events.sortedWith(compareBy({ it.startMin }, { -it.endMin }))
    val cluster = mutableListOf<Placed>(); val laneEnds = mutableListOf<Int>(); var clusterEnd = -1
    fun flush() { cluster.forEach { it.lanes = laneEnds.size }; cluster.clear(); laneEnds.clear() }
    for (p in sorted) {
        if (cluster.isNotEmpty() && p.startMin >= clusterEnd) flush()
        var lane = laneEnds.indexOfFirst { it <= p.startMin }
        if (lane < 0) { laneEnds.add(p.endMin); lane = laneEnds.size - 1 } else laneEnds[lane] = p.endMin
        p.lane = lane; cluster.add(p); clusterEnd = maxOf(clusterEnd, p.endMin)
    }
    flush()
    return sorted
}

/**
 * The time grid shared by the week (seven columns) and the day (one column). [compact] is the
 * portrait week, where columns are narrow and the text inside the blocks smaller. Tapping an
 * empty slot offers a new event at that hour.
 */
@Composable
fun TimeGrid(
    days: List<LocalDate>, occurrences: List<Occurrence>, today: LocalDate, modifier: Modifier, compact: Boolean, compactWeekend: Boolean = false,
    onEvent: (Occurrence) -> Unit, onSlot: (LocalDate, LocalTime) -> Unit, onDay: ((LocalDate) -> Unit)? = null, onSwipe: ((Int) -> Unit)? = null,
    onWeekend: (() -> Unit)? = null
) {
    // "Workdays": Monday to Friday take the width; Saturday and Sunday fold into a narrow strip on
    // the right that only says whether they hold something — tapping it opens the full week.
    val shown = if (compactWeekend) days.filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY } else days
    val weekend = days - shown.toSet()
    val stripW = 28.dp
    fun weightOf(@Suppress("UNUSED_PARAMETER") d: LocalDate): Float = 1f
    val arrowEnd = 28.dp
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val zone = ZoneId.systemDefault()
    val single = days.size == 1
    val hourHeight = if (single) 60.dp else 52.dp
    val gutter = 40.dp
    val blockSize: TextUnit = if (compact && compactWeekend) typo.small * 0.82f else if (compact) typo.small * 0.72f else typo.small
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val f = timeFmt()
    val allDay = remember(occurrences) { occurrences.filter { it.allDay } }
    val timed = remember(occurrences) { occurrences.filter { !it.allDay } }
    // Open on the first thing of the day (or now, if today is shown), an hour early; 08:00 when there is nothing.
    LaunchedEffect(days, timed) {
        val firstEvent = timed.filter { it.date in days }.minOfOrNull { Instant.ofEpochMilli(it.begin).atZone(zone).hour }
        val now = if (today in days) LocalTime.now().hour else null
        val target = (listOfNotNull(firstEvent, now).minOrNull() ?: 8) - 1
        scroll.scrollTo(with(density) { (hourHeight * target.coerceIn(0, 18)).roundToPx() })
    }
    var dragged by remember { mutableStateOf(0f) }
    val swipeMod = if (onSwipe != null) Modifier.pointerInput(days) {
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = { if (abs(dragged) > 80.dp.toPx()) onSwipe(if (dragged < 0) 1 else -1) },
            onDragCancel = { dragged = 0f }
        ) { _, dx -> dragged += dx }
    } else Modifier
    Column(modifier.fillMaxWidth().then(swipeMod)) {
        if (!single) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            // ‹ › sit in the gutter and the right margin, so the header row is also the navigation row.
            Box(Modifier.width(gutter).fillMaxHeight().then(if (onSwipe != null) Modifier.noRippleClickable { onSwipe(-1) } else Modifier), contentAlignment = Alignment.Center) { T("‹", size = typo.title, color = colors.dim, align = TextAlign.Center) }
            for (d in shown) {
                val isToday = d == today
                val narrow = weightOf(d) < 1f
                Column(
                    Modifier.weight(weightOf(d)).padding(horizontal = 2.dp).then(if (isToday) Modifier.background(colors.fg) else Modifier)
                        .then(if (onDay != null) Modifier.noRippleClickable { onDay(d) } else Modifier).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Small(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).lowercase().trimEnd('.').take(if (compact || narrow) 1 else 3), color = if (isToday) colors.bg else colors.dim, align = TextAlign.Center, maxLines = 1)
                    T(d.dayOfMonth.toString(), size = if (narrow) typo.small else typo.title, color = if (isToday) colors.bg else colors.fg, align = TextAlign.Center, maxLines = 1)
                }
            }
            if (compactWeekend) {
                // the folded weekend: a letter per day, a dot when the day holds something
                val busy = remember(occurrences) { occurrences.map { it.date }.toSet() }
                Column(
                    Modifier.width(stripW).fillMaxHeight().then(if (onWeekend != null) Modifier.noRippleClickable { onWeekend() } else Modifier).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    for (d in weekend) {
                        val isToday = d == today
                        Small(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).lowercase().take(1) + (if (d in busy) "·" else " "), color = if (isToday) colors.fg else colors.dim, align = TextAlign.Center, maxLines = 1)
                    }
                }
            }
            Box(Modifier.width(arrowEnd).fillMaxHeight().then(if (onSwipe != null) Modifier.noRippleClickable { onSwipe(1) } else Modifier), contentAlignment = Alignment.Center) { T("›", size = typo.title, color = colors.dim, align = TextAlign.Center) }
        }
        if (allDay.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(end = if (single) 8.dp else arrowEnd, top = 4.dp)) {
            Box(Modifier.width(gutter))
            for (d in shown) Column(Modifier.weight(weightOf(d)).padding(horizontal = 2.dp)) {
                allDay.filter { it.date <= d && Instant.ofEpochMilli(it.end).atZone(zone).toLocalDate() > d }.forEach { o ->
                    Box(Modifier.fillMaxWidth().padding(bottom = 2.dp).background(colors.fg).noRippleClickable { onEvent(o) }.padding(horizontal = 4.dp, vertical = 2.dp)) {
                        T(o.title, size = blockSize, color = colors.bg, maxLines = 1, align = TextAlign.Start, lineHeightMul = 1.2f)
                    }
                }
            }
            if (compactWeekend) Box(Modifier.width(stripW))
        }
        Rule(Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = if (single) 8.dp else arrowEnd)) {
            Column(Modifier.width(gutter).height(hourHeight * 24 + 8.dp)) {
                for (h in 0 until 24) Box(Modifier.height(hourHeight).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    Small("%02d".format(h), Modifier.padding(end = 6.dp).offset(y = (-7).dp), color = colors.dim, maxLines = 1, align = TextAlign.End)
                }
            }
            for (d in shown) {
                val placed = remember(timed, d) {
                    placeLanes(timed.filter { it.date == d }.map { o ->
                        val s = Instant.ofEpochMilli(o.begin).atZone(zone).toLocalTime()
                        val endSameDay = Instant.ofEpochMilli(o.end).atZone(zone).toLocalDate() == d
                        val e = if (endSameDay) Instant.ofEpochMilli(o.end).atZone(zone).toLocalTime() else LocalTime.MAX
                        Placed(o, s.toSecondOfDay() / 60, maxOf(e.toSecondOfDay() / 60, s.toSecondOfDay() / 60 + 25))
                    })
                }
                BoxWithConstraints(
                    Modifier.weight(weightOf(d)).height(hourHeight * 24 + 8.dp).pointerInput(d) {
                        detectTapGestures { pos -> onSlot(d, LocalTime.of(((pos.y / hourHeight.toPx()).toInt()).coerceIn(0, 23), 0)) }
                    }
                ) {
                    val colW = maxWidth
                    val rule = colors.rule
                    Canvas(Modifier.fillMaxSize()) {
                        val hh = hourHeight.toPx()
                        for (h in 0..24) drawLine(rule, Offset(0f, h * hh), Offset(size.width, h * hh), 1f)
                        drawLine(rule, Offset(0f, 0f), Offset(0f, size.height), 1f)
                    }
                    for (p in placed) {
                        val top = hourHeight * (p.startMin / 60f)
                        val h = hourHeight * ((p.endMin - p.startMin) / 60f)
                        val laneW = (colW - 4.dp) / p.lanes
                        val lineH = with(density) { (blockSize * 1.15f).toDp() }
                        val lines = ((h - 5.dp) / lineH).toInt().coerceAtLeast(1)
                        val start = Instant.ofEpochMilli(p.o.begin).atZone(zone).toLocalTime()
                        val showTime = !compact && lines >= 2
                        // A lane too narrow for words: one clipped line beats a column of letters.
                        val wrap = laneW >= 44.dp
                        Column(
                            Modifier.offset(x = 2.dp + laneW * p.lane, y = top).width(laneW).height(h).padding(end = if (p.lane < p.lanes - 1) 1.dp else 0.dp, bottom = 1.dp)
                                .background(colors.fg).noRippleClickable { onEvent(p.o) }.padding(horizontal = 4.dp, vertical = 2.dp).clipToBounds()
                        ) {
                            T(p.o.title, size = blockSize, color = colors.bg, maxLines = if (showTime) lines - 1 else lines, align = TextAlign.Start, lineHeightMul = 1.15f, softWrap = wrap)
                            if (showTime) T(
                                start.format(f) + (if (single && !p.o.location.isNullOrBlank()) " · " + p.o.location else ""),
                                size = blockSize, color = colors.bg.copy(alpha = 0.7f), maxLines = 1, align = TextAlign.Start, lineHeightMul = 1.15f, softWrap = false
                            )
                        }
                    }
                    if (d == today) {
                        val now = LocalTime.now()
                        val fg = colors.fg; val bg = colors.bg
                        Canvas(Modifier.fillMaxSize()) {
                            val y = hourHeight.toPx() * (now.toSecondOfDay() / 3600f)
                            drawLine(bg, Offset(0f, y), Offset(size.width, y), 4f)
                            drawLine(fg, Offset(0f, y), Offset(size.width, y), 2f)
                            drawCircle(bg, 6f, Offset(0f, y)); drawCircle(fg, 4f, Offset(0f, y))
                        }
                    }
                }
            }
            if (compactWeekend) {
                val rule = colors.rule
                Box(Modifier.width(stripW).height(hourHeight * 24 + 8.dp).then(if (onWeekend != null) Modifier.noRippleClickable { onWeekend() } else Modifier)) {
                    Canvas(Modifier.fillMaxSize()) { drawLine(rule, Offset(0f, 0f), Offset(0f, size.height), 1f) }
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
