package me.esdex.coffer.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.esdex.coffer.core.tdlib.PhoneCountry
import me.esdex.coffer.core.tdlib.PhoneNumberInfo
import me.esdex.coffer.core.ui.components.Hero
import me.esdex.coffer.core.ui.components.HeroAnimation
import me.esdex.coffer.core.ui.components.PasswordField
import me.esdex.coffer.core.ui.components.PillButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.CodeDelivery
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion
import kotlinx.coroutines.delay
import android.telephony.TelephonyManager
import java.util.Locale

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
                            PhoneStep(viewModel, busy, errorText, viewModel::submitPhoneNumber, viewModel::useQrCode)
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
private fun PhoneStep(viewModel: LoginViewModel, busy: Boolean, errorText: String?, onSubmit: (String) -> Unit, onQr: () -> Unit) {
    // With its cursor, so a move back from the number puts it at the end of the code.
    var codeField by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val code = codeField.text
    // With its cursor too: digits moved here from the code put it after them, not before (seen 2026-11-13).
    var numberField by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val number = numberField.text
    val countries by produceState(emptyList<PhoneCountry>()) { value = viewModel.countries() }
    val codes = remember(countries) { countries.flatMapTo(HashSet()) { it.callingCodes } }
    // The phone's own country, from its SIM or else its language, for a calling code several countries share.
    val context = LocalContext.current
    val region = remember {
        context.getSystemService(TelephonyManager::class.java)?.simCountryIso?.takeIf { it.length == 2 } ?: Locale.getDefault().country
    }
    // Asked again a moment after typing stops: the flag can change as the number goes on (+7 is two countries).
    val info by produceState<PhoneNumberInfo?>(null, code, number) {
        delay(INFO_DELAY_MS)
        value = viewModel.phoneNumberInfo(code + number)
    }
    // Telegram's layout only while it is for this code: an answer a keystroke old may have parsed it otherwise.
    // Telegram's reading, when it fits the fields: the same code, or a longer one the number begins (the Bahamas).
    val read = info?.let { PhoneFormat.reading(code, number, it.callingCode, it.pattern) }
    val parsed = info?.takeIf { read != null }
    val pattern = PhoneFormat.patternFor(code, read, parsed?.countryHidden == true)
    val codeFocus = remember { FocusRequester() }
    val numberFocus = remember { FocusRequester() }
    var codeFocused by remember { mutableStateOf(false) }
    fun backToCode() {
        codeField = codeField.copy(selection = TextRange(codeField.text.length))
        codeFocus.requestFocus()
    }
    // One field cut in two, as the user sees it: square where the halves meet, one border between them,
    // and the half with the cursor drawn over the other so its border shows whole.
    val corner = MaterialTheme.shapes.extraSmall
    // An account and its key, rather than a wave.
    SignInHero()
    StepTitle(stringResource(R.string.login_phone_title))
    StepBody(stringResource(R.string.login_phone_explanation))
    Row {
        OutlinedTextField(
            value = codeField,
            onValueChange = { typed ->
                val split = PhoneFormat.split(typed.text.filter(Char::isDigit), codes)
                codeField = TextFieldValue(split.code, TextRange(split.code.length))
                if (split.rest.isNotEmpty()) numberField = TextFieldValue(split.rest, TextRange(split.rest.length))
                // A settled code goes on to the number at once, as in Telegram's apps.
                if (split.settled) numberFocus.requestFocus()
            },
            label = { Text(stringResource(R.string.login_phone_code)) },
            prefix = { Text("+") },
            // The flag only for the code in the field: one worked out for another reading of the digits misleads.
            leadingIcon = PhoneFormat.countryFor(code, number.isNotEmpty(), parsed?.countryCode, parsed?.countryHidden == true, countries, region)
                ?.takeIf { code in codes }
                ?.let(PhoneFormat::flag)
                ?.let { flag -> { Text(flag, style = MaterialTheme.typography.titleLarge) } },
            singleLine = true,
            isError = errorText != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            shape = corner.copy(topEnd = CornerSize(0), bottomEnd = CornerSize(0)),
            modifier = Modifier.width(CODE_FIELD).focusRequester(codeFocus).onFocusChanged { codeFocused = it.isFocused }.zIndex(if (codeFocused) 1f else 0f),
        )
        val hint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        OutlinedTextField(
            value = numberField,
            onValueChange = { typed ->
                val digits = typed.text.filter(Char::isDigit)
                // Erased down to nothing: the erasing goes on in the code, as if the two were one field.
                // On the change itself, as a keyboard need not report a backspace on an empty field.
                if (digits.isEmpty() && numberField.text.isNotEmpty()) backToCode()
                // Anything not a digit is dropped; the cursor keeps its place among the digits.
                val cursor = typed.text.take(typed.selection.end).count(Char::isDigit)
                numberField = TextFieldValue(digits, TextRange(cursor))
            },
            label = { Text(stringResource(R.string.login_phone_number)) },
            singleLine = true,
            isError = errorText != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            visualTransformation = PhoneTransformation(pattern, hint),
            shape = corner.copy(topStart = CornerSize(0), bottomStart = CornerSize(0)),
            modifier = Modifier.weight(1f)
                // Over the code half's right border, so the two share one line.
                .offset(x = (-1).dp)
                .focusRequester(numberFocus)
                // Erasing an empty number changes nothing, so no change is reported; the key itself is.
                // Right after the code moved the cursor here, the number is empty and this is the only
                // sign the user wants back (seen 2026-11-13: 48, then erase, stayed put).
                .onPreviewKeyEvent { event ->
                    val erase = event.key == Key.Backspace && event.type == KeyEventType.KeyDown && numberField.text.isEmpty()
                    if (erase) backToCode()
                    erase
                }
                .onFocusChanged { focus ->
                    // The number waits for its code: a tap here with none sends the typing there first,
                    // and a whole code sends it back (the code field's own jump). Without Telegram's list of
                    // codes only an empty one counts, or the number could not be reached at all.
                    // The code as it is now, not as this composition saw it: the code field settles the code and
                    // moves the focus here in one go, before anything is drawn again. Read from the composition,
                    // it was still "12" when "121" had already made it 1, and sent the cursor back (seen 2026-11-13).
                    val now = codeField.text
                    val noCode = now.isEmpty() || (codes.isNotEmpty() && now !in codes)
                    if (focus.isFocused && noCode) backToCode()
                }
                .zIndex(if (codeFocused) 0f else 1f),
        )
    }
    StepError(errorText)
    SubmitButton(stringResource(R.string.login_continue), code.isNotEmpty() && (code + number).length >= 5, busy) { onSubmit("+$code$number") }
    SecondaryAction(stringResource(R.string.login_use_qr), !busy, onQr)
}

/**
 * Shows the digits typed in the country's layout, the rest still expected as a faint hint, as Telegram's
 * own apps do; the field itself holds digits only (see [PhoneFormat]).
 */
private class PhoneTransformation(private val pattern: String?, private val hint: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val layout = PhoneFormat.layout(text.text, pattern)
        val shown = buildAnnotatedString {
            append(layout.text.take(layout.typedLength))
            withStyle(SpanStyle(color = hint)) { append(layout.text.drop(layout.typedLength)) }
        }
        return TransformedText(
            shown,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = layout.toShown(offset)
                override fun transformedToOriginal(offset: Int) = layout.toTyped(offset)
            },
        )
    }

    // A new instance each recomposition must not count as a change of transformation.
    override fun equals(other: Any?) = other is PhoneTransformation && other.pattern == pattern && other.hint == hint
    override fun hashCode() = 31 * pattern.hashCode() + hint.hashCode()
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

/** Room for "+1242" and a flag. */
private val CODE_FIELD = 128.dp

// Long enough not to ask Telegram at every keystroke, short enough that the layout keeps up.
private const val INFO_DELAY_MS = 150L
