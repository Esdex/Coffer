package me.esdex.coffer.feature.browser.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.esdex.coffer.core.crypto.ContentLayout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileNames
import me.esdex.coffer.core.crypto.format.LocalUpload
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.PendingDownload
import me.esdex.coffer.core.index.PendingUpload
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.FileDeleter
import me.esdex.coffer.core.sync.Indexer
import me.esdex.coffer.core.sync.Maintenance
import me.esdex.coffer.core.sync.Snapshotter
import me.esdex.coffer.core.sync.UploadPreparer
import me.esdex.coffer.core.sync.UploadRunner
import me.esdex.coffer.core.sync.TransferKeys
import me.esdex.coffer.core.sync.VaultFileReader
import me.esdex.coffer.core.sync.VaultKeys
import me.esdex.coffer.core.sync.VaultOps
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.core.sync.eachWaitingOutFloods
import me.esdex.coffer.core.sync.reader
import me.esdex.coffer.core.sync.transferKeys
import me.esdex.coffer.core.sync.waitingOutFloods
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.core.vault.VaultStore

/** Saved Messages once TDLib is signed in, or null if it does not get there in time. */
suspend fun Telegram.readyStorage(timeoutMillis: Long = 60_000): StorageBackend? {
    start()
    val state = withTimeoutOrNull(timeoutMillis) { state.first { it == AuthState.Ready } } ?: return null
    return if (state == AuthState.Ready) storage() else null
}

/** Decrypted copies for other apps (SPEC §6): cleared on lock, at start and some minutes after opening. */
object OpenFiles {
    /**
     * How long a copy stays. An app that already opened it keeps reading through its descriptor after
     * the file is deleted, so this only limits how long the plaintext sits in the cache.
     */
    const val LIFETIME_MILLIS = 10 * 60 * 1000L

    fun dir(context: Context) = File(context.cacheDir, "open")

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /** Deletes every copy in [dir] written more than [maxAgeMillis] before [now]. Each download has its own folder. */
    fun sweep(dir: File, now: Long, maxAgeMillis: Long = LIFETIME_MILLIS) {
        for (copy in dir.listFiles().orEmpty()) {
            if (now - copy.lastModified() >= maxAgeMillis) copy.deleteRecursively()
        }
    }
}

/**
 * Encrypted parts and snapshots on their way to Saved Messages. Each is deleted once sent, but a process
 * killed mid-send leaves its file behind; a new process has nothing in flight yet, so it starts clean.
 */
object WorkFiles {
    fun upload(context: Context) = File(context.cacheDir, "upload")

    fun snapshot(context: Context) = File(context.cacheDir, "snapshot")

    fun clear(context: Context) {
        upload(context).deleteRecursively()
        snapshot(context).deleteRecursively()
    }
}

/**
 * Everything that needs the open vault: following Saved Messages into the index, preparing uploads,
 * handing keys to transfers. Starts on unlock and stops on lock, when the keys it borrowed are wiped;
 * running transfers keep going with their own DEK (SPEC §6).
 */
