package me.esdex.coffer.feature.browser.viewer

import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
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

    val sideways = rememberScrollState()
    val scope = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    LaunchedEffect(scale) { if (scale <= 1.01f) sideways.scrollTo(0) }
    var zooming: Job? by remember { mutableStateOf(null) }
    // The flings a drag let go with: a finger coming down again stops them, or they pull against it.
    var flings: List<Job> by remember { mutableStateOf(emptyList()) }
    fun stopFlings() {
        flings.forEach { it.cancel() }
        flings = emptyList()
    }

    // Where the sideways scroll should be during a pinch: the real one cannot go past the room the last
    // frame laid out, which grows a frame behind the scale, so it catches up when the pinch ends.
    var pinchSideways: Float? by remember { mutableStateOf(null) }

    // Zoom about the point between the fingers, and move with them, as the gallery does. Only the scale
    // changed, the page grew out of its top left corner and the place pinched slid away. Moving about a
    // zoomed page is a real horizontal scroll below rather than a moved layer: a moved layer stops dead
    // where the finger lifts, a scroll flings on like the list does.
    var pinching by remember { mutableStateOf(false) }
    fun pinchBy(centroid: Offset, zoomChange: Float, pan: Offset) {
        if (!pinching) {
            zooming?.cancel()
            stopFlings()
            pinching = true
        }
        val next = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        val base = (pinchSideways ?: sideways.value.toFloat()) - pan.x
        val shift = zoomShift(centroid, base, scale, next)
        // The list is drawn scaled: a finger's worth of screen is that much less document.
        listState.dispatchRawDelta(-pan.y / scale + shift.y)
        scale = next
        pinchSideways = base + shift.x
        sideways.dispatchRawDelta(base + shift.x - sideways.value)
    }

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
    val transforming = pinching
    LaunchedEffect(transforming) {
        val wanted = pinchSideways
        if (!transforming && wanted != null) {
            withFrameNanos { }
            sideways.scrollTo(wanted.roundToInt().coerceIn(0, sideways.maxValue))
            pinchSideways = null
        }
    }
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
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { at -> zoomAround(if (scale > 1.01f) 1f else ZOOM_ON_TAP, at) })
                            }
                            // Pinch and drag in one place, seen before the list does. Apart, a drag that got going
                            // before the second finger came down took the gesture, the pinch gave up until every
                            // finger was off, and zooming out after moving about worked one time in two. Two
                            // fingers zoom and move; one, while zoomed, goes any way and changes its mind mid-way,
                            // which two stacked scrolls cannot, and is let go with the platform's fling on each.
                            // At rest one finger belongs to the list, which scrolls and flings by itself.
                            .pointerInput(Unit) {
                                val velocity = VelocityTracker()
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    stopFlings()
                                    velocity.resetTracking()
                                    var dragging = false
                                    var travelled = Offset.Zero
                                    var multi = false
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.size >= 2) {
                                            pinchBy(event.calculateCentroid(useCurrent = true), event.calculateZoom(), event.calculatePan())
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            multi = true
                                            dragging = false
                                        } else if (pressed.size == 1 && scale > 1.01f) {
                                            val change = pressed.single()
                                            // Back to one finger after a pinch: a fresh drag from here, no slop to cross.
                                            if (multi) {
                                                multi = false
                                                dragging = true
                                                velocity.resetTracking()
                                            }
                                            velocity.addPointerInputChange(change)
                                            val delta = change.positionChange()
                                            if (!dragging) {
                                                travelled += delta
                                                dragging = travelled.getDistance() > viewConfiguration.touchSlop
                                            }
                                            if (dragging) {
                                                change.consume()
                                                sideways.dispatchRawDelta(-delta.x)
                                                // The list is drawn scaled: a finger's worth of screen is that much less document.
                                                listState.dispatchRawDelta(-delta.y / scale)
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                    pinching = false
                                    if (dragging && scale > 1.01f) {
                                        val speed = velocity.calculateVelocity()
                                        flings = listOf(
                                            scope.launch { sideways.scroll { with(fling) { performFling(-speed.x) } } },
                                            scope.launch { listState.scroll { with(fling) { performFling(-speed.y / scale) } } },
                                        )
                                    }
                                }
                            }
                            // Off, because the drag above moves it; on, it would take the gesture the moment it went sideways.
                            .horizontalScroll(sideways, enabled = false),
                    ) {
                        Box(Modifier.width(with(density) { (viewportWidth * scale).toDp() }).fillMaxHeight()) {
                            LazyColumn(
                                state = listState,
                                // Zoomed, the drag above moves the list. Left on, the list's own scroll took every drag
                                // that began a little up or down, and the page would then not go sideways at all.
                                userScrollEnabled = scale <= 1.01f,
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
