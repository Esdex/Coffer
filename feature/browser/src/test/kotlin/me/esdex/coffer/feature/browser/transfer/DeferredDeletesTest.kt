package me.esdex.coffer.feature.browser.transfer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Undo for deletes (pre-release item 3): nothing reaches Telegram until the chance to undo is over. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeferredDeletesTest {

    private var next = 0
    private fun id(): Id = Id.of(ByteArray(16) { if (it == 0) (++next).toByte() else 0 })

    private class Harness(scope: TestScope, failWith: Exception? = null) {
        val sent = mutableListOf<DeferredDeletes.Batch>()
        val failures = mutableListOf<Throwable>()
        val deletes = DeferredDeletes(scope.backgroundScope, 6_000, send = { batch ->
            failWith?.let { throw it }
            sent += batch
        }, onFailure = { failures += it })
    }

    @Test
    fun nothingGoesOutUntilTheTimeIsUpAndTheItemsStayHiddenMeanwhile() = runTest {
        val h = Harness(this)
        val batch = DeferredDeletes.Batch(listOf(id()), listOf(id(), id()))
        h.deletes.schedule(batch)
        advanceTimeBy(5_999)
        runCurrent()
        assertTrue(h.sent.isEmpty())
        assertEquals(batch.all, h.deletes.hidden.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(batch), h.sent)
        assertTrue(h.deletes.hidden.value.isEmpty())
    }

    @Test
    fun undoInTimeSendsNothingAndShowsTheItemsAgain() = runTest {
        val h = Harness(this)
        h.deletes.schedule(DeferredDeletes.Batch(emptyList(), listOf(id())))
        advanceTimeBy(3_000)
        assertTrue(h.deletes.undo())
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(h.sent.isEmpty())
        assertTrue(h.deletes.hidden.value.isEmpty())
    }

    @Test
    fun undoTooLateSaysSo() = runTest {
        val h = Harness(this)
        val batch = DeferredDeletes.Batch(emptyList(), listOf(id()))
        h.deletes.schedule(batch)
        advanceTimeBy(6_001)
        runCurrent()
        assertFalse(h.deletes.undo())
        assertEquals(listOf(batch), h.sent)
    }

    @Test
    fun aNewDeleteSendsTheOneBeforeAtOnceAndOnlyTheLatestCanBeUndone() = runTest {
        val h = Harness(this)
        val first = DeferredDeletes.Batch(emptyList(), listOf(id()))
        val second = DeferredDeletes.Batch(listOf(id()), emptyList())
        h.deletes.schedule(first)
        advanceTimeBy(1_000)
        h.deletes.schedule(second)
        assertEquals(listOf(first), h.sent)
        assertEquals(second.all, h.deletes.hidden.value)
        assertTrue(h.deletes.undo())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(first), h.sent)
    }

    @Test
    fun flushSendsAtOnceAndOnlyOnce() = runTest {
        val h = Harness(this)
        val batch = DeferredDeletes.Batch(emptyList(), listOf(id()))
        h.deletes.schedule(batch)
        h.deletes.flush()
        assertEquals(listOf(batch), h.sent)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(batch), h.sent)
        assertFalse(h.deletes.undo())
    }

    @Test
    fun aDeleteThatFailsBringsTheItemsBackAndSaysSo() = runTest {
        val h = Harness(this, failWith = StorageException("offline"))
        h.deletes.schedule(DeferredDeletes.Batch(emptyList(), listOf(id())))
        advanceTimeBy(6_001)
        runCurrent()
        assertEquals(1, h.failures.size)
        assertTrue(h.deletes.hidden.value.isEmpty())
    }
}
