package me.esdex.coffer.core.sync

import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.MessageContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Operations queued for the transfer queue to send (2026-11-19): a folder of folders brought in from
 * the phone stalled for minutes on FLOOD_WAIT with nothing on screen, and was lost if the app closed.
 */
class OpQueueTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val master = MasterKeys.generate(sodium)
    private val cloud = FakeCloud()

    private fun device() = Device(cloud, keysOf(master), temp.newFolder())

    private fun Device.queued() = VaultOps(storage, keys, OpQueue(index)) { 1_762_531_200_000 }

    private fun opsSent() = cloud.allMessages().count { it.content is MessageContent.Text }

    @Test
    fun foldersBroughtInAreQueuedAsOneBatchAndShowOnlyOnceSent() = runTest {
        val here = device()
        val ids = here.queued().createFolders { create ->
            val trip = create("Trip", null)
            listOf(trip, create("Day 1", trip), create("Day 2", trip))
        }
        // Their ids are known at once, so files can be queued into them, but nothing went out yet.
        assertEquals(3, ids.toSet().size)
        assertEquals(0, opsSent())
        val queued = here.index.ops()
        assertEquals(listOf(OpKind.CREATE_FOLDERS.name), queued.map { it.kind }.distinct())
        assertEquals(1, queued.map { it.batch }.distinct().size)
        assertEquals(listOf(3, 3, 3), queued.map { it.batchSize })
        here.indexer.catchUp()
        assertTrue(here.view().folders.isEmpty())

        var pauses = 0
        OpQueue(here.index).drain(here.storage, pause = { pauses++ })
        assertEquals(3, opsSent())
        // Between two operations only, not after the last.
        assertEquals(2, pauses)
        assertTrue(here.index.ops().isEmpty())
        here.indexer.catchUp()
        assertEquals(setOf("Trip" to null, "Day 1" to "Trip", "Day 2" to "Trip"), here.view().folders)
    }

    @Test
    fun aFloodWaitLeavesTheRestQueuedInOrderAndNothingGoesTwice() = runTest {
        val here = device()
        here.queued().createFolders { create -> (1..5).forEach { create("F$it", null) } }
        var sent = 0
        val queue = OpQueue(here.index)
        val failure = assertThrows(FloodWaitException::class.java) {
            kotlinx.coroutines.runBlocking {
                queue.drain(here.storage, pause = { if (++sent == 2) cloud.failNextCall(FloodWaitException(138)) })
            }
        }
        assertEquals(138, failure.seconds)
        assertEquals(2, opsSent())
        assertEquals(3, here.index.ops().size)

        // The app closed meanwhile: a new queue on the same index goes on from there.
        OpQueue(here.index).drain(here.storage, pause = {})
        assertEquals(5, opsSent())
        here.indexer.catchUp()
        assertEquals((1..5).map { "F$it" to null }.toSet(), here.view().folders)
        // In the order they were made.
        val order = cloud.allMessages().filter { it.content is MessageContent.Text }.map { message ->
            here.keys.meta.decodeOp(me.esdex.coffer.core.crypto.format.Captions.parse((message.content as MessageContent.Text).text)!!)
        }.map { (it as me.esdex.coffer.core.crypto.format.Op.FolderCreate).name }
        assertEquals((1..5).map { "F$it" }, order)
    }

    @Test
    fun movingSeveralIsOneBatchAndCannotGoIntoItself() = runTest {
        val here = device()
        val docs = here.ops.createFolder("Docs", null)
        val inner = here.ops.createFolder("Inner", docs)
        val other = here.ops.createFolder("Other", null)
        here.indexer.catchUp()
        val tree = VaultTree.of(here.index, here.keys)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { here.queued().moveMany(tree, listOf(docs), emptyList(), inner) }
        }
        assertTrue(here.index.ops().isEmpty())
        here.queued().moveMany(tree, listOf(docs, other), emptyList(), null)
        here.queued().moveMany(tree, listOf(inner, other), emptyList(), docs)
        val batches = here.index.ops().groupBy { it.batch }.values
        assertEquals(listOf(2, 2), batches.map { it.size })
        assertEquals(listOf(OpKind.MOVE.name), here.index.ops().map { it.kind }.distinct())
    }
}
