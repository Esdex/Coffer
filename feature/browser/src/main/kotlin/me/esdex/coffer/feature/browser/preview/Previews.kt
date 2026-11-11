package me.esdex.coffer.feature.browser.preview

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.feature.browser.FileItem
import me.esdex.coffer.feature.browser.transfer.FileCache
import me.esdex.coffer.feature.browser.transfer.TransferRegistry
import me.esdex.coffer.feature.browser.transfer.VaultContent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Previews on the file icons: photos, video frames and song covers, made as the files come into view
 * and kept on the phone, sealed (see [PreviewLoader]). Shown only while the vault is open; locking
 * forgets the ones in memory.
 */
@OptIn(FlowPreview::class)
@Singleton
class Previews @Inject constructor(
    @ApplicationContext context: Context,
    private val content: VaultContent,
    index: VaultIndex,
    cache: FileCache,
    registry: TransferRegistry,
    telegram: Telegram,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val store = PreviewStore(dir(context))

    val enabled: StateFlow<Boolean> = cache.prefs.map { it.previews }.stateIn(scope, SharingStarted.Eagerly, cache.prefs.value.previews)

    // Decoded, for scrolling back without a flicker; a file with none is held as NONE.
    private val memory = LruCache<Id, Any>(MEMORY_ITEMS)

    private val loader = PreviewLoader(
        store,
        // Turned off counts as locked: one being made as the switch goes off is not kept after the clear.
        seal = {
            content.keys.value?.local?.takeIf { enabled.value }?.let { sealer ->
                object : PreviewSeal {
                    override fun seal(fileId: Id, picture: ByteArray) = sealer.sealPreview(fileId, picture)
                    override fun open(fileId: Id, sealed: ByteArray) = sealer.openPreview(fileId, sealed)
                }
            }
        },
        // Transfers first: a preview is never worth slowing down what the user is waiting for.
        idle = { registry.progress.first { it.isEmpty() } },
    )

    init {
        scope.launch { content.keys.collect { if (it == null) memory.evictAll() } }
        // Turned off, the ones made so far go too.
        scope.launch { enabled.collect { on -> if (!on) clear() } }
        scope.keepWithFiles(store, index, content.catchingUp, telegram)
    }

    /** Deletes every preview, in memory and on the phone. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        memory.evictAll()
        store.clear()
    }

    /** Already in memory: shown at once, with no wait. */
    fun cached(fileId: Id): ImageBitmap? = memory.get(fileId) as? ImageBitmap

    /** [file]'s preview, made if need be; null if it has none, cannot have one, or cannot get it now. */
    suspend fun preview(file: FileItem): ImageBitmap? {
        if (!enabled.value || file.status != FileStatus.READY) return null
        val kind = PreviewKind.of(file.mime) ?: return null
        memory.get(file.fileId)?.let { return it as? ImageBitmap }
        val bytes = withContext(Dispatchers.IO) { loader.load(file.fileId) { make(file, kind) } } ?: return null
        val bitmap = if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        if (enabled.value && content.keys.value != null) memory.put(file.fileId, bitmap ?: NONE)
        return bitmap
    }

    /**
     * Reads what the preview needs. The decoders block on the reads, so a cancel (the row scrolled away)
     * closes the file under them, and they give up.
     */
    private suspend fun make(file: FileItem, kind: PreviewKind): ByteArray? {
        val opened = content.openFile(file.fileId, keepInCache = false) ?: return null
        return try {
            coroutineScope {
                val closer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        opened.close()
                    }
                }
                try {
                    withContext(Dispatchers.IO) { PreviewMaker.make(opened.reader, file.mime, kind) }
                } finally {
                    closer.cancel()
                }
            }
        } finally {
            opened.close()
        }
    }

    companion object {
        private val NONE = Any()
        // 144 px a side is about 83 KB decoded: some 16 MB at most.
        private const val MEMORY_ITEMS = 200

        /** Where previews are kept: no_backup, as they are this phone's alone. */
        fun dir(context: Context) = File(context.noBackupFilesDir, "previews")
    }
}

/**
 * What this phone made from vault files and keeps in [store], previews or waveforms: it goes after the
 * files deleted, though not during a catch-up or a rescan, when the index is still being filled and would
 * seem to have lost them; and all of it goes on sign-out.
 */
@OptIn(FlowPreview::class)
internal fun CoroutineScope.keepWithFiles(store: PreviewStore, index: VaultIndex, catchingUp: Flow<Int?>, telegram: Telegram) {
    launch { telegram.state.collect { if (it == AuthState.LoggingOut) store.clear() } }
    launch {
        combine(index.observeFiles(), catchingUp) { files, busy -> if (busy == null) files else null }
            .filterNotNull()
            .map { files -> files.mapTo(HashSet()) { it.fileId } }
            .distinctUntilChanged()
            .debounce(RETAIN_DELAY_MS)
            .collect { store.retain(it) }
    }
}

private const val RETAIN_DELAY_MS = 2_000L
