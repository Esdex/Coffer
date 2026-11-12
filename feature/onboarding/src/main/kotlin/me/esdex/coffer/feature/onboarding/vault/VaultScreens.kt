package me.esdex.coffer.feature.onboarding.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.crypto.bip39.Bip39
import me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException
import me.esdex.coffer.core.ui.components.Hero
import me.esdex.coffer.core.ui.components.HeroAnimation
import me.esdex.coffer.core.ui.components.PasswordField
import me.esdex.coffer.core.ui.components.PillButton
import me.esdex.coffer.core.ui.components.RecoveryWords
import me.esdex.coffer.core.ui.components.StrengthMeter as SharedStrengthMeter
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion
import me.esdex.coffer.core.ui.theme.tabular
import me.esdex.coffer.core.vault.DeviceUnlock
import me.esdex.coffer.core.vault.PasswordPolicy
import me.esdex.coffer.core.vault.RecoveryQuiz
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.feature.onboarding.R
import me.esdex.coffer.feature.onboarding.SecondaryAction
import me.esdex.coffer.feature.onboarding.StepBody
import me.esdex.coffer.feature.onboarding.StepError
import me.esdex.coffer.feature.onboarding.StepHero
import me.esdex.coffer.feature.onboarding.StepTitle
import me.esdex.coffer.feature.onboarding.stepSlide
import javax.crypto.Cipher

/**
 * Everything between "signed in to Telegram" and "vault open": finding the vault, creating it or
 * unlocking it (SPEC §7, step 4). Shows [content] once the vault is open.
 */
@Composable
fun VaultGate(modifier: Modifier = Modifier, viewModel: VaultViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offer by viewModel.offerDeviceUnlock.collectAsStateWithLifecycle()
    val open = state is VaultManager.State.Unlocked && !offer
    // Opening, the files come up with a slight zoom, as a viewer does. Locking, they go at once: nothing
    // of the vault may linger on screen while it fades.
    AnimatedContent(
        open,
        transitionSpec = {
            if (targetState) {
                (fadeIn(Motion.screen()) + scaleIn(Motion.screen(), initialScale = OPEN_SCALE)) togetherWith fadeOut(Motion.quick())
            } else {
                EnterTransition.None togetherWith ExitTransition.None
            }
        },
        label = "vault",
    ) { unlocked ->
        if (unlocked) content() else Gate(modifier, viewModel, state)
    }
}

@Composable
private fun Gate(modifier: Modifier, viewModel: VaultViewModel, state: VaultManager.State) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val errorText = error?.let { vaultErrorText(it) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AnimatedContent(
                state,
                contentKey = { it::class },
                transitionSpec = { stepSlide(forward = true) },
                label = "vault step",
            ) { s ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (s) {
                        VaultManager.State.Unknown -> Searching(null, errorText)
                        is VaultManager.State.Searching -> Searching(s.scanned, errorText)
                        VaultManager.State.NotFound -> CreateFlow(viewModel, busy, errorText)
                        is VaultManager.State.Locked -> UnlockStep(viewModel, busy, errorText, s.passwordChangedElsewhere)
                        is VaultManager.State.Unlocked -> OfferDeviceUnlock(viewModel, errorText)
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress() = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    HeroAnimation(Hero.LOADING, size = 96.dp)
}

@Composable
private fun WideButton(text: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) = PillButton(text, onClick, enabled = enabled, loading = busy)

@Composable
private fun Searching(scanned: Int?, errorText: String?) {
    StepTitle(stringResource(R.string.vault_searching_title))
    StepBody(stringResource(R.string.vault_searching_explanation))
    Progress()
    if (scanned != null && scanned > 0) {
        Text(pluralStringResource(R.plurals.vault_searching_scanned, scanned, scanned), style = MaterialTheme.typography.bodyMedium.tabular())
    }
    StepError(errorText)
}

// Unlock

/** Runs the system prompt for [cipher]; [onDone] gets the authenticated cipher, or null if cancelled. */
private fun FragmentActivity.authenticate(cipher: Cipher, title: String, cancel: String, onDone: (Cipher?) -> Unit) {
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onDone(result.cryptoObject?.cipher)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onDone(null)
            }
        },
    )
    prompt.authenticate(DeviceUnlock.promptInfo(title, cancel), BiometricPrompt.CryptoObject(cipher))
}

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}

