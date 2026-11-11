package me.esdex.coffer.feature.browser.transfer

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.IndexedFile
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.ui.theme.CachePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * "Cache on this phone": the queue that outlives the process, what counts as whole on the phone, and
 * the offer made before it starts. Part sizes are worked out here by hand from the format (SPEC §4.1:
 * 1 MiB chunks, each 24 bytes of nonce and 16 of tag more, 512 to a part), not by the code under test.
 */
class PrefetchQueueTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun id(n: Int) = Id.of(ByteArray(16) { if (it == 0) n.toByte() else 0 })

    private var next = 0
    private fun queue(file: java.io.File = temp.root.resolve("queue")) = PrefetchQueue(file) { "q${++next}" }

    @Test
    fun filesAreQueuedOnceInTheOrderGiven() {
        val queue = queue()
        val added = queue.add(listOf(id(1), id(2), id(1)))
        assertEquals(listOf(id(1), id(2)), added.map { it.fileId })
        assertEquals(listOf(id(3)), queue.add(listOf(id(2), id(3))).map { it.fileId })
        assertEquals(listOf(id(1), id(2), id(3)), queue.items.value.map { it.fileId })
        assertEquals(id(2), queue.find(added[1].id)?.fileId)
    }

    @Test
    fun theQueueOutlivesTheProcess() {
        val file = temp.root.resolve("queue")
        val first = queue(file)
        val (a, b) = first.add(listOf(id(1), id(2)))
        first.remove(a.id)
        val second = queue(file)
        assertEquals(listOf(b), second.items.value)
        assertNull(second.find(a.id))
        second.clear()
        assertTrue(queue(file).items.value.isEmpty())
    }

    @Test
    fun aDamagedLineIsDroppedAlone() {
        val file = temp.root.resolve("queue")
        // No file id; an empty id; a file id too short; a field too many.
        file.writeText("good AQAAAAAAAAAAAAAAAAAAAA\nno-id\n AQAAAAAAAAAAAAAAAAAAAA\nx AQAA\ny AQAAAAAAAAAAAAAAAAAAAA extra\n")
        assertEquals(listOf(PrefetchQueue.Item("good", id(1))), queue(file).items.value)
    }

    // A part of 512 full chunks, and a last part of 5 bytes.
    private val bigPart = 512L * (1_048_576 + 24 + 16)
    private val smallPart = 5L + 24 + 16
    private val twoParts = 512L * 1_048_576 + 5

    private fun file(n: Int, plainSize: Long, partCount: Int, parts: Map<Int, MessageId>, status: FileStatus = FileStatus.READY) =
        IndexedFile(id(n), plainSize, partCount, ByteArray(1), status, parts)

    private val p0 = MessageId(100)
    private val p1 = MessageId(101)

    @Test
    fun wholeMeansEveryPartAtItsFullSize() {
        val f = file(1, twoParts, 2, mapOf(0 to p0, 1 to p1))
        assertEquals(bigPart + smallPart, f.encryptedSize())
        assertTrue(f.isCached(mapOf(p0 to bigPart, p1 to smallPart)))
        assertEquals(0L, f.missingBytes(mapOf(p0 to bigPart, p1 to smallPart)))
        // One byte short of the last part: that part is still to come, all of it.
        assertFalse(f.isCached(mapOf(p0 to bigPart, p1 to smallPart - 1)))
        assertEquals(smallPart, f.missingBytes(mapOf(p0 to bigPart, p1 to smallPart - 1)))
        assertEquals(bigPart + smallPart, f.missingBytes(emptyMap()))
    }

    @Test
    fun aPaddedFileIsWholeOnlyWithItsPadding() {
        // 1000 bytes padded to 1024 (SPEC §4.5): Telegram holds one chunk of 1024, not of 1000.
        val padded = IndexedFile(id(1), 1000, 1, ByteArray(1), FileStatus.READY, mapOf(0 to p0), padded = true)
        assertEquals(1024L + 24 + 16, padded.encryptedSize())
        assertFalse(padded.isCached(mapOf(p0 to 1000L + 24 + 16)))
        assertTrue(padded.isCached(mapOf(p0 to 1024L + 24 + 16)))
    }

    @Test
    fun aFileNotWholeInSavedMessagesIsNeverCached() {
        val held = mapOf(p0 to bigPart, p1 to smallPart)
        assertFalse(file(1, twoParts, 2, mapOf(0 to p0)).isCached(held))
        assertFalse(file(1, twoParts, 2, mapOf(0 to p0, 1 to p1), FileStatus.DAMAGED).isCached(held))
    }

    private val gib = 1024L * 1024 * 1024

    private fun offer(total: Long, missing: Long = total, free: Long = 100 * gib, prefs: CachePrefs = CachePrefs()) =
        CacheOffer(listOf(id(1)), total, missing, free, prefs)

    @Test
    fun overTheLimitTheSmallestLimitThatHoldsItIsOffered() {
        assertTrue(offer(2 * gib).fits)
        assertNull(offer(2 * gib).raiseTo)
        assertFalse(offer(2 * gib + 1).fits)
        assertEquals(5120, offer(2 * gib + 1).raiseTo)
        assertEquals(1024, offer(gib, prefs = CachePrefs(limitMb = 500)).raiseTo)
        // More than the largest limit: nothing to offer, only a warning.
        assertFalse(offer(10 * gib + 1).fits)
        assertNull(offer(10 * gib + 1).raiseTo)
    }

    @Test
    fun theRoomNeededIsWhatIsMissingAndAMargin() {
        val margin = 64L * 1024 * 1024
        assertTrue(offer(5 * gib, missing = gib, free = gib + margin).enoughSpace)
        assertFalse(offer(5 * gib, missing = gib, free = gib + margin - 1).enoughSpace)
    }

    @Test
    fun theOfferLeavesOutWhatIsNotWholeAndCountsWhatIsCached() {
        val cached = file(1, twoParts, 2, mapOf(0 to p0, 1 to p1))
        val fresh = file(2, 10, 1, mapOf(0 to MessageId(200)))
        val uploading = file(3, 10, 1, mapOf(0 to MessageId(300)), FileStatus.UPLOADING)
        val partial = file(4, twoParts, 2, mapOf(0 to MessageId(400)), FileStatus.INCOMPLETE)
        val offer = CacheOffer.of(listOf(cached, fresh, uploading, partial), mapOf(p0 to bigPart, p1 to smallPart), gib, CachePrefs())
        assertEquals(listOf(id(1), id(2)), offer.fileIds)
        assertEquals(bigPart + smallPart + 10 + 40, offer.totalBytes)
        assertEquals(10L + 40, offer.missingBytes)
        assertFalse(offer.alreadyCached)
        assertTrue(CacheOffer.of(listOf(cached), mapOf(p0 to bigPart, p1 to smallPart), gib, CachePrefs()).alreadyCached)
    }
}
