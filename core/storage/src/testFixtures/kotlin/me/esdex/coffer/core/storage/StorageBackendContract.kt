package me.esdex.coffer.core.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.random.Random

/**
 * What every [StorageBackend] must do. Runs on the JVM against [FakeStorageBackend] and on a device
 * against TDLib and Telegram's test servers, so the fake cannot drift from the real thing.
 *
 * The chat may hold messages from earlier runs: tests only reason about messages they sent.
 */
abstract class StorageBackendContract {

    /** Two devices signed in to the same account. */
    protected abstract val device: StorageBackend
    protected abstract val otherDevice: StorageBackend

    /** Where test blobs are written before upload. */
    protected abstract val scratch: File

    protected open val timeoutMillis: Long = 120_000

    private fun contract(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(timeoutMillis) { block() }
    }

    /** Every message after [after], following pages until an empty one. */
    private suspend fun StorageBackend.allAfter(after: MessageId, pageSize: Int): List<StoredMessage> {
        val read = mutableListOf<StoredMessage>()
        var anchor = after
        while (true) {
            val page = historyAfter(anchor, pageSize)
            if (page.isEmpty()) return read
            assertEquals("page is oldest first", page.sortedBy { it.id }, page)
            assertTrue("page is after the anchor", page.first().id > anchor)
            read += page
            anchor = page.last().id
        }
    }

    private fun token() = "coffer:v1:op:" + UUID.randomUUID()

    private fun blobFile(bytes: ByteArray): File {
        val hex = UUID.randomUUID().toString().replace("-", "").take(12)
        return File(scratch, "cfr_$hex.bin").apply { writeBytes(bytes) }
    }

    /** Collects [backend]'s events from now on; subscribed before this returns. */
    private class Recorder(scope: CoroutineScope, backend: StorageBackend) {
        private val channel = Channel<StorageEvent>(Channel.UNLIMITED)
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { backend.events.collect { channel.send(it) } }

        suspend fun newMessage(id: MessageId): StoredMessage {
            while (true) {
                val event = channel.receive()
                if (event is StorageEvent.NewMessage && event.message.id == id) return event.message
            }
        }

        suspend fun deletion(ids: Set<MessageId>) {
            val pending = ids.toMutableSet()
            while (pending.isNotEmpty()) {
                val event = channel.receive()
                if (event is StorageEvent.MessagesDeleted) pending -= event.ids
            }
        }
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
    }

    @Test
    fun textRoundTrip() = contract {
        val text = token()
        val sent = device.sendText(text)
        assertEquals(MessageContent.Text(text), sent.content)
        assertFalse(sent.isPinned)
        assertEquals(sent, device.message(sent.id))
        assertEquals(sent, otherDevice.message(sent.id))
        assertEquals(sent.id, device.historyBefore(null, 1).single().id)
    }

    @Test
    fun messagesCarryTheServerDate() = contract {
        val before = System.currentTimeMillis() / 1000
        val sent = device.sendText(token())
        val after = System.currentTimeMillis() / 1000
        // Unix seconds from the server: within a few minutes of this clock, whatever their skew.
        assertTrue("date ${sent.date} not near $before..$after", sent.date in before - CLOCK_SKEW..after + CLOCK_SKEW)
        assertEquals(sent.date, otherDevice.message(sent.id)!!.date)
        assertEquals(sent.date, otherDevice.historyBefore(null, 1).single { it.id == sent.id }.date)
    }

