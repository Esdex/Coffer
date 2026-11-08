package me.esdex.coffer.core.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.format.RecordKind
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The operation log (SPEC §4.3, §5.2) between devices sharing one Saved Messages. Expected trees are
 * written out by hand from the rules, not computed by the code under test.
 */
class OpLogTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val master = MasterKeys.generate(sodium)
    private val cloud = FakeCloud(pageCap = 5)

    private fun device() = Device(cloud, keysOf(master), temp.newFolder())

    private fun bytes(text: String) = BytesSource(text.toByteArray())

    /** A device that reads the whole history from scratch must see the same (SPEC §11: rescan). */
    private suspend fun assertRescanAgrees(expected: View) {
        val fresh = device()
        fresh.indexer.catchUp()
        assertEquals(expected, fresh.view())
    }

    private fun newId() = Id.random(sodium)

    @Test
    fun foldersRenamesAndMovesReachAnotherDevice() = runBlocking {
        val here = device()
        val docs = here.ops.createFolder("Docs", null)
        val tax = here.ops.createFolder("Tax", docs)
        here.indexer.catchUp()
        val fileId = here.upload("scan.pdf", bytes("pdf"), folderId = tax)
        here.ops.renameFile(fileId, "scan-2025.pdf")
        here.ops.moveFile(fileId, docs)
        here.ops.renameFolder(tax, "Taxes")

        val expected = View(
            folders = setOf("Docs" to null, "Taxes" to "Docs"),
            files = setOf(Triple("scan-2025.pdf", "Docs", FileStatus.READY)),
        )
        here.indexer.catchUp()
        assertEquals(expected, here.view())
        val there = device()
        there.indexer.catchUp()
        assertEquals(expected, there.view())
    }

    @Test
    fun uploadLandsInItsFolder() = runBlocking {
        val here = device()
        val inbox = here.ops.createFolder("Inbox", null)
        here.indexer.catchUp()
        val fileId = here.upload("a.txt", bytes("a"), folderId = inbox)
        val there = device()
        there.indexer.catchUp()
        assertEquals(inbox, there.meta(there.index.file(fileId)!!).folderId)
        assertEquals(listOf(fileId), there.tree().childFiles(inbox))
    }

    @Test
    fun laterMessageWinsWhenTwoDevicesRenameAtOnce() = runBlocking {
        val here = device()
        val there = device()
        val fileId = here.upload("draft.txt", bytes("x"))
        there.indexer.catchUp()
        // Neither has seen the other's rename when sending its own.
        here.ops.renameFile(fileId, "first.txt")
        there.ops.renameFile(fileId, "second.txt")
        here.indexer.catchUp()
        there.indexer.catchUp()
        val expected = View(emptySet(), setOf(Triple("second.txt", null, FileStatus.READY)))
        assertEquals(expected, here.view())
        assertEquals(expected, there.view())
        assertRescanAgrees(expected)
    }

    @Test
    fun deleteWinsOverLaterRenameAndMove() = runBlocking {
        val here = device()
        val there = device()
        val folder = here.ops.createFolder("Old", null)
        val fileId = here.upload("old.txt", bytes("x"))
        there.indexer.catchUp()
        here.deleter.delete(here.keys, fileId, now = 1)
        here.ops.deleteFolder(here.tree(), folder) { error("empty folder") }
        // Sent by a device that has not heard of either deletion.
        there.ops.renameFile(fileId, "renamed.txt")
        there.ops.moveFile(fileId, null)
        there.ops.renameFolder(folder, "Renamed")
        there.ops.moveFolder(there.tree(), folder, null)
        there.sendOp(Op.FolderCreate(newId(), 1, folder, null, "Reborn"))

        here.indexer.catchUp()
        there.indexer.catchUp()
        val expected = View(emptySet(), emptySet())
        assertEquals(expected, here.view())
        assertEquals(expected, there.view())
        assertTrue(there.index.isDeleted(fileId))
        assertTrue(there.index.isFolderDeleted(folder))
        assertRescanAgrees(expected)
    }

    @Test
    fun orphansShowInTheRoot() = runBlocking {
        val here = device()
        val there = device()
        val shared = here.ops.createFolder("Shared", null)
        val fileId = here.upload("loose.txt", bytes("x"))
        there.indexer.catchUp()
        here.indexer.catchUp()

        // Here deletes the (empty) folder while there puts things into it.
        here.ops.deleteFolder(here.tree(), shared) { error("empty folder") }
        there.ops.moveFile(fileId, shared)
        there.ops.createFolder("Child", shared)
        there.upload("late.txt", bytes("y"), folderId = shared)

        here.indexer.catchUp()
        there.indexer.catchUp()
        val expected = View(
            folders = setOf("Child" to null),
            files = setOf(Triple("loose.txt", null, FileStatus.READY), Triple("late.txt", null, FileStatus.READY)),
        )
        assertEquals(expected, here.view())
        assertEquals(expected, there.view())
        assertRescanAgrees(expected)
    }

    @Test
    fun crossedFolderMovesCannotMakeALoop() = runBlocking {
        val here = device()
        val there = device()
        val a = here.ops.createFolder("A", null)
        val b = here.ops.createFolder("B", null)
        here.indexer.catchUp()
        there.indexer.catchUp()
        // Each device moves one into the other, unaware of the other move. The later one would close a loop.
        here.ops.moveFolder(here.tree(), a, b)
        there.ops.moveFolder(there.tree(), b, a)
        here.indexer.catchUp()
        there.indexer.catchUp()
        val expected = View(setOf("A" to "B", "B" to null), emptySet())
        assertEquals(expected, here.view())
        assertEquals(expected, there.view())
        assertRescanAgrees(expected)
    }

    @Test
    fun movingAFolderUnderItselfIsIgnored() = runBlocking {
        val here = device()
        val a = here.ops.createFolder("A", null)
        val b = here.ops.createFolder("B", a)
        here.sendOp(Op.FolderMove(newId(), 1, a, a))
        here.sendOp(Op.FolderMove(newId(), 1, a, b))
        here.indexer.catchUp()
        assertEquals(View(setOf("A" to null, "B" to "A"), emptySet()), here.view())
    }

    @Test
    fun localMoveIntoOwnSubtreeIsRefusedBeforeSending() = runBlocking {
        val here = device()
        val a = here.ops.createFolder("A", null)
        val b = here.ops.createFolder("B", a)
        here.indexer.catchUp()
        val before = cloud.allMessages().size
        for (target in listOf(a, b)) {
            try {
                here.ops.moveFolder(here.tree(), a, target)
                error("move into own subtree was sent")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(before, cloud.allMessages().size)
    }

    @Test
    fun aFolderIdIsCreatedOnce() = runBlocking {
        val here = device()
        val other = here.ops.createFolder("Other", null)
        val id = newId()
        here.sendOp(Op.FolderCreate(newId(), 1, id, null, "First"))
        here.sendOp(Op.FolderCreate(newId(), 2, id, other, "Second"))
        here.indexer.catchUp()
        assertEquals(View(setOf("Other" to null, "First" to null), emptySet()), here.view())
    }

    @Test
    fun creationUnderAFolderThatTurnsOutToBeAChildGoesToRoot() = runBlocking {
        // Only a broken or hostile client sends this; the index must still stay a tree.
        val here = device()
        val a = newId()
        val b = newId()
        here.sendOp(Op.FolderCreate(newId(), 1, a, b, "A"))
        here.sendOp(Op.FolderCreate(newId(), 2, b, a, "B"))
        here.indexer.catchUp()
        assertEquals(View(setOf("A" to "B", "B" to null), emptySet()), here.view())
        assertRescanAgrees(here.view())
    }

    @Test
    fun operationsOnUnknownObjectsAreDropped() = runBlocking {
        val here = device()
        val ghostFile = newId()
        val ghostFolder = newId()
        here.ops.renameFile(ghostFile, "ghost.txt")
        here.ops.moveFile(ghostFile, null)
        here.ops.renameFolder(ghostFolder, "Ghost")
        here.sendOp(Op.FolderMove(newId(), 1, ghostFolder, null))
        here.indexer.catchUp()
        assertEquals(View(emptySet(), emptySet()), here.view())
        assertTrue(here.index.folders().isEmpty())
        // A folder created later under that id keeps its own name: the earlier rename lost by message order.
        here.sendOp(Op.FolderCreate(newId(), 2, ghostFolder, null, "Real"))
        here.indexer.catchUp()
        assertEquals(View(setOf("Real" to null), emptySet()), here.view())
    }

    @Test
    fun deletingAFolderTakesEverythingInsideDeepestFirst() = runBlocking {
        val here = device()
        val top = here.ops.createFolder("Top", null)
        val mid = here.ops.createFolder("Mid", top)
        val low = here.ops.createFolder("Low", mid)
        val keep = here.ops.createFolder("Keep", null)
        here.indexer.catchUp()
        val inside = listOf(
            here.upload("t.txt", bytes("t"), folderId = top),
            here.upload("m.txt", bytes("m"), folderId = mid),
            here.upload("l.txt", bytes("l"), folderId = low),
        )
        here.upload("k.txt", bytes("k"), folderId = keep)
        val lastBefore = cloud.allMessages().maxOf { it.id }

        here.ops.deleteFolder(here.tree(), top) { here.deleter.delete(here.keys, it, now = 3) }
        here.indexer.catchUp()
        val expected = View(setOf("Keep" to null), setOf(Triple("k.txt", "Keep", FileStatus.READY)))
        assertEquals(expected, here.view())
        assertRescanAgrees(expected)

        // Files go first, then folders from the bottom up.
        val sent = cloud.allMessages().filter { it.id > lastBefore }.mapNotNull { message ->
            val text = (message.content as? MessageContent.Text)?.text ?: return@mapNotNull null
            here.keys.meta.decodeOp(Captions.parse(text)!!.also { assertEquals(RecordKind.OP, it.kind) })
        }
        assertEquals(inside.toSet(), sent.filterIsInstance<Op.FileDelete>().map { it.fileId }.toSet())
        assertEquals(listOf(low, mid, top), sent.filterIsInstance<Op.FolderDelete>().map { it.folderId })
        assertTrue(sent.indexOfFirst { it is Op.FolderDelete } > sent.indexOfLast { it is Op.FileDelete })
        // Remaining blobs: only the kept file's part.
        assertEquals(1, cloud.allMessages().count { it.content is MessageContent.Blob })
    }

    @Test
    fun renameSentBeforePartZeroLosesToIt() = runBlocking {
        // Part 0 is the file's creation; a rename older than it loses by message order on every device.
        // The browser therefore offers no rename or move while a file is still uploading.
        val here = device()
        val source = bytes("slow")
        val prepared = here.prepare("before.txt", source)
        val fileId = prepared.upload.fileId!!
        here.ops.renameFile(fileId, "after.txt")
        here.indexer.catchUp()
        assertEquals(FileStatus.UPLOADING, here.index.file(fileId)!!.status)
        here.runner.run(prepared.upload, prepared.keys, source)
        here.indexer.catchUp()
        val expected = View(emptySet(), setOf(Triple("before.txt", null, FileStatus.READY)))
        assertEquals(expected, here.view())
        assertRescanAgrees(expected)
    }

    @Test
    fun renameOfAPartlyUploadedFileKeepsTheUploadersStatus() = runBlocking {
        val here = device()
        val there = device()
        val size = 512L * 1024 * 1024 + 5
        val source = PatternSource(size)
        var transfers = 0
        cloud.onTransfer = { if (++transfers == 2) throw StorageException("connection lost") }
        val prepared = here.prepare("before.bin", source)
        val fileId = prepared.upload.fileId!!
        try {
            here.runner.run(prepared.upload, prepared.keys, source)
            error("the second part should have failed")
        } catch (_: StorageException) {
        }
        here.ops.renameFile(fileId, "after.bin")
        here.indexer.catchUp()
        there.indexer.catchUp()
        assertEquals(FileStatus.UPLOADING, here.index.file(fileId)!!.status)
        assertEquals(View(emptySet(), setOf(Triple("after.bin", null, FileStatus.INCOMPLETE))), there.view())

        here.runner.run(here.index.uploads().single(), prepared.keys, source)
        here.indexer.catchUp()
        there.indexer.catchUp()
        val expected = View(emptySet(), setOf(Triple("after.bin", null, FileStatus.READY)))
        assertEquals(expected, here.view())
        assertEquals(expected, there.view())
    }

    @Test
    fun operationsReachADeviceFollowingLive() {
        val here = device()
        val there = device()
        following(there) {
            val folder = here.ops.createFolder("Live", null)
            val fileId = here.upload("live.txt", bytes("x"))
            here.ops.moveFile(fileId, folder)
            withTimeout(10_000) {
                there.index.observeFiles().first { files ->
                    files.any { f -> there.keys.local.openFileMeta(f.fileId, f.sealedMeta)?.folderId == folder }
                }
            }
            assertEquals(View(setOf("Live" to null), setOf(Triple("live.txt", "Live", FileStatus.READY))), there.view())
        }
    }

    @Test
    fun treeShowsLoopsAndMissingParentsInTheRoot() {
        // Built by hand: the indexer never stores a loop, but a damaged local index must not hide folders.
        val a = newId()
        val b = newId()
        val c = newId()
        val gone = newId()
        val tree = VaultTree(
            folders = mapOf(a to LocalFolder("A", b), b to LocalFolder("B", a), c to LocalFolder("C", gone)),
            files = emptyMap<Id, FileMeta>(),
        )
        assertNull(tree.parentOf(a))
        assertNull(tree.parentOf(b))
        assertNull(tree.parentOf(c))
        assertEquals(setOf(a, b, c), tree.childFolders(null).toSet())
        assertFalse(tree.isWithin(a, b))
    }
}
