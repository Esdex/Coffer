package me.esdex.coffer.core.tdlib

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Telegram's test servers. Accounts there are throwaway: numbers 99966XYYYY live in DC X and always
 * accept the digit X repeated to the code length Telegram asks for. Nothing touches a real account.
 */
object TestDc {
    private val DC: Int get() = InstrumentationRegistry.getArguments().getString("testDc")?.toInt() ?: 2

    fun codeFor(state: AuthState.WaitCode): String = DC.toString().repeat(state.length ?: 5)

    /** Valid length, wrong digits. */
    fun wrongCodeFor(state: AuthState.WaitCode): String = "1".repeat(state.length ?: 5)

    val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** One folder per test run, under the test app's cache. */
    private val runDir by lazy { File(context.cacheDir, "testdc/${System.currentTimeMillis()}") }

    private val credentials: ApiCredentials by lazy {
        val args = InstrumentationRegistry.getArguments()
        val parsed = ApiCredentials.parse(args.getString("cofferApiId").orEmpty(), args.getString("cofferApiHash").orEmpty())
        // A failure, not a skip: a run without keys must not look green.
        checkNotNull(parsed) { "COFFER_API_ID / COFFER_API_HASH missing or invalid in local.properties" }
    }

    fun newPhoneNumber(): String = "99966$DC" + Random.nextInt(10_000).toString().padStart(4, '0')

    fun scratch(name: String): File = File(runDir, name).apply { mkdirs() }

    fun newSession(name: String): TelegramSession {
        val dir = File(runDir, name)
        return TelegramSession(
            TdlibConfig(
                credentials = credentials,
                databaseDir = File(dir, "db"),
                filesDir = File(dir, "files"),
                databaseKey = ByteArray(32).also(SecureRandom()::nextBytes),
                deviceModel = "Coffer test",
                systemVersion = "Android",
                applicationVersion = "test",
                languageCode = "en",
                useTestDc = true,
            ),
        )
    }

    /** Waits for the next state other than [previous] that is not a transitional one. */
    suspend fun TelegramSession.nextState(previous: AuthState?): AuthState = withTimeout(60_000) {
        state.first { it !== previous && it != AuthState.Starting && it != AuthState.LoggingOut }
    }

    /** Signs in by phone and code, creating the account if needed and answering 2FA with [password]. */
    suspend fun TelegramSession.signInByPhone(phoneNumber: String, password: String? = null) {
        var state: AuthState? = null
        while (true) {
            state = nextState(state)
            when (state) {
                AuthState.WaitPhoneNumber -> submitPhoneNumber(phoneNumber)
                is AuthState.WaitCode -> try {
                    submitCode(codeFor(state))
                } catch (e: TdlibException) {
                    throw AssertionError("code ${codeFor(state)} refused in $state", e)
                }
                AuthState.RegistrationRequired -> registerTestAccount()
                is AuthState.WaitPassword -> submitPassword(checkNotNull(password) { "account has a password" })
                AuthState.Ready -> return
                else -> error("unexpected sign-in state $state")
            }
        }
    }

    /** Signs in by QR code, confirmed by [signedIn] the way a phone scanning the code would. */
    suspend fun TelegramSession.signInByQr(signedIn: TelegramSession) {
        var state: AuthState? = null
        while (true) {
            state = nextState(state)
            when (state) {
                AuthState.WaitPhoneNumber -> requestQrCode()
                is AuthState.WaitQrConfirmation -> signedIn.confirmQrCode(state.link)
                AuthState.Ready -> return
                else -> error("unexpected sign-in state $state")
            }
        }
    }
}
