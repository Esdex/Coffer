package me.esdex.coffer.feature.browser.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which way the player slides on a track change: next from the right, previous from the left, round the ends. */
class SlideDirectionTest {

    @Test
    fun nextAndPreviousInTheMiddle() {
        assertEquals(1, slideDirection(3, 4, 10))
        assertEquals(-1, slideDirection(4, 3, 10))
    }

    @Test
    fun repeatAllGoesRoundTheEnds() {
        // The last track's next is the first; the first's previous is the last.
        assertEquals(1, slideDirection(9, 0, 10))
        assertEquals(-1, slideDirection(0, 9, 10))
    }

    @Test
    fun aJumpGoesByWhichWayItIs() {
        assertEquals(1, slideDirection(2, 7, 10))
        assertEquals(-1, slideDirection(7, 2, 10))
    }

    @Test
    fun theSameTrackOrNoQueueDoesNotSlide() {
        // The first tags of a track, or repeat one starting it again: a fade only.
        assertEquals(0, slideDirection(5, 5, 10))
        assertEquals(0, slideDirection(0, 1, 0))
    }

    @Test
    fun withTwoTracksEitherWayIsNext() {
        assertEquals(1, slideDirection(0, 1, 2))
        assertEquals(1, slideDirection(1, 0, 2))
    }
}
