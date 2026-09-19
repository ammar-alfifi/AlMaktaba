package com.mylibrary.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.mylibrary.core.domain.model.PageFitMode
import com.mylibrary.core.domain.model.PageSize
import com.mylibrary.core.domain.model.PageTurnEffect
import com.mylibrary.core.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A document made of page images, presented as a continuous scroll.
 *
 * The other half of [ReaderLayout]. The reader used to offer scrolling only to reflowed text — a PDF
 * was always a stack of pages to turn — which made "pages or scrolling" a question about the file
 * type rather than about how the reader wants to read. Both families now have both presentations,
 * and the layout setting picks one for whichever is open.
 *
 * **What it keeps.** Every page is rendered through the same pipeline the paged reader uses —
 * `pageSize` for the page's own proportions, `renderBoxFor` for the box, `renderPage` for the
 * pixels, all cached by the same byte-bounded `PageCache` — so this is a second way of *arranging*
 * pages, not a second renderer. The position it reports is the same [ReaderIntent.PageChanged] the
 * pager sends, which is why progress, bookmarks and restore work here without knowing this file
 * exists.
 *
 * **Inspection.** A page in a scrolling column is only as wide as the screen, which is too small to
 * read a speech bubble in. A double-tap or a pinch opens that page over the column at full size,
 * where the paged reader's whole zoom vocabulary applies: pinch to magnify, drag to pan, double-tap
 * to frame a speech bubble. A reader who has learnt one layout should not have to learn a second set
 * of gestures for the other.
 */
