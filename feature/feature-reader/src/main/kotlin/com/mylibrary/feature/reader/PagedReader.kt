package com.mylibrary.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.PageSize
import com.mylibrary.core.domain.model.PageTurnEffect
import com.mylibrary.core.ui.component.ErrorState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
            // Read inside `graphicsLayer`, so a page's turn transform tracks the finger without
            // recomposing the page on every frame of the drag.
            pageOffset = { pagerState.offsetOf(pageIndex) },
            fitMode = state.settings.pageFitMode,
            tapToTurnPages = state.settings.tapToTurnPages,
            bubbleZoom = state.settings.bubbleZoom,
            pageTurnEffect = state.settings.pageTurnEffect,
            viewModel = viewModel,
            onIntent = onIntent,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * How far a page is from the settled position, in pages, signed.
 *
 * Positive means the page is arriving from the trailing edge of the reading direction. The value is
 * continuous during a drag, which is what every effect in [pageTurnTransform] is driven by.
 */
private fun PagerState.offsetOf(pageIndex: Int): Float = getOffsetDistanceInPages(pageIndex)

/**
 * One page: its turn transform, pinch-to-zoom, pan, double-tap zoom and the page-turn tap zones.
 *
 * **Zoom, in two representations.** The *state* is a [PageTransform] in view pixels, which is what
 * the gesture handlers write. The *destination* of a programmatic zoom is a [ZoomTarget] in page
 * pixels, because the reader re-renders a page at a higher resolution once the user zooms past a
 * whole step — and for an actual-size page that makes the drawn page bigger, moving the ground
 * under a pixel-based target. A target expressed against the page survives it.
 *
 * **Double-tap.** A double-tap on a comic's speech bubble or panel zooms to frame it, using the
 * page's own pixels to work out what is under the finger ([findBubbleRegion]). Where there is
 * nothing to frame — a drawing, a page of text, a tap on the letterbox margin — it falls back to a
 * fixed zoom about the tapped point, which is the behaviour the reader always had, improved by
 * zooming where the user aimed rather than at the centre of the page.
 */
