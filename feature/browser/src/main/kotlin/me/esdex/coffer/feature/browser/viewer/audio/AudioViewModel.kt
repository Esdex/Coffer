package me.esdex.coffer.feature.browser.viewer.audio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.ui.theme.PlayerPrefs
import me.esdex.coffer.feature.browser.viewer.ViewerViewModel
import javax.inject.Inject

/**
 * The audio player's screen over [AudioPlayback] (pre-release item 5): what the shared player is doing,
 * and the buttons. The player and its queue are not the screen's: they play on when it goes.
 */
@HiltViewModel
class AudioViewModel @Inject constructor(private val playback: AudioPlayback) : ViewModel() {

    /** What a track's tags say, read by the player from the file. [color] is the cover's, for the background. */
    data class Tags(val title: String?, val artist: String?, val album: String?, val cover: Bitmap?, val color: Int?)

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

    val prefs: StateFlow<PlayerPrefs> = playback.prefs

    /** The waveform of the track playing, as far as it has been worked out. */
    val waveform: StateFlow<Waveforms.Shown?> = playback.waveform

    val player: Player get() = playback.player

    private var tagsJob: Job? = null
    private var tagsOf: MediaMetadata? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)

        override fun onPlayerError(error: PlaybackException) = stateFlow.update { it.copy(failed = true, buffering = false) }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = stateFlow.update { it.copy(failed = false) }
    }

    init {
        playback.player.addListener(listener)
        refresh(playback.player)
        viewModelScope.launch { playback.queue.collect { tracks -> stateFlow.update { it.copy(tracks = tracks) } } }
    }

    private fun refresh(player: Player) {
        stateFlow.update {
            it.copy(
                index = player.currentMediaItemIndex,
                isPlaying = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                durationMs = player.duration.takeIf { d -> d > 0L } ?: 0L,
                failed = it.failed || player.playerError != null,
                shuffled = player.shuffleModeEnabled,
                repeat = when (player.repeatMode) {
                    Player.REPEAT_MODE_ALL -> Repeat.ALL
                    Player.REPEAT_MODE_ONE -> Repeat.ONE
                    else -> Repeat.NONE
                },
            )
        }
        readTags(player.mediaMetadata)
    }

    // The cover comes as the bytes in the file; decoded off the main thread, once per change.
    private fun readTags(metadata: MediaMetadata) {
        if (metadata == tagsOf) return
        tagsOf = metadata
        tagsJob?.cancel()
        tagsJob = viewModelScope.launch {
            val cover = metadata.artworkData?.let { bytes -> withContext(Dispatchers.Default) { decodeCover(bytes) } }
            // The cover's average colour, from a 1x1 scale: enough to tint the background, without a library.
            val color = cover?.scale(1, 1)?.let { one -> one[0, 0].also { if (one !== cover) one.recycle() } }
            val tags = Tags(metadata.title?.toString(), metadata.artist?.toString(), metadata.albumTitle?.toString(), cover, color)
            stateFlow.update { it.copy(tags = tags) }
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

    fun togglePlay() {
        val p = playback.player
        when {
            p.playerError != null -> retry(p)
            // Played to the end: Play starts the last track again, as before.
            p.playbackState == Player.STATE_ENDED -> {
                p.seekToDefaultPosition(p.currentMediaItemIndex)
                p.play()
            }
            p.isPlaying -> p.pause()
            else -> p.play()
        }
    }

    fun pause() = playback.player.pause()

    fun seekTo(ms: Long) = playback.player.seekTo(ms.coerceAtLeast(0L))

    /** On to the next, even under repeat one; from the last only when everything repeats. */
    fun next() = move { it.seekToNextMediaItem() }

    /** Back to the start of the track a few seconds in, as every player does; otherwise the track before. */
    fun previous() = move { it.seekToPrevious() }

    // A track that failed leaves the player stopped: moving on has to prepare it again.
    private fun move(seek: (Player) -> Unit) {
        val p = playback.player
        val failed = p.playerError != null
        seek(p)
        if (failed) retry(p)
    }

    private fun retry(p: Player) {
        stateFlow.update { it.copy(failed = false) }
        p.prepare()
        p.play()
    }

    /**
     * The order is drawn again from the track playing each time shuffle goes on, as Arcanum learned: the
     * player's own is drawn once, so off and on replayed the same order, and the playing track could sit
     * last in it, leaving nothing to come.
     */
    @OptIn(UnstableApi::class)
    fun toggleShuffle() {
        val p = playback.player
        if (!p.shuffleModeEnabled && p.mediaItemCount > 1) {
            val current = p.currentMediaItemIndex
            val order = (listOf(current) + (0 until p.mediaItemCount).filter { it != current }.shuffled()).toIntArray()
            p.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
        }
        p.shuffleModeEnabled = !p.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val p = playback.player
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** The player was closed by hand: the music stops, and the queue's keys are wiped. */
    fun stop() = playback.stop()

    /**
     * Back from the player: while music plays it folds into the bar under the files and plays on; paused,
     * ended or failed, there is nothing to keep, and it closes.
     */
    fun back() {
        val p = playback.player
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playerError == null) playback.collapse() else playback.stop()
    }

    fun expand() = playback.expand()

    override fun onCleared() {
        playback.player.removeListener(listener)
    }

    private companion object {
        const val COVER_PX = 1024
    }
}

enum class Repeat { NONE, ALL, ONE }
