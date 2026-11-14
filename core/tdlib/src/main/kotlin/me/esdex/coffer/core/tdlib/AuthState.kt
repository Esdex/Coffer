package me.esdex.coffer.core.tdlib

/** Where sign-in stands. Mirrors TDLib's authorization states the UI has to act on (SPEC §7, step 3). */
sealed interface AuthState {

    /** Public build before the user has entered api_id and api_hash (SPEC §7, step 2). */
    data object NeedApiCredentials : AuthState

    /** TDLib is starting or a request is on its way. */
    data object Starting : AuthState

    data object WaitPhoneNumber : AuthState

    /** Show [link] as a QR code; it changes every few seconds while this state lasts. */
    data class WaitQrConfirmation(val link: String) : AuthState {
        // The link signs this device in: not for a log.
        override fun toString(): String = "WaitQrConfirmation(link=***)"
    }

    data class WaitCode(
        val phoneNumber: String,
        val delivery: CodeDelivery,
        /** Digits expected, if Telegram said. */
        val length: Int?,
        /** Whether [delivery] can be switched by asking again, and after how many seconds. */
        val canResend: Boolean,
        val resendAfterSeconds: Int,
    ) : AuthState {
        // The phone number stays out of logs (CLAUDE.md, audit 1, Info DATA-12).
        override fun toString(): String = "WaitCode(delivery=$delivery, length=$length)"
    }

    data object WaitEmailAddress : AuthState

    data class WaitEmailCode(val emailPattern: String, val length: Int) : AuthState {
        override fun toString(): String = "WaitEmailCode(length=$length)"
    }

    data class WaitPassword(val hint: String, val hasRecoveryEmail: Boolean) : AuthState {
        override fun toString(): String = "WaitPassword(hasRecoveryEmail=$hasRecoveryEmail)"
    }

    /** The number has no Telegram account. Coffer does not create accounts. */
    data object RegistrationRequired : AuthState

    /** Telegram wants a Premium purchase before this sign-in, which only its own apps can do. */
    data object PremiumRequired : AuthState

    data object Ready : AuthState

    data object LoggingOut : AuthState

    /** TDLib has shut down; a new session starts from here. */
    data object Closed : AuthState

    /** TDLib refused to start, for example because the API keys are invalid. */
    data class Failed(val error: TdlibException) : AuthState
}

enum class CodeDelivery { TELEGRAM_APP, SMS, CALL, FLASH_CALL, MISSED_CALL, FRAGMENT, OTHER }

/** How TDLib's link to Telegram stands; what Telegram's own apps show in their title bar. */
enum class Connection { WAITING_FOR_NETWORK, CONNECTING, UPDATING, READY }
