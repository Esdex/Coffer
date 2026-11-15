package me.esdex.coffer.core.sync

import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.esdex.coffer.core.crypto.ContentLayout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.crypto.Padme
import me.esdex.coffer.core.crypto.format.BlobPayload
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.IndexPayload
import me.esdex.coffer.core.crypto.format.RecordKind
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Index snapshots (SPEC §4.4, §5.4) and the full rescan (§5.5), between devices on one Saved Messages. */
class SnapshotSyncTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val master = MasterKeys.generate(sodium)
    private val cloud = FakeCloud(pageCap = 5)

    private fun device() = Device(cloud, keysOf(master), temp.newFolder())

    private fun bytes(text: String) = BytesSource(text.toByteArray())

    private fun indexBlobs() = cloud.allMessages().filter { Captions.parse((it.content as? MessageContent.Blob)?.caption)?.kind == RecordKind.INDEX }

    /** A device that reads everything from scratch, snapshots aside: the reference (SPEC §11). */
    private suspend fun rescanned(): View {
        val fresh = device()
        fresh.indexer.rescan()
        return fresh.view()
    }

    private class Built(val damaged: Id, val orphan: Id)

    /**
     * A history with every kind of state in it: nested folders, renames and moves, a deleted file and
     * folder, a file damaged outside the app, and a part whose part 0 never came.
     */
    private suspend fun Device.buildHistory(): Built {
        val docs = ops.createFolder("Docs", null)
        val tax = ops.createFolder("Tax", docs)
        val old = ops.createFolder("Old", null)
        indexer.catchUp()
        val scan = upload("scan.pdf", bytes("scan"), folderId = tax)
        ops.renameFile(scan, "scan-2025.pdf")
        ops.moveFile(scan, docs)
        ops.renameFolder(tax, "Taxes")
        upload("notes.txt", bytes("notes"))
        val doomed = upload("doomed.txt", bytes("bye"))
        deleter.delete(keys, doomed, now = 5)
        ops.deleteFolder(VaultTree.of(index, keys), old) { error("empty") }
        val hurt = upload("hurt.bin", bytes("hurt"))
        indexer.catchUp()
        val part = index.parts(hurt).getValue(0)
        cloud.deleteFromOutside(listOf(part))
        indexer.applyDeletion(setOf(part))
        val orphan = Id.random(sodium)
        val size = 512L * 1024 * 1024 + 1
        cloud.postBlobOfSize(keys.meta.encodeBlob(BlobPayload(orphan, 1, 2, size, null)), ContentLayout.encryptedPartSize(size, 1))
        indexer.catchUp()
        return Built(hurt, orphan)
    }

    private val expectedView = View(
        folders = setOf("Docs" to null, "Taxes" to "Docs"),
        files = setOf(
            Triple("scan-2025.pdf", "Docs", FileStatus.READY),
            Triple("notes.txt", null, FileStatus.READY),
            Triple("hurt.bin", null, FileStatus.DAMAGED),
        ),
    )

    /**
     * The same without the damaged file: its only part is gone, so a fresh history holds nothing of it.
     * Only a snapshot remembers it.
     */
    private val historyOnlyView = expectedView.copy(files = expectedView.files.filterNot { it.first == "hurt.bin" }.toSet())

    @Test
    fun aSnapshotRestoresWhatTheWholeHistoryGives() = runBlocking {
        val here = device()
        val built = here.buildHistory()
        assertEquals(expectedView, here.view())
        assertTrue(here.snapshotter.snapshotIfNeeded())

        val there = device()
        val report = there.indexer.catchUp()
        assertEquals(1, there.storage.blobsOpened)
        // The snapshot is the newest message: one page to find it, nothing after it to apply.
        assertTrue("read ${there.storage.messagesRead} of ${cloud.allMessages().size}", there.storage.messagesRead <= 6)
        assertEquals(Indexer.CatchUpReport(there.storage.messagesRead, snapshotUsed = true), report)
        assertEquals(expectedView, there.view())
        here.indexer.catchUp()
        assertEquals(here.index.lastApplied(), there.index.lastApplied())
        assertEquals(setOf(1), there.index.allParts().getValue(built.orphan).keys)
        assertTrue(there.index.isDeleted(here.index.deletedFiles().single()))
        assertEquals(here.index.deletedFolders(), there.index.deletedFolders())
        // Damage, which no full pass can see, came with the snapshot.
        assertEquals(FileStatus.DAMAGED, there.index.file(built.damaged)!!.status)
        // A rescan leaves snapshots aside even when there are some; a reload, like a new device, does not.
        assertEquals(historyOnlyView, rescanned())
        val reloaded = there.indexer.reload()
        assertTrue(reloaded.snapshotUsed)
        assertEquals(expectedView, there.view())
        assertEquals(Indexer.CatchUpReport(cloud.allMessages().size, snapshotUsed = false), device().indexer.rescan())
    }

    @Test
    fun aSnapshotWhoseCaptionDisagreesWithItsContentIsNotUsed() = runBlocking {
        val here = device()
        here.upload("a.txt", bytes("a"))
        here.indexer.catchUp()
        val state = here.indexer.pendingSnapshot()!!.state
        // Sound content and a sound caption, but the caption claims more than the content covers.
        val snapshotId = Id.random(sodium)
        val sealed = here.keys.snapshots.encode(snapshotId, state)
        here.storage.sendBlob(
            temp.newFile("cfr_dddddddddddd.bin").apply { writeBytes(sealed.bytes) },
            here.keys.meta.encodeIndex(IndexPayload(snapshotId, state.lastAppliedMessageId + 1, 1, sealed.plainSize, padded = true)),
        )
        here.upload("b.txt", bytes("b"))

        val there = device()
        there.indexer.catchUp()
        assertEquals(1, there.storage.blobsOpened)
        assertEquals(View(emptySet(), setOf(Triple("a.txt", null, FileStatus.READY), Triple("b.txt", null, FileStatus.READY))), there.view())
        // Read in full: every message, not just those after the snapshot. Twice: down looking for a snapshot,
        // then up applying a page at a time, as nothing read is kept past its page (audit 2, L-g).
        assertEquals(2 * cloud.allMessages().size, there.storage.messagesRead)
    }

    @Test
    fun withoutSnapshotsAFullPassAgreesExceptForDamage() = runBlocking {
        val here = device()
        here.buildHistory()
        assertEquals(historyOnlyView, rescanned())
    }

    @Test
    fun changesAfterTheSnapshotAreAppliedOnTop() = runBlocking {
        val here = device()
        here.buildHistory()
        here.snapshotter.snapshotIfNeeded()
        val later = here.upload("later.txt", bytes("later"))
        here.ops.renameFolder(here.tree().folders.entries.single { it.value.name == "Docs" }.key, "Documents")
        here.ops.moveFile(later, here.tree().folders.entries.single { it.value.name == "Taxes" }.key)
        here.indexer.catchUp()

        val there = device()
        there.indexer.catchUp()
        assertEquals(1, there.storage.blobsOpened)
        assertEquals(here.view(), there.view())
        assertTrue(Triple("later.txt", "Taxes", FileStatus.READY) in there.view().files)
    }

    @Test
    fun anIndexOlderThanTheSnapshotIsReplacedANewerOneIsNot() = runBlocking {
        val here = device()
        val there = device()
        here.upload("first.txt", bytes("1"))
        there.indexer.catchUp()
        here.buildHistory()
        here.snapshotter.snapshotIfNeeded()

        there.indexer.catchUp()
        assertEquals(1, there.storage.blobsOpened)
        assertEquals(here.view(), there.view())

        // Now level with the snapshot: catching up again reads only what is new.
        here.upload("second.txt", bytes("2"))
        there.indexer.catchUp()
        assertEquals(1, there.storage.blobsOpened)
        assertEquals(here.view().also { here.indexer.catchUp() }.files + Triple("second.txt", null, FileStatus.READY), there.view().files)
    }

    @Test
    fun aSnapshotThatDoesNotOpenFallsBackToAnOlderOneOrTheFullHistory() = runBlocking {
        val here = device()
        here.buildHistory()
        here.snapshotter.snapshotIfNeeded()
        here.upload("after.txt", bytes("a"))
        here.indexer.catchUp()
        val reference = here.view()
        // A caption that opens, over a document that does not: of the right size, so it is opened and tried.
        val last = here.index.lastApplied()!!.value
        val rightSize = ContentLayout.encryptedPartSize(Padme.contentSize(460, true), 0).toInt()
        here.storage.sendBlob(
            temp.newFile("cfr_cccccccccccc.bin").apply { writeBytes(Random(1).nextBytes(rightSize)) },
            here.keys.meta.encodeIndex(IndexPayload(Id.random(sodium), last, 1, 460, padded = true)),
        )

        val there = device()
        there.indexer.catchUp()
        assertEquals(2, there.storage.blobsOpened)
        assertEquals(reference, there.view())

        // With the good one gone too, the whole history is read.
        cloud.deleteFromOutside(indexBlobs().filter { it.content.let { c -> c is MessageContent.Blob && c.size != rightSize.toLong() } }.map { it.id })
        val third = device()
        third.indexer.catchUp()
        assertEquals(1, third.storage.blobsOpened)
        assertEquals(reference.copy(files = reference.files.filterNot { it.first == "hurt.bin" }.toSet()), third.view())
    }

    @Test
    fun aSnapshotDeletedWhileBeingLoadedFallsBackToAnOlderOne() = runBlocking {
        val here = device()
        here.buildHistory()
        here.snapshotter.snapshotIfNeeded()
        here.upload("after.txt", bytes("a"))
        here.indexer.catchUp()
        here.snapshotter.snapshotIfNeeded()
        here.indexer.catchUp()
        val reference = here.view()

        // Another device prunes the newest snapshot just as this one is about to read it.
        val there = device()
        var first = true
        there.storage.beforeOpen = { id ->
            if (first) {
                first = false
                cloud.deleteFromOutside(listOf(id))
            }
        }
        val report = there.indexer.catchUp()
        assertTrue(report.snapshotUsed)
        assertEquals(1, there.storage.blobsOpened)
        assertEquals(reference, there.view())
    }

    @Test
    fun aSnapshotThatCannotBeFetchedForOtherReasonsStopsTheCatchUp() = runBlocking {
        val here = device()
        here.upload("a.txt", bytes("a"))
        here.indexer.catchUp()
        here.snapshotter.snapshotIfNeeded()
        val there = device()
        there.storage.beforeOpen = { throw me.esdex.coffer.core.storage.StorageException("connection lost") }
        try {
            there.indexer.catchUp()
            error("a network failure must not pass for a damaged snapshot")
        } catch (_: me.esdex.coffer.core.storage.StorageException) {
        }
        // Nothing half done: the next attempt starts from scratch and works.
        there.storage.beforeOpen = {}
        there.indexer.catchUp()
        assertEquals(here.view(), there.view())
    }

    @Test
    fun anotherVaultsSnapshotIsNotOpened() = runBlocking {
        val here = device()
        here.upload("mine.txt", bytes("m"))
        val stranger = Device(cloud, keysOf(MasterKeys.generate(sodium)), temp.newFolder())
        stranger.upload("theirs.txt", bytes("t"))
        stranger.indexer.catchUp()
        assertTrue(stranger.snapshotter.snapshotIfNeeded())

        val there = device()
        there.indexer.catchUp()
        assertEquals(0, there.storage.blobsOpened)
        assertEquals(View(emptySet(), setOf(Triple("mine.txt", null, FileStatus.READY))), there.view())
    }

    @Test
    fun noSnapshotWithoutChangesAndNoneInReplyToAnotherDevicesSnapshot() = runBlocking {
        val here = device()
        val there = device()
        assertFalse(here.snapshotter.snapshotIfNeeded())
        here.upload("a.txt", bytes("a"))
        here.indexer.catchUp()
        assertTrue(here.snapshotter.snapshotIfNeeded())
        assertFalse(here.snapshotter.snapshotIfNeeded())
        // There sees the change and the snapshot covering it.
        there.indexer.catchUp()
        assertFalse(there.snapshotter.snapshotIfNeeded())
        // A change of its own does call for one, and here, seeing it, does not answer.
        there.upload("b.txt", bytes("b"))
        there.indexer.catchUp()
        assertTrue(there.snapshotter.snapshotIfNeeded())
        here.indexer.catchUp()
        assertFalse(here.snapshotter.snapshotIfNeeded())
        assertEquals(2, indexBlobs().size)
    }

    @Test
    fun damageCallsForANewSnapshot() = runBlocking {
        val here = device()
        val fileId = here.upload("fragile.bin", bytes("f"))
        here.indexer.catchUp()
        assertTrue(here.snapshotter.snapshotIfNeeded())
        assertNull(here.indexer.pendingSnapshot())
        val part = here.index.parts(fileId).getValue(0)
        cloud.deleteFromOutside(listOf(part))
        here.indexer.applyDeletion(setOf(part))
        assertNotNull(here.indexer.pendingSnapshot())
        assertTrue(here.snapshotter.snapshotIfNeeded())
        assertNull(here.indexer.pendingSnapshot())

        val there = device()
        there.indexer.catchUp()
        assertEquals(FileStatus.DAMAGED, there.index.file(fileId)!!.status)
    }

    /** [message] sent again, as anyone in the Telegram account can: the same document and caption, a new id. */
    private suspend fun Device.replay(message: MessageId): MessageId {
        val original = cloud.allMessages().single { it.id == message }
        val caption = (original.content as MessageContent.Blob).caption
        val copy = temp.newFile("cfr_" + Random.nextLong().toULong().toString(16).padStart(12, '0').take(12) + ".bin").apply { writeBytes(storage.openBlob(message).use { it.readBytes() }) }
        return storage.sendBlob(copy, caption).id
    }

    @Test
    fun anOldSnapshotSentAgainDoesNotRollANewDeviceBack() = runBlocking {
        // Audit 1, M5: the old snapshot comes back as the newest message.
        val here = device()
        var now = 1_762_531_200_000
        val snapshotter = Snapshotter(here.storage, here.index, here.indexer, here.keys, temp.newFolder()) { now += 60_000; now }
        here.upload("kept.txt", bytes("k"))
        val hurt = here.upload("hurt.bin", bytes("h"))
        here.indexer.catchUp()
        assertTrue(snapshotter.snapshotIfNeeded())
        val old = here.index.snapshots().single().messageId
        // Damage only a snapshot carries: no full pass can see it.
        val part = here.index.parts(hurt).getValue(0)
        cloud.deleteFromOutside(listOf(part))
        here.indexer.applyDeletion(setOf(part))
        assertTrue(snapshotter.snapshotIfNeeded())
        here.indexer.catchUp()
        here.replay(old)

        val there = device()
        assertTrue(there.indexer.catchUp().snapshotUsed)
        assertEquals(FileStatus.DAMAGED, there.index.file(hurt)!!.status)
    }

    @Test
    fun aSnapshotWhoseDocumentWasSwappedIsNotDownloaded() = runBlocking {
        // Audit 1, L12: a real caption over a document of another size, as editMessageMedia could leave.
        val here = device()
        here.upload("a.txt", bytes("a"))
        here.indexer.catchUp()
        assertTrue(here.snapshotter.snapshotIfNeeded())
        val real = indexBlobs().single()
        val caption = (real.content as MessageContent.Blob).caption
        val swapped = temp.newFile("cfr_eeeeeeeeeeee.bin").apply { writeBytes(Random(2).nextBytes(3 * 1024 * 1024)) }
        here.storage.sendBlob(swapped, caption)

        val there = device()
        assertTrue(there.indexer.catchUp().snapshotUsed)
        // Only the real one was opened.
        assertEquals(1, there.storage.blobsOpened)
    }

    @Test
    fun copiesOfTheNewestSnapshotOverJunkDoNotPushOutTheOthers() = runBlocking {
        // Audit 2, L-f: its caption over three documents of its size took all three places, and Coffer
        // deleted the genuine older snapshots itself.
        val here = device()
        val real = mutableListOf<MessageId>()
        repeat(3) {
            here.upload("f$it.txt", bytes("$it"))
            here.indexer.catchUp()
            assertTrue(here.snapshotter.snapshotIfNeeded())
            real += here.index.snapshots().maxOf { s -> s.messageId }
        }
        val newest = indexBlobs().single { it.id == real.last() }.content as MessageContent.Blob
        repeat(3) { cloud.postBlobOfSize(newest.caption, newest.size) }
        here.indexer.catchUp()
        here.upload("last.txt", bytes("l"))
        here.indexer.catchUp()
        assertTrue(here.snapshotter.snapshotIfNeeded())
        val latest = here.index.snapshots().maxOf { s -> s.lastApplied }.let { state -> here.index.snapshots().single { it.lastApplied == state }.messageId }
        // The three newest states, each by its first message; the copies go.
        assertEquals(setOf(real[1], real[2], latest), indexBlobs().map { it.id }.toSet())
    }

    @Test
    fun oldSnapshotsSentAgainDoNotPushOutTheNewest() = runBlocking {
        val here = device()
        val real = mutableListOf<MessageId>()
        repeat(3) {
            here.upload("f$it.txt", bytes("$it"))
            here.indexer.catchUp()
            assertTrue(here.snapshotter.snapshotIfNeeded())
            real += here.index.snapshots().maxOf { s -> s.messageId }
        }
        repeat(3) { here.replay(real.first()) }
        here.indexer.catchUp()
        here.upload("last.txt", bytes("l"))
        here.indexer.catchUp()
        assertTrue(here.snapshotter.snapshotIfNeeded())
        val newest = here.index.snapshots().maxOf { s -> s.messageId }
        // The three newest states stay, the copies of the oldest go.
        assertEquals((real.drop(1) + newest).toSet(), indexBlobs().map { it.id }.toSet())
    }

    @Test
    fun theIntegrityCheckKeepsTheNewestStatesNotTheNewestCopies() = runBlocking {
        val here = device()
        val real = mutableListOf<MessageId>()
        repeat(3) {
            here.upload("f$it.txt", bytes("$it"))
            here.indexer.catchUp()
            assertTrue(here.snapshotter.snapshotIfNeeded())
            real += here.index.snapshots().maxOf { s -> s.messageId }
        }
        repeat(3) { here.replay(real.first()) }
        here.indexer.catchUp()
        here.maintenance.run()
        // The two newest stay as they are; of the oldest state one copy is left, the original or a copy
        // sent again, which hold the same.
        val kept = indexBlobs().map { it.id }.toSet()
        assertEquals(3, kept.size)
        assertTrue(kept.containsAll(real.drop(1)))
    }

    @Test
    fun onlyTheLastThreeAreKept() = runBlocking {
        val here = device()
        val sent = mutableListOf<MessageId>()
        repeat(5) {
            here.upload("f$it.txt", bytes("$it"))
            here.indexer.catchUp()
            assertTrue(here.snapshotter.snapshotIfNeeded())
            sent += here.index.snapshots().maxOf { s -> s.messageId }
        }
        assertEquals(sent.takeLast(3).toSet(), indexBlobs().map { it.id }.toSet())
        assertEquals(sent.takeLast(3).toSet(), here.index.snapshots().map { it.messageId }.toSet())
    }

    @Test
    fun aFollowingDeviceForgetsSnapshotsDeletedElsewhere() = runBlocking {
        val here = device()
        val there = device()
        // Following from before the first snapshot: a device that catches up later loads the newest
        // snapshot and never hears of older ones (SPEC §4.4), which is not what this test is about.
        val caughtUp = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) { there.indexer.run(onCaughtUp = { caughtUp.complete(Unit) }) }
        try {
            caughtUp.await()
            repeat(5) {
                here.upload("f$it.txt", bytes("$it"))
                here.indexer.catchUp()
                here.snapshotter.snapshotIfNeeded()
            }
            val kept = indexBlobs().map { it.id }.toSet()
            assertEquals(3, kept.size)
            withTimeout(10_000) {
                while (there.index.snapshots().map { it.messageId }.toSet() != kept) delay(20)
            }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun rescanRebuildsTheSameIndexAndKeepsOwnUploads() = runBlocking {
        val here = device()
        here.buildHistory()
        val source = bytes("pending")
        val prepared = here.prepare("pending.txt", source)
        val fileId = prepared.upload.fileId!!
        here.indexer.rescan()
        assertEquals(FileStatus.UPLOADING, here.index.file(fileId)!!.status)
        here.runner.run(prepared.upload, prepared.keys, source)
        here.indexer.catchUp()
        val view = here.view()
        assertTrue(Triple("pending.txt", null, FileStatus.READY) in view.files)
        // Damage is the one thing a rescan cannot know: the file whose only part was deleted is gone.
        assertTrue(view.files.none { it.first == "hurt.bin" })
        assertEquals(view, rescanned())
    }

    @Test
    fun loadingASnapshotKeepsOwnUploads() = runBlocking {
        val here = device()
        val there = device()
        there.upload("early.txt", bytes("e"))
        there.indexer.catchUp()
        here.buildHistory()
        here.snapshotter.snapshotIfNeeded()
        // There has an upload prepared but not sent while its index falls behind the snapshot.
        val source = bytes("mine")
        val prepared = there.prepare("mine.txt", source)
        there.indexer.catchUp()
        assertEquals(1, there.storage.blobsOpened)
        assertEquals(FileStatus.UPLOADING, there.index.file(prepared.upload.fileId!!)!!.status)
        there.runner.run(prepared.upload, prepared.keys, source)
        there.indexer.catchUp()
        assertTrue(Triple("mine.txt", null, FileStatus.READY) in there.view().files)
    }
}
