package me.esdex.coffer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.crypto.Cipher
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.Maintenance
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.tdlib.TelegramAccount
import me.esdex.coffer.core.ui.theme.Appearance
import me.esdex.coffer.core.ui.theme.AppearanceStore
import me.esdex.coffer.core.ui.theme.PlayerPrefs
import me.esdex.coffer.core.ui.theme.PlayerStore
import me.esdex.coffer.core.ui.theme.ThemeMode
import me.esdex.coffer.core.vault.DeviceUnlock
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.core.vault.VaultStore
import me.esdex.coffer.feature.browser.transfer.VaultContent
import me.esdex.coffer.feature.browser.transfer.readyStorage

/** The settings of SPEC §9, each read from where it lives. */
data class SettingsState(
    /** Null = auto-lock off. */
    val autoLockMinutes: Int? = null,
    val blockScreenshots: Boolean = false,
    val weeklyCheck: Boolean = true,
    val deviceUnlockAvailable: Boolean = false,
    val deviceUnlockOn: Boolean = false,
    val debugTools: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: VaultStore,
    private val device: DeviceUnlock,
    private val vault: VaultManager,
    private val content: VaultContent,
    private val telegram: Telegram,
    private val appearanceStore: AppearanceStore,
    private val playerStore: PlayerStore,
) : ViewModel() {

    val appearance: StateFlow<Appearance> = appearanceStore.appearance

    val player: StateFlow<PlayerPrefs> = playerStore.prefs

    fun setPlayer(change: (PlayerPrefs) -> PlayerPrefs) = playerStore.update(change)

    private val accountFlow = MutableStateFlow<TelegramAccount?>(null)

    /** Who is signed in, for the Account section; null until Telegram answers. */
    val account: StateFlow<TelegramAccount?> = accountFlow.asStateFlow()

    fun loadAccount() {
        viewModelScope.launch { accountFlow.value = runCatching { telegram.account() }.getOrNull() ?: accountFlow.value }
    }

    fun setThemeMode(mode: ThemeMode) = appearanceStore.update { it.copy(themeMode = mode) }

    fun setAmoled(on: Boolean) = appearanceStore.update { it.copy(amoled = on) }

    fun setDynamicColor(on: Boolean) = appearanceStore.update { it.copy(dynamicColor = on) }

    /** A long vault operation shown as a page of its own: running, then how it ended. */
    sealed interface Operation {
        val kind: Kind

        data class Running(override val kind: Kind) : Operation
        data class Done(override val kind: Kind, val report: Maintenance.Report?) : Operation
        data class Failed(override val kind: Kind, val message: Int) : Operation
    }

    enum class Kind { CHECK, RESCAN }

    private val operationFlow = MutableStateFlow<Operation?>(null)
    val operation: StateFlow<Operation?> = operationFlow.asStateFlow()

    private fun runOperation(kind: Kind, block: suspend () -> Maintenance.Report?) {
        if (operationFlow.value is Operation.Running) return
        operationFlow.value = Operation.Running(kind)
        viewModelScope.launch {
            operationFlow.value = try {
                Operation.Done(kind, block())
            } catch (_: FloodWaitException) {
                Operation.Failed(kind, R.string.settings_error_flood)
            } catch (_: StorageException) {
                Operation.Failed(kind, R.string.settings_error_network)
            }
        }
    }

    fun dismissOperation() {
        if (operationFlow.value !is Operation.Running) operationFlow.value = null
    }

    // DeviceUnlock keeps its state in a file of its own; this re-reads it after a change made here.
    private val deviceTick = MutableStateFlow(0)

    val state: StateFlow<SettingsState> = combine(store.changes, deviceTick) { _, _ ->
        SettingsState(store.autoLockMinutes, store.blockScreenshots, store.weeklyCheck, device.isAvailable(), device.isEnabled, store.debugTools)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    /** Messages read so far by a check or rescan in progress; null when idle. */
    val working: StateFlow<Int?> = content.catchingUp

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

    fun setDebugTools(on: Boolean) {
        store.debugTools = on
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

    fun checkIntegrity() = runOperation(Kind.CHECK) { content.checkIntegrity() }

    fun rescan() = runOperation(Kind.RESCAN) {
        content.rescan()
        null
    }

    // Password and recovery key (SPEC §9), both behind the current password.

    private val busyFlow = MutableStateFlow(false)

    /** Argon2id or Telegram at work for one of the two below. */
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    private val wrongPasswordFlow = MutableStateFlow(false)
    val wrongPassword: StateFlow<Boolean> = wrongPasswordFlow.asStateFlow()

    private val wordsFlow = MutableStateFlow<List<String>?>(null)

    /** The recovery key, while it is on screen. */
    val recoveryWords: StateFlow<List<String>?> = wordsFlow.asStateFlow()

    private val passwordChangedFlow = MutableStateFlow(false)

    /** The change went through: the page shows so until it is left. */
    val passwordChanged: StateFlow<Boolean> = passwordChangedFlow.asStateFlow()

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
        passwordChangedFlow.value = false
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
                        passwordChangedFlow.value = true
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
        /** Auto-lock delays on offer, in minutes. SPEC §9: 5 by default. */
        val AUTO_LOCK_DELAYS: List<Int> = listOf(1, 5, 15, 30, 60)
        const val DEFAULT_AUTO_LOCK = 5
    }
}
