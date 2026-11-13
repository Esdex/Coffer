package me.esdex.coffer.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.tdlib.ApiCredentials
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.PhoneCountry
import me.esdex.coffer.core.tdlib.PhoneNumberInfo
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.tdlib.TdlibException
import javax.inject.Inject

sealed interface LoginError {
    data object InvalidApiKeys : LoginError

    data class FloodWait(val seconds: Int) : LoginError

    /** Telegram's error text, such as PHONE_CODE_INVALID. */
    data class Telegram(val code: String) : LoginError
}

@HiltViewModel
class LoginViewModel @Inject constructor(private val telegram: Telegram) : ViewModel() {

    val state: StateFlow<AuthState> = telegram.state

    private val busyFlow = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyFlow.asStateFlow()

    private val errorFlow = MutableStateFlow<LoginError?>(null)
    val error: StateFlow<LoginError?> = errorFlow.asStateFlow()

    val canChooseKeys: Boolean get() = telegram.canChooseKeys
    val hasBuiltInKeys: Boolean get() = telegram.hasBuiltInKeys
    val usesOwnKeys: Boolean get() = telegram.usesOwnKeys

    init {
        telegram.start()
    }

    fun submitApiKeys(apiId: String, apiHash: String) {
        val credentials = ApiCredentials.parse(apiId, apiHash)
        if (credentials == null) {
            errorFlow.value = LoginError.InvalidApiKeys
            return
        }
        perform { telegram.submitApiCredentials(credentials) }
    }

    /** The country and Telegram's layout for the start of a number; null when Telegram cannot say. */
    suspend fun phoneNumberInfo(digits: String): PhoneNumberInfo? =
        if (digits.isEmpty()) null else runCatching { telegram.phoneNumberInfo(digits) }.getOrNull()

    /** Every country Telegram knows; empty until it answers, and the code field then just takes digits. */
    suspend fun countries(): List<PhoneCountry> = runCatching { telegram.countries() }.getOrDefault(emptyList())

    fun submitPhoneNumber(phoneNumber: String) =
        perform { telegram.submitPhoneNumber(phoneNumber.filter { it.isDigit() || it == '+' }) }

    fun useQrCode() = perform { telegram.requestQrCode() }

    fun submitCode(code: String) = perform { telegram.submitCode(code.trim()) }

    fun resendCode() = perform { telegram.resendCode() }

    fun submitEmailAddress(email: String) = perform { telegram.submitEmailAddress(email.trim()) }

    fun submitEmailCode(code: String) = perform { telegram.submitEmailCode(code.trim()) }

    fun submitPassword(password: String) = perform { telegram.submitPassword(password) }

    /** Abandons the sign-in in progress; Telegram comes back at the phone number step. */
    fun startOver() = perform { telegram.logOut() }

    fun chooseOwnKeys() = perform { telegram.chooseOwnKeys() }

    fun useBuiltInKeys() = perform { telegram.useBuiltInKeys() }

    fun resetTelegramData() = perform { telegram.resetTelegramData() }

    fun dismissError() {
        errorFlow.value = null
    }

    private fun perform(action: suspend () -> Unit) {
        if (busyFlow.value) return
        viewModelScope.launch {
            busyFlow.value = true
            errorFlow.value = null
            try {
                action()
            } catch (e: FloodWaitException) {
                errorFlow.value = LoginError.FloodWait(e.seconds)
            } catch (e: TdlibException) {
                errorFlow.value = LoginError.Telegram(e.message.orEmpty())
            } finally {
                busyFlow.value = false
            }
        }
    }
}
