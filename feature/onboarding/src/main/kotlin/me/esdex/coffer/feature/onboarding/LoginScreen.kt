package me.esdex.coffer.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.esdex.coffer.core.ui.components.Hero
import me.esdex.coffer.core.ui.components.HeroAnimation
import me.esdex.coffer.core.ui.components.PasswordField
import me.esdex.coffer.core.ui.components.PillButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.CodeDelivery
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion

/** Sign-in to Telegram, every step up to [AuthState.Ready] (SPEC §7, steps 2 and 3). */
@Composable
fun LoginScreen(modifier: Modifier = Modifier, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val errorText = error?.let { loginErrorText(it) }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // A step of its own slides in; the same step updated (a new QR link, a resent code) stays put.
            AnimatedContent(
                state,
                contentKey = { it::class },
                transitionSpec = { stepSlide(forward = order(targetState) >= order(initialState)) },
                label = "sign-in step",
            ) { s ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (s) {
                        AuthState.NeedApiCredentials -> ApiKeysStep(
                            busy,
                            errorText,
                            viewModel::submitApiKeys,
                            onBuiltIn = viewModel::useBuiltInKeys.takeIf { viewModel.hasBuiltInKeys },
                        )
                        AuthState.WaitPhoneNumber -> {
                            PhoneStep(busy, errorText, viewModel::submitPhoneNumber, viewModel::useQrCode)
                            KeysChoice(viewModel, busy)
                        }
                        is AuthState.WaitQrConfirmation -> QrStep(s.link, busy, errorText, viewModel::startOver)
                        is AuthState.WaitCode -> CodeStep(s, busy, errorText, viewModel::submitCode, viewModel::resendCode, viewModel::useQrCode, viewModel::startOver)
                        AuthState.WaitEmailAddress -> EmailStep(busy, errorText, viewModel::submitEmailAddress, viewModel::startOver)
                        is AuthState.WaitEmailCode -> EmailCodeStep(s, busy, errorText, viewModel::submitEmailCode, viewModel::startOver)
                        is AuthState.WaitPassword -> PasswordStep(s, busy, errorText, viewModel::submitPassword, viewModel::startOver)
                        AuthState.RegistrationRequired ->
                            DeadEnd(stringResource(R.string.login_registration_required), busy, errorText, viewModel::startOver)
                        AuthState.PremiumRequired ->
                            DeadEnd(stringResource(R.string.login_premium_required), busy, errorText, viewModel::startOver)
                        is AuthState.Failed -> FailedStep(
                            error = s.error.message.orEmpty(),
                            busy = busy,
                            onReset = viewModel::resetTelegramData,
                            onChangeKeys = viewModel::chooseOwnKeys.takeIf { viewModel.canChooseKeys },
                        )
                        AuthState.Starting, AuthState.LoggingOut, AuthState.Closed, AuthState.Ready -> Waiting()
                    }
                }
            }
            // API_ID_INVALID and the like only show up once the phone number is sent. With the built-in
            // keys blocked by Telegram, a user's own keys are the way back in.
            if (viewModel.canChooseKeys && error.isApiKeyProblem()) {
                SecondaryAction(
                    stringResource(if (viewModel.usesOwnKeys) R.string.login_change_api_keys else R.string.login_use_own_keys),
                    enabled = !busy,
                    onClick = viewModel::chooseOwnKeys,
                )
            }
        }
    }
}

/** Where a step comes in the sign-in, so going on slides forward and starting over slides back. */
internal fun order(state: AuthState): Int = when (state) {
    AuthState.Starting -> 0
    AuthState.NeedApiCredentials -> 1
    AuthState.WaitPhoneNumber -> 2
    is AuthState.WaitQrConfirmation, is AuthState.WaitCode -> 3
    AuthState.WaitEmailAddress -> 4
    is AuthState.WaitEmailCode -> 5
    is AuthState.WaitPassword -> 6
    AuthState.RegistrationRequired, AuthState.PremiumRequired, is AuthState.Failed -> 7
    AuthState.Ready, AuthState.LoggingOut, AuthState.Closed -> 8
}

private fun LoginError?.isApiKeyProblem() = this is LoginError.Telegram && code.startsWith("API_ID")

