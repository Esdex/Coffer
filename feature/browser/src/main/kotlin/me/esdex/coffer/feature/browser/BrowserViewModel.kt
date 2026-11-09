package me.esdex.coffer.feature.browser

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.PendingDownload
import me.esdex.coffer.core.index.PendingUpload
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.feature.browser.transfer.TransferRegistry
import me.esdex.coffer.feature.browser.transfer.VaultContent
import javax.inject.Inject

/** A file as the browser shows it, decrypted in memory only (SPEC §8). */
data class FileItem(
    val fileId: Id,
    val name: String,
    val mime: String,
    val size: Long,
    val modifiedAt: Long,
    val status: FileStatus,
    /** Its delete is on the way to Telegram. */
    val deleting: Boolean = false,
)

data class FolderItem(val folderId: Id, val name: String, val deleting: Boolean = false)

/** What one screen of the browser shows. */
data class Listing(
    /** From the top folder down to the one shown; empty in the root. */
    val path: List<FolderItem> = emptyList(),
    val folders: List<FolderItem> = emptyList(),
    val files: List<FileItem> = emptyList(),
) {
    val folderId: Id? get() = path.lastOrNull()?.folderId
}

enum class SortBy { NAME, DATE, SIZE }

data class Sorting(val by: SortBy = SortBy.NAME, val descending: Boolean = false)

