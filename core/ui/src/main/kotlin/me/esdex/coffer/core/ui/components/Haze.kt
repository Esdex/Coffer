package me.esdex.coffer.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

/**
 * The HazeState a screen puts up for the frosted glass of its overlays, or null when it has none.
 * Null rather than an empty state on purpose: blurring a state with no source draws nothing at all,
 * which in AMOLED is a see-through dialog. A null makes the overlay paint a solid surface instead.
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/** Frosted glass over what is behind, or [solid] when nothing behind can be blurred. */
fun Modifier.hazeOrSolid(state: HazeState?, style: HazeStyle, solid: Color): Modifier =
    if (state != null) hazeEffect(state = state, style = style) else background(solid)
