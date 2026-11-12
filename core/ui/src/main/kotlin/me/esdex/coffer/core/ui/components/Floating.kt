package me.esdex.coffer.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeEffect
import me.esdex.coffer.core.ui.theme.CofferHazeStyle
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.LocalAmoledMode

/*
 * What floats over a screen, drawn alike everywhere: the cards along the bottom (the mini player, the
 * actions on a selection, the short notices), and the menus. In pure black a shadow is lost on the
 * page, so there they are frosted glass like the top bar, with a faint white glow to stand off it.
 */

/**
 * A card floating over the bottom of a screen, clear of the navigation bar, rounded like the app's
 * panels. [inset] keeps it off the screen's edges; a notice inside a screen's own padding passes none.
 */
@Composable
fun FloatingCard(modifier: Modifier = Modifier, inset: Boolean = true, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Corners.Large)
    val haze = LocalHazeState.current.takeIf { LocalAmoledMode.current }
    Surface(
        shape = shape,
        color = if (haze != null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = if (haze != null) 0.dp else 6.dp,
        modifier = modifier.fillMaxWidth()
            .then(if (inset) Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp) else Modifier)
            .then(
                if (haze != null) {
                    Modifier.shadow(GLOW, shape, ambientColor = Color.White, spotColor = Color.White).clip(shape).hazeEffect(state = haze, style = CofferHazeStyle.topBar)
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}

/** A short notice over the bottom of a screen, on a [FloatingCard]; above whatever floats there already. */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        FloatingCard(inset = false, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            // Laid out here rather than by Material's snackbar, whose own shadowed surface would show
            // through the glass in pure black.
            Row(Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                data.visuals.actionLabel?.let { label -> TextButton(onClick = data::performAction) { Text(label) } }
            }
        }
    }
}

/** A menu from a button: rounded like the app's tiles; in pure black, black with a hairline edge as the sheets have. */
@Composable
fun AppMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, offset: DpOffset = DpOffset(0.dp, 0.dp), content: @Composable ColumnScope.() -> Unit) {
    val amoled = LocalAmoledMode.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = RoundedCornerShape(Corners.Tile),
        containerColor = if (amoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
        border = if (amoled) BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)) else null,
        content = content,
    )
}

private val GLOW = 12.dp
