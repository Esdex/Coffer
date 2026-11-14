package me.esdex.coffer.core.index

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.MessageId

/** [VaultIndex] in memory, for sync tests on the JVM. Same behaviour as the Room one. */
class InMemoryVaultIndex : VaultIndex {
    private class Row(val plainSize: Long, val partCount: Int, val sealedMeta: ByteArray, val status: FileStatus, val padded: Boolean)

    private data class State(
        val files: Map<Id, Row> = emptyMap(),
        val parts: Map<Pair<Id, Int>, MessageId> = emptyMap(),
        val deleted: Set<Id> = emptySet(),
        val folders: Map<Id, ByteArray> = emptyMap(),
        val deletedFolders: Set<Id> = emptySet(),
        val lastApplied: MessageId? = null,
        val contentVersion: MessageId? = null,
        val maintainedAt: Long? = null,
        val oldestRecord: MessageId? = null,
        val appliedOps: Set<Id> = emptySet(),
        val snapshots: Map<MessageId, KnownSnapshot> = emptyMap(),
        val uploads: Map<String, PendingUpload> = emptyMap(),
        val downloads: Map<String, PendingDownload> = emptyMap(),
    )

    private val lock = Mutex()
    private val state = MutableStateFlow(State())

    private suspend fun update(change: (State) -> State) = lock.withLock { state.value = change(state.value) }

    private fun State.indexed(): List<IndexedFile> = files.map { (id, row) ->
        val own = parts.filterKeys { it.first == id }.mapKeys { it.key.second }
        IndexedFile(id, row.plainSize, row.partCount, row.sealedMeta, row.status, own, row.padded)
    }

    private fun State.indexedFolders(): List<IndexedFolder> = folders.map { (id, sealed) -> IndexedFolder(id, sealed.copyOf()) }

    override fun observeFiles(): Flow<List<IndexedFile>> = state.map { it.indexed() }
    override suspend fun files(): List<IndexedFile> = state.value.indexed()
    override suspend fun file(fileId: Id): IndexedFile? = state.value.indexed().find { it.fileId == fileId }

    override suspend fun putFile(fileId: Id, plainSize: Long, partCount: Int, sealedMeta: ByteArray, status: FileStatus, padded: Boolean) =
        update { it.copy(files = it.files + (fileId to Row(plainSize, partCount, sealedMeta.copyOf(), status, padded))) }

    override suspend fun updateFileMeta(fileId: Id, plainSize: Long, partCount: Int, sealedMeta: ByteArray, padded: Boolean): Boolean =
        lock.withLock {
            val row = state.value.files[fileId] ?: return false
            state.value = state.value.copy(files = state.value.files + (fileId to Row(plainSize, partCount, sealedMeta.copyOf(), row.status, padded)))
            true
        }

    override suspend fun setStatus(fileId: Id, status: FileStatus) = update { s ->
        val row = s.files[fileId] ?: return@update s
        s.copy(files = s.files + (fileId to Row(row.plainSize, row.partCount, row.sealedMeta, status, row.padded)))
    }

    override suspend fun removeFile(fileId: Id) = update { s ->
        s.copy(files = s.files - fileId, parts = s.parts.filterKeys { it.first != fileId })
    }

    override suspend fun putPart(fileId: Id, partIndex: Int, messageId: MessageId) =
        update { it.copy(parts = it.parts + ((fileId to partIndex) to messageId)) }

    override suspend fun parts(fileId: Id): Map<Int, MessageId> =
        state.value.parts.filterKeys { it.first == fileId }.mapKeys { it.key.second }

    override suspend fun removePart(messageId: MessageId): Pair<Id, Int>? = lock.withLock {
        val key = state.value.parts.entries.find { it.value == messageId }?.key ?: return null
        state.value = state.value.copy(parts = state.value.parts - key)
        key
    }

    override suspend fun isDeleted(fileId: Id): Boolean = fileId in state.value.deleted
    override suspend fun markDeleted(fileId: Id) = update { it.copy(deleted = it.deleted + fileId) }