@Composable
internal fun PagedScrollReaderContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.currentUnit.coerceAtLeast(0),
    )
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentOnIntent by rememberUpdatedState(onIntent)
    val currentTapToTurn by rememberUpdatedState(state.settings.tapToTurnPages)
    val currentReverseTapZones by rememberUpdatedState(state.settings.reverseTapZones)
    // Read through `rememberUpdatedState`: the gesture loop is not restarted when the direction
    // changes, so a plain read inside it would keep the direction the book was opened in.
    val currentIsRtl by rememberUpdatedState(isRtl)
    val currentBubbleZoom by rememberUpdatedState(state.settings.bubbleZoom)
    val currentHapticsEnabled by rememberUpdatedState(state.settings.hapticsEnabled)

    // Column -> state. The index is mapped through `contentIndex` because the column can end in an
    // item that is not part of the document — the next-book panel — and a reader who has scrolled
    // onto it is still on the last page. `distinctUntilChanged` keeps the effect below from
    // ping-ponging with it.
    LaunchedEffect(listState, state.totalUnits) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .map { index -> contentIndex(index, state.totalUnits) }
            .distinctUntilChanged()
            .collect { page -> onIntent(ReaderIntent.PageChanged(page)) }
    }

    // State -> column, for a jump that did not come from scrolling: an outline entry, a search
    // result, a bookmark, or the slider in the bottom bar. Compared through the same mapping, so
    // scrolling onto the panel is not mistaken for a position the reader did not ask for and undone.
    LaunchedEffect(state.currentUnit, state.totalUnits) {
        if (state.totalUnits > 0 && state.currentUnit != contentIndex(listState.firstVisibleItemIndex, state.totalUnits)) {
            listState.scrollToItem(state.currentUnit)
        }
    }

    // The page opened over the column to be looked at closely, and the magnification it is being
    // looked at with. One at a time: a second page cannot be inspected without letting go of the
    // first, which is also what keeps the gesture below unambiguous.
    val inspection = remember { Inspection() }
    inspection.bubbleZoom = currentBubbleZoom

    Box(
        modifier = modifier
            .fillMaxSize()
            // A page turn has no meaning in a column, so the side zones advance by a screenful —
            // the same gesture and the same physical direction as the paged reader, which is what
            // keeps the two layouts feeling like one reader. Mirrored in Arabic, so the left-hand
            // tap is the one that moves forward.
            .pointerInput(inspection.page) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        if (inspection.page == null) {
                            pageAt(position, listState)?.let { (index, fraction) ->
                                inspection.frame(index, fraction)
                            }
                        }
                    },
                    onTap = { position ->
                        if (inspection.page != null) return@detectTapGestures
                        val zone = if (currentTapToTurn) {
                            tapZoneFor(
                                x = position.x,
                                width = size.width.toFloat(),
                                isRtl = currentIsRtl,
                                reversed = currentReverseTapZones,
                            )
                        } else {
                            TapZone.CENTER
                        }
                        val viewport = listState.layoutInfo.viewportSize.height

                        when (zone) {
                            TapZone.CENTER -> currentOnIntent(ReaderIntent.ToggleChrome)

                            TapZone.NEXT -> {
                                if (currentHapticsEnabled) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                scope.launch {
                                    listState.animateScrollBy(viewport * SCROLL_PAGE_FRACTION)
                                }
                            }

                            TapZone.PREVIOUS -> {
                                if (currentHapticsEnabled) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                scope.launch {
                                    listState.animateScrollBy(-viewport * SCROLL_PAGE_FRACTION)
                                }
                            }
                        }
                    },
                )
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // The column stops moving while a page is open over it, so a drag that means "pan the
            // page" cannot also drag the page out from under the finger.
            userScrollEnabled = inspection.page == null,
        ) {
            items(count = state.totalUnits, key = { index -> index }) { index ->
                ScrollPage(
                    pageIndex = index,
                    listState = listState,
                    viewModel = viewModel,
                    onIntent = onIntent,
                    onMagnify = { pinch -> inspection.magnify(index, pinch) },
                )
            }

            // The end of the file, and what comes after it in the folder. Only ever present for a
            // book that has a next volume; see [NextBookFooter].
            state.nextBook?.let { next ->
                item(key = NEXT_BOOK_ITEM_KEY) {
                    NextBookFooter(
                        next = next,
                        onOpen = { onIntent(ReaderIntent.OpenNextBook) },
                        // The pages themselves are full-bleed and may run under the chrome, but the
                        // panel's button has to be reachable with the chrome showing — so this is
                        // the one item in the column that leaves room for it, which the reflowable
                        // reader gets instead from its own content padding.
                        modifier = Modifier.padding(
                            start = Spacing.Large,
                            end = Spacing.Large,
                            bottom = ReaderChromeClearance,
                        ),
                    )
                }
            }
        }

        inspection.page?.let { index ->
            PageInspection(
                pageIndex = index,
                viewModel = viewModel,
                onIntent = onIntent,
                inspection = inspection,
                scope = scope,
                haptics = haptics,
                hapticsEnabled = currentHapticsEnabled,
            )
        }
    }
}

/**
 * Which page of the column [position] fell on, and where on that page, as a fraction of it.
 *
 * The fraction rather than the point, because the page opened over the column is fitted differently:
 * "the place under the finger" is a different coordinate in each of the two rectangles.
 *
 * This is asked at the *list* rather than by each page, and that is deliberate. A tap detector on a
 * page would sit above the list's own and swallow every single tap — which is how a version of this
 * briefly stopped tap-to-turn and the toolbar from working anywhere over a page.
 */
private fun pageAt(position: Offset, listState: LazyListState): Pair<Int, Offset>? {
    val layout = listState.layoutInfo
    val y = position.y + layout.viewportStartOffset
    val item = layout.visibleItemsInfo.firstOrNull { info ->
        y >= info.offset && y < info.offset + info.size
    } ?: return null
    if (item.size <= 0) return null
    return item.index to Offset(
        x = (position.x / layout.viewportSize.width).coerceIn(0f, 1f),
        y = ((y - item.offset) / item.size.toFloat()).coerceIn(0f, 1f),
    )
}