@OptIn(FlowPreview::class)
@Singleton
class VaultContent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sodium: Sodium,
    private val telegram: Telegram,
    private val vault: VaultManager,
    private val index: VaultIndex,
    private val registry: TransferRegistry,
    private val store: VaultStore,
    private val inbox: ShareInbox,
    private val cache: FileCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Files open in the viewers: their pieces go to the cache when the last reader of each closes.
    private val leases = cache.leases

    private var session: Job? = null

    /** The open session's indexer and snapshotter; null while locked or not yet signed in. */
    private class Live(val indexer: Indexer, val snapshotter: Snapshotter, val maintenance: Maintenance)

    @Volatile
    private var live: Live? = null
    private val snapshotLock = Mutex()
    private val pendingWork = Channel<Unit>(Channel.CONFLATED)

    private val keysFlow = MutableStateFlow<VaultKeys?>(null)

    /** The open vault's keys, for showing names; null while locked. */
    val keys: StateFlow<VaultKeys?> = keysFlow.asStateFlow()

    private val catchUpFlow = MutableStateFlow<Int?>(null)

    private val deletingFlow = MutableStateFlow<Set<Id>>(emptySet())

    /**
     * Files and folders whose delete has not reached Telegram yet. The row stays until the operation is
     * sent (SPEC §5.3), which can take a while on a slow network; this lets the browser say so.
     */
    val deleting: StateFlow<Set<Id>> = deletingFlow.asStateFlow()

    private suspend fun <T> markedDeleting(id: Id, block: suspend () -> T): T {
        deletingFlow.update { it + id }
        try {
            return block()
        } finally {
            deletingFlow.update { it - id }
        }
    }

    /** Messages read so far while catching up, or null when caught up. */
    val catchingUp: StateFlow<Int?> = catchUpFlow.asStateFlow()

    fun start() {
        OpenFiles.clear(context)
        WorkFiles.clear(context)
        scope.launch {
            while (true) {
                delay(60_000)
                sweepOpenFiles()
            }
        }
        scope.launch {
            vault.state.collect { state ->
                if (state is VaultManager.State.Unlocked) {
                    val unlocked = state.vault
                    open(VaultKeys(sodium, unlocked.keys.metaKey, unlocked.keys.indexKey, unlocked.keys.wrapKey, unlocked.localKey))
                } else {
                    close()
                }
            }
        }
    }

    private fun open(keys: VaultKeys) {
        close()
        keysFlow.value = keys
        session = scope.launch {
            sweepShareCopies(keys)
            val storage = telegram.readyStorage(Long.MAX_VALUE) ?: return@launch
            val indexer = Indexer(storage, index, keys)
            val snapshotter = Snapshotter(storage, index, indexer, keys, WorkFiles.snapshot(context), System::currentTimeMillis)
            val current = Live(indexer, snapshotter, Maintenance(storage, index, indexer, snapshotter, keys, System::currentTimeMillis))
            live = current
            launch {
                catchUpFlow.value = 0
                indexer.run(
                    onCatchUpProgress = { catchUpFlow.value = it },
                    onCaughtUp = {
                        catchUpFlow.value = null
                        // SPEC §5.8: weekly, quietly; a failure waits for the next unlock.
                        launch { if (store.weeklyCheck && current.maintenance.isDue()) runCatchingStorage { maintain(current) } }
                    },
                    // A message id only: rare enough to want to know when it happens.
                    onLate = { Log.w(SYNC_TAG, "message ${it.value} came late; reading the history again") },
                )
            }
            // SPEC §4.4: a while after the last change, so a burst of changes makes one snapshot.
            launch { indexer.changes.debounce(SNAPSHOT_DELAY_MS).collect { snapshot() } }
            pendingWork.trySend(Unit)
            for (signal in pendingWork) resume(keys)
        }
    }

    private fun close() {
        session?.cancel()
        session = null
        live = null
        keysFlow.value = null
        catchUpFlow.value = null
        OpenFiles.clear(context)
        // The cache is encrypted, but shows what was opened lately; emptied on lock only when asked for.
        if (cache.prefs.value.clearOnLock) scope.launch { telegram.readyStorage()?.let { cache.clear(it) } }
    }

    /**
     * Prepares queued uploads and gives every pending transfer its keys, then (re)starts the workers.
     * Runs after unlock and after anything is added.
     */
    private suspend fun resume(keys: VaultKeys) {
        for (upload in index.uploads()) {
            if (registry.failures.value.containsKey(upload.uploadId)) continue
            val request = keys.local.openUpload(upload.uploadId, upload.sealedRequest) ?: continue
            val fileId = upload.fileId ?: try {
                val prepared = UploadPreparer(keys, index, System::currentTimeMillis)
                    .prepare(upload, request, ContentUriSource(context, request.source))
                registry.put(prepared.upload.fileId!!, TransferRegistry.Entry(prepared.keys, source = request.source, displayName = request.name))
                prepared.upload.fileId!!
            } catch (_: IOException) {
                registry.fail(upload.uploadId, TransferRegistry.Failure.SOURCE_GONE)
                continue
            } catch (_: SecurityException) {
                registry.fail(upload.uploadId, TransferRegistry.Failure.SOURCE_GONE)
                continue
            }
            if (!registry.has(fileId)) {
                val file = index.file(fileId) ?: continue
                val transferKeys = keys.transferKeys(fileId, file.sealedMeta) ?: continue
                registry.put(fileId, TransferRegistry.Entry(transferKeys, source = request.source, displayName = request.name))
            }
            enqueue(TransferWorker.upload(upload.uploadId))
        }
        for (download in index.downloads()) {
            if (registry.failures.value.containsKey(download.downloadId)) continue
            if (!registry.has(download.fileId)) {
                val file = index.file(download.fileId) ?: continue
                val meta = keys.local.openFileMeta(download.fileId, file.sealedMeta) ?: continue
                val transferKeys = keys.transferKeys(download.fileId, file.sealedMeta) ?: continue
                val sealedTarget = download.sealedTarget
                val target = sealedTarget?.let { keys.local.openTarget(download.downloadId, it) ?: continue }
                val open = if (target == null) TransferRegistry.OpenTarget(meta.name, meta.mime) else null
                registry.put(download.fileId, TransferRegistry.Entry(transferKeys, target = target, open = open, displayName = meta.name))
            }
            enqueue(TransferWorker.download(download.downloadId))
        }
        // These need no key; this only starts again what Android stopped while Coffer was away.
        for (item in cache.queue.items.value) {
            if (registry.failures.value.containsKey(item.id)) continue
            enqueue(TransferWorker.prefetch(item.id))
        }
    }

    /** Sends a snapshot if anything changed since the newest one; a failure waits for the next change. */
    private suspend fun snapshot() {
        val current = live ?: return
        snapshotLock.withLock {
            try {
                current.snapshotter.snapshotIfNeeded()
            } catch (_: StorageException) {
            } catch (_: IOException) {
            }
        }
    }

    /** Copies opened in other apps go after [OpenFiles.LIFETIME_MILLIS]; also called on return to the app. */
    fun sweepOpenFiles() = OpenFiles.sweep(OpenFiles.dir(context), System.currentTimeMillis())

    /** One maintenance run at a time, and never alongside a snapshot: both may send one. */
    private suspend fun maintain(current: Live, onProgress: (Int) -> Unit = {}): Maintenance.Report =
        snapshotLock.withLock { current.maintenance.run(onProgress) }

    /** The integrity check and compaction on demand (SPEC §5.8), with progress like a catch-up. */
    suspend fun checkIntegrity(): Maintenance.Report? {
        val current = live ?: return null
        catchUpFlow.value = 0
        try {
            return maintain(current) { catchUpFlow.value = it }
        } finally {
            catchUpFlow.value = null
        }
    }

    private suspend fun runCatchingStorage(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: StorageException) {
        } catch (_: IOException) {
        }
    }

    /** The app is on screen again: transfers Android stopped while it was away start over. */
    fun onForeground() {
        pendingWork.trySend(Unit)
    }

    /** The app went to the background: snapshot now rather than after the delay (SPEC §4.4). */
    fun onBackground() {
        // A delete waiting for its undo goes out now: in the background the vault may lock and take the keys.
        scope.launch {
            deferred.flush()
            snapshot()
        }
    }

    private val deleteFailuresFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A delete held back for its undo that then did not reach Telegram; the items are back in sight. */
    val deleteFailures: SharedFlow<Unit> = deleteFailuresFlow.asSharedFlow()

    private val deferred = DeferredDeletes(
        scope,
        UNDO_MILLIS,
        send = { deleteMany(it.folders, it.files) },
        onFailure = { deleteFailuresFlow.tryEmit(Unit) },
    )

    /** Deleted on screen and waiting for the chance to undo to pass; the browser leaves them out. */
    val hidden: StateFlow<Set<Id>> = deferred.hidden

    /** A delete that can be taken back for [UNDO_MILLIS]; see [DeferredDeletes]. */
    suspend fun deleteLater(folderIds: List<Id>, fileIds: List<Id>) = deferred.schedule(DeferredDeletes.Batch(folderIds, fileIds))

    suspend fun undoDelete(): Boolean = deferred.undo()

    /** Before the vault locks: a delete waiting for its undo needs the keys to go out. */
    suspend fun flushDeletes() = deferred.flush()

    /** Reads the whole of Saved Messages again (SPEC §5.5), with progress like a catch-up. */
    suspend fun rescan() {
        val current = live ?: return
        catchUpFlow.value = 0
        try {
            current.indexer.rescan { catchUpFlow.value = it }
        } finally {
            catchUpFlow.value = null
        }
    }

    /** Debug: forgets the index and catches up the way a new device would. Null while locked. */
    suspend fun reloadAsNewDevice(): Indexer.CatchUpReport? {
        val current = live ?: return null
        catchUpFlow.value = 0
        try {
            return current.indexer.reload { catchUpFlow.value = it }
        } finally {
            catchUpFlow.value = null
        }
    }

    private fun enqueue(request: Pair<String, OneTimeWorkRequest>) {
        WorkManager.getInstance(context).enqueueUniqueWork(request.first, ExistingWorkPolicy.KEEP, request.second)
    }

    class UploadRequest(val uri: Uri, val displayName: String, val mime: String?, val folderId: Id?)

    suspend fun upload(requests: List<UploadRequest>) {
        val keys = keysFlow.value ?: return
        for (request in requests) {
            val id = UUID.randomUUID().toString()
            val sealed = keys.local.sealUpload(id, LocalUpload(request.uri.toString(), request.displayName, request.mime, request.folderId))
            index.putUpload(PendingUpload(id, sealed, null, emptyList(), null))
        }
        pendingWork.trySend(Unit)
    }

    /** Queues the files the inbox holds into [folderId]; their copies are deleted once uploaded. */
    suspend fun addShared(folderId: Id?) {
        val items = inbox.take()
        upload(items.map { UploadRequest(Uri.fromFile(it.file), it.name, it.mime, folderId) })
    }

    /**
     * Copies no pending upload and no open share screen needs: left by a crash or by an upload
     * cancelled while locked. Their sources are sealed, so this needs the open vault.
     */
    private suspend fun sweepShareCopies(keys: VaultKeys) {
        if (inbox.state.value is ShareInbox.State.Copying) return
        val queued = index.uploads().mapNotNullTo(HashSet()) { upload ->
            keys.local.openUpload(upload.uploadId, upload.sealedRequest)?.source
                ?.takeIf { ShareInbox.isCopy(context, it) }
                ?.let { it.toUri().path }
                ?.let(::File)
        }
        ShareInbox.sweep(ShareInbox.dir(context), queued + inbox.held())
    }

    /** Decrypts into [target] (a document the user picked), or for another app when [target] is null. */
    suspend fun download(fileId: Id, target: Uri?) {
        val keys = keysFlow.value ?: return
        val id = UUID.randomUUID().toString()
        index.putDownload(PendingDownload(id, fileId, target?.let { keys.local.sealTarget(id, it.toString()) }))
        pendingWork.trySend(Unit)
    }

    /** What caching [fileIds] would take, for the user to agree to first; null while locked. */
    // usableSpace, as for uploads: the room other apps' caches would free comes only through allocateBytes,
    // which TDLib does not call as it writes.
    @SuppressLint("UsableSpace")
    suspend fun cacheOffer(fileIds: List<Id>): CacheOffer? {
        if (keysFlow.value == null) return null
        val files = fileIds.mapNotNull { index.file(it) }
        return CacheOffer.of(files, cache.blobs.held.value, context.noBackupFilesDir.usableSpace, cache.prefs.value)
    }

    /**
     * Brings [fileIds] onto the phone whole, into the file cache (see [PrefetchQueue]). Needs no key, so
     * it goes on after the vault locks, like any transfer.
     */
    fun cacheFiles(fileIds: List<Id>) {
        for (item in cache.queue.add(fileIds)) enqueue(TransferWorker.prefetch(item.id))
    }

    suspend fun delete(fileId: Id) {
        val keys = keysFlow.value ?: return
        markedDeleting(fileId) {
            val storage = telegram.readyStorage() ?: return@markedDeleting
            FileDeleter(storage, index).delete(keys, fileId, System.currentTimeMillis())
        }
    }

    /** Runs [block] with the operation log of the open vault; does nothing while locked or signed out. */
    private suspend fun <T> withOps(block: suspend (VaultOps, VaultKeys, StorageBackend) -> T): T? {
        val keys = keysFlow.value ?: return null
        val storage = telegram.readyStorage() ?: return null
        return block(VaultOps(storage, keys, System::currentTimeMillis), keys, storage)
    }

    suspend fun createFolder(name: String, parentId: Id?) = withOps { ops, _, _ -> ops.createFolder(name, parentId) }

    suspend fun renameFolder(folderId: Id, name: String) = withOps { ops, _, _ -> ops.renameFolder(folderId, name) }

    suspend fun moveFolder(folderId: Id, parentId: Id?) =
        withOps { ops, keys, _ -> ops.moveFolder(VaultTree.of(index, keys), folderId, parentId) }

    suspend fun renameFile(fileId: Id, name: String) = withOps { ops, _, _ -> ops.renameFile(fileId, name) }

    suspend fun moveFile(fileId: Id, folderId: Id?) = withOps { ops, _, _ -> ops.moveFile(fileId, folderId) }

    /** The folder and everything in it; uploads into it are cancelled, which takes back what they sent. */
    suspend fun deleteFolder(folderId: Id) = markedDeleting(folderId) {
        withOps { ops, keys, storage ->
            ops.deleteFolder(VaultTree.of(index, keys), folderId) { fileId ->
                val upload = index.uploads().find { it.fileId == fileId }
                if (upload != null) cancelUpload(upload.uploadId) else FileDeleter(storage, index).delete(keys, fileId, System.currentTimeMillis())
            }
        }
    }

    /**
     * Deletes all of them, folders first: a file inside one goes with it. Every row says Deleting from
     * the start, and each one disappears as its operation goes out.
     */
    suspend fun deleteMany(folderIds: List<Id>, fileIds: List<Id>) {
        val all = folderIds + fileIds
        deletingFlow.update { it + all }
        try {
            eachWaitingOutFloods(folderIds) { deleteFolder(it) }
            eachWaitingOutFloods(fileIds) { if (index.file(it) != null) delete(it) }
        } finally {
            deletingFlow.update { it - all.toSet() }
        }
    }

    suspend fun moveMany(folderIds: List<Id>, fileIds: List<Id>, destination: Id?) {
        eachWaitingOutFloods(folderIds) { moveFolder(it, destination) }
        eachWaitingOutFloods(fileIds) { moveFile(it, destination) }
    }

    /**
     * Saves decrypted copies into [tree], a folder the user picked: each file, and each folder with
     * everything in it and its shape (see [saveOut]). The downloads may outlive this process (SPEC §6),
     * so the grant on the folder is kept until nothing is left to write (see [releaseFolderGrants]).
     */
    suspend fun exportTo(tree: Uri, folderIds: List<Id>, fileIds: List<Id>): SaveCount {
        val keys = keysFlow.value ?: return SaveCount(failed = fileIds.size)
        val resolver = context.contentResolver
        runCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val vaultTree = VaultTree.of(index, keys)
        suspend fun ready(fileId: Id) = index.file(fileId)?.status == FileStatus.READY
        suspend fun save(fileId: Id, into: Uri): Boolean {
            val meta = vaultTree.files[fileId] ?: return false
            // A name taken already gets a number from the folder's provider, as any app's would.
            val target = runCatching { DocumentsContract.createDocument(resolver, into, meta.mime, meta.name) }.getOrNull() ?: return false
            download(fileId, target)
            return true
        }
        var count = SaveCount()
        for (folderId in folderIds) {
            count += saveOut(
                vaultTree, folderId, parent, ::ready,
                createFolder = { name, into -> runCatching { DocumentsContract.createDocument(resolver, into, DocumentsContract.Document.MIME_TYPE_DIR, name) }.getOrNull() },
                saveFile = ::save,
            )
        }
        for (fileId in fileIds) {
            count += when {
                !ready(fileId) -> SaveCount(notReady = 1)
                save(fileId, parent) -> SaveCount(started = 1)
                else -> SaveCount(failed = 1)
            }
        }
        return count
    }

    /**
     * Brings [tree], a folder the user picked on the phone, into the vault under [parentId] with its
     * shape (see [bringIn]), and queues its files. The uploads read them later, maybe after a restart,
     * so the grant on the folder is kept until nothing is left to read (see [releaseFolderGrants]).
     * Returns how many files were queued.
     */
    suspend fun importFolder(tree: Uri, parentId: Id?): Int {
        val resolver = context.contentResolver
        runCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val folder = withContext(Dispatchers.IO) { resolver.readPhoneFolder(tree) }
        val files = withOps { ops, _, _ ->
            bringIn(folder, parentId) { name, parent ->
                val fitted = FileNames.fitForImport(name).takeIf(FileNames::isValid) ?: FOLDER_FALLBACK_NAME
                waitingOutFloods { ops.createFolder(fitted, parent) }
            }
        } ?: return 0
        upload(files.map { (file, folderId) -> UploadRequest(file.uri.toUri(), file.name, file.mime, folderId) })
        return files.size
    }

    /** A vault file open for reading in place, for the viewers; [close] wipes and lets go of it all. */
    class OpenedFile(val name: String, val mime: String, val reader: VaultFileReader, private val onClose: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        // Once only: a viewer may close early to cut off a read waiting on Telegram, then again with the rest.
        override fun close() {
            if (closed.compareAndSet(false, true)) onClose()
        }
    }

    /**
     * Opens [fileId] for reading in place (see [VaultFileReader]); null while locked, offline for too
     * long, or when the file is not whole. Closing wipes the file's key and the chunks held, and drops
     * the encrypted pieces TDLib downloaded for it.
     */
    suspend fun openFile(fileId: Id): OpenedFile? {
        val keys = keysFlow.value ?: return null
        val file = index.file(fileId)?.takeIf { it.status == FileStatus.READY } ?: return null
        val meta = keys.local.openFileMeta(fileId, file.sealedMeta) ?: return null
        val storage = telegram.readyStorage() ?: return null
        val transfer = keys.transferKeys(fileId, file.sealedMeta) ?: return null
        val reader = keys.reader(storage, file, transfer)
        leases.open(fileId)
        return OpenedFile(meta.name, meta.mime, reader) {
            reader.close()
            transfer.close()
            leases.close(fileId, reader.partsUsed()) { cache.keep(fileId, it, storage) }
        }
    }

    /**
     * A file the audio player holds for its queue, with a key of its own: it can be read after the vault
     * locks, as a running transfer can (SPEC §6). [close] wipes that key; readers opened from it must be
     * closed before.
     */
    class HeldFile(val fileId: Id, val name: String, val mime: String, private val keys: TransferKeys, private val opener: (TransferKeys) -> OpenedFile) : AutoCloseable {
        fun open(): OpenedFile = opener(keys)

        override fun close() = keys.close()
    }

    /**
     * Holds [fileIds] for the player, in order, leaving out any not whole or gone; null while locked or
     * offline for too long. Only the vault's codec is kept, never its keys.
     */
    suspend fun holdFiles(fileIds: List<Id>): List<HeldFile>? {
        val keys = keysFlow.value ?: return null
        val storage = telegram.readyStorage() ?: return null
        val sodium = keys.sodium
        return fileIds.mapNotNull { fileId ->
            val file = index.file(fileId)?.takeIf { it.status == FileStatus.READY } ?: return@mapNotNull null
            val meta = keys.local.openFileMeta(fileId, file.sealedMeta) ?: return@mapNotNull null
            val transfer = keys.transferKeys(fileId, file.sealedMeta) ?: return@mapNotNull null
            HeldFile(fileId, meta.name, meta.mime, transfer) { held ->
                val reader = VaultFileReader(sodium, storage, file, held)
                leases.open(fileId)
                OpenedFile(meta.name, meta.mime, reader) {
                    reader.close()
                    leases.close(fileId, reader.partsUsed()) { cache.keep(fileId, it, storage) }
                }
            }
        }
    }

    suspend fun cancelUpload(uploadId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(TransferWorker.uploadName(uploadId))
        TransferWorker.dismiss(context, uploadId)
        val upload = index.uploads().find { it.uploadId == uploadId } ?: return
        keysFlow.value?.local?.openUpload(uploadId, upload.sealedRequest)?.let { ShareInbox.deleteCopy(context, it.source) }
        val storage = telegram.readyStorage() ?: return
        UploadRunner(sodium, storage, index, WorkFiles.upload(context)).cancel(upload)
        upload.fileId?.let(registry::remove)
        registry.finished(uploadId)
        registry.clearFailure(uploadId)
    }

    suspend fun cancelDownload(downloadId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(TransferWorker.downloadName(downloadId))
        TransferWorker.dismiss(context, downloadId)
        index.removeDownload(downloadId)
        registry.finished(downloadId)
        registry.clearFailure(downloadId)
    }

    /**
     * Stops caching a file. Parts that came whole stay in the cache like any other; one cut off halfway
     * is dropped, as nothing would account for it, unless a viewer is reading the file right now.
     */
    suspend fun cancelPrefetch(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(TransferWorker.prefetchName(id))
        TransferWorker.dismiss(context, id)
        val item = cache.queue.find(id)
        cache.queue.remove(id)
        registry.finished(id)
        registry.clearFailure(id)
        if (item == null || leases.isOpen(item.fileId)) return
        val file = index.file(item.fileId) ?: return
        val storage = telegram.readyStorage() ?: return
        val held = cache.blobs.held.value
        for ((index, part) in file.parts) {
            val bytes = runCatching { storage.localBytes(part) }.getOrDefault(0L)
            when {
                // Come whole as the cancel went out: the cache has it, or is about to.
                bytes >= ContentLayout.encryptedPartSize(file.plainSize, index) -> cache.keep(item.fileId, part, storage)
                bytes > 0 && part !in held -> runCatching { storage.releaseBlob(part) }
            }
        }
    }

    /** A failed transfer, tried again. */
    fun retry(id: String) {
        registry.clearFailure(id)
        if (cache.queue.find(id) != null) enqueue(TransferWorker.prefetch(id)) else pendingWork.trySend(Unit)
    }

    /** Signing out (SPEC §9): stop everything and forget the index. */
    suspend fun forget() {
        close()
        WorkManager.getInstance(context).cancelAllWorkByTag(TransferWorker.TAG)
        registry.clear()
        index.clear()
    }
}

