package me.esdex.coffer.core.sync

import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.Op
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
 * Operations queued for the transfer queue to send (2026-11-19), in packs (SPEC §4.6): a folder of
 * folders brought in from the phone stalled for minutes on FLOOD_WAIT with nothing on screen, and was
 * lost if the app closed; Telegram lets about 20 texts into a chat before such a pause.
 */
class OpQueueTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val master = MasterKeys.generate(sodium)
    private val cloud = FakeCloud()

    private fun device() = Device(cloud, keysOf(master), temp.newFolder())

    private fun Device.queued() = VaultOps(storage, keys, OpQueue(index)) { 1_762_531_200_000 }

    private fun texts() = cloud.allMessages().filter { it.content is MessageContent.Text }.map { (it.content as MessageContent.Text).text }

    /** A 255-byte name, the longest: 9 such folders in the root fill a pack, 8 in a folder (SPEC §4.6). */
    private fun longName(n: Int) = "F%02d".format(n) + "я".repeat(126)

    @Test
    fun foldersBroughtInAreQueuedAsOneBatchInOneMessageAndShowOnlyOnceSent() = runTest {
        val here = device()
        val ids = here.queued().createFolders { create ->
            val trip = create("Trip", null)
            listOf(trip, create("Day 1", trip), create("Day 2", trip))
        }
        // Their ids are known at once, so files can be queued into them, but nothing went out yet.
        assertEquals(3, ids.toSet().size)
        assertEquals(0, texts().size)
        val queued = here.index.ops().single()
        assertEquals(OpKind.CREATE_FOLDERS.name, queued.kind)
        assertEquals(3, queued.opCount)
        assertEquals(3, queued.batchSize)
        here.indexer.catchUp()
        assertTrue(here.view().folders.isEmpty())

        var pauses = 0
        OpQueue(here.index).drain(here.storage, pause = { pauses++ })
        assertEquals(1, texts().size)
        assertEquals(0, pauses)
        assertTrue(here.index.ops().isEmpty())
        here.indexer.catchUp()
        assertEquals(setOf("Trip" to null, "Day 1" to "Trip", "Day 2" to "Trip"), here.view().folders)
    }

    @Test
    fun aFloodWaitLeavesTheRestQueuedInOrderAndNothingGoesTwice() = runTest {
        val here = device()
        here.queued().createFolders { create -> (1..20).forEach { create(longName(it), null) } }
        val queued = here.index.ops()
        assertEquals(listOf(9, 9, 2), queued.map { it.opCount })
        assertEquals(listOf(20, 20, 20), queued.map { it.batchSize })
        val queue = OpQueue(here.index)
        var pauses = 0
        val left = mutableListOf<Int>()
        val failure = assertThrows(FloodWaitException::class.java) {
            kotlinx.coroutines.runBlocking {
                queue.drain(here.storage, pause = { if (++pauses == 1) cloud.failNextCall(FloodWaitException(138)) }) { sent, rest ->
                    left += rest.size
                    assertEquals(9, sent.opCount)
                }
            }
        }
        assertEquals(138, failure.seconds)
        assertEquals(1, texts().size)
        assertEquals(listOf(2), left)
        assertEquals(2, here.index.ops().size)

        // The app closed meanwhile: a new queue on the same index goes on from there.
        OpQueue(here.index).drain(here.storage, pause = {})
        assertEquals(3, texts().size)
        here.indexer.catchUp()
        assertEquals((1..20).map { longName(it) to null }.toSet(), here.view().folders)
        // In the order they were made.
        val order = texts().flatMap { here.keys.meta.decodeOpPack(Captions.parse(it)!!)!! }.map { (it as Op.FolderCreate).name }
        assertEquals((1..20).map { longName(it) }, order)
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
        assertEquals(listOf(1, 1), batches.map { it.size })
        assertEquals(listOf(2, 2), batches.map { it.single().opCount })
        assertEquals(listOf(OpKind.MOVE.name), here.index.ops().map { it.kind }.distinct())
    }

    @Test
    fun laterInAPackWinsAndAPackSentAgainIsNotAppliedAgain() = runTest {
        val here = device()
        val there = device()
        val x = Id.random(sodium)
        val y = Id.random(sodium)
        fun newId() = Id.random(sodium)
        val pack = here.keys.meta.encodeOpPack(
            listOf(
                Op.FolderCreate(newId(), 1, x, null, "A"),
                Op.FolderRename(newId(), 1, x, "B"),
                // A rename before its folder is made is about an unknown folder, and ignored (SPEC §5.2).
                Op.FolderRename(newId(), 1, y, "ignored"),
                Op.FolderCreate(newId(), 1, y, x, "C"),
            ),
        )
        here.storage.sendText(pack)
        there.indexer.catchUp()
        assertEquals(setOf("B" to null, "C" to "B"), there.view().folders)

        // Renamed after, then the same pack sent again by anyone: its op_ids give it away (audit 1, M4).
        here.storage.sendText(here.keys.meta.encodeOpPack(listOf(Op.FolderRename(newId(), 2, x, "D"))))
        here.storage.sendText(pack)
        there.indexer.catchUp()
        assertEquals(setOf("D" to null, "C" to "D"), there.view().folders)
        val fresh = device()
        fresh.indexer.rescan()
        assertEquals(setOf("D" to null, "C" to "D"), fresh.view().folders)
    }
}