    @Test
    fun idsGrowInSendOrder() = contract {
        val ids = List(3) { device.sendText(token()).id }
        assertEquals(ids.sorted(), ids)
        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun textLimits() = contract {
        device.sendText("x".repeat(StorageBackend.MAX_TEXT))
        assertFails<StorageException> { device.sendText("x".repeat(StorageBackend.MAX_TEXT + 1)) }
        assertFails<StorageException> { device.sendText("") }
    }

    @Test
    fun blobRoundTripOnBothDevices() = contract {
        // Not a multiple of any part size TDLib uses, so the last part is partial.
        val bytes = Random(7).nextBytes(300_001)
        val file = blobFile(bytes)
        val caption = token()
        val progress = mutableListOf<Progress>()
        val sent = device.sendBlob(file, caption) { synchronized(progress) { progress += it } }

        assertEquals(MessageContent.Blob(caption, file.name, bytes.size.toLong()), sent.content)
        synchronized(progress) {
            assertTrue(progress.all { it.done in 0..it.total })
            assertEquals(progress.map { it.done }.sorted(), progress.map { it.done })
        }

        // The other device has no local copy, so this is a real download.
        for (backend in listOf(otherDevice, device)) {
            assertArrayEquals(bytes, backend.openBlob(sent.id).use { it.readBytes() })
            for ((offset, length) in listOf(0L to 1L, 131_071L to 70_000L, 299_990L to 11L)) {
                val read = backend.openBlob(sent.id, offset, length).use { it.readBytes() }
                assertArrayEquals("range $offset+$length", bytes.copyOfRange(offset.toInt(), (offset + length).toInt()), read)
            }
            val tail = backend.openBlob(sent.id, 299_996, StorageBackend.WHOLE).use { it.readBytes() }
            assertArrayEquals(bytes.copyOfRange(299_996, bytes.size), tail)
            assertEquals(0, backend.openBlob(sent.id, bytes.size.toLong()).use { it.readBytes() }.size)
            backend.releaseBlob(sent.id)
        }
    }

    @Test
    fun blobReadableAfterLocalCopiesAreGone() = contract {
        // The uploader deletes its encrypted temp file right after sending (SPEC §6); reading back
        // must then come from the server, not from a path that no longer exists.
        val bytes = Random(11).nextBytes(200_000)
        val file = blobFile(bytes)
        val sent = device.sendBlob(file, token())
        assertTrue(file.delete())
        device.releaseBlob(sent.id)
        assertArrayEquals(bytes, device.openBlob(sent.id).use { it.readBytes() })
        assertArrayEquals(bytes.copyOfRange(150_000, 150_100), device.openBlob(sent.id, 150_000, 100).use { it.readBytes() })
        device.releaseBlob(sent.id)
    }

    @Test
    fun localBytesFollowWhatIsDownloadedAndReleased() = contract {
        val bytes = Random(13).nextBytes(150_000)
        val sent = otherDevice.sendBlob(blobFile(bytes), token())
        // The device that did not send it holds nothing of it yet.
        assertEquals(0L, device.localBytes(sent.id))
        device.openBlob(sent.id).use { it.readBytes() }
        assertEquals(bytes.size.toLong(), device.localBytes(sent.id))
        device.releaseBlob(sent.id)
        assertEquals(0L, device.localBytes(sent.id))
    }

    @Test
    fun blobArgumentsAreChecked() = contract {
        val sent = device.sendBlob(blobFile(ByteArray(1000) { it.toByte() }), token())
        assertFails<IllegalArgumentException> { device.openBlob(sent.id, 1001) }
        assertFails<IllegalArgumentException> { device.openBlob(sent.id, 990, 11) }
        assertFails<IllegalArgumentException> { device.openBlob(sent.id, -1) }
        assertFails<IllegalArgumentException> { device.sendBlob(blobFile(ByteArray(0)), token()) }

        val text = device.sendText(token())
        assertFails<StorageException> { device.openBlob(text.id) }
    }

    @Test
    fun captionLimit() = contract {
        val longest = "coffer:v1:blob:" + "A".repeat(StorageBackend.MAX_CAPTION - 15)
        val sent = device.sendBlob(blobFile(ByteArray(64) { 1 }), longest)
        assertEquals(longest, (sent.content as MessageContent.Blob).caption)
        assertFails<StorageException> { device.sendBlob(blobFile(ByteArray(64) { 2 }), longest + "A") }
    }

    @Test
    fun historyPagesForward() = contract {
        val marker = device.sendText(token())
        val sent = List(7) { device.sendText(token()) }
        assertEquals(sent, device.allAfter(marker.id, 3))
    }

    @Test
    fun historyPagesBackward() = contract {
        val marker = device.sendText(token())
        val sent = List(7) { device.sendText(token()) }
        val read = mutableListOf<StoredMessage>()
        var before: MessageId? = null
        while (read.none { it.id <= marker.id }) {
            val page = device.historyBefore(before, 3)
            assertTrue("reached the start before the marker", page.isNotEmpty())
            assertEquals("page is newest first", page.sortedByDescending { it.id }, page)
            before?.let { assertTrue("page is before the anchor", page.first().id < it) }
            read += page
            before = page.last().id
        }
        assertEquals(listOf(marker) + sent, read.takeWhile { it.id >= marker.id }.reversed())
    }

    @Test
    fun historyAfterDeletedAnchor() = contract {
        val anchor = device.sendText(token())
        val newer = List(2) { device.sendText(token()) }
        device.delete(listOf(anchor.id))
        assertEquals(newer, device.allAfter(anchor.id, 10))
    }

    @Test
    fun newMessagesReachEveryDevice() = contract {
        val here = Recorder(this, device)
        val there = Recorder(this, otherDevice)

        val text = device.sendText(token())
        val blob = device.sendBlob(blobFile(ByteArray(5000) { 3 }), token())
        for (recorder in listOf(here, there)) {
            assertEquals(text, recorder.newMessage(text.id))
            assertEquals(blob, recorder.newMessage(blob.id))
        }
        here.job.cancel()
        there.job.cancel()
    }

    @Test
    fun deletionReachesEveryDevice() = contract {
        val a = device.sendText(token())
        val b = device.sendBlob(blobFile(ByteArray(100) { 4 }), token())
        val here = Recorder(this, device)
        val there = Recorder(this, otherDevice)

        device.delete(listOf(a.id, b.id))
        here.deletion(setOf(a.id, b.id))
        there.deletion(setOf(a.id, b.id))
        assertNull(device.message(a.id))
        assertNull(otherDevice.message(b.id))
        // Already gone: ignored.
        device.delete(listOf(a.id))
        here.job.cancel()
        there.job.cancel()
    }

    @Test
    fun pinning() = contract {
        val sent = device.sendText(token())
        device.pin(sent.id)
        val pinned = device.pinnedMessages()
        assertEquals(sent.id, pinned.first().id)
        assertTrue(pinned.all { it.isPinned })
        assertEquals(pinned.sortedByDescending { it.id }, pinned)
        assertTrue(otherDevice.message(sent.id)!!.isPinned)

        device.unpin(sent.id)
        assertTrue(device.pinnedMessages().none { it.id == sent.id })
        assertFalse(device.message(sent.id)!!.isPinned)
    }

    @Test
    fun missingMessageIsNull() = contract {
        val sent = device.sendText(token())
        assertNull(device.message(MessageId(sent.id.value + (1000L shl 20))))
    }

    private companion object {
        const val CLOCK_SKEW = 600L
    }
}
