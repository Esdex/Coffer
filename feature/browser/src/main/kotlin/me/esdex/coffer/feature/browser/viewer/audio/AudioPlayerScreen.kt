package me.esdex.coffer.feature.browser.viewer.audio

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.ui.components.BackButton
import me.esdex.coffer.core.ui.components.FloatingCard
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion
import me.esdex.coffer.core.ui.theme.tabular
import me.esdex.coffer.feature.browser.R
import me.esdex.coffer.feature.browser.viewer.FileMenuItems
import me.esdex.coffer.feature.browser.viewer.SeekSlider
import me.esdex.coffer.feature.browser.viewer.ViewerMenuButton
import me.esdex.coffer.feature.browser.viewer.ViewerMenuItem
import me.esdex.coffer.feature.browser.viewer.ViewerViewModel
import me.esdex.coffer.feature.browser.viewer.formatTime

/**
 * The queue playing, after Arcanum's audio player (pre-release item 5): the cover and its colour, the
 * tags, a seek bar, previous and next, shuffle and repeat. The music is [AudioPlayback]'s and plays on
 * when this screen goes, the vault locking included; closing the player is what stops it.
 */
@Composable
fun AudioPlayerScreen(
    onSave: (ViewerViewModel.Page) -> Unit,
    onOpenWith: (ViewerViewModel.Page) -> Unit,
    viewModel: AudioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val waveform by viewModel.waveform.collectAsStateWithLifecycle()
    // Back folds the player away while music plays, closes it otherwise; Stop in the menu always closes.
    val onClose = viewModel::back
    BackHandler(onBack = onClose)

    // With background play turned off, Coffer out of sight means paused.
    val lifecycleOwner = LocalLifecycleOwner.current
    val background = prefs.backgroundAudio
    DisposableEffect(lifecycleOwner, background) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && !background) viewModel.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val player = viewModel.player
    var positionMs by remember { mutableLongStateOf(player.currentPosition) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    // Once a frame rather than on a timer, so the bar moves smoothly; only while it plays.
    LaunchedEffect(state.isPlaying, scrubbing, state.index) {
        positionMs = player.currentPosition
        while (state.isPlaying && !scrubbing) {
            withFrameNanos { }
            positionMs = player.currentPosition
        }
    }

    val tint by animateColorAsState(state.tags?.color?.let { Color(it).copy(alpha = 0.35f) } ?: Color.Transparent, tween(Motion.AMBIENT), label = "cover colour")
    val track = state.track
    // Back folds the player away while music plays on (see AudioViewModel.back), and its glyph says so.
    val folds = state.isPlaying || (state.buffering && !state.failed)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, MaterialTheme.colorScheme.background), endY = maxHeight.value * 2f)))
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BackButton(
                        onClick = onClose,
                        icon = if (folds) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(if (folds) R.string.audio_fold else R.string.viewer_cd_back),
                    )
                    Text(
                        if (state.tracks.size > 1) {
                            stringResource(R.string.audio_now_playing_of, state.index + 1, state.tracks.size)
                        } else {
                            stringResource(R.string.audio_now_playing)
                        },
                        style = MaterialTheme.typography.labelLarge.tabular(),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    ViewerMenuButton(tint = LocalContentColor.current) { close ->
                        FileMenuItems(
                            close,
                            onSave = { track?.let(onSave) },
                            onOpenWith = {
                                viewModel.pause()
                                track?.let(onOpenWith)
                            },
                        )
                        ViewerMenuItem(Icons.Outlined.Close, stringResource(R.string.audio_stop)) {
                            close()
                            viewModel.stop()
                        }
                    }
                }

                // A phone held sideways has no room for a square cover and the controls under it.
                Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    // The index the tags belong to: they come a moment after the track changes.
                    val tagsIndex = remember(state.tags) { state.index }
                    AnimatedContent(
                        targetState = ShownTrack(tagsIndex, state.tags),
                        contentKey = { it.tags },
                        transitionSpec = {
                            // Next comes in from the right, previous from the left; the first tags of a track only fade in.
                            val way = slideDirection(initialState.index, targetState.index, state.tracks.size)
                            (fadeIn(Motion.screen()) + slideInHorizontally(Motion.screen()) { it * way / SLIDE_PART }) togetherWith
                                (fadeOut(Motion.standard()) + slideOutHorizontally(Motion.standard()) { -it * way / SLIDE_PART })
                        },
                        label = "track",
                        modifier = Modifier.weight(1f, fill = false),
                    ) { (_, tags) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(tags, Modifier.weight(1f, fill = false).widthIn(max = 420.dp).aspectRatio(1f))
                            Spacer(Modifier.height(24.dp))
                            // Too long for the line, a name runs across it after a pause, instead of being cut.
                            Text(
                                tags?.title ?: track?.name?.let { it.substringBeforeLast('.', it) }.orEmpty(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().basicMarquee(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tags?.artist.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().basicMarquee(),
                            )
                            // The album on a line of its own, a step quieter: with the artist it cut both short.
                            tags?.album?.let { album ->
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    album,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ModeChip(state.shuffled, stringResource(R.string.audio_shuffle), Icons.Outlined.Shuffle, viewModel::toggleShuffle)
                        ModeChip(
                            state.repeat != Repeat.NONE,
                            stringResource(
                                when (state.repeat) {
                                    Repeat.NONE -> R.string.audio_repeat
                                    Repeat.ALL -> R.string.audio_repeat_all
                                    Repeat.ONE -> R.string.audio_repeat_one
                                },
                            ),
                            if (state.repeat == Repeat.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                            viewModel::cycleRepeat,
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    val duration = state.durationMs
                    val fraction = if (scrubbing) scrubFraction else if (duration > 0L) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    val scrubTo: (Float) -> Unit = {
                        scrubbing = true
                        scrubFraction = it
                    }
                    val seekToScrub = {
                        val to = (scrubFraction * duration).toLong()
                        viewModel.seekTo(to)
                        positionMs = to
                        scrubbing = false
                    }
                    if (prefs.waveform) {
                        // Only this track's: the last one's stays nowhere while the next is worked out.
                        val shown = waveform?.takeIf { it.fileId == state.track?.fileId }
                        WaveformBar(
                            bars = shown?.bars.orEmpty(),
                            complete = shown?.complete == true,
                            progress = fraction,
                            enabled = duration > 0L,
                            onScrub = scrubTo,
                            onScrubEnd = seekToScrub,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        )
                    } else {
                        SeekSlider(
                            fraction,
                            onSeek = scrubTo,
                            onSeekDone = seekToScrub,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
                            enabled = duration > 0L,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val shown = if (scrubbing) (scrubFraction * duration).toLong() else positionMs
                        val time = MaterialTheme.typography.labelSmall.tabular()
                        Text(formatTime(shown), style = time, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Text(formatTime(duration), style = time, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    }

                    if (state.failed) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.audio_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = viewModel::previous, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.audio_previous), modifier = Modifier.size(36.dp))
                        }
                        val press = remember { MutableInteractionSource() }
                        val pressed by press.collectIsPressedAsState()
                        val sink by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, Motion.quick(), label = "play press")
                        FilledIconButton(
                            onClick = viewModel::togglePlay,
                            enabled = !state.failed,
                            interactionSource = press,
                            modifier = Modifier.size(64.dp).graphicsLayer {
                                scaleX = sink
                                scaleY = sink
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            PlayGlyph(state, iconSize = 36.dp, spinnerColor = MaterialTheme.colorScheme.onPrimary)
                        }
                        IconButton(onClick = viewModel::next, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Rounded.SkipNext, stringResource(R.string.audio_next), modifier = Modifier.size(36.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** What the track part of the player shows: the tags, and which track of the queue they are. */
private data class ShownTrack(val index: Int, val tags: AudioViewModel.Tags?)

/**
 * Which way a track change slides: 1 for the next, -1 for the one before, 0 for none. Over the end of
 * the queue too, as repeat all goes round; when it could be either, as with two tracks, it is the next.
 */
internal fun slideDirection(from: Int, to: Int, count: Int): Int = when {
    from == to || count <= 0 -> 0
    to == (from + 1) % count -> 1
    to == (from - 1 + count) % count -> -1
    to > from -> 1
    else -> -1
}

/** Play, pause, or a spinner while it waits for the music, one fading into the next. */
@Composable
private fun PlayGlyph(state: AudioViewModel.State, iconSize: Dp, spinnerColor: Color) {
    val glyph = when {
        state.buffering && !state.isPlaying -> null
        state.isPlaying -> true
        else -> false
    }
    AnimatedContent(
        glyph,
        transitionSpec = { (fadeIn(Motion.quick()) + scaleIn(Motion.quick(), initialScale = 0.7f)) togetherWith fadeOut(Motion.quick()) },
        label = "play glyph",
    ) { playing ->
        when (playing) {
            null -> CircularProgressIndicator(color = spinnerColor, strokeWidth = 2.5.dp, modifier = Modifier.size(iconSize * 0.75f))
            true -> Icon(Icons.Rounded.Pause, stringResource(R.string.viewer_pause), modifier = Modifier.size(iconSize))
            false -> Icon(Icons.Rounded.PlayArrow, stringResource(R.string.viewer_play), modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
private fun ModeChip(selected: Boolean, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        // "Repeat" to "Repeat all" changes its width: grown into, so the row beside it does not jump.
        modifier = Modifier.animateContentSize(Motion.standard()),
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/** The cover, or while there is none a gradient with a note, as in Arcanum. */
@Composable
private fun Cover(
    tags: AudioViewModel.Tags?,
    modifier: Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(Corners.Large),
    elevation: androidx.compose.ui.unit.Dp = 16.dp,
    noteSize: androidx.compose.ui.unit.Dp = 72.dp,
) {
    Box(modifier.shadow(elevation, shape, clip = false).clip(shape), contentAlignment = Alignment.Center) {
        // The cover fades in over the gradient when it is read, rather than replacing it at once.
        Crossfade(tags?.cover, animationSpec = Motion.standard(), label = "cover") { cover ->
            if (cover != null) {
                Image(cover.asImageBitmap(), stringResource(R.string.audio_cd_cover), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(
                    Modifier.fillMaxSize().background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, Color.Black))),
                    contentAlignment = Alignment.Center,
                ) {
                    // Nothing while the tags are read, so a cover arriving does not replace a note.
                    if (tags != null) Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(noteSize))
                }
            }
        }
    }
}

/**
 * The player folded away: a card floating over the files while the music plays on, with the track, how
 * far it is, play and pause, and a way to stop. A tap opens the player again.
 */
@Composable
fun MiniPlayer(viewModel: AudioViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player = viewModel.player
    var positionMs by remember { mutableLongStateOf(player.currentPosition) }
    LaunchedEffect(state.isPlaying, state.index) {
        positionMs = player.currentPosition
        while (state.isPlaying) {
            withFrameNanos { }
            positionMs = player.currentPosition
        }
    }
    val fraction = if (state.durationMs > 0L) (positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    // In pure black the card is the same frosted glass as the bar at the top, the files blurred under it.
    FloatingCard {
        Box(Modifier.clickable(onClick = viewModel::expand)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedContent(
                    state.tags,
                    transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.quick()) },
                    label = "mini track",
                    modifier = Modifier.weight(1f),
                ) { tags ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Cover(tags, Modifier.size(44.dp), shape = RoundedCornerShape(Corners.Tile), elevation = 0.dp, noteSize = 22.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                tags?.title ?: state.track?.name?.let { it.substringBeforeLast('.', it) }.orEmpty(),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            tags?.artist?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                IconButton(onClick = viewModel::togglePlay) { PlayGlyph(state, iconSize = 24.dp, spinnerColor = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = viewModel::stop) { Icon(Icons.Outlined.Close, stringResource(R.string.audio_stop)) }
            }
            // How far the track is, along the top edge, without opening the player.
            Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(MINI_PROGRESS).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
            Box(Modifier.align(Alignment.TopStart).fillMaxWidth(fraction).height(MINI_PROGRESS).background(MaterialTheme.colorScheme.primary))
        }
    }
}

// How much of the width a track change slides by: enough to show the way, not a whole page.
private const val SLIDE_PART = 4
private const val PRESSED_SCALE = 0.92f
private val MINI_PROGRESS = 2.dp
