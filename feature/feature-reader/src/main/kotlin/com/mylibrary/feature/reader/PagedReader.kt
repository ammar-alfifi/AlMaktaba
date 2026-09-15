package com.mylibrary.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.PageSize
import com.mylibrary.core.ui.component.ErrorState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The reader for fixed-page documents: PDF, CBZ and CBR.
 *
 * Paging uses Compose's `HorizontalPager`, which matters for two reasons beyond the project's
 * no-`ViewPager2` rule. It is lazy — only the visible page and its immediate neighbours are
 * composed, so a 900-page PDF never holds 900 page composables — and it follows
 * `LocalLayoutDirection`, which means a right-to-left book pages right-to-left with no extra code
 * here at all.
 */
@Composable
fun PagedReaderContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = state.currentUnit.coerceAtLeast(0),
        pageCount = { state.totalUnits },
    )

    // Pager -> state. `distinctUntilChanged` is what stops the two sync effects below from
    // ping-ponging: this one emits only when the page genuinely changes.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> onIntent(ReaderIntent.PageChanged(page)) }
    }

    // State -> pager, for jumps that did not come from a swipe: a table-of-contents entry, a search
    // result, or the page slider in the bottom bar.
    LaunchedEffect(state.currentUnit, state.totalUnits) {
        if (state.totalUnits > 0 && state.currentUnit != pagerState.currentPage) {
            pagerState.animateScrollToPage(state.currentUnit)
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        pageSpacing = PAGE_SPACING,
        beyondViewportPageCount = 1,
    ) { pageIndex ->
        ZoomablePage(
            pageIndex = pageIndex,
            fitMode = state.settings.pageFitMode,
            tapToTurnPages = state.settings.tapToTurnPages,
            viewModel = viewModel,
            onIntent = onIntent,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * One page, with pinch-to-zoom, pan, double-tap-to-zoom and the page-turn tap zones.
 *
 * The page is rendered at the size of the viewport, and re-rendered at a higher resolution once the
 * user has zoomed past the point where a 1:1 render would look soft. That is the compromise that
 * keeps memory bounded: rendering every page at 3x up front would allocate nine times the memory
 * for pages the user may never zoom into, while never re-rendering would leave zoomed text blurry on
 * exactly the documents — scanned PDFs and comics — where zooming is the whole point.
 *
 * What is drawn, and therefore what the user can pan to, follows [fitMode]; see [renderBoxFor] and
 * [drawnPageSize] for the two halves of that decision.
 */
@Composable
private fun ZoomablePage(
    pageIndex: Int,
    fitMode: PageFitMode,
    tapToTurnPages: Boolean,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentOnIntent by rememberUpdatedState(onIntent)
    val haptics = LocalHapticFeedback.current

    // A page turn is felt as well as seen. It is the cheapest confirmation a touch interface has,
    // and it is what tells the reader the tap registered during the fraction of a second before the
    // page has finished moving.
    val currentTapToTurn by rememberUpdatedState(tapToTurnPages)

    // Bucketed to whole steps so that a continuous pinch does not request a new render on every
    // frame; only crossing 2x or 3x triggers a sharper render.
    val resolutionStep = remember(scale) {
        scale.coerceIn(1f, MAX_RENDER_SCALE).toInt().coerceAtLeast(1)
    }

    // What the document says its page looks like. Needed before the pixels are: a width-fitted
    // render is as tall as the page's own proportions make it, and an actual-size render is the
    // page's own dimensions. `null` until it arrives, and for a document that cannot report one —
    // which is why *whether* the question has been answered is tracked separately from its answer.
    var pageSizeAnswered by remember(pageIndex) { mutableStateOf(false) }
    val pageSize by produceState<PageSize?>(initialValue = null, pageIndex) {
        value = viewModel.pageSize(pageIndex)
        pageSizeAnswered = true
    }

    val renderBox = remember(containerSize, resolutionStep, fitMode, pageSize, pageSizeAnswered) {
        // Width fit and actual size cannot be asked for until the page's shape is known, and asking
        // for them at the viewport's shape instead would render the page twice — the first result
        // visibly snapping to the second — and spend a page's worth of the byte-bounded cache on
        // pixels nobody ever sees. Page fit needs nothing from the document, so it never waits.
        when {
            !pageSizeAnswered && fitMode != PageFitMode.PAGE -> IntSize.Zero
            else -> renderBoxFor(fitMode, containerSize, resolutionStep, pageSize)
        }
    }

    val renderState by produceState<PageRenderState>(
        initialValue = PageRenderState.Loading,
        pageIndex,
        renderBox,
    ) {
        if (renderBox.width > 0 && renderBox.height > 0) {
            value = viewModel.renderPage(
                pageIndex = pageIndex,
                widthPx = renderBox.width,
                heightPx = renderBox.height,
            )
        }
    }

    val bitmap = (renderState as? PageRenderState.Ready)?.image
    val drawnSize = bitmap
        ?.let { drawnPageSize(it.width, it.height, containerSize, fitMode) }
        ?: Size.Zero

    // The gesture handlers are installed once and never restarted, so they have to read the
    // *current* geometry rather than the geometry of the composition that created them. `drawnSize`
    // is [Size.Zero] until the first page arrives, and a captured copy of it would leave panning
    // permanently disabled and every tap landing in the middle zone.
    val currentDrawnSize by rememberUpdatedState(drawnSize)
    val currentContainer by rememberUpdatedState(containerSize)

    // A change of fit mode re-frames the page. A zoom or a pan carried over from the previous mode
    // would leave the page off-centre in a frame it no longer fits.
    LaunchedEffect(fitMode) {
        scale = 1f
        offset = Offset.Zero
    }

    Box(
        modifier = modifier
            .background(PAGE_BACKGROUND)
            // The page is drawn inside its own slot; without this a page wider than the viewport —
            // which is exactly what actual size produces — would paint over its neighbours.
            .clipToBounds()
            .onSizeChanged { size -> containerSize = size }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    offset = if (overflows(currentDrawnSize, currentContainer, scale)) {
                        clampPan(offset + pan, currentDrawnSize, currentContainer, scale)
                    } else {
                        Offset.Zero
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { position ->
                        // A zoomed page is being inspected, not read, so a tap only reveals the
                        // toolbar; turning the page under the reader's fingers would lose the place
                        // they zoomed in to look at.
                        if (scale > 1f || !currentTapToTurn) {
                            currentOnIntent(ReaderIntent.ToggleChrome)
                            return@detectTapGestures
                        }
                        when (tapZoneFor(position.x, size.width.toFloat(), isRtl)) {
                            TapZone.PREVIOUS -> {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                currentOnIntent(ReaderIntent.PreviousUnit)
                            }

                            TapZone.NEXT -> {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                currentOnIntent(ReaderIntent.NextUnit)
                            }

                            TapZone.CENTER -> currentOnIntent(ReaderIntent.ToggleChrome)
                        }
                    },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_SCALE
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val render = renderState) {
            is PageRenderState.Ready -> Image(
                bitmap = render.image,
                contentDescription = null,
                contentScale = fitMode.contentScale(),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )

            is PageRenderState.Failed -> ErrorState(
                error = render.error,
                modifier = Modifier.padding(24.dp),
                onRetry = { currentOnIntent(ReaderIntent.Retry) },
            )

            PageRenderState.Loading -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The pixel box a page is rendered into.
 *
 * This is where [PageFitMode] stops being a stored preference and starts being pixels, and it has to
 * happen *here* rather than in the view layer for a reason worth stating: pdfium does not letterbox.
 * It maps the page's box onto whatever rectangle it is handed, so a render box of the wrong shape
 * comes back as a stretched page — skewed text rather than a smaller one. The aspect of the box
 * therefore *is* the fit, and each mode asks for a differently-shaped box:
 *
 *  - [PageFitMode.PAGE] — the viewport itself, which the engine fills with the largest page that
 *    keeps its proportions. The whole page, letterboxed. This is the previous behaviour.
 *  - [PageFitMode.WIDTH] — the viewport's width, and as much height as the page's own proportions
 *    ask for. Dense two-column PDFs and comic pages are unreadable at page fit on a phone; this is
 *    the mode that makes them legible without pinching.
 *  - [PageFitMode.ACTUAL_SIZE] — the page's own dimensions, unscaled. One document point becomes one
 *    device pixel, so nothing is resampled.
 *
 * The two modes whose size comes from the *file* — width fit and actual size — are then capped by
 * [capRenderBox], because a PDF page tree may declare any MediaBox it likes and a 30000-point page
 * is a real thing to find in a malformed file. Page fit is not capped, and deliberately: its box is
 * the viewport, which is bounded by the device rather than by the document. Capping it would render
 * the page smaller than the frame it has to fill and blur it in the process. The rule is that what
 * the file controls gets bounded, and what the screen controls does not.
 */
internal fun renderBoxFor(
    fitMode: PageFitMode,
    container: IntSize,
    resolutionStep: Int,
    pageSize: PageSize?,
): IntSize {
    if (container.width <= 0 || container.height <= 0) return IntSize.Zero

    val step = resolutionStep.coerceAtLeast(1)
    val viewport = IntSize(container.width * step, container.height * step)
    val page = pageSize?.takeIf { it.width > 0 && it.height > 0 } ?: return viewport

    return when (fitMode) {
        PageFitMode.PAGE -> viewport

        PageFitMode.WIDTH -> capRenderBox(
            width = viewport.width,
            // Long arithmetic: a wide viewport times a tall page's ratio overflows an Int.
            height = (viewport.width.toLong() * page.height / page.width)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
        )

        PageFitMode.ACTUAL_SIZE -> capRenderBox(
            width = scaledSaturating(page.width, step),
            height = scaledSaturating(page.height, step),
        )
    }
}

/**
 * `value * factor`, saturating at [Int.MAX_VALUE] instead of wrapping.
 *
 * A malformed page tree can declare a MediaBox of any size at all, and a wrapped product would come
 * back negative — which [capRenderBox] reads as "no box", leaving the reader on a spinner that never
 * resolves. Saturating instead hands the cap something absurd but positive, which it shrinks.
 */
private fun scaledSaturating(value: Int, factor: Int): Int =
    (value.toLong() * factor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/**
 * The size at which a page is drawn on screen, before zoom.
 *
 * Three cases because the three fit modes reach the screen by different routes: a contained or
 * width-fitted page is scaled by the layout, while an actual-size page is drawn at its own pixel
 * dimensions and is therefore already its own drawn size.
 */
internal fun drawnPageSize(
    bitmapWidth: Int,
    bitmapHeight: Int,
    container: IntSize,
    fitMode: PageFitMode,
): Size {
    if (bitmapWidth <= 0 || bitmapHeight <= 0) return Size.Zero
    if (container.width <= 0 || container.height <= 0) return Size.Zero

    return when (fitMode) {
        PageFitMode.PAGE -> {
            val factor = min(
                container.width.toFloat() / bitmapWidth,
                container.height.toFloat() / bitmapHeight,
            )
            Size(bitmapWidth * factor, bitmapHeight * factor)
        }

        PageFitMode.WIDTH -> {
            val factor = container.width.toFloat() / bitmapWidth
            Size(bitmapWidth * factor, bitmapHeight * factor)
        }

        PageFitMode.ACTUAL_SIZE -> Size(bitmapWidth.toFloat(), bitmapHeight.toFloat())
    }
}

/**
 * Keeps a pan inside the page.
 *
 * The translation is measured from the centre, so the furthest the page may be dragged is half of
 * whatever it overflows by, per axis. Without this a zoomed page can be flung entirely off a blank
 * background with no gesture that obviously brings it back.
 */
internal fun clampPan(offset: Offset, drawn: Size, container: IntSize, scale: Float): Offset {
    val slackX = ((drawn.width * scale - container.width) / 2f).coerceAtLeast(0f)
    val slackY = ((drawn.height * scale - container.height) / 2f).coerceAtLeast(0f)
    // Written as a branch rather than `coerceIn(-slack, slack)`: negating a zero slack gives
    // negative zero, which is a different `Float` bit pattern and therefore a different `Offset`
    // from the one every caller compares against to mean "not panned".
    return Offset(
        x = if (slackX == 0f) 0f else offset.x.coerceIn(-slackX, slackX),
        y = if (slackY == 0f) 0f else offset.y.coerceIn(-slackY, slackY),
    )
}

/**
 * Shrinks a render box that a page's own dimensions made too large to allocate.
 *
 * A PDF page tree may declare any MediaBox it likes, and a 30000-point page is a real thing to find
 * in a malformed file. Both dimensions are scaled by the same factor so the page keeps its shape —
 * a capped render that had been squashed on one axis would come out stretched rather than smaller.
 */
internal fun capRenderBox(width: Int, height: Int): IntSize {
    if (width <= 0 || height <= 0) return IntSize.Zero

    val edgeFactor = min(
        MAX_RENDER_EDGE_PX.toFloat() / width,
        MAX_RENDER_EDGE_PX.toFloat() / height,
    )
    val pixelFactor = run {
        val pixels = width.toLong() * height
        if (pixels <= MAX_RENDER_PIXELS) 1f
        else kotlin.math.sqrt(MAX_RENDER_PIXELS.toDouble() / pixels).toFloat()
    }

    val factor = min(1f, min(edgeFactor, pixelFactor))
    return IntSize(
        width = (width * factor).roundToInt().coerceAtLeast(1),
        height = (height * factor).roundToInt().coerceAtLeast(1),
    )
}

/**
 * Whether the page, at [scale], is bigger than the frame it is in.
 *
 * Panning only means something when it is: dragging a contained page around would slide it off the
 * screen for no reason.
 */
private fun overflows(drawn: Size, container: IntSize, scale: Float): Boolean =
    drawn.width * scale > container.width || drawn.height * scale > container.height

/** How the rendered bitmap is laid out inside the page slot. */
private fun PageFitMode.contentScale(): ContentScale = when (this) {
    PageFitMode.PAGE -> ContentScale.Fit
    PageFitMode.WIDTH -> ContentScale.FillWidth
    // Drawn at its own pixel dimensions: one document pixel to one device pixel, no resampling.
    PageFitMode.ACTUAL_SIZE -> ContentScale.None
}

private val PAGE_SPACING = 8.dp

/** A neutral ground behind pages, matching how PDF readers present a paper page. */
private val PAGE_BACKGROUND = Color(0xFF2B2B2B)

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val MAX_RENDER_SCALE = 3f
private const val DOUBLE_TAP_SCALE = 2.5f

/** 32 MB of ARGB — several screens' worth, and far short of an out-of-memory on a mid-range phone. */
private const val MAX_RENDER_PIXELS = 8_000_000L

/** No single edge longer than this, whatever the page asks for. */
private const val MAX_RENDER_EDGE_PX = 4096
