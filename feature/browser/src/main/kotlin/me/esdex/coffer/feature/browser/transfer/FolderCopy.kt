package me.esdex.coffer.feature.browser.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.sync.VaultTree

/** A folder on the phone as the system's document provider lists it, read once before it is brought in. */
sealed interface PhoneEntry {
    val name: String

    data class File(override val name: String, val uri: String, val mime: String?) : PhoneEntry

    data class Folder(override val name: String, val children: List<PhoneEntry>) : PhoneEntry
}

/**
 * Brings [folder] into the vault under [parent], keeping its shape: each folder is created before
 * anything inside it, empty ones too. Returns every file with the vault folder it goes into, for the
 * upload queue; the files themselves are read later, by the uploads.
 */
suspend fun bringIn(folder: PhoneEntry.Folder, parent: Id?, createFolder: suspend (name: String, parent: Id?) -> Id): List<Pair<PhoneEntry.File, Id>> {
    val files = mutableListOf<Pair<PhoneEntry.File, Id>>()
    suspend fun walk(at: PhoneEntry.Folder, under: Id?) {
        val created = createFolder(at.name, under)
        for (child in at.children) {
            when (child) {
                is PhoneEntry.File -> files += child to created
                is PhoneEntry.Folder -> walk(child, created)
            }
        }
    }
    walk(folder, parent)
    return files
}

/** What saving a vault folder came to: files started, files left out as not whole, and failures. */
data class SaveCount(val started: Int = 0, val notReady: Int = 0, val failed: Int = 0) {
    operator fun plus(other: SaveCount) = SaveCount(started + other.started, notReady + other.notReady, failed + other.failed)
}

/**
 * Saves the vault folder [folderId] into [into] on the phone with its shape: a folder of the same name,
 * then what is inside. [createFolder] makes a folder on the phone and returns it, or null when the
 * phone refuses; everything meant to go inside it then counts as failed. Files that are not whole
 * ([ready] false) cannot be decrypted and are left out.
 */
suspend fun <D> saveOut(
    tree: VaultTree,
    folderId: Id,
    into: D,
    ready: suspend (Id) -> Boolean,
    createFolder: suspend (name: String, into: D) -> D?,
    saveFile: suspend (fileId: Id, into: D) -> Boolean,
): SaveCount {
    val name = tree.folders[folderId]?.name ?: return SaveCount()
    val created = createFolder(name, into) ?: return SaveCount(failed = filesUnder(tree, folderId))
    var count = SaveCount()
    for (fileId in tree.childFiles(folderId)) {
        count += when {
            !ready(fileId) -> SaveCount(notReady = 1)
            saveFile(fileId, created) -> SaveCount(started = 1)
            else -> SaveCount(failed = 1)
        }
    }
    for (child in tree.childFolders(folderId)) count += saveOut(tree, child, created, ready, createFolder, saveFile)
    return count
}

private fun filesUnder(tree: VaultTree, folderId: Id): Int = tree.subtree(folderId).second.size

/**
 * Reads the folder [tree] the user picked, all the way down, through the system's document provider.
 * Names and types only: the files are read by the uploads.
 */
internal fun ContentResolver.readPhoneFolder(tree: Uri): PhoneEntry.Folder {
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    fun read(documentId: String, name: String): PhoneEntry.Folder {
        val children = mutableListOf<PhoneEntry>()
        query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId), columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0) ?: continue
                val childName = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                children += if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    read(childId, childName)
                } else {
                    PhoneEntry.File(childName, DocumentsContract.buildDocumentUriUsingTree(tree, childId).toString(), mime)
                }
            }
        }
        return PhoneEntry.Folder(name, children)
    }
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val rootName = query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    return read(rootId, rootName ?: "Folder")
}
