package me.esdex.coffer.feature.onboarding.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import me.esdex.coffer.core.ui.components.AppDialog
import me.esdex.coffer.core.ui.components.Hero
import me.esdex.coffer.core.ui.components.HeroAnimation
import me.esdex.coffer.core.ui.components.LocalLockFlight
import me.esdex.coffer.core.ui.components.NoScreenshots
import me.esdex.coffer.core.ui.components.Padlock
import me.esdex.coffer.core.ui.components.PasswordField
import me.esdex.coffer.core.ui.components.PillButton
import me.esdex.coffer.core.ui.components.RecoveryWords
import me.esdex.coffer.core.ui.components.StrengthMeter as SharedStrengthMeter
import me.esdex.coffer.core.ui.components.lockFlightTarget
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion
import me.esdex.coffer.core.ui.theme.tabular
import me.esdex.coffer.core.vault.DeviceUnlock
import me.esdex.coffer.core.vault.PasswordPolicy
import me.esdex.coffer.core.vault.RecoveryQuiz
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.feature.onboarding.IntroPage
import me.esdex.coffer.feature.onboarding.IntroPager
import me.esdex.coffer.feature.onboarding.R
import me.esdex.coffer.feature.onboarding.SecondaryAction
import me.esdex.coffer.feature.onboarding.StepBody
import me.esdex.coffer.feature.onboarding.StepError
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
    val notice by viewModel.showSavedMessagesNotice.collectAsStateWithLifecycle()
    // Open, the files show; before them, once each, the offer of biometric unlock and the note on Saved Messages.
    val open = state is VaultManager.State.Unlocked && !offer && !notice
    val flight = LocalLockFlight.current
    // Opening, the files come up with a slight zoom, as a viewer does. Locking goes at once, as nothing
    // of the vault may linger on screen; only when the user locked it themselves, the padlock flying in
    // from the Lock they tapped, the files fade out quickly under it.
    AnimatedContent(
        open,
        transitionSpec = {
            when {
                targetState -> (fadeIn(Motion.screen()) + scaleIn(Motion.screen(), initialScale = OPEN_SCALE)) togetherWith fadeOut(Motion.quick())
                flight.flying -> fadeIn(Motion.standard()) togetherWith fadeOut(Motion.quick())
                else -> EnterTransition.None togetherWith ExitTransition.None
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
    val offer by viewModel.offerDeviceUnlock.collectAsStateWithLifecycle()
    // Open, past the offer of biometric unlock: the note on Saved Messages, a screen of its own like the welcome.
    val notice = state is VaultManager.State.Unlocked && !offer
    AnimatedContent(notice, transitionSpec = { stepSlide(forward = true) }, label = "notice") { showing ->
        if (showing) SavedMessagesNotice(viewModel, modifier) else Steps(modifier, viewModel, state, busy)
    }
}

@Composable
private fun Steps(modifier: Modifier, viewModel: VaultViewModel, state: VaultManager.State, busy: Boolean) {
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
                // The 24 words typed in show in full: the same care as where they are shown.
                NoScreenshots(keepAfter = viewModel.blockScreenshots)
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
    // A padlock, shut: where the one that flies in from Lock lands (see LockFlight).
    val flight = LocalLockFlight.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Padlock(closed = 1f, modifier = Modifier.size(LOCK_HERO).lockFlightTarget(flight))
    }
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
private fun RecoveryKeyUnlock(viewModel: VaultViewModel, busy: Boolean, errorText: String?, onBack: () -> Unit) = RecoveryWordsEntry(
    title = stringResource(R.string.vault_recovery_unlock_title),
    explanation = stringResource(R.string.vault_recovery_unlock_explanation),
    action = stringResource(R.string.vault_unlock),
    busy = busy,
    errorText = errorText,
    onSubmit = viewModel::unlockWithRecoveryKey,
    back = stringResource(R.string.vault_use_password),
    onBack = {
        viewModel.dismissError()
        onBack()
    },
)

/** The 24 words typed in, for unlocking and for bringing back a vault whose header is gone. */
@Composable
private fun RecoveryWordsEntry(
    title: String,
    explanation: String,
    action: String,
    busy: Boolean,
    errorText: String?,
    onSubmit: (String) -> Unit,
    back: String,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val words = Bip39.split(text)
    val unknown = words.filterNot(Bip39::isWord)
    StepTitle(title)
    StepBody(explanation)
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
    WideButton(action, words.size == Bip39.WORD_COUNT && unknown.isEmpty(), busy) { onSubmit(text) }
    SecondaryAction(back, !busy, onBack)
}

/** A new password for a vault brought back by its recovery key (audit 1, M1). */
@Composable
private fun RestorePassword(viewModel: VaultViewModel, busy: Boolean, errorText: String?) {
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val check = remember(password) { PasswordPolicy.check(password) }
    ClosingPadlock(if (password.isEmpty()) 0 else check.meterScore, check.acceptable && password.isNotEmpty())
    StepTitle(stringResource(R.string.vault_restore_password_title))
    StepBody(stringResource(R.string.vault_restore_password_explanation))
    PasswordField(password, stringResource(R.string.vault_password), { password = it })
    if (password.isNotEmpty()) StrengthMeter(check)
    PasswordField(repeated, stringResource(R.string.vault_password_repeat), { repeated = it }, isError = errorText != null)
    StepError(errorText)
    WideButton(stringResource(R.string.vault_restore_action), check.acceptable && repeated.isNotEmpty(), busy) {
        viewModel.restoreWithPassword(password, repeated)
    }
    SecondaryAction(stringResource(R.string.vault_restore_back), !busy, viewModel::cancelRestore)
}

/**
 * What not to touch in Saved Messages, said once per install as the vault first opens, on two pages
 * laid out as the welcome is: Coffer's notes first, as they hold the vault itself (its key, folders and
 * names), then the files' parts.
 */
@Composable
private fun SavedMessagesNotice(viewModel: VaultViewModel, modifier: Modifier) {
    val pages = remember {
        listOf(
            IntroPage(R.string.vault_notes_title, R.string.vault_notes_text) { shown -> NoticeArt(NOTE_ROWS, Icons.Outlined.EditOff, shown) },
            IntroPage(R.string.vault_notice_title, R.string.vault_notice_text) { shown -> NoticeArt(FILE_ROWS, Icons.Outlined.Delete, shown) },
        )
    }
    IntroPager(pages, R.string.vault_notice_ok, modifier, onDone = viewModel::savedMessagesNoticeRead)
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
                is CreateStep.RecoveryKey -> {
                    // Kept off screenshots and the recents thumbnail, as in Settings (SPEC §9).
                    NoScreenshots(keepAfter = viewModel.blockScreenshots)
                    ShowRecoveryKey(s.words, viewModel::recoveryKeyWrittenDown)
                }
                is CreateStep.Confirm -> {
                    // Three of the words typed in: kept off screenshots as where all 24 show (audit 1, AND-4).
                    NoScreenshots(keepAfter = viewModel.blockScreenshots)
                    ConfirmWords(s.quiz, viewModel, errorText)
                }
                CreateStep.DeviceUnlock -> NewVaultDeviceUnlock(viewModel, errorText)
                CreateStep.RestoreWords -> {
                    NoScreenshots(keepAfter = viewModel.blockScreenshots)
                    RecoveryWordsEntry(
                        title = stringResource(R.string.vault_restore_title),
                        explanation = stringResource(R.string.vault_restore_explanation),
                        action = stringResource(R.string.vault_create_continue),
                        busy = busy,
                        errorText = errorText,
                        onSubmit = viewModel::restoreWordsEntered,
                        back = stringResource(R.string.vault_restore_back),
                        onBack = viewModel::cancelRestore,
                    )
                }
                CreateStep.RestorePassword -> RestorePassword(viewModel, busy, errorText)
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
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    ClosingPadlock(if (password.isEmpty()) 0 else check.meterScore, check.acceptable && password.isNotEmpty())
    StepTitle(stringResource(R.string.vault_create_title))
    // Where the vault went, said first: a create screen out of nowhere would look like data loss.
    deleted?.let {
        Text(
            stringResource(if (it == VaultManager.Deleted.HERE) R.string.vault_deleted_here else R.string.vault_deleted_elsewhere),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    StepBody(stringResource(R.string.vault_create_explanation))
    PasswordField(password, stringResource(R.string.vault_password), { password = it })
    if (password.isNotEmpty()) StrengthMeter(check)
    PasswordField(repeated, stringResource(R.string.vault_password_repeat), { repeated = it }, isError = errorText != null)
    StepError(errorText)
    WideButton(stringResource(R.string.vault_create_continue), check.acceptable && repeated.isNotEmpty(), busy) {
        viewModel.createWithPassword(password, repeated)
    }
    // A vault whose header was deleted in Telegram, files still there, comes back by its recovery key.
    SecondaryAction(stringResource(R.string.vault_restore), !busy, viewModel::startRestore)
    // Quietly under the button: making a vault is the way on, another account the way out.
    var confirmLogOut by remember { mutableStateOf(false) }
    SecondaryAction(stringResource(R.string.vault_log_out), !busy) { confirmLogOut = true }
    if (confirmLogOut) {
        AppDialog(
            onDismissRequest = { confirmLogOut = false },
            title = { Text(stringResource(R.string.vault_log_out_title)) },
            text = { Text(stringResource(R.string.vault_log_out_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogOut = false
                    viewModel.logOut()
                }) { Text(stringResource(R.string.vault_log_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmLogOut = false }) { Text(stringResource(R.string.vault_cancel)) } },
        )
    }
}

/**
 * The padlock of the new vault, shutting as the password gets stronger: open over an empty field, its
 * shackle lowered a step with each step of the meter (score 0 to 4), but shut only once the password is
 * [acceptable], with a little jump and a tap under the finger. The same padlock the unlock screen shows
 * shut, and the one Lock sends flying there.
 */
@Composable
private fun ClosingPadlock(score: Int, acceptable: Boolean) {
    val closed by animateFloatAsState(
        when {
            acceptable -> 1f
            // Short of shut until the password will do: the last step is the click.
            else -> score / SCORE_STEPS * NEARLY_SHUT
        },
        Motion.standard(),
        label = "padlock",
    )
    val jump = remember { Animatable(1f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(acceptable) {
        if (!acceptable) return@LaunchedEffect
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        jump.animateTo(JUMP_SCALE, Motion.quick())
        jump.animateTo(1f, Motion.standard())
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Padlock(
            closed = closed,
            modifier = Modifier.size(LOCK_HERO).graphicsLayer {
                scaleX = jump.value
                scaleY = jump.value
            },
        )
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
        // Long enough and with nothing for zxcvbn to say, but still too easy to guess: say what helps.
        if (check.longEnough && !check.hardEnough && check.warning == null && check.suggestions.isEmpty()) add(stringResource(R.string.vault_strength_add_words))
    }
    SharedStrengthMeter(check.meterScore, check.acceptable, stringResource(labels[check.meterScore]), notes)
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

/** The padlock over the unlock screen: the size the other steps' animations have. */
private val LOCK_HERO = 140.dp

/** The strength meter's steps above none (PasswordPolicy scores 0 to 4). */
private const val SCORE_STEPS = 4f

/** How far a strong but not yet acceptable password lowers the shackle: close, not shut. */
private const val NEARLY_SHUT = 0.8f
private const val JUMP_SCALE = 1.08f
