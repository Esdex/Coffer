package me.esdex.coffer.core.sync

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.esdex.coffer.core.crypto.ContentLayout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.Padme
import me.esdex.coffer.core.crypto.format.BlobPayload
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
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageEvent
import me.esdex.coffer.core.storage.StorageException
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

    /**
     * Catches up, then follows new messages and deletions until cancelled.
     *
     * A full read covers everything up to the last message it applied: ids only grow, so anything older
     * was already in the history it read. A message newer than that, yet no newer than one applied
     * since, and not applied itself, was delivered late, after a newer one (a send of ours and another
     * device's landing together). Applied in its place it would break the message order the log depends
     * on, and skipping it loses it; the whole history is read again instead (SPEC §5.5), and [onLate]
     * says so. Anything else no newer than the last applied is a repeat and is skipped.
     */
    /**
     * [run], started again after a storage error instead of ending with it: Telegram may fail a page of
     * the history or ask to wait, and the network may drop. Uncaught, such an error crashed the app on
     * every unlock (audit 2, L-h). Waits as Telegram asks, otherwise longer after each failure in a row.
     */
    suspend fun follow(
        onCatchUpProgress: (Int) -> Unit = {},
        onCaughtUp: () -> Unit = {},
        onLate: (MessageId) -> Unit = {},
        onError: (StorageException) -> Unit = {},
    ) {
        var pause = FIRST_RETRY_MILLIS
        while (true) {
            try {
                run(onCatchUpProgress, { pause = FIRST_RETRY_MILLIS; onCaughtUp() }, onLate)
                return
            } catch (e: FloodWaitException) {
                onError(e)
                delay(e.seconds * 1000L)
            } catch (e: StorageException) {
                onError(e)
                delay(pause)
                pause = (pause * 2).coerceAtMost(MAX_RETRY_MILLIS)
            }
        }
    }

    suspend fun run(
        onCatchUpProgress: (Int) -> Unit = {},
        onCaughtUp: () -> Unit = {},
        onLate: (MessageId) -> Unit = {},
    ) = coroutineScope {
        // Subscribed before catching up, so nothing between the two is missed.
        val events = Channel<StorageEvent>(Channel.UNLIMITED)
        launch(start = CoroutineStart.UNDISPATCHED) { storage.events.collect { events.send(it) } }
        catchUp(onCatchUpProgress)
        var readUpTo = index.lastApplied()
        onCaughtUp()
        val appliedSince = HashSet<MessageId>()
        for (event in events) {
            when (event) {
                is StorageEvent.NewMessage -> {
                    val id = event.message.id
                    val last = index.lastApplied()
                    val late = last != null && id <= last && (readUpTo == null || id > readUpTo) && id !in appliedSince
                    if (late) {
                        onLate(id)
                        rescan(onCatchUpProgress)
                        readUpTo = index.lastApplied()
                        appliedSince.clear()
                        onCaughtUp()
                    } else {
                        apply(event.message)
                        appliedSince += id
                    }
                }
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
        index.replaceContent(withOwnUploads(keepingDeletions(IndexContent.EMPTY)))
        catchUpLocked(onProgress, useSnapshots = false)
    }

    /** Forgets everything but the transfer queue and catches up as a new device would, snapshots included. */
    suspend fun reload(onProgress: (Int) -> Unit = {}): CatchUpReport = lock.withLock {
        index.replaceContent(withOwnUploads(keepingDeletions(IndexContent.EMPTY)))
        catchUpLocked(onProgress, useSnapshots = true)
    }

    /** How many messages a catch-up fetched, and whether a snapshot spared it the rest of the history. */
    data class CatchUpReport(val messagesRead: Int, val snapshotUsed: Boolean)

    private suspend fun catchUpLocked(onProgress: (Int) -> Unit, useSnapshots: Boolean): CatchUpReport {
        val last = index.lastApplied()
        // Nothing read is kept past its page: anyone in the account can post a hundred thousand long
        // messages while a device is away, and holding them all ran out of memory at every unlock (audit 2,
        // L-g). The way down only looks for snapshots, and for where to start; what is applied is read
        // again, oldest first, a page at a time.
        var oldest: StoredMessage? = null
        // Snapshots met on the way down, not loaded yet. The newest message is not always the newest
        // state: anyone in the Telegram account can send an old snapshot again, under a new message id.
        // Every message newer than a snapshot's own state is read anyway, so the search goes on down to
        // that point, and the snapshot with the newest state found by then is loaded (audit 1, M5).
        val found = mutableListOf<Pair<MessageId, IndexPayload>>()
        var fetched = 0
        var before: MessageId? = null

        // The best of [found] that opens; one that does not is damaged and dropped.
        suspend fun loadBest(): CatchUpReport? {
            // The newest state, and of two with the same last message, the one made later: damage seen in
            // between changes a snapshot without a message of its own. Its time is inside the encryption.
            val best = found.sortedWith(compareByDescending<Pair<MessageId, IndexPayload>> { it.second.lastAppliedMessageId }.thenByDescending { it.second.createdAt })
            for ((id, snapshot) in best) {
                if (load(id, snapshot)) {
                    val read = fetched
                    return CatchUpReport(read + forward(MessageId(snapshot.lastAppliedMessageId)) { onProgress(read + it) }, true)
                }
            }
            found.clear()
            return null
        }

        scan@ while (true) {
            val page = storage.historyBefore(before, StorageBackend.MAX_PAGE)
            if (page.isEmpty()) break
            fetched += page.size
            for (message in page) {
                if (last != null && message.id <= last) break@scan
                val newest = found.maxOfOrNull { it.second.lastAppliedMessageId }
                if (newest != null && message.id.value <= newest) loadBest()?.let { return it }
                oldest = message
                if (!useSnapshots) continue
                val snapshot = snapshotOf(message) ?: continue
                if (last != null && snapshot.lastAppliedMessageId <= last.value) continue
                // The caption says how big the document is; one of another size was swapped in, and would
                // be downloaded whole before anything checked it (audit 1, L12).
                if (!sizeFits(message, snapshot)) continue
                found += message.id to snapshot
            }
            onProgress(fetched)
            before = page.last().id
        }
        loadBest()?.let { return it }
        // Reading forward starts after a message, so the oldest one, with nothing applied before it, goes first.
        val start = last ?: oldest?.also { applyLocked(it) }?.id ?: return CatchUpReport(fetched, false)
        val read = fetched
        return CatchUpReport(read + forward(start) { onProgress(read + it) }, false)
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

    /**
     * A part's document is as long as its caption says the part is. A genuine caption over another
     * document, posted again by anyone in the account after the part was deleted, is no part: taken for
     * one it turned a damaged file READY and had the whole document downloaded (audit 2, L-c).
     */
    private fun BlobPayload.fits(blob: MessageContent.Blob): Boolean =
        blob.size == ContentLayout.encryptedPartSize(contentSize, partIndex)

    private fun sizeFits(message: StoredMessage, snapshot: IndexPayload): Boolean {
        val size = (message.content as? MessageContent.Blob)?.size ?: return false
        val expected = runCatching { ContentLayout.encryptedPartSize(Padme.contentSize(snapshot.plainSize, snapshot.padded), 0) }.getOrNull()
        return size == expected
    }

    private fun snapshotOf(message: StoredMessage): IndexPayload? {
        val caption = (message.content as? MessageContent.Blob)?.caption ?: return null
        val record = Captions.parse(caption)?.takeIf { it.kind == RecordKind.INDEX } ?: return null
        return keys.meta.decodeIndex(record)
    }

    private suspend fun load(messageId: MessageId, payload: IndexPayload): Boolean {
        val state = try {
            storage.openBlob(messageId).use { keys.snapshots.decode(payload.snapshotId, payload.plainSize, it, payload.padded) }
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
        index.replaceContent(withOwnUploads(keepingDeletions(contentOf(state, KnownSnapshot(messageId, MessageId(state.lastAppliedMessageId))))))
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
                IndexedFile(file.fileId, file.plainSize, file.partCount, keys.local.sealFileMeta(file.fileId, file.meta), status, parts, file.padded)
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
            IndexedFile(row.fileId, row.plainSize, row.partCount, row.sealedMeta, FileStatus.UPLOADING, orphans.remove(row.fileId).orEmpty(), row.padded)
        }
        val files = content.files.map { file ->
            if (file.fileId in uploading) IndexedFile(file.fileId, file.plainSize, file.partCount, file.sealedMeta, FileStatus.UPLOADING, file.parts, file.padded) else file
        }
        return IndexContent(
            content.lastApplied, content.contentVersion, files + carried, orphans,
            content.folders, content.deletedFiles, content.deletedFolders, content.snapshots,
        )
    }

    /**
     * [content] with every deletion this device has applied. A deletion is for good, and its operation may
     * be gone from Saved Messages (compacted, or deleted by anyone in the account) while copies of the
     * file's parts are posted again: a rescan or another device's snapshot must not bring it back (audit 2, N2).
     */
    private suspend fun keepingDeletions(content: IndexContent): IndexContent {
        val deletedFiles = content.deletedFiles + index.deletedFiles()
        val deletedFolders = content.deletedFolders + index.deletedFolders()
        return IndexContent(
            content.lastApplied, content.contentVersion,
            content.files.filter { it.fileId !in deletedFiles }, content.orphanParts - deletedFiles,
            content.folders.filter { it.folderId !in deletedFolders }, deletedFiles, deletedFolders, content.snapshots,
        )
    }

    /** Applies one message; anything already applied is skipped. */
    suspend fun apply(message: StoredMessage) = lock.withLock { applyLocked(message) }

    private suspend fun applyLocked(message: StoredMessage) {
        val last = index.lastApplied()
        if (last != null && message.id <= last) return
        val changed = when (val content = message.content) {
            is MessageContent.Blob -> applyBlob(message.id, content)
            is MessageContent.Text -> applyText(message.id, content.text)
            MessageContent.Other -> false
        }
        index.setLastApplied(message.id)
        if (changed) {
            index.setContentVersion(message.id)
            changesFlow.tryEmit(Unit)
        }
    }

    /** True if the index changed. */
    private suspend fun applyBlob(messageId: MessageId, blob: MessageContent.Blob): Boolean {
        val record = Captions.parse(blob.caption) ?: return false
        if (record.kind == RecordKind.INDEX) {
            // Only remembered, for keeping the last few (SPEC §4.4); a snapshot is loaded only when catching up.
            val payload = keys.meta.decodeIndex(record) ?: return false
            index.putSnapshot(KnownSnapshot(messageId, MessageId(payload.lastAppliedMessageId)))
            return false
        }
        if (record.kind != RecordKind.BLOB) return false
        // Captions of another vault, or damaged ones, do not open and are ignored like foreign content.
        val payload = keys.meta.decodeBlob(record)?.takeIf { it.fits(blob) } ?: return false
        val fileId = payload.fileId
        if (index.isDeleted(fileId)) return false
        // A part sent again under a new message id is the same part: the first one stays. Its part 0 would
        // otherwise bring back the name and folder the file had when it was added (audit 1, M4).
        val known = index.parts(fileId)[payload.partIndex]
        if (known != null && known != messageId) return false
        index.putPart(fileId, payload.partIndex, messageId)
        val meta = payload.meta
        if (meta != null) {
            val sealed = keys.local.sealFileMeta(fileId, meta)
            // This device's own uploads already have a row, whose status the uploader owns.
            if (!index.updateFileMeta(fileId, payload.plainSize, payload.partCount, sealed, payload.padded)) {
                index.putFile(fileId, payload.plainSize, payload.partCount, sealed, FileStatus.INCOMPLETE, payload.padded)
            }
        }
        refreshStatus(fileId)
        return true
    }

    /** True if the index (may have) changed. */
    private suspend fun applyText(messageId: MessageId, text: String): Boolean {
        val record = Captions.parse(text)?.takeIf { it.kind == RecordKind.OP } ?: return false
        val op = keys.meta.decodeOp(record) ?: return false
        // Sent again under a new message id, by anyone in the Telegram account: the op_id gives it away (audit 1,
        // M4). The message it first came in is applied again by a rescan, and only that one (audit 2, N2).
        val first = index.opAppliedFrom(op.opId)
        if (first != null && first != UNCLAIMED && first != messageId) return false
        if (first == null || first == UNCLAIMED) index.markOpApplied(op.opId, messageId)
        when (op) {
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
        index.updateFileMeta(fileId, file.plainSize, file.partCount, keys.local.sealFileMeta(fileId, change(meta)), file.padded)
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
                SnapshotFile(f.fileId, f.plainSize, meta, f.parts.mapValues { it.value.value }, f.status == FileStatus.DAMAGED, f.padded)
            },
            orphans = index.allParts().filterKeys { it !in known && it !in deletedFiles }.map { (id, parts) ->
                SnapshotOrphan(id, parts.mapValues { it.value.value })
            },
            deletedFiles = deletedFiles.toList(),
            deletedFolders = index.deletedFolders().toList(),
        )
    }

    /** What the integrity check changed in the index, and what it leaves to delete from Saved Messages. */
    class Reconciled(
        val damaged: Int,
        val repaired: Int,
        val garbage: List<MessageId>,
        val oldSnapshots: List<MessageId>,
    )

    /**
     * The integrity check (SPEC §5.8, §5.7) against [history], all of Saved Messages read oldest first.
     * Only what was read is judged: a message newer than the last one read arrived while reading.
     *
     * - A part the index has but history does not: the file is damaged.
     * - A part history has but the index missed, even a whole file: added back.
     * - Part 0 and not every part, older than a day: damaged, an upload that never finished. Kept for
     *   the user to see and delete, since after a rescan it cannot be told from damage.
     * - Parts whose part 0 never came, older than a day, and parts left over after a `file.delete`: garbage.
     * - Snapshots beyond the newest three, including ones this device never heard of: old.
     *
     * This device's own uploads in progress are left alone.
     */
    suspend fun reconcile(history: List<StoredMessage>, nowSeconds: Long): Reconciled = lock.withLock {
        val readUpTo = history.lastOrNull()?.id ?: return@withLock Reconciled(0, 0, emptyList(), emptyList())
        for (message in history) applyLocked(message)
        val present = history.mapTo(HashSet()) { it.id }

        class Part(val index: Int, val message: StoredMessage, val caption: String, val isFirst: Boolean)
        val partsByFile = mutableMapOf<Id, MutableList<Part>>()
        val snapshotsRead = mutableListOf<KnownSnapshot>()
        for (message in history) {
            val caption = (message.content as? MessageContent.Blob)?.caption ?: continue
            val record = Captions.parse(caption) ?: continue
            when (record.kind) {
                RecordKind.BLOB -> keys.meta.decodeBlob(record)?.let { p ->
                    partsByFile.getOrPut(p.fileId) { mutableListOf() } += Part(p.partIndex, message, caption, p.meta != null)
                }
                RecordKind.INDEX -> keys.meta.decodeIndex(record)?.let {
                    snapshotsRead += KnownSnapshot(message.id, MessageId(it.lastAppliedMessageId))
                }
                else -> Unit
            }
        }
        val uploading = index.uploads().mapNotNullTo(HashSet()) { it.fileId }
        val dayAgo = nowSeconds - GARBAGE_AGE_SECONDS
        var damaged = 0
        var repaired = 0

        // Whole files the index missed come back first, through the same path as any part.
        val deletedFiles = index.deletedFiles()
        val known = index.files().mapTo(HashSet()) { it.fileId }
        for ((fileId, parts) in partsByFile) {
            if (fileId in known || fileId in deletedFiles || parts.none { it.isFirst }) continue
            for (part in parts.sortedBy { it.message.id }) applyBlob(part.message.id, part.message.content as MessageContent.Blob)
            repaired++
        }

        for (file in index.files()) {
            if (file.fileId in uploading) continue
            val gone = file.parts.values.filter { it <= readUpTo && it !in present }
            for (id in gone) index.removePart(id)
            // Against the parts as they are now, with the gone ones out: a copy of a part that went stands in
            // for it in this same check, not only in the next one (audit 1, L13).
            val now = index.parts(file.fileId).toMutableMap()
            for (part in partsByFile[file.fileId].orEmpty()) {
                if (part.index < file.partCount && now[part.index] == null) {
                    index.putPart(file.fileId, part.index, part.message.id)
                    now[part.index] = part.message.id
                    repaired++
                }
            }
            val complete = (0 until file.partCount).all { it in now }
            val firstDate = partsByFile[file.fileId]?.firstOrNull { it.isFirst }?.message?.date
            val status = when {
                // Every part is there after all, a copy standing in for any that went: whatever marked it damaged was wrong.
                complete -> FileStatus.READY
                gone.isNotEmpty() -> FileStatus.DAMAGED
                file.status == FileStatus.DAMAGED -> FileStatus.DAMAGED
                firstDate != null && firstDate < dayAgo -> FileStatus.DAMAGED
                else -> FileStatus.INCOMPLETE
            }
            if (status != file.status) {
                index.setStatus(file.fileId, status)
                if (status == FileStatus.DAMAGED) damaged++
            }
        }

        val stillKnown = index.files().mapTo(HashSet()) { it.fileId }
        val garbage = mutableListOf<MessageId>()
        for ((fileId, parts) in partsByFile) {
            if (fileId in uploading || fileId in stillKnown) continue
            val leftover = fileId in deletedFiles
            val abandoned = parts.none { it.isFirst } && parts.all { it.message.date < dayAgo }
            if (leftover || abandoned) garbage += parts.map { it.message.id }
        }
        for (id in garbage) index.removePart(id)

        for (snapshot in snapshotsRead) index.putSnapshot(snapshot)
        // By the state they hold, not by message id: an old snapshot sent again must not push out the newest (audit 1, M5).
        val oldSnapshots = index.snapshots().sortedWith(Snapshotter.NEWEST_STATE_FIRST).drop(Snapshotter.KEEP).map { it.messageId }
        for (id in oldSnapshots) index.removeSnapshot(id)

        if (damaged + repaired > 0) {
            damageSeen += damaged + repaired
            changesFlow.tryEmit(Unit)
        }
        Reconciled(damaged, repaired, garbage, oldSnapshots)
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

    private companion object {
        /** SPEC §5.7: an upload that has not finished in a day is not coming. */
        const val GARBAGE_AGE_SECONDS = 24 * 60 * 60L

        const val FIRST_RETRY_MILLIS = 5_000L
        const val MAX_RETRY_MILLIS = 5 * 60_000L

        /** An operation applied before its message was remembered with it (index schema 5). */
        val UNCLAIMED = MessageId(0)
    }

    private suspend fun refreshStatus(fileId: Id) {
        val file = index.file(fileId) ?: return
        // An upload in progress is finished by the uploader, and damage is not undone by a late part.
        if (file.status == FileStatus.UPLOADING || file.status == FileStatus.DAMAGED) return
        val complete = (0 until file.partCount).all { it in file.parts }
        index.setStatus(fileId, if (complete) FileStatus.READY else FileStatus.INCOMPLETE)
    }
}