/**
 * One page of the column: as wide as the screen, as tall as the page's own proportions make it.
 *
 * The height has to come from the *document*, because a list cannot lay out an item whose size it
 * does not know — so the page's proportions are asked for before its pixels are, which is the same
 * `pageSize` call the paged reader makes and for the same reason.
 */
@Composable
private fun ScrollPage(
    pageIndex: Int,
    listState: LazyListState,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    onMagnify: (ColumnMagnify) -> Unit,
) {
    val pageSize by produceState<PageSize?>(initialValue = null, pageIndex) {
        value = viewModel.pageSize(pageIndex)
    }
    val ratio = pageSize
        ?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width.toFloat() / it.height.toFloat() }

    // Where this page is drawn and how big, published by [ReaderPage] as it renders. The pinch below
    // needs the *size*, because that is what the overlay has to match to take the gesture over
    // without the page changing size as it does so.
    var geometry by remember { mutableStateOf(PageGeometry()) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier),
    ) {
        // The page fills its item exactly, which is what makes the pinch measurable: here the
        // container, the drawn page and the item's own box are one rectangle, so the scale a pinch
        // reports is already the scale of the page.
        ReaderPage(
            pageIndex = pageIndex,
            pageOffset = { 0f },
            isCurrentPage = false,
            layerTransform = PageTransform.Identity,
            resolutionStep = 1,
            fitMode = PageFitMode.WIDTH,
            pageTurnEffect = PageTurnEffect.CURL,
            viewModel = viewModel,
            onIntent = onIntent,
            onGeometry = { measured -> geometry = measured },
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                // A page only ever leaves the column on a pinch, and a pinch is only ever two
                // fingers: one finger on a page belongs to the column and must keep scrolling it.
                // The same rule the paged reader applies to its pager, for the same reason.
                .pointerInput(pageIndex) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoom = 1f
                        // Taken once, on the first movement, and kept for the rest of the gesture:
                        // re-aiming at a moving centroid every frame would chase the fingers rather
                        // than hold the page under them.
                        var anchor: ColumnMagnify? = null
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } < 2) continue
                            val zoomChange = event.calculateZoom()
                            if (zoomChange == 1f) continue
                            zoom *= zoomChange

                            if (anchor == null) {
                                val drawn = geometry.drawn
                                if (drawn.width <= 0f || drawn.height <= 0f) continue
                                val centroid = event.calculateCentroid(useCurrent = true)
                                anchor = ColumnMagnify(
                                    // The item's box *is* the page — width-fitted with the item's
                                    // aspect ratio — so the tap's own position is already the page
                                    // fraction it landed on.
                                    anchorFraction = Offset(
                                        (centroid.x / drawn.width).coerceIn(0f, 1f),
                                        (centroid.y / drawn.height).coerceIn(0f, 1f),
                                    ),
                                    anchorView = Offset(
                                        centroid.x,
                                        centroid.y + viewportOffsetOf(pageIndex, listState),
                                    ),
                                    zoom = 1f,
                                    columnDrawn = drawn,
                                )
                            }
                            anchor?.let { onMagnify(it.copy(zoom = zoom)) }

                            if (zoom > ZOOMED_THRESHOLD) {
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        // Back to a single page-width and the page returns to the column, which is
                        // how a reader says they have finished with it.
                        if (zoom <= ZOOMED_THRESHOLD) anchor?.let { onMagnify(it.copy(zoom = zoom)) }
                    }
                }
        )
    }
}

/**
 * Where this page's own top edge sits in the reader's viewport.
 *
 * A gesture reports its position relative to the node it landed on, and the node here is the page —
 * which is a whole screenful tall and scrolled to wherever the reader is. The overlay is positioned
 * in the viewport's coordinates, so the two have to be reconciled before a pinch point can be handed
 * from one to the other.
 */
private fun viewportOffsetOf(pageIndex: Int, listState: LazyListState): Float {
    val layout = listState.layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { info -> info.index == pageIndex } ?: return 0f
    return (item.offset - layout.viewportStartOffset).toFloat()
}

