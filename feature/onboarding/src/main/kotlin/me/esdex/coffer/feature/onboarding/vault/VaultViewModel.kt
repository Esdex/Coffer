package me.esdex.coffer.feature.onboarding.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.esdex.coffer.core.crypto.bip39.Bip39
import me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.DeviceUnlock
import me.esdex.coffer.core.vault.NewVault
import me.esdex.coffer.core.vault.RecoveryQuiz
import me.esdex.coffer.core.vault.VaultExistsException
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.core.vault.VaultStore
import me.esdex.coffer.core.vault.VaultUnlock
import javax.crypto.Cipher
import javax.inject.Inject

sealed interface VaultError {
    data object WrongPassword : VaultError
    data class BadRecoveryKey(val reason: InvalidMnemonicException.Reason) : VaultError
    data object OtherVaultsKey : VaultError
    data object WordsDoNotMatch : VaultError
    data object PasswordsDiffer : VaultError
    data object VaultAppeared : VaultError
    data object DeviceKeyFailed : VaultError
    data object DeviceKeyInvalidated : VaultError
    data class Storage(val message: String) : VaultError
}

/** Steps of creating a vault (SPEC §7, step 4). */
sealed interface CreateStep {
    data object Password : CreateStep
    data class RecoveryKey(val words: List<String>) : CreateStep
    data class Confirm(val quiz: RecoveryQuiz) : CreateStep
    data object DeviceUnlock : CreateStep
    data object Publishing : CreateStep

    /** Bringing back a vault whose header is gone, with its recovery key (audit 1, M1): the words first. */
    data object RestoreWords : CreateStep

