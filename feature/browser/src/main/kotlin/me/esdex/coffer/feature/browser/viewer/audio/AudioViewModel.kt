package me.esdex.coffer.feature.browser.viewer.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.sync.VaultFileReader
import me.esdex.coffer.feature.browser.transfer.VaultContent
import me.esdex.coffer.feature.browser.viewer.ReaderDataSource
import me.esdex.coffer.feature.browser.viewer.ViewerViewModel
import javax.inject.Inject

/**
 * The audio player (pre-release item 5), after Arcanum's: the tracks of a folder, read in place from
 * Saved Messages like a video, one file open at a time. It plays while Coffer is on screen; playing on
 * in the background, with a notification, is a step of its own.
 */
@HiltViewModel
class AudioViewModel @Inject constructor(
    private val content: VaultContent,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** What a track's tags say, or its name when it has none. [color] is the cover's, for the background. */
    data class Tags(val title: String, val artist: String?, val album: String?, val cover: Bitmap?, val color: Int?)

    data class State(
        val tracks: List<ViewerViewModel.Page> = emptyList(),
        val index: Int = 0,
        val tags: Tags? = null,
        val isPlaying: Boolean = false,
        val buffering: Boolean = false,
        val durationMs: Long = 0L,
        val failed: Boolean = false,
        val shuffled: Boolean = false,
        val repeat: Repeat = Repeat.NONE,
    ) {
        val track: ViewerViewModel.Page? get() = tracks.getOrNull(index)
    }

    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow.asStateFlow()

    /** The vault locked: the files are closed under the player, and the screen goes. */
    val locked: StateFlow<Boolean> = content.keys.map { it == null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var order: TrackOrder? = null
    private var exo: ExoPlayer? = null
    private var playing: VaultContent.OpenedFile? = null
    private var opening: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = stateFlow.update { it.copy(isPlaying = isPlaying) }

        override fun onPlaybackStateChanged(playbackState: Int) {
            stateFlow.update { s -> s.copy(buffering = playbackState == Player.STATE_BUFFERING, durationMs = exo?.duration?.takeIf { it > 0L } ?: s.durationMs) }
            if (playbackState == Player.STATE_ENDED) onEnded()
        }

        override fun onPlayerError(error: PlaybackException) = stateFlow.update { it.copy(failed = true, buffering = false) }
    }

    /**
     * One player for the screen. It takes audio focus, so a call or another app's sound pauses it, and
     * it pauses when headphones come out rather than playing on through the speaker.
     */
    @OptIn(UnstableApi::class)
    fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also {
            it.addListener(listener)
            exo = it
        }

    fun show(tracks: List<ViewerViewModel.Page>, start: Int) {
        if (stateFlow.value.tracks == tracks) return
        val first = start.coerceIn(tracks.indices)
        order = TrackOrder(tracks.size, first)
        stateFlow.value = State(tracks = tracks, index = first)
        play(first)
    }

    private fun play(index: Int) {
        stopTrack()
        stateFlow.update { it.copy(index = index, tags = null, failed = false, durationMs = 0L, buffering = true) }
        val page = stateFlow.value.tracks.getOrNull(index) ?: return
        opening = viewModelScope.launch {
            val file = content.openFile(page.fileId)
            if (file == null) {
                stateFlow.update { it.copy(failed = true, buffering = false) }
                return@launch
            }
            playing = file
            start(file.reader)
            val tags = withContext(Dispatchers.IO) { runCatching { readTags(file.reader, page.name) }.getOrNull() } ?: Tags(page.name.substringBeforeLast('.', page.name), null, null, null, null)
            ensureActive()
            stateFlow.update { it.copy(tags = tags) }
        }
    }

    @OptIn(UnstableApi::class)
    private fun start(reader: VaultFileReader) {
        // Any address will do: the data source reads one file and ignores it.
        val source = ProgressiveMediaSource.Factory { ReaderDataSource(reader) }.createMediaSource(MediaItem.fromUri("coffer://audio"))
        player().run {
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }

    /**
     * The tags, read through the same reader as the sound: what MediaMetadataRetriever asks for is the
     * head of the file and sometimes its tail, a chunk or two, never the whole track.
     */
    private fun readTags(reader: VaultFileReader, name: String): Tags {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(ReaderMediaDataSource(reader))
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.', name)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            val cover = retriever.embeddedPicture?.let(::decodeCover)
            // The cover's average colour, from a 1x1 scale: enough to tint the background, without a library.
            val color = cover?.scale(1, 1)?.let { one -> one[0, 0].also { if (one !== cover) one.recycle() } }
            return Tags(title, artist, album, cover, color)
        } finally {
            retriever.release()
        }
    }

    // A cover is often a few thousand pixels across; the screen shows it at a third of that.
    private fun decodeCover(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun onEnded() {
        val order = order ?: return
        val next = order.next(byHand = false)
        when (next) {
            null -> exo?.run {
                // The folder has played through: back to the start of the last track, paused.
                playWhenReady = false
                seekTo(0L)
            }
            stateFlow.value.index -> exo?.run {
                seekTo(0L)
                play()
            }
            else -> play(next)
        }
    }

    fun togglePlay() {
        val p = exo ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
        if (p.isPlaying) p.pause() else p.play()
    }

    fun pause() {
        exo?.pause()
    }

    fun seekTo(ms: Long) {
        exo?.seekTo(ms.coerceAtLeast(0L))
    }

    fun next() {
        order?.next(byHand = true)?.let(::play)
    }

    /** Back to the start of the track a few seconds in, as every player does; otherwise the track before. */
    fun previous() {
        val p = exo ?: return
        if (p.currentPosition > RESTART_MS) p.seekTo(0L) else order?.previous()?.let(::play)
    }

    fun toggleShuffle() {
        val order = order ?: return
        order.shuffle(!order.shuffled)
        stateFlow.update { it.copy(shuffled = order.shuffled) }
    }

    fun cycleRepeat() {
        val order = order ?: return
        order.repeat = Repeat.entries[(order.repeat.ordinal + 1) % Repeat.entries.size]
        stateFlow.update { it.copy(repeat = order.repeat) }
    }

    private fun stopTrack() {
        opening?.cancel()
        opening = null
        exo?.run {
            stop()
            clearMediaItems()
        }
        playing?.close()
        playing = null
    }

    /**
     * The player was left. This model lives as long as the activity, so nothing goes by itself: the
     * sound stops here and the file closes.
     */
    fun close() {
        stopTrack()
        order = null
        stateFlow.value = State()
    }

    override fun onCleared() {
        stopTrack()
        exo?.run {
            removeListener(listener)
            release()
        }
        exo = null
    }

    private companion object {
        const val COVER_PX = 1024
        const val RESTART_MS = 3_000L
    }
}

/** MediaMetadataRetriever reading a track in place; it calls from a thread of its own, where blocking is fine. */
private class ReaderMediaDataSource(private val reader: VaultFileReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= reader.size) return -1
        return runBlocking { reader.read(position, buffer, offset, size) }
    }

    override fun getSize(): Long = reader.size

    override fun close() {}
}
