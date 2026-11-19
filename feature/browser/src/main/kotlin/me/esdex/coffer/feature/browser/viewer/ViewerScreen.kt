package me.esdex.coffer.feature.browser.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.esdex.coffer.core.ui.components.AppMenu
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion
import me.esdex.coffer.core.ui.theme.tabular
import me.esdex.coffer.feature.browser.R

/**
 * Pictures and videos of a folder, one page each, read in place from Saved Messages (pre-release
 * item 5), after Arcanum's MediaViewerScreen: pinch and double tap to zoom a picture, swipe to the
 * next, tap to hide the bars; a video plays as soon as it is on screen, with Arcanum's gestures and
 * controls, each extra behind its own switch in Settings.
 */
@OptIn(UnstableApi::class)
@Composable
fun ViewerScreen(
    pages: List<ViewerViewModel.Page>,
    startIndex: Int,
    onClose: () -> Unit,
    onSave: (ViewerViewModel.Page) -> Unit,
    onOpenWith: (ViewerViewModel.Page) -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    LaunchedEffect(pages) { viewModel.show(pages, startIndex) }
    // Left in any way (back, the vault locking): the player must not play on unseen.
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val pictures by viewModel.pictures.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    val prefs by viewModel.playerPrefs.collectAsStateWithLifecycle()
    val videoFailed by viewModel.videoFailed.collectAsStateWithLifecycle()
    // The vault locked under the viewer: nothing it shows may stay on screen.
    LaunchedEffect(locked) { if (locked) onClose() }

    val pagerState = rememberPagerState(initialPage = startIndex) { pages.size }
    val current = pages.getOrNull(pagerState.currentPage)
    val isVideo = current?.isVideo == true
    val player = if (isVideo) viewModel.player() else null

    var showBars by remember { mutableStateOf(true) }
    var barsPoke by remember { mutableIntStateOf(0) }
    var zoomed by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }

    // ── Video state ──────────────────────────────────────────────────────
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }
    var videoAspect by remember { mutableFloatStateOf(0f) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var userTapped by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var looping by remember { mutableStateOf(false) }
    var holdingFast by remember { mutableStateOf(false) }
    var holdResumed by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var unlockPoke by remember { mutableIntStateOf(0) }
    var swipeKind by remember { mutableStateOf(SwipeKind.VOLUME) }
    var swipeLevel by remember { mutableFloatStateOf(0f) }
    var swipeVisible by remember { mutableStateOf(false) }
    var swipeActive by remember { mutableStateOf(false) }
    var swipeEndPoke by remember { mutableIntStateOf(0) }
    var seekBackPoke by remember { mutableIntStateOf(0) }
    var seekOnPoke by remember { mutableIntStateOf(0) }
    // Taps in a row add up, as the chip says: -10s, -20s, -30s.
    var seekBackSeconds by remember { mutableIntStateOf(0) }
    var seekOnSeconds by remember { mutableIntStateOf(0) }
    var showSeekBack by remember { mutableStateOf(false) }
    var showSeekOn by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    val isWide = videoAspect > 1.05f

    BackHandler {
        when {
            isLocked -> {
                showUnlock = true
                unlockPoke++
            }
            isFullscreen -> isFullscreen = false
            else -> onClose()
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect(viewModel::focus)
    }

    // The page on screen decides what the player does: a video plays, anything else stops it.
    LaunchedEffect(current) {
        playbackError = null
        userTapped = false
        positionMs = 0L
        durationMs = 0L
        bufferedMs = 0L
        isPlaying = false
        scrubbing = false
        videoAspect = 0f
        if (current?.isVideo == true) {
            // A video's controls live in the bars: landing on one with them hidden would leave no way to play it.
            showBars = true
            barsPoke++
            viewModel.play(current)
        } else {
            viewModel.stopVideo()
        }
    }

    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                p.duration.takeIf { it > 0L }?.let { durationMs = it }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // Anamorphic video is only wide once its pixel ratio is applied.
                val w = videoSize.width * videoSize.pixelWidthHeightRatio
                val h = videoSize.height.toFloat()
                videoAspect = if (w > 0f && h > 0f) w / h else 0f
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                playbackError = error.errorCodeName
            }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }

    // How far the file is loaded, for the seek bar: the player goes on loading while paused, so not only while playing.
    LaunchedEffect(player, current) {
        while (player != null) {
            bufferedMs = player.bufferedPosition
            delay(BUFFERED_POLL_MS)
        }
    }

    // Once a frame rather than on a timer, so the seek bar moves smoothly.
    LaunchedEffect(isPlaying, scrubbing, player) {
        while (isPlaying && !scrubbing) {
            withFrameNanos { }
            positionMs = player?.currentPosition ?: 0L
        }
    }

    val effectiveSpeed = when {
        holdingFast -> 2f
        prefs.speedMenu -> playbackSpeed
        else -> 1f
    }
    val effectiveLoop = prefs.loop && looping
    LaunchedEffect(player, effectiveSpeed, current) { player?.setPlaybackSpeed(effectiveSpeed) }
    LaunchedEffect(player, effectiveLoop, current) { player?.repeatMode = if (effectiveLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

    val context = LocalContext.current
    val activity = context.activity()
    // Fullscreen is turning the window: the player runs on, so playback carries on from the same moment.
    DisposableEffect(isFullscreen, activity) {
        activity?.requestedOrientation = if (isFullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    LaunchedEffect(isWide, isVideo) { if (!isWide || !isVideo) isFullscreen = false }
    // A long video must not be cut off by the screen timeout; only while it actually plays.
    DisposableEffect(isPlaying) {
        val window = activity?.window
        if (isPlaying) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // Brightness is the window's only, and handed back to the system when the viewer goes.
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { w ->
                if (w.attributes.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                    w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                }
            }
        }
    }
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val onSwipeStart: (Boolean) -> Unit = start@{ left ->
        // A finger already holding for 2x that then slides is still holding, not swiping.
        swipeActive = !holdingFast
        if (!swipeActive) return@start
        if (left) {
            val now = activity?.window?.attributes?.screenBrightness ?: -1f
            swipeKind = SwipeKind.BRIGHTNESS
            swipeLevel = if (now >= 0f) {
                now
            } else {
                runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f).coerceIn(0f, 1f)
            }
        } else {
            val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            swipeKind = SwipeKind.VOLUME
            swipeLevel = if (max > 0) audio!!.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else 0f
        }
        swipeVisible = true
    }
    val onSwipe: (Float) -> Unit = swipe@{ fraction ->
        if (!swipeActive) return@swipe
        swipeLevel = (swipeLevel + fraction * SWIPE_GAIN).coerceIn(0f, 1f)
        when (swipeKind) {
            // Never quite 0: a window at zero brightness reads as a phone that died.
            SwipeKind.BRIGHTNESS -> activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = swipeLevel.coerceAtLeast(0.01f) } }
            SwipeKind.VOLUME -> audio?.let { am ->
                val index = Math.round(swipeLevel * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                if (index != am.getStreamVolume(AudioManager.STREAM_MUSIC)) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
            }
        }
    }

    SystemBarsFollow(showBars)
    // Bars go by themselves after a while; a tap brings them back. An open menu keeps them up.
    LaunchedEffect(showBars, barsPoke, menuOpen, speedMenuOpen) {
        if (showBars && !menuOpen && !speedMenuOpen) {
            delay(BARS_MILLIS)
            showBars = false
        }
    }
    LaunchedEffect(unlockPoke) { if (showUnlock) { delay(3_000); showUnlock = false } }
    LaunchedEffect(swipeEndPoke) { if (swipeEndPoke > 0) { delay(600); swipeVisible = false } }
    LaunchedEffect(seekBackPoke) { if (seekBackPoke > 0) { showSeekBack = true; delay(800); showSeekBack = false } }
    LaunchedEffect(seekOnPoke) { if (seekOnPoke > 0) { showSeekOn = true; delay(800); showSeekOn = false } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, userScrollEnabled = !zoomed && !isLocked, modifier = Modifier.fillMaxSize()) { page ->
            val item = pages.getOrNull(page) ?: return@HorizontalPager
            val isCurrent = page == pagerState.currentPage
            if (item.isVideo) {
                VideoSurface(
                    player = if (isCurrent) player else null,
                    videoAspect = videoAspect,
                    onTap = {
                        userTapped = true
                        showBars = !showBars
                    },
                    // A double tap seeks; if the bars are up their countdown starts over, if not they stay down.
                    onDoubleTapLeft = {
                        player?.let { it.seekTo((it.currentPosition - SEEK_MS).coerceAtLeast(0L)) }
                        seekBackSeconds = (if (showSeekBack) seekBackSeconds else 0) + (SEEK_MS / 1000).toInt()
                        seekBackPoke++
                        if (showBars) barsPoke++
                    },
                    onDoubleTapRight = {
                        player?.let { it.seekTo(it.currentPosition + SEEK_MS) }
                        seekOnSeconds = (if (showSeekOn) seekOnSeconds else 0) + (SEEK_MS / 1000).toInt()
                        seekOnPoke++
                        if (showBars) barsPoke++
                    },
                    onZoomChanged = { zoomed = it > ZOOMED },
                    swipeLeft = prefs.swipeBrightness,
                    swipeRight = prefs.swipeVolume,
                    onSwipeStart = onSwipeStart,
                    onSwipe = onSwipe,
                    onSwipeEnd = {
                        if (swipeActive) {
                            swipeActive = false
                            swipeEndPoke++
                        }
                    },
                    onHoldStart = if (!prefs.holdForSpeed) null else {
                        {
                            holdResumed = !isPlaying
                            holdingFast = true
                            if (holdResumed) player?.play()
                        }
                    },
                    onHoldEnd = {
                        if (holdingFast) {
                            holdingFast = false
                            if (holdResumed) player?.pause()
                            holdResumed = false
                        }
                    },
                )
            } else {
                ZoomablePicture(
                    picture = pictures[item.fileId] ?: ViewerViewModel.Picture.Loading(),
                    preview = remember(item.fileId) { viewModel.preview(item.fileId) },
                    isCurrent = isCurrent,
                    onTap = { showBars = !showBars },
                    onZoomed = { zoomed = it },
                )
            }
        }

        if (isVideo) {
            // Laid out against the picture, not the window: a wide video is a strip with black around it.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val boxAspect = maxWidth / maxHeight
                val aspect = if (videoAspect > 0f) videoAspect else boxAspect
                val w = if (aspect >= boxAspect) maxWidth else maxHeight * aspect
                val h = if (aspect >= boxAspect) maxWidth / aspect else maxHeight
                Box(Modifier.size(w, h).align(Alignment.Center)) {
                    AnimatedVisibility(showSeekBack, enter = fadeIn(Motion.quick()), exit = fadeOut(Motion.standard()), modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                        Chip(stringResource(R.string.viewer_seek_back, seekBackSeconds), Icons.Filled.FastRewind, iconFirst = true)
                    }
                    AnimatedVisibility(showSeekOn, enter = fadeIn(Motion.quick()), exit = fadeOut(Motion.standard()), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
                        Chip(stringResource(R.string.viewer_seek_on, seekOnSeconds), Icons.Filled.FastForward, iconFirst = false)
                    }
                    AnimatedVisibility(isWide && !isFullscreen && showBars, enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick()), modifier = Modifier.align(Alignment.BottomEnd)) {
                        Box(Modifier.padding(8.dp)) {
                            GlyphButton(Icons.Outlined.Fullscreen, stringResource(R.string.viewer_fullscreen)) {
                                isFullscreen = true
                                showBars = true
                                barsPoke++
                            }
                        }
                    }
                }
            }

            // Play and pause in the middle, once the video has been tapped.
            AnimatedVisibility(showBars && userTapped, enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick()), modifier = Modifier.align(Alignment.Center)) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(OverlayScrim).clickable { if (isPlaying) player?.pause() else player?.play() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(ViewerSpinner))
                    } else {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (isPlaying) R.string.viewer_pause else R.string.viewer_play),
                            tint = Color.White,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
            if (isBuffering && !userTapped) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(ViewerSpinner))
            }

            // An error says so instead of an endless black screen.
            if (playbackError != null || videoFailed) {
                ViewerFailure(
                                        title = stringResource(R.string.viewer_video_failed),
                    subtitle = null,
                    onDark = true,
                    detail = playbackError,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            AnimatedVisibility(holdingFast, enter = fadeIn(Motion.quick()), exit = fadeOut(Motion.standard()), modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp)) {
                Chip(stringResource(R.string.viewer_hold_speed), Icons.Filled.FastForward, iconFirst = false)
            }
            AnimatedVisibility(swipeVisible, enter = fadeIn(Motion.quick()), exit = fadeOut(Motion.standard()), modifier = Modifier.align(Alignment.Center)) {
                SwipeLevel(swipeKind, swipeLevel)
            }

            // Locked: nothing under it answers a touch; a touch shows the way out.
            if (isLocked) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            showUnlock = true
                            unlockPoke++
                        }
                    },
                )
            }
            AnimatedVisibility(isLocked && showUnlock, enter = fadeIn(Motion.quick()), exit = fadeOut(Motion.standard()), modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clip(Corners.pill).background(OverlayScrim).clickable {
                        isLocked = false
                        showUnlock = false
                        showBars = true
                        barsPoke++
                    }.padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Outlined.LockOpen, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.viewer_unlock), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        AnimatedVisibility(showBars && !isLocked, enter = fadeIn(Motion.standard()) + slideInVertically(Motion.standard()) { -it }, exit = fadeOut(Motion.quick()) + slideOutVertically(Motion.quick()) { -it }, modifier = Modifier.align(Alignment.TopCenter)) {
            TopBar(
                title = current?.name.orEmpty(),
                // Where in the folder: only when there is more than one to go through.
                position = if (pages.size > 1) stringResource(R.string.viewer_position, pagerState.currentPage + 1, pages.size) else null,
                onBack = onClose,
                menuOpen = menuOpen,
                onMenu = { menuOpen = it },
                videoItems = if (!isVideo) null else VideoMenu(
                    speed = playbackSpeed.takeIf { prefs.speedMenu },
                    looping = looping.takeIf { prefs.loop },
                    onSpeed = { speedMenuOpen = true },
                    onLoop = { looping = !looping },
                ),
                speedMenuOpen = speedMenuOpen,
                onSpeedMenu = { speedMenuOpen = it },
                currentSpeed = playbackSpeed,
                onPickSpeed = { playbackSpeed = it },
                onSave = { current?.let(onSave) },
                onOpenWith = { current?.let(onOpenWith) },
            )
        }

        if (isVideo) {
            AnimatedVisibility(showBars && !isLocked, enter = fadeIn(Motion.standard()) + slideInVertically(Motion.standard()) { it }, exit = fadeOut(Motion.quick()) + slideOutVertically(Motion.quick()) { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
                val shownMs = if (scrubbing) scrubMs else positionMs
                VideoBottomBar(
                    fraction = if (durationMs > 0L) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    bufferedFraction = if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    positionMs = shownMs,
                    durationMs = durationMs,
                    onSeek = { f ->
                        scrubbing = true
                        scrubMs = (f * durationMs).toLong()
                        player?.seekTo(scrubMs)
                        barsPoke++
                    },
                    onSeekDone = {
                        player?.seekTo(scrubMs)
                        scrubbing = false
                        barsPoke++
                    },
                    isFullscreen = isFullscreen,
                    onExitFullscreen = {
                        isFullscreen = false
                        barsPoke++
                    },
                    speed = effectiveSpeed.takeIf { it != 1f && !holdingFast }?.let(::speedLabel),
                    looping = effectiveLoop,
                    onLock = if (!prefs.lockButton) null else {
                        {
                            isLocked = true
                            showBars = false
                            showUnlock = true
                            unlockPoke++
                        }
                    },
                )
            }
        }
    }
}

