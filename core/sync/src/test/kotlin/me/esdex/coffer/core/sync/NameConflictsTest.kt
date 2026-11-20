package me.esdex.coffer.core.sync

import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.FileNames
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.index.PendingUpload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Names taken where something goes (decision 2026-11-19): asked about, or settled by a decision for
 * all. Expected placements are written out by hand.
 */
class NameConflictsTest {
    private fun id() = Id.random(sodium)
    private fun meta(name: String, folderId: Id?) =
        FileMeta(name, folderId, "application/octet-stream", 1, 1, Bytes.of(ByteArray(32)), Bytes.of(ByteArray(72)))

    // The vault: Docs/ with Report.pdf and Old/ (with a.txt); notes.txt in the root.
    private val docs = id()
    private val old = id()
    private val report = id()
    private val oldA = id()
    private val notes = id()
    private val tree = VaultTree(
        folders = mapOf(docs to LocalFolder("Docs", null), old to LocalFolder("Old", docs)),
        files = mapOf(report to meta("Report.pdf", docs), oldA to meta("a.txt", old), notes to meta("notes.txt", null)),
    )

    private fun file(name: String) = Incoming.File(name, name)
    private fun folder(name: String, vararg children: Incoming<String>) = Incoming.Folder(name, name, children.toList())

    private val asked = mutableListOf<Conflict>()

    private fun conflicts(file: FileChoice = FileChoice.KEEP_BOTH, folder: FolderChoice = FolderChoice.MERGE, forAll: Boolean = false, leaving: Set<Id> = emptySet()) =
        NameConflicts(tree, decider({ asked += it; Decision(file, forAll) }, { asked += it; Decision(folder, forAll) }), leaving = leaving)

    private fun decider(onFile: suspend (Conflict.File) -> Decision<FileChoice>, onFolder: suspend (Conflict.Folder) -> Decision<FolderChoice>) =
        object : ConflictDecider {
            override suspend fun file(conflict: Conflict.File) = onFile(conflict)
            override suspend fun folder(conflict: Conflict.Folder) = onFolder(conflict)
        }

