package me.esdex.coffer.feature.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhoneAndroid
import android.text.format.Formatter
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.ui.theme.Motion
import me.esdex.coffer.feature.browser.stats.FileKind
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import javax.crypto.Cipher
import me.esdex.coffer.core.ui.components.GroupBuilder
import me.esdex.coffer.core.ui.components.GroupedRoundIcon
import me.esdex.coffer.core.ui.components.GroupedRow
import me.esdex.coffer.core.ui.components.OperationFailure
import me.esdex.coffer.core.ui.components.OperationLoading
import me.esdex.coffer.core.ui.components.OperationSuccess
import me.esdex.coffer.core.ui.components.SettingsGroup
import me.esdex.coffer.core.vault.DeviceUnlock

/** Where in Settings the user is. [depth] decides which way a change slides; [parent] is where back goes. */
private enum class Screen(val depth: Int) {
    MAIN(0),
    SECURITY(1),
    VAULT(1),
    APPEARANCE(1),
    PLAYER(1),
    TEXT(1),
    STORAGE(1),
    PHONE(1),
    MAINTENANCE(1),
    ACCOUNT(1),
    ABOUT(1),
    DEBUG(1),
    PASSWORD(2),
    RECOVERY(2),
    LICENSES(2),
    KIND(2),
    OPERATION(3),
    ;

    val parent: Screen
        get() = when (this) {
            PASSWORD, RECOVERY -> VAULT
            LICENSES -> ABOUT
            KIND -> STORAGE
            else -> MAIN
        }
}

/**
 * Settings, SPEC §9: a list of sections, each a screen of its own, as in Arcanum. [debugTools] is
 * what debug builds hand in for a Debug section; release builds pass null and have none.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onShowFolder: (Id?) -> Unit,
    modifier: Modifier = Modifier,
    debugTools: (@Composable () -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var current by rememberSaveable { mutableStateOf(Screen.MAIN) }
    // The kind whose files are listed, under Storage.
    var kind by rememberSaveable { mutableStateOf(FileKind.PICTURES) }
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    // A running or finished vault operation takes the screen until it is dismissed.
    val shown = if (operation != null) Screen.OPERATION else current
    val activity = LocalContext.current.fragmentActivity()
    val promptTitle = stringResource(R.string.settings_device_prompt)
    val promptCancel = stringResource(R.string.settings_cancel)
    val onDeviceUnlock: (Boolean) -> Unit = { on ->
        if (!on) {
            viewModel.disableDeviceUnlock()
        } else {
            val cipher = viewModel.deviceUnlockCipher()
            if (cipher != null && activity != null) {
                activity.authenticate(cipher, promptTitle, promptCancel) { done -> if (done != null) viewModel.completeDeviceUnlock(done) else viewModel.disableDeviceUnlock() }
            }
        }
    }
    val back = { current = current.parent }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val debugPromptTitle = stringResource(R.string.settings_debug_prompt)
    // Six taps on the icon in About, then the phone's own screen lock, as in Arcanum.
    val unlockDebug: ((onResult: (Boolean) -> Unit) -> Unit)? = if (debugTools == null || activity == null) {
        null
    } else {
        { onResult ->
            activity.confirmOwner(debugPromptTitle) { ok ->
                if (ok) {
                    viewModel.setDebugTools(true)
                    current = Screen.DEBUG
                }
                onResult(ok)
            }
        }
    }

    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            val spec = Motion.screen<androidx.compose.ui.unit.IntOffset>()
            if (targetState.depth > initialState.depth) {
                slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it }
            } else {
                slideInHorizontally(spec) { -it } togetherWith slideOutHorizontally(spec) { it }
            }
        },
        modifier = modifier,
        label = "settings",
    ) { screen ->
        when (screen) {
            Screen.MAIN -> MainList(onBack, viewModel, showDebug = debugTools != null && state.debugTools, onOpen = { current = it })
            Screen.SECURITY -> SecuritySection(viewModel, back, onDeviceUnlock)
            Screen.VAULT -> VaultSection(back, onPassword = { current = Screen.PASSWORD }, onRecovery = { current = Screen.RECOVERY })
            Screen.APPEARANCE -> AppearanceSection(viewModel, back)
            Screen.PLAYER -> PlayerSection(viewModel, back)
            Screen.TEXT -> TextSection(viewModel, back)
            Screen.STORAGE -> StorageSection(viewModel, back, onKind = { kind = it; current = Screen.KIND })
            Screen.KIND -> KindPage(viewModel, kind, back, onShowFolder)
            Screen.PHONE -> PhoneSection(viewModel, back)
            Screen.MAINTENANCE -> MaintenanceSection(viewModel, back)
            Screen.ACCOUNT -> AccountSection(viewModel, back)
            Screen.ABOUT -> AboutSection(back, onLicenses = { current = Screen.LICENSES }, onUnlockDebug = unlockDebug, debugUnlocked = state.debugTools)
            Screen.LICENSES -> LicensesPage(back)
            Screen.DEBUG -> DebugSection(back, onHide = { viewModel.setDebugTools(false); current = Screen.MAIN }) { debugTools?.invoke() }
            Screen.PASSWORD -> ChangePasswordPage(onBack = back, viewModel = viewModel)
            Screen.RECOVERY -> RecoveryKeyPage(onBack = back, viewModel = viewModel)
            Screen.OPERATION -> OperationPage(viewModel)
        }
    }
}

/**
 * The colours of the round icons on the main list: pale circles with a deep glyph of the same hue, as
 * Android's own Settings draws them. Outside Material You on purpose, so a colour says which section
 * it is rather than matching the wallpaper.
 */
