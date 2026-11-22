package me.esdex.coffer.core.index

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.MessageId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What every [VaultIndex] must do. Runs against [InMemoryVaultIndex], which every sync test stands on, and
 * against the Room index on the desktop, so the two cannot drift apart. Lists are compared as sets where
 * the interface promises no order; operations come in the order they were queued.
 */
abstract class VaultIndexContract {

    /** A new, empty index. */
    protected abstract fun newIndex(): VaultIndex

    private fun contract(block: suspend CoroutineScope.(VaultIndex) -> Unit) = runBlocking {
        val index = newIndex()
        withTimeout(TIMEOUT_MILLIS) { block(index) }
    }

    @Test
    fun filesComeBackAsTheyWentIn() = contract { index ->
        index.putFile(A, 10, 1, byteArrayOf(1), FileStatus.READY)
        index.putFile(B, 3_000_000, 3, byteArrayOf(2, 2), FileStatus.UPLOADING, padded = true)
        index.putPart(A, 0, M1)
        index.putPart(B, 0, M2)
        index.putPart(B, 2, M3)

        assertEquals(setOf(A, B), index.files().map { it.fileId }.toSet())
        val b = index.file(B)!!
        assertEquals(3_000_000L, b.plainSize)
        assertEquals(3, b.partCount)
        assertArrayEquals(byteArrayOf(2, 2), b.sealedMeta)
        assertEquals(FileStatus.UPLOADING, b.status)
        assertTrue(b.padded)
        assertEquals(mapOf(0 to M2, 2 to M3), b.parts)
        assertFalse(index.file(A)!!.padded)
        assertNull(index.file(C))
    }

    @Test
    fun aFilePutAgainIsReplacedAndKeepsItsParts() = contract { index ->
        index.putFile(A, 10, 1, byteArrayOf(1), FileStatus.UPLOADING)
        index.putPart(A, 0, M1)
        index.putFile(A, 20, 2, byteArrayOf(9), FileStatus.READY)
        val a = index.files().single()
        assertEquals(20L, a.plainSize)
        assertEquals(FileStatus.READY, a.status)
        assertEquals(mapOf(0 to M1), a.parts)
    }

    @Test
    fun metaUpdatesOnlyAKnownFileAndKeepsItsStatus() = contract { index ->
        index.putFile(A, 10, 1, byteArrayOf(1), FileStatus.DAMAGED)
        assertTrue(index.updateFileMeta(A, 30, 3, byteArrayOf(7), padded = true))
        val a = index.file(A)!!
        assertEquals(30L, a.plainSize)
        assertEquals(3, a.partCount)
        assertArrayEquals(byteArrayOf(7), a.sealedMeta)
        assertTrue(a.padded)
        assertEquals(FileStatus.DAMAGED, a.status)
        assertFalse(index.updateFileMeta(B, 1, 1, byteArrayOf(1)))
        assertNull(index.file(B))
    }

    @Test
    fun statusChangesOnlyAKnownFile() = contract { index ->
        index.putFile(A, 10, 1, byteArrayOf(1), FileStatus.UPLOADING)
        index.setStatus(A, FileStatus.READY)
        index.setStatus(B, FileStatus.READY)
        assertEquals(FileStatus.READY, index.file(A)!!.status)
        assertNull(index.file(B))
    }

    @Test
    fun removingAFileTakesItsPartsOnly() = contract { index ->
        index.putFile(A, 10, 1, byteArrayOf(1), FileStatus.READY)
        index.putFile(B, 10, 1, byteArrayOf(2), FileStatus.READY)
        index.putPart(A, 0, M1)
        index.putPart(B, 0, M2)
        index.removeFile(A)
        assertNull(index.file(A))
        assertEquals(emptyMap<Int, MessageId>(), index.parts(A))
        assertEquals(mapOf(0 to M2), index.parts(B))
        assertFalse("removed is not deleted", index.isDeleted(A))
    }

    @Test
    fun partsAreFoundByMessageAndKeptForFilesNotYetSeen() = contract { index ->
        index.putPart(A, 0, M1)
        index.putPart(A, 1, M2)
        // A part whose part 0 has not come yet: no file row.
        index.putPart(B, 1, M3)
        assertEquals(mapOf(A to mapOf(0 to M1, 1 to M2), B to mapOf(1 to M3)), index.allParts())
        assertEquals(A to 1, index.removePart(M2))
        assertNull(index.removePart(M2))
        assertNull(index.removePart(MessageId(999L shl 20)))
        assertEquals(mapOf(0 to M1), index.parts(A))
    }