    @Test
    fun freeNamesGoAsTheyAreWithoutAQuestion() = runTest {
        val placed = conflicts().place(docs, listOf(file("new.txt"), folder("New", file("x"))))
        assertEquals(listOf(Placed.File("new.txt", "new.txt"), Placed.Folder("New", "New", null, listOf(Placed.File("x", "x")))), placed)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun aTakenFileIsKeptBothSkippedOrReplacedAsDecided() = runTest {
        assertEquals(listOf(Placed.File("report (1).pdf", "report.pdf")), conflicts(FileChoice.KEEP_BOTH).place(docs, listOf(file("report.pdf"))))
        // Case does not make it another name.
        assertEquals(listOf(Conflict.File("report.pdf", docs)), asked)
        assertEquals(emptyList<Placed<String>>(), conflicts(FileChoice.SKIP).place(docs, listOf(file("Report.pdf"))))
        assertEquals(listOf(Placed.File("Report.pdf", "Report.pdf", replaces = report)), conflicts(FileChoice.REPLACE).place(docs, listOf(file("Report.pdf"))))
    }

    @Test
    fun mergingAFolderChecksWhatIsInsideAgainstWhatItHolds() = runTest {
        val incoming = folder("docs", file("Report.pdf"), file("Summary.pdf"), folder("Old", file("a.txt"), file("b.txt")))
        val placed = conflicts(FileChoice.REPLACE, FolderChoice.MERGE).place(null, listOf(incoming))
        val expected = Placed.Folder(
            "Docs", "docs", docs,
            listOf(
                Placed.File("Report.pdf", "Report.pdf", replaces = report),
                Placed.File("Summary.pdf", "Summary.pdf"),
                Placed.Folder("Old", "Old", old, listOf(Placed.File("a.txt", "a.txt", replaces = oldA), Placed.File("b.txt", "b.txt"))),
            ),
        )
        assertEquals(listOf(expected), placed)
        assertEquals(listOf(Conflict.Folder("docs", null), Conflict.File("Report.pdf", docs), Conflict.Folder("Old", docs), Conflict.File("a.txt", old)), asked)
    }

    @Test
    fun aFolderKeptBothIsNumberedAndItsInsideNeedsNoQuestion() = runTest {
        val placed = conflicts(folder = FolderChoice.KEEP_BOTH).place(null, listOf(folder("Docs", file("Report.pdf"))))
        assertEquals(listOf(Placed.Folder("Docs (1)", "Docs", null, listOf(Placed.File("Report.pdf", "Report.pdf")))), placed)
        assertEquals(listOf(Conflict.Folder("Docs", null)), asked)
        assertEquals(emptyList<Placed<String>>(), conflicts(folder = FolderChoice.SKIP).place(null, listOf(folder("Docs"))))
    }

    @Test
    fun aDecisionForAllIsAskedOnceAndMakesTheRest() = runTest {
        val vault = VaultTree(emptyMap(), (1..3).associate { id() to meta("f$it", null) })
        var questions = 0
        val conflicts = NameConflicts(vault, decider({ questions++; Decision(FileChoice.SKIP, forAll = true) }, { error("no folders") }))
        assertEquals(listOf(Placed.File("g", "g")), conflicts.place(null, listOf(file("f1"), file("f2"), file("g"), file("f3"))))
        assertEquals(1, questions)
    }

    @Test
    fun withoutForAllEachTakenNameIsAskedAbout() = runTest {
        val vault = VaultTree(emptyMap(), (1..3).associate { id() to meta("f$it", null) })
        var questions = 0
        val conflicts = NameConflicts(vault, decider({ questions++; Decision(FileChoice.SKIP) }, { error("no folders") }))
        conflicts.place(null, listOf(file("f1"), file("f2"), file("f3")))
        assertEquals(3, questions)
    }

    @Test
    fun twoIncomingOfOneNameAreBothKeptAndAFileNeverTakesAFoldersName() = runTest {
        val placed = conflicts().place(null, listOf(file("a.txt"), file("A.txt"), file("Docs"), folder("notes.txt")))
        assertEquals(
            listOf(Placed.File("a.txt", "a.txt"), Placed.File("A (1).txt", "A.txt"), Placed.File("Docs (1)", "Docs"), Placed.Folder("notes.txt (1)", "notes.txt", null, emptyList())),
            placed,
        )
        assertTrue(asked.isEmpty())
    }

    @Test
    fun whatIsMovingDoesNotTakeItsOwnName() = runTest {
        // Report.pdf moving out of Docs and a new one coming in: the old one's name is free.
        val placed = conflicts(leaving = setOf(report)).place(docs, listOf(file("Report.pdf")))
        assertEquals(listOf(Placed.File("Report.pdf", "Report.pdf")), placed)
        assertTrue(asked.isEmpty())
    }

    @get:org.junit.Rule
    val temp = org.junit.rules.TemporaryFolder()

    private val cloud = me.esdex.coffer.core.storage.FakeCloud()

    private fun blobs() = cloud.allMessages().filter { it.content is me.esdex.coffer.core.storage.MessageContent.Blob }.map { it.id }.toSet()

    @Test
    fun aReplacedFileGoesOnlyOnceTheNewOneIsWhole() = runTest {
        val here = Device(cloud, keysOf(me.esdex.coffer.core.crypto.MasterKeys.generate(sodium)), temp.newFolder())
        val old = here.upload("a.txt", BytesSource("old".toByteArray()))
        here.indexer.catchUp()
        val oldParts = here.index.parts(old).values.toSet()

        // An upload that fails leaves the old file alone.
        val (first, request) = here.queued("a.txt")
        val failing = here.preparer.prepare(PendingUpload(first.uploadId, first.sealedRequest, null, emptyList(), null, replaces = old), request, BytesSource("new".toByteArray()))
        cloud.failNextCall(me.esdex.coffer.core.storage.StorageException("connection lost"))
        runCatching { here.runner.run(failing.upload, failing.keys, BytesSource("new".toByteArray())) }
        assertTrue(here.index.ops().isEmpty())
        assertTrue(blobs().containsAll(oldParts))

        // Run again to the end: the old file is held for deleting, sent only once a key seals it.
        here.runner.run(here.index.uploads().single(), failing.keys, BytesSource("new".toByteArray()))
        assertTrue(OpQueue(here.index).sendable().isEmpty())
        OpQueue(here.index).drain(here.storage, pause = {})
        assertTrue(blobs().containsAll(oldParts))
        OpQueue(here.index).sealHeld(here.keys) { 1 }
        val queued = here.index.ops().single()
        assertEquals(OpKind.DELETE.name, queued.kind)
        assertEquals(listOf(old), queued.deletedFiles)
        assertEquals(oldParts, queued.parts.toSet())
        OpQueue(here.index).drain(here.storage, pause = {})
        here.indexer.catchUp()
        assertEquals(setOf(Triple("a.txt", null, me.esdex.coffer.core.index.FileStatus.READY)), here.view().files)
        assertTrue(oldParts.none { it in blobs() })
        assertEquals(null, here.index.file(old))
    }

    @Test
    fun anUploadCutOffAsItEndsLeavesBothFilesNeverNeither() = runTest {
        // Audit 3, Q3-3: killed between the old file's hold and the upload's removal, cancelling took both.
        val here = Device(cloud, keysOf(me.esdex.coffer.core.crypto.MasterKeys.generate(sodium)), temp.newFolder())
        val old = here.upload("a.txt", BytesSource("old".toByteArray()))
        here.indexer.catchUp()
        val (queued, request) = here.queued("a.txt")
        val prepared = here.preparer.prepare(PendingUpload(queued.uploadId, queued.sealedRequest, null, emptyList(), null, replaces = old), request, BytesSource("new".toByteArray()))
        val killed = object : me.esdex.coffer.core.index.VaultIndex by here.index {
            override suspend fun removeUpload(uploadId: String) = throw IllegalStateException("killed")
        }
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { UploadRunner(sodium, here.storage, killed, temp.newFolder()).run(prepared.upload, prepared.keys, BytesSource("new".toByteArray())) }
        }
        // After the restart the upload is still there; the user cancels it.
        here.runner.cancel(here.index.uploads().single())
        OpQueue(here.index).sealHeld(here.keys) { 1_762_531_200_000 }
        OpQueue(here.index).drain(here.storage, pause = {})
        here.indexer.catchUp()
        assertNotNull(here.index.file(old))
        assertTrue(here.index.ops().isEmpty())
    }

