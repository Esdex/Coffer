package me.esdex.coffer.feature.settings

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.esdex.coffer.core.tdlib.TelegramAccount
import me.esdex.coffer.core.ui.components.ActionCapsule
import me.esdex.coffer.core.ui.components.AppDialog
import me.esdex.coffer.core.ui.components.CofferScaffold
import me.esdex.coffer.core.ui.components.GroupedBox
import me.esdex.coffer.core.ui.components.GroupedRow
import me.esdex.coffer.core.ui.components.GroupedSwitch
import me.esdex.coffer.core.ui.components.SettingsGroup
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion
import me.esdex.coffer.core.ui.theme.ThemeMode
import me.esdex.coffer.core.ui.theme.tabular

/**
 * The frame of every settings section: its own large title and back button, and a column of groups
 * with the side margins the whole of Settings uses.
 */
@Composable
internal fun SectionScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    CofferScaffold(title = title, onBack = onBack, snackbar = snackbar) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = padding.calculateBottomPadding() + 24.dp),
            content = content,
        )
    }
}

/** A row that opens a page of its own: an arrow on the right says so. */
@Composable
internal fun LinkRow(shape: Shape, title: String, subtitle: String? = null, onClick: () -> Unit) {
    GroupedRow(
        shape,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

// ── Security ─────────────────────────────────────────────────────────────────

@Composable
internal fun SecuritySection(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onDeviceUnlock: (Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SectionScaffold(stringResource(R.string.settings_card_security), onBack) {
        SettingsGroup(stringResource(R.string.settings_group_locking)) {
            row { shape ->
                GroupedSwitch(
                    shape,
                    title = stringResource(R.string.settings_auto_lock),
                    subtitle = stringResource(R.string.settings_auto_lock_desc),
                    info = stringResource(R.string.settings_auto_lock_info),
                    checked = state.autoLockMinutes != null,
                    onCheckedChange = { on -> viewModel.setAutoLock(if (on) SettingsViewModel.DEFAULT_AUTO_LOCK else null) },
                )
            }
            // The delay only exists while auto-lock is on, so it is absent rather than greyed.
            val minutes = state.autoLockMinutes
            if (minutes != null) {
                row { shape -> AutoLockDelay(shape, minutes, viewModel::setAutoLock) }
            }
            row { shape ->
                GroupedSwitch(
                    shape,
                    title = stringResource(R.string.settings_device_unlock),
                    checked = state.deviceUnlockOn,
                    subtitle = if (state.deviceUnlockAvailable) null else stringResource(R.string.settings_device_unlock_unavailable),
                    info = stringResource(R.string.settings_device_unlock_summary),
                    enabled = state.deviceUnlockAvailable,
                    onCheckedChange = onDeviceUnlock,
                )
            }
        }
        SettingsGroup(stringResource(R.string.settings_group_privacy)) {
            row { shape ->
                GroupedSwitch(
                    shape,
                    title = stringResource(R.string.settings_screenshots),
                    checked = state.blockScreenshots,
                    info = stringResource(R.string.settings_screenshots_summary),
                    onCheckedChange = viewModel::setBlockScreenshots,
                )
            }
            row { shape ->
                GroupedSwitch(
                    shape,
                    title = stringResource(R.string.settings_hide_sizes),
                    checked = state.hideSizes,
                    info = stringResource(R.string.settings_hide_sizes_summary),
                    onCheckedChange = viewModel::setHideSizes,
                )
            }
            row { shape ->
                GroupedSwitch(
                    shape,
                    title = stringResource(R.string.settings_edit_elsewhere),
                    checked = state.editInOtherApps,
                    info = stringResource(R.string.settings_edit_elsewhere_summary),
                    onCheckedChange = viewModel::setEditInOtherApps,
                )
            }
            row { shape ->
                GroupedSwitch(
                    shape,
                    title = stringResource(R.string.settings_file_picker),
                    checked = state.showInFilePicker,
                    info = stringResource(R.string.settings_file_picker_summary),
                    onCheckedChange = viewModel::setShowInFilePicker,
                )
            }
        }
    }
}

/** How long before auto-lock, on a slider over the few choices there are. */
@Composable
private fun AutoLockDelay(shape: Shape, minutes: Int, onChange: (Int) -> Unit) {
    val choices = SettingsViewModel.AUTO_LOCK_DELAYS
    val index = choices.indexOf(minutes).takeIf { it >= 0 } ?: choices.indexOf(SettingsViewModel.DEFAULT_AUTO_LOCK)
    GroupedBox(shape) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_auto_lock_delay), style = MaterialTheme.typography.bodyLarge)
            Text(
                pluralStringResource(R.plurals.settings_minutes, choices[index], choices[index]),
                style = MaterialTheme.typography.bodyLarge.tabular(),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { onChange(choices[it.toInt().coerceIn(choices.indices)]) },
            valueRange = 0f..(choices.size - 1).toFloat(),
            steps = choices.size - 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Password and recovery key ───────────────────────────────────────────────

@Composable
internal fun VaultSection(onBack: () -> Unit, onPassword: () -> Unit, onRecovery: () -> Unit, onDelete: () -> Unit) {
    SectionScaffold(stringResource(R.string.settings_card_vault), onBack) {
        SettingsGroup(stringResource(R.string.settings_group_password)) {
            row { shape -> LinkRow(shape, stringResource(R.string.settings_password), stringResource(R.string.settings_password_summary), onPassword) }
        }
        SettingsGroup(stringResource(R.string.settings_group_recovery)) {
            row { shape -> LinkRow(shape, stringResource(R.string.settings_recovery), stringResource(R.string.settings_recovery_summary), onRecovery) }
        }
        // Last and red, apart from the rest: the one thing here that cannot be taken back (SPEC §5.9).
        SettingsGroup(stringResource(R.string.settings_group_danger)) {
            row { shape ->
                GroupedRow(
                    shape,
                    title = stringResource(R.string.settings_delete_vault),
                    subtitle = stringResource(R.string.settings_delete_vault_summary),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

// ── Player ───────────────────────────────────────────────────────────────────

/** The players' extras, one switch each, as in Arcanum, and how music plays outside Coffer (pre-release item 5). */
@Composable
internal fun PlayerSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val prefs by viewModel.player.collectAsStateWithLifecycle()
    SectionScaffold(stringResource(R.string.settings_card_player), onBack) {
        SettingsGroup(stringResource(R.string.settings_player_gestures)) {
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_player_swipe_volume), checked = prefs.swipeVolume,
                    onCheckedChange = { on -> viewModel.setPlayer { it.copy(swipeVolume = on) } })
            }
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_player_swipe_brightness), checked = prefs.swipeBrightness,
                    info = stringResource(R.string.settings_player_swipe_brightness_info),
                    onCheckedChange = { on -> viewModel.setPlayer { it.copy(swipeBrightness = on) } })
            }
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_player_hold_speed), checked = prefs.holdForSpeed,
                    onCheckedChange = { on -> viewModel.setPlayer { it.copy(holdForSpeed = on) } })
            }
        }
        SettingsGroup(stringResource(R.string.settings_player_controls)) {
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_player_lock), checked = prefs.lockButton,
                    info = stringResource(R.string.settings_player_lock_info),
                    onCheckedChange = { on -> viewModel.setPlayer { it.copy(lockButton = on) } })
            }
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_player_speed), checked = prefs.speedMenu,
                    onCheckedChange = { on -> viewModel.setPlayer { it.copy(speedMenu = on) } })
            }
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_player_loop), checked = prefs.loop,
                    onCheckedChange = { on -> viewModel.setPlayer { it.copy(loop = on) } })
            }
        }
        SettingsGroup(stringResource(R.string.settings_player_music)) {
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_player_background), checked = prefs.backgroundAudio,
                    info = stringResource(R.string.settings_player_background_info),
                    onCheckedChange = { on -> viewModel.setPlayer { it.copy(backgroundAudio = on) } })
            }
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_player_show_playing), checked = prefs.showPlaying,
                    info = stringResource(R.string.settings_player_show_playing_info),
                    onCheckedChange = { on -> viewModel.setPlayer { it.copy(showPlaying = on) } })
            }
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_player_waveform), checked = prefs.waveform,
                    info = stringResource(R.string.settings_player_waveform_info),
                    onCheckedChange = { on -> viewModel.setPlayer { it.copy(waveform = on) } })
            }
        }
    }
}

