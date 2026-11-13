package me.esdex.coffer.feature.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import me.esdex.coffer.core.ui.components.Padlock
import me.esdex.coffer.core.ui.theme.Motion

/**
 * Over the step for the code Telegram sends: a message, as the code comes in one, with its digits as
 * dots turning up one by one, held a moment, then gone to come again. As many dots as the code has
 * digits mostly does: five. In the theme's colours, the message drawn as on the welcome's last page.
 */
@Composable
internal fun CodeArt(modifier: Modifier = Modifier) {
    val time by rememberInfiniteTransition(label = "code").animateFloat(
        initialValue = 0f,
        targetValue = CODE_CYCLE.toFloat(),
        animationSpec = infiniteRepeatable(tween(CODE_CYCLE, easing = LinearEasing)),
        label = "code time",
    )
    val bubble = MaterialTheme.colorScheme.primaryContainer
    val dot = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(modifier) {
        val w = size.minDimension
        val left = (size.width - w) / 2 + w * 0.08f
        val top = (size.height - w) / 2 + w * 0.22f
        val width = w * 0.84f
        val height = w * 0.48f
        drawRoundRect(bubble, Offset(left, top), Size(width, height), CornerRadius(w * 0.14f))
        drawPath(
            Path().apply {
                moveTo(left + w * 0.10f, top + height - w * 0.02f)
                lineTo(left + w * 0.02f, top + height + w * 0.13f)
                lineTo(left + w * 0.30f, top + height - w * 0.02f)
                close()
            },
            bubble,
        )
        // All of them fade together once the last has been seen a moment.
        val fade = 1f - ((time - FADE_AT) / Motion.SCREEN).coerceIn(0f, 1f)
        val gap = width / (DIGITS + 1)
        for (i in 0 until DIGITS) {
            val shown = ((time - i * DOT_STEP) / Motion.QUICK).coerceIn(0f, 1f)
            if (shown == 0f || fade == 0f) continue
            drawCircle(
                dot.copy(alpha = shown * fade),
                radius = w * 0.045f * (0.6f + 0.4f * shown),
                center = Offset(left + gap * (i + 1), top + height / 2),
            )
        }
    }
}

private const val DIGITS = 5
private const val DOT_STEP = 220
private const val FADE_AT = DIGITS * DOT_STEP + 900
private const val CODE_CYCLE = FADE_AT + Motion.SCREEN + 500

/**
 * Over the step for the Telegram password: the padlock the vault's password has, shutting once as the
 * step opens, for an account that is locked a second time.
 */
@Composable
internal fun ShuttingPadlock(modifier: Modifier = Modifier) {
    val closed = remember { Animatable(0f) }
    LaunchedEffect(Unit) { closed.animateTo(1f, tween(Motion.SCREEN, delayMillis = Motion.SCREEN, easing = Motion.Easing)) }
    Padlock(closed.value, modifier)
}
