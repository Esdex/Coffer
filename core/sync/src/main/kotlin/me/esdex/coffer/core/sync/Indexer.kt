package me.esdex.coffer.core.sync

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.IndexPayload
import me.esdex.coffer.core.crypto.format.IndexState
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.format.RecordKind
import me.esdex.coffer.core.crypto.format.SnapshotFile
import me.esdex.coffer.core.crypto.format.SnapshotFolder
import me.esdex.coffer.core.crypto.format.SnapshotOrphan
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.IndexContent
import me.esdex.coffer.core.index.IndexedFile
import me.esdex.coffer.core.index.IndexedFolder
import me.esdex.coffer.core.index.KnownSnapshot
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.storage.StorageEvent
import me.esdex.coffer.core.storage.StoredMessage

/**
 * Keeps the local index in step with Saved Messages (SPEC §5.1, §5.4): blob parts, the operation log
 * and index snapshots.
 *
 * Messages are applied in message id order, the global order of events (SPEC §5), which makes the
 * last write win. An operation on an object that is not known (yet) is dropped rather than deferred:
 * the object's creation would come later and, being later, would overwrite it anyway (SPEC §5.2).
 *
 * One lock covers every change to the index, and a catch-up or rescan holds it throughout, so live
 * events wait instead of moving `last_applied_message_id` past messages still being read.
 */
