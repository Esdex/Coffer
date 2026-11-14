package me.esdex.coffer.feature.browser.viewer.audio

import android.content.Context
import android.content.Intent
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.sync.VaultFileReader
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.ui.theme.PlayerPrefs
import me.esdex.coffer.core.ui.theme.PlayerStore
import me.esdex.coffer.feature.browser.transfer.VaultContent
import me.esdex.coffer.feature.browser.viewer.ReaderDataSource
import me.esdex.coffer.feature.browser.viewer.ViewerViewModel

/**
 * The music: one player for the app, its queue, and the keys of that queue's files (pre-release item 5).
 * It outlives the screen so music plays on in the background, and the vault locking: the queue's files
 * are held with keys of their own (SPEC §6), wiped when the music is stopped, on log out, and ten
 * minutes after it stops playing with the vault locked. [AudioService] puts it in a notification.
 */
@Singleton
class AudioPlayback @Inject constructor(
    @ApplicationContext private val context: Context,
    private val content: VaultContent,
    telegram: Telegram,
    playerStore: PlayerStore,
    private val waveforms: Waveforms,
) {
    val prefs: StateFlow<PlayerPrefs> = playerStore.prefs

    /** The waveform of the track playing, as far as it has been worked out (see [Waveforms]). */
    val waveform: StateFlow<Waveforms.Shown?> = waveforms.shown

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val queueFlow = MutableStateFlow<List<ViewerViewModel.Page>>(emptyList())

    /** The tracks queued, as the player screen lists them; empty when nothing is. */
    val queue: StateFlow<List<ViewerViewModel.Page>> = queueFlow.asStateFlow()

    private val expandedFlow = MutableStateFlow(true)

    /** The player on the whole screen, or folded into a bar under the files while the music plays on. */
    val expanded: StateFlow<Boolean> = expandedFlow.asStateFlow()

    fun expand() {
        expandedFlow.value = true
    }

    fun collapse() {
        expandedFlow.value = false
    }

    // Read by the loader threads; changed under the readers' lock, so no reader opens on a wiped key.
    @Volatile private var held: List<VaultContent.HeldFile> = emptyList()

    // Open readers by queue position: the track playing and the one the player loads ahead of it.
    private val readers = HashMap<Int, VaultContent.OpenedFile>()

    private val playingFlow = MutableStateFlow(false)
    private var exo: ExoPlayer? = null

    /** The player, made on first use; the main thread's only. */
    val player: ExoPlayer
        @MainThread get() = exo ?: build().also { exo = it }

    init {
        // Logged out, or signed into another account: the queue belongs to a vault that is gone.
        scope.launch { telegram.state.collect { if (it == AuthState.LoggingOut || it == AuthState.Closed) stop() } }
        stopWhenIdleLocked(scope, content.keys.map { it == null }, playingFlow, IDLE_STOP_MILLIS) { stop() }
        // Locked with music queued: the track playing may finish, nothing after it (Esdex, audit 1, M12).
        scope.launch { content.keys.map { it == null }.distinctUntilChanged().collect { locked -> if (locked) keepOnlyTheCurrentTrack() } }
    }

    /**
     * The vault locked while music was queued. The lock screen's and the headset's controls reach the
     * player past the lock, so with the queue left whole anyone holding the locked phone could play the
     * vault's other tracks (audit 1, M12). The track playing stays, to finish; the others leave the
     * player, and their keys are wiped. Repeat and shuffle go, so it ends where it ends.
     */
    @MainThread
    private fun keepOnlyTheCurrentTrack() {
        val player = exo ?: return
        if (player.mediaItemCount == 0) return
        val current = player.currentMediaItemIndex
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        if (current + 1 < player.mediaItemCount) player.removeMediaItems(current + 1, player.mediaItemCount)
        if (current > 0) player.removeMediaItems(0, current)
        // The track left keeps its place in [held], which its address points at.
        synchronized(readers) {
            for (index in readers.keys - current) readers.remove(index)?.close()
            held.forEachIndexed { index, file -> if (index != current) file.close() }
        }
        queueFlow.value = queueFlow.value.getOrNull(current)?.let(::listOf).orEmpty()
    }

    @OptIn(UnstableApi::class)
    private fun build(): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory { ReaderDataSource(::readerFor) })
        // Audio focus: a call or another app's sound pauses the music. And it pauses when headphones
        // come out rather than playing on through the speaker.
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { player ->
            player.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    playingFlow.value = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
                    // The last track played out with the vault locked: nothing more to play until it opens.
                    if (player.playbackState == Player.STATE_ENDED && content.keys.value == null) {
                        scope.launch { stop() }
                        return
                    }
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                        closeOthers(player)
                        followWaveform(player)
                    }
                }
            })
        }

    /**
     * Plays [tracks] from [start], holding their files with keys of their own. False when the vault is
     * locked, Telegram cannot be reached, or none of them can be read.
     */
    @MainThread
    suspend fun play(tracks: List<ViewerViewModel.Page>, start: Int): Boolean {
        stop()
        val files = content.holdFiles(tracks.map { it.fileId }) ?: return false
        if (files.isEmpty()) return false
        val wanted = tracks.getOrNull(start)?.fileId
        synchronized(readers) { held = files }
        queueFlow.value = files.map { ViewerViewModel.Page(it.fileId, it.name, it.mime) }
        expandedFlow.value = true
        // No title on the items: the file's own tags would lose to it, and the name is not for outside Coffer.
        val items = files.indices.map { MediaItem.Builder().setMediaId(it.toString()).setUri(addressOf(it)).build() }
        player.run {
            setMediaItems(items, files.indexOfFirst { it.fileId == wanted }.coerceAtLeast(0), 0L)
            prepare()
            playWhenReady = true
        }
        followWaveform(player)
        context.startService(Intent(context, AudioService::class.java))
        return true
    }

    /** The queue's file at [index], for the save and open-with menu. */
    fun fileAt(index: Int): Id? = queueFlow.value.getOrNull(index)?.fileId

    /** Stops the music and lets go of everything: the readers, then the keys, then the notification. */
    @MainThread
    fun stop() {
        if (held.isEmpty() && queueFlow.value.isEmpty() && (exo?.mediaItemCount ?: 0) == 0) return
        // Its reader goes before the keys it reads with.
        waveforms.follow(null)
        exo?.run {
            stop()
            clearMediaItems()
        }
        synchronized(readers) {
            readers.values.forEach { it.close() }
            readers.clear()
            held.forEach { it.close() }
            held = emptyList()
        }
        queueFlow.value = emptyList()
        playingFlow.value = false
        context.stopService(Intent(context, AudioService::class.java))
    }

    // Called on the player's loader threads; one reader per track, kept while it plays.
    private fun readerFor(uri: android.net.Uri): VaultFileReader {
        val index = uri.lastPathSegment?.toIntOrNull() ?: throw IOException("not a queue address")
        synchronized(readers) {
            readers[index]?.let { return it.reader }
            val file = held.getOrNull(index) ?: throw IOException("not in the queue")
            return file.open().also { readers[index] = it }.reader
        }
    }

    /** The waveform follows the track playing. */
    @MainThread
    private fun followWaveform(player: Player) = waveforms.follow(held.getOrNull(player.currentMediaItemIndex))

    /** Readers of tracks that are neither playing nor next are closed, so a long queue holds two at most. */
    private fun closeOthers(player: Player) {
        val keep = setOf(player.currentMediaItemIndex, player.nextMediaItemIndex)
        synchronized(readers) {
            val gone = readers.keys - keep
            for (index in gone) readers.remove(index)?.close()
        }
    }

    private fun addressOf(index: Int) = "coffer://audio/$index"

    companion object {
        /** What a tap on the notification opens Coffer with: the player, on the whole screen. */
        const val ACTION_SHOW_PLAYER = "me.esdex.coffer.action.SHOW_PLAYER"

        private const val IDLE_STOP_MILLIS = 10 * 60_000L
    }
}
