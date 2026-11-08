package me.esdex.coffer.core.sync

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.format.RecordKind
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageEvent
import me.esdex.coffer.core.storage.StoredMessage

/**
 * Keeps the local index in step with Saved Messages (SPEC §5.1, §5.4), for files: blob parts and
 * `file.delete`. Folder and rename/move operations come with the full operation log.
 *
 * Messages are applied in message id order, the global order of events (SPEC §5).
 */
class Indexer(
    private val storage: StorageBackend,
    private val index: VaultIndex,
    private val keys: VaultKeys,
) {
    private val lock = Mutex()

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
     * Applies everything after the last applied message. The very first time that is the whole
     * history, read newest to oldest and applied oldest first.
     */
    suspend fun catchUp(onProgress: (Int) -> Unit = {}) {
        repairStaleUploads()
        var seen = 0
        val last = lock.withLock { index.lastApplied() }
        if (last == null) {
            val all = mutableListOf<StoredMessage>()
            var before: MessageId? = null
            while (true) {
                val page = storage.historyBefore(before, StorageBackend.MAX_PAGE)
                if (page.isEmpty()) break
                all += page
                before = page.last().id
                onProgress(all.size)
            }
            for (message in all.asReversed()) apply(message)
            return
        }
        var after: MessageId = last
        while (true) {
            val page = storage.historyAfter(after, StorageBackend.MAX_PAGE)
            if (page.isEmpty()) return
            for (message in page) apply(message)
            seen += page.size
            onProgress(seen)
            after = page.last().id
        }
    }

    /** Applies one message; anything already applied is skipped. */
    suspend fun apply(message: StoredMessage) = lock.withLock {
        val last = index.lastApplied()
        if (last != null && message.id <= last) return@withLock
        when (val content = message.content) {
            is MessageContent.Blob -> applyBlob(message.id, content.caption)
            is MessageContent.Text -> applyText(content.text)
            MessageContent.Other -> Unit
        }
        index.setLastApplied(message.id)
    }

    private suspend fun applyBlob(messageId: MessageId, caption: String) {
        val record = Captions.parse(caption)?.takeIf { it.kind == RecordKind.BLOB } ?: return
        // Captions of another vault, or damaged ones, do not open and are ignored like foreign content.
        val payload = keys.meta.decodeBlob(record) ?: return
        val fileId = payload.fileId
        if (index.isDeleted(fileId)) return
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
    }

    private suspend fun applyText(text: String) {
        val record = Captions.parse(text)?.takeIf { it.kind == RecordKind.OP } ?: return
        when (val op = keys.meta.decodeOp(record)) {
            is Op.FileDelete -> forget(op.fileId)
            // Folders, renames and moves are applied by the operation log, not here yet.
            else -> Unit
        }
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
            val (fileId, _) = index.removePart(id) ?: continue
            if (index.isDeleted(fileId)) continue
            val file = index.file(fileId) ?: continue
            if (file.status != FileStatus.UPLOADING) index.setStatus(fileId, FileStatus.DAMAGED)
        }
    }

    /**
     * A file still marked UPLOADING with no upload left for it (an upload that ended without finishing
     * its row, as after a crash) gets the status its parts say.
     */
    private suspend fun repairStaleUploads() = lock.withLock {
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
