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
    private class Row(val plainSize: Long, val partCount: Int, val sealedMeta: ByteArray, val status: FileStatus)

    private data class State(
        val files: Map<Id, Row> = emptyMap(),
        val parts: Map<Pair<Id, Int>, MessageId> = emptyMap(),
        val deleted: Set<Id> = emptySet(),
        val lastApplied: MessageId? = null,
        val uploads: Map<String, PendingUpload> = emptyMap(),
        val downloads: Map<String, PendingDownload> = emptyMap(),
    )

    private val lock = Mutex()
    private val state = MutableStateFlow(State())

    private suspend fun update(change: (State) -> State) = lock.withLock { state.value = change(state.value) }

    private fun State.indexed(): List<IndexedFile> = files.map { (id, row) ->
        val own = parts.filterKeys { it.first == id }.mapKeys { it.key.second }
        IndexedFile(id, row.plainSize, row.partCount, row.sealedMeta, row.status, own)
    }

    override fun observeFiles(): Flow<List<IndexedFile>> = state.map { it.indexed() }
    override suspend fun files(): List<IndexedFile> = state.value.indexed()
    override suspend fun file(fileId: Id): IndexedFile? = state.value.indexed().find { it.fileId == fileId }

    override suspend fun putFile(fileId: Id, plainSize: Long, partCount: Int, sealedMeta: ByteArray, status: FileStatus) =
        update { it.copy(files = it.files + (fileId to Row(plainSize, partCount, sealedMeta.copyOf(), status))) }

    override suspend fun updateFileMeta(fileId: Id, plainSize: Long, partCount: Int, sealedMeta: ByteArray): Boolean =
        lock.withLock {
            val row = state.value.files[fileId] ?: return false
            state.value = state.value.copy(files = state.value.files + (fileId to Row(plainSize, partCount, sealedMeta.copyOf(), row.status)))
            true
        }

    override suspend fun setStatus(fileId: Id, status: FileStatus) = update { s ->
        val row = s.files[fileId] ?: return@update s
        s.copy(files = s.files + (fileId to Row(row.plainSize, row.partCount, row.sealedMeta, status)))
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

    override suspend fun lastApplied(): MessageId? = state.value.lastApplied
    override suspend fun setLastApplied(messageId: MessageId) = update { it.copy(lastApplied = messageId) }

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
