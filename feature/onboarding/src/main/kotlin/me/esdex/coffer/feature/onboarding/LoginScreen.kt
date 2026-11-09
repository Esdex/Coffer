package me.esdex.coffer.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.esdex.coffer.core.ui.components.Hero
import me.esdex.coffer.core.ui.components.HeroAnimation
import me.esdex.coffer.core.ui.components.PillButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.CodeDelivery

/** Sign-in to Telegram, every step up to [AuthState.Ready] (SPEC §7, steps 2 and 3). */
@Composable
fun LoginScreen(modifier: Modifier = Modifier, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                AuthState.NeedApiCredentials -> ApiKeysStep(
                    busy,
                    viewModel::submitApiKeys,
                    onBuiltIn = viewModel::useBuiltInKeys.takeIf { viewModel.hasBuiltInKeys },
                )
                AuthState.WaitPhoneNumber -> {
                    PhoneStep(busy, viewModel::submitPhoneNumber, viewModel::useQrCode)
                    KeysChoice(viewModel, busy)
                }
                is AuthState.WaitQrConfirmation -> QrStep(s.link, busy, viewModel::startOver)
                is AuthState.WaitCode -> CodeStep(s, busy, viewModel::submitCode, viewModel::resendCode, viewModel::useQrCode, viewModel::startOver)
                AuthState.WaitEmailAddress -> EmailStep(busy, viewModel::submitEmailAddress, viewModel::startOver)
                is AuthState.WaitEmailCode -> EmailCodeStep(s, busy, viewModel::submitEmailCode, viewModel::startOver)
                is AuthState.WaitPassword -> PasswordStep(s, busy, viewModel::submitPassword, viewModel::startOver)
                AuthState.RegistrationRequired ->
                    DeadEnd(stringResource(R.string.login_registration_required), busy, viewModel::startOver)
                AuthState.PremiumRequired ->
                    DeadEnd(stringResource(R.string.login_premium_required), busy, viewModel::startOver)
                is AuthState.Failed -> FailedStep(
                    error = s.error.message.orEmpty(),
                    busy = busy,
                    onReset = viewModel::resetTelegramData,
                    onChangeKeys = viewModel::chooseOwnKeys.takeIf { viewModel.canChooseKeys },
                )
                AuthState.Starting, AuthState.LoggingOut, AuthState.Closed, AuthState.Ready -> Waiting()
            }
            error?.let { ErrorText(it) }
            // API_ID_INVALID and the like only show up once the phone number is sent. With the built-in
            // keys blocked by Telegram, a user's own keys are the way back in.
            if (viewModel.canChooseKeys && error.isApiKeyProblem()) {
                TextButton(onClick = viewModel::chooseOwnKeys, enabled = !busy) {
                    Text(stringResource(if (viewModel.usesOwnKeys) R.string.login_change_api_keys else R.string.login_use_own_keys))
                }
            }
        }
    }
}

private fun LoginError?.isApiKeyProblem() = this is LoginError.Telegram && code.startsWith("API_ID")

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Waiting() = Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
    HeroAnimation(Hero.LOADING, size = 120.dp)
}

@Composable
private fun SubmitButton(text: String, enabled: Boolean, onClick: () -> Unit) = PillButton(text, onClick, enabled = enabled)

@Composable
private fun StartOverButton(busy: Boolean, onStartOver: () -> Unit) =
    TextButton(onClick = onStartOver, enabled = !busy) { Text(stringResource(R.string.login_start_over)) }

/**
 * Under the sign-in steps: switch to keys of one's own, or back to the built-in ones. Quiet on purpose:
 * the built-in keys are the way most people go, and own keys are for those who want them.
 */
