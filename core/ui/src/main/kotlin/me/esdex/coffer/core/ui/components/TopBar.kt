package me.esdex.coffer.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import me.esdex.coffer.core.ui.R
import me.esdex.coffer.core.ui.theme.CofferHazeStyle
import me.esdex.coffer.core.ui.theme.LocalAmoledMode

/**
 * The way back out of a screen: the arrow inside a filled circle, as Android's own settings draw it.
 * One component so the shape cannot drift between screens. Moving up a folder is not this: it keeps a
 * plain button, since it is not the way out.
 */
@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // Another glyph where back means something else, as folding the music player away.
    icon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    contentDescription: String = stringResource(R.string.ui_back),
) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(icon, contentDescription = contentDescription)
    }
}

/**
 * A large top bar that opens collapsed, the way Android's settings screens do: the title beside the
 * back button, and pulling the page down unfolds it. The bar has to be measured before it can be
 * collapsed, so the first non-zero limit is waited for rather than assumed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberCollapsedTopBarBehavior(): TopAppBarScrollBehavior {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(behavior) {
        val limit = snapshotFlow { behavior.state.heightOffsetLimit }.filter { it < 0f }.first()
        behavior.state.heightOffset = limit
    }
    return behavior
}

/**
 * The frame of a screen with a large title: its bar, the back button, the snackbar, and in AMOLED the
 * frosted glass over content scrolling under the bar. Each screen handles its own insets through this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CofferScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val amoled = LocalAmoledMode.current
    val haze = remember { HazeState() }
    val behavior = rememberCollapsedTopBarBehavior()
    CompositionLocalProvider(LocalHazeState provides haze) {
        Scaffold(
            modifier = modifier.nestedScroll(behavior.nestedScrollConnection),
            snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
            floatingActionButton = floatingActionButton,
            topBar = {
                LargeTopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { if (onBack != null) BackButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) },
                    actions = actions,
                    scrollBehavior = behavior,
                    // In AMOLED both colours go transparent, or the bar fills grey the moment the page moves.
                    colors = if (amoled) {
                        TopAppBarDefaults.largeTopAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
                    } else {
                        TopAppBarDefaults.largeTopAppBarColors()
                    },
                    modifier = if (amoled) Modifier.hazeEffect(state = haze, style = CofferHazeStyle.topBar) else Modifier,
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().hazeSource(haze)) { content(padding) }
        }
    }
}