    @Test
    fun aReplacedFileGoneMeanwhileIsNotDeletedAgain() = runTest {
        val here = Device(cloud, keysOf(me.esdex.coffer.core.crypto.MasterKeys.generate(sodium)), temp.newFolder())
        val old = here.upload("a.txt", BytesSource("old".toByteArray()))
        here.indexer.catchUp()
        val (queued, request) = here.queued("a.txt")
        val prepared = here.preparer.prepare(PendingUpload(queued.uploadId, queued.sealedRequest, null, emptyList(), null, replaces = old), request, BytesSource("new".toByteArray()))
        here.deleter.delete(here.keys, old, now = 1)
        here.runner.run(prepared.upload, prepared.keys, BytesSource("new".toByteArray()))
        assertTrue(here.index.ops().isEmpty())
    }

    @Test
    fun manyReplacedFilesGoInFewMessages() = runTest {
        // 150 files replaced by one folder brought in again sent a message each and ran into Telegram's pause.
        val here = Device(cloud, keysOf(me.esdex.coffer.core.crypto.MasterKeys.generate(sodium)), temp.newFolder())
        val olds = (1..60).map { here.upload("f$it.txt", BytesSource(byteArrayOf(it.toByte()))) }
        here.indexer.catchUp()
        val prepared = olds.mapIndexed { n, old ->
            val (queued, request) = here.queued("f${n + 1}.txt")
            val source = BytesSource(byteArrayOf(0, n.toByte()))
            here.preparer.prepare(PendingUpload(queued.uploadId, queued.sealedRequest, null, emptyList(), null, replaces = old), request, source) to source
        }
        // Sealed as each one ended, they went as packs of one or two: nothing is sealed while one is left.
        for ((upload, source) in prepared.dropLast(1)) {
            here.runner.run(upload.upload, upload.keys, source)
            OpQueue(here.index).sealHeld(here.keys) { 1_762_531_200_000 }
        }
        assertTrue(OpQueue(here.index).sendable().isEmpty())
        // The last one's old file deleted meanwhile is left out.
        here.deleter.delete(here.keys, olds.last(), now = 1)
        prepared.last().let { (upload, source) -> here.runner.run(upload.upload, upload.keys, source) }
        val textsBefore = cloud.allMessages().count { it.content is me.esdex.coffer.core.storage.MessageContent.Text }
        OpQueue(here.index).sealHeld(here.keys) { 1_762_531_200_000 }
        assertEquals(listOf(49, 10), here.index.ops().map { it.opCount })
        OpQueue(here.index).drain(here.storage, pause = {})
        assertEquals(textsBefore + 2, cloud.allMessages().count { it.content is me.esdex.coffer.core.storage.MessageContent.Text })
        here.indexer.catchUp()
        assertEquals((1..60).map { "f$it.txt" }.toSet(), here.view().files.map { it.first }.toSet())
        // Each old one gone, each new one there: one file a name.
        assertEquals(60, here.index.files().size)
    }

