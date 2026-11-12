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

/** Time left as a clock, "0:42" or "1:05:00", rounded up so it never says 0:00 while there is still a wait. */
fun countdown(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 999) / 1_000
    val h = seconds / 3_600
    val m = seconds % 3_600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
