package me.esdex.coffer.feature.browser.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import me.esdex.coffer.feature.browser.R

internal enum class SwipeKind { VOLUME, BRIGHTNESS }

/** How much of the range a swipe the height of the picture covers; above 1, so a thumb's reach is enough. */
internal const val SWIPE_GAIN = 1.5f

internal val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** "1.5x", "2x", "0.25x": a whole number without its ".0". */
internal fun speedLabel(speed: Float): String = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "x"

internal fun formatTime(ms: Long): String {
    val total = ms / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * The video, after Arcanum's VideoSurfacePage: pinch to zoom (a double tap seeks here, so it does not
 * zoom), double tap on either half for 10 s back or on, vertical swipes for brightness (left) and
 * volume (right), hold for 2x. Each extra only when its switch in Settings is on.
 */
@Composable
internal fun VideoSurface(
    player: Player?,
    videoAspect: Float,
    onTap: () -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onZoomChanged: (Float) -> Unit,
    swipeLeft: Boolean,
    swipeRight: Boolean,
    onSwipeStart: (left: Boolean) -> Unit,
    onSwipe: (fraction: Float) -> Unit,
    onSwipeEnd: () -> Unit,
    onHoldStart: (() -> Unit)?,
    onHoldEnd: () -> Unit,
) {
    // The detectors below are keyed on what is switched on, so they read the current lambdas here.
    val swipeStart by rememberUpdatedState(onSwipeStart)
    val swipeBy by rememberUpdatedState(onSwipe)
    val swipeEnd by rememberUpdatedState(onSwipeEnd)
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    val canHold = onHoldStart != null
    var width by remember { mutableIntStateOf(0) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    val scale = remember { Animatable(1f) }
    val panX = remember { Animatable(0f) }
    val panY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()

    // Where the picture is inside the window: pan is bounded by the picture, not the black around it.
    val rendered = remember(videoAspect, container) {
        if (container == IntSize.Zero || videoAspect <= 0f) {
            container
        } else {
            val cw = container.width.toFloat()
            val ch = container.height.toFloat()
            if (cw / ch > videoAspect) IntSize((ch * videoAspect).toInt(), ch.toInt()) else IntSize(cw.toInt(), (cw / videoAspect).toInt())
        }
    }
    fun maxPan(s: Float) = maxOf(0f, (rendered.width * s - container.width) / 2f) to maxOf(0f, (rendered.height * s - container.height) / 2f)

    // Leaving the video resets its zoom: a magnified corner of one clip means nothing on the next.
    LaunchedEffect(player) {
        if (player == null) {
            scale.snapTo(1f)
            panX.snapTo(0f)
            panY.snapTo(0f)
            onZoomChanged(1f)
        }
    }

    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale.value * zoomChange).coerceIn(1f, 5f)
        val (mx, my) = maxPan(newScale)
        val x = if (newScale > 1f) (panX.value + panChange.x).coerceIn(-mx, mx) else 0f
        val y = if (newScale > 1f) (panY.value + panChange.y).coerceIn(-my, my) else 0f
        scope.launch {
            scale.snapTo(newScale)
            panX.snapTo(x)
            panY.snapTo(y)
        }
        onZoomChanged(newScale)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                width = it.width
                container = it
            }
            .pointerInput(canHold) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { offset -> if (offset.x < width / 2f) onDoubleTapLeft() else onDoubleTapRight() },
                    // A handler here is also what keeps the release at the end of a hold from counting as a tap.
                    onLongPress = if (canHold) { _ -> holdStart?.invoke() } else null,
                    onPress = {
                        if (canHold) {
                            tryAwaitRelease()
                            holdEnd()
                        }
                    },
                )
            }
            // Inner to the zoom, so it sees a drag first; it stands aside while zoomed, where a drag pans,
            // and when a second finger lands, which makes it a pinch.
            .pointerInput(swipeLeft, swipeRight) {
                if (!swipeLeft && !swipeRight) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val left = down.position.x < size.width / 2f
                    if (scale.value > ZOOMED || (left && !swipeLeft) || (!left && !swipeRight)) return@awaitEachGesture
                    val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                        if (currentEvent.changes.count { it.pressed } == 1) change.consume()
                    } ?: return@awaitEachGesture
                    swipeStart(left)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } != 1) break
                        val change = event.changes.firstOrNull { it.id == start.id } ?: break
                        val dy = change.positionChange().y
                        if (dy != 0f) {
                            change.consume()
                            // Up is more; a swipe the height of the picture is the whole range.
                            swipeBy(-dy / size.height)
                        }
                    }
                    swipeEnd()
                }
            }
            .transformable(state = transform, canPan = { scale.value > ZOOMED })
            .pointerInput(Unit) {
                awaitEachGesture {
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(awaitFirstDown(requireUnconsumed = false))
                    while (true) {
                        val pressed = awaitPointerEvent().changes.filter { it.pressed }
                        if (pressed.size == 1) tracker.addPointerInputChange(pressed[0]) else if (pressed.size > 1) tracker.resetTracking()
                        if (pressed.isEmpty()) break
                    }
                    if (scale.value > ZOOMED) {
                        val velocity = tracker.calculateVelocity()
                        scope.launch {
                            val (mx, my) = maxPan(scale.value)
                            launch {
                                panX.animateDecay(velocity.x.coerceIn(-5000f, 5000f), decay)
                                panX.snapTo(panX.value.coerceIn(-mx, mx))
                            }
                            launch {
                                panY.animateDecay(velocity.y.coerceIn(-5000f, 5000f), decay)
                                panY.snapTo(panY.value.coerceIn(-my, my))
                            }
                        }
                    }
                }
            }
            .graphicsLayer {
                val s = scale.value
                val (mx, my) = maxPan(s)
                scaleX = s
                scaleY = s
                translationX = panX.value.coerceIn(-mx, mx)
                translationY = panY.value.coerceIn(-my, my)
            },
    ) {
        if (player != null) {
            AndroidView(
                // No parent to inflate against: AndroidView adds the view itself.
                factory = { context -> inflateSurface(context) },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

/** Seek bar, time, and what holds for the next seconds of the video: speed, repeat, lock, fullscreen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoBottomBar(
    fraction: Float,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    onSeekDone: () -> Unit,
    isFullscreen: Boolean,
    onExitFullscreen: () -> Unit,
    speed: String?,
    looping: Boolean,
    onLock: (() -> Unit)?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            // Taps that miss the slider must not fall through to the video and hide the bars.
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 8.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onLock != null) {
                IconButton(onClick = onLock, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Lock, stringResource(R.string.viewer_lock), tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
            Slider(
                value = fraction,
                onValueChange = onSeek,
                onValueChangeFinished = onSeekDone,
                thumb = {
                    Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                        Spacer(Modifier.size(12.dp).shadow(4.dp, CircleShape).background(Color.White, CircleShape))
                    }
                },
                track = {
                    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                        Spacer(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
                        Spacer(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).clip(CircleShape).background(Color.White))
                    }
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text("${formatTime(positionMs)} / ${formatTime(durationMs)}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            // A speed other than normal and a repeat are easy to forget having set: they stay in sight.
            if (speed != null) {
                Spacer(Modifier.width(8.dp))
                Text(speed, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
            }
            if (looping) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.RepeatOne, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
            }
            if (isFullscreen) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onExitFullscreen, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.FullscreenExit, stringResource(R.string.viewer_exit_fullscreen), tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
internal fun GlyphButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

@Composable
internal fun SwipeLevel(kind: SwipeKind, level: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            when {
                kind == SwipeKind.BRIGHTNESS -> Icons.Outlined.BrightnessMedium
                level <= 0f -> Icons.AutoMirrored.Outlined.VolumeOff
                else -> Icons.AutoMirrored.Outlined.VolumeUp
            },
            null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        LinearProgressIndicator(progress = { level }, color = Color.White, trackColor = Color.White.copy(alpha = 0.3f), modifier = Modifier.width(120.dp))
        Text("${Math.round(level * 100)}%", color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@SuppressLint("InflateParams")
private fun inflateSurface(context: Context): PlayerView =
    (LayoutInflater.from(context).inflate(R.layout.video_surface, null) as PlayerView).apply { useController = false }

internal const val ZOOMED = 1.05f
