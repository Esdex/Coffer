package me.esdex.coffer.desktop.drive

import me.esdex.coffer.core.crypto.Id

/** What the drive asks of the vault when something is changed on it; [me.esdex.coffer.desktop.vault.VaultWriter] does it. */
interface DriveEdits {
    suspend fun createFolder(parentId: Id?, name: String)

    /** [entry], shown as [shownName] in the folder [from], to the folder [to] under [name]: a move, a rename, or both. */
    suspend fun move(entry: DriveTree.Entry, from: DriveTree.Folder, shownName: String, to: DriveTree.Folder, name: String)

    suspend fun delete(entry: DriveTree.Entry)
}
