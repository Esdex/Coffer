package me.esdex.coffer.feature.browser.viewer

import android.content.Context
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.ui.theme.PlayerPrefs
import me.esdex.coffer.core.ui.theme.PlayerStore
import me.esdex.coffer.feature.browser.preview.Previews
import me.esdex.coffer.feature.browser.transfer.VaultContent
import javax.inject.Inject

/**
 * The viewer's pages and what each shows (pre-release item 5). Only the page on screen and its two
 * neighbours are decoded; the rest are let go as the pager moves, so a folder of a thousand photos
 * costs three.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val content: VaultContent,
    @ApplicationContext private val context: Context,
    playerStore: PlayerStore,
    private val previews: Previews,
) : ViewModel() {

    data class Page(val fileId: Id, val name: String, val mime: String) {
        val isVideo: Boolean get() = mime.startsWith("video/")
    }

    val playerPrefs: StateFlow<PlayerPrefs> = playerStore.prefs

    sealed interface Picture {
        /** [loaded] of [size] bytes in memory, the size 0 until it is known. */
        data class Loading(val loaded: Long = 0, val size: Long = 0) : Picture
        data class Shown(val drawable: Drawable) : Picture
        data object Failed : Picture
    }

    /** The file's preview, if the list has it in memory: something to show while the picture loads. */
    fun preview(fileId: Id): ImageBitmap? = previews.cached(fileId)

    private val pagesFlow = MutableStateFlow<List<Page>>(emptyList())
    val pages: StateFlow<List<Page>> = pagesFlow.asStateFlow()

    private val picturesFlow = MutableStateFlow<Map<Id, Picture>>(emptyMap())
    val pictures: StateFlow<Map<Id, Picture>> = picturesFlow.asStateFlow()

    /** The vault locked: the keys behind every open file are gone, and so must the viewer be. */
    val locked: StateFlow<Boolean> = content.keys.map { it == null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** An animated picture keeps reading frames from its file for as long as it plays. */
    // An animation reads its frames as it plays: its copy in memory stays while it is shown.
    private val held = HashMap<Id, MemoryCopy>()
    private val jobs = HashMap<Id, Job>()

    // Big enough to zoom into a little, small enough that three pages do not fill the heap.
    private val maxDimension: Int = context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) * 3 / 2 }.coerceAtMost(4096)

    fun show(pages: List<Page>, index: Int) {
        if (pagesFlow.value == pages) return
        dropAll()
        pagesFlow.value = pages
        focus(index)
    }

    /** The pager settled on [index]: it and its neighbours are wanted, nothing else. */
    fun focus(index: Int) {
        val pages = pagesFlow.value
        // Pictures only: a video is read by the player, and only while it is on screen.
        val wanted = (index - 1..index + 1).mapNotNull { pages.getOrNull(it)?.takeUnless(Page::isVideo)?.fileId }.toSet()
        val known = synchronized(held) { held.keys + jobs.keys } + picturesFlow.value.keys
        for (id in known - wanted) drop(id)
        for (id in wanted) if (id !in picturesFlow.value) load(id)
    }

    private fun load(fileId: Id) {
        picturesFlow.update { it + (fileId to Picture.Loading()) }
        // Registered before it starts: decode() asks whether it is still wanted by looking for it.
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val result = runCatching { decode(fileId) }.getOrNull()
            ensureActive()
            picturesFlow.update { if (fileId in it) it + (fileId to (result?.let(Picture::Shown) ?: Picture.Failed)) else it }
        }
        synchronized(held) { jobs[fileId] = job }
        job.invokeOnCompletion { synchronized(held) { if (jobs[fileId] === job) jobs.remove(fileId) } }
        job.start()
    }

    /**
     * The picture read whole into memory first (see [MemoryCopy]), then decoded from there: the decoder
     * reads a still whole anyway. The vault file is let go as soon as it is copied.
     */
    private suspend fun decode(fileId: Id): Drawable? {
        val file = content.openFile(fileId) ?: return null
        val copy = try {
            var shown = -1
            MemoryCopy.of(file.reader) { done, size ->
                // Whole percents only: the copy reports every chunk, the screen needs a step it can see.
                val percent = if (size > 0) (done * 100 / size).toInt() else 0
                if (percent != shown) {
                    shown = percent
                    picturesFlow.update { if (it[fileId] is Picture.Loading) it + (fileId to Picture.Loading(done, size)) else it }
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            file.close()
        }
        val drawable = Pictures.decode(copy.descriptor, maxDimension)
        val keep = drawable is AnimatedImageDrawable
        // Let go at once unless it animates; and when the page went away while decoding, either way.
        val stillWanted = synchronized(held) {
            val wanted = jobs.containsKey(fileId) && picturesFlow.value.containsKey(fileId)
            if (keep && wanted) held[fileId] = copy
            wanted
        }
        if (!keep || !stillWanted) copy.close()
        return drawable?.takeIf { stillWanted }
    }

    private fun drop(fileId: Id) {
        val (job, open) = synchronized(held) { jobs.remove(fileId) to held.remove(fileId) }
        job?.cancel()
        (picturesFlow.value[fileId] as? Picture.Shown)?.drawable?.let(Pictures::stop)
        open?.close()
        picturesFlow.update { it - fileId }
    }

    private fun dropAll() {
        val ids = synchronized(held) { held.keys + jobs.keys } + picturesFlow.value.keys
        ids.forEach(::drop)
    }

    // ── Video ────────────────────────────────────────────────────────────────

    private var exo: ExoPlayer? = null
    private var playing: Pair<Id, VaultContent.OpenedFile>? = null
    private var opening: Job? = null

    private val videoFailedFlow = MutableStateFlow(false)

    /** The video on screen could not be opened: locked, offline, or not whole. */
    val videoFailed: StateFlow<Boolean> = videoFailedFlow.asStateFlow()

    /** One player for the whole viewer, made the first time a video is on screen. */
    @OptIn(UnstableApi::class)
    fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(context)
        // Audio focus, so a video pauses music playing in the background instead of sounding over it.
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .build()
        .also { exo = it }

    /** [page] is on screen: it plays, read in place; whatever played before is let go. */
    @OptIn(UnstableApi::class)
    fun play(page: Page) {
        if (playing?.first == page.fileId && opening == null) return
        stopVideo()
        videoFailedFlow.value = false
        opening = viewModelScope.launch {
            val file = content.openFile(page.fileId)
            opening = null
            if (file == null) {
                videoFailedFlow.value = true
                return@launch
            }
            playing = page.fileId to file
            val source = ProgressiveMediaSource.Factory { ReaderDataSource { file.reader } }
                // Any address will do: the data source reads one file and ignores it.
                .createMediaSource(MediaItem.fromUri("coffer://video"))
            player().run {
                setMediaSource(source)
                prepare()
                playWhenReady = true
            }
        }
    }

    /** No video on screen any more: the player stops and the file is closed. */
    fun stopVideo() {
        opening?.cancel()
        opening = null
        exo?.run {
            stop()
            clearMediaItems()
        }
        playing?.second?.close()
        playing = null
    }

    /**
     * The viewer was left. This model lives as long as the activity, not the viewer, so nothing is
     * let go by itself: the video stops here, its file closes, the pictures are dropped.
     */
    fun close() {
        stopVideo()
        dropAll()
        pagesFlow.value = emptyList()
    }

    override fun onCleared() {
        stopVideo()
        exo?.release()
        exo = null
        dropAll()
    }
}
