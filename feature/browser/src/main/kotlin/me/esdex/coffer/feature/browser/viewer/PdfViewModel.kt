package me.esdex.coffer.feature.browser.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.feature.browser.transfer.VaultContent
import javax.inject.Inject

/**
 * A PDF from Saved Messages (pre-release item 5), after Arcanum's PdfViewerViewModel. The system
 * renderer reads only a real descriptor, so the document is decrypted whole into memory first (see
 * [MemoryCopy]); reading it in place through a descriptor this process serves could leave Coffer
 * unable to start after a kill. Up to [MemoryCopy.MAX_BYTES]; larger ones open in another app.
 */
@HiltViewModel
class PdfViewModel @Inject constructor(
    private val content: VaultContent,
) : ViewModel() {

    enum class Failure { PASSWORD_PROTECTED, UNREADABLE, NOT_AVAILABLE, TOO_LARGE }

    /** [loaded] of [size] bytes are in memory while [loading]. */
    data class State(
        val fileId: Id? = null,
        val loading: Boolean = true,
        val pageCount: Int = 0,
        val failure: Failure? = null,
        val loaded: Long = 0,
        val size: Long = 0,
    )

    /** A page's size in points, so its place in the list has the right shape before it is drawn. */
    data class PageShape(val width: Int, val height: Int)

    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow.asStateFlow()

    /** The vault locked: the keys behind the document are gone, and so must the viewer be. */
    val locked: StateFlow<Boolean> = content.keys.map { it == null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** One page at a time, one thread in the renderer: pdfium is not re-entrant, and closing it under a render is a crash. */
    private val lock = Mutex()

    // Closing outlives viewModelScope: onCleared comes after it is cancelled, and the close still has to wait for the lock.
    private val closing = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Read outside the lock by close(), which must not wait for a page to get at it.
    @Volatile private var file: VaultContent.OpenedFile? = null
    private var copy: MemoryCopy? = null
    private var renderer: PdfRenderer? = null
    private val shapes = HashMap<Int, PageShape>()

    // Counts opens and closes, so an open that finishes after its viewer was left does not keep the document.
    private var session = 0

    /**
     * Rendered pages, bounded by memory rather than by count: a page at full width is several
     * megabytes, and a phone that holds eight of them would not hold eight of something larger.
     */
    private val pages = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024) / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun open(fileId: Id) {
        if (stateFlow.value.fileId == fileId) return
        stateFlow.value = State(fileId = fileId)
        viewModelScope.launch(Dispatchers.IO) {
            val mine = lock.withLock {
                closeDocument()
                ++session
            }
            val opened = content.openFile(fileId)
            // Outside the lock: a close has to be able to cut the copy short by closing the file under it.
            file = opened
            var failure: Failure? = null
            val memory = opened?.let {
                try {
                    MemoryCopy.of(it.reader) { done, size -> progress(fileId, done, size) }
                } catch (e: CancellationException) {
                    it.close()
                    throw e
                } catch (_: MemoryCopy.TooLargeException) {
                    failure = Failure.TOO_LARGE
                    null
                } catch (_: Exception) {
                    failure = Failure.NOT_AVAILABLE
                    null
                }
            }
            // Copied or not, the vault file is done with, and its pieces go to the cache.
            opened?.close()
            file = null
            val result: Pair<Failure?, Int> = lock.withLock {
                if (session != mine) {
                    memory?.close()
                    return@withLock null to 0
                }
                if (memory == null) return@withLock (failure ?: Failure.NOT_AVAILABLE) to 0
                copy = memory
                try {
                    val open = PdfRenderer(memory.descriptor)
                    renderer = open
                    null to open.pageCount
                } catch (_: SecurityException) {
                    // pdfium's answer to an encrypted document: it does not ask for a password, and
                    // below Android 15 there is no API to give it one.
                    closeDocument()
                    Failure.PASSWORD_PROTECTED to 0
                } catch (_: Exception) {
                    closeDocument()
                    Failure.UNREADABLE to 0
                }
            }
            stateFlow.value.takeIf { it.fileId == fileId }?.let {
                stateFlow.value = it.copy(loading = false, pageCount = result.second, failure = result.first)
            }
        }
    }

    private fun progress(fileId: Id, done: Long, size: Long) {
        stateFlow.value.takeIf { it.fileId == fileId && it.loading }?.let { stateFlow.value = it.copy(loaded = done, size = size) }
    }

    /** The page's shape in points, read once and remembered. */
    suspend fun shapeOf(index: Int): PageShape? = withContext(Dispatchers.IO) {
        lock.withLock {
            shapes[index] ?: runCatching { renderer?.openPage(index)?.use { PageShape(it.width, it.height) } }.getOrNull()?.also { shapes[index] = it }
        }
    }

    /**
     * The page drawn [widthPx] wide, from the cache when it is there. Drawn onto white: a PDF page has
     * no background of its own, and left transparent it would show whatever is behind it.
     */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (widthPx <= 0) return@withContext null
        val key = "$index@$widthPx"
        pages[key]?.let { return@withContext it }
        lock.withLock {
            pages[key]?.let { return@withLock it }
            val open = renderer ?: return@withLock null
            runCatching {
                open.openPage(index).use { page ->
                    val height = (widthPx.toLong() * page.height / page.width).coerceAtLeast(1L).toInt()
                    shapes[index] = PageShape(page.width, page.height)
                    createBitmap(widthPx, height).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()?.also { pages.put(key, it) }
        }
    }

    /** Under [lock] only. */
    private fun closeDocument() {
        runCatching { renderer?.close() }
        renderer = null
        copy?.close()
        copy = null
        file?.close()
        file = null
        shapes.clear()
        pages.evictAll()
    }

    /**
     * The viewer was left. This model lives as long as the activity, so nothing goes by itself. The
     * file is closed first, outside the lock: a page waiting on Telegram then fails at once instead of
     * holding the close up behind it.
     */
    fun close() {
        stateFlow.value = State()
        file?.close()
        closing.launch {
            lock.withLock {
                session++
                closeDocument()
            }
        }
    }

    override fun onCleared() {
        close()
    }
}
