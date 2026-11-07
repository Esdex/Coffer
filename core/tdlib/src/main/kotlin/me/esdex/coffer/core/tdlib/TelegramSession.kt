package me.esdex.coffer.core.tdlib

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Telegram API keys (BYO api_id). The hash is a secret: it never appears in [toString]. */
class ApiCredentials(val apiId: Int, val apiHash: String) {
    init {
        require(isValid(apiId, apiHash)) { "api_id must be positive and api_hash 32 hex characters" }
    }

    override fun equals(other: Any?) = other is ApiCredentials && other.apiId == apiId && other.apiHash == apiHash
    override fun hashCode() = apiId
    override fun toString() = "ApiCredentials(apiId=$apiId)"

    companion object {
        private val HASH = Regex("[0-9a-fA-F]{32}")

        fun isValid(apiId: Int, apiHash: String) = apiId > 0 && HASH.matches(apiHash)

        /** From what the user typed (SPEC §7, step 2), or null if it is not a valid pair. */
        fun parse(apiId: String, apiHash: String): ApiCredentials? {
            val id = apiId.trim().toIntOrNull() ?: return null
            val hash = apiHash.trim()
            return if (isValid(id, hash)) ApiCredentials(id, hash) else null
        }
    }
}

class TdlibConfig(
    val credentials: ApiCredentials,
    val databaseDir: File,
    val filesDir: File,
    /** TDLib encrypts its database with this (SPEC §8). */
    val databaseKey: ByteArray,
    val deviceModel: String,
    val systemVersion: String,
    val applicationVersion: String,
    val languageCode: String,
    val useTestDc: Boolean = false,
)

/**
 * One TDLib client, from start to [AuthState.Closed]. After that a new session is needed;
 * [Telegram] takes care of that for the app.
 */
class TelegramSession(config: TdlibConfig) {
    private val stateFlow = MutableStateFlow<AuthState>(AuthState.Starting)
    val state: StateFlow<AuthState> = stateFlow.asStateFlow()

