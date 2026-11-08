package me.esdex.coffer.core.index

import kotlinx.coroutines.flow.Flow
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.MessageId

enum class FileStatus {
    /** This device is sending it. */
    UPLOADING,

    /** Part 0 is known but not every part: still arriving, or an upload that stopped. */
    INCOMPLETE,

    READY,

    /** A part was deleted outside the app (SPEC §5.1, §5.8). */
    DAMAGED,
}

/**
 * A file in the local index. Only what Telegram can see anyway is in the clear: ids, sizes, message
 * ids and status. Name, folder, type, dates, hash and wrapped key are in [sealedMeta] (SPEC §8).
 */
class IndexedFile(
    val fileId: Id,
    val plainSize: Long,
    val partCount: Int,
    val sealedMeta: ByteArray,
    val status: FileStatus,
    val parts: Map<Int, MessageId>,
)

/** A folder in the local index: name and parent are in [sealedMeta] (SPEC §8). */
class IndexedFolder(val folderId: Id, val sealedMeta: ByteArray)

/** An upload this device owes: queued until prepared, then the captions it will send (SPEC §6). */
class PendingUpload(
    val uploadId: String,
    /** Source URI, name and type, sealed with the local key: picked URIs carry paths (SPEC §8). */
    val sealedRequest: ByteArray,
    val fileId: Id?,
    /** One caption per part, ciphertext; empty while queued. */
    val captions: List<String>,
    /** A ready file.delete op, so an upload can be abandoned without MetaKey (the vault may be locked). */
    val abandonOp: String?,
)

class PendingDownload(
    val downloadId: String,
    val fileId: Id,
    /** Sealed content:// URI to write to, or null to decrypt for opening in another app. */
    val sealedTarget: ByteArray?,
)

/**
 * The local index (SPEC §2: a cache, always rebuildable from Saved Messages). Plain Kotlin so sync logic
 * is tested on the JVM against [InMemoryVaultIndex]; the app uses Room.
 */
interface VaultIndex {
    fun observeFiles(): Flow<List<IndexedFile>>
    suspend fun files(): List<IndexedFile>
    suspend fun file(fileId: Id): IndexedFile?

    /** Inserts or replaces the file row; parts are kept. */
    suspend fun putFile(fileId: Id, plainSize: Long, partCount: Int, sealedMeta: ByteArray, status: FileStatus)
    /**
     * Replaces size and sealed metadata of an existing row, leaving its status alone, so it cannot undo a
     * status another writer set meanwhile. False if there is no such row.
     */
    suspend fun updateFileMeta(fileId: Id, plainSize: Long, partCount: Int, sealedMeta: ByteArray): Boolean
    suspend fun setStatus(fileId: Id, status: FileStatus)
    suspend fun removeFile(fileId: Id)

    /** Parts may arrive before their file's part 0; they are kept until it comes. */
    suspend fun putPart(fileId: Id, partIndex: Int, messageId: MessageId)
    suspend fun parts(fileId: Id): Map<Int, MessageId>
    suspend fun removePart(messageId: MessageId): Pair<Id, Int>?

    suspend fun isDeleted(fileId: Id): Boolean
    suspend fun markDeleted(fileId: Id)

    fun observeFolders(): Flow<List<IndexedFolder>>
    suspend fun folders(): List<IndexedFolder>
    suspend fun folder(folderId: Id): IndexedFolder?
    /** Inserts or replaces. */
    suspend fun putFolder(folderId: Id, sealedMeta: ByteArray)
    suspend fun removeFolder(folderId: Id)
    suspend fun isFolderDeleted(folderId: Id): Boolean
    suspend fun markFolderDeleted(folderId: Id)

    suspend fun lastApplied(): MessageId?
    suspend fun setLastApplied(messageId: MessageId)

    fun observeUploads(): Flow<List<PendingUpload>>
    suspend fun uploads(): List<PendingUpload>
    suspend fun putUpload(upload: PendingUpload)
    suspend fun removeUpload(uploadId: String)

    fun observeDownloads(): Flow<List<PendingDownload>>
    suspend fun downloads(): List<PendingDownload>
    suspend fun putDownload(download: PendingDownload)
    suspend fun removeDownload(downloadId: String)

    /** Everything, for signing out (SPEC §9). */
    suspend fun clear()
}
