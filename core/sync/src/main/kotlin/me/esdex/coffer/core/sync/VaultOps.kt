package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.storage.StorageBackend

/**
 * Sends the operation log (SPEC §4.3). Nothing here touches the index: the indexer applies each
 * operation when its message comes back, in message order like everyone else's.
 */
class VaultOps(
    private val storage: StorageBackend,
    private val keys: VaultKeys,
    private val clock: () -> Long,
) {
    private fun newId() = Id.random(keys.sodium)

    private suspend fun send(op: Op) {
        storage.sendText(keys.meta.encodeOp(op))
    }

    /** Returns the new folder's id. */
    suspend fun createFolder(name: String, parentId: Id?): Id {
        val folderId = newId()
        send(Op.FolderCreate(newId(), clock(), folderId, parentId, name))
        return folderId
    }

    suspend fun renameFolder(folderId: Id, name: String) = send(Op.FolderRename(newId(), clock(), folderId, name))

    /** Refuses a move into the folder itself or below it; the indexer would ignore it anyway. */
    suspend fun moveFolder(tree: VaultTree, folderId: Id, parentId: Id?) {
        require(parentId == null || !tree.isWithin(parentId, folderId)) { "folder cannot move into itself" }
        send(Op.FolderMove(newId(), clock(), folderId, parentId))
    }

    suspend fun renameFile(fileId: Id, name: String) = send(Op.FileRename(newId(), clock(), fileId, name))

    suspend fun moveFile(fileId: Id, folderId: Id?) = send(Op.FileMove(newId(), clock(), fileId, folderId))

    /**
     * Deletes a folder with everything in it: each file through [deleteFile] first, then the folders
     * deepest first, so no `folder.delete` goes out while something is still inside (SPEC §4.3).
     */
    suspend fun deleteFolder(tree: VaultTree, folderId: Id, deleteFile: suspend (Id) -> Unit) {
        val (folders, files) = tree.subtree(folderId)
        for (fileId in files) deleteFile(fileId)
        for (id in folders) send(Op.FolderDelete(newId(), clock(), id))
    }
}
