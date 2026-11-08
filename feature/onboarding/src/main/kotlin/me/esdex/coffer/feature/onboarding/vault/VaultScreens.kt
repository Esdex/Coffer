package me.esdex.coffer.feature.onboarding.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.crypto.bip39.Bip39
import me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException
import me.esdex.coffer.core.vault.DeviceUnlock
import me.esdex.coffer.core.vault.PasswordPolicy
import me.esdex.coffer.core.vault.RecoveryQuiz
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.feature.onboarding.R
import javax.crypto.Cipher

/**
 * Everything between "signed in to Telegram" and "vault open": finding the vault, creating it or
 * unlocking it (SPEC §7, step 4). Shows [content] once the vault is open.
 */
@Composable
fun VaultGate(modifier: Modifier = Modifier, viewModel: VaultViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offer by viewModel.offerDeviceUnlock.collectAsStateWithLifecycle()
    if (state is VaultManager.State.Unlocked && !offer) {
        content()
        return
    }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                VaultManager.State.Unknown -> Searching(null)
                is VaultManager.State.Searching -> Searching(s.scanned)
                VaultManager.State.NotFound -> CreateFlow(viewModel, busy)
                is VaultManager.State.Locked -> UnlockStep(viewModel, busy)
                is VaultManager.State.Unlocked -> OfferDeviceUnlock(viewModel)
            }
            error?.let { ErrorText(it) }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Progress() = Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
}

@Composable
private fun WideButton(text: String, enabled: Boolean, onClick: () -> Unit) =
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text) }

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun Searching(scanned: Int?) {
    Title(stringResource(R.string.vault_searching_title))
    Body(stringResource(R.string.vault_searching_explanation))
    Progress()
    if (scanned != null && scanned > 0) Body(stringResource(R.string.vault_searching_scanned, scanned))
}

// Unlock

/** Runs the system prompt for [cipher]; [onDone] gets the authenticated cipher, or null if cancelled. */
private fun FragmentActivity.authenticate(cipher: Cipher, title: String, onDone: (Cipher?) -> Unit) {
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
    prompt.authenticate(DeviceUnlock.promptInfo(title), BiometricPrompt.CryptoObject(cipher))
}

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}

@Composable
private fun UnlockStep(viewModel: VaultViewModel, busy: Boolean) {
    var useRecoveryKey by remember { mutableStateOf(false) }
    if (useRecoveryKey) {
        RecoveryKeyUnlock(viewModel, busy) { useRecoveryKey = false }
        return
    }
    val activity = LocalContext.current.fragmentActivity()
    val promptTitle = stringResource(R.string.vault_unlock_prompt)
    val promptDevice = {
        val cipher = viewModel.deviceUnlockCipher()
        if (cipher != null && activity != null) {
            activity.authenticate(cipher, promptTitle) { done -> done?.let(viewModel::completeDeviceUnlock) }
        }
    }
    // Biometric unlock, when enabled, comes up by itself.
    LaunchedEffect(Unit) { if (viewModel.deviceUnlockEnabled) promptDevice() }

    var password by remember { mutableStateOf("") }
    Title(stringResource(R.string.vault_unlock_title))
    Body(stringResource(R.string.vault_unlock_explanation))
    PasswordField(password, stringResource(R.string.vault_password)) { password = it }
    WideButton(stringResource(R.string.vault_unlock), !busy && password.isNotEmpty()) { viewModel.unlockWithPassword(password) }
    if (busy) Progress()
    if (viewModel.deviceUnlockEnabled) {
        TextButton(onClick = promptDevice, enabled = !busy) { Text(stringResource(R.string.vault_unlock_device)) }
    }
    TextButton(onClick = { viewModel.dismissError(); useRecoveryKey = true }, enabled = !busy) {
        Text(stringResource(R.string.vault_use_recovery_key))
    }
}

@Composable
private fun RecoveryKeyUnlock(viewModel: VaultViewModel, busy: Boolean, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val words = Bip39.split(text)
    val unknown = words.filterNot(Bip39::isWord)
    Title(stringResource(R.string.vault_recovery_unlock_title))
    Body(stringResource(R.string.vault_recovery_unlock_explanation))
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
        isError = unknown.isNotEmpty(),
        minLines = 4,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, capitalization = KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth(),
    )
    WideButton(stringResource(R.string.vault_unlock), !busy && words.size == Bip39.WORD_COUNT && unknown.isEmpty()) {
        viewModel.unlockWithRecoveryKey(text)
    }
    if (busy) Progress()
    TextButton(onClick = { viewModel.dismissError(); onBack() }, enabled = !busy) { Text(stringResource(R.string.vault_use_password)) }
}

@Composable
private fun OfferDeviceUnlock(viewModel: VaultViewModel) {
    val activity = LocalContext.current.fragmentActivity()
    val promptTitle = stringResource(R.string.vault_device_enable_prompt)
    Title(stringResource(R.string.vault_device_title))
    Body(stringResource(R.string.vault_device_explanation))
    WideButton(stringResource(R.string.vault_device_enable), activity != null) {
        val cipher = viewModel.deviceUnlockEnableCipher()
        if (cipher == null || activity == null) {
            viewModel.declineDeviceUnlock()
        } else {
            activity.authenticate(cipher, promptTitle) { done ->
                if (done != null) viewModel.completeEnableDeviceUnlock(done) else viewModel.declineDeviceUnlock()
            }
        }
    }
    TextButton(onClick = viewModel::declineDeviceUnlock) { Text(stringResource(R.string.vault_device_skip)) }
}

