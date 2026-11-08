package me.esdex.coffer.feature.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.vault.DeviceUnlock
import javax.crypto.Cipher

/** Settings, SPEC §9. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val integrity by viewModel.integrity.collectAsStateWithLifecycle()
    val activity = LocalContext.current.fragmentActivity()
    val resources = LocalResources.current
    val promptTitle = stringResource(R.string.settings_device_prompt)
    val snackbar = remember { SnackbarHostState() }
    var choosingAutoLock by remember { mutableStateOf(false) }
    var confirmRescan by remember { mutableStateOf(false) }
    var confirmLogOut by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }

    when (page) {
        Page.MAIN -> Unit
        Page.PASSWORD -> return ChangePasswordPage(onBack = { page = Page.MAIN }, modifier = modifier, viewModel = viewModel)
        Page.RECOVERY -> return RecoveryKeyPage(onBack = { page = Page.MAIN }, modifier = modifier, viewModel = viewModel)
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(resources.getString(it)) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.settings_section_security))
            Item(
                title = stringResource(R.string.settings_auto_lock),
                summary = autoLockLabel(state.autoLockMinutes),
                onClick = { choosingAutoLock = true },
            )
            SwitchItem(
                title = stringResource(R.string.settings_device_unlock),
                summary = stringResource(
                    if (state.deviceUnlockAvailable) R.string.settings_device_unlock_summary else R.string.settings_device_unlock_unavailable,
                ),
                checked = state.deviceUnlockOn,
                enabled = state.deviceUnlockAvailable && activity != null,
                onChange = { on ->
                    if (!on) {
                        viewModel.disableDeviceUnlock()
                    } else {
                        val cipher = viewModel.deviceUnlockCipher()
                        if (cipher != null && activity != null) {
                            activity.authenticate(cipher, promptTitle) { done -> if (done != null) viewModel.completeDeviceUnlock(done) else viewModel.disableDeviceUnlock() }
                        }
                    }
                },
            )
            SwitchItem(
                title = stringResource(R.string.settings_screenshots),
                summary = stringResource(R.string.settings_screenshots_summary),
                checked = state.blockScreenshots,
                onChange = viewModel::setBlockScreenshots,
            )

            Section(stringResource(R.string.settings_section_vault))
            Item(
                title = stringResource(R.string.settings_password),
                summary = stringResource(R.string.settings_password_summary),
                onClick = { page = Page.PASSWORD },
            )
            Item(
                title = stringResource(R.string.settings_recovery),
                summary = stringResource(R.string.settings_recovery_summary),
                onClick = { page = Page.RECOVERY },
            )
            Item(
                title = stringResource(R.string.settings_check),
                summary = stringResource(R.string.settings_check_summary),
                enabled = working == null,
                onClick = viewModel::checkIntegrity,
            )
            SwitchItem(
                title = stringResource(R.string.settings_weekly),
                summary = stringResource(R.string.settings_weekly_summary),
                checked = state.weeklyCheck,
                onChange = viewModel::setWeeklyCheck,
            )
            Item(
                title = stringResource(R.string.settings_rescan),
                summary = stringResource(R.string.settings_rescan_summary),
                enabled = working == null,
                onClick = { confirmRescan = true },
            )
            working?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text(
                    pluralStringResource(R.plurals.settings_reading, it, it),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Section(stringResource(R.string.settings_section_account))
            Item(
                title = stringResource(R.string.settings_log_out),
                summary = stringResource(R.string.settings_log_out_summary),
                onClick = { confirmLogOut = true },
            )
        }
    }

    if (choosingAutoLock) {
        AlertDialog(
            onDismissRequest = { choosingAutoLock = false },
            title = { Text(stringResource(R.string.settings_auto_lock)) },
            text = {
                Column {
                    for (choice in SettingsViewModel.AUTO_LOCK_CHOICES) {
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = choice == state.autoLockMinutes) { viewModel.setAutoLock(choice); choosingAutoLock = false }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == state.autoLockMinutes, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(autoLockLabel(choice))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingAutoLock = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    if (confirmRescan) {
        AlertDialog(
            onDismissRequest = { confirmRescan = false },
            title = { Text(stringResource(R.string.settings_rescan_title)) },
            text = { Text(stringResource(R.string.settings_rescan_text)) },
            confirmButton = { TextButton(onClick = { confirmRescan = false; viewModel.rescan() }) { Text(stringResource(R.string.settings_rescan_start)) } },
            dismissButton = { TextButton(onClick = { confirmRescan = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    if (confirmLogOut) {
        AlertDialog(
            onDismissRequest = { confirmLogOut = false },
            title = { Text(stringResource(R.string.settings_log_out_title)) },
            text = { Text(stringResource(R.string.settings_log_out_text)) },
            confirmButton = { TextButton(onClick = { confirmLogOut = false; viewModel.logOut() }) { Text(stringResource(R.string.settings_log_out)) } },
            dismissButton = { TextButton(onClick = { confirmLogOut = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
    integrity?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissIntegrity,
            title = { Text(stringResource(R.string.settings_check_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_check_result,
                        report.messagesRead,
                        report.damaged,
                        report.repaired,
                        report.garbageDeleted,
                        report.snapshotsDeleted,
                        report.operationsCompacted,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::dismissIntegrity) { Text(stringResource(R.string.settings_ok)) } },
        )
    }
}

private enum class Page { MAIN, PASSWORD, RECOVERY }

@Composable
private fun autoLockLabel(minutes: Int?): String =
    if (minutes == null) stringResource(R.string.settings_auto_lock_off) else pluralStringResource(R.plurals.settings_auto_lock_after, minutes, minutes)

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(title: String, summary: String, onClick: () -> Unit, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        Text(summary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SwitchItem(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

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
