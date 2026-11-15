package me.esdex.coffer.core.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.crypto.bip39.Bip39
import me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException
import me.esdex.coffer.core.crypto.format.VaultHeaderCodec
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
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
    /** What a new password must be: checked here as well as on screen (audit 1, CRYPTO-10). */
    private val acceptPassword: (CharSequence) -> Boolean = { PasswordPolicy.check(it).acceptable },
) {
    sealed interface State {
        /** Not known yet whether Saved Messages holds a vault. */
        data object Unknown : State

        data class Searching(val scanned: Int) : State

        data object NotFound : State

        /**
         * A vault exists; [masterId] tells which, so a device-unlocked key can be checked against it.
         * [passwordChangedElsewhere]: it was open here and locked because another device changed the password.
         */
        data class Locked(val masterId: Bytes, val passwordChangedElsewhere: Boolean = false) : State

        data class Unlocked(val vault: UnlockedVault) : State
    }

    /** Why there is no vault any more, for the screen that offers a new one (SPEC §5.9). */
    enum class Deleted { HERE, ELSEWHERE }

    private val lock = Mutex()
    private var headers: List<FoundHeader> = store.headers()

    private val deletedFlow = MutableStateFlow<Deleted?>(null)

    /** Set once the vault is deleted, here or on another device; cleared by a new vault or a sign-out. */
    val deleted: StateFlow<Deleted?> = deletedFlow.asStateFlow()

    // While this device deletes the vault, its own deletes come back as events: not a deletion elsewhere.
    @Volatile
    private var deleting = false

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
        val known = lock.withLock { knownMasterId() }
        if (firstTime) stateFlow.value = State.Searching(0)
        val found = VaultHeaders.find(storage, known) { scanned -> if (firstTime) stateFlow.value = State.Searching(scanned) }
        // A device that was off while the vault was deleted elsewhere finds out here, at its next start.
        val deletedElsewhere = deletedElsewhere(storage, found)
        lock.withLock {
            // Headers were written while this search was out (a password change, a new vault): what it
            // found may be older than that, and the newer headers stay.
            if (headerWrites != startedAt) {
                // Whoever wrote also set the state; only a search screen this one put up is left to undo.
                if (stateFlow.value is State.Searching) stateFlow.value = lockedOrUnknown()
                return
            }
            if (deletedElsewhere && !deleting) {
                forgetDeletedLocked(Deleted.ELSEWHERE)
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
            // A vault this device knows stays the one it looks for: headers of another vault, which anyone
            // in the Telegram account can post, do not take its place (audit 1, M2). A real deletion is
            // told apart above, by Telegram confirming the known headers are gone.
            val known = knownMasterId()
            val mine = if (known == null) found else found.filter { it.header.masterId == known }
            if (mine.isEmpty()) return
            // Off while the password changed elsewhere: it says so on the unlock screen, and the fingerprint goes.
            val changed = passwordChanged(headers.map { it.messageId }.toSet() - mine.map { it.messageId }.toSet())
            remember(mine)
            val saidSo = (stateFlow.value as? State.Locked)?.passwordChangedElsewhere == true
            stateFlow.value = headers.first().let { State.Locked(it.header.masterId, passwordChangedElsewhere = changed || saidSo) }
        }
    }

    /**
     * True if [gone], headers this device knew, means the password was changed on another device: the
     * header the password last opened is among them (one someone else posted and deleted is not). The
     * master key stays the same through a change, so the fingerprint here would still open the vault with
     * the old password's key; it is marked for turning off (audit 1, M7).
     */
    private fun passwordChanged(gone: Set<MessageId>): Boolean {
        val opened = store.openedHeader
        if (gone.isEmpty() || (opened != null && opened !in gone)) return false
        store.deviceUnlockStale = true
        return true
    }

    private fun knownMasterId(): Bytes? = headers.firstOrNull()?.header?.masterId

    /**
     * After a password or recovery key opened nothing: every header in Saved Messages, past the pinned
     * ones, in case another vault's header pinned over the user's own hid it (audit 1, M2). Once per run:
     * a full pass is slow, and a mistyped password is not a reason to repeat it.
     */
    private suspend fun searchPastThePins(storage: StorageBackend): Boolean {
        if (searchedPastThePins) return false
        searchedPastThePins = true
        val startedAt = lock.withLock { headerWrites }
        val all = runCatching { VaultHeaders.findAll(storage) }.getOrNull() ?: return false
        return lock.withLock {
            if (headerWrites != startedAt || stateFlow.value is State.Unlocked) return@withLock false
            val known = knownMasterId()
            // The known vault's headers first, so the state and a fingerprint unlock still go by it.
            val ordered = all.filter { it.header.masterId == known } + all.filter { it.header.masterId != known }
            if (ordered.map { it.messageId }.toSet() == headers.map { it.messageId }.toSet()) return@withLock false
            remember(ordered)
            stateFlow.value = lockedOrUnknown()
            true
        }
    }

    @Volatile
    private var searchedPastThePins = false

    private fun remember(found: List<FoundHeader>) {
        headers = found
        headerWrites++
        store.saveHeaders(found)
    }

    /**
     * Unlocks with the vault password. With [storage], the headers are read again first, when Telegram
     * answers in time: a password changed on another device must stop the old one here as well, and an
     * old header kept on this device would still open the vault with it. Without an answer the headers
     * this device knows are tried; until it hears of the change, the old password works here.
     */
    suspend fun unlockWithPassword(password: CharSequence, storage: StorageBackend? = null): Boolean {
        if (storage != null) withTimeoutOrNull(HEADER_REFRESH_MILLIS) { runCatching { locate(storage) } }
        // A wrong password works out every header once, not again for those the search past the pins finds twice.
        val tried = HashSet<VaultUnlock.KdfInput>()
        if (tryPassword(password, tried)) return true
        return storage != null && searchPastThePins(storage) && tryPassword(password, tried)
    }

    /**
     * A vault header came or went in Saved Messages: a password changed, here or on another device.
     * The headers are read again, and a vault open here whose known header is gone locks, so the old
     * password does not outlive the change on this device. A new header alone (a change still under
     * way, or cut short) leaves it open: both passwords are good until the old header is deleted.
     */
    suspend fun headersChanged(storage: StorageBackend) {
        if (deleting) return
        val startedAt = lock.withLock { headerWrites }
        val known = lock.withLock { knownMasterId() }
        val found = runCatching { VaultHeaders.find(storage, known) }.getOrNull() ?: return
        val deletedElsewhere = deletedElsewhere(storage, found)
        lock.withLock {
            // This device wrote headers meanwhile (its own password change): what was read may predate it.
            if (headerWrites != startedAt) return
            if (deletedElsewhere && !deleting) {
                forgetDeletedLocked(Deleted.ELSEWHERE)
                return
            }
            val masterId = headers.firstOrNull()?.header?.masterId ?: return
            val same = found.filter { it.header.masterId == masterId }
            if (same.isEmpty()) return
            val gone = headers.map { it.messageId }.toSet() - same.map { it.messageId }.toSet()
            passwordChanged(gone)
            remember(same)
            val current = stateFlow.value
            if (current is State.Unlocked && gone.isNotEmpty()) {
                current.vault.close()
                stateFlow.value = State.Locked(masterId, passwordChangedElsewhere = true)
            }
        }
    }

    /**
     * True if the vault this device knows was deleted on another device: no header of it in [found], and
     * Telegram confirms every header known here is gone. The search alone would not do: Telegram offline
     * can answer with an empty history, and that must not wipe the vault from this device. A cached
     * message is still there offline, so only a deletion Telegram has passed on counts.
     */
    private suspend fun deletedElsewhere(storage: StorageBackend, found: List<FoundHeader>): Boolean {
        val known = lock.withLock { headers }
        val masterId = known.firstOrNull()?.header?.masterId ?: return false
        if (found.any { it.header.masterId == masterId }) return false
        return known.all { header ->
            try {
                storage.message(header.messageId) == null
            } catch (_: StorageException) {
                false
            }
        }
    }

    /**
     * Deletes the open vault (SPEC §5.9): [run] gets its master_id and deletes its messages from Saved
     * Messages, then the vault is forgotten here and found no more. A run that fails leaves it as it is,
     * open, to be run again.
     */
    suspend fun delete(run: suspend (masterId: Bytes) -> Unit) {
        val masterId = (stateFlow.value as? State.Unlocked)?.vault?.masterId ?: throw IllegalStateException("the vault is locked")
        deleting = true
        try {
            run(masterId)
            lock.withLock { forgetDeletedLocked(Deleted.HERE) }
        } finally {
            deleting = false
        }
    }

    // The headers go, the settings stay: they are this device's, not the vault's.
    private fun forgetDeletedLocked(why: Deleted) {
        (stateFlow.value as? State.Unlocked)?.vault?.close()
        remember(emptyList())
        deletedFlow.value = why
        stateFlow.value = State.NotFound
    }

    /** True if any of [ids] is a vault header this device knows: its delete means a password change. */
    fun knowsHeader(ids: Collection<me.esdex.coffer.core.storage.MessageId>): Boolean = headers.any { it.messageId in ids }

    private suspend fun tryPassword(password: CharSequence, tried: MutableSet<VaultUnlock.KdfInput>): Boolean {
        val candidates = lock.withLock { headers }
        val started = System.nanoTime()
        val opened = withContext(work) { VaultUnlock.withPasswordFrom(sodium, candidates, password, store.openedHeader, tried) }
        lastKdfMillis = (System.nanoTime() - started) / 1_000_000
        if (opened == null || !open(opened.first)) return false
        store.openedHeader = opened.second
        return true
    }

    /**
     * Throws [me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException] for words that are not a recovery
     * key. With [storage], a key of no vault known here looks past the pinned headers once.
     */
    suspend fun unlockWithRecoveryKey(words: List<String>, storage: StorageBackend? = null): VaultUnlock.MnemonicResult {
        val result = tryRecoveryKey(words)
        if (result != VaultUnlock.MnemonicResult.OtherVault || storage == null || !searchPastThePins(storage)) return result
        return tryRecoveryKey(words)
    }

    private suspend fun tryRecoveryKey(words: List<String>): VaultUnlock.MnemonicResult {
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
        // Any vault whose header is known here, not only the newest one's (audit 1, M2); from now on
        // only its headers are kept.
        val its = headers.filter { it.header.masterId == vault.masterId }
        if (current is State.Unlocked || its.isEmpty()) {
            vault.close()
            return current is State.Unlocked && current.vault.masterId == vault.masterId
        }
        if (its.size != headers.size) remember(its)
        stateFlow.value = State.Unlocked(vault)
        true
    }

    /** A new vault under [password]; shown and confirmed before [publish]. */
    suspend fun prepareNew(password: CharSequence): NewVault {
        require(acceptPassword(password)) { "the password is too weak" }
        return withContext(work) { VaultCreation.prepare(sodium, password, clock()) }
    }

    /**
     * A header for the vault of the recovery key [words] under [password], when Saved Messages has none
     * left (audit 1, M1); [publish] with `restoring` sends it. Throws
     * [me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException] for words that are not a recovery key.
     */
    suspend fun prepareRestore(words: List<String>, password: CharSequence): NewVault {
        require(acceptPassword(password)) { "the password is too weak" }
        return withContext(work) { VaultCreation.restore(sodium, words, password, clock()) }
    }

    /** Sends and pins the header, then opens the vault. Takes ownership of [vault]. */
    suspend fun publish(storage: StorageBackend, vault: NewVault, restoring: Boolean = false) {
        val published = try {
            VaultHeaders.publish(storage, vault, restoring)
        } catch (e: Exception) {
            vault.close()
            throw e
        }
        lock.withLock {
            remember(listOf(published))
            deletedFlow.value = null
            stateFlow.value = State.Unlocked(vault.vault)
        }
        // The header its password opens, tried first from now on, before any posted later (audit 2, L-a).
        store.openedHeader = published.messageId
    }

    /**
     * True if [password] opens the vault that is unlocked now: asked before showing the recovery key
     * or changing the password, so an unlocked phone alone is not enough for either.
     */
    suspend fun checkPassword(password: CharSequence): Boolean {
        val open = (stateFlow.value as? State.Unlocked)?.vault ?: return false
        val candidates = lock.withLock { headers }
        val vault = withContext(work) { VaultUnlock.withPasswordFrom(sodium, candidates, password, store.openedHeader)?.first } ?: return false
        return vault.use { it.masterId == open.masterId }
    }

    /**
     * True if [words] are the recovery key of the vault open now: what stands in for the password when
     * it is forgotten, to set a new one (audit 2, N1).
     */
    suspend fun checkRecoveryKey(words: List<String>): Boolean {
        val open = (stateFlow.value as? State.Unlocked)?.vault ?: return false
        return withContext(work) {
            val master = try {
                Bip39.toMasterKey(words)
            } catch (_: InvalidMnemonicException) {
                return@withContext false
            }
            master.use { MasterKeys.masterId(sodium, it) == open.masterId }
        }
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
     * header with the same master_id. Other devices see the header change and lock (see [headersChanged]).
     */
    suspend fun changePassword(storage: StorageBackend, current: CharSequence, newPassword: CharSequence): PasswordChange =
        replaceHeader(storage, newPassword) { checkPassword(current) }

    /**
     * As [changePassword], with the recovery key in place of the forgotten password. Every other header of
     * the vault goes, so a forged one that only the recovery key got past is gone too (audit 2, N1).
     */
    suspend fun changePasswordWithRecoveryKey(storage: StorageBackend, words: List<String>, newPassword: CharSequence): PasswordChange =
        replaceHeader(storage, newPassword) { checkRecoveryKey(words) }

    private suspend fun replaceHeader(storage: StorageBackend, newPassword: CharSequence, authorised: suspend () -> Boolean): PasswordChange {
        if (!authorised()) {
            return if (stateFlow.value is State.Unlocked) PasswordChange.WrongPassword else PasswordChange.Locked
        }
        val open = (stateFlow.value as? State.Unlocked)?.vault ?: return PasswordChange.Locked
        require(acceptPassword(newPassword)) { "the password is too weak" }
        val header = withContext(work) { VaultCreation.rewrap(sodium, open, newPassword, clock()) }
        val sent = storage.sendText(VaultHeaderCodec.encode(header))
        try {
            storage.pin(sent.id)
        } catch (e: StorageException) {
            // Not left behind unpinned, where it would open the vault with the new password for good while
            // this device still asks for the old one (audit 1, L15).
            runCatching { storage.delete(listOf(sent.id)) }
            throw e
        }
        val published = FoundHeader(sent.id, header)
        // What this device knew, and what Saved Messages holds now, in case another device added one.
        val old = (lock.withLock { headers } + VaultHeaders.find(storage))
            .filter { it.header.masterId == open.masterId && it.messageId != sent.id }
            .map { it.messageId }
            .distinct()
        lock.withLock { remember(listOf(published)) }
        // The new password opens the new header: the one tried first from now on, here.
        store.openedHeader = published.messageId
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
        deletedFlow.value = null
        stateFlow.value = State.Unknown
    }

    /** The master_id the Keystore-wrapped key must match. */
    fun expectedMasterId(): Bytes? = headers.firstOrNull()?.header?.masterId

    private companion object {
        /** How long an unlock waits for Telegram to say which headers are current. */
        const val HEADER_REFRESH_MILLIS = 5_000L
    }
}
