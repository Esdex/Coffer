package me.esdex.coffer.feature.browser.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileNames
import me.esdex.coffer.core.sync.Incoming
import me.esdex.coffer.core.sync.Placed
import me.esdex.coffer.core.sync.VaultTree

/** A folder on the phone as the system's document provider lists it, read once before it is brought in. */
sealed interface PhoneEntry {
    val name: String

    data class File(override val name: String, val uri: String, val mime: String?) : PhoneEntry {
        override fun toString(): String = "File(mime=$mime, name=***, uri=***)"
    }

    data class Folder(override val name: String, val children: List<PhoneEntry>) : PhoneEntry {
        override fun toString(): String = "Folder(name=***, children=${children.size})"
    }
}

/** Files in this folder and every folder below it. */
fun PhoneEntry.Folder.fileCount(): Int = children.sumOf { if (it is PhoneEntry.Folder) it.fileCount() else 1 }

/** A phone file on its way into the vault folder [folderId], under [name], maybe in place of [replaces]. */
data class Arriving(val file: PhoneEntry.File, val folderId: Id?, val name: String, val replaces: Id? = null) {
    override fun toString(): String = "Arriving(folderId=$folderId, replaces=$replaces, name=***)"
}

/** [this] as it comes into a vault folder, its names fitted to the vault's limits (SPEC §4.0). */
fun PhoneEntry.incoming(): Incoming<PhoneEntry> = when (this) {
    is PhoneEntry.File -> Incoming.File(vaultName(name, FILE_FALLBACK_NAME), this)
    is PhoneEntry.Folder -> Incoming.Folder(vaultName(name, FOLDER_FALLBACK_NAME), this, children.map { it.incoming() })
}

/** [name] shortened to the vault's limit, or [fallback] when nothing usable is left of it. */
fun vaultName(name: String, fallback: String): String =
    runCatching { FileNames.fitForImport(name) }.getOrNull()?.takeIf(FileNames::isValid) ?: fallback

const val FILE_FALLBACK_NAME = "file"
const val FOLDER_FALLBACK_NAME = "Folder"

/**
 * Brings what [placed] says (see [NameConflicts]) into the vault under [parent], keeping its shape:
 * each new folder is created before anything inside it, empty ones too, and a folder merged into one
 * of the same name uses that one. Returns every file with where it goes, for the upload queue; the
 * files themselves are read later, by the uploads.
 */
suspend fun bringIn(placed: List<Placed<PhoneEntry>>, parent: Id?, createFolder: suspend (name: String, parent: Id?) -> Id): List<Arriving> {
    val files = mutableListOf<Arriving>()
    suspend fun walk(items: List<Placed<PhoneEntry>>, under: Id?) {
        for (item in items) {
            when (item) {
                is Placed.File -> files += Arriving(item.item as PhoneEntry.File, under, item.name, item.replaces)
                is Placed.Folder -> walk(item.children, item.existing ?: createFolder(item.name, under))
            }
        }
    }
    walk(placed, parent)
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