private class Hue(val circle: Color, val glyph: Color)

private object SectionHue {
    val Security = Hue(Color(0xFF60D5F3), Color(0xFF004E5D))
    val Vault = Hue(Color(0xFFB9C4FF), Color(0xFF1B2E82))
    val Appearance = Hue(Color(0xFFFFB683), Color(0xFF753403))
    val Player = Hue(Color(0xFFFFB1C8), Color(0xFF7B1D44))
    val Text = Hue(Color(0xFFE2E58A), Color(0xFF4A4C00))
    val Storage = Hue(Color(0xFF9ECAFF), Color(0xFF003258))
    val Phone = Hue(Color(0xFFB4E1A2), Color(0xFF1F4F12))
    val Maintenance = Hue(Color(0xFF7FD8C8), Color(0xFF00504A))
    val Account = Hue(Color(0xFFD0BCFF), Color(0xFF381E72))
    val About = Hue(Color(0xFFC7C7C7), Color(0xFF474747))
    val Debug = Hue(Color(0xFF80DA88), Color(0xFF00522C))
}

@Composable
private fun MainList(onBack: () -> Unit, viewModel: SettingsViewModel, showDebug: Boolean, onOpen: (Screen) -> Unit) {
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(resources.getString(it)) }
    }
    SectionScaffold(stringResource(R.string.settings_title), onBack, snackbar) {
        // Grouped as Android's own Settings groups its sections: no headings, a round icon of its own
        // colour on each row, and the gap between groups doing the sorting.
        Spacer(Modifier.height(8.dp))
        SettingsGroup(title = null) {
            section(Screen.SECURITY, Icons.Outlined.Security, SectionHue.Security, R.string.settings_card_security, R.string.settings_card_security_desc, onOpen)
            section(Screen.VAULT, Icons.Outlined.Key, SectionHue.Vault, R.string.settings_card_vault, R.string.settings_card_vault_desc, onOpen)
        }
        Spacer(Modifier.height(16.dp))
        SettingsGroup(title = null) {
            section(Screen.APPEARANCE, Icons.Outlined.Palette, SectionHue.Appearance, R.string.settings_card_appearance, R.string.settings_card_appearance_desc, onOpen)
            section(Screen.PLAYER, Icons.Outlined.PlayCircle, SectionHue.Player, R.string.settings_card_player, R.string.settings_card_player_desc, onOpen)
            section(Screen.TEXT, Icons.Outlined.Description, SectionHue.Text, R.string.settings_card_text, R.string.settings_card_text_desc, onOpen)
        }
        Spacer(Modifier.height(16.dp))
        // Where things are: the vault in Saved Messages, what is on the phone, and keeping the two in step.
        val breakdown by viewModel.breakdown.collectAsStateWithLifecycle()
        val phone by viewModel.phone.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { viewModel.measurePhone() }
        val context = LocalContext.current
        val storageNow = breakdown?.let {
            pluralStringResource(R.plurals.settings_storage_files, it.files, it.files) + " · " + Formatter.formatShortFileSize(context, it.bytes)
        } ?: stringResource(R.string.settings_card_storage_desc)
        val phoneNow = phone?.let { stringResource(R.string.settings_card_phone_now, Formatter.formatShortFileSize(context, it.total)) }
            ?: stringResource(R.string.settings_card_phone_desc)
        SettingsGroup(title = null) {
            section(Screen.STORAGE, Icons.Outlined.Cloud, SectionHue.Storage, R.string.settings_card_storage, storageNow, onOpen)
            section(Screen.PHONE, Icons.Outlined.PhoneAndroid, SectionHue.Phone, R.string.settings_card_phone, phoneNow, onOpen)
            section(Screen.MAINTENANCE, Icons.Outlined.Sync, SectionHue.Maintenance, R.string.settings_card_maintenance, R.string.settings_card_maintenance_desc, onOpen)
        }
        Spacer(Modifier.height(16.dp))
        SettingsGroup(title = null) {
            section(Screen.ACCOUNT, Icons.Outlined.AccountCircle, SectionHue.Account, R.string.settings_card_account, R.string.settings_card_account_desc, onOpen)
            section(Screen.ABOUT, Icons.Outlined.Info, SectionHue.About, R.string.settings_card_about, R.string.settings_card_about_desc, onOpen)
            if (showDebug) {
                section(Screen.DEBUG, Icons.Outlined.BugReport, SectionHue.Debug, R.string.settings_card_debug, R.string.settings_card_debug_desc, onOpen)
            }
        }
    }
}