    private val updateFlow = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = Int.MAX_VALUE)

    /** Every TDLib update; hot, nothing is replayed. */
    internal val updates: SharedFlow<TdApi.Object> = updateFlow.asSharedFlow()

    /** Called on TDLib's thread before [updates] sees the update, for callers that must not miss one. */
    private val listeners = CopyOnWriteArrayList<(TdApi.Object) -> Unit>()

    private val client: Client

    private val storageLock = Mutex()
    private var storage: TdlibStorageBackend? = null

    init {
        quietTdlibLogs
        client = Client.create(::onUpdate, null, null)
        // A new client always starts in WaitTdlibParameters, so the parameters can go out right away.
        client.send(
            TdApi.SetTdlibParameters(
                config.useTestDc,
                config.databaseDir.absolutePath,
                config.filesDir.absolutePath,
                config.databaseKey,
                true,
                true,
                true,
                false,
                config.credentials.apiId,
                config.credentials.apiHash,
                config.languageCode,
                config.deviceModel,
                config.systemVersion,
                config.applicationVersion,
            ),
        ) { result ->
            if (result is TdApi.Error) {
                Log.w(TAG, "setTdlibParameters failed: ${result.code}")
                stateFlow.value = AuthState.Failed(TdlibException(result.code, result.message))
            }
        }
    }

    internal fun addListener(listener: (TdApi.Object) -> Unit) {
        listeners += listener
    }

    private fun onUpdate(update: TdApi.Object) {
        if (update is TdApi.UpdateAuthorizationState) onAuthorizationState(update.authorizationState)
        for (listener in listeners) listener(update)
        updateFlow.tryEmit(update)
    }

    private fun onAuthorizationState(state: TdApi.AuthorizationState) {
        // The state's name only: its fields carry phone numbers, links and hints.
        Log.i(TAG, "authorization state ${state.javaClass.simpleName}")
        stateFlow.value = when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> AuthState.Starting
            is TdApi.AuthorizationStateWaitPhoneNumber -> AuthState.WaitPhoneNumber
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> AuthState.WaitQrConfirmation(state.link)
            is TdApi.AuthorizationStateWaitCode -> state.codeInfo.toWaitCode()
            is TdApi.AuthorizationStateWaitEmailAddress -> AuthState.WaitEmailAddress
            is TdApi.AuthorizationStateWaitEmailCode ->
                AuthState.WaitEmailCode(state.codeInfo.emailAddressPattern, state.codeInfo.length)
            is TdApi.AuthorizationStateWaitPassword ->
                AuthState.WaitPassword(state.passwordHint, state.hasRecoveryEmailAddress)
            is TdApi.AuthorizationStateWaitRegistration -> AuthState.RegistrationRequired
            is TdApi.AuthorizationStateWaitPremiumPurchase -> AuthState.PremiumRequired
            is TdApi.AuthorizationStateReady -> AuthState.Ready
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> AuthState.LoggingOut
            is TdApi.AuthorizationStateClosed -> AuthState.Closed
            else -> stateFlow.value
        }
    }

    /** Sends [function] and returns its result; a TDLib error is thrown as [TdlibException] or flood wait. */
    @Suppress("UNCHECKED_CAST")
    internal suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { continuation ->
            client.send(function) { result ->
                if (result is TdApi.Error) {
                    // Function name and error code only: messages can echo what was sent.
                    Log.w(TAG, "${function.javaClass.simpleName} failed: ${result.code}")
                    continuation.resumeWithException(result.toException())
                } else {
                    continuation.resume(result as T)
                }
            }
        }

    suspend fun submitPhoneNumber(phoneNumber: String) {
        // No flash calls, missed calls or Firebase: those need permissions and services Coffer does not use.
        val settings = TdApi.PhoneNumberAuthenticationSettings(false, false, false, false, false, null, emptyArray())
        send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, settings))
    }

    suspend fun requestQrCode() {
        send(TdApi.RequestQrCodeAuthentication(LongArray(0)))
    }

    suspend fun submitCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code))
    }

    suspend fun resendCode() {
        send(TdApi.ResendAuthenticationCode(TdApi.ResendCodeReasonUserRequest()))
    }

    suspend fun submitEmailAddress(email: String) {
        send(TdApi.SetAuthenticationEmailAddress(email))
    }

    suspend fun submitEmailCode(code: String) {
        send(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code)))
    }

    suspend fun submitPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password))
    }

    /** The signed-in account's display name. */
    suspend fun accountName(): String {
        val me = send(TdApi.GetMe())
        return listOf(me.firstName, me.lastName).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** Ends the Telegram session on the server and deletes TDLib's local data. */
    suspend fun logOut() {
        send(TdApi.LogOut())
    }

    /** Stops TDLib, keeping its data. */
    suspend fun close() {
        send(TdApi.Close())
    }

    /** Saved Messages of the signed-in account. Only once [state] is [AuthState.Ready]. */
    suspend fun storage(): TdlibStorageBackend = storageLock.withLock {
        storage ?: run {
            check(state.value == AuthState.Ready) { "not signed in" }
            val me = send(TdApi.GetMe())
            // Saved Messages is the private chat with oneself; this also loads it into TDLib.
            val chat = send(TdApi.CreatePrivateChat(me.id, false))
            TdlibStorageBackend(this, chat.id).also { storage = it }
        }
    }

    private fun TdApi.AuthenticationCodeInfo.toWaitCode(): AuthState.WaitCode {
        val (delivery, length) = when (val t = type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> CodeDelivery.TELEGRAM_APP to t.length
            is TdApi.AuthenticationCodeTypeSms -> CodeDelivery.SMS to t.length
            is TdApi.AuthenticationCodeTypeCall -> CodeDelivery.CALL to t.length
            is TdApi.AuthenticationCodeTypeFlashCall -> CodeDelivery.FLASH_CALL to null
            is TdApi.AuthenticationCodeTypeMissedCall -> CodeDelivery.MISSED_CALL to t.length
            is TdApi.AuthenticationCodeTypeFragment -> CodeDelivery.FRAGMENT to t.length
            else -> CodeDelivery.OTHER to null
        }
        return AuthState.WaitCode(phoneNumber, delivery, length?.takeIf { it > 0 }, nextType != null, timeout)
    }

    // Test helpers: accounts on Telegram's test servers have to be created, and a second test
    // device signs in by QR the way a phone would confirm it.
    internal suspend fun registerTestAccount() {
        send(TdApi.RegisterUser("Coffer", "Test", true))
    }

    internal suspend fun confirmQrCode(link: String) {
        send(TdApi.ConfirmQrCodeAuthentication(link))
    }

    internal suspend fun setPassword(password: String) {
        send(TdApi.SetPassword("", password, "", false, ""))
    }

    private companion object {
        const val TAG = "CofferTdlib"

        /**
         * TDLib logs to logcat at a high verbosity by default, including message text and file paths.
         * Errors only (SPEC §8).
         */
        val quietTdlibLogs: Unit by lazy {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
            Unit
        }
    }
}