/**
 * The page opened over the column, at whatever magnification the reader has asked for.
 *
 * The gestures are the paged reader's, unchanged — a pinch and a drag to look around, a double-tap
 * to frame a speech bubble or to come back out of a zoom, a tap to put the page back.
 */
@Composable
private fun PageInspection(
    pageIndex: Int,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    inspection: Inspection,
    scope: CoroutineScope,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    hapticsEnabled: Boolean,
) {
    val geometry = inspection.geometry

    // Re-anchors an in-flight magnification when the container it was measured against changes.
    LaunchedEffect(inspection.reference) { inspection.rebase() }


    val layerTransform = PageTransform(
        scale = layerScaleFor(inspection.transform.scale, geometry.drawn, geometry.reference),
        offset = inspection.transform.offset,
    )

    /** Animates to [target], cancelling any zoom already in flight. */
    fun zoomTo(target: ZoomTarget) {
        val bitmap = geometry.bitmap ?: return
        if (geometry.container.width <= 0 || geometry.drawn.width <= 0f) return
        val endOnScreen = transformFor(
            target,
            geometry.container,
            geometry.drawn,
            bitmap.width,
            bitmap.height,
        )
        inspection.animateTo(
            scope = scope,
            end = PageTransform(
                scale = referenceScaleFor(endOnScreen.scale, geometry.drawn, geometry.reference),
                offset = endOnScreen.offset,
            ),
        )
    }

    /**
     * Magnifies whatever is at [fraction] of the page: a speech bubble or panel if one is there, the
     * point itself if not.
     */
    fun frameAt(fraction: Offset) {
        val bitmap = geometry.bitmap ?: return
        val pixel = Offset(fraction.x * bitmap.width, fraction.y * bitmap.height)
        scope.launch {
            val framed = if (inspection.bubbleZoom) {
                viewModel.bubbleRegionAt(bitmap, pixel.x.toInt(), pixel.y.toInt())
                    ?.let { region ->
                        zoomTargetForRegion(
                            region = region.bounds,
                            container = geometry.container,
                            drawn = geometry.drawn,
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height,
                        )
                    }
                    // A region covering nearly the whole page is not worth framing: the plain
                    // magnification is both smaller and more useful.
                    ?.takeIf { it.scale >= MIN_USEFUL_ZOOM }
            } else {
                null
            }

            if (framed != null) {
                if (hapticsEnabled) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                zoomTo(framed)
                return@launch
            }

            zoomTo(
                ZoomTarget(
                    anchorFraction = fraction,
                    // The tapped place is brought to the middle of the screen rather than held under
                    // a finger that is no longer there — it is a double-tap, not a drag.
                    anchorView = Offset(
                        geometry.container.width / 2f,
                        geometry.container.height / 2f,
                    ),
                    scale = DOUBLE_TAP_SCALE,
                ),
            )
        }
    }


    // Both of the column's gestures have to wait for this page to render and report where it is
    // drawn: a pinch cannot be carried on without knowing how big the overlay draws it, and a
    // double-tap cannot be framed without knowing where the page is.
    LaunchedEffect(inspection.pendingMagnify, inspection.pendingFrame, geometry) {
        inspection.applyPendingMagnify()

        val fraction = inspection.pendingFrame ?: return@LaunchedEffect
        if (geometry.bitmap == null) return@LaunchedEffect
        inspection.pendingFrame = null
        frameAt(fraction)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(pageIndex) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed < 2 && inspection.transform.scale <= ZOOMED_THRESHOLD) continue

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange == 1f && panChange == Offset.Zero) continue

                        inspection.cancelAnimation()
                        val nextScale =
                            (inspection.transform.scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                        val nextLayerScale =
                            layerScaleFor(nextScale, geometry.drawn, geometry.reference)
                        val nextOffset =
                            if (overflows(geometry.drawn, geometry.container, nextLayerScale)) {
                                clampPan(
                                    layerTransform.offset + panChange,
                                    geometry.drawn,
                                    geometry.container,
                                    nextLayerScale,
                                )
                            } else {
                                Offset.Zero
                            }
                        inspection.transform = PageTransform(nextScale, nextOffset)
                        inspection.reference = geometry.reference

                        event.changes.forEach { change ->
                            if (change.positionChanged()) change.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    if (inspection.transform.scale <= ZOOMED_THRESHOLD) inspection.close()
                }
            }
            .pointerInput(pageIndex) {
                detectTapGestures(
                    onTap = { inspection.close() },
                    onDoubleTap = { position ->
                        if (inspection.transform.scale > 1.02f) {
                            zoomTo(
                                identityTarget(
                                    bitmapWidth = geometry.bitmap?.width ?: 0,
                                    bitmapHeight = geometry.bitmap?.height ?: 0,
                                    container = geometry.container,
                                ),
                            )
                        } else {
                            val pixel = viewPointToPixel(
                                viewPoint = position,
                                container = geometry.container,
                                drawn = geometry.drawn,
                                transform = layerTransform,
                                bitmapWidth = geometry.bitmap?.width ?: 0,
                                bitmapHeight = geometry.bitmap?.height ?: 0,
                            )
                            frameAt(
                                pixel?.let {
                                    pixelToPageFraction(
                                        it,
                                        geometry.bitmap?.width ?: 0,
                                        geometry.bitmap?.height ?: 0,
                                    )
                                } ?: Offset(0.5f, 0.5f),
                            )
                        }
                    },
                )
            },
    ) {
        ReaderPage(
            pageIndex = pageIndex,
            pageOffset = { 0f },
            isCurrentPage = true,
            layerTransform = layerTransform,
            resolutionStep = inspection.resolutionStep,
            fitMode = PageFitMode.PAGE,
            pageTurnEffect = PageTurnEffect.CURL,
            viewModel = viewModel,
            onIntent = onIntent,
            onGeometry = { measured: PageGeometry -> inspection.report(pageIndex, measured) },
        )
    }
}

