package me.esdex.coffer.feature.browser.transfer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.FakeStorageBackend
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.ui.theme.CachePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The file cache on a fake Telegram: what stays on the phone, and what goes first when it is full. */
@OptIn(ExperimentalCoroutinesApi::class)
class BlobCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val cloud = FakeCloud()
    private val uploader = cloud.device()
    private val phone = cloud.device()

    // 400 000 bytes a piece against a 1 MiB limit: two fit, a third does not.
    private val piece = 400_000
    private var prefs = CachePrefs(limitMb = 1)
    private val open = mutableSetOf<Id>()
    private var now = 1_000L

    private var nextId = 0
    private fun fileId() = Id.of(ByteArray(16) { if (it == 0) (++nextId).toByte() else 0 })

    private suspend fun blob(): MessageId {
        val file = temp.newFile().apply { writeBytes(ByteArray(piece) { it.toByte() }) }
        return uploader.sendBlob(file, "coffer:v1:blob:x").id
    }

    /** A piece read on the phone, as a viewer reads it: downloaded, then kept at close. */
    private suspend fun read(id: MessageId) {
        phone.openBlob(id).use { it.readBytes() }
    }

    private fun TestScope.cache(ledger: java.io.File = temp.root.resolve("ledger")) = BlobCache(backgroundScope, ledger, { prefs }, { it in open }, { now })

    private suspend fun FakeStorageBackend.held(id: MessageId) = localBytes(id) > 0

    @Test
    fun underTheLimitEverythingStays() = runTest {
        val cache = cache()
        val a = blob().also { read(it) }
        val b = blob().also { read(it) }
        cache.keep(fileId(), listOf(a, b), phone)
        runCurrent()
        assertTrue(phone.held(a) && phone.held(b))
        assertEquals(2L * piece, cache.size())
    }

    @Test
    fun overTheLimitTheLongestUnreadGoesFirst() = runTest {
        val cache = cache()
        val (a, b, c) = List(3) { blob().also { read(it) } }
        val (fa, fb, fc) = List(3) { fileId() }
        now = 10; cache.keep(fa, listOf(a), phone); runCurrent()
        now = 30; cache.keep(fb, listOf(b), phone); runCurrent()
        // a is read again after b, so b is now the one read longest ago.
        now = 40; cache.keep(fa, listOf(a), phone); runCurrent()
        now = 50; cache.keep(fc, listOf(c), phone); runCurrent()
        assertTrue(phone.held(a))
        assertFalse(phone.held(b))
        assertTrue(phone.held(c))
        assertEquals(2L * piece, cache.size())
    }

    @Test
    fun aFileStillOpenIsNeverTakenAway() = runTest {
        val cache = cache()
        val (a, b, c) = List(3) { blob().also { read(it) } }
        val (fa, fb, fc) = List(3) { fileId() }
        now = 10; cache.keep(fa, listOf(a), phone)
        now = 20; cache.keep(fb, listOf(b), phone)
        runCurrent()
        open += fa
        now = 30; cache.keep(fc, listOf(c), phone); runCurrent()
        // a is the oldest, but open: b goes in its place.
        assertTrue(phone.held(a))
        assertFalse(phone.held(b))
        assertTrue(phone.held(c))
    }

    @Test
    fun withTheCacheOffPiecesGoAtOnce() = runTest {
        prefs = CachePrefs(enabled = false)
        val cache = cache()
        val a = blob().also { read(it) }
        cache.keep(fileId(), listOf(a), phone)
        runCurrent()
        assertFalse(phone.held(a))
        assertEquals(0L, cache.size())
    }

    @Test
    fun withTheCacheOffAFileOpenAgainKeepsItsPiecesTillItCloses() = runTest {
        prefs = CachePrefs(enabled = false)
        val cache = cache()
        val (a, b) = List(2) { blob().also { read(it) } }
        val f = fileId()
        // Closed and at once opened again: its pieces stay under the new reader.
        open += f
        cache.keep(f, listOf(a), phone)
        runCurrent()
        assertTrue(phone.held(a))
        open -= f
        cache.keep(f, listOf(b), phone)
        runCurrent()
        assertFalse(phone.held(a) || phone.held(b))
    }

    @Test
    fun clearingLeavesOnlyWhatIsOpen() = runTest {
        prefs = CachePrefs(limitMb = 10)
        val cache = cache()
        val (a, b) = List(2) { blob().also { read(it) } }
        val (fa, fb) = List(2) { fileId() }
        cache.keep(fa, listOf(a), phone)
        cache.keep(fb, listOf(b), phone)
        runCurrent()
        open += fb
        cache.clear(phone)
        assertFalse(phone.held(a))
        assertTrue(phone.held(b))
        assertEquals(piece.toLong(), cache.size())
    }

    @Test
    fun clearingDropsTheUnknownOnlyWhenNothingIsReadOrSaved() = runTest {
        val cache = cache()
        val a = blob().also { read(it) }
        val f = fileId()
        cache.keep(f, listOf(a), phone)
        runCurrent()
        var purged = 0
        // Something is being saved: what the ledger never heard of may be that download.
        cache.clear(phone, idle = { false }, purge = { purged++ })
        assertEquals(0, purged)
        // Idle, but a file is open: its pieces stay, and so does everything else.
        val b = blob().also { read(it) }
        open += f
        cache.keep(f, listOf(b), phone)
        runCurrent()
        cache.clear(phone, idle = { true }, purge = { purged++ })
        assertEquals(0, purged)
        // Nothing open and nothing saved: the unknown goes too.
        open -= f
        cache.clear(phone, idle = { true }, purge = { purged++ })
        assertEquals(1, purged)
        assertEquals(0L, cache.size())
    }

    @Test
    fun theLedgerOutlivesTheProcess() = runTest {
        prefs = CachePrefs(limitMb = 10)
        val ledger = temp.root.resolve("ledger")
        val first = cache(ledger)
        val (a, b) = List(2) { blob().also { read(it) } }
        now = 10; first.keep(fileId(), listOf(a), phone); runCurrent()
        now = 20; first.keep(fileId(), listOf(b), phone); runCurrent()
        // A new process, and a lower limit: what was read first goes, from the ledger alone.
        val second = cache(ledger)
        assertEquals(2L * piece, second.size())
        prefs = CachePrefs(limitMb = 0)
        second.trim(phone)
        assertFalse(phone.held(a) || phone.held(b))
        assertEquals(0L, second.size())
    }

    @Test
    fun aDamagedLedgerLosesOnlyItsBadLines() = runTest {
        val ledger = temp.root.resolve("ledger")
        ledger.writeText("12 AAAAAAAAAAAAAAAAAAAAAA 500 7\nnot a line\n13 short 1 1\n14 AAAAAAAAAAAAAAAAAAAAAA x 7\n15 AAAAAAAAAAAAAAAAAAAAAA 9\n")
        assertEquals(500L, cache(ledger).size())
    }

    @Test
    fun aPieceWithNothingOnThePhoneIsNotWrittenDown() = runTest {
        val ledger = temp.root.resolve("ledger")
        val cache = cache(ledger)
        val a = blob()
        cache.keep(fileId(), listOf(a), phone)
        runCurrent()
        assertEquals(0L, cache.size())
        assertEquals("", ledger.readText())
    }

    @Test
    fun whatIsHeldIsPublishedForTheCheckMarks() = runTest {
        val ledger = temp.root.resolve("ledger")
        val cache = cache(ledger)
        val (a, b, c) = List(3) { blob().also { read(it) } }
        now = 10; cache.keep(fileId(), listOf(a), phone); runCurrent()
        now = 15; cache.keep(fileId(), listOf(b), phone); runCurrent()
        assertEquals(mapOf(a to piece.toLong(), b to piece.toLong()), cache.held.value)
        // Over the limit: the one read longest ago goes from the list too.
        now = 20; cache.keep(fileId(), listOf(c), phone); runCurrent()
        assertEquals(setOf(b, c), cache.held.value.keys)
        // A new process knows it from the ledger before anything is kept.
        val next = cache(ledger)
        runCurrent()
        assertEquals(setOf(b, c), next.held.value.keys)
        next.forget()
        assertTrue(next.held.value.isEmpty())
    }

    @Test
    fun signingOutForgetsTheLedger() = runTest {
        val ledger = temp.root.resolve("ledger")
        val cache = cache(ledger)
        val a = blob().also { read(it) }
        cache.keep(fileId(), listOf(a), phone)
        runCurrent()
        assertTrue(ledger.exists())
        cache.forget()
        assertFalse(ledger.exists())
        assertEquals(0L, cache(ledger).size())
    }
}
