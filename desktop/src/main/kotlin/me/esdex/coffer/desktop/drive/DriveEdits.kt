package me.esdex.coffer.desktop.drive

import me.esdex.coffer.core.crypto.Id

/** What the drive asks of the vault when something is changed on it; [me.esdex.coffer.desktop.vault.VaultWriter] does it. */
interface DriveEdits {
    suspend fun createFolder(parentId: Id?, name: String)

    /**
     * [entry], shown as [shownName] in the folder [from], to the folder [to] under [name]: a move, a rename,
     * or both. [replacing] is what is there now, which goes, as rename(2) asks: a draft over a file takes its
     * place once uploaded, anything else is deleted.
     */
    suspend fun move(entry: DriveTree.Entry, from: DriveTree.Folder, shownName: String, to: DriveTree.Folder, name: String, replacing: DriveTree.Entry?)

    suspend fun delete(entry: DriveTree.Entry)

    /** A new, empty file named [name] in the folder [parentId], open for writing. */
    suspend fun createFile(parentId: Id?, name: String): OpenFile

    /** The vault's [file] open for writing: a draft that takes its place, holding its content unless [truncate]. */
    suspend fun editFile(file: DriveTree.File, truncate: Boolean): OpenFile

    /** [draft] open; for writing too if [write], emptied first if [truncate]. */
    suspend fun openDraft(draft: DriveTree.Draft, write: Boolean, truncate: Boolean): OpenFile

    /** [draft]'s size set, as truncate(2) on a closed file does. */
    suspend fun resize(draft: DriveTree.Draft, size: Long)

    /** [draft]'s copy as it is now: its size and time change with every write, the tree's only now and then. */
    fun copyOf(draft: DriveTree.Draft): java.io.File
}
