package me.esdex.coffer.core.tdlib

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * @param bundledCredentials keys built into the app from local.properties; null when there are none.
 * @param allowsOwnKeys a user may bring API keys of their own (public build); they win over built-in ones.
 */
class Telegram(
    context: Context,
    private val bundledCredentials: ApiCredentials?,
    private val allowsOwnKeys: Boolean,
    private val applicationVersion: String,
) {
    private val root = File(context.noBackupFilesDir, "tdlib")
    private val secrets = DeviceSecrets(File(context.noBackupFilesDir, "secrets"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var session: TelegramSession? = null
    private var stateJob: Job? = null

    private val stateFlow = MutableStateFlow<AuthState>(AuthState.Starting)
    val state: StateFlow<AuthState> = stateFlow.asStateFlow()

    private val connectionFlow = MutableStateFlow(Connection.CONNECTING)

    /** The running session's link to Telegram; [Connection.CONNECTING] between sessions. */
    val connection: StateFlow<Connection> = connectionFlow.asStateFlow()

    init {
        // Android 15 cuts the network of an app a few seconds after it leaves the screen and gives it
        // back when it returns or runs a transfer; TDLib, meanwhile, backs off further with every
        // failed attempt. Seen 2026-11-09: sends waited 28 s to 7 min with the app on screen.
        context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = networkChanged()

                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    if (!blocked) networkChanged()
                }
            },
        )
    }

    private fun networkChanged() {
        scope.launch { lock.withLock { session }?.networkChanged() }
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
            // The Keystore key is gone, so TDLib's database can never be opened again. Start clean;
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
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            systemVersion = "Android ${Build.VERSION.RELEASE}",
            applicationVersion = applicationVersion,
            languageCode = Locale.getDefault().language,
        )
        val started = TelegramSession(config)
        // TelegramSession has handed the parameters to TDLib in its constructor.
        databaseKey.fill(0)
        session = started
        stateJob = scope.launch {
            launch { started.connection.collect { connectionFlow.value = it } }
            started.state.collect { state ->
                stateFlow.value = state
                if (state == AuthState.Closed) scope.launch { restartAfter(started) }
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
        secrets.clearApiCredentials()
        stateFlow.value = AuthState.NeedApiCredentials
    }

    /** From the keys step: back to the built-in keys, when there are some. */
    suspend fun useBuiltInKeys() = lock.withLock {
        checkNotNull(bundledCredentials) { "no built-in keys" }
        check(stateFlow.value != AuthState.Ready) { "signed in: keys change only with a new sign-in" }
        stopLocked()
        root.deleteRecursively()
        secrets.clearApiCredentials()
        startLocked()
    }

    /** After [AuthState.Failed]: wipe TDLib's data and key and start over. */
    suspend fun resetTelegramData() = lock.withLock {
        stopLocked()
        root.deleteRecursively()
        secrets.resetDatabaseKey()
        startLocked()
    }

    suspend fun submitPhoneNumber(phoneNumber: String) = current().submitPhoneNumber(phoneNumber)
    suspend fun requestQrCode() = current().requestQrCode()
    suspend fun submitCode(code: String) = current().submitCode(code)
    suspend fun resendCode() = current().resendCode()
    suspend fun submitEmailAddress(email: String) = current().submitEmailAddress(email)
    suspend fun submitEmailCode(code: String) = current().submitEmailCode(code)
    suspend fun submitPassword(password: String) = current().submitPassword(password)
    suspend fun accountName(): String = current().accountName()
    suspend fun account(): TelegramAccount = current().account()
    suspend fun storage(): TdlibStorageBackend = current().storage()

    /**
     * Signs out, or abandons a half-done sign-in. TDLib deletes its data and closes; a new session
     * starts at the phone number step.
     */
    suspend fun logOut() = current().logOut()
}