@Composable
private fun UnlockStep(viewModel: VaultViewModel, busy: Boolean, errorText: String?, passwordChangedElsewhere: Boolean = false) {
    var useRecoveryKey by remember { mutableStateOf(false) }
    // The recovery key is a step of its own within the unlock: it slides in, and back out.
    AnimatedContent(useRecoveryKey, transitionSpec = { stepSlide(forward = targetState) }, label = "unlock way") { recovery ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (recovery) {
                RecoveryKeyUnlock(viewModel, busy, errorText) { useRecoveryKey = false }
            } else {
                PasswordUnlock(viewModel, busy, errorText, passwordChangedElsewhere) {
                    viewModel.dismissError()
                    useRecoveryKey = true
                }
            }
        }
    }
}

@Composable
private fun PasswordUnlock(viewModel: VaultViewModel, busy: Boolean, errorText: String?, passwordChangedElsewhere: Boolean, onRecoveryKey: () -> Unit) {
    val activity = LocalContext.current.fragmentActivity()
    val promptTitle = stringResource(R.string.vault_unlock_prompt)
    val promptCancel = stringResource(R.string.vault_use_password)
    val promptDevice = {
        val cipher = viewModel.deviceUnlockCipher()
        if (cipher != null && activity != null) {
            activity.authenticate(cipher, promptTitle, promptCancel) { done -> done?.let(viewModel::completeDeviceUnlock) }
        }
    }
    // Biometric unlock, when enabled, comes up by itself.
    LaunchedEffect(Unit) { if (viewModel.deviceUnlockEnabled) promptDevice() }

    var password by remember { mutableStateOf("") }
    StepHero(Hero.VAULT)
    StepTitle(stringResource(R.string.vault_unlock_title))
    StepBody(stringResource(R.string.vault_unlock_explanation))
    if (passwordChangedElsewhere) {
        Text(stringResource(R.string.vault_unlock_password_changed), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
    PasswordField(password, stringResource(R.string.vault_password), { password = it }, isError = errorText != null)
    StepError(errorText)
    WideButton(stringResource(R.string.vault_unlock), password.isNotEmpty(), busy) { viewModel.unlockWithPassword(password) }
    if (viewModel.deviceUnlockEnabled) SecondaryAction(stringResource(R.string.vault_unlock_device), !busy, promptDevice)
    SecondaryAction(stringResource(R.string.vault_use_recovery_key), !busy, onRecoveryKey)
}

@Composable
private fun RecoveryKeyUnlock(viewModel: VaultViewModel, busy: Boolean, errorText: String?, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val words = Bip39.split(text)
    val unknown = words.filterNot(Bip39::isWord)
    StepTitle(stringResource(R.string.vault_recovery_unlock_title))
    StepBody(stringResource(R.string.vault_recovery_unlock_explanation))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.vault_recovery_key)) },
        supportingText = {
            Text(
                if (unknown.isEmpty()) {
                    stringResource(R.string.vault_recovery_word_count, words.size, Bip39.WORD_COUNT)
                } else {
                    stringResource(R.string.vault_recovery_unknown_words, unknown.joinToString(", "))
                },
            )
        },
        isError = unknown.isNotEmpty() || errorText != null,
        minLines = 4,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, capitalization = KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth(),
    )
    StepError(errorText)
    WideButton(stringResource(R.string.vault_unlock), words.size == Bip39.WORD_COUNT && unknown.isEmpty(), busy) {
        viewModel.unlockWithRecoveryKey(text)
    }
    SecondaryAction(stringResource(R.string.vault_use_password), !busy) {
        viewModel.dismissError()
        onBack()
    }
}

