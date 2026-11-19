package me.esdex.coffer.core.sync

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.ContentLayout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.crypto.format.BlobPayload
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.format.RecordKind
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.IndexContent
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The integrity check (SPEC §5.7, §5.8) and compaction (§5.6). Expected outcomes are written out by hand. */
class MaintenanceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val master = MasterKeys.generate(sodium)
    private val cloud = FakeCloud(pageCap = 5)
    private var clock = 1_762_531_200L

    init {
        cloud.now = { clock }
    }

    private fun device() = Device(cloud, keysOf(master), temp.newFolder())

    private fun bytes(text: String) = BytesSource(text.toByteArray())

    private fun hours(n: Long) = n * 3600
    private fun days(n: Long) = n * 24 * 3600

    private fun present(id: MessageId) = cloud.allMessages().any { it.id == id }

    private fun lastId() = cloud.allMessages().maxOf { it.id }

    private fun indexBlobs() = cloud.allMessages().filter { Captions.parse((it.content as? MessageContent.Blob)?.caption)?.kind == RecordKind.INDEX }

    /** Sends one part's caption over a few dummy bytes, as another client might. */
    /** One part of a two-part file, as another device posts it: a document of the part's size, its bytes left out. */
    private fun Device.sendPart(fileId: Id, partIndex: Int, meta: FileMeta?): MessageId {
        val size = 512L * 1024 * 1024 + 1
        val caption = keys.meta.encodeBlob(BlobPayload(fileId, partIndex, 2, size, meta))
        return cloud.postBlobOfSize(caption, ContentLayout.encryptedPartSize(size, partIndex)).id
    }

    private fun meta(name: String) = FileMeta(name, null, "application/octet-stream", 1, 1, Bytes.of(ByteArray(32)), Bytes.of(ByteArray(72)))

    // --- integrity ---

    @Test
    fun aPartDeletedWhileNobodyWatchedIsFound() = runBlocking {
        val here = device()
        val hurt = here.upload("hurt.txt", bytes("h"))
        val fine = here.upload("fine.txt", bytes("f"))
        here.indexer.catchUp()
        cloud.deleteFromOutside(here.index.parts(hurt).values)
        val report = here.maintenance.run()
        assertEquals(1, report.damaged)
        assertEquals(FileStatus.DAMAGED, here.index.file(hurt)!!.status)
        assertEquals(FileStatus.READY, here.index.file(fine)!!.status)
        // The snapshot sent first is part of what was read.
        assertEquals(cloud.allMessages().size, report.messagesRead)
    }

    @Test
    fun aHistoryCutShortMarksNothingAndDeletesNothing() = runBlocking {
        // Audit 1, M6: TDLib answering empty before the start of the chat, after the first few messages.
        val here = device()
        val early = here.upload("early.txt", bytes("e"))
        here.sendOp(Op.FileRename(Id.random(sodium), 1, early, "renamed early.txt"))
        clock += days(40)
        repeat(3) { here.upload("later $it.txt", bytes("l")) }
        here.indexer.catchUp()
        here.maintenance.run()
        val before = cloud.allMessages().map { it.id }.toSet()

        val cut = here.index.parts(early).values.max()
        val shortSighted = object : StorageBackend by here.storage {
            override suspend fun historyBefore(before: MessageId?, limit: Int) =
                here.storage.historyBefore(before, limit).filter { it.id > cut }
        }
        clock += days(40)
        val maintenance = Maintenance(shortSighted, here.index, here.indexer, here.snapshotter, here.keys) { clock * 1000 }
        assertThrows(StorageException::class.java) { runBlocking { maintenance.run() } }
        assertEquals(FileStatus.READY, here.index.file(early)!!.status)
        assertEquals("renamed early.txt", here.meta(here.index.file(early)!!).name)
        // Nothing went but what the snapshot step adds and keeps.
        assertTrue(before.all { id -> cloud.allMessages().any { it.id == id } || id in here.index.snapshots().map { it.messageId } })
    }

    @Test
    fun aHistoryCutShortAboveTheOldestOperationIsNoticedToo() = runBlocking {
        // Audit 1, M6: only operations below the cut, no part a file is known by. The folder's rename
        // would look like one of a folder that never existed, and compaction would take it.
        val here = device()
        val folder = Id.random(sodium)
        val created = here.sendOp(Op.FolderCreate(Id.random(sodium), 1, folder, null, "Trip"))
        here.sendOp(Op.FolderRename(Id.random(sodium), 2, folder, "Trip 2026"))
        here.upload("ticket.pdf", bytes("t"), folderId = folder)
        here.indexer.catchUp()
        // A complete run first, which remembers where the vault starts.
        here.maintenance.run()

        clock += days(40)
        here.upload("later.txt", bytes("l"))
        here.indexer.catchUp()
        val shortSighted = object : StorageBackend by here.storage {
            override suspend fun historyBefore(before: MessageId?, limit: Int) =
                here.storage.historyBefore(before, limit).filter { it.id > created.id }
        }
        val maintenance = Maintenance(shortSighted, here.index, here.indexer, here.snapshotter, here.keys) { clock * 1000 }
        val ops = cloud.allMessages().filter { Captions.parse((it.content as? MessageContent.Text)?.text)?.kind == RecordKind.OP }.map { it.id }
        assertThrows(StorageException::class.java) { runBlocking { maintenance.run() } }
        assertTrue(ops.all(::present))
    }

    @Test
    fun aHistoryCutShortOnTheFirstRunIsNoticedByTheVaultsHeader() = runBlocking {
        // Audit 2, L-d: on a first run nothing else is known below the cut, which passed as the start.
        val here = device()
        val header = here.storage.sendText("coffer:v1:vault:a header stand-in, only its message is checked")
        val folder = Id.random(sodium)
        val created = here.sendOp(Op.FolderCreate(Id.random(sodium), 1, folder, null, "Trip"))
        here.sendOp(Op.FolderRename(Id.random(sodium), 2, folder, "Trip 2026"))
        clock += days(40)
        here.upload("later.txt", bytes("l"))
        here.indexer.catchUp()
        val shortSighted = object : StorageBackend by here.storage {
            override suspend fun historyBefore(before: MessageId?, limit: Int) =
                here.storage.historyBefore(before, limit).filter { it.id > created.id }
        }
        val maintenance = Maintenance(shortSighted, here.index, here.indexer, here.snapshotter, here.keys, headers = { listOf(header.id) }) { clock * 1000 }
        val ops = cloud.allMessages().filter { Captions.parse((it.content as? MessageContent.Text)?.text)?.kind == RecordKind.OP }.map { it.id }
        assertThrows(StorageException::class.java) { runBlocking { maintenance.run() } }
        assertTrue(ops.all(::present))
    }

    @Test
    fun aCopyOfAPartStandsInForItAtOnce() = runBlocking {
        // Audit 1, L13: the part the index knew is deleted, a copy of it is still in Saved Messages.
        val here = device()
        val fileId = here.upload("kept.txt", bytes("k"))
        here.indexer.catchUp()
        val original = here.index.parts(fileId).getValue(0)
        val caption = (cloud.allMessages().single { it.id == original }.content as MessageContent.Blob).caption
        val copyFile = temp.newFile("cfr_ffffffffffff.bin").apply { writeBytes(here.storage.openBlob(original).use { it.readBytes() }) }
        val copy = here.storage.sendBlob(copyFile, caption).id
        here.indexer.catchUp()
        cloud.deleteFromOutside(listOf(original))

        val report = here.maintenance.run()
        assertEquals(0, report.damaged)
        assertEquals(FileStatus.READY, here.index.file(fileId)!!.status)
        assertEquals(copy, here.index.parts(fileId).getValue(0))
    }

    @Test
    fun aPartThatArrivedWhileReadingIsNotTakenForMissing() = runBlocking {
        val here = device()
        here.upload("early.txt", bytes("e"))
        here.indexer.catchUp()
        val read = cloud.allMessages().sortedBy { it.id }
        // Sent after the history was read, and already applied from its event.
        val late = here.upload("late.txt", bytes("l"))
        here.indexer.catchUp()
        val result = here.indexer.reconcile(read, clock)
        assertEquals(0, result.damaged)
        assertEquals(FileStatus.READY, here.index.file(late)!!.status)
    }

    @Test
    fun aFileWronglyMarkedDamagedIsHealed() = runBlocking {
        val here = device()
        val fileId = here.upload("ok.txt", bytes("o"))
        here.indexer.catchUp()
        // A deletion event for a part that is still there.
        here.indexer.applyDeletion(setOf(here.index.parts(fileId).getValue(0)))
        assertEquals(FileStatus.DAMAGED, here.index.file(fileId)!!.status)
        val report = here.maintenance.run()
        assertEquals(FileStatus.READY, here.index.file(fileId)!!.status)
        assertEquals(1, report.repaired)
        assertEquals(0, report.damaged)
    }

    @Test
    fun aFileTheIndexMissedComesBack() = runBlocking {
        val here = device()
        val fileId = here.upload("missed.txt", bytes("m"))
        here.indexer.catchUp()
        here.index.removeFile(fileId)
        assertEquals(1, here.maintenance.run().repaired)
        assertEquals(FileStatus.READY, here.index.file(fileId)!!.status)
        assertEquals("missed.txt", here.meta(here.index.file(fileId)!!).name)
    }

    @Test
    fun anUnfinishedUploadIsDamagedAfterADayButNotDeleted() = runBlocking {
        val here = device()
        val other = device()
        val fileId = Id.random(sodium)
        val part0 = other.sendPart(fileId, 0, meta("half.bin"))
        here.indexer.catchUp()
        assertEquals(FileStatus.INCOMPLETE, here.index.file(fileId)!!.status)

        clock += hours(23)
        assertEquals(0, here.maintenance.run().damaged)
        assertEquals(FileStatus.INCOMPLETE, here.index.file(fileId)!!.status)

        clock += hours(2)
        assertEquals(1, here.maintenance.run().damaged)
        assertEquals(FileStatus.DAMAGED, here.index.file(fileId)!!.status)
        // Shown to the user rather than removed: after a rescan it could be damage (SPEC §5.7).
        assertTrue(present(part0))
    }

    @Test
    fun partsWhosePartZeroNeverCameGoAfterADay() = runBlocking {
        val here = device()
        val other = device()
        val stray = Id.random(sodium)
        val part1 = other.sendPart(stray, 1, null)
        here.indexer.catchUp()
        assertEquals(setOf(1), here.index.allParts().getValue(stray).keys)

        clock += hours(23)
        assertEquals(0, here.maintenance.run().garbageDeleted)
        assertTrue(present(part1))

        clock += hours(2)
        assertEquals(1, here.maintenance.run().garbageDeleted)
        assertFalse(present(part1))
        assertTrue(stray !in here.index.allParts())
    }

    @Test
    fun partsLeftAfterADeleteGoAtOnce() = runBlocking {
        val here = device()
        val fileId = here.upload("half-deleted.txt", bytes("x"))
        val part = here.index.parts(fileId).getValue(0)
        // The delete went out but deleting the parts did not happen (connection lost, app killed).
        here.sendOp(Op.FileDelete(Id.random(sodium), 1, fileId))
        here.indexer.catchUp()
        assertEquals(1, here.maintenance.run().garbageDeleted)
        assertFalse(present(part))
    }

    @Test
    fun ownUploadInProgressIsLeftAlone() = runBlocking {
        val here = device()
        val prepared = here.prepare("mine.txt", bytes("mine"))
        val fileId = prepared.upload.fileId!!
        here.storage.sendBlob(temp.newFile("cfr_eeeeeeeeeeee.bin").apply { writeBytes(ByteArray(8)) }, prepared.upload.captions[0])
        here.indexer.catchUp()
        clock += days(3)
        val report = here.maintenance.run()
        assertEquals(0, report.damaged + report.garbageDeleted)
        assertEquals(FileStatus.UPLOADING, here.index.file(fileId)!!.status)
    }

    @Test
    fun snapshotsBeyondTheLastThreeGoEvenIfThisDeviceNeverKnewThem() = runBlocking {
        val here = device()
        repeat(3) {
            here.upload("s$it.txt", bytes("$it"))
            here.indexer.catchUp()
            here.snapshotter.snapshotIfNeeded()
        }
        val there = device()
        there.indexer.catchUp()
        assertEquals(1, there.index.snapshots().size)
        there.upload("t.txt", bytes("t"))
        there.indexer.catchUp()
        there.snapshotter.snapshotIfNeeded()
        assertEquals(4, indexBlobs().size)
        assertEquals(1, there.maintenance.run().snapshotsDeleted)
        assertEquals(3, indexBlobs().size)
        assertEquals(indexBlobs().map { it.id }.toSet(), there.index.snapshots().map { it.messageId }.toSet())
    }

    @Test
    fun itIsDueWeeklyAndARescanDoesNotResetThat() = runBlocking {
        val here = device()
        assertTrue(here.maintenance.isDue())
        here.maintenance.run()
        assertFalse(here.maintenance.isDue())
        here.index.replaceContent(IndexContent.EMPTY)
        assertFalse(here.maintenance.isDue())
        clock += days(7) - 1
        assertFalse(here.maintenance.isDue())
        clock += 1
        assertTrue(here.maintenance.isDue())
    }

    // --- compaction ---

    /** Sends [block]'s operations and returns the id of the last message it produced. */
    private suspend fun Device.op(block: suspend Device.() -> Unit): MessageId {
        block()
        return lastId()
    }

    @Test
    fun supersededOperationsGoAndARescanStillAgrees() = runBlocking {
        val here = device()
        val keep = mutableSetOf<MessageId>()
        val drop = mutableSetOf<MessageId>()
        val docs = here.ops.createFolder("Docs", null).also { keep += lastId() }
        val tax = here.ops.createFolder("Tax", docs).also { keep += lastId() }
        val old = here.ops.createFolder("Old", null).also { drop += lastId() }
        here.indexer.catchUp()
        val scan = here.upload("scan.pdf", bytes("scan"), folderId = tax)
        drop += here.op { ops.renameFile(scan, "scan-1.pdf") }
        keep += here.op { ops.renameFile(scan, "scan-2.pdf") }
        drop += here.op { ops.moveFile(scan, tax) }
        keep += here.op { ops.moveFile(scan, docs) }
        drop += here.op { ops.renameFolder(tax, "Taxes") }
        keep += here.op { ops.renameFolder(tax, "Taxes 2025") }
        val doomed = here.upload("doomed.txt", bytes("d"))
        drop += here.op { ops.renameFile(doomed, "doomed-renamed.txt") }
        // Its parts are deleted along with it, so the delete itself has nothing left to delete.
        drop += here.op { deleter.delete(keys, doomed, now = 1) }
        drop += here.op { ops.renameFolder(old, "Older") }
        drop += here.op { ops.deleteFolder(tree(), old) { error("empty") } }
        // About an object that never existed: ignored by every rescan.
        drop += here.op { ops.renameFile(Id.random(sodium), "ghost.txt") }
        // Something after the last operation: the snapshot's last_applied must be above them (SPEC §5.6 says below).
        here.upload("last.txt", bytes("l"))
        here.indexer.catchUp()
        val before = here.view()

        clock += days(31)
        val report = here.maintenance.run()
        assertEquals(drop.size, report.operationsCompacted)
        for (id in drop) assertFalse("$id should be gone", present(id))
        for (id in keep) assertTrue("$id should stay", present(id))

        here.indexer.catchUp()
        assertEquals(before, here.view())
        val fresh = device()
        fresh.indexer.rescan()
        assertEquals(before, fresh.view())
        // And again: nothing more to compact.
        clock += days(1)
        assertEquals(0, here.maintenance.run().operationsCompacted)
    }

    @Test
    fun operationsYoungerThanThirtyDaysStay() = runBlocking {
        val here = device()
        val fileId = here.upload("a.txt", bytes("a"))
        val first = here.op { ops.renameFile(fileId, "b.txt") }
        here.ops.renameFile(fileId, "c.txt")
        here.indexer.catchUp()
        clock += days(29)
        assertEquals(0, here.maintenance.run().operationsCompacted)
        assertTrue(present(first))
        clock += days(2)
        assertEquals(1, here.maintenance.run().operationsCompacted)
        assertFalse(present(first))
    }

    @Test
    fun operationsAfterTheOldestKeptSnapshotStay() = runBlocking {
        val here = device()
        val fileId = here.upload("a.txt", bytes("a"))
        here.indexer.catchUp()
        here.snapshotter.snapshotIfNeeded()
        // Superseded and old enough, but newer than the oldest snapshot: a device falling back to that
        // snapshot would still need it.
        val first = here.op { ops.renameFile(fileId, "b.txt") }
        here.ops.renameFile(fileId, "c.txt")
        here.indexer.catchUp()
        clock += days(31)
        assertEquals(0, here.maintenance.run().operationsCompacted)
        assertTrue(present(first))
        // Once every kept snapshot is newer, it goes.
        repeat(3) {
            here.upload("n$it.txt", bytes("$it"))
            here.indexer.catchUp()
            here.snapshotter.snapshotIfNeeded()
        }
        assertEquals(1, here.maintenance.run().operationsCompacted)
        assertFalse(present(first))
    }

    @Test
    fun aMoveThatALaterIgnoredMoveSeemsToSupersedeStays() = runBlocking {
        val here = device()
        val a = here.ops.createFolder("A", null)
        val x = here.ops.createFolder("X", null)
        val b = here.ops.createFolder("B", a)
        here.indexer.catchUp()
        val effective = here.op { ops.moveFolder(tree(), a, x) }
        // Into its own child: every rescan ignores this one, so the move above stays in force.
        here.sendOp(Op.FolderMove(Id.random(sodium), 1, a, b))
        // Unrelated and plainly superseded: the narrower compaction still takes it.
        val fileId = here.upload("f.txt", bytes("f"))
        val stale = here.op { ops.renameFile(fileId, "g.txt") }
        here.ops.renameFile(fileId, "h.txt")
        here.upload("last.txt", bytes("l"))
        here.indexer.catchUp()
        val before = here.view()
        assertEquals(setOf("A" to "X", "X" to null, "B" to "A"), before.folders)

        clock += days(31)
        assertEquals(1, here.maintenance.run().operationsCompacted)
        assertFalse(present(stale))
        assertTrue(present(effective))
        val fresh = device()
        fresh.indexer.rescan()
        assertEquals(before, fresh.view())
    }

    @Test
    fun aPackGoesOnlyOnceEveryOperationInItCan() = runBlocking {
        val here = device()
        val (a, b) = here.ops.createFolders { create -> create("A", null) to create("B", null) }
        val pack = lastId()
        here.indexer.catchUp()
        val deleteA = here.op { ops.deleteFolder(tree(), a) { error("empty") } }
        here.upload("last.txt", bytes("l"))
        here.indexer.catchUp()
        clock += days(31)
        // B is still there, so the pack stays, and with it A's delete, or A would come back.
        assertEquals(0, here.maintenance.run().operationsCompacted)
        assertTrue(present(pack))
        assertTrue(present(deleteA))

        // Once B is deleted too, both go; B's delete is newer than the snapshot kept from the first run.
        val deleteB = here.op { ops.deleteFolder(tree(), b) { error("empty") } }
        here.upload("later.txt", bytes("l"))
        here.indexer.catchUp()
        val before = here.view()
        clock += days(31)
        assertEquals(2, here.maintenance.run().operationsCompacted)
        assertFalse(present(pack))
        assertFalse(present(deleteA))
        assertTrue(present(deleteB))
        val fresh = device()
        fresh.indexer.rescan()
        assertEquals(before, fresh.view())
    }

    @Test
    fun anEarlierRenameInAPackIsSupersededButThePackStaysForTheLaterOne() = runBlocking {
        val here = device()
        val fileId = here.upload("a.txt", bytes("a"))
        val stale = here.op { ops.renameFile(fileId, "b.txt") }
        here.storage.sendText(
            here.keys.meta.encodeOpPack(listOf(Op.FileRename(Id.random(sodium), 2, fileId, "c.txt"), Op.FileRename(Id.random(sodium), 3, fileId, "d.txt"))),
        )
        val pack = lastId()
        here.upload("last.txt", bytes("l"))
        here.indexer.catchUp()
        clock += days(31)
        assertEquals(1, here.maintenance.run().operationsCompacted)
        assertFalse(present(stale))
        assertTrue(present(pack))
        val fresh = device()
        fresh.indexer.rescan()
        assertEquals(setOf("d.txt", "last.txt"), fresh.view().files.map { it.first }.toSet())
    }

    @Test
    fun opsOnADeletedFileGoEvenWhileItsPartsRemainButTheDeleteStays() = runBlocking {
        // Maintenance deletes such parts first, so only the rules themselves can show this case.
        val here = device()
        val fileId = here.upload("x.txt", bytes("x"))
        val rename = here.op { ops.renameFile(fileId, "y.txt") }
        val delete = here.op { sendOp(Op.FileDelete(Id.random(sodium), 1, fileId)) }
        here.upload("last.txt", bytes("l"))
        val history = cloud.allMessages().sortedBy { it.id }
        val candidates = Compactor(here.storage, here.keys).candidates(history, lastId(), clock + 1, folderMovesSupersede = true)
        assertTrue(rename in candidates)
        assertFalse(delete in candidates)
    }
}
