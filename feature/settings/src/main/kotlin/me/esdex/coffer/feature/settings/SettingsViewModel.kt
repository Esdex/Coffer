package me.esdex.coffer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.Maintenance
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.DeviceUnlock
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.core.vault.VaultStore
import me.esdex.coffer.feature.browser.transfer.VaultContent
import me.esdex.coffer.feature.browser.transfer.readyStorage
import javax.crypto.Cipher
import javax.inject.Inject

/** The settings of SPEC §9, each read from where it lives. */
data class SettingsState(
    /** Null = auto-lock off. */
    val autoLockMinutes: Int? = null,
    val blockScreenshots: Boolean = false,
    val weeklyCheck: Boolean = true,
    val deviceUnlockAvailable: Boolean = false,
    val deviceUnlockOn: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: VaultStore,
    private val device: DeviceUnlock,
    private val vault: VaultManager,
    private val content: VaultContent,
    private val telegram: Telegram,
) : ViewModel() {

    // DeviceUnlock keeps its state in a file of its own; this re-reads it after a change made here.
    private val deviceTick = MutableStateFlow(0)

    val state: StateFlow<SettingsState> = combine(store.changes, deviceTick) { _, _ ->
        SettingsState(store.autoLockMinutes, store.blockScreenshots, store.weeklyCheck, device.isAvailable(), device.isEnabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    /** Messages read so far by a check or rescan in progress; null when idle. */
    val working: StateFlow<Int?> = content.catchingUp

    private val integrityFlow = MutableStateFlow<Maintenance.Report?>(null)

    /** The last integrity check started here, until its result is dismissed. */
    val integrity: StateFlow<Maintenance.Report?> = integrityFlow.asStateFlow()

    // A channel, not a shared flow: a notice sent while a sub-page is open waits for the main page.
    private val messagesFlow = Channel<Int>(Channel.BUFFERED)

    /** Short notices for a snackbar, as string resources. */
    val messages: Flow<Int> = messagesFlow.receiveAsFlow()

    fun setAutoLock(minutes: Int?) {
        store.autoLockMinutes = minutes
    }

    fun setBlockScreenshots(on: Boolean) {
        store.blockScreenshots = on
    }

    fun setWeeklyCheck(on: Boolean) {
        store.weeklyCheck = on
    }

    /** Step 1 of turning screen-lock unlock on: the cipher for the system prompt, or null if Keystore refuses. */
    fun deviceUnlockCipher(): Cipher? = runCatching { device.enableCipher() }.getOrNull()

    /** Step 2, after the prompt: wraps the open vault's master key. */
    fun completeDeviceUnlock(cipher: Cipher) {
        val open = (vault.state.value as? VaultManager.State.Unlocked)?.vault
        if (open == null) {
            device.disable()
        } else {
            runCatching { device.completeEnable(cipher, open) }.onFailure { device.disable() }
        }
        // Asked and answered here: the offer after a password unlock is not needed any more.
        store.biometricOffered = true
        deviceTick.value++
    }

    fun disableDeviceUnlock() {
        device.disable()
        deviceTick.value++
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: FloodWaitException) {
                messagesFlow.send(R.string.settings_error_flood)
            } catch (_: StorageException) {
                messagesFlow.send(R.string.settings_error_network)
            }
        }
    }

    fun checkIntegrity() = act { integrityFlow.value = content.checkIntegrity() }

    fun dismissIntegrity() {
        integrityFlow.value = null
    }

    fun rescan() = act { content.rescan() }

    // Password and recovery key (SPEC §9), both behind the current password.

    private val busyFlow = MutableStateFlow(false)

    /** Argon2id or Telegram at work for one of the two below. */
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    private val wrongPasswordFlow = MutableStateFlow(false)
    val wrongPassword: StateFlow<Boolean> = wrongPasswordFlow.asStateFlow()

    private val wordsFlow = MutableStateFlow<List<String>?>(null)

    /** The recovery key, while it is on screen. */
    val recoveryWords: StateFlow<List<String>?> = wordsFlow.asStateFlow()

    private val passwordChangedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val passwordChanged: SharedFlow<Unit> = passwordChangedFlow.asSharedFlow()

    fun showRecoveryKey(password: String) {
        if (busyFlow.value) return
        busyFlow.value = true
        viewModelScope.launch {
            try {
                if (vault.checkPassword(password)) {
                    wrongPasswordFlow.value = false
                    wordsFlow.value = vault.recoveryWords()
                } else {
                    wrongPasswordFlow.value = true
                }
            } finally {
                busyFlow.value = false
            }
        }
    }

    /** Leaving the recovery key or password page forgets what it held. */
    fun leaveSecrets() {
        wordsFlow.value = null
        wrongPasswordFlow.value = false
    }

    fun changePassword(current: String, newPassword: String) {
        if (busyFlow.value) return
        busyFlow.value = true
        act {
            try {
                val storage = telegram.readyStorage() ?: throw StorageException("not signed in")
                when (vault.changePassword(storage, current, newPassword)) {
                    VaultManager.PasswordChange.Changed -> {
                        wrongPasswordFlow.value = false
                        passwordChangedFlow.emit(Unit)
                        messagesFlow.send(R.string.settings_password_changed)
                    }
                    VaultManager.PasswordChange.WrongPassword -> wrongPasswordFlow.value = true
                    VaultManager.PasswordChange.Locked -> Unit
                }
            } finally {
                busyFlow.value = false
            }
        }
    }

    override fun onCleared() {
        wordsFlow.value = null
    }

    fun logOut() {
        viewModelScope.launch { runCatching { telegram.logOut() } }
    }

    companion object {
        /** Choices for auto-lock, in minutes; null is off. SPEC §9: 5 by default. */
        val AUTO_LOCK_CHOICES: List<Int?> = listOf(null, 1, 5, 15, 30, 60)
    }
}
