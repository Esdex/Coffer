package me.esdex.coffer.feature.browser.viewer.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A queue's keys outlive the lock while music plays, and go ten minutes after it stops. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleStopTest {

    private val minutes10 = 10 * 60_000L

    private class Harness {
        val locked = MutableStateFlow(false)
        val playing = MutableStateFlow(true)
        var stops = 0
    }

    private fun kotlinx.coroutines.test.TestScope.harness(): Harness = Harness().also { h ->
        stopWhenIdleLocked(backgroundScope, h.locked, h.playing, minutes10) { h.stops++ }
        runCurrent()
    }

    @Test
    fun playingOnAfterTheLockNeverStops() = runTest {
        val h = harness()
        h.locked.value = true
        advanceTimeBy(5 * minutes10)
        assertEquals(0, h.stops)
    }

    @Test
    fun pausedWhileLockedStopsAfterTenMinutes() = runTest {
        val h = harness()
        h.locked.value = true
        h.playing.value = false
        advanceTimeBy(minutes10 - 1)
        runCurrent()
        assertEquals(0, h.stops)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, h.stops)
    }

    @Test
    fun playingAgainInTimeStartsTheWaitOver() = runTest {
        val h = harness()
        h.locked.value = true
        h.playing.value = false
        advanceTimeBy(minutes10 - 60_000)
        h.playing.value = true
        runCurrent()
        advanceTimeBy(minutes10)
        h.playing.value = false
        advanceTimeBy(minutes10 - 1)
        runCurrent()
        assertEquals(0, h.stops)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, h.stops)
    }

    @Test
    fun pausedWithTheVaultOpenIsLeftAlone() = runTest {
        val h = harness()
        h.playing.value = false
        advanceTimeBy(5 * minutes10)
        assertEquals(0, h.stops)
        // Unlocked again before the time is up: the wait is called off.
        h.locked.value = true
        advanceTimeBy(minutes10 / 2)
        h.locked.value = false
        advanceTimeBy(minutes10)
        runCurrent()
        assertEquals(0, h.stops)
    }
}