@Composable
private fun OfferDeviceUnlock(viewModel: VaultViewModel, errorText: String?) {
    val activity = LocalContext.current.fragmentActivity()
    val promptTitle = stringResource(R.string.vault_device_enable_prompt)
    val promptCancel = stringResource(R.string.vault_device_skip)
    StepTitle(stringResource(R.string.vault_device_title))
    StepBody(stringResource(R.string.vault_device_explanation))
    StepError(errorText)
    WideButton(stringResource(R.string.vault_device_enable), activity != null) {
        val cipher = viewModel.deviceUnlockEnableCipher()
        if (cipher == null || activity == null) {
            viewModel.declineDeviceUnlock()
        } else {
            activity.authenticate(cipher, promptTitle, promptCancel) { done ->
                if (done != null) viewModel.completeEnableDeviceUnlock(done) else viewModel.declineDeviceUnlock()
            }
        }
    }
    SecondaryAction(stringResource(R.string.vault_device_skip), onClick = viewModel::declineDeviceUnlock)
}

// Create

@Composable
private fun CreateFlow(viewModel: VaultViewModel, busy: Boolean, errorText: String?) {
    val step by viewModel.createStep.collectAsStateWithLifecycle()
    AnimatedContent(
        step,
        contentKey = { it::class },
        // Back to the words from the check slides back; everything else goes on.
        transitionSpec = { stepSlide(forward = !(initialState is CreateStep.Confirm && targetState is CreateStep.RecoveryKey)) },
        label = "create step",
    ) { s ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (s) {
                CreateStep.Password -> NewPassword(viewModel, busy, errorText)
                is CreateStep.RecoveryKey -> ShowRecoveryKey(s.words, viewModel::recoveryKeyWrittenDown)
                is CreateStep.Confirm -> ConfirmWords(s.quiz, viewModel, errorText)
                CreateStep.DeviceUnlock -> NewVaultDeviceUnlock(viewModel, errorText)
                CreateStep.Publishing -> {
                    StepTitle(stringResource(R.string.vault_publishing_title))
                    Progress()
                    StepError(errorText)
                }
            }
        }
    }
}

@Composable
private fun NewPassword(viewModel: VaultViewModel, busy: Boolean, errorText: String?) {
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val check = remember(password) { PasswordPolicy.check(password) }
    StepHero(Hero.SHIELD)
    StepTitle(stringResource(R.string.vault_create_title))
    StepBody(stringResource(R.string.vault_create_explanation))
    PasswordField(password, stringResource(R.string.vault_password), { password = it })
    if (password.isNotEmpty()) StrengthMeter(check)
    PasswordField(repeated, stringResource(R.string.vault_password_repeat), { repeated = it }, isError = errorText != null)
    StepError(errorText)
    WideButton(stringResource(R.string.vault_create_continue), check.acceptable && repeated.isNotEmpty(), busy) {
        viewModel.createWithPassword(password, repeated)
    }
}

@Composable
private fun StrengthMeter(check: PasswordPolicy.Check) {
    val labels = listOf(
        R.string.vault_strength_0, R.string.vault_strength_1, R.string.vault_strength_2,
        R.string.vault_strength_3, R.string.vault_strength_4,
    )
    val notes = buildList {
        if (!check.longEnough) add(pluralStringResource(R.plurals.vault_strength_too_short, PasswordPolicy.MIN_LENGTH, PasswordPolicy.MIN_LENGTH))
        check.warning?.let(::add)
        check.suggestions.forEach { add("- $it") }
    }
    SharedStrengthMeter(check.score, check.acceptable, stringResource(labels[check.score]), notes)
}

