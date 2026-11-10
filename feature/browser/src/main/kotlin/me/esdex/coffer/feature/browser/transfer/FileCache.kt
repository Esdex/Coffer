package me.esdex.coffer.feature.browser.transfer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.esdex.coffer.core.crypto.Id
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
@Singleton
class FileCache @Inject constructor(
    @ApplicationContext context: Context,
    store: CacheStore,
    telegram: Telegram,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val prefs: StateFlow<CachePrefs> = store.prefs

    /** Files open for reading in place; the cache never takes a piece from under one. */
    val leases = ReadLeases(scope)

    val blobs = BlobCache(scope, File(context.noBackupFilesDir, "file-cache"), store.prefs::value, leases::isOpen)

    init {
        // Logging out deletes TDLib's files and the next account has other message ids: the list goes too.
        scope.launch { telegram.state.collect { if (it == AuthState.LoggingOut) blobs.forget() } }
    }

    /** [part] of [fileId] was read whole for a copy and is done with. */
    fun keep(fileId: Id, part: MessageId, storage: StorageBackend) = blobs.keep(fileId, listOf(part), storage)
}
