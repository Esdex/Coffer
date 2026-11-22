package me.esdex.coffer.desktop.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.OpMessage
import me.esdex.coffer.core.index.PendingUpload
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Changes made on the drive while the vault is open: folders made, things renamed, moved and deleted, as
 * the Android app makes them, through the queue of operations (SPEC §4.3, §4.6), which outlives the app.
 * [tree] shows them at once (see [PendingOps]). Deletes made in a burst, as a file manager deletes a folder
 * file by file, go as one batch: one message per file would run into Telegram's pause after about twenty.
 * Files written go up as [Drafts], from copies in [draftsDir].
 */
class VaultWriter(
    private val scope: CoroutineScope,
    private val index: VaultIndex,
    private val keys: VaultKeys,
    private val storage: suspend () -> StorageBackend,
    draftsDir: File,
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

    private val drafts = Drafts(scope, index, keys, storage, draftsDir, clock, ::uploaded, ::rebuild)

    /** What goes to Telegram from here: for the tray. */
    data class Transfers(val uploading: List<Drafts.Progress>, val waiting: Int, val operations: Int) {
        val idle: Boolean get() = uploading.isEmpty() && waiting == 0 && operations == 0

        companion object {
            val NONE = Transfers(emptyList(), 0, 0)
        }
    }

    /** Uploads going on and waiting, and operations to send; held deletes wait for an upload, not counted. */
    val transfers: StateFlow<Transfers> = combine(drafts.progress, index.observeUploads(), index.observeOps()) { going, uploads, ops ->
        Transfers(
            uploading = going.values.toList(),
            waiting = uploads.count { it.uploadId !in going },
            operations = ops.filter { it.caption.isNotEmpty() }.sumOf { it.opCount },
        )
    }.stateIn(scope, SharingStarted.Eagerly, Transfers.NONE)

    // Deleted on the drive, not queued yet: gone from it already. Under editLock.
    private val deletingFiles = LinkedHashSet<Id>()
    private val deletingFolders = LinkedHashSet<Id>()
    private var deleteFlush: Job? = null

    suspend fun start() {
        // What an earlier session queued shows the same way; taken before anything is sent.
        made.addAll(0, PendingOps.of(index.ops(), keys))
        scope.launch {
            combine(index.observeFiles(), index.observeFolders(), index.observeOps(), index.observeUploads()) { _, _, _, _ -> }.collect { rebuild() }
        }
        scope.launch { for (signal in wake) sendQueue() }
        // What an earlier session left in the queue goes now, uploads too.
        wake.trySend(Unit)
        drafts.start()
    }

    override suspend fun createFolder(parentId: Id?, name: String) = edit { ops -> ops.createFolder(name, parentId) }

    override suspend fun move(entry: DriveTree.Entry, from: DriveTree.Folder, shownName: String, to: DriveTree.Folder, name: String, replacing: DriveTree.Entry?) {
        // A draft saved over a file takes its place once it is whole, as the phone replaces one; the old file
        // stays until then. Anything else in the way goes now.
        if (entry is DriveTree.Draft) {
            if (replacing is DriveTree.Draft) delete(replacing)
            drafts.move(entry, to.folderId, name, replaces = (replacing as? DriveTree.File)?.fileId)
            return
        }
        replacing?.let { delete(it) }
        edit { ops ->
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
                is DriveTree.Draft -> Unit
            }
        }
    }

    /** Gone from the drive at once; queued with whatever else is deleted in the next moment. */
    override suspend fun delete(entry: DriveTree.Entry) {
        if (entry is DriveTree.Draft) return drafts.delete(entry)
        editLock.withLock {
            when (entry) {
                is DriveTree.File -> deletingFiles += entry.fileId
                is DriveTree.Folder -> deletingFolders += checkNotNull(entry.folderId) { "the root is not deleted" }
                is DriveTree.Draft -> Unit
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

    override suspend fun createFile(parentId: Id?, name: String) = drafts.create(parentId, name)

    override suspend fun editFile(file: DriveTree.File, truncate: Boolean) = drafts.edit(file.fileId, truncate)

    override suspend fun openDraft(draft: DriveTree.Draft, write: Boolean, truncate: Boolean) = drafts.open(draft, write, truncate)

    override suspend fun resize(draft: DriveTree.Draft, size: Long) = drafts.resize(draft, size)

    override fun copyOf(draft: DriveTree.Draft): File = drafts.file(draft.uploadId)

    /**
     * After an upload: a name, folder or file to replace changed while it went up, which its captions could
     * not take any more, goes as operations; the replaced files' deletes, held till now, are packed.
     */
    private suspend fun uploaded(fileId: Id, uploaded: PendingUpload, now: Drafts.Wanted) {
        val sent = keys.local.openUpload(uploaded.uploadId, uploaded.sealedRequest)
        edit { ops ->
            if (sent != null && sent.folderId != now.request.folderId) ops.moveFile(fileId, now.request.folderId)
            if (sent != null && sent.name != now.request.name) ops.renameFile(fileId, now.request.name)
            val replaces = now.replaces
            if (replaces != null && replaces != uploaded.replaces && index.file(replaces) != null) {
                OpQueue(index).hold(replaces, index.parts(replaces).values.toList())
            }
            drafts.sealHeld(OpQueue(noting))
        }
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

    private suspend fun rebuild(): Unit = treeLock.withLock {
        // Replaced files held for deleting are gone from the drive too: their drafts took their place.
        val held = index.ops().filter { it.caption.isEmpty() }.flatMap { it.deletedFiles }
        val gone = editLock.withLock { deletingFiles + deletingFolders } + held
        val whole = index.files().filter { it.status == FileStatus.READY }.associateBy { it.fileId }
        treeFlow.value = DriveTree.of(PendingOps.without(overlay(), gone), whole, drafts.infos())
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