    @Test
    fun deletionsAreRemembered() = contract { index ->
        assertFalse(index.isDeleted(A))
        index.markDeleted(A)
        index.markDeleted(A)
        index.markFolderDeleted(F1)
        assertTrue(index.isDeleted(A))
        assertFalse(index.isDeleted(B))
        assertTrue(index.isFolderDeleted(F1))
        assertFalse(index.isFolderDeleted(F2))
        assertEquals(setOf(A), index.deletedFiles())
        assertEquals(setOf(F1), index.deletedFolders())
    }

    @Test
    fun foldersComeBackAndArePutAgainAndRemoved() = contract { index ->
        index.putFolder(F1, byteArrayOf(1))
        index.putFolder(F2, byteArrayOf(2))
        index.putFolder(F1, byteArrayOf(3))
        assertEquals(setOf(F1, F2), index.folders().map { it.folderId }.toSet())
        assertArrayEquals(byteArrayOf(3), index.folder(F1)!!.sealedMeta)
        index.removeFolder(F2)
        assertNull(index.folder(F2))
        assertEquals(listOf(F1), index.folders().map { it.folderId })
    }

    @Test
    fun syncValuesStartEmptyAndKeepTheLatest() = contract { index ->
        assertNull(index.lastApplied())
        assertNull(index.contentVersion())
        assertNull(index.maintainedAt())
        assertNull(index.oldestRecord())
        index.setLastApplied(M1)
        index.setLastApplied(M2)
        index.setContentVersion(M3)
        index.setMaintainedAt(1_700_000_000_000)
        index.setOldestRecord(M1)
        assertEquals(M2, index.lastApplied())
        assertEquals(M3, index.contentVersion())
        assertEquals(1_700_000_000_000, index.maintainedAt())
        assertEquals(M1, index.oldestRecord())
    }

    @Test
    fun snapshotsArePutAgainByMessageAndRemoved() = contract { index ->
        index.putSnapshot(KnownSnapshot(M2, M1))
        index.putSnapshot(KnownSnapshot(M3, M2))
        index.putSnapshot(KnownSnapshot(M2, M3))
        assertEquals(setOf(KnownSnapshot(M2, M3), KnownSnapshot(M3, M2)), index.snapshots().toSet())
        assertTrue(index.removeSnapshot(M3))
        assertFalse(index.removeSnapshot(M3))
        assertEquals(listOf(KnownSnapshot(M2, M3)), index.snapshots())
    }

    @Test
    fun appliedOperationsRememberTheirMessage() = contract { index ->
        assertNull(index.opAppliedFrom(A))
        index.markOpApplied(A, M1)
        index.markOpApplied(A, M2)
        assertEquals(M2, index.opAppliedFrom(A))
        assertNull(index.opAppliedFrom(B))
    }

    @Test
    fun replacingTheContentKeepsTheQueueAndWhatIsNotContent() = contract { index ->
        index.putFile(A, 10, 1, byteArrayOf(1), FileStatus.READY)
        index.putPart(A, 0, M1)
        index.markDeleted(B)
        index.putFolder(F1, byteArrayOf(1))
        index.markFolderDeleted(F2)
        index.putSnapshot(KnownSnapshot(M3, M1))
        index.setLastApplied(M1)
        index.setContentVersion(M1)
        index.setMaintainedAt(5)
        index.setOldestRecord(M1)
        index.markOpApplied(C, M1)
        index.putOps("b", "RENAME", listOf(OpMessage("op", 1)))
        index.putUpload(PendingUpload("u", byteArrayOf(1), null, emptyList(), null))
        index.putDownload(PendingDownload("d", A, null))

        val file = IndexedFile(C, 50, 2, byteArrayOf(5), FileStatus.INCOMPLETE, mapOf(0 to M2), padded = true)
        index.replaceContent(
            IndexContent(
                lastApplied = null,
                contentVersion = M2,
                files = listOf(file),
                orphanParts = mapOf(D to mapOf(1 to M3)),
                folders = listOf(IndexedFolder(F2, byteArrayOf(6))),
                deletedFiles = setOf(A),
                deletedFolders = setOf(F1),
                snapshots = listOf(KnownSnapshot(M2, M2)),
            ),
        )

        val c = index.files().single()
        assertEquals(C, c.fileId)
        assertEquals(50L, c.plainSize)
        assertEquals(FileStatus.INCOMPLETE, c.status)
        assertTrue(c.padded)
        assertEquals(mapOf(C to mapOf(0 to M2), D to mapOf(1 to M3)), index.allParts())
        assertEquals(setOf(A), index.deletedFiles())
        assertEquals(setOf(F1), index.deletedFolders())
        assertEquals(listOf(F2), index.folders().map { it.folderId })
        assertEquals(listOf(KnownSnapshot(M2, M2)), index.snapshots())
        assertNull("no last applied in the content: none", index.lastApplied())
        assertEquals(M2, index.contentVersion())
        // Not content: the queue, maintenance and what was applied stay.
        assertEquals(5L, index.maintainedAt())
        assertEquals(M1, index.oldestRecord())
        assertEquals(M1, index.opAppliedFrom(C))
        assertEquals(1, index.ops().size)
        assertEquals(1, index.uploads().size)
        assertEquals(1, index.downloads().size)
    }