    /** Then a new password for it. The words stay in the view model, not in the step. */
    data object RestorePassword : CreateStep
}

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val vault: VaultManager,
    private val telegram: Telegram,
    private val device: DeviceUnlock,
    private val store: VaultStore,
) : ViewModel() {

    val state: StateFlow<VaultManager.State> = vault.state

    /** The vault before this one was deleted, here or on another device: said over the screen that makes a new one. */
    val deleted: StateFlow<VaultManager.Deleted?> = vault.deleted

    /** The screenshot setting, for the window to go back to once the recovery key is off screen. */
    val blockScreenshots: Boolean get() = store.blockScreenshots

    /** Out of Telegram instead of making a vault: to sign in with another account (Esdex, 2026-11-12). */
    fun logOut() {
        viewModelScope.launch { runCatching { telegram.logOut() } }
    }

    private val createFlow = MutableStateFlow<CreateStep>(CreateStep.Password)
    val createStep: StateFlow<CreateStep> = createFlow.asStateFlow()

    private val busyFlow = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    private val errorFlow = MutableStateFlow<VaultError?>(null)
    val error: StateFlow<VaultError?> = errorFlow.asStateFlow()

    /** Holds the new master key until it is published; closed if the user leaves first. */
    private var pending: NewVault? = null

    val deviceUnlockAvailable: Boolean get() = device.isAvailable()
    val deviceUnlockEnabled: Boolean
        get() {
            // The password was changed on another device: the fingerprint goes until the new one is typed,
            // and the offer to turn it on comes again after that (audit 1, M7).
            if (store.deviceUnlockStale) {
                device.disable()
                store.biometricOffered = false
                store.deviceUnlockStale = false
            }
            return device.isEnabled
        }

    private val offerFlow = MutableStateFlow(false)

    /** After an unlock with password or recovery key, offer biometric unlock once (SPEC §3.8). */
    val offerDeviceUnlock: StateFlow<Boolean> = offerFlow.asStateFlow()

    private val noticeFlow = MutableStateFlow(!store.savedMessagesNoticeSeen)

    /**
     * Not to delete Coffer's messages in Telegram: said once per install, as the vault first opens here,
     * made or found (Esdex, 2026-11-13). Moved out of the welcome, which says where the files are, not this.
     */
    val showSavedMessagesNotice: StateFlow<Boolean> = noticeFlow.asStateFlow()

    fun savedMessagesNoticeRead() {
        store.savedMessagesNoticeSeen = true
        noticeFlow.value = false
    }

    private fun offerIfUseful() {
        offerFlow.value = !device.isEnabled && !store.biometricOffered && device.isAvailable()
    }

    private fun perform(action: suspend () -> Unit) {
        if (busyFlow.value) return
        viewModelScope.launch {
            busyFlow.value = true
            errorFlow.value = null
            try {
                action()
            } catch (e: StorageException) {
                errorFlow.value = VaultError.Storage(e.message.orEmpty())
            } finally {
                busyFlow.value = false
            }
        }
    }

    fun dismissError() {
        errorFlow.value = null
    }

    // Unlocking

    fun unlockWithPassword(password: String) = perform {
        val storage = runCatching { telegram.storage() }.getOrNull()
        if (vault.unlockWithPassword(password, storage)) offerIfUseful() else errorFlow.value = VaultError.WrongPassword
    }

    fun unlockWithRecoveryKey(text: String) = perform {
        try {
            val storage = runCatching { telegram.storage() }.getOrNull()
            if (vault.unlockWithRecoveryKey(Bip39.split(text), storage) == VaultUnlock.MnemonicResult.OtherVault) {
                errorFlow.value = VaultError.OtherVaultsKey
            } else {
                offerIfUseful()
            }
        } catch (e: InvalidMnemonicException) {
            errorFlow.value = VaultError.BadRecoveryKey(e.reason)
        }
    }

    /** A cipher for the unlock prompt, or null when there is nothing to prompt for. */
    fun deviceUnlockCipher(): Cipher? {
        val expected = vault.expectedMasterId() ?: return null
        return when (val prepared = device.unlockCipher(expected)) {
            is DeviceUnlock.Prepared.Ready -> prepared.cipher
            DeviceUnlock.Prepared.NotEnabled -> null
            DeviceUnlock.Prepared.Invalidated -> {
                // SPEC §3.8: back to the password, then wrap again. The offer after the unlock does that.
                store.biometricOffered = false
                errorFlow.value = VaultError.DeviceKeyInvalidated
                null
            }
        }
    }

    fun completeDeviceUnlock(cipher: Cipher) = perform {
        val master = device.completeUnlock(cipher)
        if (master == null || !vault.unlockWithDeviceKey(master)) errorFlow.value = VaultError.DeviceKeyFailed
    }

    // Offering biometric unlock after a password or recovery key unlock

    fun deviceUnlockEnableCipher(): Cipher? = runCatching { device.enableCipher() }.getOrNull()

    fun completeEnableDeviceUnlock(cipher: Cipher) {
        val open = (vault.state.value as? VaultManager.State.Unlocked)?.vault ?: return
        runCatching { device.completeEnable(cipher, open) }.onFailure { device.disable() }
        store.biometricOffered = true
        offerFlow.value = false
    }

    fun declineDeviceUnlock() {
        store.biometricOffered = true
        offerFlow.value = false
    }

    fun lock() = perform { vault.lockVault() }

    // Creating

    fun createWithPassword(password: String, repeated: String) = perform {
        if (password != repeated) {
            errorFlow.value = VaultError.PasswordsDiffer
            return@perform
        }
        pending?.close()
        val created = vault.prepareNew(password)
        pending = created
        createFlow.value = CreateStep.RecoveryKey(created.recoveryWords)
    }

    // Restoring

    /** The recovery key typed in for a restore, kept only until the vault is back. */
    private var restoreWords: List<String>? = null

    fun startRestore() {
        errorFlow.value = null
        createFlow.value = CreateStep.RestoreWords
    }

    fun cancelRestore() {
        restoreWords = null
        errorFlow.value = null
        createFlow.value = CreateStep.Password
    }

    fun restoreWordsEntered(text: String) {
        val words = Bip39.split(text)
        try {
            // Only checked here; the key itself is made with the new password.
            Bip39.toMasterKey(words).close()
        } catch (e: InvalidMnemonicException) {
            errorFlow.value = VaultError.BadRecoveryKey(e.reason)
            return
        }
        restoreWords = words
        errorFlow.value = null
        createFlow.value = CreateStep.RestorePassword
    }

    fun restoreWithPassword(password: String, repeated: String) = perform {
        val words = restoreWords ?: return@perform
        if (password != repeated) {
            errorFlow.value = VaultError.PasswordsDiffer
            return@perform
        }
        createFlow.value = CreateStep.Publishing
        val restored = vault.prepareRestore(words, password)
        try {
            vault.publish(telegram.storage(), restored, restoring = true)
        } catch (e: StorageException) {
            createFlow.value = CreateStep.RestorePassword
            throw e
        }
        restoreWords = null
        createFlow.value = CreateStep.Password
        offerIfUseful()
    }

    fun recoveryKeyWrittenDown() {
        val words = pending?.recoveryWords ?: return
        createFlow.value = CreateStep.Confirm(RecoveryQuiz(words))
    }

    fun showRecoveryKeyAgain() {
        val words = pending?.recoveryWords ?: return
        errorFlow.value = null
        createFlow.value = CreateStep.RecoveryKey(words)
    }

    fun confirmWords(quiz: RecoveryQuiz, answers: List<String>) {
        if (!quiz.check(answers)) {
            errorFlow.value = VaultError.WordsDoNotMatch
            return
        }
        errorFlow.value = null
        if (device.isAvailable()) createFlow.value = CreateStep.DeviceUnlock else publish()
    }

    /** Step 2 of enabling biometric unlock for the vault being created. */
    fun completeEnableForNewVault(cipher: Cipher) {
        val created = pending ?: return
        runCatching { device.completeEnable(cipher, created.vault) }.onFailure { device.disable() }
        store.biometricOffered = true
        publish()
    }

    fun skipDeviceUnlockForNewVault() {
        store.biometricOffered = true
        publish()
    }

    private fun publish() = perform {
        val created = pending ?: return@perform
        createFlow.value = CreateStep.Publishing
        try {
            vault.publish(telegram.storage(), created)
            pending = null
        } catch (_: VaultExistsException) {
            pending = null
            device.disable()
            errorFlow.value = VaultError.VaultAppeared
            createFlow.value = CreateStep.Password
            vault.locate(telegram.storage())
        } catch (e: StorageException) {
            // publish closed the new vault; start over.
            pending = null
            device.disable()
            createFlow.value = CreateStep.Password
            throw e
        }
    }

    override fun onCleared() {
        pending?.close()
        pending = null
    }
}
