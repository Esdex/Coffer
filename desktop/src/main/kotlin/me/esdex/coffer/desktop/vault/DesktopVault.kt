package me.esdex.coffer.desktop.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.RoomVaultIndex
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageEvent
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.Indexer
import me.esdex.coffer.core.sync.VaultKeys
import me.esdex.coffer.core.sync.reader
import me.esdex.coffer.core.sync.transferKeys
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.FoundHeader
import me.esdex.coffer.core.vault.UnlockedVault
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.core.vault.VaultStore
import me.esdex.coffer.desktop.DesktopSecrets
import me.esdex.coffer.desktop.drive.DriveTree
import me.esdex.coffer.desktop.drive.OpenFile
import java.io.File

/**
 * The vault on this computer, as the Android app's VaultLifecycle and VaultContent keep it: found once
 * signed in, kept up to date in [index] while unlocked, forgotten on sign-out or when deleted (SPEC §5.9,
 * §9). Read only for now: nothing is sent, no snapshots are written. No auto-lock on the desktop (Esdex,
 * 2026-11-21); the vault opens by itself at start only if the user asked to be remembered here.
 */
class DesktopVault(
    private val sodium: Sodium,
    private val telegram: Telegram,
    private val secrets: DesktopSecrets,
    val index: RoomVaultIndex,
    dataDir: File,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = VaultStore(File(dataDir, "vault"), secrets)

    val manager = VaultManager(
        sodium,
        store,
        // As on Android (VaultModule): a few parts and snapshots this computer knows, asked for by message id;
        // any still there means the vault is (audit 2, L-b). No answer counts as there.
        contentRemains = { storage ->
            val ids = index.allParts().values.asSequence().flatMap { it.values }.take(CONTENT_PROBES).toList() +
                index.snapshots().map { it.messageId }
            ids.any { id ->
                try {
                    storage.message(id) != null
                } catch (_: StorageException) {
                    true
                }
            }
        },
    )

    private val keysFlow = MutableStateFlow<VaultKeys?>(null)

    /** The open vault's keys, for showing names; null while locked. */
    val keys: StateFlow<VaultKeys?> = keysFlow.asStateFlow()

    private val catchUpFlow = MutableStateFlow<Int?>(null)

    /** Messages read so far while catching up, or null when caught up or locked. */
    val catchingUp: StateFlow<Int?> = catchUpFlow.asStateFlow()

    private var session: Job? = null

    private val treeFlow = MutableStateFlow(DriveTree.EMPTY)

    /** What the drive shows: the open vault's folders and whole files; empty while locked. */
    val tree: StateFlow<DriveTree> = treeFlow.asStateFlow()

    private val settledFlow = MutableStateFlow(false)

    /** The vault was looked for and a remembered key had its go: what is locked now waits for the password. */
    val settled: StateFlow<Boolean> = settledFlow.asStateFlow()

    fun start() {
        scope.launch {
            telegram.state.collectLatest { state ->
                if (state != AuthState.Ready) return@collectLatest
                val storage = runCatching { telegram.storage() }.getOrNull() ?: return@collectLatest
                launch {
                    runCatching { manager.locate(storage) }.onFailure { log("looking for the vault failed (${it.javaClass.simpleName})") }
                    openRemembered()
                    settledFlow.value = true
                }
                watchHeaders(storage)
            }
        }
        // Signed out, or Telegram's data wiped: the vault of that account is forgotten (audit 3, Q3-4).
        scope.launch { telegram.ended.collect { withContext(NonCancellable) { forget() } } }
        // Deleted here or elsewhere (SPEC §5.9): nothing of it stays on this computer.
        scope.launch { manager.deleted.filterNotNull().collect { withContext(NonCancellable) { forget() } } }
        scope.launch {
            manager.state.collect { state -> if (state is VaultManager.State.Unlocked) open(state.vault) else close() }
        }
    }

    /**
     * Tries [password]; with [remember], the master key goes to the keyring and the vault opens by itself
     * at the next start; without it, a key remembered before is forgotten.
     */
    suspend fun unlock(password: CharSequence, remember: Boolean): Boolean {
        val storage = runCatching { telegram.storage() }.getOrNull()
        if (!manager.unlockWithPassword(password, storage)) return false
        val unlocked = manager.state.value as? VaultManager.State.Unlocked ?: return true
        runCatching {
            if (remember) unlocked.vault.withMasterKey(secrets::rememberMaster) else secrets.forgetMaster()
        }.onFailure { log("the keyring refused the vault key (${it.javaClass.simpleName})") }
        return true
    }

    val remembered: Boolean get() = runCatching { secrets.rememberedMaster()?.close() != null }.getOrDefault(false)

    fun lock() {
        scope.launch {
            // Deletes gathered on the drive go into the queue while the keys are still there.
            runCatching { writer?.flush() }.onFailure { log("queueing deletes before the lock failed (${it.javaClass.simpleName})") }
            manager.lockVault(byHand = true)
        }
    }

    @Volatile
    private var writer: VaultWriter? = null

    /** What changes the vault from the drive; null while locked. */
    val edits: VaultWriter? get() = writer

    /** At start only: a vault locked by hand asks for the password, or Lock would mean nothing. */
    private suspend fun openRemembered() {
        val state = manager.state.value as? VaultManager.State.Locked ?: return
        if (state.byHand) return
        // The password was changed on another device: the remembered key goes until the new one is typed
        // (audit 1, M7), as fingerprint unlock does on the phone.
        if (store.deviceUnlockStale) {
            runCatching { secrets.forgetMaster() }
            store.deviceUnlockStale = false
            return
        }
        val master = runCatching { secrets.rememberedMaster() }.getOrNull() ?: return
        if (!manager.unlockWithDeviceKey(master)) {
            log("the remembered vault key does not open the vault; forgotten")
            runCatching { secrets.forgetMaster() }
        }
    }

    /** A header arriving or a known one going is a password change (SPEC §4.1). */
    private suspend fun watchHeaders(storage: StorageBackend) {
        storage.events.collect { event ->
            val header = when (event) {
                is StorageEvent.NewMessage -> FoundHeader.from(event.message) != null
                is StorageEvent.MessagesDeleted -> manager.knowsHeader(event.ids)
            }
            if (header) runCatching { manager.headersChanged(storage) }
        }
    }

    private fun open(unlocked: UnlockedVault) {
        close()
        val keys = VaultKeys(sodium, unlocked.keys.metaKey, unlocked.keys.indexKey, unlocked.keys.wrapKey, unlocked.localKey)
        keysFlow.value = keys
        log("unlocked")
        session = scope.launch {
            val current = VaultWriter(this, index, keys, storage = { telegram.state.first { it == AuthState.Ready }; telegram.storage() })
            writer = current
            launch { current.tree.collect { treeFlow.value = it } }
            current.start()
            telegram.state.first { it == AuthState.Ready }
            val storage = telegram.storage()
            catchUpFlow.value = 0
            Indexer(storage, index, keys).follow(
                onCatchUpProgress = { catchUpFlow.value = it },
                onCaughtUp = {
                    catchUpFlow.value = null
                    launch { log("caught up: ${index.files().size} files, ${index.folders().size} folders") }
                },
                // A message id only: rare enough to want to know when it happens.
                onLate = { log("message ${it.value} came late; reading the history again") },
                // Its kind only: a message could carry more than the project's rule on logs allows.
                onError = { log("catching up failed (${it.javaClass.simpleName}); trying again") },
            )
        }
    }

    /** [file] open for reading in place; null while locked, or if it is no longer whole. */
    suspend fun openFile(file: DriveTree.File): OpenFile? {
        val keys = keysFlow.value ?: return null
        val indexed = index.file(file.fileId)?.takeIf { it.status == FileStatus.READY } ?: return null
        val storage = telegram.storage()
        val transfer = keys.transferKeys(file.fileId, indexed.sealedMeta) ?: return null
        val reader = keys.reader(storage, indexed, transfer)
        return OpenFile(reader::read) {
            reader.close()
            transfer.close()
        }
    }

    private fun close() {
        if (keysFlow.value != null) log("locked")
        session?.cancel()
        session = null
        writer = null
        treeFlow.value = DriveTree.EMPTY
        keysFlow.value = null
        catchUpFlow.value = null
    }

    private suspend fun forget() {
        close()
        index.clear()
        runCatching { secrets.forgetMaster() }
        manager.forget()
    }

    private companion object {
        const val CONTENT_PROBES = 8
    }
}

/** Coffer's lines about the vault: states, counts and ids, never names or content (SPEC §8). */
internal fun log(message: String) = System.err.println("I/CofferVault: $message")
