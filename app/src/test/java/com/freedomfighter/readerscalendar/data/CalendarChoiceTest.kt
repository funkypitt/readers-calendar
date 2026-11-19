package com.freedomfighter.readerscalendar.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** An event's calendar is picked among the calendars shown in the views; the hidden ones come after. */
class CalendarChoiceTest {
    private val all = listOf(
        CalendarInfo(1, "archive", "a", true), CalendarInfo(2, "family", "a", true), CalendarInfo(3, "holidays", "a", false),
        CalendarInfo(4, "old", "b", true), CalendarInfo(5, "personal", "b", true),
    )
    private val hidden = setOf(1L, 4L)
    private fun names(l: List<CalendarInfo>) = l.map { it.name }

    @Test fun shownFirstThenHiddenWritableOnly() = assertEquals(listOf("family", "personal", "archive", "old"), names(all.forAnEvent(hidden)))
    @Test fun nothingHiddenKeepsTheOrder() = assertEquals(listOf("archive", "family", "old", "personal"), names(all.forAnEvent(emptySet())))
    @Test fun onlyTheShownAreListedFirst() = assertEquals(listOf("family", "personal"), names(calendarsListedFirst(all.forAnEvent(hidden), hidden, 2)))
    @Test fun theCalendarInUseIsListedAlthoughHidden() = assertEquals(listOf("family", "personal", "old"), names(calendarsListedFirst(all.forAnEvent(hidden), hidden, 4)))
    @Test fun allOfThemWhenNoneIsShown() {
        val h = setOf(1L, 2L, 4L, 5L)
        assertEquals(listOf("archive", "family", "old", "personal"), names(calendarsListedFirst(all.forAnEvent(h), h, 0)))
    }
}
