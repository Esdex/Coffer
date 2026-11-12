package me.esdex.coffer.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.esdex.coffer.core.ui.R
import me.esdex.coffer.core.ui.theme.Corners

/**
 * Rows that belong together, drawn as one block of cards: the outer corners rounded far more than the
 * inner ones, so several cards read as one thing without a border, as in Android's own settings. Rows go
 * through [GroupBuilder] because each needs to know where in the group it sits.
 */
@Composable
fun SettingsGroup(title: String?, modifier: Modifier = Modifier, content: GroupBuilder.() -> Unit) {
    val rows = GroupBuilder().apply(content).rows
    if (rows.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
            rows.forEachIndexed { index, row -> row(groupShape(index, rows.size)) }
        }
    }
}

class GroupBuilder internal constructor() {
    internal val rows = mutableListOf<@Composable (Shape) -> Unit>()

    /** One row; the shape it is handed already knows where it sits. */
    fun row(content: @Composable (Shape) -> Unit) {
        rows += content
    }
}

/**
 * One card: a title, an optional subtitle for what the row *is* (the chosen value), an "i" that opens
 * what the setting *does*, and whatever belongs on the right. Keeping explanations out of the list is
 * what lets a screen of settings be taken in at a glance.
 */
@Composable
fun GroupedRow(
    shape: Shape,
    title: String,
    subtitle: String? = null,
    info: String? = null,
    enabled: Boolean = true,
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val fade = if (enabled) 1f else 0.38f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = (titleColor ?: MaterialTheme.colorScheme.onSurface).copy(alpha = fade),
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = fade))
            }
        }
        if (info != null) {
            var showInfo by remember { mutableStateOf(false) }
            Spacer(Modifier.width(4.dp))
            // Never faded with the row: on a setting that cannot be changed it is the one thing still worth pressing.
            IconButton(onClick = { showInfo = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.ui_info), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            if (showInfo) {
                AppDialog(
                    onDismissRequest = { showInfo = false },
                    title = { Text(title) },
                    text = { Text(info) },
                    confirmButton = { TextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.ui_ok)) } },
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(if (info != null) 8.dp else 16.dp))
            trailing()
        }
    }
}

/** A row with a switch; a tick in the thumb when on, a padlock when it cannot be moved. The whole row is the target. */
@Composable
fun GroupedSwitch(
    shape: Shape,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    info: String? = null,
    enabled: Boolean = true,
) {
    GroupedRow(
        shape = shape,
        title = title,
        subtitle = subtitle,
        info = info,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                thumbContent = when {
                    !enabled -> { { Icon(Icons.Filled.Lock, null, Modifier.size(SwitchDefaults.IconSize)) } }
                    checked -> { { Icon(Icons.Filled.Check, null, Modifier.size(SwitchDefaults.IconSize)) } }
                    else -> null
                },
            )
        },
    )
}

/** Room for something of its own inside a group, shaped like the rows around it. */
@Composable
fun GroupedBox(shape: Shape, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 20.dp, vertical = 16.dp),
        content = content,
    )
}

/** The round filled icon at the head of a row that leads somewhere; the glyph takes black or white by brightness. */
@Composable
fun GroupedRoundIcon(icon: ImageVector, color: Color, iconColor: Color? = null) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = iconColor ?: if (color.luminance() > 0.45f) Color.Black else Color.White, modifier = Modifier.size(22.dp))
    }
}

/** Round on the outside of the group, nearly square within it. */
fun groupShape(index: Int, count: Int): Shape = when {
    count == 1 -> RoundedCornerShape(OUTER_RADIUS)
    index == 0 -> RoundedCornerShape(topStart = OUTER_RADIUS, topEnd = OUTER_RADIUS, bottomStart = INNER_RADIUS, bottomEnd = INNER_RADIUS)
    index == count - 1 -> RoundedCornerShape(topStart = INNER_RADIUS, topEnd = INNER_RADIUS, bottomStart = OUTER_RADIUS, bottomEnd = OUTER_RADIUS)
    else -> RoundedCornerShape(INNER_RADIUS)
}

private val OUTER_RADIUS = Corners.Panel
private val INNER_RADIUS = Corners.Inner
private val GAP = 3.dp

// A Material switch plus the row's padding: what a row is when it holds one.
private val ROW_MIN_HEIGHT = 64.dp
