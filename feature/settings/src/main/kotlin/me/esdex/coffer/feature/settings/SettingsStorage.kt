package me.esdex.coffer.feature.settings

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import me.esdex.coffer.core.ui.components.AppDialog
import me.esdex.coffer.core.ui.components.GroupedSwitch
import me.esdex.coffer.core.ui.theme.CachePrefs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.sync.Census
import me.esdex.coffer.core.ui.components.CofferScaffold
import me.esdex.coffer.core.ui.components.GroupBuilder
import me.esdex.coffer.core.ui.components.GroupedBox
import me.esdex.coffer.core.ui.components.GroupedRow
import me.esdex.coffer.core.ui.components.SettingsGroup
import me.esdex.coffer.core.ui.components.TypeTile
import me.esdex.coffer.core.ui.components.fileTypeIcon
import me.esdex.coffer.feature.browser.rowKey
import me.esdex.coffer.feature.browser.stats.FileKind

// ── Storage: the vault, in Saved Messages ───────────────────────────────────

/** The colour and icon of a kind: the same on the ring, in the legend and on the file list. */
internal fun FileKind.look(): Pair<ImageVector, Color> = when (this) {
    FileKind.PICTURES -> Icons.Outlined.Image to Color(0xFF3B82F6)
    FileKind.VIDEOS -> Icons.Outlined.Videocam to Color(0xFFEF4444)
    FileKind.MUSIC -> Icons.Outlined.AudioFile to Color(0xFF8B5CF6)
    FileKind.DOCUMENTS -> Icons.Outlined.Description to Color(0xFF16A34A)
    FileKind.OTHER -> Icons.Outlined.InsertDriveFile to Color(0xFF9CA3AF)
}

internal fun FileKind.label(): Int = when (this) {
    FileKind.PICTURES -> R.string.settings_storage_pictures
    FileKind.VIDEOS -> R.string.settings_storage_videos
    FileKind.MUSIC -> R.string.settings_storage_music
    FileKind.DOCUMENTS -> R.string.settings_storage_documents
    FileKind.OTHER -> R.string.settings_storage_other
}

/**
 * What the vault holds, by kind, and what Coffer keeps in Saved Messages for it (pre-release item 6).
 * A kind opens its files, largest first. Saved Messages is counted when the screen first opens, as that
 * means reading its whole history.
 */
