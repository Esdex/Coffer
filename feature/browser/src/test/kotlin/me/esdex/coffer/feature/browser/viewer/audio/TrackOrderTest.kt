package me.esdex.coffer.feature.browser.viewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class TrackOrderTest {

    private fun TrackOrder.play(steps: Int, byHand: Boolean = false) = List(steps) { next(byHand) }

    @Test
    fun inFolderOrderItStopsAfterTheLast() {
        val order = TrackOrder(3, start = 1)
        assertEquals(listOf(2, null), order.play(2))
        assertEquals(2, order.current)
    }

    @Test
    fun repeatAllGoesRoundBothWays() {
        val order = TrackOrder(3, start = 2).apply { repeat = Repeat.ALL }
        assertEquals(listOf(0, 1, 2, 0), order.play(4))
        val back = TrackOrder(3, start = 0).apply { repeat = Repeat.ALL }
        assertEquals(2, back.previous())
    }

    @Test
    fun previousFromTheFirstStaysThereWithoutRepeat() {
        val order = TrackOrder(3, start = 0)
        assertEquals(0, order.previous())
        order.jumpTo(2)
        assertEquals(1, order.previous())
    }

    @Test
    fun repeatOneReplaysUntilNextIsPressed() {
        val order = TrackOrder(3, start = 1).apply { repeat = Repeat.ONE }
        assertEquals(listOf(1, 1), order.play(2))
        assertEquals(2, order.next(byHand = true))
        // Next by hand from the last track under repeat one stops, as without repeat.
        assertNull(order.next(byHand = true))
    }

    @Test
    fun shuffleKeepsThePlayingTrackAndVisitsEveryOtherOnce() {
        val order = TrackOrder(10, start = 4, random = Random(7))
        order.shuffle(true)
        assertEquals(4, order.current)
        val rest = order.play(9).map { it!! }
        assertEquals((0 until 10).toSet() - 4, rest.toSet())
        assertEquals(9, rest.size)
        assertNull(order.next(byHand = false))
    }

    @Test
    fun shuffleOffGoesOnInFolderOrderFromWhereItIs() {
        val order = TrackOrder(10, start = 0, random = Random(7))
        order.shuffle(true)
        val now = order.play(3).last()!!
        order.shuffle(false)
        assertEquals(now, order.current)
        assertEquals(if (now + 1 < 10) now + 1 else null, order.next(byHand = false))
    }

    @Test
    fun aStartOutsideTheFolderIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { TrackOrder(3, start = 3) }
        assertThrows(IllegalArgumentException::class.java) { TrackOrder(0, start = 0) }
    }
}
