package me.esdex.coffer.core.sync

import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.crypto.Argon2Params
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.format.VaultHeader
import me.esdex.coffer.core.crypto.format.VaultHeaderCodec
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Deleting a vault (SPEC §5.9) takes all of it and nothing else, the headers last. What stays is
 * worked out here by hand from what each test sent, not by the code's own sorting.
 */
class VaultDeletionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val cloud = FakeCloud(pageCap = 7)
    private val ours = MasterKeys.generate(sodium)
    private val theirs = MasterKeys.generate(sodium)
    private val ourId = masterId(1)
    private val theirId = masterId(2)

    private fun masterId(fill: Int) = Bytes.of(ByteArray(16) { fill.toByte() })

    private fun header(masterId: Bytes) = VaultHeaderCodec.encode(
        VaultHeader(1, Bytes.of(ByteArray(16)), Argon2Params(1, 8L * 1024 * 1024), Bytes.of(ByteArray(72)), masterId),
    )

    private fun device(master: me.esdex.coffer.core.crypto.SecretKey) = Device(cloud, keysOf(master), temp.newFolder())

    private fun remaining(): Set<MessageId> = cloud.allMessages().map { it.id }.toSet()

    /** Records each delete request, to see what went in which order and how many at a time. */
    private class Recording(private val inner: StorageBackend) : StorageBackend by inner {
        val requests = mutableListOf<List<MessageId>>()
        var failAt: Int? = null
        var floodsLeft = 0

        override suspend fun delete(ids: Collection<MessageId>) {
            if (floodsLeft > 0) {
                floodsLeft--
                throw FloodWaitException(0)
            }
            if (requests.size == failAt) throw StorageException("Telegram is not ready")
            requests += ids.toList()
            inner.delete(ids)
        }
    }

    // Our messages all come before the other vault's in [scene]; this marks where they end.
    private var lastOfOurs = MessageId(0)

    /**
     * Saved Messages with this vault (two files, folder operations, a snapshot, two headers as a half
     * done password change leaves), then another vault (its header, a file, an operation) and the user's
     * own messages, one of them dressed as a Coffer record that no key opens. Returns what must stay.
     */
    private suspend fun scene(): Set<MessageId> {
        val here = device(ours)
        val storage = here.storage
        storage.sendText(header(ourId))
        here.upload("a.bin", BytesSource(ByteArray(3000) { it.toByte() }))
        here.upload("b.bin", BytesSource(ByteArray(10)))
        val folder = Id.random(sodium)
        here.sendOp(Op.FolderCreate(Id.random(sodium), 1, folder, null, "Trip"))
        here.sendOp(Op.FolderRename(Id.random(sodium), 2, folder, "Trip 2025"))
        here.indexer.catchUp()
        assertTrue(here.snapshotter.snapshotIfNeeded())
        lastOfOurs = storage.sendText(header(ourId)).id
        val ourCount = cloud.allMessages().size

        val other = device(theirs)
        other.storage.sendText(header(theirId))
        other.upload("theirs.bin", BytesSource(ByteArray(20)))
        other.sendOp(Op.FolderCreate(Id.random(sodium), 3, Id.random(sodium), null, "Theirs"))
        storage.sendText("buy milk")
        storage.sendText("coffer:v2:op:not-really")
        storage.sendBlob(temp.newFile("photo.jpg").apply { writeBytes(ByteArray(5)) }, "holiday")
        // By hand: 2 headers + 2 parts + 2 operations + 1 snapshot of ours.
        assertEquals(7, ourCount)
        return cloud.allMessages().filter { it.id > lastOfOurs }.map { it.id }.toSet()
    }

    @Test
    fun allOfThisVaultGoesAndNothingElse() = runBlocking {
        val keep = scene()
        val storage = Recording(cloud.device())
        val selection = VaultDeletion(storage, keysOf(ours), ourId).run()
        assertEquals(7, selection.total)
        assertEquals(keep, remaining())
        // The other vault's header, part and operation, and three messages of the user's own.
        assertEquals(6, keep.size)
    }

    @Test
    fun theHeadersGoLast() = runBlocking {
        scene()
        val headers = cloud.allMessages().filter { (it.content as? MessageContent.Text)?.text == header(ourId) }.map { it.id }.toSet()
        assertEquals(2, headers.size)
        val storage = Recording(cloud.device())
        VaultDeletion(storage, keysOf(ours), ourId).run()
        val order = storage.requests.flatten()
        assertEquals(headers, order.takeLast(2).toSet())
        assertTrue(order.dropLast(2).none { it in headers })
    }

    @Test
    fun cutShortItLeavesAVaultThatOpensAndRunsAgainToTheEnd() = runBlocking {
        val keep = scene()
        val storage = Recording(cloud.device()).apply { failAt = 0 }
        try {
            VaultDeletion(storage, keysOf(ours), ourId).run()
            fail("the failure should end the run")
        } catch (_: StorageException) {
        }
        // Nothing went, the headers least of all: the vault still opens.
        assertTrue(cloud.allMessages().count { (it.content as? MessageContent.Text)?.text == header(ourId) } == 2)
        storage.failAt = null
        VaultDeletion(storage, keysOf(ours), ourId).run()
        assertEquals(keep, remaining())
    }

    @Test
    fun aFloodWaitIsWaitedOutAndSaid() = runBlocking {
        val keep = scene()
        val storage = Recording(cloud.device()).apply { floodsLeft = 2 }
        val waits = mutableListOf<Int>()
        VaultDeletion(storage, keysOf(ours), ourId).run(onWait = { waits += it })
        assertEquals(listOf(0, 0), waits)
        assertEquals(keep, remaining())
    }

    @Test
    fun aHundredAtATimeAndProgressToTheEnd() = runBlocking {
        val here = device(ours)
        here.storage.sendText(header(ourId))
        repeat(230) { here.sendOp(Op.FolderCreate(Id.random(sodium), it.toLong(), Id.random(sodium), null, "F$it")) }
        val storage = Recording(cloud.device())
        val progress = mutableListOf<Pair<Int, Int>>()
        VaultDeletion(storage, keysOf(ours), ourId).run(onDeleted = { done, total -> progress += done to total })
        // 230 operations in three requests, then the header in one of its own.
        assertEquals(listOf(100, 100, 30, 1), storage.requests.map { it.size })
        assertEquals(listOf(0 to 231, 100 to 231, 200 to 231, 230 to 231, 231 to 231), progress)
        assertTrue(cloud.allMessages().isEmpty())
    }
}