private fun GroupBuilder.section(screen: Screen, icon: ImageVector, hue: Hue, title: Int, subtitle: Int, onOpen: (Screen) -> Unit) = row { shape ->
    GroupedRow(
        shape,
        title = stringResource(title),
        subtitle = stringResource(subtitle),
        leading = { GroupedRoundIcon(icon, hue.circle, hue.glyph) },
        onClick = { onOpen(screen) },
    )
}

/** A section whose line under the title is a figure worked out on the spot. */
private fun GroupBuilder.section(screen: Screen, icon: ImageVector, hue: Hue, title: Int, subtitle: String, onOpen: (Screen) -> Unit) = row { shape ->
    GroupedRow(
        shape,
        title = stringResource(title),
        subtitle = subtitle,
        leading = { GroupedRoundIcon(icon, hue.circle, hue.glyph) },
        onClick = { onOpen(screen) },
    )
}

/** The integrity check or the rescan, running and then finished, as a page of its own. */
@Composable
private fun OperationPage(viewModel: SettingsViewModel) {
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    BackHandler(onBack = viewModel::dismissOperation)
    val modifier = Modifier.safeDrawingPadding()
    when (val op = operation) {
        null -> Unit
        is SettingsViewModel.Operation.Running -> OperationLoading(
            title = stringResource(if (op.kind == SettingsViewModel.Kind.CHECK) R.string.settings_check_running else R.string.settings_rescan_running),
            subtitle = working?.let { pluralStringResource(R.plurals.settings_reading, it, it) },
            modifier = modifier,
        )
        is SettingsViewModel.Operation.Done -> OperationSuccess(
            title = stringResource(if (op.kind == SettingsViewModel.Kind.CHECK) R.string.settings_check_title else R.string.settings_rescan_done),
            body = op.report?.let { report ->
                stringResource(
                    R.string.settings_check_result,
                    report.messagesRead,
                    report.damaged,
                    report.repaired,
                    report.garbageDeleted,
                    report.snapshotsDeleted,
                    report.operationsCompacted,
                )
            },
            onDone = viewModel::dismissOperation,
            modifier = modifier,
        )
        is SettingsViewModel.Operation.Failed -> OperationFailure(
            title = stringResource(R.string.settings_operation_failed),
            body = stringResource(op.message),
            onDone = viewModel::dismissOperation,
            modifier = modifier,
        )
    }
}

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

/** The phone's own screen lock, with nothing to unlock behind it: proof that the owner is holding the phone. */
private fun FragmentActivity.confirmOwner(title: String, onDone: (Boolean) -> Unit) {
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onDone(true)

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone(false)
        },
    )
    // Throws where no screen lock is set; that is a refusal too.
    runCatching { prompt.authenticate(DeviceUnlock.ownerPromptInfo(title)) }.onFailure { onDone(false) }
}

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}

