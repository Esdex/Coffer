package me.esdex.coffer

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageEvent
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.DeviceUnlock
import me.esdex.coffer.core.vault.FoundHeader
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.core.vault.VaultStore
import me.esdex.coffer.feature.browser.preview.Previews
import me.esdex.coffer.feature.browser.transfer.VaultContent
import me.esdex.coffer.feature.browser.viewer.audio.AudioPlayback
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ties the vault to the rest of the app: looks for it once signed in, forgets it on sign-out
 * (SPEC §9), and locks it after a while in the background (SPEC §9: on, 5 minutes by default).
 */
@Singleton
class VaultLifecycle @Inject constructor(
    private val telegram: Telegram,
    private val vault: VaultManager,
    private val store: VaultStore,
    private val device: DeviceUnlock,
    private val content: VaultContent,
    private val playback: AudioPlayback,
    private val previews: Previews,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lockTimer: Job? = null
    private var backgroundSince: Long? = null

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        content.start()
        scope.launch {
            // Latest only: watching the headers ends with the session it belongs to.
            telegram.state.collectLatest { state ->
                when (state) {
                    AuthState.Ready -> {
                        val storage = runCatching { telegram.storage() }.getOrNull() ?: return@collectLatest
                        launch { runCatching { vault.locate(storage) } }
                        watchHeaders(storage)
                    }
                    // Closed only follows a log out or a reset of Telegram's data. The next state comes at
                    // once, and forgetting the vault must not be cut short by it.
                    AuthState.Closed -> withContext(NonCancellable) {
                        content.forget()
                        device.disable()
                        vault.forget()
                    }
                    else -> Unit
                }
            }
        }
        // The vault deleted, here or on another device (SPEC §5.9): nothing of it stays on this phone.
        // The previews and waveforms on disk follow the emptied index by themselves.
        scope.launch {
            vault.deleted.filterNotNull().collect {
                withContext(NonCancellable) {
                    playback.stop()
                    content.forget()
                    device.disable()
                    previews.clear()
                }
            }
        }
    }

    /**
     * A vault header arriving or a known one deleted means a password change (SPEC §4.1); the vault
     * re-reads its headers and, if it is open here and its header went, locks.
     */
    private suspend fun watchHeaders(storage: StorageBackend) {
        storage.events.collect { event ->
            val header = when (event) {
                is StorageEvent.NewMessage -> FoundHeader.from(event.message) != null
                is StorageEvent.MessagesDeleted -> vault.knowsHeader(event.ids)
            }
            if (header) runCatching { vault.headersChanged(storage) }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        content.onBackground()
        val minutes = store.autoLockMinutes ?: return
        backgroundSince = SystemClock.elapsedRealtime()
        lockTimer = scope.launch {
            delay(minutes * 60_000L)
            vault.lockVault()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        lockTimer?.cancel()
        content.onForeground()
        // The minute timer does not run while the process is frozen either.
        content.sweepOpenFiles()
        // The timer does not run while Android has the process frozen, so check the clock too.
        val since = backgroundSince ?: return
        backgroundSince = null
        val minutes = store.autoLockMinutes ?: return
        if (SystemClock.elapsedRealtime() - since >= minutes * 60_000L) scope.launch { vault.lockVault() }
    }
}