/**
 * The page opened over the column, and the magnification it is being read at.
 *
 * A holder rather than a handful of `remember`s inside one composable, because two of them drive it:
 * the page in the column starts the magnification with a pinch and keeps feeding it for as long as
 * that gesture lasts, and the page opened over the column takes the gesture over once it ends. Both
 * write the same transform, so the page never jumps between them mid-gesture — which is what would
 * happen if the opened page tried to take over the gesture that opened it.
 */
private class Inspection {
    /** The page opened over the column, or `null` while the reader is scrolling. */
    var page by mutableStateOf<Int?>(null)
        private set

    var transform by mutableStateOf(PageTransform.Identity)
    var reference by mutableStateOf(Size.Zero)
    var geometry by mutableStateOf(PageGeometry())
        private set

    /**
     * The page [geometry] describes.
     *
     * A second field rather than a comparison against [page], because the two are briefly different
     * on purpose: opening a page sets [page] before the new page has drawn anything, and for those
     * frames the geometry still belongs to whatever was open before. Aiming a zoom at it would aim
     * at the wrong page's size.
     */
    private var geometryPage by mutableStateOf<Int?>(null)

    /** Whether a double-tap looks for a speech bubble first. Read from settings by the caller. */
    var bubbleZoom by mutableStateOf(true)

    /** The opened page reporting where it is drawn and how big. */
    fun report(pageIndex: Int, measured: PageGeometry) {
        geometry = measured
        geometryPage = pageIndex
    }

    /** Whether the opened page has said enough for a zoom to be aimed at it. */
    fun isDrawn(): Boolean =
        geometryPage == page && geometry.bitmap != null && geometry.drawn.width > 0f