@Composable
internal fun StorageSection(viewModel: SettingsViewModel, onBack: () -> Unit, onKind: (FileKind) -> Unit) {
    val breakdown by viewModel.breakdown.collectAsStateWithLifecycle()
    val counting by viewModel.counting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    LaunchedEffect(Unit) { if (viewModel.counting.value == null) viewModel.count() }

    SectionScaffold(stringResource(R.string.settings_card_storage), onBack) {
        val current = breakdown
        if (current == null) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@SectionScaffold
        }
        Ring(
            segments = current.kinds.map { it.bytes to it.kind.look().second },
            center = size(current.bytes),
            under = pluralStringResource(R.plurals.settings_storage_files, current.files, current.files) + " · " +
                pluralStringResource(R.plurals.settings_storage_folders, current.folders, current.folders),
        )
        SettingsGroup(stringResource(R.string.settings_storage_by_kind)) {
            for (total in current.kinds) row { shape ->
                GroupedRow(
                    shape,
                    title = stringResource(total.kind.label()),
                    subtitle = pluralStringResource(R.plurals.settings_storage_files, total.count, total.count),
                    leading = { TypeTile(total.kind.look()) },
                    trailing = { Text(size(total.bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = if (total.count > 0) ({ onKind(total.kind) }) else null,
                )
            }
        }
        SettingsGroup(stringResource(R.string.settings_storage_saved_messages)) {
            when (val c = counting) {
                null, is SettingsViewModel.Counting.Running -> row { shape ->
                    GroupedBox(shape) {
                        val read = (c as? SettingsViewModel.Counting.Running)?.read ?: 0
                        Text(pluralStringResource(R.plurals.settings_storage_counting, read, read), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                SettingsViewModel.Counting.Failed -> row { shape ->
                    GroupedRow(shape, title = stringResource(R.string.settings_storage_count_failed), trailing = {
                        TextButton(onClick = viewModel::count) { Text(stringResource(R.string.settings_storage_recount)) }
                    })
                }
                is SettingsViewModel.Counting.Done -> censusRows(c.census, ::size, onRecount = viewModel::count)
            }
        }
    }
}

private val PARTS = Color(0xFF3B82F6)
private val OPERATIONS = Color(0xFFF59E0B)
private val SNAPSHOTS = Color(0xFF8B5CF6)
private val HEADER = Color(0xFF16A34A)

private fun GroupBuilder.censusRows(census: Census, size: (Long) -> String, onRecount: () -> Unit) {
    row { shape ->
        GroupedBox(shape) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pluralStringResource(R.plurals.settings_storage_messages, census.coffer.count, census.coffer.count), style = MaterialTheme.typography.bodyLarge)
                    Text(size(census.coffer.bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRecount) { Text(stringResource(R.string.settings_storage_recount)) }
            }
            Spacer(Modifier.height(12.dp))
            // By count rather than by size: by size the parts are all there is, and the rest is what needs explaining.
            Bar(listOf(census.parts.count.toLong() to PARTS, census.operations.count.toLong() to OPERATIONS, census.snapshots.count.toLong() to SNAPSHOTS, census.headers.count.toLong() to HEADER))
        }
    }
    fun line(color: Color, title: Int, info: Int, tally: Census.Tally) = row { shape ->
        GroupedRow(
            shape,
            title = stringResource(title),
            subtitle = pluralStringResource(R.plurals.settings_storage_messages, tally.count, tally.count),
            info = stringResource(info),
            leading = { Dot(color) },
            trailing = { Text(size(tally.bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
    line(PARTS, R.string.settings_storage_parts, R.string.settings_storage_parts_info, census.parts)
    line(OPERATIONS, R.string.settings_storage_operations, R.string.settings_storage_operations_info, census.operations)
    line(SNAPSHOTS, R.string.settings_storage_snapshots, R.string.settings_storage_snapshots_info, census.snapshots)
    line(HEADER, R.string.settings_storage_header, R.string.settings_storage_header_info, census.headers)
    row { shape ->
        GroupedRow(shape, title = stringResource(R.string.settings_storage_own), subtitle = pluralStringResource(R.plurals.settings_storage_messages, census.ownMessages, census.ownMessages), info = stringResource(R.string.settings_storage_own_info))
    }
}

/** The files of one kind, largest first; a tap shows the folder it is in. */
@Composable
internal fun KindPage(viewModel: SettingsViewModel, kind: FileKind, onBack: () -> Unit, onShowFolder: (Id?) -> Unit) {
    val files by remember(kind) { viewModel.filesOf(kind) }.collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    CofferScaffold(title = stringResource(kind.label()), onBack = onBack) { padding ->
        val list = files
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(padding).padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@CofferScaffold
        }
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            items(list, key = { it.first.fileId.rowKey() }) { (file, path) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onShowFolder(file.folderId) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TypeTile(fileTypeIcon(file.name))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            path.ifEmpty { stringResource(R.string.settings_storage_root) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(Formatter.formatShortFileSize(context, file.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── On this phone ───────────────────────────────────────────────────────────

private val TELEGRAM_FILES = Color(0xFF2AABEE)
private val TELEGRAM_DATABASE = Color(0xFF0B6FA4)
private val INDEX = Color(0xFFF59E0B)
private val TEMPORARY = Color(0xFF9CA3AF)

/**
 * What Coffer takes up on this phone, apart from the vault, which is in Saved Messages (pre-release
 * item 6). Measured each time the screen opens.
 */
@Composable
internal fun PhoneSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val phone by viewModel.phone.collectAsStateWithLifecycle()
    val cache by viewModel.cachePrefs.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    LaunchedEffect(Unit) { viewModel.measurePhone() }

    SectionScaffold(stringResource(R.string.settings_card_phone), onBack) {
        val usage = phone
        if (usage == null) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@SectionScaffold
        }
        SettingsGroup(title = null) {
            row { shape ->
                GroupedBox(shape) {
                    Text(size(usage.total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settings_phone_total), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Bar(listOf(usage.telegramFiles to TELEGRAM_FILES, usage.telegramDatabase to TELEGRAM_DATABASE, usage.index to INDEX, usage.temporary to TEMPORARY))
                }
            }
        }
        SettingsGroup(stringResource(R.string.settings_phone_what)) {
            fun line(color: Color, title: Int, info: Int, bytes: Long) = row { shape ->
                GroupedRow(
                    shape,
                    title = stringResource(title),
                    info = stringResource(info),
                    leading = { Dot(color) },
                    trailing = { Text(size(bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            row { shape ->
                GroupedRow(
                    shape,
                    title = stringResource(R.string.settings_phone_downloads),
                    subtitle = if (cache.enabled) stringResource(R.string.settings_phone_cache_limit, limitLabel(cache.limitMb)) else stringResource(R.string.settings_phone_cache_off),
                    info = stringResource(R.string.settings_phone_downloads_info),
                    leading = { Dot(TELEGRAM_FILES) },
                    trailing = { Text(size(usage.telegramFiles), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            line(TELEGRAM_DATABASE, R.string.settings_phone_database, R.string.settings_phone_database_info, usage.telegramDatabase)
            line(INDEX, R.string.settings_phone_index, R.string.settings_phone_index_info, usage.index)
            line(TEMPORARY, R.string.settings_phone_temporary, R.string.settings_phone_temporary_info, usage.temporary)
        }
        SettingsGroup(stringResource(R.string.settings_cache)) {
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_cache_on), checked = cache.enabled,
                    info = stringResource(R.string.settings_cache_on_info),
                    onCheckedChange = { on -> viewModel.setCache { it.copy(enabled = on) } })
            }
            row { shape ->
                GroupedBox(shape) {
                    Text(stringResource(R.string.settings_cache_limit), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.alpha(if (cache.enabled) 1f else 0.38f))
                    Spacer(Modifier.height(8.dp))
                    val limits = CachePrefs.LIMITS_MB
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        limits.forEachIndexed { i, mb ->
                            SegmentedButton(
                                selected = cache.limitMb == mb,
                                onClick = { viewModel.setCache { it.copy(limitMb = mb) } },
                                enabled = cache.enabled,
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = limits.size),
                                icon = {},
                                label = { Text(limitLabel(mb), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                            )
                        }
                    }
                }
            }
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_cache_clear_on_lock), checked = cache.clearOnLock,
                    info = stringResource(R.string.settings_cache_clear_on_lock_info),
                    onCheckedChange = { on -> viewModel.setCache { it.copy(clearOnLock = on) } })
            }
            row { shape ->
                GroupedRow(shape, title = stringResource(R.string.settings_cache_clear), titleColor = MaterialTheme.colorScheme.error, onClick = { confirmClear = true })
            }
        }
    }
    if (confirmClear) {
        AppDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_cache_clear_title)) },
            text = { Text(stringResource(R.string.settings_cache_clear_text)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.clearCache() }) { Text(stringResource(R.string.settings_cache_clear_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

/** A cache limit as offered: whole megabytes or gigabytes of 1024, as the choices were made, not Android's 1000s. */
private fun limitLabel(mb: Int): String = if (mb < 1024) "$mb MB" else "${mb / 1024} GB"

// ── Drawing ──────────────────────────────────────────────────────────────────

/** A ring of [segments] by share, drawn in on first show, with [center] in the hole and [under] below. */
@Composable
private fun Ring(segments: List<Pair<Long, Color>>, center: String, under: String) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) { sweep.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val total = segments.sumOf { it.first }.toFloat()
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(200.dp)) {
                val stroke = 22.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(empty, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                if (total <= 0f) return@Canvas
                // A small gap between kinds, unless one kind is all there is.
                val shown = segments.filter { it.first > 0 }
                val gap = if (shown.size > 1) 2.5f else 0f
                var start = -90f
                for ((bytes, color) in shown) {
                    val angle = 360f * bytes / total * sweep.value
                    if (angle > gap) drawArc(color, start + gap / 2, angle - gap, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += angle
                }
            }
            Text(center, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        Text(under, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One bar split by share of [segments]; parts too small to see still get a sliver. */
@Composable
private fun Bar(segments: List<Pair<Long, Color>>) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
        drawRect(empty)
        val shown = segments.filter { it.first > 0 }
        val total = shown.sumOf { it.first }.toFloat()
        if (total <= 0f) return@Canvas
        val sliver = 3.dp.toPx()
        val widths = shown.map { maxOf(sliver, size.width * it.first / total) }
        val scale = size.width / widths.sum()
        var x = 0f
        for ((i, segment) in shown.withIndex()) {
            val w = widths[i] * scale
            drawRect(segment.second, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.padding(horizontal = 13.dp).size(16.dp).clip(CircleShape).background(color))
}
