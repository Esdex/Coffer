package me.esdex.coffer.core.sync

import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.InMemoryVaultIndex
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.Progress
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FilterInputStream
import java.io.InputStream

/**
 * "Cache on this phone": every part of a file brought down whole, with no key. The oracle for "whole"
 * is the size of the document the uploader sent, as the fake cloud holds it, not the format's arithmetic.
 */
class PrefetchTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val master = MasterKeys.generate(sodium)
    private val cloud = FakeCloud()

    private fun device() = Device(cloud, keysOf(master), temp.newFolder())

    private suspend fun sentSize(id: MessageId): Long = (cloud.device().message(id)!!.content as MessageContent.Blob).size

    /** Every stream it opens, to see that none is left open: on the phone each holds a file descriptor. */
    private class Streams(inner: StorageBackend) : StorageBackend by inner {
        private val delegate = inner
        val open = mutableListOf<Boolean>()

        override suspend fun openBlob(id: MessageId, offset: Long, length: Long, onProgress: (Progress) -> Unit): InputStream {
            val inner = delegate.openBlob(id, offset, length, onProgress)
            val slot = open.size
            open += true
            return object : FilterInputStream(inner) {
                override fun close() {
                    open[slot] = false
                    super.close()
                }
            }
        }
    }

    @Test
    fun everyPartComesWholeAndIsHandedOver() = runBlocking {
        val here = device()
        val fileId = here.upload("clip.mp4", PatternSource(3L * CofferFormat.CHUNK_SIZE + 77))
        val streams = Streams(cloud.device())
        val phone = CountingStorage(streams)
        val handed = mutableListOf<MessageId>()
        var last = 0L to 0L
        PrefetchRunner(phone, here.index) { handed += it }.run(fileId) { done, total -> last = done to total }
        val parts = here.index.file(fileId)!!.parts.values.toList()
        assertEquals(parts, handed)
        for (part in parts) assertEquals(sentSize(part), phone.localBytes(part))
        val total = parts.sumOf { sentSize(it) }
        assertEquals(total to total, last)
        assertEquals(listOf(false), streams.open)
    }

    @Test
    fun aFileAlreadyWholeIsNotAskedForAgain() = runBlocking {
        val here = device()
        val fileId = here.upload("a.bin", PatternSource(2L * CofferFormat.CHUNK_SIZE))
        val phone = CountingStorage(cloud.device())
        PrefetchRunner(phone, here.index) {}.run(fileId)
        assertEquals(1, phone.blobsOpened)
        val handed = mutableListOf<MessageId>()
        PrefetchRunner(phone, here.index) { handed += it }.run(fileId)
        assertEquals(1, phone.blobsOpened)
        // Still handed over, so the cache marks it as just used.
        assertEquals(here.index.file(fileId)!!.parts.values.toList(), handed)
    }

    @Test
    fun aPartReadOnlyInPlaceIsFinished() = runBlocking {
        val here = device()
        val fileId = here.upload("a.bin", PatternSource(2L * CofferFormat.CHUNK_SIZE))
        val phone = CountingStorage(cloud.device())
        val part = here.index.file(fileId)!!.parts.getValue(0)
        // A viewer read all but the last byte.
        phone.openBlob(part, 0, sentSize(part) - 1).close()
        assertEquals(sentSize(part) - 1, phone.localBytes(part))
        PrefetchRunner(phone, here.index) {}.run(fileId)
        assertEquals(2, phone.blobsOpened)
        assertEquals(sentSize(part), phone.localBytes(part))
    }

    @Test
    fun aStoppedRunPicksUpAtThePartItStoppedAt() = runBlocking {
        val here = device()
        val fileId = here.upload("big.bin", PatternSource(CofferFormat.CHUNKS_PER_PART.toLong() * CofferFormat.CHUNK_SIZE + 5))
        val (first, second) = here.index.file(fileId)!!.parts.let { it.getValue(0) to it.getValue(1) }
        val phone = CountingStorage(cloud.device())
        phone.beforeOpen = { if (it == second) throw StorageException("connection lost") }
        val handed = mutableListOf<MessageId>()
        try {
            PrefetchRunner(phone, here.index) { handed += it }.run(fileId)
            fail("the second part cannot come")
        } catch (_: StorageException) {
        }
        assertEquals(listOf(first), handed)
        val opened = mutableListOf<MessageId>()
        phone.beforeOpen = { opened += it }
        PrefetchRunner(phone, here.index) {}.run(fileId)
        assertEquals(listOf(second), opened)
        assertEquals(sentSize(second), phone.localBytes(second))
    }

    @Test
    fun aPartNotInSavedMessagesStopsIt() = runBlocking {
        val index = InMemoryVaultIndex()
        val fileId = Id.random(sodium)
        index.putFile(fileId, 10, 1, ByteArray(1), FileStatus.INCOMPLETE)
        try {
            PrefetchRunner(cloud.device(), index) {}.run(fileId)
            fail("no part 0")
        } catch (e: MissingPartException) {
            assertTrue(e.partIndex == 0)
        }
    }
}
