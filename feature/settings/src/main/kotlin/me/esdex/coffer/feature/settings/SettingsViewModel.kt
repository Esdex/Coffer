package me.esdex.coffer.feature.settings

import androidx.compose.ui.graphics.ImageBitmap
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
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.Maintenance
import me.esdex.coffer.core.sync.VaultDeletion
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.tdlib.TelegramAccount
import me.esdex.coffer.core.ui.theme.Appearance
import me.esdex.coffer.core.ui.theme.AppearanceStore
import me.esdex.coffer.core.ui.theme.PlayerPrefs
import me.esdex.coffer.core.ui.theme.PlayerStore
import me.esdex.coffer.core.ui.theme.TextPrefs
import me.esdex.coffer.core.ui.theme.TextStore
import me.esdex.coffer.core.sync.Census
import java.io.IOException
import me.esdex.coffer.feature.browser.stats.Breakdown
import me.esdex.coffer.feature.browser.stats.FileKind
import me.esdex.coffer.feature.browser.stats.PhoneUsage
import me.esdex.coffer.feature.browser.stats.VaultStats
import me.esdex.coffer.feature.browser.preview.Previews
import me.esdex.coffer.feature.browser.transfer.FileCache
import me.esdex.coffer.core.ui.theme.CachePrefs
import me.esdex.coffer.core.ui.theme.CacheStore
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
    val hideSizes: Boolean = true,
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
    private val textStore: TextStore,
    private val stats: VaultStats,
    private val cache: FileCache,
    private val cacheStore: CacheStore,
    private val previews: Previews,
) : ViewModel() {

    val appearance: StateFlow<Appearance> = appearanceStore.appearance

    val player: StateFlow<PlayerPrefs> = playerStore.prefs

    fun setPlayer(change: (PlayerPrefs) -> PlayerPrefs) = playerStore.update(change)

    val text: StateFlow<TextPrefs> = textStore.prefs

    fun setText(change: (TextPrefs) -> TextPrefs) = textStore.update(change)

    // ── Storage and the phone (pre-release item 6) ──────────────────────────

    /** The vault by kind; null while locked or not yet worked out. */
    val breakdown: StateFlow<Breakdown?> = stats.breakdown.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun filesOf(kind: FileKind) = stats.filesOf(kind)

    /** A file's preview if the files list has one in memory, for the list of a kind. */
    fun preview(fileId: Id): ImageBitmap? = previews.cached(fileId)

    /** Counting Saved Messages: how far it got, then what it found; null before it is asked for. */
    sealed interface Counting {
        data class Running(val read: Int) : Counting
        data class Done(val census: Census) : Counting
        data object Failed : Counting
    }

    private val countingFlow = MutableStateFlow<Counting?>(null)
    val counting: StateFlow<Counting?> = countingFlow.asStateFlow()

    /** Reads the whole of Saved Messages and counts it, unless that is already being done. */
    fun count() {
        if (countingFlow.value is Counting.Running) return
        countingFlow.value = Counting.Running(0)
        viewModelScope.launch {
            val census = try {
                stats.census { read -> countingFlow.value = Counting.Running(read) }
            } catch (_: StorageException) {
                null
            } catch (_: IOException) {
                null
            }
            countingFlow.value = census?.let(Counting::Done) ?: Counting.Failed
        }
    }

    private val phoneFlow = MutableStateFlow<PhoneUsage?>(null)

    /** What Coffer takes up on the phone, as last measured. */
    val phone: StateFlow<PhoneUsage?> = phoneFlow.asStateFlow()

    fun measurePhone() {
        viewModelScope.launch { phoneFlow.value = stats.phone() }
    }

    val cachePrefs: StateFlow<CachePrefs> = cache.prefs

    /** A new setting for the cache, and the cache brought into line: a lower limit or the cache off lets pieces go. */
    fun setCache(change: (CachePrefs) -> CachePrefs) {
        val before = cache.prefs.value
        cacheStore.update(change)
        val after = cache.prefs.value
        if (after.enabled && after.limitMb >= before.limitMb && before.enabled) return
        viewModelScope.launch {
            telegram.readyStorage()?.let { cache.blobs.trim(it) }
            phoneFlow.value = stats.phone()
        }
    }

    /** Previews on or off; off deletes the ones made, and the figure here follows. */
    fun setPreviews(on: Boolean) {
        cacheStore.update { it.copy(previews = on) }
        if (on) return
        viewModelScope.launch {
            previews.clear()
            phoneFlow.value = stats.phone()
        }
    }

    /** Lets go of every cached piece but those of a file open right now. */
    fun clearCache() {
        viewModelScope.launch {
            telegram.readyStorage()?.let { cache.clear(it) }
            phoneFlow.value = stats.phone()
        }
    }

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

    enum class Kind { CHECK, RESCAN, DELETE }

    private val operationFlow = MutableStateFlow<Operation?>(null)
    val operation: StateFlow<Operation?> = operationFlow.asStateFlow()

    private fun runOperation(kind: Kind, block: suspend () -> Maintenance.Report?) {
        if (operationFlow.value is Operation.Running) return
        operationFlow.value = Operation.Running(kind)
        viewModelScope.launch {
            operationFlow.value = try {
                val report = block()
                // A deleted vault takes the app back to the first-start screens; nothing to show here after.
                if (kind == Kind.DELETE) null else Operation.Done(kind, report)
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
        SettingsState(store.autoLockMinutes, store.blockScreenshots, store.weeklyCheck, store.hideSizes, device.isAvailable(), device.isEnabled, store.debugTools)
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

    fun setHideSizes(on: Boolean) {
        store.hideSizes = on
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

    init {
        // A lock forgets the words on screen: after the next unlock, a fingerprint one too, the page must ask
        // for the password again (SPEC §9, audit 1, AND-3). This view model outlives the lock.
        viewModelScope.launch { vault.state.collect { if (it !is VaultManager.State.Unlocked) leaveSecrets() } }
    }

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

    // Deleting the vault (SPEC §5.9), behind the current password like the two above.

    /** How far a deletion is: Saved Messages read, then the vault's messages deleted of how many. */
    data class Deleting(val read: Int = 0, val deleted: Int? = null, val total: Int = 0, val waitSeconds: Int? = null)

    private val deletingFlow = MutableStateFlow<Deleting?>(null)
    val deleting: StateFlow<Deleting?> = deletingFlow.asStateFlow()

    /**
     * Checks [password], then deletes the vault: syncing and transfers stop first, so nothing new is sent
     * while the history is read; then every message of the vault goes, its headers last. Done, the vault
     * is gone and the first-start screens offer a new one. Cut short, the vault stays open and is read in
     * afresh from what is left; the deletion can be run again.
     */
    fun deleteVault(password: String) {
        if (busyFlow.value || operationFlow.value is Operation.Running) return
        busyFlow.value = true
        viewModelScope.launch {
            val right = try {
                vault.checkPassword(password)
            } finally {
                busyFlow.value = false
            }
            wrongPasswordFlow.value = !right
            if (!right) return@launch
            val keys = content.keys.value ?: return@launch
            deletingFlow.value = Deleting()
            runOperation(Kind.DELETE) {
                try {
                    content.forget()
                    val storage = telegram.readyStorage() ?: throw StorageException("not signed in")
                    vault.delete { masterId ->
                        VaultDeletion(storage, keys, masterId).run(
                            onRead = { read -> deletingFlow.value = Deleting(read = read) },
                            onDeleted = { done, total -> deletingFlow.value = deletingFlow.value?.copy(deleted = done, total = total, waitSeconds = null) },
                            onWait = { seconds -> deletingFlow.value = deletingFlow.value?.copy(waitSeconds = seconds) },
                        )
                    }
                } catch (e: StorageException) {
                    content.reopen()
                    throw e
                } finally {
                    deletingFlow.value = null
                }
                null
            }
        }
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
