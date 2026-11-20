package me.esdex.coffer.feature.browser.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.esdex.coffer.core.crypto.ContentHasher
import me.esdex.coffer.core.crypto.ContentLayout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.LocalUpload
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.PendingDownload
import me.esdex.coffer.core.index.PendingUpload
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.ConflictDecider
import me.esdex.coffer.core.sync.Incoming
import me.esdex.coffer.core.sync.Indexer
import me.esdex.coffer.core.sync.Maintenance
import me.esdex.coffer.core.sync.NameConflicts
import me.esdex.coffer.core.sync.Placed
import me.esdex.coffer.core.sync.Snapshotter
import me.esdex.coffer.core.sync.UploadPreparer
import me.esdex.coffer.core.sync.UploadRunner
import me.esdex.coffer.core.sync.TransferKeys
import me.esdex.coffer.core.sync.VaultFileReader
import me.esdex.coffer.core.sync.VaultKeys
import me.esdex.coffer.core.sync.OpQueue
import me.esdex.coffer.core.sync.VaultOps
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.core.sync.reader
import me.esdex.coffer.core.sync.transferKeys
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.UnlockedVault
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

    /**
     * A vault file's name as a name on disk, inside its copy's own folder. The name comes from whatever app
     * shared the file in, or from a rename, so it may hold "/" or be "..": only its last part stays, with no
     * control characters, and never "." or ".." (audit 1, L3).
     */
    fun safeName(name: String): String {
        val last = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = last.filterNot { it.isISOControl() }.trim()
        return clean.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "file"
    }

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /**
     * Deletes every copy in [dir] last written more than [maxAgeMillis] before [now]. Each download has its
     * own folder, aged by the newest write inside it: the folder's own time is when the download began, and
     * a long one would lose its copy halfway (audit 2, DATA2-10). True if any copy is left.
     */
    fun sweep(dir: File, now: Long, maxAgeMillis: Long = LIFETIME_MILLIS): Boolean {
        var left = false
        for (copy in dir.listFiles().orEmpty()) {
            val written = copy.walk().maxOf { it.lastModified() }
            if (now - written >= maxAgeMillis) copy.deleteRecursively() else left = true
        }
        return left
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
     * Files and folders whose delete has not reached Telegram yet: being queued, or in the queue of
     * operations, which outlives the app. The row stays until the operation is sent (SPEC §5.3), which
     * can take a while; this lets the browser say so.
     */
    val deleting: StateFlow<Set<Id>> = combine(deletingFlow, index.observeOps()) { queuing, queued ->
        queuing + queued.flatMap { it.deletedFiles + it.deletedFolders }
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * Deletes held for files an upload replaced go into packs here, the one place with the key: once no
     * such upload is left but failed ones (see [OpQueue.sealHeld]), at once or after an unlock. Left to
     * the transfer queue, an upload that ended while the vault was locked left them held for good.
     */
    private fun CoroutineScope.sealHeldDeletes(keys: VaultKeys) = launch {
        combine(index.observeOps(), index.observeUploads(), registry.failures) { _, _, failed -> failed.keys }.collect { failed ->
            if (OpQueue(index).sealHeld(keys, failed, System::currentTimeMillis)) TransferWorker.start(context, registry)
        }
    }

    /** Messages read so far while catching up, or null when caught up. */
    val catchingUp: StateFlow<Int?> = catchUpFlow.asStateFlow()

    fun start() {
        OpenFiles.clear(context)
        WorkFiles.clear(context)
        scope.launch {
            // Copies being edited too, locked or not: the sweep after a save needs the vault open (audit 3, DATA3-1).
            if (EditSessionsSweeper.sweep(context, index)) EditSessionsSweeper.schedule(context)
            while (true) {
                delay(60_000)
                sweepOpenFiles()
                EditSessionsSweeper.sweep(context, index)
            }
        }
        // The setting, which signing out clears with the rest: Coffer in the system file picker, or not.
        scope.launch {
            store.changes.map { store.showInFilePicker }.distinctUntilChanged().collect { VaultDocumentsProvider.show(context, it) }
        }
        // The picker shows what the vault holds now, and nothing once it locks.
        scope.launch {
            combine(keysFlow, index.observeFiles(), index.observeFolders(), deleting, EditSessions.changes.onStart { emit(Unit) }) { _, _, _, _, _ -> }
                .debounce(PICKER_REFRESH_MS)
                .collect { VaultDocumentsProvider.changed(context) }
        }
        scope.launch {
            vault.state.collect { state ->
                if (state is VaultManager.State.Unlocked) {
                    open(keysOf(state.vault))
                } else {
                    close()
                }
            }
        }
    }

    private fun keysOf(unlocked: UnlockedVault) = VaultKeys(sodium, unlocked.keys.metaKey, unlocked.keys.indexKey, unlocked.keys.wrapKey, unlocked.localKey)

    private fun open(keys: VaultKeys) {
        close()
        keysFlow.value = keys
        session = scope.launch {
            sweepShareCopies(keys)
            sealHeldDeletes(keys)
            saveEdits(keys)
            val storage = telegram.readyStorage(Long.MAX_VALUE) ?: return@launch
            val indexer = Indexer(storage, index, keys)
            val snapshotter = Snapshotter(storage, index, indexer, keys, WorkFiles.snapshot(context), System::currentTimeMillis)
            val current = Live(indexer, snapshotter, Maintenance(storage, index, indexer, snapshotter, keys, vault::headerIds, System::currentTimeMillis))
            live = current
            launch {
                catchUpFlow.value = 0
                indexer.follow(
                    onCatchUpProgress = { catchUpFlow.value = it },
                    onCaughtUp = {
                        catchUpFlow.value = null
                        // SPEC §5.8: weekly, quietly; a failure waits for the next unlock.
                        launch { if (store.weeklyCheck && current.maintenance.isDue()) runCatchingStorage { maintain(current) } }
                    },
                    // A message id only: rare enough to want to know when it happens.
                    onLate = { Log.w(SYNC_TAG, "message ${it.value} came late; reading the history again") },
                    // Its kind only: a message could carry more than the project's rule on logs allows.
                    onError = { Log.w(SYNC_TAG, "catching up failed (${it.javaClass.simpleName}); trying again") },
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
     * Prepares queued uploads and gives every pending transfer its keys, then wakes the queue, or starts it
     * (see [TransferWorker]). Runs after unlock and after anything is added.
     */
    private suspend fun resume(keys: VaultKeys) {
        for (upload in index.uploads()) {
            if (registry.failures.value.containsKey(upload.uploadId)) continue
            val request = keys.local.openUpload(upload.uploadId, upload.sealedRequest) ?: continue
            val fileId = upload.fileId ?: try {
                val prepared = UploadPreparer(keys, index, store.hideSizes, System::currentTimeMillis)
                    .prepare(upload, request, inbox.source(request.source))
                registry.put(prepared.upload.fileId!!, TransferRegistry.Entry(prepared.keys, source = request.source, displayName = request.name))
                EditSessions.prepared(EditSessions.dir(context), upload.uploadId, prepared.upload.fileId!!, forward = store.showInFilePicker)
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
        }
        for (download in index.downloads()) {
            if (registry.failures.value.containsKey(download.downloadId)) continue
            if (!registry.has(download.fileId)) {
                val file = index.file(download.fileId) ?: continue
                val meta = keys.local.openFileMeta(download.fileId, file.sealedMeta) ?: continue
                val transferKeys = keys.transferKeys(download.fileId, file.sealedMeta) ?: continue
                val sealedTarget = download.sealedTarget
                val sealed = sealedTarget?.let { keys.local.openTarget(download.downloadId, it) ?: continue }
                val edit = sealed?.takeIf { it.startsWith(EDIT_TARGET) }?.removePrefix(EDIT_TARGET)
                val target = sealed?.takeIf { edit == null }
                val open = if (target == null) TransferRegistry.OpenTarget(meta.name, meta.mime, edit) else null
                registry.put(download.fileId, TransferRegistry.Entry(transferKeys, target = target, open = open, displayName = meta.name))
            }
        }
        // With the keys in place the queue can run all of it; files being cached need none, and run anyway.
        TransferWorker.dismissPaused(context)
        TransferWorker.start(context, registry)
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
        send = { deleteMany(it.folders, it.files, expand = false) },
        onFailure = { deleteFailuresFlow.tryEmit(Unit) },
    )

    /** Deleted on screen and waiting for the chance to undo to pass; the browser leaves them out. */
    val hidden: StateFlow<Set<Id>> = deferred.hidden

    /**
     * A delete that can be taken back for [UNDO_MILLIS]; see [DeferredDeletes]. What the folders hold is
     * taken now: something moved or merged into one meanwhile stays (audit 3, Q3-1).
     */
    suspend fun deleteLater(folderIds: List<Id>, fileIds: List<Id>) {
        val keys = keysFlow.value ?: return
        val (folders, files) = VaultOps.contents(VaultTree.of(index, keys), folderIds, fileIds)
        deferred.schedule(DeferredDeletes.Batch(folders, files))
    }

    /** The vault as names are settled against: without what is being deleted or waits for its Undo (audit 3, Q3-1). */
    private suspend fun settling(keys: VaultKeys): VaultTree = VaultTree.of(index, keys).without(hidden.value + deleting.value)

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

    data class UploadRequest(val uri: Uri, val displayName: String, val mime: String?, val folderId: Id?, val replaces: Id? = null) {
        override fun toString(): String = "UploadRequest(folderId=$folderId, replaces=$replaces, name=***)"
    }

    /**
     * Queues [requests], their names settled first against what their folders hold (see [NameConflicts]):
     * asked about through [decider], which may cancel it all with [ConflictsCancelled].
     */
    suspend fun upload(requests: List<UploadRequest>, decider: ConflictDecider) {
        val keys = keysFlow.value ?: return
        val conflicts = NameConflicts(settling(keys), decider)
        val placed = requests.groupBy { it.folderId }.flatMap { (folderId, group) ->
            val incoming = group.map { Incoming.File(vaultName(it.displayName, FILE_FALLBACK_NAME), it) }
            conflicts.place(folderId, incoming).map { (it as Placed.File).let { p -> p.item.copy(displayName = p.name, replaces = p.replaces) } }
        }
        queue(placed)
    }

    /** Queues [requests] as they are; returns their upload ids. */
    private suspend fun queue(requests: List<UploadRequest>): List<String> {
        val keys = keysFlow.value ?: return emptyList()
        val ids = requests.map { request ->
            val id = UUID.randomUUID().toString()
            val sealed = keys.local.sealUpload(id, LocalUpload(request.uri.toString(), request.displayName, request.mime, request.folderId))
            index.putUpload(PendingUpload(id, sealed, null, emptyList(), null, replaces = request.replaces))
            id
        }
        pendingWork.trySend(Unit)
        return ids
    }

    /**
     * Decrypts [fileId] for editing in another app (decision 2026-11-19): into a session of its own (see
     * [EditSessions]), handed out read and write once it is there, like a copy to open.
     */
    suspend fun edit(fileId: Id) {
        val keys = keysFlow.value ?: return
        val file = index.file(fileId)?.takeIf { it.status == FileStatus.READY } ?: return
        val meta = keys.local.openFileMeta(fileId, file.sealedMeta) ?: return
        val session = EditSessions.begin(EditSessions.dir(context), meta.name, meta.mime, fileId, meta.folderId, meta.contentHash, System.currentTimeMillis(), ready = false)
        // Swept in time even if the process is gone by then (audit 3, DATA3-1).
        EditSessionsSweeper.schedule(context)
        val id = UUID.randomUUID().toString()
        index.putDownload(PendingDownload(id, fileId, keys.local.sealTarget(id, EDIT_TARGET + session.id)))
        pendingWork.trySend(Unit)
    }

    /**
     * Saves what editors wrote: each copy changed since its last save is uploaded in place of its file
     * (see [EditSessions.plan]). At once while the vault is open, and for those saved while it was locked,
     * on the next unlock: the change needs the key.
     */
    private fun CoroutineScope.saveEdits(keys: VaultKeys) = launch {
        EditSessions.changes.onStart { emit(Unit) }.collect {
            val root = EditSessions.dir(context)
            for (session in EditSessions.all(root).filter { it.dirty && !it.broken }) {
                // One save failing, as on Telegram's pause while the last one is cancelled, stays to be tried
                // again; out of here it took the app down, at each unlock, and its message named the copy
                // (audit 3, DATA3-5).
                try {
                    saveEdit(session, keys)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(SYNC_TAG, "an edit was not saved yet (${e.javaClass.simpleName}); trying again")
                    launch {
                        delay(EDIT_RETRY_MS)
                        EditSessions.nudge()
                    }
                }
            }
            EditSessions.sweep(root, index.uploads().mapTo(HashSet()) { it.uploadId }, System.currentTimeMillis(), android.os.Process.myPid())
        }
    }

    private suspend fun saveEdit(session: EditSession, keys: VaultKeys) {
        val hash = withContext(Dispatchers.IO) { session.copy.inputStream().use { ContentHasher.hash(sodium, it).first } }
        if (hash == session.savedHash) {
            EditSessions.unchanged(session)
            return
        }
        val pending = index.uploads().mapTo(HashSet()) { it.uploadId }
        val whole = session.uploadFile?.takeIf { index.file(it)?.status == FileStatus.READY }
        val save = EditSessions.plan(session, VaultTree.of(index, keys), pending) { it == whole }
        save.cancel?.let { cancel ->
            cancelUpload(cancel)
            // Not cancelled, Telegram not ready: queued beside it, both would go.
            if (index.uploads().any { it.uploadId == cancel }) throw IOException("the last save is still queued")
        }
        val uploadId = queue(listOf(UploadRequest(Uri.fromFile(session.copy), save.name, session.mime, save.folderId, save.replaces))).single()
        EditSessions.queued(session, uploadId, hash, save.original)
    }

    /**
     * Queues the files the inbox holds into [folderId]; their copies are deleted once uploaded. Taken from
     * the inbox only once their names are settled: cancelled, they stay for another try. Returns how many
     * were queued, skipped ones left out.
     */
    suspend fun addShared(folderId: Id?, decider: ConflictDecider): Int {
        val keys = keysFlow.value ?: return 0
        val items = (inbox.state.value as? ShareInbox.State.Ready)?.items.orEmpty()
        val incoming = items.map { Incoming.File(vaultName(it.name, FILE_FALLBACK_NAME), it) }
        val placed = NameConflicts(settling(keys), decider).place(folderId, incoming).map { it as Placed.File }
        inbox.take()
        queue(placed.map { UploadRequest(Uri.fromFile(it.item.file), it.name, it.item.mime, folderId, it.replaces) })
        return placed.size
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
        // A document picked to save into: kept writable past a restart, until no export is left (audit 2, L-n).
        // One inside a picked folder is covered by the folder's grant, and this does nothing.
        if (target != null) runCatching { context.contentResolver.takePersistableUriPermission(target, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
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
        if (cache.queue.add(fileIds).isNotEmpty()) TransferWorker.start(context, registry)
    }

    /** Runs [block] with the operation log of the open vault; does nothing while locked or signed out. */
    /**
     * Creating, renaming and moving go into the queue of operations, which the transfer queue sends at
     * Telegram's pace and shows; the list changes once Telegram has each one (see [OpQueue]).
     */
    private suspend fun <T> withOps(block: suspend (VaultOps, VaultKeys, StorageBackend) -> T): T? {
        val keys = keysFlow.value ?: return null
        val storage = telegram.readyStorage() ?: return null
        val result = block(VaultOps(storage, keys, OpQueue(index), System::currentTimeMillis), keys, storage)
        if (index.ops().isNotEmpty()) TransferWorker.start(context, registry)
        return result
    }

    suspend fun createFolder(name: String, parentId: Id?) = withOps { ops, _, _ -> ops.createFolder(name, parentId) }

    suspend fun renameFolder(folderId: Id, name: String) = withOps { ops, _, _ -> ops.renameFolder(folderId, name) }


    suspend fun renameFile(fileId: Id, name: String) = withOps { ops, _, _ -> ops.renameFile(fileId, name) }


    /**
     * Deletes all of them, folders with everything in them, as one batch through the queue of
     * operations (see [VaultOps.deleteMany]), which outlives the app; uploads of this device's into them
     * are cancelled instead, which takes back what they sent. Every row says Deleting from the start, and
     * each one disappears once Telegram has its delete.
     */
    suspend fun deleteMany(folderIds: List<Id>, fileIds: List<Id>, expand: Boolean = true) {
        val all = folderIds + fileIds
        deletingFlow.update { it + all }
        try {
            withOps { ops, keys, _ ->
                val files = fileIds.filter { index.file(it) != null }
                ops.deleteMany(VaultTree.of(index, keys), folderIds, files, partsOf = { index.parts(it).values }, expand = expand) { fileId ->
                    val upload = index.uploads().find { it.fileId == fileId }
                    upload?.let { cancelUpload(it.uploadId) }
                    upload != null
                }
            }
        } finally {
            deletingFlow.update { it - all.toSet() }
        }
    }

    /**
     * Moves them into [destination], names settled first against what it holds (see [NameConflicts]):
     * asked about through [decider], which may cancel it all. What is there already stays as it is.
     */
    suspend fun moveMany(folderIds: List<Id>, fileIds: List<Id>, destination: Id?, decider: ConflictDecider) =
        withOps { ops, keys, _ ->
            val tree = VaultTree.of(index, keys)
            val (outer, loose) = tree.outermost(folderIds, fileIds)
            val folders = outer.filter { it in tree.folders && tree.parentOf(it) != destination }
            val files = loose.filter { it in tree.files && tree.folderOf(it) != destination }
            require(destination == null || folders.none { tree.isWithin(destination, it) }) { "folder cannot move into itself" }
            val incoming = folders.map { NameConflicts.incoming(tree, it) } + files.map { Incoming.File(tree.files.getValue(it).name, it) }
            val placed = NameConflicts(tree.without(hidden.value + deleting.value), decider, leaving = (folders + files).toSet()).place(destination, incoming)
            ops.movePlaced(tree, placed, destination, partsOf = { index.parts(it).values })
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
     * Names taken where they go are settled first (see [NameConflicts]), asked about through [decider].
     * Returns how many files the phone folder holds, whether queued or skipped.
     */
    suspend fun importFolder(tree: Uri, parentId: Id?, decider: ConflictDecider): Int {
        val resolver = context.contentResolver
        runCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val folder = withContext(Dispatchers.IO) { resolver.readPhoneFolder(tree) }
        // Every new folder as one batch for the queue, their ids known at once; the files follow them into
        // the transfer queue straight away, which sends the folders first. Nothing waits on Telegram here.
        val files = withOps { ops, keys, _ ->
            val placed = NameConflicts(settling(keys), decider).place(parentId, listOf(folder.incoming()))
            ops.createFolders { create -> bringIn(placed, parentId) { name, parent -> create(name, parent) } }
        } ?: return 0
        queue(files.map { UploadRequest(it.file.uri.toUri(), it.name, it.file.mime, it.folderId, it.replaces) })
        return folder.fileCount()
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
     * long, or when the file is not whole. Closing wipes the file's key and the chunks held, and hands
     * the encrypted pieces read to the cache; with [keepInCache] off, as for a preview, those the cache
     * did not have already are dropped instead: a scroll through a thousand photos would otherwise fill
     * it with a piece of each and push out the files put there on purpose.
     */
    suspend fun openFile(fileId: Id, keepInCache: Boolean = true): OpenedFile? {
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
            leases.close(fileId, reader.partsUsed()) { part ->
                when {
                    keepInCache -> cache.keep(fileId, part, storage)
                    part !in cache.blobs.held.value -> storage.releaseBlob(part)
                }
            }
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
        registry.stop(uploadId)
        val upload = index.uploads().find { it.uploadId == uploadId } ?: return
        keysFlow.value?.local?.openUpload(uploadId, upload.sealedRequest)?.let { ShareInbox.deleteCopy(context, it.source) }
        val storage = telegram.readyStorage() ?: return
        UploadRunner(sodium, storage, index, WorkFiles.upload(context)).cancel(upload)
        upload.fileId?.let(registry::remove)
        registry.finished(uploadId)
        registry.clearFailure(uploadId)
    }

    suspend fun cancelDownload(downloadId: String) {
        registry.stop(downloadId)
        index.removeDownload(downloadId)
        registry.finished(downloadId)
        registry.clearFailure(downloadId)
    }

    /**
     * Stops caching a file. Parts that came whole stay in the cache like any other; one cut off halfway
     * is dropped, as nothing would account for it, unless a viewer is reading the file right now.
     */
    suspend fun cancelPrefetch(id: String) {
        registry.stop(id)
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
                bytes >= ContentLayout.encryptedPartSize(file.contentSize, index) -> cache.keep(item.fileId, part, storage)
                bytes > 0 && part !in held -> runCatching { storage.releaseBlob(part) }
            }
        }
    }

    /** A failed transfer, tried again. */
    fun retry(id: String) {
        registry.clearFailure(id)
        if (cache.queue.find(id) != null) TransferWorker.start(context, registry) else pendingWork.trySend(Unit)
    }

    /**
     * After [forget] with the vault still open, a deletion of it cut short (SPEC §5.9): starts syncing
     * again, and the index is read in afresh from what is left.
     */
    fun reopen() {
        (vault.state.value as? VaultManager.State.Unlocked)?.let { open(keysOf(it.vault)) }
    }

    /** Signing out or the vault deleted (SPEC §9, §5.9): stop everything and forget the index. */
    suspend fun forget() {
        close()
        WorkManager.getInstance(context).cancelAllWorkByTag(TransferWorker.TAG)
        registry.clear()
        index.clear()
        // Files shared in and not uploaded are plain copies; none of them is to outlive the vault (audit 1, M10).
        inbox.discard()
        // Nor copies being edited, nor what other apps were given through the picker (audit 3, AND3-7).
        EditSessions.dir(context).deleteRecursively()
        VaultDocumentsProvider.forgetGrants(context)
        ShareInbox.dir(context).deleteRecursively()
    }
}

private const val SNAPSHOT_DELAY_MS = 90_000L

/** A save of an edit that failed is tried again after this, or at the next save or unlock. */
private const val EDIT_RETRY_MS = 60_000L

/** A burst of changes, as a catch-up brings, makes one refresh of the system file picker. */
private const val PICKER_REFRESH_MS = 300L

internal fun transferConstraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

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
        // Folders, and single documents picked to save into: with nothing queued, none is needed any more.
        val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { resolver.releasePersistableUriPermission(grant.uri, flags) }
    }
}


/** A download's target naming an edit session rather than a place to save into (see [VaultContent.edit]). */
private const val EDIT_TARGET = "coffer-edit:"

/** How long a delete can be taken back before it goes to Telegram, where it is final. */
const val UNDO_MILLIS = 6_000L
