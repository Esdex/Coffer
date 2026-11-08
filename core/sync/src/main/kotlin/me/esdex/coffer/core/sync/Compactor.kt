package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.format.RecordKind
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.InMemoryVaultIndex
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StoredMessage

/**
 * Finds superseded operations (SPEC §5.6): those without which a full rescan gives the same index.
 *
 * The rules below pick candidates; a replay of the history with and without them then decides. If the
 * two disagree, a narrower set is tried, and failing that nothing is removed: compaction only saves
 * space, a wrong one loses the user's changes.
 */
class Compactor(private val storage: StorageBackend, private val keys: VaultKeys) {

    /**
     * Operations in [history] (oldest first) that can go. Only operations with a message id below
     * [beforeId] and a date before [beforeDate] (unix seconds) are considered.
     */
    suspend fun removable(history: List<StoredMessage>, beforeId: MessageId, beforeDate: Long): Set<MessageId> {
        val wide = candidates(history, beforeId, beforeDate, folderMovesSupersede = true)
        if (wide.isEmpty() || sameIndex(history, wide)) return wide
        // A later folder.move may have been ignored as a loop, leaving the earlier one in force.
        val narrow = candidates(history, beforeId, beforeDate, folderMovesSupersede = false)
        if (narrow.isEmpty() || sameIndex(history, narrow)) return narrow
        return emptySet()
    }

    internal fun candidates(history: List<StoredMessage>, beforeId: MessageId, beforeDate: Long, folderMovesSupersede: Boolean): Set<MessageId> {
        val ops = history.mapNotNull { message ->
            val text = (message.content as? MessageContent.Text)?.text ?: return@mapNotNull null
            val record = Captions.parse(text)?.takeIf { it.kind == RecordKind.OP } ?: return@mapNotNull null
            keys.meta.decodeOp(record)?.let { message to it }
        }
        val filesWithParts = history.mapNotNullTo(HashSet()) { message ->
            val caption = (message.content as? MessageContent.Blob)?.caption ?: return@mapNotNullTo null
            Captions.parse(caption)?.takeIf { it.kind == RecordKind.BLOB }?.let { keys.meta.decodeBlob(it)?.fileId }
        }
        val deletedFiles = ops.mapNotNullTo(HashSet()) { (it.second as? Op.FileDelete)?.fileId }
        val deletedFolders = ops.mapNotNullTo(HashSet()) { (it.second as? Op.FolderDelete)?.folderId }
        val firstCreate = HashMap<Id, MessageId>()
        val lastOfKind = HashMap<Pair<String, Id>, MessageId>()
        for ((message, op) in ops) {
            if (op is Op.FolderCreate) firstCreate.putIfAbsent(op.folderId, message.id)
            lastOfKind[op.type to target(op)] = message.id
        }

        val removable = HashSet<MessageId>()
        for ((message, op) in ops) {
            val superseded = lastOfKind[op.type to target(op)] != message.id
            val goes = when (op) {
                is Op.FileRename, is Op.FileMove -> {
                    val fileId = target(op)
                    fileId in deletedFiles || fileId !in filesWithParts || superseded
                }
                // Once no part is left, a rescan has nothing for the delete to remove.
                is Op.FileDelete -> op.fileId !in filesWithParts
                is Op.FolderCreate -> op.folderId in deletedFolders || firstCreate[op.folderId] != message.id
                is Op.FolderRename -> op.folderId in deletedFolders || op.folderId !in firstCreate || superseded
                is Op.FolderMove -> op.folderId in deletedFolders || op.folderId !in firstCreate || (folderMovesSupersede && superseded)
                is Op.FolderDelete -> false
            }
            if (goes && message.id < beforeId && message.date < beforeDate) removable += message.id
        }
        // A folder.delete may go only with every folder.create of its folder, or the folder would come back.
        for ((message, op) in ops) {
            if (op !is Op.FolderDelete || message.id >= beforeId || message.date >= beforeDate) continue
            val creates = ops.filter { (m, o) -> o is Op.FolderCreate && o.folderId == op.folderId }.map { it.first.id }
            if (creates.all { it in removable }) removable += message.id
        }
        return removable
    }

    private fun target(op: Op): Id = when (op) {
        is Op.FileRename -> op.fileId
        is Op.FileMove -> op.fileId
        is Op.FileDelete -> op.fileId
        is Op.FolderCreate -> op.folderId
        is Op.FolderRename -> op.folderId
        is Op.FolderMove -> op.folderId
        is Op.FolderDelete -> op.folderId
    }

    /** What the user would see after a rescan: folders and files with where they show, and file status. */
    private data class Seen(
        val folders: Map<Id, Pair<LocalFolder, Id?>>,
        val files: Map<Id, Triple<FileMeta, Id?, FileStatus>>,
    )

    private suspend fun replay(history: List<StoredMessage>): Seen {
        val index = InMemoryVaultIndex()
        // Only apply() is used: it reads nothing from storage.
        val indexer = Indexer(storage, index, keys)
        for (message in history) indexer.apply(message)
        val tree = VaultTree.of(index, keys)
        val statuses = index.files().associate { it.fileId to it.status }
        return Seen(
            folders = tree.folders.mapValues { (id, folder) -> folder to tree.parentOf(id) },
            files = tree.files.mapValues { (id, meta) -> Triple(meta, tree.folderOf(id), statuses.getValue(id)) },
        )
    }

    internal suspend fun sameIndex(history: List<StoredMessage>, without: Set<MessageId>): Boolean =
        replay(history) == replay(history.filter { it.id !in without })
}
