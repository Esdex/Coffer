package me.esdex.coffer.feature.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import me.esdex.coffer.core.ui.theme.Motion

/**
 * Coffer's own icon, for the welcome's first page, where a waving hand was: as the launcher shows it,
 * in the phone's own icon shape. It springs up once when [shown], and now and then light runs across
 * it, as over gold.
 */
@Composable
internal fun AppIconArt(shown: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { ICON.roundToPx() }
    val icon = remember(px) { context.packageManager.getApplicationIcon(context.applicationInfo).toBitmap(px, px).asImageBitmap() }
    val pop = remember { Animatable(START_SCALE) }
    LaunchedEffect(shown) {
        if (shown) pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    // Where the light is, from before the icon's left edge to past its right, then a rest.
    val sweep by rememberInfiniteTransition(label = "shine").animateFloat(
        initialValue = -SHINE_WIDTH,
        targetValue = -SHINE_WIDTH,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = SHINE_CYCLE
                -SHINE_WIDTH at 0
                -SHINE_WIDTH at SHINE_REST using Motion.Easing
                1f + SHINE_WIDTH at SHINE_CYCLE
            },
        ),
        label = "shine sweep",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            icon,
            contentDescription = null,
            modifier = Modifier
                .size(ICON)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    // Its own layer, so the light lands only where the icon is.
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    val x = size.width * sweep
                    val band = size.width * SHINE_WIDTH
                    drawRect(
                        Brush.linearGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = 0.35f),
                            1f to Color.Transparent,
                            start = Offset(x - band, 0f),
                            end = Offset(x + band, size.height * 0.6f),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
        )
    }
}

/** The size of the icon: the circles on the other pages are as wide. */
private val ICON = 168.dp
private const val START_SCALE = 0.6f

// The light's width as a share of the icon; a rest, then one pass as long as the shimmer's.
private const val SHINE_WIDTH = 0.35f
private const val SHINE_REST = 2_400
private const val SHINE_CYCLE = SHINE_REST + Motion.SHIMMER