// ── Appearance ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val dark = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val modes = ThemeMode.entries
    SectionScaffold(stringResource(R.string.settings_card_appearance), onBack) {
        SettingsGroup(stringResource(R.string.settings_theme)) {
            row { shape ->
                GroupedBox(shape) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = appearance.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                // No tick, as on the cache limit: the fill says which one is chosen.
                                icon = {},
                                label = { Text(themeLabel(mode), style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }
            }
        }
        SettingsGroup(stringResource(R.string.settings_group_display)) {
            // Pure black is a dark-theme setting and simply is not there in a light one.
            if (dark) {
                row { shape ->
                    GroupedSwitch(
                        shape,
                        title = stringResource(R.string.settings_amoled),
                        checked = appearance.amoled,
                        info = stringResource(R.string.settings_amoled_info),
                        onCheckedChange = viewModel::setAmoled,
                    )
                }
            }
            row { shape ->
                val available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                GroupedSwitch(
                    shape,
                    title = stringResource(R.string.settings_dynamic),
                    checked = appearance.dynamicColor && available,
                    info = stringResource(R.string.settings_dynamic_info),
                    enabled = available,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    },
)

// ── Saved Messages ───────────────────────────────────────────────────────────

@Composable
internal fun MaintenanceSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmRescan by remember { mutableStateOf(false) }
    SectionScaffold(stringResource(R.string.settings_card_maintenance), onBack) {
        SettingsGroup(stringResource(R.string.settings_group_integrity)) {
            row { shape ->
                GroupedRow(
                    shape,
                    title = stringResource(R.string.settings_check),
                    subtitle = stringResource(R.string.settings_check_short),
                    info = stringResource(R.string.settings_check_summary),
                    onClick = viewModel::checkIntegrity,
                )
            }
            row { shape ->
                GroupedSwitch(
                    shape,
                    title = stringResource(R.string.settings_weekly),
                    checked = state.weeklyCheck,
                    info = stringResource(R.string.settings_weekly_summary),
                    onCheckedChange = viewModel::setWeeklyCheck,
                )
            }
        }
        SettingsGroup(stringResource(R.string.settings_group_rebuild)) {
            row { shape ->
                GroupedRow(
                    shape,
                    title = stringResource(R.string.settings_rescan),
                    subtitle = stringResource(R.string.settings_rescan_summary),
                    onClick = { confirmRescan = true },
                )
            }
        }
    }
    if (confirmRescan) {
        AppDialog(
            onDismissRequest = { confirmRescan = false },
            title = { Text(stringResource(R.string.settings_rescan_title)) },
            text = { Text(stringResource(R.string.settings_rescan_text)) },
            confirmButton = { TextButton(onClick = { confirmRescan = false; viewModel.rescan() }) { Text(stringResource(R.string.settings_rescan_start)) } },
            dismissButton = { TextButton(onClick = { confirmRescan = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

// ── Account ──────────────────────────────────────────────────────────────────

@Composable
internal fun AccountSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    var confirmLogOut by remember { mutableStateOf(false) }
    var premiumInfo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.loadAccount() }
    SectionScaffold(stringResource(R.string.settings_card_account), onBack) {
        val me = account
        // Who this is, the way Telegram's own profile opens: the picture, the name, and a star for Premium.
        // Faded in once Telegram has answered, rather than the name and picture popping into place.
        AnimatedContent(
            me,
            contentKey = { it != null },
            transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.quick()) },
            label = "account",
        ) { me ->
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(me)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    me?.displayName ?: stringResource(R.string.settings_account_loading),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (me?.isPremium == true) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.settings_premium),
                        tint = PremiumStar,
                        modifier = Modifier.size(24.dp).clip(CircleShape).clickable { premiumInfo = true },
                    )
                }
            }
            me?.username?.let { Text("@$it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        }
        if (me != null) {
            SettingsGroup(stringResource(R.string.settings_group_telegram)) {
                row { shape -> GroupedRow(shape, title = stringResource(R.string.settings_account_phone), subtitle = "+" + me.phoneNumber) }
                me.username?.let { username ->
                    row { shape -> GroupedRow(shape, title = stringResource(R.string.settings_account_username), subtitle = "@$username") }
                }
                row { shape ->
                    GroupedRow(
                        shape,
                        title = stringResource(R.string.settings_premium),
                        subtitle = stringResource(if (me.isPremium) R.string.settings_premium_on else R.string.settings_premium_off),
                        onClick = { premiumInfo = true },
                        leading = { Icon(Icons.Filled.Star, null, tint = if (me.isPremium) PremiumStar else MaterialTheme.colorScheme.outline) },
                    )
                }
            }
        }
        SettingsGroup(stringResource(R.string.settings_group_session)) {
            row { shape ->
                GroupedRow(
                    shape,
                    title = stringResource(R.string.settings_log_out),
                    subtitle = stringResource(R.string.settings_log_out_summary),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmLogOut = true },
                )
            }
        }
    }
    if (premiumInfo) {
        val premium = account?.isPremium == true
        AppDialog(
            onDismissRequest = { premiumInfo = false },
            title = { Text(stringResource(if (premium) R.string.settings_premium_on_title else R.string.settings_premium_off_title)) },
            text = { Text(stringResource(if (premium) R.string.settings_premium_on_text else R.string.settings_premium_off_text)) },
            confirmButton = { TextButton(onClick = { premiumInfo = false }) { Text(stringResource(R.string.settings_ok)) } },
        )
    }
    if (confirmLogOut) {
        AppDialog(
            onDismissRequest = { confirmLogOut = false },
            title = { Text(stringResource(R.string.settings_log_out_title)) },
            text = { Text(stringResource(R.string.settings_log_out_text)) },
            confirmButton = {
                TextButton(onClick = { confirmLogOut = false; viewModel.logOut() }) {
                    Text(stringResource(R.string.settings_log_out), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLogOut = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

/** Telegram's own gold for the Premium star. */
private val PremiumStar = Color(0xFFFFB300)

/** The profile picture, or the initials on a circle while there is none. */
@Composable
private fun Avatar(account: TelegramAccount?) {
    val photo = remember(account?.photoPath) {
        account?.photoPath?.let { path -> runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    }
    val modifier = Modifier.size(88.dp).clip(CircleShape)
    if (photo != null) {
        Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(
                account?.initials.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

// ── About ────────────────────────────────────────────────────────────────────

/**
 * Where About's links go. Null for the ones Coffer does not have yet: the row is there and says so,
 * and filling in the address is all it takes later.
 */
private object AboutLinks {
    val sourceCode: String? = "https://github.com/Esdex/Coffer"
    // The documentation, on GitHub Pages from docs/ in the repository.
    val website: String? = "https://esdex.github.io/Coffer/"
    val privacyPolicy: String? = "https://esdex.github.io/Coffer/privacy.html"
}

/**
 * A new issue on GitHub, opened on the bug report form with what the app knows filled in: its version,
 * and Android's with the phone's model. Nothing else, and all of it on screen to change before sending.
 */
internal fun bugReportUrl(version: String, android: String, manufacturer: String, model: String): String {
    // "Google Pixel 8", not "Google Google Pixel 8": some models carry their maker's name already.
    val device = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    return "https://github.com/Esdex/Coffer/issues/new?template=bug_report.yml" +
        "&version=" + encode(version) + "&android=" + encode("Android $android, $device")
}

@Composable
internal fun AboutSection(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onWhatsNew: () -> Unit,
    /** Null where there are no debug tools to unlock (release builds). Asks for the screen lock first. */
    onUnlockDebug: ((onResult: (Boolean) -> Unit) -> Unit)?,
    debugUnlocked: Boolean,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var taps by remember { mutableIntStateOf(0) }
    var tapHint by remember { mutableStateOf<String?>(null) }
    // Each tap shows how many are left for a moment, then the hint fades.
    LaunchedEffect(tapHint) {
        if (tapHint != null) {
            delay(TAP_HINT_MILLIS)
            tapHint = null
        }
    }
    val onIconTap: (() -> Unit)? = onUnlockDebug?.let { unlock ->
        {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (debugUnlocked) {
                tapHint = resources.getString(R.string.settings_debug_already)
            } else if (++taps >= DEBUG_TAPS) {
                taps = 0
                tapHint = null
                unlock { ok -> if (!ok) scope.launch { snackbar.showSnackbar(resources.getString(R.string.settings_debug_failed)) } }
            } else {
                val left = DEBUG_TAPS - taps
                tapHint = resources.getQuantityString(R.plurals.settings_debug_taps, left, left)
            }
        }
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val icon = remember {
        runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap().asImageBitmap() }.getOrNull()
    }
    fun open(link: String?) {
        if (link == null) {
            scope.launch { snackbar.showSnackbar(resources.getString(R.string.settings_about_soon)) }
        } else {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) }
        }
    }
    SectionScaffold(stringResource(R.string.settings_card_about), onBack, snackbar) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Image(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp).clip(RoundedCornerShape(Corners.Large))
                        .then(if (onIconTap != null) Modifier.clickable(onClick = onIconTap) else Modifier),
                )
            }
            Text(stringResource(R.string.settings_about_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedVisibility(visible = tapHint != null, enter = fadeIn(Motion.quick()), exit = fadeOut(Motion.standard())) {
                Text(tapHint.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
            ActionCapsule(Icons.Outlined.Code, stringResource(R.string.settings_about_source), onClick = { open(AboutLinks.sourceCode) })
            ActionCapsule(Icons.Outlined.Language, stringResource(R.string.settings_about_website), onClick = { open(AboutLinks.website) })
        }
        SettingsGroup(stringResource(R.string.settings_card_about)) {
            row { shape ->
                GroupedBox(shape) {
                    Text(stringResource(R.string.settings_about_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            row { shape -> LinkRow(shape, stringResource(R.string.settings_about_licenses), onClick = onLicenses) }
            row { shape ->
                GroupedRow(
                    shape,
                    title = stringResource(R.string.settings_about_whats_new),
                    subtitle = stringResource(R.string.settings_about_whats_new_desc),
                    onClick = onWhatsNew,
                )
            }
        }
        SettingsGroup(stringResource(R.string.settings_about_connect)) {
            row { shape -> ExternalRow(shape, stringResource(R.string.settings_about_privacy), null) { open(AboutLinks.privacyPolicy) } }
            row { shape ->
                ExternalRow(shape, stringResource(R.string.settings_about_report), stringResource(R.string.settings_about_report_desc)) {
                    open(bugReportUrl(version, Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL))
                }
            }
        }
    }
}

/** A row that leaves the app for a browser: the arrow out of a box on the right says so. */
@Composable
private fun ExternalRow(shape: Shape, title: String, subtitle: String?, onClick: () -> Unit) {
    GroupedRow(
        shape,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

/** The open-source parts Coffer is built from, each with its licence. */
private val LICENSES = listOf(
    "TDLib" to "Boost Software License 1.0",
    "OpenSSL" to "Apache License 2.0",
    "libsodium" to "ISC License",
    "Lazysodium" to "Mozilla Public License 2.0",
    "JNA" to "Apache License 2.0 / LGPL 2.1",
    "zxcvbn4j" to "MIT License",
    "ZXing" to "Apache License 2.0",
    "Kotlin, kotlinx" to "Apache License 2.0",
    "AndroidX, Jetpack Compose, Room, WorkManager, Hilt" to "Apache License 2.0",
    "Material icons" to "Apache License 2.0",
    "Haze" to "Apache License 2.0",
    "Lottie" to "Apache License 2.0",
    "Animations from LottieFiles" to "Lottie Simple License",
)

@Composable
internal fun LicensesPage(onBack: () -> Unit) {
    SectionScaffold(stringResource(R.string.settings_about_licenses), onBack) {
        Spacer(Modifier.height(8.dp))
        SettingsGroup(title = null) {
            for ((name, licence) in LICENSES) {
                row { shape -> GroupedRow(shape, title = name, subtitle = licence) }
            }
        }
    }
}

/** What changed in this version, as Arcanum shows it; the versions before it behind a button. */
@Composable
internal fun WhatsNewPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val whatsNew = remember { WhatsNew.load(context) }
    val info = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() }
    val versionCode = info?.longVersionCode ?: 0L
    var showOlder by rememberSaveable { mutableStateOf(false) }
    SectionScaffold(stringResource(R.string.settings_about_whats_new), onBack) {
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.settings_whats_new_version, info?.versionName.orEmpty()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
                Text(
                    stringResource(R.string.settings_whats_new_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        WhatsNewEntries(whatsNew.current(versionCode)?.entries.orEmpty())
        val older = whatsNew.older(versionCode)
        if (older.isNotEmpty()) {
            if (!showOlder) {
                TextButton(onClick = { showOlder = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_whats_new_show_older))
                }
            } else {
                for (version in older) {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalDivider(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.settings_whats_new_version, version.version),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.weight(1f))
                    }
                    WhatsNewEntries(version.entries)
                }
            }
        }
    }
}

@Composable
private fun WhatsNewEntries(entries: List<WhatsNewEntry>) {
    if (entries.isEmpty()) return
    SettingsGroup(title = null) {
        for (entry in entries.inDisplayOrder()) {
            row { shape ->
                GroupedBox(shape) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val (icon, color) = entry.visuals()
                        Box(Modifier.size(32.dp).background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            Icon(icon, null, modifier = Modifier.size(18.dp), tint = color)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            entry.description?.let {
                                Text(linkified(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Debug ────────────────────────────────────────────────────────────────────

/** Debug builds only: the tools the app hands in, in a section of their own, until hidden again. */
@Composable
internal fun DebugSection(onBack: () -> Unit, onHide: () -> Unit, tools: @Composable () -> Unit) {
    SectionScaffold(stringResource(R.string.settings_card_debug), onBack) {
        SettingsGroup(title = null) {
            row { shape -> GroupedBox(shape) { tools() } }
        }
        SettingsGroup(title = null) {
            row { shape ->
                GroupedRow(
                    shape,
                    title = stringResource(R.string.settings_debug_hide),
                    subtitle = stringResource(R.string.settings_debug_hide_desc),
                    onClick = onHide,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Taps on the icon in About that bring up the screen lock for the debug tools, as in Arcanum. */
private const val DEBUG_TAPS = 6
// Long enough to read "3 more taps" between taps; it was 550 ms and blinked.
private const val TAP_HINT_MILLIS = Motion.REVEAL.toLong()
