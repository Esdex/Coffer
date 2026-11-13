package me.esdex.coffer.feature.browser.viewer

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion
import me.esdex.coffer.core.ui.theme.tabular
import me.esdex.coffer.feature.browser.R
import kotlin.math.roundToInt

/**
 * A PDF, read in place from Saved Messages (pre-release item 5), after Arcanum's PdfViewerScreen.
 * The pages are a plain vertical list, each drawn to the width of the screen as it comes into view
 * and let go again when memory gets tight. A pinch scales what is drawn, and past a point the page
 * under the finger is drawn again wider so the text stays sharp; a drag while zoomed moves about the
 * page and goes on scrolling the document.
 */
@Composable
fun PdfViewerScreen(
    page: ViewerViewModel.Page,
    onClose: () -> Unit,
    onSave: (ViewerViewModel.Page) -> Unit,
    onOpenWith: (ViewerViewModel.Page) -> Unit,
    viewModel: PdfViewModel = hiltViewModel(),
) {
    LaunchedEffect(page.fileId) { viewModel.open(page.fileId) }
    // Left in any way, the vault locking included: the document and its file are let go.
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    LaunchedEffect(locked) { if (locked) onClose() }
    BackHandler(onBack = onClose)

    val listState = rememberLazyListState()
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    // Zoom only. Moving about a zoomed page is a real horizontal scroll below rather than a moved
    // layer: a moved layer stops dead where the finger lifts, a scroll flings on like the list does.
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        scale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
    }
    val sideways = rememberScrollState()
    val scope = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    LaunchedEffect(scale) { if (scale <= 1.01f) sideways.scrollTo(0) }
    var zooming: Job? by remember { mutableStateOf(null) }

    // A double tap glides to the new size and keeps what was under the finger under it, as a pinch does,
    // instead of jumping and growing out of the top left corner.
    fun zoomAround(target: Float, at: Offset) {
        zooming?.cancel()
        zooming = scope.launch {
            // Where the sideways scroll should be: the real one cannot go past the room the last frame
            // laid out, which grows a frame behind the scale, so it catches up at the end.
            var wanted = sideways.value.toFloat()
            Animatable(scale).animateTo(target, Motion.standard()) {
                val shift = zoomShift(at, wanted, scale, value)
                scale = value
                wanted += shift.x
                listState.dispatchRawDelta(shift.y)
                sideways.dispatchRawDelta(wanted - sideways.value)
            }
            withFrameNanos { }
            sideways.scrollTo(wanted.roundToInt().coerceIn(0, sideways.maxValue))
        }
    }

    // Sharper pages while zoomed, on two conditions Arcanum learned on a device: not during the
    // gesture, and only for the page in focus. A page half again the screen's width is fifteen
    // megabytes; drawing one for each page in view in the middle of a pinch made panning stutter.
    val zoomedIn by remember { derivedStateOf { scale > ZOOM_FOR_DETAIL } }
    val transforming = transformState.isTransformInProgress
    var detailWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(transforming, zoomedIn, viewportWidth) {
        if (!transforming) detailWidth = if (zoomedIn && viewportWidth > 0) minOf((viewportWidth * DETAIL_FACTOR).roundToInt(), MAX_RENDER_WIDTH) else 0
    }
    val focusedPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ViewerTopBar(
                title = page.name,
                onBack = onClose,
                actions = {
                    if (state.pageCount > 0) {
                        Text(
                            stringResource(R.string.viewer_position, focusedPage + 1, state.pageCount),
                            style = MaterialTheme.typography.labelLarge.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
            ) { close -> FileMenuItems(close, onSave = { onSave(page) }, onOpenWith = { onOpenWith(page) }) }

            Box(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .clipToBounds()
                    .onSizeChanged {
                        viewportWidth = it.width
                        viewportHeight = it.height
                    },
                contentAlignment = Alignment.Center,
            ) {
                val failure = state.failure
                when {
                    // The whole document comes first (see MemoryCopy): how much of it, once its size is known.
                    state.loading -> FileLoading(state.loaded, state.size, onDark = false)
                    failure != null -> ViewerFailure(
                                                title = stringResource(
                            when (failure) {
                                PdfViewModel.Failure.PASSWORD_PROTECTED -> R.string.viewer_pdf_password
                                PdfViewModel.Failure.NOT_AVAILABLE -> R.string.viewer_pdf_not_available
                                PdfViewModel.Failure.UNREADABLE -> R.string.viewer_pdf_unreadable
                                PdfViewModel.Failure.TOO_LARGE -> R.string.viewer_pdf_too_large
                            },
                        ),
                        subtitle = when (failure) {
                            PdfViewModel.Failure.PASSWORD_PROTECTED -> stringResource(R.string.viewer_pdf_password_hint)
                            PdfViewModel.Failure.NOT_AVAILABLE -> stringResource(R.string.viewer_pdf_not_available_hint)
                            PdfViewModel.Failure.UNREADABLE -> null
                            PdfViewModel.Failure.TOO_LARGE -> stringResource(R.string.viewer_pdf_too_large_hint, Formatter.formatShortFileSize(context, MemoryCopy.MAX_BYTES))
                        },
                        onDark = false,
                    )
                    else -> Box(
                        Modifier
                            .fillMaxSize()
                            // At rest a one-finger drag belongs to the list, which scrolls and flings.
                            .transformable(state = transformState, canPan = { false })
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { at -> zoomAround(if (scale > 1.01f) 1f else ZOOM_ON_TAP, at) })
                            }
                            // Zoomed, one drag has to go any way and change its mind mid-way; two stacked
                            // scrolls cannot, the first to claim an axis keeps the finger. So the drag is
                            // read here and pushed into both, and let go with the platform's fling on each.
                            .pointerInput(scale > 1.01f) {
                                if (scale <= 1.01f) return@pointerInput
                                val velocity = VelocityTracker()
                                detectDragGestures(
                                    onDragStart = { velocity.resetTracking() },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        velocity.addPointerInputChange(change)
                                        sideways.dispatchRawDelta(-delta.x)
                                        // The list is drawn scaled: a finger's worth of screen is that much less document.
                                        listState.dispatchRawDelta(-delta.y / scale)
                                    },
                                    onDragEnd = {
                                        val speed = velocity.calculateVelocity()
                                        scope.launch { sideways.scroll { with(fling) { performFling(-speed.x) } } }
                                        scope.launch { listState.scroll { with(fling) { performFling(-speed.y / scale) } } }
                                    },
                                )
                            }
                            // Off, because the drag above moves it; on, it would take the gesture the moment it went sideways.
                            .horizontalScroll(sideways, enabled = false),
                    ) {
                        Box(Modifier.width(with(density) { (viewportWidth * scale).toDp() }).fillMaxHeight()) {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .width(with(density) { viewportWidth.toDp() })
                                    .height(with(density) { viewportHeight.toDp() })
                                    // From the top left corner, so the sideways scroll and the screen count the same pixels.
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        transformOrigin = TransformOrigin(0f, 0f)
                                    },
                            ) {
                                items(count = state.pageCount, key = { it }) { index ->
                                    PdfPage(
                                        viewModel = viewModel,
                                        index = index,
                                        widthPx = if (detailWidth > 0 && index == focusedPage) detailWidth else viewportWidth,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(viewModel: PdfViewModel, index: Int, widthPx: Int) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    var shape by remember(index) { mutableStateOf<PdfViewModel.PageShape?>(null) }

    LaunchedEffect(index, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        shape = viewModel.shapeOf(index)
        bitmap = viewModel.render(index, widthPx)
    }

    val ratio = shape?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: DEFAULT_PAGE_RATIO
    // Paper on the background: a slight lift and the app's smallest corner.
    val paper = RoundedCornerShape(Corners.Inner)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).aspectRatio(ratio).shadow(2.dp, paper).clip(paper).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), stringResource(R.string.viewer_pdf_cd_page, index + 1), contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * How far to move the sideways scroll (x) and the list (y, in the document's own pixels) as the scale
 * goes [from] to [to], so the point of the document under [at] stays under it. The list is drawn scaled
 * from its top left corner and the sideways scroll counts screen pixels (see [PdfViewerScreen]).
 */
internal fun zoomShift(at: Offset, sideways: Float, from: Float, to: Float): Offset {
    val docX = (sideways + at.x) / from
    return Offset(docX * to - at.x - sideways, at.y / from - at.y / to)
}

/** A4 upright, holding a page's place until its own shape is known. */
private const val DEFAULT_PAGE_RATIO = 1f / 1.414f
private const val MAX_ZOOM = 4f
private const val ZOOM_ON_TAP = 2.5f
private const val ZOOM_FOR_DETAIL = 1.6f
private const val DETAIL_FACTOR = 1.5f
private const val MAX_RENDER_WIDTH = 2200
