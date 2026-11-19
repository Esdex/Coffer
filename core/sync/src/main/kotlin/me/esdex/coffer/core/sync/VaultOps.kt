package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.index.OpMessage
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend

/**
 * Sends the operation log (SPEC §4.3). Nothing here touches the index: the indexer applies each
 * operation when its message comes back, in message order like everyone else's.
 *
 * Creating, renaming, moving and deleting go in packs (SPEC §4.6), into [queue] if there is one, for the
 * transfer queue to send and show, rather than out at once (see [OpQueue]).
 */
class VaultOps(
    private val storage: StorageBackend,
    private val keys: VaultKeys,
    private val queue: OpQueue? = null,
    private val clock: () -> Long,
) {
    private fun newId() = Id.random(keys.sodium)

    /**
     * As few messages as hold [ops] (SPEC §4.6): Telegram lets about 20 into a chat before a pause of
     * minutes, and a folder brought in with its subfolders was one message a folder.
     */
    private suspend fun send(kind: OpKind, ops: List<Op>) {
        val messages = keys.meta.packOps(ops).map { OpMessage(keys.meta.encodeOpPack(it), it.size) }
        if (queue != null) queue.enqueue(kind, messages) else messages.forEach { storage.sendText(it.caption) }
    }

    /**
     * Folders with everything in them and files, as one batch of deletes in packs: each file first,
     * then the folders deepest first, so no `folder.delete` goes while something is still inside (SPEC
     * §4.3). Each message carries the [partsOf] its files, deleted once it is in Telegram (see
     * [OpQueue.drain]). [handled] takes a file out of the batch when it deals with it itself, as with an
     * upload of this device's, cancelled instead. Needs [queue].
     */
    suspend fun deleteMany(
        tree: VaultTree,
        folderIds: List<Id>,
        fileIds: List<Id>,
        partsOf: suspend (Id) -> Collection<MessageId>,
        handled: suspend (Id) -> Boolean = { false },
    ) {
        val queue = checkNotNull(queue) { "deletes go through the queue" }
        val subtrees = folderIds.map(tree::subtree)
        val folders = subtrees.flatMap { it.first }.distinct()
        val files = (subtrees.flatMap { it.second } + fileIds).distinct().filterNot { handled(it) }
        val ops = files.map { Op.FileDelete(newId(), clock(), it) } + folders.map { Op.FolderDelete(newId(), clock(), it) }
        val messages = keys.meta.packOps(ops).map { pack ->
            val deletedFiles = pack.mapNotNull { (it as? Op.FileDelete)?.fileId }
            OpMessage(
                keys.meta.encodeOpPack(pack),
                pack.size,
                deletedFiles = deletedFiles,
                deletedFolders = pack.mapNotNull { (it as? Op.FolderDelete)?.folderId },
                parts = deletedFiles.flatMap { partsOf(it) },
            )
        }
        queue.enqueue(OpKind.DELETE, messages)
    }

    private suspend fun sendNow(op: Op) {
        storage.sendText(keys.meta.encodeOp(op))
    }

    /** Returns the new folder's id. */
    suspend fun createFolder(name: String, parentId: Id?): Id = createFolders { create -> create(name, parentId) }

    /**
     * Several folders as one batch, as for a folder brought in from the phone with its subfolders.
     * [build] gets a function that names a folder and returns its id at once; the folders are sent,
     * or queued together, when [build] is done.
     */
    suspend fun <T> createFolders(build: suspend (create: (name: String, parentId: Id?) -> Id) -> T): T {
        val ops = mutableListOf<Op>()
        val result = build { name, parentId -> newId().also { ops += Op.FolderCreate(newId(), clock(), it, parentId, name) } }
        send(OpKind.CREATE_FOLDERS, ops)
        return result
    }

    suspend fun renameFolder(folderId: Id, name: String) = send(OpKind.RENAME, listOf(Op.FolderRename(newId(), clock(), folderId, name)))

    /** Refuses a move into the folder itself or below it; the indexer would ignore it anyway. */
    suspend fun moveFolder(tree: VaultTree, folderId: Id, parentId: Id?) = moveMany(tree, listOf(folderId), emptyList(), parentId)

    suspend fun renameFile(fileId: Id, name: String) = send(OpKind.RENAME, listOf(Op.FileRename(newId(), clock(), fileId, name)))

    suspend fun moveFile(fileId: Id, folderId: Id?) = send(OpKind.MOVE, listOf(Op.FileMove(newId(), clock(), fileId, folderId)))

    /** Folders and files into [destination], as one batch; refuses a folder into itself or below it. */
    suspend fun moveMany(tree: VaultTree, folderIds: List<Id>, fileIds: List<Id>, destination: Id?) {
        require(destination == null || folderIds.none { tree.isWithin(destination, it) }) { "folder cannot move into itself" }
        val ops = folderIds.map { Op.FolderMove(newId(), clock(), it, destination) } +
            fileIds.map { Op.FileMove(newId(), clock(), it, destination) }
        send(OpKind.MOVE, ops)
    }

    /**
     * Deletes a folder with everything in it: each file through [deleteFile] first, then the folders
     * deepest first, so no `folder.delete` goes out while something is still inside (SPEC §4.3).
     */
    suspend fun deleteFolder(tree: VaultTree, folderId: Id, deleteFile: suspend (Id) -> Unit) {
        val (folders, files) = tree.subtree(folderId)
        for (fileId in files) deleteFile(fileId)
        for (id in folders) sendNow(Op.FolderDelete(newId(), clock(), id))
    }
}