    @Test
    fun operationsKeepTheirOrderAndWhatTheyDelete() = contract { index ->
        index.putOps(
            "b1", "DELETE",
            listOf(
                OpMessage("first", 2, deletedFiles = listOf(A, B), deletedFolders = listOf(F1), parts = listOf(M1, M2)),
                OpMessage("second", 3),
            ),
        )
        index.putOps("b2", "RENAME", listOf(OpMessage("third", 1)))
        val ops = index.ops()
        assertEquals(listOf("first", "second", "third"), ops.map { it.caption })
        assertEquals("seqs rise in queue order", ops.map { it.seq }.sorted(), ops.map { it.seq })
        assertEquals(3, ops.map { it.seq }.toSet().size)
        val first = ops[0]
        assertEquals("b1", first.batch)
        assertEquals("a batch's size is all its operations", 5, first.batchSize)
        assertEquals(5, ops[1].batchSize)
        assertEquals("DELETE", first.kind)
        assertEquals(2, first.opCount)
        assertEquals(listOf(A, B), first.deletedFiles)
        assertEquals(listOf(F1), first.deletedFolders)
        assertEquals(listOf(M1, M2), first.parts)
        assertFalse(first.sent)
        assertEquals(emptyList<Id>(), ops[1].deletedFiles)
        assertEquals(1, ops[2].batchSize)

        index.markOpSent(first.seq)
        index.removeOp(ops[1].seq)
        val left = index.ops()
        assertEquals(listOf("first", "third"), left.map { it.caption })
        assertTrue(left[0].sent)
        assertFalse(left[1].sent)

        // A seq is never given twice, not even after the newest one went.
        index.removeOp(left[1].seq)
        index.putOps("b3", "MOVE", listOf(OpMessage("fourth", 1)))
        assertTrue(index.ops().last().seq > left[1].seq)
    }

    @Test
    fun uploadsComeBackAndArePutAgainById() = contract { index ->
        index.putUpload(PendingUpload("u1", byteArrayOf(1), null, emptyList(), null))
        index.putUpload(PendingUpload("u2", byteArrayOf(2), B, listOf("c0", "c1"), "abandon", replaces = C))
        index.putUpload(PendingUpload("u1", byteArrayOf(3), A, listOf("c"), null))
        val byId = index.uploads().associateBy { it.uploadId }
        assertEquals(setOf("u1", "u2"), byId.keys)
        val u1 = byId.getValue("u1")
        assertArrayEquals(byteArrayOf(3), u1.sealedRequest)
        assertEquals(A, u1.fileId)
        assertEquals(listOf("c"), u1.captions)
        assertNull(u1.abandonOp)
        assertNull(u1.replaces)
        val u2 = byId.getValue("u2")
        assertEquals(listOf("c0", "c1"), u2.captions)
        assertEquals("abandon", u2.abandonOp)
        assertEquals(C, u2.replaces)
        index.removeUpload("u1")
        assertEquals(listOf("u2"), index.uploads().map { it.uploadId })
    }

    @Test
    fun downloadsComeBackAndAreRemoved() = contract { index ->
        index.putDownload(PendingDownload("d1", A, null))
        index.putDownload(PendingDownload("d2", B, byteArrayOf(4)))
        val byId = index.downloads().associateBy { it.downloadId }
        assertNull(byId.getValue("d1").sealedTarget)
        assertEquals(B, byId.getValue("d2").fileId)
        assertArrayEquals(byteArrayOf(4), byId.getValue("d2").sealedTarget)
        index.removeDownload("d1")
        assertEquals(listOf("d2"), index.downloads().map { it.downloadId })
    }

    @Test
    fun observersSeeChanges() = contract { index ->
        val seenA = CompletableDeferred<Unit>()
        val gone = async {
            index.observeFiles().first { files ->
                if (files.any { it.fileId == A }) seenA.complete(Unit)
                files.isEmpty() && seenA.isCompleted
            }
        }
        val folder = async { index.observeFolders().first { folders -> folders.any { it.folderId == F1 } } }
        val op = async { index.observeOps().first { it.isNotEmpty() } }
        val upload = async { index.observeUploads().first { it.isNotEmpty() } }
        val download = async { index.observeDownloads().first { it.isNotEmpty() } }
        index.putFile(A, 1, 1, byteArrayOf(1), FileStatus.READY)
        seenA.await()
        index.removeFile(A)
        index.putFolder(F1, byteArrayOf(1))
        index.putOps("b", "RENAME", listOf(OpMessage("op", 1)))
        index.putUpload(PendingUpload("u", byteArrayOf(1), null, emptyList(), null))
        index.putDownload(PendingDownload("d", A, null))
        listOf(gone, folder, op, upload, download).awaitAll()
    }

