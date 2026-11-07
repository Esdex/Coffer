package me.esdex.coffer

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.DeviceUnlock
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.core.vault.VaultStore
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
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lockTimer: Job? = null
    private var backgroundSince: Long? = null

    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            telegram.state.collect { state ->
                when (state) {
                    AuthState.Ready -> launch { runCatching { vault.locate(telegram.storage()) } }
                    // Closed only follows a log out or a reset of Telegram's data.
                    AuthState.Closed -> {
                        device.disable()
                        vault.forget()
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        val minutes = store.autoLockMinutes ?: return
        backgroundSince = SystemClock.elapsedRealtime()
        lockTimer = scope.launch {
            delay(minutes * 60_000L)
            vault.lockVault()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        lockTimer?.cancel()
        // The timer does not run while Android has the process frozen, so check the clock too.
        val since = backgroundSince ?: return
        backgroundSince = null
        val minutes = store.autoLockMinutes ?: return
        if (SystemClock.elapsedRealtime() - since >= minutes * 60_000L) scope.launch { vault.lockVault() }
    }
}
