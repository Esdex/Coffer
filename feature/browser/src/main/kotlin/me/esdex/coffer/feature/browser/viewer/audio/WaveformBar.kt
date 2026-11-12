package me.esdex.coffer.feature.browser.viewer.audio

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.random.Random
import me.esdex.coffer.core.ui.theme.Motion

/**
 * The progress bar as the song's waveform, after Arcanum's WaveformView: the bars worked out so far
 * rise into place and glide to new heights as louder stretches come in, the rest of the track shimmers
 * until its bars arrive. A tap or a drag seeks, from the start: the waveform follows what the player
 * has loaded, and may be a long way behind.
 */
@Composable
internal fun WaveformBar(
    bars: List<Float>,
    complete: Boolean,
    progress: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val played = MaterialTheme.colorScheme.primary
    val ahead = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)
    val unknown = MaterialTheme.colorScheme.onBackground

    // The position comes a few times a second: a tween of about that length makes the fill run smoothly.
    val shownProgress by animateFloatAsState(progress, Motion.linear(200), label = "waveform progress")
    val heights = List(WaveformBytes.BARS) { i ->
        animateFloatAsState(bars.getOrNull(i) ?: 0f, Motion.standard(), label = "waveform bar")
    }
    val placeholder = remember { List(WaveformBytes.BARS) { Random.nextFloat().coerceIn(0.1f, 0.9f) } }
    val shimmer = if (complete) {
        0f
    } else {
        val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(Motion.linear(Motion.SHIMMER), RepeatMode.Restart),
            label = "shimmer x",
        )
        x
    }

    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    val seekable = if (!enabled) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    scrub((offset.x / size.width).coerceIn(0f, 1f))
                    end()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                ) { change, _ -> scrub((change.position.x / size.width).coerceIn(0f, 1f)) }
            }
    }

    Canvas(modifier.then(seekable)) {
        val barWidth = size.width / WaveformBytes.BARS
        val centre = size.height / 2f
        val progressX = size.width * shownProgress
        val stroke = (barWidth * 0.6f).coerceAtLeast(2.5f)
        for (i in 0 until WaveformBytes.BARS) {
            val x = i * barWidth + barWidth / 2f
            if (i < bars.size) {
                val h = (heights[i].value * size.height * 0.9f).coerceAtLeast(stroke)
                drawLine(if (x <= progressX) played else ahead, Offset(x, centre - h / 2f), Offset(x, centre + h / 2f), stroke, StrokeCap.Round)
            } else {
                // Still to come: faint, with a light running over it.
                val near = 1f - (abs(x / size.width - shimmer) * 6f).coerceIn(0f, 1f)
                val alpha = (0.15f + near * 0.35f) * 0.35f
                val h = placeholder[i] * size.height * 0.8f
                drawLine(unknown.copy(alpha = alpha), Offset(x, centre - h / 2f), Offset(x, centre + h / 2f), stroke, StrokeCap.Round)
            }
        }
    }
}