    @Test
    fun clearEmptiesEverythingAndTheIndexGoesOn() = contract { index ->
        index.putFile(A, 10, 1, byteArrayOf(1), FileStatus.READY)
        index.putPart(A, 0, M1)
        index.markDeleted(B)
        index.putFolder(F1, byteArrayOf(1))
        index.markFolderDeleted(F2)
        index.putSnapshot(KnownSnapshot(M2, M1))
        index.setLastApplied(M1)
        index.setContentVersion(M1)
        index.setMaintainedAt(1)
        index.setOldestRecord(M1)
        index.markOpApplied(C, M1)
        index.putOps("b", "RENAME", listOf(OpMessage("op", 1)))
        index.putUpload(PendingUpload("u", byteArrayOf(1), null, emptyList(), null))
        index.putDownload(PendingDownload("d", A, null))

        index.clear()

        assertEquals(emptyList<IndexedFile>(), index.files())
        assertEquals(emptyMap<Id, Map<Int, MessageId>>(), index.allParts())
        assertEquals(emptySet<Id>(), index.deletedFiles())
        assertEquals(emptyList<IndexedFolder>(), index.folders())
        assertEquals(emptySet<Id>(), index.deletedFolders())
        assertEquals(emptyList<KnownSnapshot>(), index.snapshots())
        assertNull(index.lastApplied())
        assertNull(index.contentVersion())
        assertNull(index.maintainedAt())
        assertNull(index.oldestRecord())
        assertNull(index.opAppliedFrom(C))
        assertEquals(emptyList<PendingOp>(), index.ops())
        assertEquals(emptyList<PendingUpload>(), index.uploads())
        assertEquals(emptyList<PendingDownload>(), index.downloads())

        index.putFile(A, 20, 1, byteArrayOf(2), FileStatus.READY)
        assertEquals(20L, index.file(A)?.plainSize)
    }

    @Test
    fun aReplacedContentIsSeenWholeOrNotAtAll() = contract { index ->
        // replaceContent empties the files and puts the new ones in: one transaction, or a reader can catch
        // the index empty in between, as a snapshot load would show an empty vault for a moment.
        val before = IndexContent(null, null, listOf(IndexedFile(A, 1, 1, byteArrayOf(1), FileStatus.READY, emptyMap())), emptyMap(), emptyList(), emptySet(), emptySet(), emptyList())
        val after = IndexContent(null, null, listOf(IndexedFile(B, 1, 1, byteArrayOf(2), FileStatus.READY, emptyMap())), emptyMap(), emptyList(), emptySet(), emptySet(), emptyList())
        index.replaceContent(before)
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<Set<Id>>()
        val writer = async(Dispatchers.Default) {
            repeat(REPLACEMENTS) { index.replaceContent(if (it % 2 == 0) after else before) }
        }
        val readers = (1..4).map {
            async(Dispatchers.Default) {
                while (writer.isActive) seen += index.files().map { it.fileId }.toSet()
            }
        }
        writer.await()
        readers.awaitAll()
        assertEquals(setOf(setOf(A), setOf(B)), seen)
    }

    @Test
    fun manyWritersAtOnceLoseNothing() = contract { index ->
        // Removing a part reads and deletes in one transaction; under load it must neither deadlock nor lose rows.
        val count = 200
        (0 until count).map { i ->
            async(Dispatchers.Default) { index.putPart(A, i, MessageId((i + 1).toLong() shl 20)) }
        }.awaitAll()
        val removed = (0 until count step 2).map { i ->
            async(Dispatchers.Default) { index.removePart(MessageId((i + 1).toLong() shl 20)) }
        }.awaitAll()
        assertEquals((0 until count step 2).map { A to it }, removed)
        assertEquals((1 until count step 2).toSet(), index.parts(A).keys)
    }

    protected companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val REPLACEMENTS = 300

        private fun id(n: Int) = Id.of(ByteArray(16) { n.toByte() })

        val A = id(1)
        val B = id(2)
        val C = id(3)
        val D = id(4)
        val F1 = id(11)
        val F2 = id(12)

        // Server message ids: multiples of 2^20 (TdlibMapping.isServerMessageId).
        val M1 = MessageId(1L shl 20)
        val M2 = MessageId(2L shl 20)
        val M3 = MessageId(3L shl 20)
    }
}
