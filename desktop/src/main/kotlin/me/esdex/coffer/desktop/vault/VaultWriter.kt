package me.esdex.coffer.desktop.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.OpMessage
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.OpQueue
import me.esdex.coffer.core.sync.VaultKeys
import me.esdex.coffer.core.sync.VaultOps
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.desktop.drive.DriveEdits
import me.esdex.coffer.desktop.drive.DriveTree
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Changes made on the drive while the vault is open: folders made, things renamed, moved and deleted, as
 * the Android app makes them, through the queue of operations (SPEC §4.3, §4.6), which outlives the app.
 * [tree] shows them at once (see [PendingOps]). Deletes made in a burst, as a file manager deletes a folder
 * file by file, go as one batch: one message per file would run into Telegram's pause after about twenty.
 */
class VaultWriter(
    private val scope: CoroutineScope,
    private val index: VaultIndex,
    private val keys: VaultKeys,
    private val storage: suspend () -> StorageBackend,
    private val clock: () -> Long = System::currentTimeMillis,
) : DriveEdits {
    private val treeFlow = MutableStateFlow(DriveTree.EMPTY)

    /** What the drive shows. */
    val tree: StateFlow<DriveTree> = treeFlow.asStateFlow()

    private val treeLock = Mutex()
    private val editLock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Made on this computer and not back from Telegram yet: still to be shown. Taken as the queue gets them,
    // so no moment between the queue and Telegram finds one in neither (VaultWriterTest caught that).
    private val made = CopyOnWriteArrayList<Op>()

    // The index the queue writes through: every batch of operations queued here is noted on its way in.
    private val noting = object : VaultIndex by index {
        override suspend fun putOps(batch: String, kind: String, messages: List<OpMessage>) {
            made += messages.flatMap { m -> Captions.parse(m.caption)?.let(keys.meta::decodeOps).orEmpty() }
            index.putOps(batch, kind, messages)
        }
    }

    // Deleted on the drive, not queued yet: gone from it already. Under editLock.
    private val deletingFiles = LinkedHashSet<Id>()
    private val deletingFolders = LinkedHashSet<Id>()
    private var deleteFlush: Job? = null

    suspend fun start() {
        // What an earlier session queued shows the same way; taken before anything is sent.
        made.addAll(0, PendingOps.of(index.ops(), keys))
        scope.launch {
            combine(index.observeFiles(), index.observeFolders(), index.observeOps()) { _, _, _ -> }.collect { rebuild() }
        }
        scope.launch { for (signal in wake) sendQueue() }
        // What an earlier session left in the queue goes now.
        wake.trySend(Unit)
    }

    override suspend fun createFolder(parentId: Id?, name: String) = edit { ops -> ops.createFolder(name, parentId) }

    override suspend fun move(entry: DriveTree.Entry, from: DriveTree.Folder, shownName: String, to: DriveTree.Folder, name: String) = edit { ops ->
        val moved = from.folderId != to.folderId
        val renamed = name != shownName
        when (entry) {
            is DriveTree.File -> {
                if (moved) ops.moveFile(entry.fileId, to.folderId)
                if (renamed) ops.renameFile(entry.fileId, name)
            }
            is DriveTree.Folder -> {
                val folderId = checkNotNull(entry.folderId) { "the root does not move" }
                if (moved) ops.moveFolder(overlay(), folderId, to.folderId)
                if (renamed) ops.renameFolder(folderId, name)
            }
        }
    }

    /** Gone from the drive at once; queued with whatever else is deleted in the next moment. */
    override suspend fun delete(entry: DriveTree.Entry) {
        editLock.withLock {
            when (entry) {
                is DriveTree.File -> deletingFiles += entry.fileId
                is DriveTree.Folder -> deletingFolders += checkNotNull(entry.folderId) { "the root is not deleted" }
            }
            deleteFlush?.cancel()
            deleteFlush = scope.launch {
                delay(DELETE_GATHER_MS)
                editLock.withLock { flushDeletes() }
                rebuild()
            }
        }
        rebuild()
    }

    /** Queues the deletes gathered so far, as one batch; the vault locking does it before the keys go. */
    suspend fun flush() = editLock.withLock { flushDeletes() }

    private suspend fun flushDeletes() {
        if (deletingFiles.isEmpty() && deletingFolders.isEmpty()) return
        val files = deletingFiles.toList()
        val folders = deletingFolders.toList()
        ops().deleteMany(overlay(), folders, files, partsOf = { index.parts(it).values })
        deletingFiles.clear()
        deletingFolders.clear()
        wake.trySend(Unit)
    }

    private suspend fun edit(block: suspend (VaultOps) -> Unit) {
        editLock.withLock { block(ops()) }
        wake.trySend(Unit)
        // Shown before the call returns: the file manager looks right after.
        rebuild()
    }

    private suspend fun ops() = VaultOps(storage(), keys, OpQueue(noting), clock)

    /**
     * The vault with this computer's operations in, those not back from Telegram yet. The indexer marks an
     * operation applied a moment before it changes the row; a rebuild in that moment shows the old row, and
     * the change, a moment later, rebuilds again.
     */
    private suspend fun overlay(): VaultTree {
        made.removeAll(made.filter { index.opAppliedFrom(it.opId) != null }.toSet())
        return PendingOps.apply(VaultTree.of(index, keys), made.toList())
    }

    private suspend fun rebuild() = treeLock.withLock {
        val gone = editLock.withLock { deletingFiles + deletingFolders }
        val whole = index.files().filter { it.status == FileStatus.READY }.associateBy { it.fileId }
        treeFlow.value = DriveTree.of(PendingOps.without(overlay(), gone), whole)
    }

    /** Sends the queue at Telegram's pace; a FLOOD_WAIT is waited out, any other failure tried again later. */
    private suspend fun sendQueue() {
        while (true) {
            try {
                OpQueue(index).drain(storage(), pause = { delay(OpQueue.PACE_MILLIS) }) { message, left ->
                    log("sent ${message.opCount} operations, ${left.sumOf { it.opCount }} left")
                }
                return
            } catch (e: FloodWaitException) {
                log("Telegram asks to wait ${e.seconds} s before the next operation")
                delay(e.seconds * 1000L)
            } catch (e: StorageException) {
                // Its kind only: a message could carry more than the project's rule on logs allows.
                log("sending operations failed (${e.javaClass.simpleName}); trying again")
                delay(RETRY_MS)
            }
        }
    }

    private companion object {
        const val DELETE_GATHER_MS = 700L
        const val RETRY_MS = 10_000L
    }
}