// Create

@Composable
private fun CreateFlow(viewModel: VaultViewModel, busy: Boolean) {
    val step by viewModel.createStep.collectAsStateWithLifecycle()
    when (val s = step) {
        CreateStep.Password -> NewPassword(viewModel, busy)
        is CreateStep.RecoveryKey -> ShowRecoveryKey(s.words, viewModel::recoveryKeyWrittenDown)
        is CreateStep.Confirm -> ConfirmWords(s.quiz, viewModel)
        CreateStep.DeviceUnlock -> NewVaultDeviceUnlock(viewModel)
        CreateStep.Publishing -> {
            Title(stringResource(R.string.vault_publishing_title))
            Progress()
        }
    }
}

@Composable
private fun NewPassword(viewModel: VaultViewModel, busy: Boolean) {
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val check = remember(password) { PasswordPolicy.check(password) }
    Title(stringResource(R.string.vault_create_title))
    Body(stringResource(R.string.vault_create_explanation))
    PasswordField(password, stringResource(R.string.vault_password)) { password = it }
    if (password.isNotEmpty()) StrengthMeter(check)
    PasswordField(repeated, stringResource(R.string.vault_password_repeat)) { repeated = it }
    WideButton(stringResource(R.string.vault_create_continue), !busy && check.acceptable && repeated.isNotEmpty()) {
        viewModel.createWithPassword(password, repeated)
    }
    if (busy) Progress()
}

@Composable
private fun StrengthMeter(check: PasswordPolicy.Check) {
    val labels = listOf(
        R.string.vault_strength_0, R.string.vault_strength_1, R.string.vault_strength_2,
        R.string.vault_strength_3, R.string.vault_strength_4,
    )
    val color = if (check.acceptable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    LinearProgressIndicator(
        progress = { (check.score + 1) / 5f },
        color = color,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(labels[check.score]), color = color, style = MaterialTheme.typography.labelLarge)
    if (!check.longEnough) Body(stringResource(R.string.vault_strength_too_short, PasswordPolicy.MIN_LENGTH))
    check.warning?.let { Body(it) }
    check.suggestions.forEach { Body("- $it") }
}

@Composable
private fun ShowRecoveryKey(words: List<String>, onDone: () -> Unit) {
    var written by remember { mutableStateOf(false) }
    Title(stringResource(R.string.vault_recovery_title))
    Body(stringResource(R.string.vault_recovery_explanation))
    // Three columns, numbered top to bottom like a paper backup card.
    val rows = words.size / 3
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (column in 0 until 3) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in 0 until rows) {
                    val index = column * rows + row
                    Text(
                        "%2d. %s".format(index + 1, words[index]),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = written, onCheckedChange = { written = it })
        Body(stringResource(R.string.vault_recovery_written))
    }
    WideButton(stringResource(R.string.vault_create_continue), written, onDone)
}

@Composable
private fun ConfirmWords(quiz: RecoveryQuiz, viewModel: VaultViewModel) {
    val answers = remember(quiz) { quiz.positions.map { mutableStateOf("") } }
    Title(stringResource(R.string.vault_confirm_title))
    Body(stringResource(R.string.vault_confirm_explanation))
    quiz.positions.forEachIndexed { i, position ->
        OutlinedTextField(
            value = answers[i].value,
            onValueChange = { answers[i].value = it },
            label = { Text(stringResource(R.string.vault_confirm_word, position + 1)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    WideButton(stringResource(R.string.vault_create_continue), answers.all { it.value.isNotBlank() }) {
        viewModel.confirmWords(quiz, answers.map { it.value })
    }
    TextButton(onClick = viewModel::showRecoveryKeyAgain) { Text(stringResource(R.string.vault_confirm_show_again)) }
}

@Composable
private fun NewVaultDeviceUnlock(viewModel: VaultViewModel) {
    val activity = LocalContext.current.fragmentActivity()
    val promptTitle = stringResource(R.string.vault_device_enable_prompt)
    Title(stringResource(R.string.vault_device_title))
    Body(stringResource(R.string.vault_device_explanation))
    WideButton(stringResource(R.string.vault_device_enable), activity != null) {
        val cipher = viewModel.deviceUnlockEnableCipher()
        if (cipher == null || activity == null) {
            viewModel.skipDeviceUnlockForNewVault()
        } else {
            activity.authenticate(cipher, promptTitle) { done ->
                if (done != null) viewModel.completeEnableForNewVault(done) else viewModel.skipDeviceUnlockForNewVault()
            }
        }
    }
    TextButton(onClick = viewModel::skipDeviceUnlockForNewVault) { Text(stringResource(R.string.vault_device_skip)) }
}

@Composable
private fun ErrorText(error: VaultError) {
    val text = when (error) {
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
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
}
