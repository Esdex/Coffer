package me.esdex.coffer.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Today and yesterday by the calendar of the phone's zone, not by counting hours back. */
class DatesTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun todayYesterdayAndOlderGoByTheCalendar() {
        val now = at("2026-11-12T00:30:00")
        assertEquals(Day.TODAY, dayOf(at("2026-11-12T00:00:00"), now, zone))
        // An hour ago, but yesterday.
        assertEquals(Day.YESTERDAY, dayOf(at("2026-11-11T23:30:00"), now, zone))
        assertEquals(Day.YESTERDAY, dayOf(at("2026-11-11T00:00:00"), now, zone))
        assertEquals(Day.OLDER, dayOf(at("2026-11-10T23:59:59"), now, zone))
        // A file dated later than now, from another phone's clock, is today as well.
        assertEquals(Day.TODAY, dayOf(at("2026-11-12T08:00:00"), now, zone))
        assertEquals(Day.OLDER, dayOf(at("2026-11-13T08:00:00"), now, zone))
    }

    @Test
    fun theZoneDecidesTheDay() {
        val now = at("2026-11-12T00:30:00")
        val evening = at("2026-11-11T22:00:00")
        assertEquals(Day.YESTERDAY, dayOf(evening, now, zone))
        // In UTC both are on the 11th.
        assertEquals(Day.TODAY, dayOf(evening, now, ZoneId.of("UTC")))
    }
}