private const val SNAPSHOT_DELAY_MS = 90_000L

internal fun transferConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

internal fun OneTimeWorkRequest.Builder.withTransferDefaults(kind: String, id: String): OneTimeWorkRequest.Builder = this
    .setConstraints(transferConstraints())
    .addTag(TransferWorker.TAG)
    .setInputData(workDataOf(TransferWorker.KEY_KIND to kind, TransferWorker.KEY_ID to id))

private const val SYNC_TAG = "CofferSync"

/**
 * Lets go of the phone folders picked for saving copies or bringing in a folder (see
 * [VaultContent.exportTo], [VaultContent.importFolder]) once no transfer is left that reads or writes
 * through them. Only folder grants: a file picked by itself keeps the grant of its own.
 */
suspend fun releaseFolderGrants(context: Context, index: VaultIndex) {
    if (index.uploads().isNotEmpty() || index.downloads().any { it.sealedTarget != null }) return
    val resolver = context.contentResolver
    for (grant in resolver.persistedUriPermissions) {
        if (DocumentsContract.isTreeUri(grant.uri)) {
            runCatching { resolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }
}

private const val FOLDER_FALLBACK_NAME = "Folder"

/** How long a delete can be taken back before it goes to Telegram, where it is final. */
const val UNDO_MILLIS = 6_000L