@Composable
private fun Waiting() = Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
    HeroAnimation(Hero.LOADING, size = 120.dp)
}

@Composable
private fun SubmitButton(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) = PillButton(text, onClick, enabled = enabled, loading = busy)

@Composable
private fun StartOverButton(busy: Boolean, onStartOver: () -> Unit) = SecondaryAction(stringResource(R.string.login_start_over), !busy, onStartOver)

/**
 * Under the sign-in steps: switch to keys of one's own, or back to the built-in ones. Quiet on purpose:
 * the built-in keys are the way most people go, and own keys are for those who want them.
 */
@Composable
private fun KeysChoice(viewModel: LoginViewModel, busy: Boolean) {
    if (!viewModel.canChooseKeys || !viewModel.hasBuiltInKeys) return
    if (viewModel.usesOwnKeys) {
        SecondaryAction(stringResource(R.string.login_use_built_in_keys), !busy, viewModel::useBuiltInKeys)
    } else {
        SecondaryAction(stringResource(R.string.login_use_own_keys), !busy, viewModel::chooseOwnKeys)
    }
}

@Composable
private fun ApiKeysStep(busy: Boolean, errorText: String?, onSubmit: (String, String) -> Unit, onBuiltIn: (() -> Unit)?) {
    var apiId by rememberSaveable { mutableStateOf("") }
    var apiHash by rememberSaveable { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    StepHero(Hero.SHIELD)
    StepTitle(stringResource(R.string.login_api_keys_title))
    StepBody(stringResource(if (onBuiltIn != null) R.string.login_api_keys_optional else R.string.login_api_keys_explanation))
    StepBody(stringResource(R.string.login_api_keys_steps))
    SecondaryAction(stringResource(R.string.login_api_keys_open_site)) { uriHandler.openUri("https://my.telegram.org") }
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
        isError = errorText != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
    )
    StepError(errorText)
    SubmitButton(stringResource(R.string.login_continue), apiId.isNotEmpty() && apiHash.isNotEmpty(), busy) {
        onSubmit(apiId, apiHash)
    }
    onBuiltIn?.let { SecondaryAction(stringResource(R.string.login_use_built_in_keys), !busy, it) }
}

@Composable
private fun PhoneStep(busy: Boolean, errorText: String?, onSubmit: (String) -> Unit, onQr: () -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    StepHero(Hero.HELLO)
    StepTitle(stringResource(R.string.login_phone_title))
    StepBody(stringResource(R.string.login_phone_explanation))
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text(stringResource(R.string.login_phone_number)) },
        placeholder = { Text("+1 234 567 8900") },
        singleLine = true,
        isError = errorText != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    StepError(errorText)
    SubmitButton(stringResource(R.string.login_continue), phone.count(Char::isDigit) >= 5, busy) { onSubmit(phone) }
    SecondaryAction(stringResource(R.string.login_use_qr), !busy, onQr)
}

@Composable
private fun QrStep(link: String, busy: Boolean, errorText: String?, onStartOver: () -> Unit) {
    StepTitle(stringResource(R.string.login_qr_title))
    StepBody(stringResource(R.string.login_qr_explanation))
    // On a white card of its own in every theme; a new link from Telegram fades in over the old one.
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).clip(RoundedCornerShape(Corners.Large)).background(Color.White).padding(8.dp)) {
        Crossfade(link, animationSpec = Motion.standard(), label = "qr") { QrCode(it, Modifier.fillMaxWidth()) }
    }
    StepError(errorText)
    SecondaryAction(stringResource(R.string.login_use_phone), !busy, onStartOver)
}

