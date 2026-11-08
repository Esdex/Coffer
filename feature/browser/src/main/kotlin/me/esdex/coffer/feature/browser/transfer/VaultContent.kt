package me.esdex.coffer.feature.browser.transfer

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.LocalUpload
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.index.PendingDownload
import me.esdex.coffer.core.index.PendingUpload
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.FileDeleter
import me.esdex.coffer.core.sync.Indexer
import me.esdex.coffer.core.sync.Snapshotter
import me.esdex.coffer.core.sync.UploadPreparer
import me.esdex.coffer.core.sync.UploadRunner
import me.esdex.coffer.core.sync.VaultKeys
import me.esdex.coffer.core.sync.VaultOps
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.core.sync.transferKeys
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.VaultManager
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Saved Messages once TDLib is signed in, or null if it does not get there in time. */
suspend fun Telegram.readyStorage(timeoutMillis: Long = 60_000): StorageBackend? {
    start()
    val state = withTimeoutOrNull(timeoutMillis) { state.first { it == AuthState.Ready } } ?: return null
    return if (state == AuthState.Ready) storage() else null
}

/** Decrypted copies for other apps (SPEC §6): cleared on lock, at start and some minutes after opening. */
object OpenFiles {
    fun dir(context: Context) = File(context.cacheDir, "open")
    fun clear(context: Context) {
        dir(context).deleteRecursively()
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: Job? = null

    /** The open session's indexer and snapshotter; null while locked or not yet signed in. */
    private class Live(val indexer: Indexer, val snapshotter: Snapshotter)

    @Volatile
    private var live: Live? = null
    private val snapshotLock = Mutex()
    private val pendingWork = Channel<Unit>(Channel.CONFLATED)

    private val keysFlow = MutableStateFlow<VaultKeys?>(null)

    /** The open vault's keys, for showing names; null while locked. */
    val keys: StateFlow<VaultKeys?> = keysFlow.asStateFlow()

    private val catchUpFlow = MutableStateFlow<Int?>(null)

    /** Messages read so far while catching up, or null when caught up. */
    val catchingUp: StateFlow<Int?> = catchUpFlow.asStateFlow()

    fun start() {
        OpenFiles.clear(context)
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
            val storage = telegram.readyStorage(Long.MAX_VALUE) ?: return@launch
            val indexer = Indexer(storage, index, keys)
            live = Live(indexer, Snapshotter(storage, index, indexer, keys, File(context.cacheDir, "snapshot"), System::currentTimeMillis))
            launch {
                catchUpFlow.value = 0
                indexer.run(
                    onCatchUpProgress = { catchUpFlow.value = it },
                    onCaughtUp = { catchUpFlow.value = null },
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

    /** The app went to the background: snapshot now rather than after the delay (SPEC §4.4). */
    fun onBackground() {
        scope.launch { snapshot() }
    }

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

    /** Decrypts into [target] (a document the user picked), or for another app when [target] is null. */
    suspend fun download(fileId: Id, target: Uri?) {
        val keys = keysFlow.value ?: return
        val id = UUID.randomUUID().toString()
        index.putDownload(PendingDownload(id, fileId, target?.let { keys.local.sealTarget(id, it.toString()) }))
        pendingWork.trySend(Unit)
    }

    suspend fun delete(fileId: Id) {
        val keys = keysFlow.value ?: return
        val storage = telegram.readyStorage() ?: return
        FileDeleter(storage, index).delete(keys, fileId, System.currentTimeMillis())
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
    suspend fun deleteFolder(folderId: Id) = withOps { ops, keys, storage ->
        ops.deleteFolder(VaultTree.of(index, keys), folderId) { fileId ->
            val upload = index.uploads().find { it.fileId == fileId }
            if (upload != null) cancelUpload(upload.uploadId) else FileDeleter(storage, index).delete(keys, fileId, System.currentTimeMillis())
        }
    }

    suspend fun cancelUpload(uploadId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(TransferWorker.uploadName(uploadId))
        val upload = index.uploads().find { it.uploadId == uploadId } ?: return
        val storage = telegram.readyStorage() ?: return
        UploadRunner(sodium, storage, index, File(context.cacheDir, "upload")).cancel(upload)
        upload.fileId?.let(registry::remove)
        registry.finished(uploadId)
        registry.clearFailure(uploadId)
    }

    suspend fun cancelDownload(downloadId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(TransferWorker.downloadName(downloadId))
        index.removeDownload(downloadId)
        registry.finished(downloadId)
        registry.clearFailure(downloadId)
    }

    /** A failed transfer, tried again. */
    fun retry(id: String) {
        registry.clearFailure(id)
        pendingWork.trySend(Unit)
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