    /**
     * A pinch that opened this page before it had drawn anything.
     *
     * Held rather than applied because the handover needs a number only the page can supply — the
     * size the overlay draws it at — and until that arrives there is no honest way to keep the page
     * the size it already was.
     */
    var pendingMagnify: ColumnMagnify? by mutableStateOf(null)
        private set

    /**
     * A place in a page the column asked to have framed, as a fraction of the page.
     *
     * Held rather than acted on because the page has not been rendered yet when the double-tap
     * arrives: there is nothing to frame until the renderer has said where on screen the page is
     * drawn. The opened page clears it as soon as it can honour it.
     */
    var pendingFrame: Offset? by mutableStateOf(null)

    private val clock = Animatable(0f)
    private var from by mutableStateOf(PageTransform.Identity)
    private var to by mutableStateOf(PageTransform.Identity)
    private var job by mutableStateOf<Job?>(null)

    val resolutionStep: Int
        get() = transform.scale.coerceIn(1f, MAX_RENDER_SCALE).toInt().coerceAtLeast(1)

    /**
     * Opens [index] over the column, carrying on from the pinch that asked for it.
     *
     * The page must not move or change size as it is handed over, which is why the transform is
     * built from [ColumnMagnify] — what the column was showing — rather than from the pinch's own
     * magnification applied to the overlay's quite different idea of "unzoomed".
     */
    fun magnify(index: Int, pinch: ColumnMagnify) {
        if (page != index) {
            page = index
            reference = Size.Zero
            transform = PageTransform.Identity
            pendingFrame = null
            pendingMagnify = null
        }
        // Back to a single page-width: the reader has finished with the page, not merely stopped
        // moving their fingers.
        if (pinch.zoom <= ZOOMED_THRESHOLD) {
            close()
            return
        }
        cancelAnimation()
        pendingMagnify = pinch
        applyPendingMagnify()
    }

    /**
     * Turns a parked pinch into a transform, now that the page has said how big it draws.
     *
     * Does nothing until then, which is what makes it safe to call on every frame of a pinch: the
     * first call that finds the geometry ready is the one that lands the page exactly where the
     * column had it, and every call after that starts from that transform.
     */
    fun applyPendingMagnify() {
        val pending = pendingMagnify ?: return
        val bitmap = geometry.bitmap ?: return
        if (!isDrawn()) return
        if (geometry.container.width <= 0 || geometry.container.height <= 0) return

        pendingMagnify = null
        val onScreen = transformFor(
            target = pending.toTarget(geometry.drawn),
            container = geometry.container,
            drawn = geometry.drawn,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
        )
        transform = PageTransform(
            scale = referenceScaleFor(onScreen.scale, geometry.drawn, geometry.reference),
            offset = onScreen.offset,
        )
        reference = geometry.reference
    }

    /** Opens [index] over the column at fit, asking for [fraction] of it to be framed. */
    fun frame(index: Int, fraction: Offset) {
        if (page != index) {
            page = index
            reference = Size.Zero
            transform = PageTransform.Identity
            pendingMagnify = null
        }
        pendingFrame = fraction
    }

    fun close() {
        cancelAnimation()
        pendingFrame = null
        pendingMagnify = null
        page = null
        transform = PageTransform.Identity
        reference = Size.Zero
    }

    fun cancelAnimation() = job?.cancel()

    /** Re-anchors an in-flight magnification once the opened page reports what it measured. */
    fun rebase() {
        if (reference.width > 0f && geometry.reference.width > 0f &&
            reference != geometry.reference
        ) {
            transform = rebaseTransform(transform, reference, geometry.reference, geometry.container)
        }
        reference = geometry.reference
    }

    fun animateTo(scope: CoroutineScope, end: PageTransform) {
        job?.cancel()
        from = transform
        to = end
        job = scope.launch {
            clock.snapTo(0f)
            clock.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = ZOOM_TWEEN_MS, easing = FastOutSlowInEasing),
            ) {
                transform = lerpTransform(from, to, value)
                reference = geometry.reference
            }
        }
    }
}

