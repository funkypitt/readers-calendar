package com.freedomfighter.readerscalendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "next" on an event's page: the event read after it, in the order of the views. */
class NextEventTest {
    private fun o(id: Long, begin: Long, allDay: Boolean = false) = Occurrence(id, 1L, "e$id", begin, begin + 60, allDay, null)
    // given out of order, as a provider may
    private val list = listOf(o(4, 300), o(2, 100), o(1, 100, allDay = true), o(3, 100), o(2, 500), o(5, 300))

    private fun walk(from: Occurrence): List<String> {
        val out = ArrayList<String>(); var at: Occurrence? = from
        while (at != null) { out += "${at.eventId}@${at.begin}"; at = list.after(at.eventId, at.begin, at.allDay) }
        return out
    }

    @Test fun `every event is read once, the whole day first, twins in a fixed order`() =
        assertEquals(listOf("1@100", "2@100", "3@100", "4@300", "5@300", "2@500"), walk(o(1, 100, allDay = true)))

    @Test fun `the last one has no next`() = assertNull(list.after(2, 500, false))

    @Test fun `an occurrence of a series leads to what follows it, not to the series' first`() =
        assertEquals(3L, list.after(2, 100, false)?.eventId)

    @Test fun `an event of a calendar that is not shown stands at its own start`() {
        assertEquals(4L, list.after(9, 200, false)?.eventId)
        // a whole day of the 300 comes before the timed events of that instant
        assertEquals(4L, list.after(9, 300, true)?.eventId)
    }
}