    @Test
    fun aFailedUploadDoesNotHoldBackTheOthersDeletes() = runTest {
        val here = Device(cloud, keysOf(me.esdex.coffer.core.crypto.MasterKeys.generate(sodium)), temp.newFolder())
        val (a, b) = listOf("a.txt", "b.txt").map { here.upload(it, BytesSource(it.toByteArray())) }
        here.indexer.catchUp()
        val (queued, request) = here.queued("b.txt")
        val stuck = here.preparer.prepare(PendingUpload(queued.uploadId, queued.sealedRequest, null, emptyList(), null, replaces = b), request, BytesSource("x".toByteArray()))
        OpQueue(here.index).hold(a, here.index.parts(a).values.toList())
        OpQueue(here.index).sealHeld(here.keys) { 1 }
        assertTrue(OpQueue(here.index).sendable().isEmpty())
        OpQueue(here.index).sealHeld(here.keys, failed = setOf(stuck.upload.uploadId)) { 1 }
        assertEquals(listOf(listOf(a)), OpQueue(here.index).sendable().map { it.deletedFiles })
    }

    @Test
    fun numbersGoOnFromAStemAndStayWithinTheNameLimit() {
        assertEquals("file (1).pdf", NameConflicts.numbered("file.pdf", isFile = true, setOf("file.pdf")))
        assertEquals("file (3).pdf", NameConflicts.numbered("file.pdf", isFile = true, setOf("file.pdf", "file (1).pdf", "file (2).pdf")))
        assertEquals("file (2).pdf", NameConflicts.numbered("file (1).pdf", isFile = true, setOf("file (1).pdf")))
        assertEquals(".bashrc (1)", NameConflicts.numbered(".bashrc", isFile = true, setOf(".bashrc")))
        assertEquals("v1.2 (1)", NameConflicts.numbered("v1.2", isFile = false, setOf("v1.2")))
        val long = "я".repeat(125) + ".pdf"
        assertEquals(254, FileNames.utf8Size(long))
        val numbered = NameConflicts.numbered(long, isFile = true, setOf(NameConflicts.key(long)))
        assertTrue(FileNames.isValid(numbered))
        assertTrue(numbered.endsWith(" (1).pdf"))
    }
}
