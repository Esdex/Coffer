package me.esdex.coffer.feature.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import me.esdex.coffer.core.ui.theme.Motion

/**
 * Saved Messages, as a message with a bookmark in it: the welcome page that says the files are kept
 * there. Plays once when [shown]: the message pops up from small with a little spring, then the
 * bookmark drops into it, as a message being saved; then it stays still. A plain bookmark, not
 * Telegram's own mark. In the theme's colours.
 */
@Composable
internal fun SavedMessagesArt(shown: Boolean, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(START_SCALE) }
    val drop = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        drop.animateTo(1f, Motion.standard())
    }
    val bubble = MaterialTheme.colorScheme.primaryContainer
    val bookmark = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(modifier) {
        val w = size.minDimension
        val left = (size.width - w) / 2 + w * 0.10f
        val top = (size.height - w) / 2 + w * 0.18f
        val width = w * 0.80f
        val height = w * 0.58f
        scale(pop.value, pivot = Offset(size.width / 2, size.height / 2)) {
            // A message: rounded, with its tail at the bottom left, as a chat draws one.
            drawRoundRect(bubble, Offset(left, top), Size(width, height), CornerRadius(w * 0.16f))
            val tail = Path().apply {
                moveTo(left + w * 0.10f, top + height - w * 0.02f)
                lineTo(left + w * 0.02f, top + height + w * 0.14f)
                lineTo(left + w * 0.30f, top + height - w * 0.02f)
                close()
            }
            drawPath(tail, bubble)
            // The bookmark, dropping in from above as it fades in.
            val cx = left + width / 2
            val markWidth = w * 0.22f
            val markTop = top + height * 0.20f
            val markBottom = top + height * 0.82f
            translate(top = -w * DROP * (1f - drop.value)) {
                val mark = Path().apply {
                    moveTo(cx - markWidth / 2, markTop)
                    lineTo(cx + markWidth / 2, markTop)
                    lineTo(cx + markWidth / 2, markBottom)
                    lineTo(cx, markBottom - w * 0.09f)
                    lineTo(cx - markWidth / 2, markBottom)
                    close()
                }
                drawPath(mark, bookmark.copy(alpha = drop.value))
            }
        }
    }
}

private const val START_SCALE = 0.6f

/** How far above its place the bookmark starts, as a share of the drawing. */
private const val DROP = 0.18f
