package me.esdex.coffer.core.sync

import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Moving into a folder that holds the same names (decision 2026-11-19), through to what a rescan shows. */
class MoveConflictsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val cloud = FakeCloud()
    private val here by lazy { Device(cloud, keysOf(MasterKeys.generate(sodium)), temp.newFolder()) }
    private val ops by lazy { VaultOps(here.storage, here.keys, OpQueue(here.index)) { 1_762_531_200_000 } }

    private fun blobs() = cloud.allMessages().filter { it.content is MessageContent.Blob }.map { it.id }.toSet()

    private suspend fun move(folders: List<Id>, files: List<Id>, destination: Id?, file: FileChoice, folder: FolderChoice) {
        val tree = here.tree()
        val incoming = folders.map { NameConflicts.incoming(tree, it) } + files.map { Incoming.File(tree.files.getValue(it).name, it) }
        val placed = NameConflicts(tree, ConflictDecider.always(file, folder), leaving = (folders + files).toSet()).place(destination, incoming)
        ops.movePlaced(tree, placed, destination, partsOf = { here.index.parts(it).values })
        OpQueue(here.index).drain(here.storage, pause = {})
        here.indexer.catchUp()
    }

    private suspend fun assertRescanAgrees() {
        val fresh = Device(cloud, here.keys, temp.newFolder())
        fresh.indexer.rescan()
        assertEquals(here.view(), fresh.view())
    }

    private fun ready(name: String, folder: String?) = Triple(name, folder, FileStatus.READY)

    @Test
    fun aMergedFolderGoesInsideTheOtherAndIsGoneOnceEmpty() = runTest {
        // A/ holds x.txt and Sub/y.txt; B/ holds x.txt and A/z.txt.
        val a = here.ops.createFolder("A", null)
        val sub = here.ops.createFolder("Sub", a)
        val b = here.ops.createFolder("B", null)
        val ba = here.ops.createFolder("a", b)
        here.indexer.catchUp()
        here.upload("x.txt", BytesSource("ax".toByteArray()), folderId = a)
        here.upload("y.txt", BytesSource("y".toByteArray()), folderId = sub)
        here.upload("x.txt", BytesSource("bx".toByteArray()), folderId = b)
        here.upload("z.txt", BytesSource("z".toByteArray()), folderId = ba)
        here.indexer.catchUp()

        move(listOf(a), emptyList(), b, FileChoice.REPLACE, FolderChoice.MERGE)
        assertEquals(setOf("B" to null, "a" to "B", "Sub" to "a"), here.view().folders)
        assertEquals(setOf(ready("x.txt", "B"), ready("x.txt", "a"), ready("z.txt", "a"), ready("y.txt", "Sub")), here.view().files)
        assertRescanAgrees()
    }

    @Test
    fun aFileReplacingAnotherTakesItsPlaceAndTheOldOneGoesWithItsParts() = runTest {
        val docs = here.ops.createFolder("Docs", null)
        here.indexer.catchUp()
        val incoming = here.upload("Report.pdf", BytesSource("new".toByteArray()))
        val old = here.upload("report.pdf", BytesSource("old".toByteArray()), folderId = docs)
        here.indexer.catchUp()
        val oldParts = here.index.parts(old).values.toSet()
        move(emptyList(), listOf(incoming), docs, FileChoice.REPLACE, FolderChoice.MERGE)
        assertEquals(setOf(ready("Report.pdf", "Docs")), here.view().files)
        assertTrue(oldParts.none { it in blobs() })
        assertRescanAgrees()
    }

    @Test
    fun keptBothIsMovedUnderANewNameAndSkippedStaysWhereItWas() = runTest {
        val docs = here.ops.createFolder("Docs", null)
        val a = here.ops.createFolder("A", null)
        val docsA = here.ops.createFolder("A", docs)
        here.indexer.catchUp()
        val loose = here.upload("n.txt", BytesSource("1".toByteArray()))
        here.upload("n.txt", BytesSource("2".toByteArray()), folderId = docs)
        here.upload("k.txt", BytesSource("k".toByteArray()), folderId = a)
        here.upload("k.txt", BytesSource("k2".toByteArray()), folderId = docsA)
        here.indexer.catchUp()

        move(emptyList(), listOf(loose), docs, FileChoice.KEEP_BOTH, FolderChoice.KEEP_BOTH)
        move(listOf(a), emptyList(), docs, FileChoice.KEEP_BOTH, FolderChoice.KEEP_BOTH)
        assertEquals(setOf("Docs" to null, "A" to "Docs", "A (1)" to "Docs"), here.view().folders)
        assertEquals(setOf(ready("n.txt", "Docs"), ready("n (1).txt", "Docs"), ready("k.txt", "A"), ready("k.txt", "A (1)")), here.view().files)
        assertRescanAgrees()
    }

    @Test
    fun aMergeThatSkippedSomethingLeavesTheFolderWithIt() = runTest {
        val a = here.ops.createFolder("A", null)
        val b = here.ops.createFolder("B", null)
        val ba = here.ops.createFolder("A", b)
        here.indexer.catchUp()
        here.upload("same.txt", BytesSource("1".toByteArray()), folderId = a)
        here.upload("other.txt", BytesSource("o".toByteArray()), folderId = a)
        here.upload("same.txt", BytesSource("2".toByteArray()), folderId = ba)
        here.indexer.catchUp()
        move(listOf(a), emptyList(), b, FileChoice.SKIP, FolderChoice.MERGE)
        assertEquals(setOf("A" to null, "B" to null, "A" to "B"), here.view().folders)
        assertEquals(listOf(a), here.tree().childFolders(null).filter { here.tree().folders.getValue(it).name == "A" })
        assertEquals(setOf("same.txt"), here.tree().childFiles(a).map { here.tree().files.getValue(it).name }.toSet())
        assertEquals(setOf("same.txt", "other.txt"), here.tree().childFiles(ba).map { here.tree().files.getValue(it).name }.toSet())
        assertRescanAgrees()
    }
}
