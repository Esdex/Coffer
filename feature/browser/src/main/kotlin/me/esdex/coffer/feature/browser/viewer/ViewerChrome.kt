package me.esdex.coffer.feature.browser.viewer

import android.content.pm.ApplicationInfo
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.esdex.coffer.core.ui.components.BackButton
import me.esdex.coffer.core.ui.components.EmptyStateView
import me.esdex.coffer.core.ui.theme.Motion
import me.esdex.coffer.core.ui.theme.tabular
import me.esdex.coffer.feature.browser.R

/*
 * What every viewer shares, so they cannot drift apart: the bar of the PDF and text viewers, the file
 * menu, how a file loads, how a failure reads, and the look of what floats over a picture or a video.
 */

/** Behind whatever floats over a picture or a video: chips, buttons, the level of a swipe. */
internal val OverlayScrim = Color.Black.copy(alpha = 0.6f)

/** From the top and bottom edges of a picture, so the bars stay readable over a white one. */
internal val OverlayEdge = Color.Black.copy(alpha = 0.7f)

/** One spinner size for every viewer, on its own or inside the play button. */
internal val ViewerSpinner = 40.dp

/** The bar of a viewer on the app's own background: the way back, the file's name, its actions, the menu. */
@Composable
internal fun ViewerTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onClick = onBack)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
        ViewerMenuButton(tint = LocalContentColor.current, menu = menu)
    }
}

/** The three dots and what they open; shared by the bars on the app's background and over a picture. */
@Composable
internal fun ViewerMenuButton(tint: Color, menu: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_cd_more), tint = tint) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

/** What can be done with any file a viewer shows: save a copy out, or hand it to another app. */
@Composable
internal fun FileMenuItems(close: () -> Unit, onSave: () -> Unit, onOpenWith: () -> Unit) {
    ViewerMenuItem(Icons.Outlined.SaveAlt, stringResource(R.string.browser_export)) {
        close()
        onSave()
    }
    ViewerMenuItem(Icons.Outlined.OpenInNew, stringResource(R.string.viewer_open_with)) {
        close()
        onOpenWith()
    }
}

/** A menu entry with its icon in front, as every menu in the app has; [trailing] for a value or a check. */
@Composable
internal fun ViewerMenuItem(icon: ImageVector?, label: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = icon?.let { { Icon(it, null) } },
        trailingIcon = trailing,
        onClick = onClick,
    )
}

/**
 * A file on its way into memory: how much of it, once its size is known, and a spinner before. [onDark]
 * over a picture's black, the app's colours otherwise.
 */
@Composable
internal fun FileLoading(loaded: Long, size: Long, onDark: Boolean, modifier: Modifier = Modifier) {
    val color = if (onDark) Color.White else MaterialTheme.colorScheme.primary
    if (size <= 0) {
        CircularProgressIndicator(color = color, modifier = modifier.size(ViewerSpinner))
        return
    }
    val context = LocalContext.current
    val shown by animateFloatAsState((loaded.toFloat() / size).coerceIn(0f, 1f), Motion.standard(), label = "file loading")
    Column(modifier.padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(
            progress = { shown },
            color = color,
            trackColor = if (onDark) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.transfers_amount, Formatter.formatShortFileSize(context, loaded), Formatter.formatShortFileSize(context, size)),
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = if (onDark) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A file a viewer could not show, the same in every viewer: the app's empty state, in white over a
 * picture's black. [detail] is the player's own code, which tells a failed read from a format the
 * phone cannot play; shown in debug builds only, as it means nothing to anyone else.
 */
@Composable
internal fun ViewerFailure(icon: ImageVector, title: String, subtitle: String?, onDark: Boolean, modifier: Modifier = Modifier, detail: String? = null) {
    val debuggable = LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val text = listOfNotNull(subtitle, detail?.takeIf { debuggable }).joinToString("\n").ifEmpty { null }
    if (!onDark) {
        EmptyStateView(title = title, subtitle = text, icon = icon, modifier = modifier.padding(horizontal = 32.dp))
        return
    }
    val scheme = MaterialTheme.colorScheme.copy(
        secondaryContainer = Color.White.copy(alpha = 0.12f),
        onSecondaryContainer = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = 0.7f),
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            EmptyStateView(title = title, subtitle = text, icon = icon, modifier = modifier.padding(horizontal = 32.dp))
        }
    }
}
