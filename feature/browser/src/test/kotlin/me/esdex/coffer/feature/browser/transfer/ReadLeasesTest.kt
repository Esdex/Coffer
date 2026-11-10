package me.esdex.coffer.feature.browser.transfer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.MessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces of a file read in place go when its last reader closes, never under a reader still open. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadLeasesTest {

    private val a = Id.of(ByteArray(16) { if (it == 0) 1 else 0 })
    private val b = Id.of(ByteArray(16) { if (it == 0) 2 else 0 })
    private fun parts(vararg ids: Long) = ids.map(::MessageId).toSet()

    @Test
    fun theOnlyReaderClosingLetsGoOfWhatItRead() = runTest {
        val leases = ReadLeases(backgroundScope)
        val released = mutableListOf<MessageId>()
        leases.open(a)
        leases.close(a, parts(10, 11)) { released += it }
        runCurrent()
        assertEquals(parts(10, 11), released.toSet())
        assertEquals(2, released.size)
    }

    @Test
    fun openedAgainBeforeTheReleaseRunsNothingGoes() = runTest {
        val leases = ReadLeases(backgroundScope)
        val released = mutableListOf<MessageId>()
        leases.open(a)
        leases.close(a, parts(10)) { released += it }
        leases.open(a)
        runCurrent()
        assertTrue(released.isEmpty())
        // The second reader's close lets go of what both read.
        leases.close(a, parts(11)) { released += it }
        runCurrent()
        assertEquals(parts(10, 11), released.toSet())
    }

    @Test
    fun twoReadersAtOnceTheFirstToCloseKeepsEverything() = runTest {
        val leases = ReadLeases(backgroundScope)
        val released = mutableListOf<MessageId>()
        leases.open(a)
        leases.open(a)
        leases.close(a, parts(10)) { released += it }
        runCurrent()
        assertTrue(released.isEmpty())
        leases.close(a, parts(12)) { released += it }
        runCurrent()
        assertEquals(parts(10, 12), released.toSet())
    }

    @Test
    fun openedAgainWhileReleasingTheRestWaitsForTheNewReader() = runTest {
        val leases = ReadLeases(backgroundScope)
        val released = mutableListOf<MessageId>()
        leases.open(a)
        leases.close(a, parts(10, 11, 12)) { part ->
            released += part
            // A viewer opens the file again while its first piece is being let go.
            if (released.size == 1) leases.open(a)
        }
        runCurrent()
        assertEquals(1, released.size)
        leases.close(a, emptySet()) { released += it }
        runCurrent()
        assertEquals(parts(10, 11, 12), released.toSet())
        assertEquals(3, released.size)
    }

    @Test
    fun filesAreCountedApart() = runTest {
        val leases = ReadLeases(backgroundScope)
        val released = mutableListOf<MessageId>()
        leases.open(a)
        leases.open(b)
        leases.close(a, parts(10)) { released += it }
        runCurrent()
        assertEquals(parts(10), released.toSet())
        // A close nobody opened lets go of nothing.
        leases.close(a, parts(99)) { released += it }
        runCurrent()
        assertEquals(parts(10), released.toSet())
    }

    @Test
    fun aFailedReleaseDoesNotStopTheRest() = runTest {
        val leases = ReadLeases(backgroundScope)
        val released = mutableListOf<MessageId>()
        leases.open(a)
        leases.close(a, linkedSetOf(MessageId(10), MessageId(11))) { part ->
            if (part == MessageId(10)) error("offline")
            released += part
        }
        runCurrent()
        assertEquals(parts(11), released.toSet())
    }
}
