package me.esdex.coffer.feature.browser

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.PendingDownload
import me.esdex.coffer.core.index.PendingUpload
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.core.tdlib.Connection
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.ui.theme.CacheStore
import me.esdex.coffer.feature.browser.transfer.CacheOffer
import me.esdex.coffer.feature.browser.transfer.FileCache
import me.esdex.coffer.feature.browser.transfer.PrefetchQueue
import me.esdex.coffer.feature.browser.transfer.TransferRegistry
import me.esdex.coffer.feature.browser.transfer.isCached
import me.esdex.coffer.feature.browser.transfer.VaultContent
import me.esdex.coffer.feature.browser.viewer.ViewerViewModel
import me.esdex.coffer.feature.browser.viewer.audio.AudioPlayback
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
    /** Whole on this phone: it opens at once, with no network. */
    val cached: Boolean = false,
) {
    /** An upload is left alone until it is done (SPEC §5.2), and a delete on its way is final. */
    val selectable: Boolean get() = status != FileStatus.UPLOADING && !deleting
}

data class FolderItem(val folderId: Id, val name: String, val deleting: Boolean = false) {
    val selectable: Boolean get() = !deleting
}

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
    /** Brought onto the phone for the cache, not decrypted anywhere. */
    val caching: Boolean = false,
)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val content: VaultContent,
    private val index: VaultIndex,
    private val registry: TransferRegistry,
    private val playback: AudioPlayback,
    private val cache: FileCache,
    private val cacheStore: CacheStore,
    telegram: Telegram,
) : ViewModel() {

    /** Telegram's link, for the title bar. */
    val connection: StateFlow<Connection> = telegram.connection
        .ignoringBlips(CONNECTION_GRACE_MS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Connection.READY)

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

    private val selectionFlow = MutableStateFlow<Set<Id>>(emptySet())

    /** Files and folders picked for one action on all of them: only those still in the vault. */
    val selection: StateFlow<Set<Id>> = combine(selectionFlow, contents) { picked, contents ->
        if (contents == null) emptySet() else picked.filterTo(HashSet()) { it in contents.files || it in contents.tree.folders }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleSelected(id: Id) = selectionFlow.update { if (id in it) it - id else it + id }

    /** Everything on screen that can be picked: not what is being uploaded or deleted. */
    fun selectAll() {
        val shown = listing.value
        selectionFlow.value = (shown.folders.filter { it.selectable }.map { it.folderId } + shown.files.filter { it.selectable }.map { it.fileId }).toSet()
    }

    fun clearSelection() {
        selectionFlow.value = emptySet()
    }

    /** The selection split into folders and files, then cleared: the action takes it from here. */
    private fun takeSelection(): Pair<List<Id>, List<Id>> {
        val picked = selection.value
        val tree = contents.value?.tree
        clearSelection()
        val folders = picked.filter { tree != null && it in tree.folders }
        return folders to (picked - folders.toSet()).toList()
    }

    fun deleteSelected() {
        val (folders, files) = takeSelection()
        deleteLater(folders, files)
    }

    init {
        viewModelScope.launch { content.deleteFailures.collect { messagesFlow.emit(R.string.browser_delete_failed) } }
    }

    private val undoableFlow = MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** How many items a delete just took out of sight, for the snackbar that offers to undo it. */
    val undoable: SharedFlow<Int> = undoableFlow.asSharedFlow()

    private fun deleteLater(folders: List<Id>, files: List<Id>) {
        viewModelScope.launch {
            content.deleteLater(folders, files)
            undoableFlow.emit(folders.size + files.size)
        }
    }

    fun undoDelete() {
        viewModelScope.launch { if (!content.undoDelete()) messagesFlow.emit(R.string.browser_undo_too_late) }
    }

    /** A delete waiting for its undo goes out first: it needs the keys the lock takes away. */
    fun lock(onLock: () -> Unit) {
        viewModelScope.launch {
            runCatching { content.flushDeletes() }
            onLock()
        }
    }

    fun moveSelected(destination: Id?) {
        val (folders, files) = takeSelection()
        act { content.moveMany(folders, files, destination) }
    }

    /** Files and folders, each folder with everything in it. */
    fun exportSelected(tree: Uri) {
        val (folders, files) = takeSelection()
        export(tree, folders, files)
    }

    fun exportFolder(folderId: Id, tree: Uri) = export(tree, listOf(folderId), emptyList())

    private fun export(tree: Uri, folders: List<Id>, files: List<Id>) {
        viewModelScope.launch {
            val count = content.exportTo(tree, folders, files)
            if (count.notReady > 0) messagesFlow.emit(R.string.browser_export_not_ready)
            if (count.failed > 0) messagesFlow.emit(R.string.browser_export_some_failed)
        }
    }

    /** A folder from the phone into the folder on screen, with everything in it. */
    fun importFolder(tree: Uri) {
        val parent = listing.value.folderId
        act {
            if (content.importFolder(tree, parent) == 0) messagesFlow.emit(R.string.browser_import_empty)
        }
    }

    /** What the viewer shows and the page it opened on; kept here, so a recreated screen does not close it. */
    data class Viewing(val pages: List<ViewerViewModel.Page>, val start: Int)

    private val viewingFlow = MutableStateFlow<Viewing?>(null)
    val viewing: StateFlow<Viewing?> = viewingFlow.asStateFlow()

    fun view(pages: List<ViewerViewModel.Page>, start: Int) {
        viewingFlow.value = Viewing(pages, start)
    }

    fun closeViewer() {
        viewingFlow.value = null
    }

    /** A document open on its own, outside the pager: a PDF scrolls and zooms in ways a swipe to the next file would fight. */
    private val readingFlow = MutableStateFlow<ViewerViewModel.Page?>(null)
    val reading: StateFlow<ViewerViewModel.Page?> = readingFlow.asStateFlow()

    fun read(page: ViewerViewModel.Page) {
        readingFlow.value = page
    }

    fun closeReading() {
        readingFlow.value = null
    }

    /** The music queued; the player is on screen while there is any, and comes back with it after an unlock. */
    val listening: StateFlow<List<ViewerViewModel.Page>> = playback.queue

    /** Whether the player fills the screen or sits folded under the files. */
    val playerExpanded: StateFlow<Boolean> = playback.expanded

    /** The tracks of the folder on screen, from the one tapped. */
    fun listen(tracks: List<ViewerViewModel.Page>, start: Int) {
        viewModelScope.launch {
            if (!playback.play(tracks, start)) messagesFlow.emit(R.string.audio_failed)
        }
    }

    /** The whole tree, for picking where to move something; null while locked. */
    val tree: StateFlow<VaultTree?> = contents.map { it?.tree }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Files whole on the phone, by the cache's ledger. */
    private val cached: Flow<Set<Id>> = combine(index.observeFiles(), cache.blobs.held) { files, held ->
        files.filter { it.isCached(held) }.mapTo(HashSet()) { it.fileId }
    }.flowOn(Dispatchers.Default)

    private class Marks(val deleting: Set<Id>, val hidden: Set<Id>, val cached: Set<Id>)

    private val marks = combine(content.deleting, content.hidden, cached, ::Marks)

    /** The folder shown, or everything matching the search across all folders. */
    val listing: StateFlow<Listing> = combine(contents, folderFlow, queryFlow, sortingFlow, marks) { contents, folder, query, sorting, marks ->
        val deleting = marks.deleting
        val hidden = marks.hidden
        if (contents == null) return@combine Listing()
        val tree = contents.tree
        // Deleted and waiting for the undo to pass: out of sight, and so is everything inside a folder that is.
        val hiddenFolders = hidden.filter { it in tree.folders }
        fun hiddenFolder(id: Id?) = id != null && hiddenFolders.any { tree.isWithin(id, it) }
        // A folder deleted from another device takes the browser back to the root.
        val current = folder?.takeIf { it in tree.folders }
        val path = current?.let { tree.path(it).map { id -> FolderItem(id, tree.folders.getValue(id).name) } }.orEmpty()
        val needle = query.trim()
        val folders = (if (needle.isEmpty()) tree.childFolders(current) else tree.folders.filterValues { it.name.contains(needle, ignoreCase = true) }.keys)
            .filterNot(::hiddenFolder)
        val files = (if (needle.isEmpty()) tree.childFiles(current).mapNotNull(contents.files::get) else contents.files.values.filter { it.name.contains(needle, ignoreCase = true) })
            .filterNot { it.fileId in hidden || hiddenFolder(tree.folderOf(it.fileId)) }
            .map { it.copy(deleting = it.fileId in deleting, cached = it.fileId in marks.cached) }

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

    private class Queued(val uploads: List<PendingUpload>, val downloads: List<PendingDownload>, val caching: List<PrefetchQueue.Item>)

    val transfers: StateFlow<List<TransferItem>> = combine(
        combine(index.observeUploads(), index.observeDownloads(), cache.queue.items, ::Queued),
        contents,
        registry.progress,
        registry.failures,
    ) { queued, contents, progress, failures ->
        val names = contents?.files?.mapValues { it.value.name }.orEmpty()
        queued.uploads.map {
            val name = it.fileId?.let(names::get) ?: content.keys.value?.local?.openUpload(it.uploadId, it.sealedRequest)?.name
            TransferItem(it.uploadId, true, name, progress[it.uploadId], failures[it.uploadId], it.fileId == null)
        } + queued.downloads.map {
            TransferItem(it.downloadId, false, names[it.fileId], progress[it.downloadId], failures[it.downloadId], false)
        } + queued.caching.map {
            TransferItem(it.id, false, names[it.fileId], progress[it.id], failures[it.id], false, caching = true)
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

    fun delete(fileId: Id) = deleteLater(emptyList(), listOf(fileId))

    fun renameFile(fileId: Id, name: String) = act { content.renameFile(fileId, name) }

    fun moveFile(fileId: Id, folderId: Id?) = act { content.moveFile(fileId, folderId) }

    fun createFolder(name: String) {
        val parent = listing.value.folderId
        act { content.createFolder(name, parent) }
    }

    fun renameFolder(folderId: Id, name: String) = act { content.renameFolder(folderId, name) }

    fun moveFolder(folderId: Id, parentId: Id?) = act { content.moveFolder(folderId, parentId) }

    fun deleteFolder(folderId: Id) = deleteLater(listOf(folderId), emptyList())


    private val cacheOfferFlow = MutableStateFlow<CacheOffer?>(null)

    /** "Cache on this phone" waiting for the user's word, with what it would take. */
    val cacheOffer: StateFlow<CacheOffer?> = cacheOfferFlow.asStateFlow()

    fun cacheFile(fileId: Id) = offerCache(emptyList(), listOf(fileId))

    fun cacheFolder(folderId: Id) = offerCache(listOf(folderId), emptyList())

    fun cacheSelected() {
        val (folders, files) = takeSelection()
        offerCache(folders, files)
    }

    /** Folders with everything in them, then the files picked by themselves, in the order shown. */
    private fun offerCache(folders: List<Id>, files: List<Id>) {
        val tree = tree.value ?: return
        val all = (folders.flatMap { tree.subtree(it).second } + files).distinct()
        viewModelScope.launch {
            val offer = content.cacheOffer(all) ?: return@launch
            when {
                offer.fileIds.isEmpty() -> messagesFlow.emit(R.string.browser_cache_nothing)
                offer.alreadyCached && offer.prefs.enabled -> messagesFlow.emit(R.string.browser_cache_already)
                else -> cacheOfferFlow.value = offer
            }
        }
    }

    /** Agreed: the cache turned on if it was off, its limit raised to [limitMb] if asked, and the files queued. */
    fun acceptCacheOffer(limitMb: Int? = null) {
        val offer = cacheOfferFlow.value ?: return
        cacheOfferFlow.value = null
        cacheStore.update { it.copy(enabled = true, limitMb = limitMb ?: it.limitMb) }
        content.cacheFiles(offer.fileIds)
    }

    fun dismissCacheOffer() {
        cacheOfferFlow.value = null
    }

    // Cancelling an upload deletes what it sent, so it talks to Telegram too.
    fun cancel(item: TransferItem) = act {
        when {
            item.isUpload -> content.cancelUpload(item.id)
            item.caching -> content.cancelPrefetch(item.id)
            else -> content.cancelDownload(item.id)
        }
    }

    fun retry(item: TransferItem) = content.retry(item.id)
}

/**
 * Coming back to the app reconnects in well under a second, and a title that said so every time would
 * flicker; a lapse is shown only once it has lasted [graceMillis].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Connection>.ignoringBlips(graceMillis: Long): Flow<Connection> = transformLatest { state ->
    if (state != Connection.READY) delay(graceMillis)
    emit(state)
}

private const val CONNECTION_GRACE_MS = 1_000L
