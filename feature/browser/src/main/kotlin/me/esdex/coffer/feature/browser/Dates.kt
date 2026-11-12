package me.esdex.coffer.feature.browser

import java.time.Instant
import java.time.ZoneId

/** How a file's date reads in its row: today and yesterday by name, anything older as a date. */
enum class Day { TODAY, YESTERDAY, OLDER }

/** Which [Day] [millis] falls on, seen from [nowMillis] in [zone]: by the calendar, not by 24 hours. */
fun dayOf(millis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Day {
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return when (day) {
        today -> Day.TODAY
        today.minusDays(1) -> Day.YESTERDAY
        else -> Day.OLDER
    }
}
