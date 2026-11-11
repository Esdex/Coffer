package me.esdex.coffer.feature.browser.viewer.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.ui.theme.PlayerStore
import me.esdex.coffer.feature.browser.preview.PreviewStore
import me.esdex.coffer.feature.browser.preview.keepWithFiles
import me.esdex.coffer.feature.browser.transfer.FileCache
import me.esdex.coffer.feature.browser.transfer.VaultContent
import me.esdex.coffer.feature.browser.transfer.isCached
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The waveform of the track playing, in place of the plain progress bar (Esdex, 2026-11-11). Worked out
 * from the whole track as soon as it starts, as fast as it comes in: the first try followed the player's
 * buffer, and Esdex found it too slow and stuck after a seek forward. So the track is downloaded whole on
 * its first play, and its pieces stay in the cache. Once done the waveform is kept on the phone, sealed
 * like a preview, and the next time shown whole from the start.
 */
@Singleton
class Waveforms @Inject constructor(
    @ApplicationContext context: Context,
    private val content: VaultContent,
    private val index: VaultIndex,
    private val cache: FileCache,
    playerStore: PlayerStore,
    telegram: Telegram,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreviewStore(dir(context))
    private val prefs = playerStore.prefs

    /** [bars] of [fileId] so far, all [WaveformBytes.BARS] of them once [complete]. */
    data class Shown(val fileId: Id, val bars: List<Float>, val complete: Boolean)

    private val shownFlow = MutableStateFlow<Shown?>(null)
    val shown: StateFlow<Shown?> = shownFlow.asStateFlow()

    // The track being worked out, and its reader.
    private var following: Id? = null
    private var job: Job? = null
    private var reading: VaultContent.OpenedFile? = null

    private val working: Boolean get() = job?.isActive == true

    init {
        scope.keepWithFiles(store, index, content.catchingUp, telegram)
        // Turned off, the ones kept go too.
        scope.launch {
            prefs.map { it.waveform }.distinctUntilChanged().collect { on ->
                if (!on) {
                    follow(null)
                    store.clear()
                }
            }
        }
    }

    /**
     * [held] is the track playing now, or null for none. Whatever was being worked out for another is
     * let go first: its reader is closed here, before its key can be wiped.
     */
    @Synchronized
    fun follow(held: VaultContent.HeldFile?) {
        // The same track again: left alone while it is worked out or done, started over if it stopped short.
        if (held != null && held.fileId == following && (working || shownFlow.value?.complete == true)) return
        job?.cancel()
        job = null
        reading?.close()
        reading = null
        following = held?.fileId
        shownFlow.value = null
        if (held == null || !prefs.value.waveform) return
        val fileId = held.fileId
        job = scope.launch { workOut(held, fileId) }
    }

    private suspend fun workOut(held: VaultContent.HeldFile, fileId: Id) {
        val kept = content.keys.value?.local?.let { sealer ->
            store.read(fileId)?.let { sealer.openWaveform(fileId, it) }?.let(WaveformBytes::decode)
        }
        if (kept != null) {
            Log.i(TAG, "waveform from the phone")
            show(Shown(fileId, kept, complete = true))
            return
        }
        val whole = index.file(fileId)?.isCached(cache.blobs.held.value) == true
        val started = System.currentTimeMillis()
        val opened = synchronized(this) {
            if (following != fileId) return
            runCatching { held.open() }.getOrNull()?.also { reading = it }
        } ?: return
        val bars = try {
            WaveformDecoder.decode(opened.reader) { partial -> show(Shown(fileId, partial, complete = false)) }
        } finally {
            synchronized(this) {
                if (reading === opened) reading = null
            }
            opened.close()
        }
        // Sizes and times only: never which track.
        if (bars == null) {
            Log.i(TAG, "waveform stopped after ${System.currentTimeMillis() - started} ms")
            return
        }
        Log.i(TAG, "waveform done in ${System.currentTimeMillis() - started} ms${if (whole) ", track on the phone" else ""}")
        show(Shown(fileId, bars, complete = true))
        // Locked meanwhile, or turned off: shown, not kept.
        val sealer = content.keys.value?.local ?: return
        if (prefs.value.waveform && following == fileId) store.write(fileId, sealer.sealWaveform(fileId, WaveformBytes.encode(bars)))
    }

    private fun show(shown: Shown) {
        if (following == shown.fileId) shownFlow.value = shown
    }

    companion object {
        /** Where waveforms are kept: no_backup, as they are this phone's alone; 120 bytes a track. */
        fun dir(context: Context) = File(context.noBackupFilesDir, "waveforms")

        private const val TAG = "CofferWave"
    }
}
