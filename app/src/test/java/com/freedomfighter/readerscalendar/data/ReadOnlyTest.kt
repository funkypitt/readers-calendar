package com.freedomfighter.readerscalendar.data

import android.provider.CalendarContract.Calendars
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ReadOnlyTest {
    @Test fun aCalendarOneMayOnlyReadTakesNoChange() {
        for (level in listOf(Calendars.CAL_ACCESS_NONE, Calendars.CAL_ACCESS_FREEBUSY, Calendars.CAL_ACCESS_READ, Calendars.CAL_ACCESS_RESPOND, Calendars.CAL_ACCESS_OVERRIDE))
            assertFalse("level $level", takesChanges(level))
    }

    @Test fun aCalendarOneMayWriteToTakesChanges() {
        for (level in listOf(Calendars.CAL_ACCESS_CONTRIBUTOR, Calendars.CAL_ACCESS_EDITOR, Calendars.CAL_ACCESS_OWNER, Calendars.CAL_ACCESS_ROOT))
            assertTrue("level $level", takesChanges(level))
    }

    /** A new event, which no calendar holds yet, is one that can be written. */
    @Test fun aNewEventCanBeWritten() {
        val now = LocalDateTime.of(2026, 10, 2, 9, 0)
        assertTrue(EventDetails(calendarId = 1L, start = now, end = now.plusHours(1)).writable)
    }
}