@Composable
private fun CodeStep(
    state: AuthState.WaitCode,
    busy: Boolean,
    errorText: String?,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
    onUseQr: () -> Unit,
    onStartOver: () -> Unit,
) {
    var code by rememberSaveable(state.phoneNumber) { mutableStateOf("") }
    StepHero(Hero.SHIELD)
    StepTitle(stringResource(R.string.login_code_title))
    StepBody(
        when (state.delivery) {
            CodeDelivery.TELEGRAM_APP -> stringResource(R.string.login_code_in_app)
            CodeDelivery.SMS -> stringResource(R.string.login_code_sms, state.phoneNumber)
            CodeDelivery.CALL -> stringResource(R.string.login_code_call, state.phoneNumber)
            CodeDelivery.FRAGMENT -> stringResource(R.string.login_code_fragment)
            else -> stringResource(R.string.login_code_other)
        },
    )
    val length = state.length?.takeIf { it in CodeCells.LENGTHS }
    if (length != null) {
        CodeCells(code, length, onChange = { code = it }, isError = errorText != null)
    } else {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isLetterOrDigit) },
            label = { Text(stringResource(R.string.login_code)) },
            singleLine = true,
            isError = errorText != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    StepError(errorText)
    SubmitButton(stringResource(R.string.login_continue), if (length != null) code.length == length else code.isNotEmpty(), busy) { onSubmit(code) }
    if (state.canResend) SecondaryAction(stringResource(R.string.login_code_resend), !busy, onResend)
    // Telegram sometimes says a code is on its way and never sends it (seen 2026-11-09, own API keys);
    // nothing in the reply tells that apart, so the screen says what to try.
    Text(stringResource(R.string.login_code_missing_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.login_code_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SecondaryAction(stringResource(R.string.login_use_qr), !busy, onUseQr)
    StartOverButton(busy, onStartOver)
}

@Composable
private fun EmailStep(busy: Boolean, errorText: String?, onSubmit: (String) -> Unit, onStartOver: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    StepTitle(stringResource(R.string.login_email_title))
    StepBody(stringResource(R.string.login_email_explanation))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(stringResource(R.string.login_email)) },
        singleLine = true,
        isError = errorText != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    StepError(errorText)
    SubmitButton(stringResource(R.string.login_continue), '@' in email, busy) { onSubmit(email) }
    StartOverButton(busy, onStartOver)
}

@Composable
private fun EmailCodeStep(state: AuthState.WaitEmailCode, busy: Boolean, errorText: String?, onSubmit: (String) -> Unit, onStartOver: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    StepTitle(stringResource(R.string.login_code_title))
    StepBody(stringResource(R.string.login_email_code_explanation, state.emailPattern))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isLetterOrDigit) },
        label = { Text(stringResource(R.string.login_code)) },
        singleLine = true,
        isError = errorText != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    StepError(errorText)
    SubmitButton(stringResource(R.string.login_continue), code.isNotEmpty(), busy) { onSubmit(code) }
    StartOverButton(busy, onStartOver)
}

@Composable
private fun PasswordStep(state: AuthState.WaitPassword, busy: Boolean, errorText: String?, onSubmit: (String) -> Unit, onStartOver: () -> Unit) {
    // Not saved across process death: a password does not belong in the saved instance state.
    var password by remember { mutableStateOf("") }
    StepHero(Hero.SHIELD)
    StepTitle(stringResource(R.string.login_password_title))
    StepBody(stringResource(R.string.login_password_explanation))
    PasswordField(password, stringResource(R.string.login_password), { password = it }, isError = errorText != null)
    state.hint.takeIf { it.isNotBlank() }?.let {
        Text(stringResource(R.string.login_password_hint, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    StepError(errorText)
    SubmitButton(stringResource(R.string.login_continue), password.isNotEmpty(), busy) { onSubmit(password) }
    StartOverButton(busy, onStartOver)
}

@Composable
private fun DeadEnd(message: String, busy: Boolean, errorText: String?, onStartOver: () -> Unit) {
    StepBody(message)
    StepError(errorText)
    StartOverButton(busy, onStartOver)
}

@Composable
private fun FailedStep(error: String, busy: Boolean, onReset: () -> Unit, onChangeKeys: (() -> Unit)?) {
    StepTitle(stringResource(R.string.login_failed_title))
    StepBody(stringResource(R.string.login_failed_explanation, error))
    SubmitButton(stringResource(R.string.login_failed_reset), true, busy, onReset)
    onChangeKeys?.let { SecondaryAction(stringResource(R.string.login_change_api_keys), !busy, it) }
}

/** What a sign-in error says, in words; shown under the field it is about. */
@Composable
private fun loginErrorText(error: LoginError): String = when (error) {
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

private fun formatWait(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${(seconds + 59) / 60} min"
    else -> "${(seconds + 3599) / 3600} h"
}
