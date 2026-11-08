package me.esdex.coffer.feature.browser

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.PendingDownload
import me.esdex.coffer.core.index.PendingUpload
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageException
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
)

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

    private val messagesFlow = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** Short notices for a snackbar, as string resources: an action that did not reach Telegram. */
    val messages: SharedFlow<Int> = messagesFlow.asSharedFlow()

    private val allFiles: StateFlow<List<FileItem>> = combine(index.observeFiles(), content.keys) { files, keys ->
        if (keys == null) return@combine emptyList()
        files.mapNotNull { file ->
            val meta = keys.local.openFileMeta(file.fileId, file.sealedMeta) ?: return@mapNotNull null
            FileItem(file.fileId, meta.name, meta.mime, file.plainSize, meta.modifiedAt, file.status)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every file is in the root for now: folders arrive with the operation log (SPEC §5.2: orphans go to root). */
    val files: StateFlow<List<FileItem>> = combine(allFiles, queryFlow, sortingFlow) { files, query, sorting ->
        val matching = if (query.isBlank()) files else files.filter { it.name.contains(query.trim(), ignoreCase = true) }
        val sorted = when (sorting.by) {
            SortBy.NAME -> matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            SortBy.DATE -> matching.sortedBy { it.modifiedAt }
            SortBy.SIZE -> matching.sortedBy { it.size }
        }
        if (sorting.descending) sorted.reversed() else sorted
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val catchingUp: StateFlow<Int?> = content.catchingUp

    val transfers: StateFlow<List<TransferItem>> = combine(
        index.observeUploads(),
        index.observeDownloads(),
        allFiles,
        registry.progress,
        registry.failures,
    ) { uploads: List<PendingUpload>, downloads: List<PendingDownload>, files, progress, failures ->
        val names = files.associate { it.fileId to it.name }
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

    // Cancelling an upload deletes what it sent, so it talks to Telegram too.
    fun cancel(item: TransferItem) = act { if (item.isUpload) content.cancelUpload(item.id) else content.cancelDownload(item.id) }

    fun retry(item: TransferItem) = content.retry(item.id)
}
