package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.index.VaultIndex

/**
 * Folders and files with their decrypted metadata, and where each one shows. A parent that is deleted
 * or unknown puts the child in the root (SPEC §5.2), and so would a loop, which the indexer never makes.
 */
class VaultTree(val folders: Map<Id, LocalFolder>, val files: Map<Id, FileMeta>) {

    private val parents: Map<Id, Id?> = folders.mapValues { (id, folder) -> shownParent(id, folder.parentId) }

    private fun shownParent(folderId: Id, parentId: Id?): Id? {
        if (parentId == null || parentId !in folders) return null
        val seen = mutableSetOf(folderId)
        var current: Id? = parentId
        while (current != null) {
            if (!seen.add(current)) return null
            current = folders[current]?.parentId
        }
        return parentId
    }

    /** Where [folderId] shows: null for the root. */
    fun parentOf(folderId: Id): Id? = parents[folderId]

    /** Where [fileId] shows: null for the root. */
    fun folderOf(fileId: Id): Id? = files[fileId]?.folderId?.takeIf { it in folders }

    fun childFolders(parentId: Id?): List<Id> = folders.keys.filter { parentOf(it) == parentId }

    fun childFiles(folderId: Id?): List<Id> = files.keys.filter { folderOf(it) == folderId }

    /** [folderId] and the folders above it, from the root down. */
    fun path(folderId: Id): List<Id> = generateSequence(folderId) { parentOf(it) }.toList().asReversed()

    /** True if [folderId] is [ancestorId] or lies somewhere below it. */
    fun isWithin(folderId: Id, ancestorId: Id): Boolean = generateSequence(folderId) { parentOf(it) }.any { it == ancestorId }

    /** Everything under [folderId]: its folders deepest first (ending with itself), and every file in them. */
    fun subtree(folderId: Id): Pair<List<Id>, List<Id>> {
        val order = mutableListOf<Id>()
        fun visit(id: Id) {
            for (child in childFolders(id)) visit(child)
            order += id
        }
        visit(folderId)
        val inside = order.toSet()
        return order to files.keys.filter { folderOf(it) in inside }
    }

    /**
     * This tree without [gone] and all inside the folders among them: what is being deleted, or waits for
     * its Undo to pass, holds no name a new thing must give way to (audit 3, Q3-1).
     */
    fun without(gone: Set<Id>): VaultTree {
        if (gone.isEmpty()) return this
        val goneFolders = folders.keys.filterTo(HashSet()) { id -> gone.any { it in folders && isWithin(id, it) } }
        return VaultTree(folders - goneFolders, files.filterKeys { it !in gone && folderOf(it) !in goneFolders })
    }

    companion object {
        /** Reads and opens the whole index; rows that do not open are left out. */
        suspend fun of(index: VaultIndex, keys: VaultKeys): VaultTree = VaultTree(
            folders = index.folders().mapNotNull { f -> keys.local.openFolder(f.folderId, f.sealedMeta)?.let { f.folderId to it } }.toMap(),
            files = index.files().mapNotNull { f -> keys.local.openFileMeta(f.fileId, f.sealedMeta)?.let { f.fileId to it } }.toMap(),
        )
    }
}
