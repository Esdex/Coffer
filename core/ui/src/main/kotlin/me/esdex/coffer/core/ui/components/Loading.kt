package me.esdex.coffer.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Something is on its way: three dots in a circle, rising one after another as a wave runs through
 * them, each a little brighter at the top. The circle is as the pictures of the first screens have it.
 */
@Composable
fun LoadingDots(modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(WAVE, easing = LinearEasing)),
        label = "loading wave",
    )
    val circle = MaterialTheme.colorScheme.primaryContainer
    val dot = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(modifier) {
        val s = size.minDimension
        drawCircle(circle, radius = s * 0.38f, center = center)
        val gap = s * 0.17f
        for (i in 0 until 3) {
            // Each dot a third of a wave behind the one before; up only, resting at the bottom between.
            val lift = max(0f, sin(((phase - i * 0.18f) * 2 * PI).toFloat()))
            drawCircle(
                dot.copy(alpha = 0.55f + 0.45f * lift),
                radius = s * 0.05f,
                center = Offset(center.x + (i - 1) * gap, center.y + s * 0.03f - lift * s * 0.09f),
            )
        }
    }
}

private const val WAVE = 1_100
