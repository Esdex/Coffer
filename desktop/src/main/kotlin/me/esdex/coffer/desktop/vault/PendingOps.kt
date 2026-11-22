package me.esdex.coffer.desktop.vault

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.index.PendingOp
import me.esdex.coffer.core.sync.VaultKeys
import me.esdex.coffer.core.sync.VaultTree

/**
 * The vault as it will be once this computer's operations are in: the index shows an operation only when
 * its message comes back from Telegram (SPEC §4.3), seconds later, minutes under a FLOOD_WAIT, where a file
 * manager wants a folder it made to be there at once. Applied in the order they were made; the indexer's
 * own rules settle the vault when they come back.
 */
object PendingOps {

    /** [tree] with [ops] applied, in order. */
    fun apply(tree: VaultTree, ops: List<Op>): VaultTree {
        if (ops.isEmpty()) return tree
        val folders = tree.folders.toMutableMap()
        val files = tree.files.toMutableMap()
        for (op in ops) {
            when (op) {
                is Op.FolderCreate -> folders[op.folderId] = LocalFolder(op.name, op.parentId)
                is Op.FolderRename -> folders[op.folderId]?.let { folders[op.folderId] = it.copy(name = op.name) }
                is Op.FolderMove -> folders[op.folderId]?.let { folders[op.folderId] = it.copy(parentId = op.parentId) }
                is Op.FolderDelete -> folders.remove(op.folderId)
                is Op.FileRename -> files[op.fileId]?.let { files[op.fileId] = it.copy(name = op.name) }
                is Op.FileMove -> files[op.fileId]?.let { files[op.fileId] = it.copy(folderId = op.folderId) }
                is Op.FileDelete -> files.remove(op.fileId)
            }
        }
        return VaultTree(folders, files)
    }

    /** The operations in the queue, oldest first; a held row has no caption yet and holds none. */
    fun of(queue: List<PendingOp>, keys: VaultKeys): List<Op> = queue.flatMap { row ->
        Captions.parse(row.caption)?.let(keys.meta::decodeOps).orEmpty()
    }

    /** Deletes waiting to be queued: their files, and their folders with all inside, are gone from the drive already. */
    fun without(tree: VaultTree, gone: Set<Id>): VaultTree = tree.without(gone)
}