@Composable
private fun ZoomablePage(
    pageIndex: Int,
    pageOffset: () -> Float,
    fitMode: PageFitMode,
    tapToTurnPages: Boolean,
    bubbleZoom: Boolean,
    pageTurnEffect: PageTurnEffect,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // The zoom, in the reference-render space described on [PageTransform]: a magnification that does
    // not change when the page is re-rendered more sharply. `referenceWhenSet` is the page's
    // reference size at the moment the translation was measured, so a change of the *slot* can rebase
    // it while a change of resolution deliberately cannot.
    var transform by remember { mutableStateOf(PageTransform.Identity) }
    var referenceWhenSet by remember { mutableStateOf(Size.Zero) }

    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val currentOnIntent by rememberUpdatedState(onIntent)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // The zoom animation, as a clock from 0 to 1 plus the two transforms it moves between. The
    // interpolated value is written into `transform` itself rather than into a separate layer, so a
    // pinch that interrupts an animation takes over from exactly where it had got to.
    val zoomClock = remember { Animatable(0f) }
    var zoomFrom by remember { mutableStateOf(PageTransform.Identity) }
    var zoomTo by remember { mutableStateOf(PageTransform.Identity) }
    var zoomJob by remember { mutableStateOf<Job?>(null) }

    // A page turn is felt as well as seen. It is the cheapest confirmation a touch interface has,
    // and it is what tells the reader the tap registered during the fraction of a second before the
    // page has finished moving.
    val currentTapToTurn by rememberUpdatedState(tapToTurnPages)
    val currentBubbleZoom by rememberUpdatedState(bubbleZoom)

    // Bucketed to whole steps so that a continuous pinch does not request a new render on every
    // frame; only crossing 2x or 3x triggers a sharper render.
    val resolutionStep = remember(transform.scale) {
        transform.scale.coerceIn(1f, MAX_RENDER_SCALE).toInt().coerceAtLeast(1)
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

    // What the page would be drawn at if it were rendered at the reader's base resolution. For page
    // fit and width fit this equals `drawnSize`, because those sizes come from the viewport rather
    // than from the bitmap; for actual size it is the size the page is drawn at *per unit of zoom*,
    // which is what the stored transform is measured against. The ratio of the two is what the layer
    // scale has to undo — without it, crossing a resolution step while pinching would double the
    // magnification of an actual-size page instead of sharpening it.
    val referenceDrawnSize = remember(drawnSize, resolutionStep, containerSize, fitMode, bitmap) {
        bitmap?.let {
            val baseWidth = (it.width / resolutionStep).coerceAtLeast(1)
            val baseHeight = (it.height / resolutionStep).coerceAtLeast(1)
            drawnPageSize(baseWidth, baseHeight, containerSize, fitMode)
        } ?: Size.Zero
    }

    // What the layout draws with: the stored magnification converted into the scale this render
    // needs in order to look the same size.
    val layerTransform = PageTransform(
        scale = layerScaleFor(transform.scale, drawnSize, referenceDrawnSize),
        offset = transform.offset,
    )

    // A change of the slot's own size — a rotation, a split screen — moves the ground the pan was
    // measured against, so it is rebased once, here rather than in composition.
    LaunchedEffect(referenceDrawnSize) {
        if (referenceWhenSet.width > 0f && referenceDrawnSize.width > 0f &&
            referenceWhenSet != referenceDrawnSize
        ) {
            transform = rebaseTransform(transform, referenceWhenSet, referenceDrawnSize, containerSize)
        }
        referenceWhenSet = referenceDrawnSize
    }

    // The gesture handlers are installed once and never restarted, so they have to read the
    // *current* geometry rather than the geometry of the composition that created them. `drawnSize`
    // is [Size.Zero] until the first page arrives, and a captured copy of it would leave panning
    // permanently disabled and every tap landing in the middle zone.
    val currentDrawnSize by rememberUpdatedState(drawnSize)
    val currentContainer by rememberUpdatedState(containerSize)
    val currentLayerTransform by rememberUpdatedState(layerTransform)
    val currentReference by rememberUpdatedState(referenceDrawnSize)
    val currentBitmap by rememberUpdatedState(bitmap)

    /** Animates to [target], cancelling any zoom already in flight. */
    fun zoomTo(target: ZoomTarget) {
        val bitmap = currentBitmap ?: return
        val container = currentContainer
        val drawn = currentDrawnSize
        val reference = currentReference
        if (container.width <= 0 || container.height <= 0 || drawn.width <= 0f) return

        // The destination is worked out in the scale the layout sees — a region framed to 85% of the
        // viewport is a statement about pixels on screen — and stored in the scale that survives a
        // re-render.
        val endOnScreen = transformFor(target, container, drawn, bitmap.width, bitmap.height)
        val end = PageTransform(
            scale = referenceScaleFor(endOnScreen.scale, drawn, reference),
            offset = endOnScreen.offset,
        )

        zoomJob?.cancel()
        zoomFrom = transform
        zoomTo = end
        zoomJob = scope.launch {
            zoomClock.snapTo(0f)
            zoomClock.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = ZOOM_TWEEN_MS, easing = FastOutSlowInEasing),
            ) {
                transform = lerpTransform(zoomFrom, zoomTo, value)
                // Recorded every frame, so a rebase caused by the slot changing size is measured
                // against the size this interpolation was computed for.
                referenceWhenSet = currentReference
            }
        }
    }

    /**
     * Zooms into whatever is under [position]: a speech bubble or panel if one is there, and a fixed
     * magnification about the tapped point if not.
     */
    fun zoomIntoPage(position: Offset) {
        val bitmap = currentBitmap
        val container = currentContainer
        val drawn = currentDrawnSize
        val wantsBubble = currentBubbleZoom

        scope.launch {
            val framed = if (bitmap != null && wantsBubble) {
                val pixel = viewPointToPixel(
                    viewPoint = position,
                    container = container,
                    drawn = drawn,
                    transform = currentLayerTransform,
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height,
                )
                pixel
                    ?.let { point ->
                        viewModel.bubbleRegionAt(bitmap, point.x.toInt(), point.y.toInt())
                    }
                    ?.let { region ->
                        zoomTargetForRegion(
                            region = region.bounds,
                            container = container,
                            drawn = drawn,
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height,
                        )
                    }
                    // A region covering nearly the whole page is not worth framing: the plain zoom
                    // is both smaller and more useful.
                    ?.takeIf { it.scale >= MIN_USEFUL_ZOOM }
            } else {
                null
            }

            if (framed != null) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                zoomTo(framed)
                return@launch
            }

            // Nothing to frame: the whole page, or a tap that landed on a drawing. Zoom about the
            // point that was tapped — the reader aimed there, and a zoom that lands somewhere else
            // is the one thing a double-tap must not do.
            val pixel = viewPointToPixel(
                viewPoint = position,
                container = container,
                drawn = drawn,
                transform = currentLayerTransform,
                bitmapWidth = bitmap?.width ?: 0,
                bitmapHeight = bitmap?.height ?: 0,
            )
            val fraction = pixel?.let {
                pixelToPageFraction(it, bitmap?.width ?: 0, bitmap?.height ?: 0)
            } ?: Offset(0.5f, 0.5f)
            zoomTo(
                ZoomTarget(
                    anchorFraction = fraction,
                    anchorView = position,
                    scale = DOUBLE_TAP_SCALE,
                ),
            )
        }
    }

    // A change of fit mode re-frames the page. A zoom or a pan carried over from the previous mode
    // would leave the page off-centre in a frame it no longer fits.
    LaunchedEffect(fitMode) {
        zoomJob?.cancel()
        transform = PageTransform.Identity
        referenceWhenSet = Size.Zero
    }

    val currentPageOffset = pageOffset
    val shadeBrush = { size: Size, pivot: Float, alpha: Float ->
        // Shaded from the hinge outwards: a turning page is lit by the room and shadowed by the
        // block it is coming off, so the darkest part of it is the part still attached.
        Brush.horizontalGradient(
            colors = listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
            startX = if (pivot < 0.5f) 0f else size.width,
            endX = if (pivot < 0.5f) size.width else 0f,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // The turn transform wraps the page rather than sharing a modifier chain with the zoom:
            // the page must be free to swing past its own slot, while the *zoom* stays clipped to it.
            .graphicsLayer {
                val turn = pageTurnTransform(currentPageOffset(), pageTurnEffect, isRtl)
                scaleX = turn.scale
                scaleY = turn.scale
                alpha = turn.alpha
                rotationY = turn.rotationY
                transformOrigin = TransformOrigin(pageTurnPivotX(currentPageOffset(), isRtl), 0.5f)
                // A page turning in flat space looks like a card being slid. The camera distance is
                // what gives the rotation perspective; expressed in density units so it looks the
                // same on a dense screen as on a cheap one.
                cameraDistance = CAMERA_DISTANCE_DP * density.density
            }
            .drawWithContent {
                drawContent()
                val alpha = pageTurnTransform(currentPageOffset(), pageTurnEffect, isRtl).shadeAlpha
                if (alpha > 0f) {
                    val pivot = pageTurnPivotX(currentPageOffset(), isRtl)
                    drawRect(brush = shadeBrush(size, pivot, alpha))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PAGE_BACKGROUND)
                // The page is drawn inside its own slot; without this a page wider than the
                // viewport — which is exactly what actual size produces — would paint over its
                // neighbours.
                .clipToBounds()
                .onSizeChanged { size -> containerSize = size }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // A pinch takes over from an animation in flight, from wherever it had got
                        // to: the animation writes into the same state this reads.
                        zoomJob?.cancel()
                        // The rebased transform, not the stored one: for an actual-size page a
                        // sharper render changes how large the page is drawn, and a gesture applied
                        // to the pre-render numbers would jump the page on the next frame.
                        // The zoom multiplies the *stored* magnification, which is the one that
                        // survives a change of render resolution; the pan is clamped against the
                        // scale the layout is actually using.
                        val nextScale = (transform.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        val nextLayerScale = layerScaleFor(nextScale, currentDrawnSize, currentReference)
                        val nextOffset = if (overflows(currentDrawnSize, currentContainer, nextLayerScale)) {
                            clampPan(
                                currentLayerTransform.offset + pan,
                                currentDrawnSize,
                                currentContainer,
                                nextLayerScale,
                            )
                        } else {
                            Offset.Zero
                        }
                        transform = PageTransform(nextScale, nextOffset)
                        referenceWhenSet = currentReference
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { position ->
                            // A zoomed page is being inspected, not read, so a tap only reveals the
                            // toolbar; turning the page under the reader's fingers would lose the
                            // place they zoomed in to look at.
                            if (transform.scale > 1f || !currentTapToTurn) {
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
                        onDoubleTap = { position ->
                            // A double-tap never reaches `onTap`, so the page-turn zones are
                            // untouched by sharing the surface with this.
                            if (transform.scale > 1.02f) {
                                zoomTo(
                                    identityTarget(
                                        bitmapWidth = currentBitmap?.width ?: 0,
                                        bitmapHeight = currentBitmap?.height ?: 0,
                                        container = currentContainer,
                                    ),
                                )
                            } else {
                                zoomIntoPage(position)
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
                            scaleX = layerTransform.scale,
                            scaleY = layerTransform.scale,
                            translationX = layerTransform.offset.x,
                            translationY = layerTransform.offset.y,
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

/**
 * How far the virtual camera sits from a turning page, in density-independent units.
 *
 * Larger values flatten the rotation towards an orthographic projection; this is the range that
 * makes a page at seventy degrees look like paper coming off a block rather than a spinning panel.
 */
private const val CAMERA_DISTANCE_DP = 14f

/** Long enough to be read as movement, short enough not to be waited for. */
private const val ZOOM_TWEEN_MS = 280

private const val MAX_RENDER_SCALE = 3f
private const val DOUBLE_TAP_SCALE = 2.5f

/** 32 MB of ARGB — several screens' worth, and far short of an out-of-memory on a mid-range phone. */
private const val MAX_RENDER_PIXELS = 8_000_000L

/** No single edge longer than this, whatever the page asks for. */
private const val MAX_RENDER_EDGE_PX = 4096
