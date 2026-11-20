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

    private fun parts() = cloud.allMessages().filter { it.content is MessageContent.Blob }.map { it.id }.toSet()

    @Test
    fun aDeleteIsOneBatchWhosePartsGoOnlyOnceItsMessageIsInTelegram() = runTest {
        val here = device()
        val trip = here.ops.createFolder("Trip", null)
        val day = here.ops.createFolder("Day 1", trip)
        here.indexer.catchUp()
        val inside = (1..3).map { here.upload("p$it.jpg", BytesSource(byteArrayOf(it.toByte())), folderId = day) }
        val loose = here.upload("note.txt", BytesSource("n".toByteArray()))
        val kept = here.upload("kept.txt", BytesSource("k".toByteArray()))
        here.indexer.catchUp()
        val keptParts = here.index.parts(kept).values.toSet()
        val doomedParts = (inside + loose).flatMap { here.index.parts(it).values }.toSet()
        val textsBefore = texts().size

        here.queued().deleteMany(here.tree(), listOf(trip), listOf(loose), partsOf = { here.index.parts(it).values })
        // Nothing gone yet: queued, files first, then the folders deepest first.
        assertEquals(textsBefore, texts().size)
        assertEquals(doomedParts + keptParts, parts())
        val queued = here.index.ops().single()
        assertEquals(OpKind.DELETE.name, queued.kind)
        assertEquals(6, queued.opCount)
        assertEquals((inside + loose).toSet(), queued.deletedFiles.toSet())
        assertEquals(listOf(day, trip), queued.deletedFolders)
        assertEquals(doomedParts, queued.parts.toSet())
        val ops = here.keys.meta.decodeOpPack(Captions.parse(queued.caption)!!)!!
        assertEquals(4, ops.takeWhile { it is Op.FileDelete }.size)

        // Telegram has the message, then the connection drops before the parts go.
        var refuse = true
        val flaky = object : me.esdex.coffer.core.storage.StorageBackend by here.storage {
            override suspend fun delete(ids: Collection<me.esdex.coffer.core.storage.MessageId>) {
                if (refuse) throw me.esdex.coffer.core.storage.StorageException("connection lost")
                here.storage.delete(ids)
            }
        }
        assertThrows(me.esdex.coffer.core.storage.StorageException::class.java) {
            kotlinx.coroutines.runBlocking { OpQueue(here.index).drain(flaky, pause = {}) }
        }
        assertEquals(textsBefore + 1, texts().size)
        assertEquals(doomedParts + keptParts, parts())
        assertTrue(here.index.ops().single().sent)
        // Deleted from the moment the message went: the parts going later is not damage.
        assertTrue((inside + loose).all { here.index.isDeleted(it) })

        // Again: the parts go, the message is not sent a second time.
        refuse = false
        OpQueue(here.index).drain(flaky, pause = {})
        assertEquals(textsBefore + 1, texts().size)
        assertEquals(keptParts, parts())
        assertTrue(here.index.ops().isEmpty())
        here.indexer.catchUp()
        assertEquals(setOf(Triple("kept.txt", null, me.esdex.coffer.core.index.FileStatus.READY)), here.view().files)
        assertTrue(here.view().folders.isEmpty())
        val fresh = device()
        fresh.indexer.rescan()
        assertEquals(here.view(), fresh.view())
    }

    @Test
    fun aLongDeleteSplitsIntoPacksEachWithItsOwnFilesParts() = runTest {
        val here = device()
        val files = (1..60).map { here.upload("f$it.txt", BytesSource(byteArrayOf(it.toByte()))) }
        val own = files.last()
        here.indexer.catchUp()
        here.queued().deleteMany(here.tree(), emptyList(), files, partsOf = { here.index.parts(it).values }) { it == own }
        val queued = here.index.ops()
        assertEquals(listOf(49, 10), queued.map { it.opCount })
        assertEquals(listOf(59, 59), queued.map { it.batchSize })
        // A file dealt with by the caller, as an upload of this device's would be, is left out.
        assertTrue(queued.none { own in it.deletedFiles })
        for (message in queued) {
            assertEquals(message.deletedFiles.flatMap { here.index.parts(it).values }.toSet(), message.parts.toSet())
        }
        OpQueue(here.index).drain(here.storage, pause = {})
        here.indexer.catchUp()
        assertEquals(setOf("f60.txt"), here.view().files.map { it.first }.toSet())
        assertEquals(here.index.parts(own).values.toSet(), parts())
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

    @Test
    fun aDeleteTakesWhatTheFolderHeldWhenItWasDeletedNotWhatItHoldsWhenItGoes() = runTest {
        // Audit 3, Q3-1: a merge into a folder during its Undo went with the folder.
        val here = device()
        val docs = here.ops.createFolder("Docs", null)
        here.indexer.catchUp()
        val old = here.upload("old.txt", BytesSource("o".toByteArray()), folderId = docs)
        val merged = here.upload("merged.txt", BytesSource("m".toByteArray()))
        here.indexer.catchUp()
        val (folders, files) = VaultOps.contents(here.tree(), listOf(docs), emptyList())
        assertEquals(listOf(docs) to listOf(old), folders to files)
        // Moved in while the delete waits for its Undo to pass.
        here.ops.moveFile(merged, docs)
        here.indexer.catchUp()

        here.queued().deleteMany(here.tree(), folders, files, partsOf = { here.index.parts(it).values }, expand = false)
        OpQueue(here.index).drain(here.storage, pause = {})
        here.indexer.catchUp()
        assertEquals(View(emptySet(), setOf(Triple("merged.txt", null, me.esdex.coffer.core.index.FileStatus.READY))), here.view())
    }

    @Test
    fun namesAreSettledWithoutWhatIsGoing() = runTest {
        val here = device()
        val docs = here.ops.createFolder("Docs", null)
        val inner = here.ops.createFolder("Inner", docs)
        val other = here.ops.createFolder("Other", null)
        here.indexer.catchUp()
        val a = here.upload("a.txt", BytesSource("a".toByteArray()), folderId = inner)
        val b = here.upload("b.txt", BytesSource("b".toByteArray()))
        val c = here.upload("c.txt", BytesSource("c".toByteArray()), folderId = other)
        here.indexer.catchUp()
        val tree = here.tree()
        assertEquals(tree.folders.keys, tree.without(emptySet()).folders.keys)
        val left = tree.without(setOf(docs, b))
        assertEquals(setOf(other), left.folders.keys)
        assertEquals(setOf(c), left.files.keys)
        assertTrue(a in tree.files)
    }
}
