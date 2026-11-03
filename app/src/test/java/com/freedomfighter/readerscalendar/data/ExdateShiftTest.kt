package com.freedomfighter.readerscalendar.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** A series moved by a drag takes the dates it leaves out along with it. */
class ExdateShiftTest {
    private var saved: TimeZone? = null
    @Before fun zurich() { saved = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich")) }
    @After fun back() { TimeZone.setDefault(saved) }

    @Test fun utcValueMovesByAnHour() = assertEquals("20261104T140000Z", shiftedExdates("20261104T130000Z", 0, 60))
    @Test fun severalValuesAndADay() = assertEquals("20261105T130000Z,20261108T130000Z", shiftedExdates("20261104T130000Z,20261107T130000Z", 1, 0))
    @Test fun withATimeZone() = assertEquals("Europe/Zurich;20261104T153000", shiftedExdates("Europe/Zurich;20261104T140000", 0, 90))
    @Test fun allDayDates() = assertEquals("20261106", shiftedExdates("20261104", 2, 0))
    // across the end of summer time the wall clock is kept: 14:00 local stays 14:00 local
    @Test fun acrossTheTimeChange() = assertEquals("20261026T130000Z", shiftedExdates("20261019T120000Z", 7, 0))
    @Test fun nothingToMove() = assertEquals("20261104T130000Z", shiftedExdates("20261104T130000Z", 0, 0))
    @Test fun unreadableValueKept() = assertEquals("garbage,20261104T140000Z", shiftedExdates("garbage,20261104T130000Z", 0, 60))
}
