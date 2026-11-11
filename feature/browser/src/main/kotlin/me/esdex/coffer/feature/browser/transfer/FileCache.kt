package me.esdex.coffer.feature.browser.transfer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.ui.theme.CachePrefs
import me.esdex.coffer.core.ui.theme.CacheStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The file cache for the whole app: the viewers and the player read through [leases], saving a copy
 * reads whole parts, and all of it ends up in [blobs] rather than being dropped (see [BlobCache]).
 */
@OptIn(FlowPreview::class)
@Singleton
class FileCache @Inject constructor(
    @ApplicationContext context: Context,
    store: CacheStore,
    private val telegram: Telegram,
    private val index: VaultIndex,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val prefs: StateFlow<CachePrefs> = store.prefs

    /** Files open for reading in place; the cache never takes a piece from under one. */
    val leases = ReadLeases(scope)

    val blobs = BlobCache(scope, File(context.noBackupFilesDir, "file-cache"), store.prefs::value, leases::isOpen)

    /** Files the user asked to have on the phone, waiting or being brought down (see [PrefetchQueue]). */
    val queue = PrefetchQueue(File(context.noBackupFilesDir, "cache-queue"))

    init {
        // Logging out deletes TDLib's files and the next account has other message ids: the lists go too.
        scope.launch {
            telegram.state.collect {
                if (it == AuthState.LoggingOut) {
                    queue.clear()
                    blobs.forget()
                }
            }
        }
        // A file deleted takes its pieces out of the ledger (see BlobCache.retain). After a pause, so a
        // rescan or a burst of deletes asks Telegram once rather than at every step.
        scope.launch {
            index.observeFiles()
                .map { files -> files.flatMapTo(HashSet()) { it.parts.values } }
                .distinctUntilChanged()
                .debounce(RETAIN_DELAY_MS)
                .collectLatest { known ->
                    val storage = telegram.readyStorage() ?: return@collectLatest
                    blobs.retain(known) { storage.message(it) == null }
                }
        }
    }

    /**
     * Empties the cache; with nothing open, no copy being saved and nothing being cached, also of the
     * pieces it never knew about, so the phone's figure comes down to what open files still hold.
     */
    suspend fun clear(storage: StorageBackend) = blobs.clear(
        storage,
        idle = { !leases.anyOpen() && index.downloads().isEmpty() && queue.items.value.isEmpty() },
        purge = { telegram.dropDownloads() },
    )

    /** [part] of [fileId] was read whole for a copy and is done with. */
    fun keep(fileId: Id, part: MessageId, storage: StorageBackend) = blobs.keep(fileId, listOf(part), storage)
}

private const val RETAIN_DELAY_MS = 2_000L
