package me.esdex.coffer.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A little ghost wandering to and fro where there is nothing to show: it drifts from side to side,
 * looking the way it goes, bobs as it floats, its hem rippling, and blinks now and then. Its shadow
 * shrinks as it rises. Drawn here rather than taken from an animation file, so it is Coffer's own and
 * in the theme's colours. [speed] slows or quickens all of it at once.
 */
@Composable
fun Ghost(modifier: Modifier = Modifier, speed: Float = 1f) {
    val transition = rememberInfiniteTransition(label = "ghost")
    fun cycle(millis: Int) = infiniteRepeatable<Float>(tween((millis / speed).toInt(), easing = LinearEasing))
    val drift by transition.animateFloat(0f, TAU, cycle(DRIFT), label = "drift")
    val bob by transition.animateFloat(0f, TAU, cycle(BOB), label = "bob")
    val hem by transition.animateFloat(0f, TAU, cycle(HEM), label = "hem")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = (BLINK / speed).toInt()
                1f at 0
                1f at durationMillis - 160
                0.1f at durationMillis - 80
                1f at durationMillis
            },
        ),
        label = "blink",
    )
    val body = MaterialTheme.colorScheme.primaryContainer
    val eyes = MaterialTheme.colorScheme.onPrimaryContainer
    val shadow = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Canvas(modifier) {
        val s = size.minDimension
        val width = s * 0.40f
        val height = s * 0.48f
        val rise = (sin(bob) + 1f) / 2f
        val cx = size.width / 2 + sin(drift) * s * 0.20f
        val top = (size.height - s) / 2 + s * 0.14f + (1f - rise) * s * 0.06f
        // Which way it goes: the slope of the drift, so the eyes turn before it turns back.
        val facing = cos(drift)

        // The shadow on the ground, smaller and fainter the higher it floats.
        val shadowWidth = width * (0.9f - rise * 0.25f)
        drawOval(
            shadow.copy(alpha = shadow.alpha * (1f - rise * 0.4f)),
            topLeft = Offset(cx - shadowWidth / 2, (size.height - s) / 2 + s * 0.86f),
            size = Size(shadowWidth, s * 0.05f),
        )

        // A round head, straight sides and a hem of four waves running along it.
        val left = cx - width / 2
        val radius = width / 2
        val hemY = top + height
        val ghost = Path().apply {
            moveTo(left, top + radius)
            arcTo(Rect(left, top, left + width, top + width), 180f, 180f, false)
            lineTo(left + width, hemY)
            val waves = 4
            val step = width / waves
            for (i in 0 until waves) {
                val x0 = left + width - i * step
                val lift = sin(hem + i * PI.toFloat() / 2) * s * 0.02f
                quadraticTo(x0 - step / 2, hemY - s * 0.06f + lift, x0 - step, hemY + lift * 0.5f)
            }
            close()
        }
        drawPath(ghost, body)

        // The eyes, looking the way it drifts, and shut for a moment now and then.
        val eyeY = top + width * 0.52f
        val eyeGap = width * 0.18f
        val look = facing * width * 0.08f
        val eye = Size(width * 0.12f, width * 0.18f * blink)
        for (side in listOf(-1, 1)) {
            drawOval(eyes, topLeft = Offset(cx + side * eyeGap + look - eye.width / 2, eyeY - eye.height / 2), size = eye)
        }
    }
}

private const val TAU = (2 * PI).toFloat()

// Each motion on its own cycle, none a multiple of another, so the ghost never quite repeats itself.
private const val DRIFT = 7_000
private const val BOB = 2_300
private const val HEM = 1_100
private const val BLINK = 4_300