@Composable
private fun ShowRecoveryKey(words: List<String>, onDone: () -> Unit) {
    var written by remember { mutableStateOf(false) }
    StepTitle(stringResource(R.string.vault_recovery_title))
    StepBody(stringResource(R.string.vault_recovery_explanation))
    RecoveryWords(words)
    // The whole line is the target, not only the box.
    Row(
        Modifier.fillMaxWidth().clip(Corners.pill).toggleable(value = written, role = Role.Checkbox, onValueChange = { written = it }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = written, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        StepBody(stringResource(R.string.vault_recovery_written))
    }
    WideButton(stringResource(R.string.vault_create_continue), written, onClick = onDone)
}

@Composable
private fun ConfirmWords(quiz: RecoveryQuiz, viewModel: VaultViewModel, errorText: String?) {
    val answers = remember(quiz) { quiz.positions.map { mutableStateOf("") } }
    StepTitle(stringResource(R.string.vault_confirm_title))
    StepBody(stringResource(R.string.vault_confirm_explanation))
    quiz.positions.forEachIndexed { i, position ->
        OutlinedTextField(
            value = answers[i].value,
            onValueChange = { answers[i].value = it },
            label = { Text(stringResource(R.string.vault_confirm_word, position + 1)) },
            singleLine = true,
            isError = errorText != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    StepError(errorText)
    WideButton(stringResource(R.string.vault_create_continue), answers.all { it.value.isNotBlank() }) {
        viewModel.confirmWords(quiz, answers.map { it.value })
    }
    SecondaryAction(stringResource(R.string.vault_confirm_show_again), onClick = viewModel::showRecoveryKeyAgain)
}

@Composable
private fun NewVaultDeviceUnlock(viewModel: VaultViewModel, errorText: String?) {
    val activity = LocalContext.current.fragmentActivity()
    val promptTitle = stringResource(R.string.vault_device_enable_prompt)
    val promptCancel = stringResource(R.string.vault_device_skip)
    StepTitle(stringResource(R.string.vault_device_title))
    StepBody(stringResource(R.string.vault_device_explanation))
    StepError(errorText)
    WideButton(stringResource(R.string.vault_device_enable), activity != null) {
        val cipher = viewModel.deviceUnlockEnableCipher()
        if (cipher == null || activity == null) {
            viewModel.skipDeviceUnlockForNewVault()
        } else {
            activity.authenticate(cipher, promptTitle, promptCancel) { done ->
                if (done != null) viewModel.completeEnableForNewVault(done) else viewModel.skipDeviceUnlockForNewVault()
            }
        }
    }
    SecondaryAction(stringResource(R.string.vault_device_skip), onClick = viewModel::skipDeviceUnlockForNewVault)
}

/** What a vault error says, in words; shown under the field it is about. */
@Composable
private fun vaultErrorText(error: VaultError): String = when (error) {
    VaultError.WrongPassword -> stringResource(R.string.vault_error_wrong_password)
    is VaultError.BadRecoveryKey -> when (error.reason) {
        InvalidMnemonicException.Reason.WRONG_WORD_COUNT -> stringResource(R.string.vault_error_word_count)
        InvalidMnemonicException.Reason.UNKNOWN_WORD -> stringResource(R.string.vault_error_unknown_word)
        InvalidMnemonicException.Reason.BAD_CHECKSUM -> stringResource(R.string.vault_error_checksum)
    }
    VaultError.OtherVaultsKey -> stringResource(R.string.vault_error_other_vault)
    VaultError.WordsDoNotMatch -> stringResource(R.string.vault_error_words_mismatch)
    VaultError.PasswordsDiffer -> stringResource(R.string.vault_error_passwords_differ)
    VaultError.VaultAppeared -> stringResource(R.string.vault_error_vault_appeared)
    VaultError.DeviceKeyFailed -> stringResource(R.string.vault_error_device_key)
    VaultError.DeviceKeyInvalidated -> stringResource(R.string.vault_error_device_invalidated)
    is VaultError.Storage -> stringResource(R.string.vault_error_storage, error.message)
}

/** The files coming up as the vault opens: from a little smaller, as a viewer opens. */
private const val OPEN_SCALE = 0.96f