class Indexer(
    private val storage: StorageBackend,
    private val index: VaultIndex,
    private val keys: VaultKeys,
) {
    private val lock = Mutex()

    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Something changed files or folders: the cue for the next snapshot (SPEC §4.4). */
    val changes: SharedFlow<Unit> = changesFlow.asSharedFlow()

    // Damage comes as a deletion event, without a message id to compare with snapshots, so it is counted.
    private var damageSeen = 0
    private var damageSnapshotted = 0

    /** Catches up, then follows new messages and deletions until cancelled. */
    suspend fun run(onCatchUpProgress: (Int) -> Unit = {}, onCaughtUp: () -> Unit = {}) = coroutineScope {
        // Subscribed before catching up, so nothing between the two is missed; duplicates are skipped by id.
        val events = Channel<StorageEvent>(Channel.UNLIMITED)
        launch(start = CoroutineStart.UNDISPATCHED) { storage.events.collect { events.send(it) } }
        catchUp(onCatchUpProgress)
        onCaughtUp()
        for (event in events) {
            when (event) {
                is StorageEvent.NewMessage -> apply(event.message)
                is StorageEvent.MessagesDeleted -> applyDeletion(event.ids)
            }
        }
    }

    /**
     * Applies everything after the last applied message (SPEC §5.4). History is read newest first down to
     * that point; a snapshot found on the way that is newer than the index replaces it, and only what
     * follows the snapshot is then applied. Without one, what was read is applied oldest first.
     */
    suspend fun catchUp(onProgress: (Int) -> Unit = {}): CatchUpReport = lock.withLock {
        repairStaleUploads()
        catchUpLocked(onProgress, useSnapshots = true)
    }

    /** Forgets everything but the transfer queue and reads the whole history again, snapshots aside (SPEC §5.5). */
    suspend fun rescan(onProgress: (Int) -> Unit = {}): CatchUpReport = lock.withLock {
        index.replaceContent(withOwnUploads(IndexContent.EMPTY))
        catchUpLocked(onProgress, useSnapshots = false)
    }

    /** Forgets everything but the transfer queue and catches up as a new device would, snapshots included. */
    suspend fun reload(onProgress: (Int) -> Unit = {}): CatchUpReport = lock.withLock {
        index.replaceContent(withOwnUploads(IndexContent.EMPTY))
        catchUpLocked(onProgress, useSnapshots = true)
    }

    /** How many messages a catch-up fetched, and whether a snapshot spared it the rest of the history. */
    data class CatchUpReport(val messagesRead: Int, val snapshotUsed: Boolean)

    private suspend fun catchUpLocked(onProgress: (Int) -> Unit, useSnapshots: Boolean): CatchUpReport {
        val last = index.lastApplied()
        val newer = mutableListOf<StoredMessage>()
        var fetched = 0
        var before: MessageId? = null
        scan@ while (true) {
            val page = storage.historyBefore(before, StorageBackend.MAX_PAGE)
            if (page.isEmpty()) break
            fetched += page.size
            for (message in page) {
                if (last != null && message.id <= last) break@scan
                newer += message
                if (!useSnapshots) continue
                val snapshot = snapshotOf(message) ?: continue
                if (last != null && snapshot.lastAppliedMessageId <= last.value) continue
                // A snapshot that does not open is damaged: keep looking for an older one.
                if (load(message.id, snapshot)) {
                    val read = fetched
                    return CatchUpReport(read + forward(MessageId(snapshot.lastAppliedMessageId)) { onProgress(read + it) }, true)
                }
            }
            onProgress(fetched)
            before = page.last().id
        }
        for (message in newer.asReversed()) applyLocked(message)
        return CatchUpReport(fetched, false)
    }

    /** Applies everything after [after]; returns how many messages that was. */
    private suspend fun forward(after: MessageId, onProgress: (Int) -> Unit): Int {
        var from = after
        var seen = 0
        while (true) {
            val page = storage.historyAfter(from, StorageBackend.MAX_PAGE)
            if (page.isEmpty()) return seen
            for (message in page) applyLocked(message)
            seen += page.size
            onProgress(seen)
            from = page.last().id
        }
    }

    private fun snapshotOf(message: StoredMessage): IndexPayload? {
        val caption = (message.content as? MessageContent.Blob)?.caption ?: return null
        val record = Captions.parse(caption)?.takeIf { it.kind == RecordKind.INDEX } ?: return null
        return keys.meta.decodeIndex(record)
    }

    private suspend fun load(messageId: MessageId, payload: IndexPayload): Boolean {
        val state = try {
            storage.openBlob(messageId).use { keys.snapshots.decode(payload.snapshotId, payload.plainSize, it) }
        } catch (e: StorageException) {
            // Another device may have just deleted it as one of the old ones: then look further. A message
            // that is still there failed for another reason, such as the network, and the catch-up stops.
            if (e is FloodWaitException || storage.message(messageId) != null) throw e
            return false
        } finally {
            try {
                storage.releaseBlob(messageId)
            } catch (_: StorageException) {
                // Nothing left to drop.
            }
        }
        if (state == null || state.lastAppliedMessageId != payload.lastAppliedMessageId) return false
        index.replaceContent(withOwnUploads(contentOf(state, KnownSnapshot(messageId, MessageId(state.lastAppliedMessageId)))))
        return true
    }

    private fun contentOf(state: IndexState, snapshot: KnownSnapshot): IndexContent {
        val last = MessageId(state.lastAppliedMessageId)
        return IndexContent(
            lastApplied = last,
            contentVersion = last,
            files = state.files.map { file ->
                val parts = file.parts.mapValues { MessageId(it.value) }
                val complete = (0 until file.partCount).all { it in parts }
                val status = when {
                    file.damaged -> FileStatus.DAMAGED
                    complete -> FileStatus.READY
                    else -> FileStatus.INCOMPLETE
                }
                IndexedFile(file.fileId, file.plainSize, file.partCount, keys.local.sealFileMeta(file.fileId, file.meta), status, parts)
            },
            orphanParts = state.orphans.associate { orphan -> orphan.fileId to orphan.parts.mapValues { MessageId(it.value) } },
            folders = state.folders.map { IndexedFolder(it.folderId, keys.local.sealFolder(it.folderId, LocalFolder(it.name, it.parentId))) },
            deletedFiles = state.deletedFiles.toSet(),
            deletedFolders = state.deletedFolders.toSet(),
            snapshots = listOf(snapshot),
        )
    }

    /**
     * This device's own uploads keep their rows and the UPLOADING status the uploader owns, also when
     * the new content does not know them yet.
     */
    private suspend fun withOwnUploads(content: IndexContent): IndexContent {
        val uploading = index.uploads().mapNotNullTo(mutableSetOf()) { it.fileId }
        if (uploading.isEmpty()) return content
        val known = content.files.map { it.fileId }.toSet()
        val orphans = content.orphanParts.toMutableMap()
        val carried = index.files().filter { it.fileId in uploading && it.fileId !in known }.map { row ->
            IndexedFile(row.fileId, row.plainSize, row.partCount, row.sealedMeta, FileStatus.UPLOADING, orphans.remove(row.fileId).orEmpty())
        }
        val files = content.files.map { file ->
            if (file.fileId in uploading) IndexedFile(file.fileId, file.plainSize, file.partCount, file.sealedMeta, FileStatus.UPLOADING, file.parts) else file
        }
        return IndexContent(
            content.lastApplied, content.contentVersion, files + carried, orphans,
            content.folders, content.deletedFiles, content.deletedFolders, content.snapshots,
        )
    }

    /** Applies one message; anything already applied is skipped. */
    suspend fun apply(message: StoredMessage) = lock.withLock { applyLocked(message) }

    private suspend fun applyLocked(message: StoredMessage) {
        val last = index.lastApplied()
        if (last != null && message.id <= last) return
        val changed = when (val content = message.content) {
            is MessageContent.Blob -> applyBlob(message.id, content.caption)
            is MessageContent.Text -> applyText(content.text)
            MessageContent.Other -> false
        }
        index.setLastApplied(message.id)
        if (changed) {
            index.setContentVersion(message.id)
            changesFlow.tryEmit(Unit)
        }
    }

    /** True if the index changed. */
    private suspend fun applyBlob(messageId: MessageId, caption: String): Boolean {
        val record = Captions.parse(caption) ?: return false
        if (record.kind == RecordKind.INDEX) {
            // Only remembered, for keeping the last few (SPEC §4.4); a snapshot is loaded only when catching up.
            val payload = keys.meta.decodeIndex(record) ?: return false
            index.putSnapshot(KnownSnapshot(messageId, MessageId(payload.lastAppliedMessageId)))
            return false
        }
        if (record.kind != RecordKind.BLOB) return false
        // Captions of another vault, or damaged ones, do not open and are ignored like foreign content.
        val payload = keys.meta.decodeBlob(record) ?: return false
        val fileId = payload.fileId
        if (index.isDeleted(fileId)) return false
        index.putPart(fileId, payload.partIndex, messageId)
        val meta = payload.meta
        if (meta != null) {
            val sealed = keys.local.sealFileMeta(fileId, meta)
            // This device's own uploads already have a row, whose status the uploader owns.
            if (!index.updateFileMeta(fileId, payload.plainSize, payload.partCount, sealed)) {
                index.putFile(fileId, payload.plainSize, payload.partCount, sealed, FileStatus.INCOMPLETE)
            }
        }
        refreshStatus(fileId)
        return true
    }

    /** True if the index (may have) changed. */
    private suspend fun applyText(text: String): Boolean {
        val record = Captions.parse(text)?.takeIf { it.kind == RecordKind.OP } ?: return false
        when (val op = keys.meta.decodeOp(record)) {
            null -> return false
            is Op.FolderCreate -> {
                // Delete wins over everything, and an id is created once.
                if (index.isFolderDeleted(op.folderId) || index.folder(op.folderId) != null) return false
                val parent = op.parentId?.takeUnless { makesLoop(op.folderId, it) }
                putFolder(op.folderId, LocalFolder(op.name, parent))
            }
            is Op.FolderRename -> {
                val folder = openFolder(op.folderId) ?: return false
                putFolder(op.folderId, folder.copy(name = op.name))
            }
            is Op.FolderMove -> {
                val folder = openFolder(op.folderId) ?: return false
                val parent = op.parentId
                if (parent != null && makesLoop(op.folderId, parent)) return false
                putFolder(op.folderId, folder.copy(parentId = op.parentId))
            }
            is Op.FolderDelete -> {
                // Whatever is still inside (a concurrent upload or move) shows in the root from now on.
                index.markFolderDeleted(op.folderId)
                index.removeFolder(op.folderId)
            }
            is Op.FileRename -> changeFile(op.fileId) { it.copy(name = op.name) }
            is Op.FileMove -> changeFile(op.fileId) { it.copy(folderId = op.folderId) }
            is Op.FileDelete -> forget(op.fileId)
        }
        return true
    }

    private suspend fun openFolder(folderId: Id): LocalFolder? {
        val row = index.folder(folderId) ?: return null
        return keys.local.openFolder(folderId, row.sealedMeta)
    }

    private suspend fun putFolder(folderId: Id, folder: LocalFolder) = index.putFolder(folderId, keys.local.sealFolder(folderId, folder))

    /** True if [parentId] is [folderId] or lies below it, by the parents as recorded. */
    private suspend fun makesLoop(folderId: Id, parentId: Id): Boolean {
        val parents = index.folders().associate { it.folderId to keys.local.openFolder(it.folderId, it.sealedMeta)?.parentId }
        val seen = mutableSetOf<Id>()
        var current: Id? = parentId
        while (current != null && seen.add(current)) {
            if (current == folderId) return true
            current = parents[current]
        }
        return false
    }

    /** Renames and moves a known file; its status is left alone. */
    private suspend fun changeFile(fileId: Id, change: (FileMeta) -> FileMeta) {
        val file = index.file(fileId) ?: return
        val meta = keys.local.openFileMeta(fileId, file.sealedMeta) ?: return
        index.updateFileMeta(fileId, file.plainSize, file.partCount, keys.local.sealFileMeta(fileId, change(meta)))
    }

    private suspend fun forget(fileId: Id) {
        index.markDeleted(fileId)
        index.removeFile(fileId)
    }

    /**
     * Deleted messages. A part gone without a `file.delete` first means the file was damaged outside
     * the app (SPEC §5.1); the backend already dropped deletions that only left TDLib's cache.
     */
    suspend fun applyDeletion(ids: Set<MessageId>) = lock.withLock {
        for (id in ids) {
            if (index.removeSnapshot(id)) continue
            val (fileId, _) = index.removePart(id) ?: continue
            if (index.isDeleted(fileId)) continue
            val file = index.file(fileId) ?: continue
            if (file.status != FileStatus.UPLOADING && file.status != FileStatus.DAMAGED) {
                index.setStatus(fileId, FileStatus.DAMAGED)
                damageSeen++
                changesFlow.tryEmit(Unit)
            }
        }
    }

    /** What the next snapshot would hold, or null if the newest known snapshot already covers every change. */
    class Pending internal constructor(val state: IndexState, internal val damage: Int)

    suspend fun pendingSnapshot(): Pending? = lock.withLock {
        val last = index.lastApplied() ?: return@withLock null
        // An index from before content versions were kept counts as changed at its last message.
        val version = index.contentVersion() ?: last
        val newest = index.snapshots().maxOfOrNull { it.lastApplied }
        if (newest != null && newest >= version && damageSeen == damageSnapshotted) return@withLock null
        Pending(stateOf(last), damageSeen)
    }

    /** The snapshot of [pending] is in Saved Messages. */
    suspend fun snapshotSent(pending: Pending, snapshot: KnownSnapshot) = lock.withLock {
        index.putSnapshot(snapshot)
        damageSnapshotted = maxOf(damageSnapshotted, pending.damage)
    }

    private suspend fun stateOf(last: MessageId): IndexState {
        val files = index.files()
        val known = files.map { it.fileId }.toSet()
        val deletedFiles = index.deletedFiles()
        return IndexState(
            lastAppliedMessageId = last.value,
            folders = index.folders().mapNotNull { f ->
                keys.local.openFolder(f.folderId, f.sealedMeta)?.let { SnapshotFolder(f.folderId, it.parentId, it.name) }
            },
            files = files.mapNotNull { f ->
                val meta = keys.local.openFileMeta(f.fileId, f.sealedMeta) ?: return@mapNotNull null
                SnapshotFile(f.fileId, f.plainSize, meta, f.parts.mapValues { it.value.value }, f.status == FileStatus.DAMAGED)
            },
            orphans = index.allParts().filterKeys { it !in known && it !in deletedFiles }.map { (id, parts) ->
                SnapshotOrphan(id, parts.mapValues { it.value.value })
            },
            deletedFiles = deletedFiles.toList(),
            deletedFolders = index.deletedFolders().toList(),
        )
    }

    /**
     * A file still marked UPLOADING with no upload left for it (an upload that ended without finishing
     * its row, as after a crash) gets the status its parts say.
     */
    private suspend fun repairStaleUploads() {
        val uploading = index.uploads().mapNotNullTo(mutableSetOf()) { it.fileId }
        for (file in index.files()) {
            if (file.status != FileStatus.UPLOADING || file.fileId in uploading) continue
            val complete = (0 until file.partCount).all { it in file.parts }
            index.setStatus(file.fileId, if (complete) FileStatus.READY else FileStatus.INCOMPLETE)
        }
    }

    private suspend fun refreshStatus(fileId: Id) {
        val file = index.file(fileId) ?: return
        // An upload in progress is finished by the uploader, and damage is not undone by a late part.
        if (file.status == FileStatus.UPLOADING || file.status == FileStatus.DAMAGED) return
        val complete = (0 until file.partCount).all { it in file.parts }
        index.setStatus(fileId, if (complete) FileStatus.READY else FileStatus.INCOMPLETE)
    }
}