@Composable
private fun KeysChoice(viewModel: LoginViewModel, busy: Boolean) {
    if (!viewModel.canChooseKeys || !viewModel.hasBuiltInKeys) return
    if (viewModel.usesOwnKeys) {
        TextButton(onClick = viewModel::useBuiltInKeys, enabled = !busy) { Text(stringResource(R.string.login_use_built_in_keys)) }
    } else {
        TextButton(onClick = viewModel::chooseOwnKeys, enabled = !busy) { Text(stringResource(R.string.login_use_own_keys)) }
    }
}

@Composable
private fun ApiKeysStep(busy: Boolean, onSubmit: (String, String) -> Unit, onBuiltIn: (() -> Unit)?) {
    var apiId by rememberSaveable { mutableStateOf("") }
    var apiHash by rememberSaveable { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    Title(stringResource(R.string.login_api_keys_title))
    Body(stringResource(if (onBuiltIn != null) R.string.login_api_keys_optional else R.string.login_api_keys_explanation))
    Body(stringResource(R.string.login_api_keys_steps))
    TextButton(onClick = { uriHandler.openUri("https://my.telegram.org") }) {
        Text(stringResource(R.string.login_api_keys_open_site))
    }
    OutlinedTextField(
        value = apiId,
        onValueChange = { apiId = it.filter(Char::isDigit) },
        label = { Text(stringResource(R.string.login_api_id)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = apiHash,
        onValueChange = { apiHash = it.trim() },
        label = { Text(stringResource(R.string.login_api_hash)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
    SubmitButton(stringResource(R.string.login_continue), !busy && apiId.isNotEmpty() && apiHash.isNotEmpty()) {
        onSubmit(apiId, apiHash)
    }
    onBuiltIn?.let { TextButton(onClick = it, enabled = !busy) { Text(stringResource(R.string.login_use_built_in_keys)) } }
}

@Composable
private fun PhoneStep(busy: Boolean, onSubmit: (String) -> Unit, onQr: () -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    Title(stringResource(R.string.login_phone_title))
    Body(stringResource(R.string.login_phone_explanation))
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text(stringResource(R.string.login_phone_number)) },
        placeholder = { Text("+1 234 567 8900") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    SubmitButton(stringResource(R.string.login_continue), !busy && phone.count(Char::isDigit) >= 5) { onSubmit(phone) }
    TextButton(onClick = onQr, enabled = !busy) { Text(stringResource(R.string.login_use_qr)) }
}

@Composable
private fun QrStep(link: String, busy: Boolean, onStartOver: () -> Unit) {
    Title(stringResource(R.string.login_qr_title))
    Body(stringResource(R.string.login_qr_explanation))
    QrCode(link, Modifier.fillMaxWidth().padding(horizontal = 24.dp))
    TextButton(onClick = onStartOver, enabled = !busy) { Text(stringResource(R.string.login_use_phone)) }
}

@Composable
private fun CodeStep(
    state: AuthState.WaitCode,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onUseQr: () -> Unit,
    onStartOver: () -> Unit,
) {
    var code by rememberSaveable(state.phoneNumber) { mutableStateOf("") }
    Title(stringResource(R.string.login_code_title))
    Body(
        when (state.delivery) {
            CodeDelivery.TELEGRAM_APP -> stringResource(R.string.login_code_in_app)
            CodeDelivery.SMS -> stringResource(R.string.login_code_sms, state.phoneNumber)
            CodeDelivery.CALL -> stringResource(R.string.login_code_call, state.phoneNumber)
            CodeDelivery.FRAGMENT -> stringResource(R.string.login_code_fragment)
            else -> stringResource(R.string.login_code_other)
        },
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isLetterOrDigit) },
        label = { Text(stringResource(R.string.login_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    SubmitButton(stringResource(R.string.login_continue), !busy && code.isNotEmpty()) { onSubmit(code) }
    if (state.canResend) {
        TextButton(onClick = onResend, enabled = !busy) { Text(stringResource(R.string.login_code_resend)) }
    }
    // Telegram sometimes says a code is on its way and never sends it (seen 2026-11-09, own API keys);
    // nothing in the reply tells that apart, so the screen says what to try.
    Text(stringResource(R.string.login_code_missing_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.login_code_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = onUseQr, enabled = !busy) { Text(stringResource(R.string.login_use_qr)) }
    StartOverButton(busy, onStartOver)
}

@Composable
private fun EmailStep(busy: Boolean, onSubmit: (String) -> Unit, onStartOver: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    Title(stringResource(R.string.login_email_title))
    Body(stringResource(R.string.login_email_explanation))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(stringResource(R.string.login_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    SubmitButton(stringResource(R.string.login_continue), !busy && '@' in email) { onSubmit(email) }
    StartOverButton(busy, onStartOver)
}

@Composable
private fun EmailCodeStep(state: AuthState.WaitEmailCode, busy: Boolean, onSubmit: (String) -> Unit, onStartOver: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    Title(stringResource(R.string.login_code_title))
    Body(stringResource(R.string.login_email_code_explanation, state.emailPattern))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isLetterOrDigit) },
        label = { Text(stringResource(R.string.login_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    SubmitButton(stringResource(R.string.login_continue), !busy && code.isNotEmpty()) { onSubmit(code) }
    StartOverButton(busy, onStartOver)
}

@Composable
private fun PasswordStep(state: AuthState.WaitPassword, busy: Boolean, onSubmit: (String) -> Unit, onStartOver: () -> Unit) {
    // Not saved across process death: a password does not belong in the saved instance state.
    var password by remember { mutableStateOf("") }
    Title(stringResource(R.string.login_password_title))
    Body(stringResource(R.string.login_password_explanation))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.login_password)) },
        supportingText = state.hint.takeIf { it.isNotBlank() }?.let { { Text(stringResource(R.string.login_password_hint, it)) } },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    SubmitButton(stringResource(R.string.login_continue), !busy && password.isNotEmpty()) { onSubmit(password) }
    StartOverButton(busy, onStartOver)
}

@Composable
private fun DeadEnd(message: String, busy: Boolean, onStartOver: () -> Unit) {
    Body(message)
    StartOverButton(busy, onStartOver)
}

@Composable
private fun FailedStep(error: String, busy: Boolean, onReset: () -> Unit, onChangeKeys: (() -> Unit)?) {
    Title(stringResource(R.string.login_failed_title))
    Body(stringResource(R.string.login_failed_explanation, error))
    SubmitButton(stringResource(R.string.login_failed_reset), !busy, onReset)
    onChangeKeys?.let { TextButton(onClick = it, enabled = !busy) { Text(stringResource(R.string.login_change_api_keys)) } }
}

@Composable
private fun ErrorText(error: LoginError) {
    val text = when (error) {
        LoginError.InvalidApiKeys -> stringResource(R.string.login_error_api_keys_format)
        is LoginError.FloodWait -> stringResource(R.string.login_error_flood_wait, formatWait(error.seconds))
        is LoginError.Telegram -> when (error.code) {
            "PHONE_NUMBER_INVALID" -> stringResource(R.string.login_error_phone_invalid)
            "PHONE_NUMBER_BANNED" -> stringResource(R.string.login_error_phone_banned)
            "PHONE_CODE_INVALID", "CODE_INVALID", "EMAIL_CODE_INVALID" -> stringResource(R.string.login_error_code_invalid)
            "PHONE_CODE_EXPIRED", "CODE_EXPIRED" -> stringResource(R.string.login_error_code_expired)
            "PASSWORD_HASH_INVALID" -> stringResource(R.string.login_error_password_invalid)
            "API_ID_INVALID" -> stringResource(R.string.login_error_api_id_invalid)
            "API_ID_PUBLISHED_FLOOD" -> stringResource(R.string.login_error_api_id_published)
            else -> stringResource(R.string.login_error_other, error.code)
        }
    }
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

private fun formatWait(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${(seconds + 59) / 60} min"
    else -> "${(seconds + 3599) / 3600} h"
}