/** What the top bar's menu offers for a video, beyond saving and opening elsewhere; null for a switch turned off. */
private class VideoMenu(val speed: Float?, val looping: Boolean?, val onSpeed: () -> Unit, val onLoop: () -> Unit)

@Composable
private fun Chip(text: String, icon: ImageVector, iconFirst: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.background(OverlayScrim, Corners.pill).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (iconFirst) Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        if (!iconFirst) Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TopBar(
    title: String,
    position: String?,
    onBack: () -> Unit,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    videoItems: VideoMenu?,
    speedMenuOpen: Boolean,
    onSpeedMenu: (Boolean) -> Unit,
    currentSpeed: Float,
    onPickSpeed: (Float) -> Unit,
    onSave: () -> Unit,
    onOpenWith: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(OverlayEdge, Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_cd_back), tint = Color.White) }
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (position != null) {
            Text(position, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge.tabular(), modifier = Modifier.padding(start = 8.dp))
        }
        Box {
            IconButton(onClick = { onMenu(true) }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_cd_more), tint = Color.White) }
            AppMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
                videoItems?.speed?.let { speed ->
                    ViewerMenuItem(Icons.Outlined.Speed, stringResource(R.string.viewer_speed), trailing = { Text(speedLabel(speed)) }) {
                        onMenu(false)
                        videoItems.onSpeed()
                    }
                }
                videoItems?.looping?.let { looping ->
                    ViewerMenuItem(Icons.Outlined.RepeatOne, stringResource(R.string.viewer_loop), trailing = { if (looping) Icon(Icons.Outlined.Check, null) }) {
                        onMenu(false)
                        videoItems.onLoop()
                    }
                }
                FileMenuItems(close = { onMenu(false) }, onSave = onSave, onOpenWith = onOpenWith)
            }
            // Opened from the item above, in the same place, so the choice is made where it was asked for.
            AppMenu(expanded = speedMenuOpen, onDismissRequest = { onSpeedMenu(false) }) {
                PLAYBACK_SPEEDS.forEach { speed ->
                    ViewerMenuItem(
                        icon = null,
                        label = if (speed == 1f) stringResource(R.string.viewer_speed_normal) else speedLabel(speed),
                        trailing = { if (speed == currentSpeed) Icon(Icons.Outlined.Check, null) },
                    ) {
                        onPickSpeed(speed)
                        onSpeedMenu(false)
                    }
                }
            }
        }
    }
}

