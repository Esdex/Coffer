package me.esdex.coffer.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.vault.PasswordPolicy

/** A settings page that asks for the current password first (SPEC §9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecretPage(title: String, onBack: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit, isError: Boolean = false) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    singleLine = true,
    isError = isError,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
fun ChangePasswordPage(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val wrong by viewModel.wrongPassword.collectAsStateWithLifecycle()
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val check = remember(password) { PasswordPolicy.check(password) }
    val leave = rememberUpdatedState { viewModel.leaveSecrets(); onBack() }
    LaunchedEffect(Unit) { viewModel.passwordChanged.collect { leave.value() } }

    SecretPage(stringResource(R.string.settings_password), onBack = leave.value, modifier = modifier) {
        Text(stringResource(R.string.settings_password_explanation), style = MaterialTheme.typography.bodyMedium)
        PasswordField(current, stringResource(R.string.settings_password_current), { current = it }, isError = wrong)
        if (wrong) Text(stringResource(R.string.settings_password_wrong), color = MaterialTheme.colorScheme.error)
        PasswordField(password, stringResource(R.string.settings_password_new), { password = it })
        if (password.isNotEmpty()) StrengthMeter(check)
        PasswordField(repeated, stringResource(R.string.settings_password_repeat), { repeated = it }, isError = repeated.isNotEmpty() && repeated != password)
        if (repeated.isNotEmpty() && repeated != password) Text(stringResource(R.string.settings_password_mismatch), color = MaterialTheme.colorScheme.error)
        Button(
            onClick = { viewModel.changePassword(current, password) },
            enabled = !busy && current.isNotEmpty() && check.acceptable && repeated == password,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_password_change)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun StrengthMeter(check: PasswordPolicy.Check) {
    val labels = listOf(
        R.string.settings_strength_0, R.string.settings_strength_1, R.string.settings_strength_2,
        R.string.settings_strength_3, R.string.settings_strength_4,
    )
    val color = if (check.acceptable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    LinearProgressIndicator(progress = { (check.score + 1) / 5f }, color = color, modifier = Modifier.fillMaxWidth())
    Text(stringResource(labels[check.score]), color = color, style = MaterialTheme.typography.labelLarge)
    if (!check.longEnough) {
        Text(pluralStringResource(R.plurals.settings_strength_too_short, PasswordPolicy.MIN_LENGTH, PasswordPolicy.MIN_LENGTH), style = MaterialTheme.typography.bodyMedium)
    }
    check.warning?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    check.suggestions.forEach { Text("- $it", style = MaterialTheme.typography.bodyMedium) }
}

@Composable
fun RecoveryKeyPage(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val wrong by viewModel.wrongPassword.collectAsStateWithLifecycle()
    val words by viewModel.recoveryWords.collectAsStateWithLifecycle()
    val settings by viewModel.state.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    val leave = { viewModel.leaveSecrets(); onBack() }

    SecretPage(stringResource(R.string.settings_recovery), onBack = leave, modifier = modifier) {
        val shown = words
        if (shown == null) {
            Text(stringResource(R.string.settings_recovery_explanation), style = MaterialTheme.typography.bodyMedium)
            PasswordField(password, stringResource(R.string.settings_password_current), { password = it }, isError = wrong)
            if (wrong) Text(stringResource(R.string.settings_password_wrong), color = MaterialTheme.colorScheme.error)
            Button(
                onClick = { viewModel.showRecoveryKey(password) },
                enabled = !busy && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_recovery_show)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            NoScreenshots(keepAfter = settings.blockScreenshots)
            Text(stringResource(R.string.settings_recovery_shown), style = MaterialTheme.typography.bodyMedium)
            // Three columns, numbered top to bottom like the paper backup made at creation.
            val rows = shown.size / 3
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (column in 0 until 3) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (row in 0 until rows) {
                            val index = column * rows + row
                            Text("%2d. %s".format(index + 1, shown[index]), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Button(onClick = leave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_recovery_done)) }
        }
    }
}

/** FLAG_SECURE while on screen, whatever the setting; afterwards back to what the setting says. */
@Composable
private fun NoScreenshots(keepAfter: Boolean) {
    val activity = LocalContext.current.activity() ?: return
    val keep = rememberUpdatedState(keepAfter)
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!keep.value) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
