package me.esdex.coffer.core.tdlib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

/**
 * The app's Telegram connection. Starts TDLib with the right keys and starts a fresh session after
 * every log out, so [state] goes on after [AuthState.Closed].
 *
 * The platform gives it a place and its secrets: on Android see androidTelegram in :core:device.
 *
 * @param root TDLib's database and files; nothing else lives there, it is deleted whole on a reset.
 * @param bundledCredentials keys built into the app from local.properties; null when there are none.
 * @param allowsOwnKeys a user may bring API keys of their own (public build); they win over built-in ones.
 * @param deviceModel and [systemVersion]: what Telegram shows for this session among the account's devices.
 */
class Telegram(
    private val root: File,
    private val secrets: TelegramSecrets,
    private val bundledCredentials: ApiCredentials?,
    private val allowsOwnKeys: Boolean,
    private val deviceModel: String,
    private val systemVersion: String,
    private val applicationVersion: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var session: TelegramSession? = null
    private var stateJob: Job? = null

    private val stateFlow = MutableStateFlow<AuthState>(AuthState.Starting)
    val state: StateFlow<AuthState> = stateFlow.asStateFlow()

    private val endedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /**
     * The account's data on this phone is gone: signed out, or TDLib's data wiped (reset, other keys).
     * What belongs to it goes too, queued work above all, which would otherwise be sent, and delete
     * message ids, in whatever account signs in next. An event, not [state]: the next session's first
     * state overwrote a Closed there before a collector saw it, and a reset never showed one (audit 3, Q3-4).
     */
    val ended: SharedFlow<Unit> = endedFlow.asSharedFlow()

    private val connectionFlow = MutableStateFlow(Connection.CONNECTING)

    /** The running session's link to Telegram; [Connection.CONNECTING] between sessions. */
    val connection: StateFlow<Connection> = connectionFlow.asStateFlow()

    @Volatile
    private var networkUp = true

    /** The network came back: TDLib backs off further with every failed attempt and is told to try now. */
    fun networkChanged() {
        networkUp = true
        tellNetwork()
    }

    /**
     * The computer has no network, as the desktop app hears from NetworkManager: TDLib stops trying and says it
     * waits for the network, where it would otherwise go on connecting; [networkChanged] once it is back. Android
     * does not call it: it tells only of the network coming back.
     */
    fun networkLost() {
        networkUp = false
        tellNetwork()
    }

    // The latest word, whichever call gets the lock first: a loss and a return a moment apart end as the return.
    private fun tellNetwork() {
        scope.launch { lock.withLock { session?.network(networkUp) } }
    }

    /** The user may pick API keys of their own before signing in. */
    val canChooseKeys: Boolean get() = allowsOwnKeys

    val hasBuiltInKeys: Boolean get() = bundledCredentials != null

    /** Signed in, or signing in, with keys the user entered rather than the built-in ones. */
    val usesOwnKeys: Boolean get() = allowsOwnKeys && secrets.apiCredentials() != null

    /** Starts TDLib unless it runs already. */
    fun start() {
        scope.launch { lock.withLock { if (session == null) startLocked() } }
    }

    private fun startLocked() {
        val credentials = (if (allowsOwnKeys) secrets.apiCredentials() else null) ?: bundledCredentials
        if (credentials == null) {
            stateFlow.value = AuthState.NeedApiCredentials
            return
        }
        val databaseKey = secrets.databaseKey() ?: run {
            // The device key is gone, so TDLib's database can never be opened again. Start clean;
            // the user signs in again.
            root.deleteRecursively()
            secrets.resetDatabaseKey()
            checkNotNull(secrets.databaseKey())
        }
        val config = TdlibConfig(
            credentials = credentials,
            databaseDir = File(root, "db"),
            filesDir = File(root, "files"),
            databaseKey = databaseKey,
            deviceModel = deviceModel,
            systemVersion = systemVersion,
            applicationVersion = applicationVersion,
            languageCode = Locale.getDefault().language,
        )
        val started = TelegramSession(config)
        // TelegramSession has handed the parameters to TDLib in its constructor.
        databaseKey.fill(0)
        session = started
        if (!networkUp) started.network(false)
        stateJob = scope.launch {
            launch { started.connection.collect { connectionFlow.value = it } }
            started.state.collect { state ->
                stateFlow.value = state
                if (state == AuthState.Closed) {
                    endedFlow.emit(Unit)
                    scope.launch { restartAfter(started) }
                }
            }
        }
    }

    private suspend fun restartAfter(closed: TelegramSession) = lock.withLock {
        if (session !== closed) return@withLock
        stateJob?.cancel()
        session = null
        startLocked()
    }

    /** Stops the running session and waits until TDLib has closed its files. */
    private suspend fun stopLocked() {
        val running = session ?: return
        stateJob?.cancel()
        session = null
        stateFlow.value = AuthState.Starting
        connectionFlow.value = Connection.CONNECTING
        runCatching { running.close() }
        running.state.first { it == AuthState.Closed }
    }

    private fun current(): TelegramSession = session ?: throw TdlibException(0, "Telegram is not started")

    suspend fun submitApiCredentials(credentials: ApiCredentials) = lock.withLock {
        check(allowsOwnKeys) { "this build takes no keys of its own" }
        secrets.saveApiCredentials(credentials)
        if (session == null) startLocked()
    }

    /**
     * Before sign-in: drop whatever keys are in use and TDLib's (still empty) data, and ask for keys.
     * Keys cannot change under a signed-in session: Telegram ties a session to the app that made it.
     */
    suspend fun chooseOwnKeys() = lock.withLock {
        check(allowsOwnKeys) { "this build takes no keys of its own" }
        check(stateFlow.value != AuthState.Ready) { "signed in: keys change only with a new sign-in" }
        stopLocked()
        root.deleteRecursively()
        endedFlow.emit(Unit)
        secrets.clearApiCredentials()
        stateFlow.value = AuthState.NeedApiCredentials
    }

    /** From the keys step: back to the built-in keys, when there are some. */
    suspend fun useBuiltInKeys() = lock.withLock {
        checkNotNull(bundledCredentials) { "no built-in keys" }
        check(stateFlow.value != AuthState.Ready) { "signed in: keys change only with a new sign-in" }
        stopLocked()
        root.deleteRecursively()
        endedFlow.emit(Unit)
        secrets.clearApiCredentials()
        startLocked()
    }

    /** After [AuthState.Failed]: wipe TDLib's data and key and start over. */
    suspend fun resetTelegramData() = lock.withLock {
        stopLocked()
        root.deleteRecursively()
        endedFlow.emit(Unit)
        secrets.resetDatabaseKey()
        startLocked()
    }

    suspend fun submitPhoneNumber(phoneNumber: String) = current().submitPhoneNumber(phoneNumber)
    suspend fun requestQrCode() = current().requestQrCode()

    /** Before signing in too: the country and Telegram's own layout for the start of a number (digits only). */
    suspend fun phoneNumberInfo(prefix: String): PhoneNumberInfo = current().phoneNumberInfo(prefix)

    suspend fun countries(): List<PhoneCountry> = current().countries()
    suspend fun submitCode(code: String) = current().submitCode(code)
    suspend fun resendCode() = current().resendCode()
    suspend fun submitEmailAddress(email: String) = current().submitEmailAddress(email)
    suspend fun submitEmailCode(code: String) = current().submitEmailCode(code)
    suspend fun submitPassword(password: String) = current().submitPassword(password)
    suspend fun accountName(): String = current().accountName()
    suspend fun account(): TelegramAccount = current().account()
    suspend fun usage(): TelegramUsage = current().usage()
    suspend fun dropDownloads() = current().dropDownloads()
    suspend fun storage(): TdlibStorageBackend = current().storage()

    /**
     * Signs out, or abandons a half-done sign-in. TDLib deletes its data and closes; a new session
     * starts at the phone number step.
     */
    suspend fun logOut() = current().logOut()
}
