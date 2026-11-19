package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.storage.StorageBackend

/**
 * Sends the operation log (SPEC §4.3). Nothing here touches the index: the indexer applies each
 * operation when its message comes back, in message order like everyone else's.
 *
 * With [queue], creating, renaming and moving go into it, for the transfer queue to send and show,
 * rather than out at once (see [OpQueue]); deletes still go out at once.
 */
class VaultOps(
    private val storage: StorageBackend,
    private val keys: VaultKeys,
    private val queue: OpQueue? = null,
    private val clock: () -> Long,
) {
    private fun newId() = Id.random(keys.sodium)

    private suspend fun send(kind: OpKind, ops: List<Op>) {
        val captions = ops.map(keys.meta::encodeOp)
        if (queue != null) queue.enqueue(kind, captions) else captions.forEach { storage.sendText(it) }
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
