package me.esdex.coffer.core.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.crypto.bip39.Bip39
import me.esdex.coffer.core.crypto.format.VaultHeaderCodec
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.storage.StorageBackend
import kotlin.coroutines.CoroutineContext

/**
 * The vault on this device: finding it, creating it, unlocking and locking (SPEC §3, §4.1, §7).
 * Plain Kotlin; the Keystore side of biometric unlock is [DeviceUnlock], driven by the UI.
 */
class VaultManager(
    private val sodium: Sodium,
    private val store: VaultStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Argon2id and key derivation run here, off the main thread. */
    private val work: CoroutineContext = Dispatchers.Default,
) {
    sealed interface State {
        /** Not known yet whether Saved Messages holds a vault. */
        data object Unknown : State

        data class Searching(val scanned: Int) : State

        data object NotFound : State

        /** A vault exists; [masterId] tells which, so a device-unlocked key can be checked against it. */
        data class Locked(val masterId: Bytes) : State

        data class Unlocked(val vault: UnlockedVault) : State
    }

    private val lock = Mutex()
    private var headers: List<FoundHeader> = store.headers()

    /** Counts writes of [headers], so a search that took a while can tell it was overtaken. */
    private var headerWrites = 0L
    private val stateFlow = MutableStateFlow(lockedOrUnknown())
    val state: StateFlow<State> = stateFlow.asStateFlow()

    /** How long the last password check took, for calibrating Argon2id (SPEC §3.3). */
    @Volatile
    var lastKdfMillis: Long? = null
        private set

    private fun lockedOrUnknown(): State = headers.firstOrNull()?.let { State.Locked(it.header.masterId) } ?: State.Unknown

    /**
     * Looks for the vault in Saved Messages. With headers already known it only refreshes them, so a
     * password changed on another device is picked up.
     */
    suspend fun locate(storage: StorageBackend) {
        val (firstTime, startedAt) = lock.withLock { (headers.isEmpty() && stateFlow.value !is State.Unlocked) to headerWrites }
        if (firstTime) stateFlow.value = State.Searching(0)
        val found = VaultHeaders.find(storage) { scanned -> if (firstTime) stateFlow.value = State.Searching(scanned) }
        lock.withLock {
            // Headers were written while this search was out (a password change, a new vault): what it
            // found may be older than that, and the newer headers stay.
            if (headerWrites != startedAt) {
                // Whoever wrote also set the state; only a search screen this one put up is left to undo.
                if (stateFlow.value is State.Searching) stateFlow.value = lockedOrUnknown()
                return
            }
            val current = stateFlow.value
            if (current is State.Unlocked) {
                // Keep only headers of the open vault; another vault's header must not replace it.
                val same = found.filter { it.header.masterId == current.vault.masterId }
                if (same.isNotEmpty()) remember(same)
                return
            }
            if (found.isEmpty()) {
                if (headers.isEmpty()) stateFlow.value = State.NotFound
                return
            }
            remember(found)
            stateFlow.value = lockedOrUnknown()
        }
    }

    private fun remember(found: List<FoundHeader>) {
        headers = found
        headerWrites++
        store.saveHeaders(found)
    }

    /**
     * Unlocks with the vault password. If it fails and [storage] is given, the headers are fetched
     * again once and the password retried, in case it was changed on another device.
     */
    suspend fun unlockWithPassword(password: CharSequence, storage: StorageBackend? = null): Boolean {
        if (tryPassword(password)) return true
        if (storage == null) return false
        val before = lock.withLock { headers }
        locate(storage)
        return lock.withLock { headers } != before && tryPassword(password)
    }

    private suspend fun tryPassword(password: CharSequence): Boolean {
        val candidates = lock.withLock { headers }
        val started = System.nanoTime()
        val vault = withContext(work) { VaultUnlock.withPassword(sodium, candidates, password) }
        lastKdfMillis = (System.nanoTime() - started) / 1_000_000
        return vault != null && open(vault)
    }

    /** Throws [me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException] for words that are not a recovery key. */
    suspend fun unlockWithRecoveryKey(words: List<String>): VaultUnlock.MnemonicResult {
        val candidates = lock.withLock { headers }
        val result = withContext(work) { VaultUnlock.withMnemonic(sodium, candidates, words) }
        if (result is VaultUnlock.MnemonicResult.Unlocked && !open(result.vault)) return VaultUnlock.MnemonicResult.OtherVault
        return result
    }

    /** With the master key the Keystore gave back after biometric or screen lock (SPEC §3.8). Takes ownership. */
    suspend fun unlockWithDeviceKey(master: SecretKey): Boolean {
        val vault = withContext(work) { UnlockedVault.open(sodium, master) }
        return open(vault)
    }

    private suspend fun open(vault: UnlockedVault): Boolean = lock.withLock {
        val current = stateFlow.value
        val expected = headers.firstOrNull()?.header?.masterId
        if (current is State.Unlocked || vault.masterId != expected) {
            vault.close()
            return current is State.Unlocked && current.vault.masterId == vault.masterId
        }
        stateFlow.value = State.Unlocked(vault)
        true
    }

    /** A new vault under [password]; shown and confirmed before [publish]. */
    suspend fun prepareNew(password: CharSequence): NewVault =
        withContext(work) { VaultCreation.prepare(sodium, password, clock()) }

    /** Sends and pins the header, then opens the vault. Takes ownership of [vault]. */
    suspend fun publish(storage: StorageBackend, vault: NewVault) {
        val published = try {
            VaultHeaders.publish(storage, vault)
        } catch (e: Exception) {
            vault.close()
            throw e
        }
        lock.withLock {
            remember(listOf(published))
            stateFlow.value = State.Unlocked(vault.vault)
        }
    }

    /**
     * True if [password] opens the vault that is unlocked now: asked before showing the recovery key
     * or changing the password, so an unlocked phone alone is not enough for either.
     */
    suspend fun checkPassword(password: CharSequence): Boolean {
        val open = (stateFlow.value as? State.Unlocked)?.vault ?: return false
        val candidates = lock.withLock { headers }
        val vault = withContext(work) { VaultUnlock.withPassword(sodium, candidates, password) } ?: return false
        return vault.use { it.masterId == open.masterId }
    }

    /** The recovery key of the open vault (SPEC §3.7), or null while locked. */
    fun recoveryWords(): List<String>? = (stateFlow.value as? State.Unlocked)?.vault?.withMasterKey(Bip39::toMnemonic)

    sealed interface PasswordChange {
        data object Changed : PasswordChange
        data object WrongPassword : PasswordChange
        data object Locked : PasswordChange
    }

    /**
     * SPEC §4.1: a new header under [newPassword] is sent and pinned, then every older header of this
     * vault is deleted. Interrupted after the send, both passwords keep working: unlocking tries every
     * header with the same master_id. Other devices pick up the new header on their next unlock.
     */
    suspend fun changePassword(storage: StorageBackend, current: CharSequence, newPassword: CharSequence): PasswordChange {
        if (!checkPassword(current)) {
            return if (stateFlow.value is State.Unlocked) PasswordChange.WrongPassword else PasswordChange.Locked
        }
        val open = (stateFlow.value as? State.Unlocked)?.vault ?: return PasswordChange.Locked
        val header = withContext(work) { VaultCreation.rewrap(sodium, open, newPassword, clock()) }
        val sent = storage.sendText(VaultHeaderCodec.encode(header))
        storage.pin(sent.id)
        val published = FoundHeader(sent.id, header)
        // What this device knew, and what Saved Messages holds now, in case another device added one.
        val old = (lock.withLock { headers } + VaultHeaders.find(storage))
            .filter { it.header.masterId == open.masterId && it.messageId != sent.id }
            .map { it.messageId }
            .distinct()
        lock.withLock { remember(listOf(published)) }
        if (old.isNotEmpty()) storage.delete(old)
        return PasswordChange.Changed
    }

    /** Wipes the keys from memory (SPEC §3.8). */
    suspend fun lockVault() = lock.withLock {
        (stateFlow.value as? State.Unlocked)?.vault?.close()
        stateFlow.value = lockedOrUnknown()
    }

    /** After signing out of Telegram: lock and forget the vault on this device. */
    suspend fun forget() = lock.withLock {
        (stateFlow.value as? State.Unlocked)?.vault?.close()
        headers = emptyList()
        store.clear()
        stateFlow.value = State.Unknown
    }

    /** The master_id the Keystore-wrapped key must match. */
    fun expectedMasterId(): Bytes? = headers.firstOrNull()?.header?.masterId
}