    override fun observeFolders(): Flow<List<IndexedFolder>> = state.map { it.indexedFolders() }
    override suspend fun folders(): List<IndexedFolder> = state.value.indexedFolders()
    override suspend fun folder(folderId: Id): IndexedFolder? =
        state.value.folders[folderId]?.let { IndexedFolder(folderId, it.copyOf()) }
    override suspend fun putFolder(folderId: Id, sealedMeta: ByteArray) =
        update { it.copy(folders = it.folders + (folderId to sealedMeta.copyOf())) }
    override suspend fun removeFolder(folderId: Id) = update { it.copy(folders = it.folders - folderId) }
    override suspend fun isFolderDeleted(folderId: Id): Boolean = folderId in state.value.deletedFolders
    override suspend fun markFolderDeleted(folderId: Id) = update { it.copy(deletedFolders = it.deletedFolders + folderId) }

    override suspend fun lastApplied(): MessageId? = state.value.lastApplied
    override suspend fun setLastApplied(messageId: MessageId) = update { it.copy(lastApplied = messageId) }

    override suspend fun allParts(): Map<Id, Map<Int, MessageId>> =
        state.value.parts.entries.groupBy({ it.key.first }, { it.key.second to it.value }).mapValues { it.value.toMap() }
    override suspend fun deletedFiles(): Set<Id> = state.value.deleted
    override suspend fun deletedFolders(): Set<Id> = state.value.deletedFolders

    override suspend fun contentVersion(): MessageId? = state.value.contentVersion
    override suspend fun setContentVersion(messageId: MessageId) = update { it.copy(contentVersion = messageId) }

    override suspend fun snapshots(): List<KnownSnapshot> = state.value.snapshots.values.toList()
    override suspend fun putSnapshot(snapshot: KnownSnapshot) =
        update { it.copy(snapshots = it.snapshots + (snapshot.messageId to snapshot)) }
    override suspend fun removeSnapshot(messageId: MessageId): Boolean = lock.withLock {
        if (messageId !in state.value.snapshots) return false
        state.value = state.value.copy(snapshots = state.value.snapshots - messageId)
        true
    }

    override suspend fun maintainedAt(): Long? = state.value.maintainedAt
    override suspend fun setMaintainedAt(millis: Long) = update { it.copy(maintainedAt = millis) }
    override suspend fun isOpApplied(opId: Id): Boolean = opId in state.value.appliedOps
    override suspend fun markOpApplied(opId: Id) = update { it.copy(appliedOps = it.appliedOps + opId) }
    override suspend fun oldestRecord(): MessageId? = state.value.oldestRecord
    override suspend fun setOldestRecord(messageId: MessageId) = update { it.copy(oldestRecord = messageId) }

    override suspend fun replaceContent(content: IndexContent) = update { s ->
        val parts = content.files.flatMap { f -> f.parts.map { (i, m) -> (f.fileId to i) to m } } +
            content.orphanParts.flatMap { (id, parts) -> parts.map { (i, m) -> (id to i) to m } }
        State(
            files = content.files.associate { it.fileId to Row(it.plainSize, it.partCount, it.sealedMeta.copyOf(), it.status, it.padded) },
            parts = parts.toMap(),
            deleted = content.deletedFiles,
            folders = content.folders.associate { it.folderId to it.sealedMeta.copyOf() },
            deletedFolders = content.deletedFolders,
            lastApplied = content.lastApplied,
            contentVersion = content.contentVersion,
            snapshots = content.snapshots.associateBy { it.messageId },
            uploads = s.uploads,
            downloads = s.downloads,
            maintainedAt = s.maintainedAt,
            oldestRecord = s.oldestRecord,
        )
    }

    override fun observeUploads(): Flow<List<PendingUpload>> = state.map { it.uploads.values.toList() }
    override suspend fun uploads(): List<PendingUpload> = state.value.uploads.values.toList()
    override suspend fun putUpload(upload: PendingUpload) = update { it.copy(uploads = it.uploads + (upload.uploadId to upload)) }
    override suspend fun removeUpload(uploadId: String) = update { it.copy(uploads = it.uploads - uploadId) }

    override fun observeDownloads(): Flow<List<PendingDownload>> = state.map { it.downloads.values.toList() }
    override suspend fun downloads(): List<PendingDownload> = state.value.downloads.values.toList()
    override suspend fun putDownload(download: PendingDownload) =
        update { it.copy(downloads = it.downloads + (download.downloadId to download)) }
    override suspend fun removeDownload(downloadId: String) = update { it.copy(downloads = it.downloads - downloadId) }

    override suspend fun clear() = update { State() }
}
