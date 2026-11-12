package me.esdex.coffer.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** The wait left on a transfer, as a clock that never shows 0:00 while there is still some. */
class CountdownTest {

    @Test
    fun secondsRoundUpAndMinutesCarry() {
        assertEquals("0:42", countdown(42_000))
        // 41.001 s left is still 42 on the clock: rounding down would reach 0:00 a second early.
        assertEquals("0:42", countdown(41_001))
        assertEquals("0:01", countdown(1))
        assertEquals("1:00", countdown(59_001))
        assertEquals("5:00", countdown(300_000))
    }

    @Test
    fun hoursShowOnlyWhenThereAreAny() {
        assertEquals("59:59", countdown(3_599_000))
        assertEquals("1:00:00", countdown(3_600_000))
        assertEquals("1:05:09", countdown(3_909_000))
    }

    @Test
    fun aWaitThatIsOverSaysSo() {
        assertEquals("0:00", countdown(0))
        // The clock may pass the end before the next tick.
        assertEquals("0:00", countdown(-1_500))
    }
}
