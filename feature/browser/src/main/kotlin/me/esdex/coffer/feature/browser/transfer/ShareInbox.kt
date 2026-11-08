package me.esdex.coffer.feature.browser.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files shared to Coffer from another app (SPEC §6, step 1). The permission to read them lasts only as
 * long as the receiving screen, so each one is copied into the app's own storage at once; the copy is
 * what gets uploaded, and it is deleted after the upload, on cancel, or when found orphaned.
 */
@Singleton
class ShareInbox @Inject constructor(@ApplicationContext private val context: Context) {

    /** A copied file. The name and type stay in memory until the upload request seals them (SPEC §8). */
    data class Item(val file: File, val name: String, val mime: String?, val size: Long)

    sealed interface State {
        data object Empty : State
        data class Copying(val done: Long, val total: Long?) : State
        data class Ready(val items: List<Item>) : State
        data class Failed(val reason: Reason) : State
    }

    enum class Reason { NO_SPACE, UNREADABLE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val stateFlow = MutableStateFlow<State>(State.Empty)
    val state: StateFlow<State> = stateFlow.asStateFlow()

    /** Copies [uris] right away; anything received before and not added yet is discarded. */
    // usableSpace on purpose, as for uploads: refusing early beats running out halfway through a copy.
    @SuppressLint("UsableSpace")
    fun receive(uris: List<Uri>, typeHint: String?) {
        discard()
        if (uris.isEmpty()) return
        stateFlow.value = State.Copying(0, null)
        job = scope.launch {
            val items = mutableListOf<Item>()
            try {
                val sizes = uris.map { querySize(it) }
                val total = sizes.takeIf { list -> list.all { it != null } }?.sumOf { it!! }
                val dir = dir(context).apply { mkdirs() }
                // Created first: File.usableSpace reports 0 for a path that does not exist.
                if (total != null && dir.usableSpace < total + SPACE_MARGIN) {
                    stateFlow.value = State.Failed(Reason.NO_SPACE)
                    return@launch
                }
                var done = 0L
                for (uri in uris) {
                    val file = File(dir, UUID.randomUUID().toString())
                    items += Item(file, queryName(uri) ?: uri.lastPathSegment ?: "file", context.contentResolver.getType(uri) ?: typeHint, 0)
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open")
                    input.use { from ->
                        file.outputStream().use { to ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                val n = from.read(buffer)
                                if (n < 0) break
                                to.write(buffer, 0, n)
                                done += n
                                stateFlow.value = State.Copying(done, total)
                            }
                        }
                    }
                    items[items.lastIndex] = items.last().copy(size = file.length())
                }
                stateFlow.value = State.Ready(items)
            } catch (e: Exception) {
                // Half-made copies go in every case, cancelled included.
                items.forEach { it.file.delete() }
                if (e is CancellationException) throw e
                stateFlow.value = State.Failed(if (e.isNoSpace()) Reason.NO_SPACE else Reason.UNREADABLE)
            }
        }
    }

    /** The copies, handed over to uploads; from now on the uploads own them. */
    fun take(): List<Item> {
        val ready = stateFlow.value as? State.Ready ?: return emptyList()
        stateFlow.value = State.Empty
        return ready.items
    }

    /** Cancel: the copies go. */
    fun discard() {
        job?.cancel()
        job = null
        (stateFlow.value as? State.Ready)?.items?.forEach { it.file.delete() }
        stateFlow.value = State.Empty
    }

    /** Files the inbox still holds, so a sweep for orphans leaves them alone. */
    fun held(): Set<File> = (stateFlow.value as? State.Ready)?.items?.mapTo(HashSet()) { it.file }.orEmpty()

    private fun queryName(uri: Uri): String? = query(uri, OpenableColumns.DISPLAY_NAME) { it.getString(0) }

    private fun querySize(uri: Uri): Long? = query(uri, OpenableColumns.SIZE) { if (it.isNull(0)) null else it.getLong(0) }

    private fun <T> query(uri: Uri, column: String, read: (android.database.Cursor) -> T?): T? = try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) read(it) else null }
    } catch (_: Exception) {
        null
    }

    private fun Exception.isNoSpace() = this is IOException && message?.contains("ENOSPC") == true

    companion object {
        // Room for the encrypted part the upload writes next to the copy, and for TDLib (SPEC §6, step 4).
        private const val SPACE_MARGIN = 64L * 1024 * 1024

        fun dir(context: Context) = File(context.noBackupFilesDir, "share")

        /** True if [source], an upload's source URI, is one of these copies. */
        fun isCopy(context: Context, source: String): Boolean {
            val uri = source.toUri()
            if (uri.scheme != "file") return false
            val path = uri.path ?: return false
            return File(path).parentFile?.canonicalPath == dir(context).canonicalPath
        }

        /** Deletes [source] if it is one of these copies; other sources are the user's own files. */
        fun deleteCopy(context: Context, source: String) {
            if (isCopy(context, source)) source.toUri().path?.let { File(it).delete() }
        }

        /** Deletes every copy in [dir] that is not in [keep]: left behind by a crash or a cancelled share. */
        fun sweep(dir: File, keep: Set<File>) {
            val kept = keep.mapTo(HashSet()) { it.canonicalPath }
            for (file in dir.listFiles().orEmpty()) if (file.canonicalPath !in kept) file.delete()
        }
    }
}
