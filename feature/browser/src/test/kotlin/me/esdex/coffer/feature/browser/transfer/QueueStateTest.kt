package me.esdex.coffer.feature.browser.transfer

import me.esdex.coffer.feature.browser.transfer.QueueState.Start
import me.esdex.coffer.feature.browser.transfer.TransferKind.CACHE
import me.esdex.coffer.feature.browser.transfer.TransferKind.DOWNLOAD
import me.esdex.coffer.feature.browser.transfer.TransferKind.UPLOAD
import me.esdex.coffer.feature.browser.transfer.TransferRegistry.Phase.MOVING
import me.esdex.coffer.feature.browser.transfer.TransferRegistry.Phase.PREPARING
import me.esdex.coffer.feature.browser.transfer.TransferRegistry.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One run of the transfer queue at a time, and no work lost to a run that is ending; and its notification. */
class QueueStateTest {

    @Test
    fun workWhileNothingRunsStartsARunAndWorkWhileItRunsWakesIt() {
        val queue = QueueState()
        assertEquals(Start.ONE, queue.added())
        val seen = queue.started()
        assertEquals(Start.NONE, queue.added())
        // That work came after the run last looked: it may not end yet.
        assertFalse(queue.finishing(seen))
        val again = queue.seen()
        assertTrue(queue.finishing(again))
    }

    @Test
    fun workAsTheRunEndsGetsOneRunAfterIt() {
        val queue = QueueState()
        queue.added()
        val seen = queue.started()
        assertTrue(queue.finishing(seen))
        // The run is ending but not over: a plain request would be dropped while it still counts as running.
        assertEquals(Start.AFTER, queue.added())
        // Until that next run starts, more work needs no more runs than one.
        assertEquals(Start.ONE, queue.added())
        queue.started()
        assertEquals(Start.NONE, queue.added())
    }

    @Test
    fun aRunStoppedFromOutsideLetsTheNextWorkAskAgain() {
        val queue = QueueState()
        queue.added()
        queue.started()
        queue.stopped()
        assertEquals(Start.ONE, queue.added())
        // Stopped after it had already said it was ending: it stays ending.
        val q2 = QueueState()
        q2.added()
        assertTrue(q2.finishing(q2.started()))
        q2.stopped()
        assertEquals(Start.AFTER, q2.added())
    }

    private fun moving(done: Long, total: Long) = Progress(done, total, MOVING)

    @Test
    fun theSummaryCountsTheWholeRun() {
        // 2 of 4 done, one running at half, one waiting.
        val s = QueueSummary.of(listOf(UPLOAD, UPLOAD), finished = 2, total = 4, running = listOf(UPLOAD to moving(50, 100), UPLOAD to null))
        assertEquals(UPLOAD, s.kind)
        assertEquals(62, s.percent)
        assertEquals(50L, s.done)
        assertEquals(100L, s.size)
    }

    @Test
    fun aMixIsNamedAsAMixAndNothingMovingRunsTheBar() {
        assertNull(QueueSummary.of(listOf(UPLOAD, DOWNLOAD, CACHE), 0, 3, listOf(UPLOAD to null)).kind)
        // Only preparing, nothing finished: no percent to show.
        assertNull(QueueSummary.of(listOf(UPLOAD), 0, 1, listOf(UPLOAD to Progress(0, 10, PREPARING))).percent)
        // But finished ones count even while the rest is preparing.
        assertEquals(50, QueueSummary.of(listOf(UPLOAD), 1, 2, listOf(UPLOAD to Progress(0, 10, PREPARING))).percent)
        // A progress with nothing to measure yet does not divide by nothing.
        assertNull(QueueSummary.of(listOf(DOWNLOAD), 0, 1, listOf(DOWNLOAD to moving(0, 0))).percent)
        assertEquals(100, QueueSummary.of(listOf(CACHE), 3, 3, emptyList()).percent)
    }
}
