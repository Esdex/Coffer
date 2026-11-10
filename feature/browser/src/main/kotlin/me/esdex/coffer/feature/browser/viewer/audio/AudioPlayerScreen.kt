package me.esdex.coffer.feature.browser.viewer.audio

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.feature.browser.R
import me.esdex.coffer.feature.browser.viewer.ViewerViewModel
import me.esdex.coffer.feature.browser.viewer.formatTime

/**
 * The tracks of a folder, one at a time, after Arcanum's audio player (pre-release item 5): the cover
 * and its colour, the tags, a seek bar, previous and next, shuffle and repeat. It plays while Coffer is
 * on screen and pauses when Coffer leaves it.
 */
@Composable
fun AudioPlayerScreen(
    tracks: List<ViewerViewModel.Page>,
    startIndex: Int,
    onClose: () -> Unit,
    onSave: (ViewerViewModel.Page) -> Unit,
    onOpenWith: (ViewerViewModel.Page) -> Unit,
    viewModel: AudioViewModel = hiltViewModel(),
) {
    LaunchedEffect(tracks) { viewModel.show(tracks, startIndex) }
    // Left in any way, the vault locking included: the sound stops and the file closes.
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    LaunchedEffect(locked) { if (locked) onClose() }
    BackHandler(onBack = onClose)

    // Playing on in the background is a step of its own; until then Coffer out of sight means paused.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val player = viewModel.player()
    var positionMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.index) { positionMs = 0L }
    // Once a frame rather than on a timer, so the bar moves smoothly; only while it plays.
    LaunchedEffect(state.isPlaying, scrubbing) {
        positionMs = player.currentPosition
        while (state.isPlaying && !scrubbing) {
            withFrameNanos { }
            positionMs = player.currentPosition
        }
    }

    val tint by animateColorAsState(state.tags?.color?.let { Color(it).copy(alpha = 0.35f) } ?: Color.Transparent, tween(700), label = "cover colour")
    var menuOpen by remember { mutableStateOf(false) }
    val track = state.track

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint, MaterialTheme.colorScheme.background), endY = maxHeight.value * 2f)))
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_cd_back)) }
                    Text(
                        stringResource(R.string.audio_now_playing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_cd_more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_export)) },
                                leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) },
                                onClick = { menuOpen = false; track?.let(onSave) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.viewer_open_with)) },
                                leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                                onClick = { menuOpen = false; viewModel.pause(); track?.let(onOpenWith) },
                            )
                        }
                    }
                }

                // A phone held sideways has no room for a square cover and the controls under it.
                Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    AnimatedContent(state.tags, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(280)) }, label = "track", modifier = Modifier.weight(1f, fill = false)) { tags ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(tags, Modifier.weight(1f, fill = false).widthIn(max = 420.dp).aspectRatio(1f))
                            Spacer(Modifier.height(24.dp))
                            Text(
                                tags?.title ?: track?.name.orEmpty(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(tags?.artist, tags?.album).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
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
                    Slider(
                        value = fraction,
                        onValueChange = {
                            scrubbing = true
                            scrubFraction = it
                        },
                        onValueChangeFinished = {
                            val to = (scrubFraction * duration).toLong()
                            viewModel.seekTo(to)
                            positionMs = to
                            scrubbing = false
                        },
                        enabled = duration > 0L,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val shown = if (scrubbing) (scrubFraction * duration).toLong() else positionMs
                        Text(formatTime(shown), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
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
                        FilledIconButton(
                            onClick = viewModel::togglePlay,
                            enabled = !state.failed,
                            modifier = Modifier.size(64.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            if (state.buffering && !state.isPlaying) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                            } else {
                                Icon(
                                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    stringResource(if (state.isPlaying) R.string.viewer_pause else R.string.viewer_play),
                                    modifier = Modifier.size(36.dp),
                                )
                            }
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

@Composable
private fun ModeChip(selected: Boolean, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
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
private fun Cover(tags: AudioViewModel.Tags?, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.shadow(16.dp, shape, clip = false).clip(shape), contentAlignment = Alignment.Center) {
        val cover = tags?.cover
        if (cover != null) {
            Image(cover.asImageBitmap(), stringResource(R.string.audio_cd_cover), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier.fillMaxSize().background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, Color.Black))),
                contentAlignment = Alignment.Center,
            ) {
                // Nothing while the tags are read, so a cover arriving does not replace a note.
                if (tags != null) Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(72.dp))
            }
        }
    }
}
