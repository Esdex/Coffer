package me.esdex.coffer.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlinx.coroutines.launch
import me.esdex.coffer.core.ui.theme.Motion

/**
 * How an operation ended, played once: a circle springs up and a check is drawn into it, or for a
 * failure a cross, after which the circle shakes its head. Drawn here, in the theme's colours.
 */
@Composable
fun ResultMark(success: Boolean, modifier: Modifier = Modifier) {
    val pop = remember { Animatable(0f) }
    val draw = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
        draw.animateTo(1f, tween(Motion.SCREEN, delayMillis = Motion.QUICK, easing = Motion.Easing))
        if (!success) {
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = Motion.AMBIENT
                    -1f at 100
                    1f at 220
                    -0.7f at 340
                    0.5f at 460
                    -0.2f at 580
                },
            )
        }
    }
    val circle = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val mark = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Canvas(modifier) {
        val s = size.minDimension
        val origin = Offset((size.width - s) / 2, (size.height - s) / 2)
        fun at(x: Float, y: Float) = Offset(origin.x + x * s, origin.y + y * s)
        val stroke = Stroke(width = s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        translate(left = shake.value * s * 0.05f) {
            scale(pop.value, pivot = center) {
                drawCircle(circle, radius = s * 0.40f, center = center)
                if (success) {
                    val check = Path().apply {
                        moveTo(at(0.31f, 0.52f).x, at(0.31f, 0.52f).y)
                        lineTo(at(0.44f, 0.65f).x, at(0.44f, 0.65f).y)
                        lineTo(at(0.70f, 0.38f).x, at(0.70f, 0.38f).y)
                    }
                    val measure = PathMeasure().apply { setPath(check, false) }
                    val drawn = Path()
                    measure.getSegment(0f, measure.length * draw.value, drawn, true)
                    drawPath(drawn, mark, style = stroke)
                } else {
                    // The two strokes of the cross, one after the other.
                    val first = (draw.value * 2f).coerceIn(0f, 1f)
                    val second = (draw.value * 2f - 1f).coerceIn(0f, 1f)
                    val a = at(0.36f, 0.36f)
                    val b = at(0.64f, 0.64f)
                    val c = at(0.64f, 0.36f)
                    val d = at(0.36f, 0.64f)
                    if (first > 0f) drawLine(mark, a, a + (b - a) * first, stroke.width, StrokeCap.Round)
                    if (second > 0f) drawLine(mark, c, c + (d - c) * second, stroke.width, StrokeCap.Round)
                }
            }
        }
    }
}
