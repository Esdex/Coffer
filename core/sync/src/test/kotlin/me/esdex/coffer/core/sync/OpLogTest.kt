package me.esdex.coffer.core.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageEvent
import me.esdex.coffer.core.storage.StoredMessage
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

    /**
     * A device following live whose events arrive in the order a test chooses: [deliver] hands it any
     * message from the cloud. Returns once its catch-up is done; [late] collects what it reported late.
     */
    private class Puppet(val device: Device, scope: kotlinx.coroutines.CoroutineScope) {
        private val events = kotlinx.coroutines.flow.MutableSharedFlow<StorageEvent>(extraBufferCapacity = 64)
        val late = java.util.concurrent.CopyOnWriteArrayList<MessageId>()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        private val storage = object : StorageBackend by device.storage {
            override val events = this@Puppet.events
        }
        private val caughtUp = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = scope.launch {
            Indexer(storage, device.index, device.keys).run(
                onCaughtUp = { reads.incrementAndGet(); caughtUp.complete(Unit) },
                onLate = { late += it },
            )
        }

        suspend fun started() = caughtUp.await()

        suspend fun deliver(message: StoredMessage) = events.emit(StorageEvent.NewMessage(message))
    }

    private suspend fun lastMessages(count: Int): List<StoredMessage> =
        cloud.device().historyBefore(null, count).reversed()

    @Test
    fun anOperationDeliveredLateIsAppliedInItsPlace() = runBlocking {
        val here = device()
        val a = here.ops.createFolder("A", null)
        val b = here.ops.createFolder("B", null)
        val follower = device()
        val puppet = Puppet(follower, this)
        puppet.started()

        // In message order: A goes into B, then B into A, which would make a loop and is ignored.
        here.sendOp(Op.FolderMove(newId(), 1, a, b))
        here.sendOp(Op.FolderMove(newId(), 2, b, a))
        val (first, second) = lastMessages(2)
        // Delivered the other way round: the second arrives, then the first, late.
        puppet.deliver(second)
        puppet.deliver(first)

        val expected = View(folders = setOf("A" to "B", "B" to null), files = emptySet())
        withTimeout(10_000) { while (follower.view() != expected) kotlinx.coroutines.delay(10) }
        // The read again covers both: delivered again, they are repeats, not another late pair.
        puppet.deliver(first)
        puppet.deliver(second)
        kotlinx.coroutines.delay(100)
        assertEquals(listOf(first.id), puppet.late.toList())
        assertEquals(2, puppet.reads.get())
        assertEquals(expected, follower.view())
        assertRescanAgrees(expected)
        puppet.job.cancel()
    }

    @Test
    fun aRepeatedOrCoveredMessageIsNotTakenForALateOne() = runBlocking {
        val here = device()
        here.ops.createFolder("Before", null)
        val covered = lastMessages(1).single()
        val follower = device()
        val puppet = Puppet(follower, this)
        puppet.started()
        // Already read by the catch-up, then delivered: nothing new.
        puppet.deliver(covered)
        here.ops.createFolder("After", null)
        val fresh = lastMessages(1).single()
        puppet.deliver(fresh)
        puppet.deliver(fresh)

        val expected = View(folders = setOf("Before" to null, "After" to null), files = emptySet())
        withTimeout(10_000) { while (follower.view() != expected) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(100)
        assertTrue(puppet.late.isEmpty())
        assertEquals(1, puppet.reads.get())
        puppet.job.cancel()
    }

    /** [message] sent again, as anyone in the Telegram account can: the same text or document, a new id. */
    private suspend fun Device.replay(message: MessageId) {
        when (val content = cloud.allMessages().single { it.id == message }.content) {
            is MessageContent.Text -> storage.sendText(content.text)
            is MessageContent.Blob -> {
                val copy = temp.newFile("cfr_" + kotlin.random.Random.nextLong().toULong().toString(16).padStart(12, '0').take(12) + ".bin")
                copy.writeBytes(storage.openBlob(message).use { it.readBytes() })
                storage.sendBlob(copy, content.caption)
            }
            MessageContent.Other -> error("not a vault message")
        }
    }

    private fun lastMessage() = cloud.allMessages().maxOf { it.id }

    @Test
    fun anOperationSentAgainIsNotAppliedTwice() = runBlocking {
        // Audit 1, M4: an old rename forwarded later must not undo the newer one.
        val here = device()
        val folder = here.ops.createFolder("Trip", null)
        here.ops.renameFolder(folder, "Trip 2025")
        val firstRename = lastMessage()
        here.ops.renameFolder(folder, "Trip 2026")
        here.indexer.catchUp()
        here.replay(firstRename)

        val there = device()
        there.indexer.catchUp()
        here.indexer.catchUp()
        for (device in listOf(here, there)) assertEquals(setOf("Trip 2026" to null), device.view().folders)
    }

    @Test
    fun aStorageErrorWhileCatchingUpIsWaitedOutNotThrown() = kotlinx.coroutines.test.runTest {
        // Audit 2, L-h: an error from Telegram while catching up went uncaught and crashed the app.
        val here = device()
        here.ops.createFolder("Docs", null)
        val there = device()
        var failures = 2
        val flaky = object : StorageBackend by there.storage {
            override suspend fun historyBefore(before: MessageId?, limit: Int): List<StoredMessage> {
                if (failures-- > 0) throw StorageException("connection lost")
                return there.storage.historyBefore(before, limit)
            }
        }
        val errors = mutableListOf<StorageException>()
        val caughtUp = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch {
            Indexer(flaky, there.index, there.keys).follow(onCaughtUp = { caughtUp.complete(Unit) }, onError = { errors += it })
        }
        caughtUp.await()
        assertEquals(2, errors.size)
        assertEquals(setOf("Docs" to null), there.view().folders)
        job.cancel()
    }

    @Test
    fun aGenuineCaptionOverAnotherDocumentIsNoPart() = runBlocking {
        // Audit 2, L-c: the part deleted, its caption posted again over junk; taken for the part, it made
        // the file READY and had the junk downloaded whole.
        val here = device()
        val fileId = here.upload("photo.jpg", bytes("the real content of the photo"))
        here.indexer.catchUp()
        val part = cloud.allMessages().single { it.id == here.index.parts(fileId).getValue(0) }
        here.storage.delete(listOf(part.id))
        val junk = temp.newFile("cfr_" + kotlin.random.Random.nextLong().toULong().toString(16).padStart(12, '0').take(12) + ".bin")
        junk.writeBytes(ByteArray(5_000))
        here.storage.sendBlob(junk, (part.content as MessageContent.Blob).caption)

        val there = device()
        there.indexer.catchUp()
        assertTrue(there.index.parts(fileId).isEmpty())
        // Nor does the integrity check take it as the part.
        there.maintenance.run()
        assertTrue(there.index.parts(fileId).isEmpty())
    }

    @Test
    fun anOperationSentAgainAfterASnapshotLoadIsNotApplied() = runBlocking {
        // Audit 2, N2: loading a snapshot forgot which operations had been applied, so a copy counted as new.
        val here = device()
        val there = device()
        val folder = here.ops.createFolder("Trip", null)
        here.ops.renameFolder(folder, "Trip 2025")
        val firstRename = lastMessage()
        there.indexer.catchUp()
        here.ops.renameFolder(folder, "Trip 2026")
        here.indexer.catchUp()
        assertTrue(here.snapshotter.snapshotIfNeeded())
        assertTrue(there.indexer.catchUp().snapshotUsed)
        here.replay(firstRename)

        there.indexer.catchUp()
        assertEquals(setOf("Trip 2026" to null), there.view().folders)
    }

    @Test
    fun anOperationSentAgainIsNotAppliedByARescanOnceItsOriginalIsGone() = runBlocking {
        // Audit 2, N2: a rescan forgot which operations had been applied; with the original deleted, as
        // anyone in the account can, or compacted, the copy was the only one left and counted as new.
        val here = device()
        val folder = here.ops.createFolder("Trip", null)
        here.ops.renameFolder(folder, "Trip 2025")
        val firstRename = lastMessage()
        here.ops.renameFolder(folder, "Trip 2026")
        here.indexer.catchUp()
        here.replay(firstRename)
        here.indexer.catchUp()
        here.storage.delete(listOf(firstRename))

        // The rescan rebuilds from nothing: "Trip 2026" means it applied the later rename again, from its own message.
        here.indexer.rescan()
        assertEquals(setOf("Trip 2026" to null), here.view().folders)
    }

    @Test
    fun aDeletedFileStaysDeletedThroughARescanWhenItsPartsArePostedAgain() = runBlocking {
        // Audit 2, N2: with the delete gone from Saved Messages and copies of the parts posted again, a
        // rescan brought the file back.
        val here = device()
        val fileId = here.upload("secret.txt", bytes("s"))
        here.indexer.catchUp()
        val parts = here.index.parts(fileId).values.map { id -> cloud.allMessages().single { it.id == id } }
        val kept = parts.map { message ->
            val copy = temp.newFile("cfr_" + kotlin.random.Random.nextLong().toULong().toString(16).padStart(12, '0').take(12) + ".bin")
            copy.writeBytes(here.storage.openBlob(message.id).use { it.readBytes() })
            copy to (message.content as MessageContent.Blob).caption
        }
        here.deleter.delete(here.keys, fileId, now = 1)
        here.indexer.catchUp()
        val deleteOp = lastMessage()
        for ((copy, caption) in kept) here.storage.sendBlob(copy, caption)
        here.storage.delete(listOf(deleteOp))

        here.indexer.rescan()
        assertEquals(View(emptySet(), emptySet()), here.view())
        assertTrue(here.index.isDeleted(fileId))
    }

    @Test
    fun aFilesFirstPartSentAgainDoesNotBringBackItsOldName() = runBlocking {
        val here = device()
        val fileId = here.upload("draft.txt", bytes("d"))
        here.indexer.catchUp()
        val first = here.index.parts(fileId).getValue(0)
        here.ops.renameFile(fileId, "final.txt")
        here.indexer.catchUp()
        here.replay(first)
        here.indexer.catchUp()

        val there = device()
        there.indexer.catchUp()
        for (device in listOf(here, there)) {
            assertEquals(setOf("final.txt"), device.view().files.map { it.first }.toSet())
            // The part stays the one first sent.
            assertEquals(first, device.index.parts(fileId).getValue(0))
        }
    }

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
