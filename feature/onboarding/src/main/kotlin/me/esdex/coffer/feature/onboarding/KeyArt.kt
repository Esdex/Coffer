package me.esdex.coffer.feature.onboarding

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import me.esdex.coffer.core.ui.theme.Motion

/**
 * The key that only the user holds, for the welcome page that says so: in a circle, as the account on
 * the sign-in step, turning a whole round about its upright now and then, as a coin turns, and resting
 * between, so it does not read as something loading. In the theme's colours.
 */
@Composable
internal fun KeyArt(modifier: Modifier = Modifier) {
    val turn by rememberInfiniteTransition(label = "key").animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = CYCLE
                0f at 0
                0f at REST using Motion.Easing
                360f at CYCLE
            },
        ),
        label = "key turn",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(CIRCLE).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(CIRCLE * 0.58f).graphicsLayer {
                    rotationY = turn
                    // A little depth, so the turn reads as a turn and not a squeeze.
                    cameraDistance = 12f * density
                },
            )
        }
    }
}

private val CIRCLE = 168.dp

// A rest, then one round over a little more than a screen's change.
private const val REST = 1_600
private const val CYCLE = REST + Motion.REVEAL + Motion.SCREEN