data class TransferItem(
    val id: String,
    val isUpload: Boolean,
    /** Known only while unlocked; null shows a generic label. */
    val name: String?,
    val progress: TransferRegistry.Progress?,
    val failure: TransferRegistry.Failure?,
    val preparing: Boolean,
)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val content: VaultContent,
    private val index: VaultIndex,
    private val registry: TransferRegistry,
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    val query: StateFlow<String> = queryFlow.asStateFlow()

    private val sortingFlow = MutableStateFlow(Sorting())
    val sorting: StateFlow<Sorting> = sortingFlow.asStateFlow()

    private val folderFlow = MutableStateFlow<Id?>(null)

    private val messagesFlow = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** Short notices for a snackbar, as string resources: an action that did not reach Telegram. */
    val messages: SharedFlow<Int> = messagesFlow.asSharedFlow()

    private class Contents(val tree: VaultTree, val files: Map<Id, FileItem>)

    private val contents: StateFlow<Contents?> = combine(index.observeFiles(), index.observeFolders(), content.keys) { files, folders, keys ->
        if (keys == null) return@combine null
        val metas = files.mapNotNull { file -> keys.local.openFileMeta(file.fileId, file.sealedMeta)?.let { file to it } }
        Contents(
            tree = VaultTree(
                folders = folders.mapNotNull { f -> keys.local.openFolder(f.folderId, f.sealedMeta)?.let { f.folderId to it } }.toMap(),
                files = metas.associate { (file, meta) -> file.fileId to meta },
            ),
            files = metas.associate { (file, meta) ->
                file.fileId to FileItem(file.fileId, meta.name, meta.mime, file.plainSize, meta.modifiedAt, file.status)
            },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The whole tree, for picking where to move something; null while locked. */
    val tree: StateFlow<VaultTree?> = contents.map { it?.tree }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The folder shown, or everything matching the search across all folders. */
    val listing: StateFlow<Listing> = combine(contents, folderFlow, queryFlow, sortingFlow, content.deleting) { contents, folder, query, sorting, deleting ->
        if (contents == null) return@combine Listing()
        val tree = contents.tree
        // A folder deleted from another device takes the browser back to the root.
        val current = folder?.takeIf { it in tree.folders }
        val path = current?.let { tree.path(it).map { id -> FolderItem(id, tree.folders.getValue(id).name) } }.orEmpty()
        val needle = query.trim()
        val folders = if (needle.isEmpty()) tree.childFolders(current) else tree.folders.filterValues { it.name.contains(needle, ignoreCase = true) }.keys
        val files = (if (needle.isEmpty()) tree.childFiles(current).mapNotNull(contents.files::get) else contents.files.values.filter { it.name.contains(needle, ignoreCase = true) })
            .map { if (it.fileId in deleting) it.copy(deleting = true) else it }

        // Folders are listed by name whatever the sorting; they have no size or date of their own.
        val folderItems = folders.map { FolderItem(it, tree.folders.getValue(it).name, deleting = it in deleting) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .let { if (sorting.by == SortBy.NAME && sorting.descending) it.reversed() else it }
        val sorted = when (sorting.by) {
            SortBy.NAME -> files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            SortBy.DATE -> files.sortedBy { it.modifiedAt }
            SortBy.SIZE -> files.sortedBy { it.size }
        }
        Listing(path, folderItems, if (sorting.descending) sorted.reversed() else sorted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Listing())

    val catchingUp: StateFlow<Int?> = content.catchingUp

    val transfers: StateFlow<List<TransferItem>> = combine(
        index.observeUploads(),
        index.observeDownloads(),
        contents,
        registry.progress,
        registry.failures,
    ) { uploads: List<PendingUpload>, downloads: List<PendingDownload>, contents, progress, failures ->
        val names = contents?.files?.mapValues { it.value.name }.orEmpty()
        uploads.map {
            val name = it.fileId?.let(names::get) ?: content.keys.value?.local?.openUpload(it.uploadId, it.sealedRequest)?.name
            TransferItem(it.uploadId, true, name, progress[it.uploadId], failures[it.uploadId], it.fileId == null)
        } + downloads.map {
            TransferItem(it.downloadId, false, names[it.fileId], progress[it.downloadId], failures[it.downloadId], false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val readyToOpen = registry.readyToOpen

    fun search(query: String) {
        queryFlow.value = query
    }

    fun sortBy(by: SortBy) {
        val current = sortingFlow.value
        sortingFlow.value = if (current.by == by) current.copy(descending = !current.descending) else Sorting(by)
    }

    fun openFolder(folderId: Id) {
        queryFlow.value = ""
        folderFlow.value = folderId
    }

    fun goToRoot() {
        queryFlow.value = ""
        folderFlow.value = null
    }

    /** One level up. False in the root, where there is nowhere to go. */
    fun up(): Boolean {
        val current = listing.value.folderId ?: return false
        folderFlow.value = tree.value?.parentOf(current)
        return true
    }

    /** Runs an action that talks to Telegram; a failure becomes a notice instead of a crash. */
    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: FloodWaitException) {
                messagesFlow.emit(R.string.browser_error_flood)
            } catch (_: StorageException) {
                messagesFlow.emit(R.string.browser_error_network)
            }
        }
    }

    fun upload(requests: List<VaultContent.UploadRequest>) {
        viewModelScope.launch { content.upload(requests) }
    }

    fun export(fileId: Id, target: Uri) {
        viewModelScope.launch { content.download(fileId, target) }
    }

    fun open(fileId: Id) {
        viewModelScope.launch { content.download(fileId, null) }
    }

    fun delete(fileId: Id) = act { content.delete(fileId) }

    fun renameFile(fileId: Id, name: String) = act { content.renameFile(fileId, name) }

    fun moveFile(fileId: Id, folderId: Id?) = act { content.moveFile(fileId, folderId) }

    fun createFolder(name: String) {
        val parent = listing.value.folderId
        act { content.createFolder(name, parent) }
    }

    fun renameFolder(folderId: Id, name: String) = act { content.renameFolder(folderId, name) }

    fun moveFolder(folderId: Id, parentId: Id?) = act { content.moveFolder(folderId, parentId) }

    fun deleteFolder(folderId: Id) = act { content.deleteFolder(folderId) }


    // Cancelling an upload deletes what it sent, so it talks to Telegram too.
    fun cancel(item: TransferItem) = act { if (item.isUpload) content.cancelUpload(item.id) else content.cancelDownload(item.id) }

    fun retry(item: TransferItem) = content.retry(item.id)
}
