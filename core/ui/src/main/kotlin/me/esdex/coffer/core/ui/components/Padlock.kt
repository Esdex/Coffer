package me.esdex.coffer.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toSize
import me.esdex.coffer.core.ui.theme.Motion
import kotlin.math.roundToInt

/**
 * A padlock in the theme's colours, square, filling its box. [closed] runs from 0, the shackle raised
 * and swung open, to 1, shut into the body.
 */
@Composable
fun Padlock(closed: Float, modifier: Modifier = Modifier) {
    val body = MaterialTheme.colorScheme.primary
    val shackle = MaterialTheme.colorScheme.onSurfaceVariant
    val keyhole = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier) {
        val w = size.minDimension
        val cx = size.width / 2
        val top = (size.height - w) / 2
        val bodyTop = top + w * 0.44f
        val bodyWidth = w * 0.66f
        val bodyHeight = w * 0.48f
        val radius = w * 0.20f
        val arcCentre = top + w * 0.30f
        val open = 1f - closed.coerceIn(0f, 1f)

        // The shackle: raised and swung about its right leg as it opens.
        translate(top = -w * 0.14f * open) {
            rotate(degrees = -28f * open, pivot = Offset(cx + radius, bodyTop)) {
                val path = Path().apply {
                    moveTo(cx - radius, bodyTop + w * 0.04f)
                    lineTo(cx - radius, arcCentre)
                    arcTo(Rect(cx - radius, arcCentre - radius, cx + radius, arcCentre + radius), 180f, 180f, false)
                    lineTo(cx + radius, bodyTop + w * 0.04f)
                }
                drawPath(path, shackle, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round))
            }
        }
        drawRoundRect(body, Offset(cx - bodyWidth / 2, bodyTop), Size(bodyWidth, bodyHeight), CornerRadius(w * 0.10f))
        drawCircle(keyhole, radius = w * 0.055f, center = Offset(cx, bodyTop + bodyHeight * 0.42f))
        drawRoundRect(
            keyhole,
            Offset(cx - w * 0.022f, bodyTop + bodyHeight * 0.42f),
            Size(w * 0.044f, bodyHeight * 0.30f),
            CornerRadius(w * 0.02f),
        )
    }
}

/**
 * The padlock that flies when the user locks the vault: from the Lock item they tapped to where the
 * unlock screen shows its padlock, shutting on the way. [from] is set by the tap, [to] by the unlock
 * screen wherever it lays its padlock out; both in screen coordinates, as the menu is a window of its own.
 */
@Stable
class LockFlight {
    var from by mutableStateOf<Rect?>(null)
        private set
    var to by mutableStateOf<Rect?>(null)

    /** While true the unlock screen keeps its own padlock hidden: the flying one is on its way there. */
    val flying: Boolean get() = from != null

    fun launch(from: Rect) {
        // Where the unlock screen was last time is not where it is yet: wait for it to be laid out anew.
        to = null
        this.from = from
    }

    fun landed() {
        from = null
    }
}

val LocalLockFlight = staticCompositionLocalOf { LockFlight() }

/** Where [this] is on the screen, for [LockFlight]. */
fun LayoutCoordinates.screenRect(): Rect = Rect(positionOnScreen(), size.toSize())

/** Records where the unlock screen's padlock is, for the flight to land on; hidden while one is flying. */
fun Modifier.lockFlightTarget(flight: LockFlight): Modifier = this
    .onGloballyPositioned { flight.to = it.screenRect() }
    // Hidden, not removed: it keeps its place, so the flight has somewhere to land.
    .graphicsLayer { alpha = if (flight.flying) 0f else 1f }

/**
 * Over the whole app: the padlock in flight. It waits at the tapped item, open, until the unlock screen
 * has laid out its own; then flies there over [Motion.AMBIENT], growing to its size and shutting in the
 * second half, and hands over to it.
 */
@Composable
fun LockFlightOverlay(flight: LockFlight) {
    val from = flight.from ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen() }) {
        val progress = remember(from) { Animatable(0f) }
        val to = flight.to
        LaunchedEffect(from, to != null) {
            if (to == null) return@LaunchedEffect
            progress.animateTo(1f, tween(Motion.AMBIENT, easing = Motion.Easing))
            flight.landed()
        }
        val p = progress.value
        val target = to ?: from
        val left = from.left + (target.left - from.left) * p
        val top = from.top + (target.top - from.top) * p
        val side = from.width + (target.width - from.width) * p
        Padlock(
            closed = ((p - SHUT_FROM) / (1f - SHUT_FROM)).coerceIn(0f, 1f),
            modifier = Modifier
                .offset { IntOffset((left - origin.x).roundToInt(), (top - origin.y).roundToInt()) }
                .size(with(density) { side.toDp() }),
        )
    }
}

// The shackle stays open for the first part of the flight and shuts on the way in.
private const val SHUT_FROM = 0.45f
