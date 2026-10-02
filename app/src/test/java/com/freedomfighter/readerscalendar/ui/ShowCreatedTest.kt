package com.freedomfighter.readerscalendar.ui

import com.freedomfighter.readerscalendar.data.EventDetails
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/**
 * A new event is shown in the view it was created from, turned to the event's day — never on a
 * page of its own.
 */
class ShowCreatedTest {
    private fun event(day: LocalDate, hour: Int = 18, allDay: Boolean = false) =
        EventDetails(calendarId = 1L, title = "x", allDay = allDay, start = LocalDateTime.of(day, LocalTime.of(hour, 0)), end = LocalDateTime.of(day, LocalTime.of(hour, 0)).plusHours(1))

    /** The stack as it is when the form has just closed over [view]. */
    private fun over(view: Screen) = Nav().apply { push(view); push(Screen.Edit(0L)); pop() }

    private val monday = LocalDate.of(2026, 10, 5)

    @Test fun weekStaysWhenTheEventIsInIt() {
        val nav = over(Screen.Week(monday)); nav.show(event(monday.plusDays(3)), true)
        assertEquals(listOf(Screen.Agenda, Screen.Week(monday)), nav.stack.toList())
        assertEquals(Reveal(monday.plusDays(3), LocalTime.of(18, 0)), nav.reveal)
    }

    @Test fun weekTurnsToTheEventsWeek() {
        val nav = over(Screen.Week(monday)); nav.show(event(monday.plusDays(16)), true)
        assertEquals(Screen.Week(monday.plusDays(14)), nav.current)
        assertEquals(2, nav.stack.size)
    }

    @Test fun weekStartingOnSunday() {
        val sunday = monday.minusDays(1)
        val nav = over(Screen.Week(sunday)); nav.show(event(monday.plusDays(6)), false)   // the next Sunday
        assertEquals(Screen.Week(monday.plusDays(6)), nav.current)
    }

    @Test fun workdaysKeepTheirLookOnAWeekday() {
        val nav = over(Screen.Week(monday, workdays = true)); nav.show(event(monday.plusDays(8)), false)
        assertEquals(Screen.Week(monday.plusDays(7), workdays = true), nav.current)
    }

    @Test fun workdaysOpenInFullForAWeekendEvent() {
        val nav = over(Screen.Week(monday, workdays = true)); nav.show(event(monday.plusDays(5)), true)
        assertEquals(Screen.Week(monday, workdays = false), nav.current)
    }

    @Test fun dayAndMonthGoToTheDate() {
        val day = over(Screen.Day(monday)); day.show(event(monday.plusDays(40)), true)
        assertEquals(Screen.Day(monday.plusDays(40)), day.current)
        val month = over(Screen.Month(YearMonth.of(2026, 10))); month.show(event(LocalDate.of(2027, 1, 12)), true)
        assertEquals(Screen.Month(YearMonth.of(2027, 1)), month.current)
        assertEquals(2, month.stack.size)
    }

    @Test fun listKeepsWhatItCanShowAndHandsTheRestToTheDay() {
        val today = LocalDate.now()
        val near = Nav(); near.show(event(today.plusDays(3)), true)
        assertEquals(listOf<Screen>(Screen.Agenda), near.stack.toList())
        val past = Nav(); past.show(event(today.minusDays(2)), true)
        assertEquals(listOf(Screen.Agenda, Screen.Day(today.minusDays(2))), past.stack.toList())
        val far = Nav(); far.show(event(today.plusDays(AGENDA_DAYS.toLong())), true)
        assertEquals(Screen.Day(today.plusDays(AGENDA_DAYS.toLong())), far.current)
    }

    @Test fun allDayEventLeavesTheHoursAlone() {
        val nav = over(Screen.Day(monday)); nav.show(event(monday, hour = 0, allDay = true), true)
        assertEquals(Reveal(monday, null), nav.reveal)
    }

    @Test fun leavingTheViewForgetsTheEvent() {
        val nav = over(Screen.Week(monday)); nav.show(event(monday), true)
        nav.push(Screen.Settings)
        assertEquals(null, nav.reveal)
    }
}