/** The status bar goes with the viewer's own bars and comes back when the viewer does. */
@Composable
private fun SystemBarsFollow(showBars: Boolean) {
    val view = LocalView.current
    val window = LocalContext.current.activity()?.window ?: return
    LaunchedEffect(showBars) {
        val controller = WindowCompat.getInsetsController(window, view)
        // The status bar only: hiding the navigation area too hands the screen edges to the pager,
        // and the back gesture with them (Arcanum found this out).
        if (showBars) controller.show(WindowInsetsCompat.Type.statusBars()) else controller.hide(WindowInsetsCompat.Type.statusBars())
    }
    DisposableEffect(Unit) {
        onDispose { WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.statusBars()) }
    }
}

/**
 * One picture, after Arcanum's ZoomableImagePage: pinch up to 5x, double tap to 2.5x and back, pan
 * a zoomed picture with a fling, never past its edges.
 */
@Composable
private fun ZoomablePicture(picture: ViewerViewModel.Picture, preview: ImageBitmap?, isCurrent: Boolean, onTap: () -> Unit, onZoomed: (Boolean) -> Unit) {
    val drawable = (picture as? ViewerViewModel.Picture.Shown)?.drawable
    // Only the page on screen animates; one beside it would go on decoding frames unseen.
    if (drawable != null) {
        DisposableEffect(drawable, isCurrent) {
            if (isCurrent) Pictures.play(drawable) else Pictures.stop(drawable)
            onDispose { Pictures.stop(drawable) }
        }
    }
    val scale = remember { Animatable(1f) }
    val panX = remember { Animatable(0f) }
    val panY = remember { Animatable(0f) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()

    // Where the picture really is inside the page: ContentScale.Fit may leave bars around it.
    val rendered = remember(drawable, container) {
        val w = drawable?.intrinsicWidth ?: 0
        val h = drawable?.intrinsicHeight ?: 0
        if (w <= 0 || h <= 0 || container == IntSize.Zero) {
            container
        } else {
            val fit = minOf(container.width.toFloat() / w, container.height.toFloat() / h)
            IntSize((w * fit).toInt(), (h * fit).toInt())
        }
    }
    fun maxPan(s: Float) = maxOf(0f, (rendered.width * s - container.width) / 2f) to maxOf(0f, (rendered.height * s - container.height) / 2f)

    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale.value * zoomChange).coerceIn(1f, MEDIA_MAX_ZOOM)
        val (mx, my) = maxPan(newScale)
        val x = if (newScale > 1f) (panX.value + panChange.x).coerceIn(-mx, mx) else 0f
        val y = if (newScale > 1f) (panY.value + panChange.y).coerceIn(-my, my) else 0f
        scope.launch {
            scale.snapTo(newScale)
            panX.snapTo(x)
            panY.snapTo(y)
        }
        onZoomed(newScale > ZOOMED)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { container = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        scope.launch {
                            val ease = Motion.standard<Float>()
                            if (scale.value > ZOOMED) {
                                onZoomed(false)
                                launch { scale.animateTo(1f, ease) }
                                launch { panX.animateTo(0f, ease) }
                                launch { panY.animateTo(0f, ease) }
                            } else {
                                onZoomed(true)
                                scale.animateTo(DOUBLE_TAP_ZOOM, ease)
                            }
                        }
                    },
                )
            }
            .transformable(state = transform, canPan = { scale.value > ZOOMED })
            // A fling when the finger lifts off a zoomed picture.
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
                                panX.animateDecay(velocity.x.coerceIn(-MEDIA_MAX_FLING, MEDIA_MAX_FLING), decay)
                                panX.snapTo(panX.value.coerceIn(-mx, mx))
                            }
                            launch {
                                panY.animateDecay(velocity.y.coerceIn(-MEDIA_MAX_FLING, MEDIA_MAX_FLING), decay)
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
        // The picture fades in over what held its place; one already decoded is there at once.
        val reveal by animateFloatAsState(if (picture is ViewerViewModel.Picture.Shown) 1f else 0f, Motion.standard(), label = "picture")
        if (reveal < 1f && picture !is ViewerViewModel.Picture.Failed) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - reveal }, contentAlignment = Alignment.Center) {
                // The list's preview is the middle square of the picture: blurred, it stands in without
                // pretending to be the picture itself.
                if (preview != null) {
                    Image(
                        preview,
                        null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).blur(PREVIEW_BLUR).graphicsLayer { alpha = PREVIEW_ALPHA },
                    )
                }
                val loading = picture as? ViewerViewModel.Picture.Loading
                FileLoading(loading?.loaded ?: 0, loading?.size ?: 0, onDark = true)
            }
        }
        when (picture) {
            is ViewerViewModel.Picture.Shown ->
                Image(rememberDrawablePainter(picture.drawable), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = reveal })
            is ViewerViewModel.Picture.Loading -> Unit
            ViewerViewModel.Picture.Failed -> ViewerFailure(
                                title = stringResource(R.string.viewer_failed),
                subtitle = null,
                onDark = true,
            )
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private const val DOUBLE_TAP_ZOOM = 2.5f
private val PREVIEW_BLUR = 16.dp
private const val PREVIEW_ALPHA = 0.6f
private const val BARS_MILLIS = 3_000L
private const val SEEK_MS = 10_000L

/** How often the seek bar asks how far the file is loaded: often enough to grow smoothly, at no cost. */
private const val BUFFERED_POLL_MS = 250L
