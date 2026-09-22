package com.mylibrary.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.ui.component.ErrorState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
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
 *
 * **Why the gestures live here and not on the page.** They used to be attached to each page, and on
 * a device that turned out to be wrong in a way no unit test could see: a tap in the middle of the
 * screen was delivered to a *neighbouring* page's node, so a double-tap zoomed a page that was not
 * on screen — indistinguishable, to the reader, from the double-tap doing nothing at all. The
 * reader therefore owns a single zoom, applies it to the page it is showing, and hangs one set of
 * gesture handlers on the viewport. That is also the honest model: only the page in front of the
 * reader can be zoomed, and turning the page puts the zoom back.
 *
 * **The pager counts the reading order, not the document.** A folder is a series, so the volume
 * before the open one is drawn above it and the volume after below, with a seam page wherever two
 * books meet — the same list the other three presentations draw, built by [readingOrder]. The pages
 * of a neighbouring volume are therefore entries of this pager like any other, and it is the entry
 * keys that keep the reader's place when crossing the seam renumbers them.
 */
@Composable
fun PagedReaderContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val openBookId = state.book?.id

    // The entries this pager turns through: the open book's pages, the volumes either side of it,
    // and the seam where each of them meets this one. Remembered on the ids and counts it is built
    // from rather than on the state object — which is replaced on every page turn — because a new
    // list every frame is a renumbering as far as the pager is concerned, and one it has no reason
    // to scroll through. A neighbour whose document has not been opened yet contributes no units and
    // still gets its seam; see [readingOrder].
    val order = remember(
        openBookId,
        state.totalUnits,
        state.previousSegment,
        state.nextSegment,
        state.sequence?.previous?.id,
        state.sequence?.next?.id,
    ) {
        if (openBookId == null) {
            emptyList()
        } else {
            readingOrder(
                primaryBookId = openBookId,
                primary = List(state.totalUnits) { ReadingEntry.Page(openBookId, it) },
                previous = state.previousNeighbour { id, count ->
                    List(count) { ReadingEntry.Page(id, it) }
                },
                next = state.nextNeighbour { id, count ->
                    List(count) { ReadingEntry.Page(id, it) }
                },
            )
        }
    }

    // Where a unit of the open book sits in that order: the volumes above it come first, and the
    // seam between them takes a slot of its own. -1 when the order holds none of the book's entries,
    // which is what a caller has to be able to tell apart from the first entry.
    fun orderIndexOf(unit: Int): Int = openBookId?.let { order.indexOfPage(it, unit) } ?: -1

    val pagerState = rememberPagerState(
        initialPage = orderIndexOf(state.currentUnit).coerceAtLeast(0),
        pageCount = { order.size },
    )

    // Read through `rememberUpdatedState` rather than taken as a key of the collector below, so that
    // a renumbered order does not restart it: restarted, it would report the entry under the reader
    // again, and after a handoff that is the report that caused the handoff.
    val currentOrder by rememberUpdatedState(order)

    // Pager -> state. What is reported is the *entry* under the reader, not the index they are
    // looking at it through: the same page wears a different index once a neighbour's document opens
    // above it, and an index that moved while the page did not is not a move. Mapping first and then
    // dropping repeats is what stops this and the sync effect below from ping-ponging: this one
    // emits only when the reader's position genuinely changes.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .map { index ->
                when (val entry = currentOrder.entryAt(index)) {
                    // A page of the open book: the intent the reader's position has always been
                    // reported with.
                    is ReadingEntry.Page ->
                        if (entry.bookId == openBookId) {
                            ReaderIntent.PageChanged(entry.pageIndex)
                        } else {
                            // A page of another book of the folder: the reader has crossed into it,
                            // and the page they are looking at is the position they arrive at —
                            // which is what makes the crossing a change of document rather than a
                            // second reader starting at the beginning of the volume.
                            ReaderIntent.EnteredBook(
                                entry.bookId,
                                ReadingLocator.Paged(entry.pageIndex),
                            )
                        }

                    // A seam is between two books, so there is nothing to report: the open book's
                    // position has not changed while the reader stands on it, and the progress bar
                    // saying so is the truthful answer. Reporting the page behind them again would
                    // read as the reader having gone back to it.
                    is ReadingEntry.Seam -> null

                    // Chapters and pages-within-chapters are what the reflowed presentations count
                    // in; this order is built from pages alone. An index past the end of an order
                    // that has just been rebuilt lands here too, and maps to the same nothing.
                    else -> null
                }
            }
            .distinctUntilChanged()
            .collect { intent -> if (intent != null) onIntent(intent) }
    }

    // State -> pager, for jumps that did not come from a swipe: a table-of-contents entry, a search
    // result, or the page slider in the bottom bar. Such a jump names a page of the open book, which
    // is no longer the same thing as an index into the pager — the volumes above come first — so it
    // is resolved through the order to the entry that page occupies, and a book whose entries are
    // not in the order at all has nowhere to scroll to.
    //
    // **A long jump does not animate, and that is a crash fix rather than a taste.** The slider is
    // dragged across a whole comic in one gesture, and `animateScrollToPage` sweeps the viewport
    // through every page between here and there: each one is composed, its native page decoded and a
    // full-page bitmap allocated on the way past. Dragging quickly across a 200-page CBZ therefore
    // queues two hundred decodes for pages the reader never stops on — the out-of-memory crash
    // reported against image files. `scrollToPage` moves the pager in one step, so only the pages at
    // the destination are ever rendered. A one- or two-page move — an outline entry, a tap-zone turn
    // at the end of the order — still animates, because there is nothing in between to pay for.
    LaunchedEffect(state.currentUnit, order) {
        val target = orderIndexOf(state.currentUnit)
        if (target >= 0 && target != pagerState.currentPage) {
            if (abs(target - pagerState.currentPage) <= PAGES_THAT_ANIMATE) {
                pagerState.animateScrollToPage(target)
            } else {
                pagerState.scrollToPage(target)
            }
        }
    }

    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnIntent by rememberUpdatedState(onIntent)
    val currentTapToTurn by rememberUpdatedState(state.settings.tapToTurnPages)
    val currentReverseTapZones by rememberUpdatedState(state.settings.reverseTapZones)
    val currentBubbleZoom by rememberUpdatedState(state.settings.bubbleZoom)
    val currentHapticsEnabled by rememberUpdatedState(state.settings.hapticsEnabled)
    // Through `rememberUpdatedState` like the settings above, and for the same reason: the gesture
    // loop is not restarted when the direction changes, so a plain `val` read inside it is whatever
    // the direction was when the book was opened. That made the setting look inert — the pages
    // turned one way and the taps stayed the other — until the reader closed the book and reopened
    // it, which is exactly the kind of bug that survives a review and dies the moment someone
    // changes the setting while looking at the screen.
    val currentIsRtl by rememberUpdatedState(isRtl)

    // The zoom of the page on screen, in the reference-render space described on [PageTransform]:
    // a magnification that does not change when the page is re-rendered more sharply.
    var transform by remember { mutableStateOf(PageTransform.Identity) }
    var referenceWhenSet by remember { mutableStateOf(Size.Zero) }

    // The animation, as a clock from 0 to 1 plus the two transforms it moves between. The
    // interpolated value is written into `transform` itself rather than into a separate layer, so a
    // pinch that interrupts an animation takes over from exactly where it had got to.
    val zoomClock = remember { Animatable(0f) }
    var zoomFrom by remember { mutableStateOf(PageTransform.Identity) }
    var zoomTo by remember { mutableStateOf(PageTransform.Identity) }
    var zoomJob by remember { mutableStateOf<Job?>(null) }

    // What the page on screen is and how big it is drawn, published by that page as it renders. The
    // reader needs it to turn a tap into a page pixel and a page pixel back into a place on screen.
    var geometry by remember { mutableStateOf(PageGeometry()) }

    // Which page `geometry` describes. Published beside it so that a double-tap can tell whether the
    // page it is about to read pixels from is still the page under the reader: turning the page does
    // not clear the geometry in the same frame the page changes, and a bubble detected from the old
    // page's pixels is a bubble drawn from the page the reader has already left.
    var geometryPage by remember { mutableStateOf(-1) }

    // A detected speech bubble drawn enlarged over the page it came from, until a tap or back
    // clears it. `null` means an ordinary page is on screen and gestures behave accordingly.
    var bubbleOverlay by remember { mutableStateOf<BubbleOverlay?>(null) }

    // The detection in flight, so a page turn can cancel it. The detector copies the page's pixels
    // off the main thread, and without this a turn made while it ran would let its answer land on
    // the page that replaced the one it was reading.
    var bubbleJob by remember { mutableStateOf<Job?>(null) }

    // Bucketed to whole steps so that a continuous pinch does not request a new render on every
    // frame; only crossing 2x or 3x triggers a sharper render.
    val resolutionStep = remember(transform.scale) {
        transform.scale.coerceIn(1f, MAX_RENDER_SCALE).toInt().coerceAtLeast(1)
    }

    val layerTransform = PageTransform(
        scale = layerScaleFor(transform.scale, geometry.drawn, geometry.reference),
        offset = transform.offset,
    )

    // A change of the slot's own size — a rotation, a split screen — moves the ground the pan was
    // measured against, so it is rebased once, here rather than in composition.
    LaunchedEffect(geometry.reference) {
        if (referenceWhenSet.width > 0f && geometry.reference.width > 0f &&
            referenceWhenSet != geometry.reference
        ) {
            transform = rebaseTransform(
                transform,
                referenceWhenSet,
                geometry.reference,
                geometry.container,
            )
        }
        referenceWhenSet = geometry.reference
    }

    // Turning the page puts the zoom back: the page that was framed is no longer on screen, and a
    // magnification carried into the next page would be a magnification of a page nobody chose.
    // The geometry goes with it, so a double-tap made before the new page has rendered cannot read
    // the old page's pixels, and any bubble still being detected is cancelled rather than allowed to
    // land on the page that replaced it.
    LaunchedEffect(pagerState.currentPage) {
        zoomJob?.cancel()
        bubbleJob?.cancel()
        bubbleOverlay = null
        geometry = PageGeometry()
        geometryPage = -1
        transform = PageTransform.Identity
        referenceWhenSet = Size.Zero
    }

    /** Animates to [target], cancelling any zoom already in flight. */
    fun zoomTo(target: ZoomTarget) {
        val bitmap = geometry.bitmap ?: return
        val container = geometry.container
        val drawn = geometry.drawn
        if (container.width <= 0 || container.height <= 0 || drawn.width <= 0f) return

        // The destination is worked out in the scale the layout sees — a region framed to 85% of the
        // viewport is a statement about pixels on screen — and stored in the scale that survives a
        // re-render.
        val endOnScreen = transformFor(target, container, drawn, bitmap.width, bitmap.height)
        val end = PageTransform(
            scale = referenceScaleFor(endOnScreen.scale, drawn, geometry.reference),
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
                referenceWhenSet = geometry.reference
            }
        }
    }

    /**
     * Zooms into whatever is under [position]: a speech bubble enlarged in place if one is there,
     * and a fixed magnification about the tapped point if not.
     *
     * A bubble now belongs to the overlay rather than to the page's zoom. The camera zoom this used
     * to do answered "double-tap" with a *page* move: everything in the frame — the panel around
     * the bubble, the art either side of it — came with the magnification, and what the reader had
     * asked for was only the bubble, bigger, with the page it lives on left exactly as it was. So a
     * detected region is cropped out of the render and drawn back over its own place on screen,
     * enlarged; the page below never moves. Only when nothing is detected does the tapped-point
     * zoom remain, which is the same double-tap every other comic reader falls back to.
     */
    fun zoomIntoPage(position: Offset) {
        val page = pagerState.currentPage
        // The geometry belongs to the page under the reader and no other. After a turn it is empty
        // until the new page has rendered, and reading it then would crop the bubble out of the
        // previous page's pixels — the bubble that appeared "from the page before", at the place the
        // reader had double-tapped on the new one.
        if (geometryPage != page) return
        val bitmap = geometry.bitmap
        val container = geometry.container
        val drawn = geometry.drawn
        if (container.width <= 0 || container.height <= 0 || drawn.width <= 0f) return

        bubbleJob?.cancel()
        bubbleJob = scope.launch {
            val overlay = if (bitmap != null && currentBubbleZoom) {
                viewPointToPixel(
                    viewPoint = position,
                    container = container,
                    drawn = drawn,
                    transform = layerTransform,
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height,
                )
                    ?.let { point ->
                        viewModel.bubbleRegionAt(bitmap, point.x.toInt(), point.y.toInt())
                    }
                    // A region covering much of the page is not a bubble to enlarge: it is a
                    // panel, and the whole-page plain zoom is both smaller and more useful.
                    ?.takeIf { region ->
                        region.bounds.area <= OVERLAY_MAX_PAGE_SHARE * bitmap.width * bitmap.height
                    }
                    ?.let { region ->
                        bubbleOverlayFor(
                            bitmap = bitmap,
                            region = region,
                            container = container,
                            drawn = drawn,
                        )
                    }
            } else {
                null
            }

            // The detection ran off the main thread and the reader may have turned the page while
            // it did; an answer about a page that is no longer on screen is thrown away rather than
            // painted over the page that replaced it.
            if (pagerState.currentPage != page || geometryPage != page) return@launch

            if (overlay != null) {
                if (currentHapticsEnabled) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                bubbleOverlay = overlay
                return@launch
            }

            // Nothing detected: the whole page, or a tap that landed on a drawing. Zoom about the
            // point that was tapped — the reader aimed there, and a zoom that lands somewhere else
            // is the one thing a double-tap must not do.
            val pixel = viewPointToPixel(
                viewPoint = position,
                container = container,
                drawn = drawn,
                transform = layerTransform,
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

    Box(
        modifier = modifier
            .fillMaxSize()
            // **The drag is claimed only when the page needs it.** `detectTransformGestures` would
            // be the obvious thing to write here and it was, for two releases: it consumes every
            // drag it sees, and a `HorizontalPager` can only turn a page from a drag that reaches
            // it — which made a reader whose pages could be turned by tapping the edges but not by
            // swiping. So this loop takes a drag only when it is a pinch (two fingers) or when the
            // page is already zoomed, where panning is the whole point. A single finger on a page at
            // 1× is left alone, and the pager scrolls it.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed < 2 && transform.scale <= ZOOMED_THRESHOLD) continue

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange == 1f && panChange == Offset.Zero) continue

                        zoomJob?.cancel()
                        val nextScale = (transform.scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                        val nextLayerScale = layerScaleFor(
                            nextScale,
                            geometry.drawn,
                            geometry.reference,
                        )
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
                        transform = PageTransform(nextScale, nextOffset)
                        referenceWhenSet = geometry.reference

                        // Claimed only now, having decided this gesture is ours, so the pager never
                        // sees a drag it should have handled.
                        event.changes.forEach { change ->
                            if (change.positionChanged()) change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { position ->
                        // A zoomed page is being inspected, not read, so a tap only reveals the
                        // toolbar; turning the page under the reader's fingers would lose the place
                        // they zoomed in to look at.
                        if (transform.scale > 1f || !currentTapToTurn) {
                            currentOnIntent(ReaderIntent.ToggleChrome)
                            return@detectTapGestures
                        }
                        val zone = tapZoneFor(
                            x = position.x,
                            width = size.width.toFloat(),
                            isRtl = currentIsRtl,
                            reversed = currentReverseTapZones,
                        )
                        // The page-turn zones move the pager by one *entry*, because the entries are
                        // no longer only this book's pages: the seam before the next volume is one,
                        // and so is every page of that volume. Asking the document for its own next
                        // page would step over both — and at the end of a volume it would ask for a
                        // page the open document does not have. Only at the ends of the order is
                        // there no entry to turn to, and there the intent the reader always had is
                        // the only answer left.
                        when (zone) {
                            TapZone.PREVIOUS -> {
                                if (currentHapticsEnabled) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                val previous = pagerState.currentPage - 1
                                if (previous >= 0) {
                                    scope.launch { pagerState.animateScrollToPage(previous) }
                                } else {
                                    currentOnIntent(ReaderIntent.PreviousUnit)
                                }
                            }

                            TapZone.NEXT -> {
                                if (currentHapticsEnabled) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                val next = pagerState.currentPage + 1
                                if (next < order.size) {
                                    scope.launch { pagerState.animateScrollToPage(next) }
                                } else {
                                    currentOnIntent(ReaderIntent.NextUnit)
                                }
                            }

                            TapZone.CENTER -> currentOnIntent(ReaderIntent.ToggleChrome)
                        }
                    },
                    onDoubleTap = { position ->
                        // A double-tap never reaches `onTap`, so the page-turn zones are untouched
                        // by sharing the surface with this.
                        if (transform.scale > 1.02f) {
                            zoomTo(
                                identityTarget(
                                    bitmapWidth = geometry.bitmap?.width ?: 0,
                                    bitmapHeight = geometry.bitmap?.height ?: 0,
                                    container = geometry.container,
                                ),
                            )
                        } else {
                            zoomIntoPage(position)
                        }
                    },
                )
            },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = PAGE_SPACING,
            beyondViewportPageCount = 1,
            // Every entry is filed under a key naming the book it belongs to, which is what keeps the
            // reader's place when the order is renumbered: crossing a seam rewrites which book is
            // open and every index around the reader moves, while the page they are looking at does
            // not. Without keys the pager would hold the index and land them on a page of the volume
            // they had just finished. `getOrNull` because the order can shrink between the pager
            // asking for a key and the composition that changes its page count.
            key = { index -> order.getOrNull(index)?.readingKey() ?: "missing:$index" },
        ) { pageIndex ->
            when (val entry = order.getOrNull(pageIndex)) {
                is ReadingEntry.Page -> ReaderPage(
                    bookId = entry.bookId,
                    pageIndex = entry.pageIndex,
                    // The slot's own index, not the page's: this is where the page sits in the order,
                    // and a turn transform is measured between neighbouring slots. It is also the one
                    // place the two are still told apart — below, `entry.pageIndex` is the page of
                    // the document, and it is not the same number once a volume is drawn above this
                    // one. Read inside `graphicsLayer`, so a page's turn transform tracks the finger
                    // without recomposing the page on every frame of the drag.
                    pageOffset = { pagerState.offsetOf(pageIndex) },
                    isCurrentPage = pageIndex == pagerState.currentPage,
                    layerTransform = layerTransform,
                    resolutionStep = resolutionStep,
                    fitMode = state.settings.pageFitMode,
                    pageTurnEffect = state.settings.pageTurnEffect,
                    viewModel = viewModel,
                    onIntent = onIntent,
                    onGeometry = { page ->
                        if (pageIndex == pagerState.currentPage) {
                            geometry = page
                            geometryPage = pageIndex
                        }
                    },
                )

                is ReadingEntry.Seam -> ReaderSeamPage(
                    fromTitle = state.titleOf(entry.fromBookId).orEmpty(),
                    toTitle = state.titleOf(entry.toBookId).orEmpty(),
                    // Only the seam ahead of the reader offers to open its book, and only when that
                    // book is one the reader could not be carried into: a book that could be is
                    // already open and drawn below the seam. The seam behind the reader offers
                    // nothing at all — the volume behind them is finished, and its saved position,
                    // not its first page, is where they would want to arrive.
                    onOpenNext = if (
                        entry.fromBookId == openBookId &&
                        entry.toBookId in state.unavailableNeighbours
                    ) {
                        { onIntent(ReaderIntent.OpenNeighbour(entry.toBookId)) }
                    } else {
                        null
                    },
                )

                // Chapters and pages-within-chapters are the units of the reflowed presentations;
                // this order is built from pages alone. `null` is an index past the end of an order
                // that has just been rebuilt under the pager.
                is ReadingEntry.Chapter, is ReadingEntry.TextPage, null -> Unit
            }
        }

        // The enlarged bubble floats above everything, scrim and all, and swallows taps until it
        // is dismissed: a tap that closes it must not also turn the page beneath it.
        bubbleOverlay?.let { overlay ->
            BubbleOverlayLayer(
                overlay = overlay,
                onDismiss = { bubbleOverlay = null },
            )
        }
    }
}

/**
 * How far a page is from the settled position, in pages, signed.
 *
 * Positive means the page is arriving from the trailing edge of the reading direction. The value is
 * continuous during a drag, which is what every effect in [pageTurnTransform] is driven by.
 *
 * The index is checked against the pager's *current* page count because a slider dragged quickly
 * through a folder's reading order rebuilds that order underneath the pager: opening or closing the
 * volume either side changes how many entries there are, and a page composed under the old
 * numbering can still re-run its layer lambda after the count has changed. `getOffsetDistanceInPages`
 * throws for an index outside the pager, and a throw in a draw pass is a crash; such a page draws
 * settled instead, which is what a page on its way out of the pager looks like anyway.
 */
private fun PagerState.offsetOf(pageIndex: Int): Float =
    if (pageIndex in 0 until pageCount) getOffsetDistanceInPages(pageIndex) else 0f

/**
 * Where the page on screen is drawn, and what it is.
 *
 * Published by the page as it renders, because only the page knows the bitmap it was handed and the
 * size it is drawn at — and the reader's gestures need both to turn a tap into a page pixel. Immutable
 * and compared by value, so a page that republishes the same geometry costs nothing.
 */
@Immutable
internal data class PageGeometry(
    val bitmap: ImageBitmap? = null,
    val drawn: Size = Size.Zero,
    val reference: Size = Size.Zero,
    val container: IntSize = IntSize.Zero,
)

/**
 * One page: the turn transform, the zoom it is drawn with, and the pixels.
 *
 * Deliberately without gestures — see the note on [PagedReaderContent]. This composable renders what
 * it is told to render and reports what it measured.
 */
@Composable
internal fun ReaderPage(
    bookId: Long,
    pageIndex: Int,
    pageOffset: () -> Float,
    isCurrentPage: Boolean,
    layerTransform: PageTransform,
    resolutionStep: Int,
    fitMode: PageFitMode,
    pageTurnEffect: PageTurnEffect,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    onGeometry: (PageGeometry) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val currentOnIntent by rememberUpdatedState(onIntent)

    // What the document says its page looks like. Needed before the pixels are: a width-fitted
    // render is as tall as the page's own proportions make it, and an actual-size render is the
    // page's own dimensions. `null` until it arrives, and for a document that cannot report one —
    // which is why *whether* the question has been answered is tracked separately from its answer.
    // Keyed on the book as well as the page, because page 12 of two volumes is two different pages,
    // and this composable is handed the slot of whichever entry the pager puts there.
    var pageSizeAnswered by remember(bookId, pageIndex) { mutableStateOf(false) }
    val pageSize by produceState<PageSize?>(initialValue = null, bookId, pageIndex) {
        value = viewModel.pageSize(bookId, pageIndex)
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
        bookId,
        pageIndex,
        renderBox,
    ) {
        if (renderBox.width > 0 && renderBox.height > 0) {
            value = viewModel.renderPage(
                bookId = bookId,
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

    val referenceDrawnSize = remember(drawnSize, fitMode, pageSize) {
        referenceDrawnSizeFor(fitMode, drawnSize, pageSize)
    }

    // `isCurrentPage` is among the keys so that a page pre-composed as a neighbour republishes the
    // moment it becomes the one on screen: its bitmap and size have not changed, so without this the
    // effect would not run again and the reader's geometry would go on describing the page just left.
    // The callback decides whether the report belongs to the reader — the paged reader accepts only
    // the current page's, while a page of the scrolling column publishes for its own pinch.
    LaunchedEffect(bitmap, drawnSize, referenceDrawnSize, containerSize, isCurrentPage) {
        onGeometry(PageGeometry(bitmap, drawnSize, referenceDrawnSize, containerSize))
    }

    // A change of fit mode re-frames the page; the reader's own zoom is reset by the page change
    // that a re-frame usually comes with, and by the fit-mode effect in the reader.
    val currentPageOffset = pageOffset

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The turn transform wraps the page rather than sharing a modifier chain with the zoom:
            // the page must be free to swing past its own slot, while the *zoom* stays clipped to it.
            .graphicsLayer {
                // A curl is the page bending, and the page draws that itself — see [drawPaperPage].
                // Rotating the slot as well would bend it twice, and painting the slot's shadow
                // would put a second one over the bend's own. The slot is handed the identity
                // instead, which is continuous with the rotation it replaces: both go to nothing as
                // the page settles, so nothing jumps at the moment a gesture starts.
                val turn = if (paperCurlOwns(pageTurnEffect, currentPageOffset())) {
                    PageTurnTransform.Identity
                } else {
                    pageTurnTransform(currentPageOffset(), pageTurnEffect, isRtl)
                }
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
                if (!paperCurlOwns(pageTurnEffect, currentPageOffset())) {
                    val alpha = pageTurnTransform(currentPageOffset(), pageTurnEffect, isRtl).shadeAlpha
                    if (alpha > 0f) {
                        val pivot = pageTurnPivotX(currentPageOffset(), isRtl)
                        drawRect(brush = shadeBrush(size, pivot, alpha))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PAGE_BACKGROUND)
                // The page is drawn inside its own slot; without this a page wider than the viewport
                // — which is exactly what actual size produces — would paint over its neighbours.
                .clipToBounds()
                .onSizeChanged { size -> containerSize = size },
            contentAlignment = Alignment.Center,
        ) {
            when (val render = renderState) {
                // Drawn by hand rather than by `Image`, because a curl has to re-draw the page's
                // pixels column by column and `Image` can only place the whole of it. The flat case
                // is the single `drawImage` call `Image` was making anyway, so a page nobody is
                // turning is drawn exactly as it was.
                is PageRenderState.Ready -> Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            // Only the page in front of the reader carries the zoom; a neighbour
                            // drawn with it would arrive on screen already magnified.
                            scaleX = if (isCurrentPage) layerTransform.scale else 1f,
                            scaleY = if (isCurrentPage) layerTransform.scale else 1f,
                            translationX = if (isCurrentPage) layerTransform.offset.x else 0f,
                            translationY = if (isCurrentPage) layerTransform.offset.y else 0f,
                        ),
                ) {
                    // Read here, in the draw phase, and never while composing: the offset changes on
                    // every frame of a drag, and a value read during composition would recompose
                    // the page — bitmap decode, geometry and all — for each one of them.
                    val page = render.image
                    val frame = pageFrame(
                        container = size,
                        drawn = drawnPageSize(
                            bitmapWidth = page.width,
                            bitmapHeight = page.height,
                            container = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            fitMode = fitMode,
                        ),
                    )
                    if (paperCurlOwns(pageTurnEffect, currentPageOffset())) {
                        val roll = paperCurlRoll(currentPageOffset())
                        drawPaperCurl(
                            page = page,
                            frame = frame,
                            curl = paperCurl(
                                roll = roll,
                                frame = frame,
                                hingeAtLeft = pageTurnPivotX(currentPageOffset(), isRtl) < 0.5f,
                            ),
                        )
                    } else {
                        drawSettledPage(page, frame)
                    }
                }

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

/** The shadow a lifting page casts on itself, from its hinge outwards. */
private fun shadeBrush(size: Size, pivot: Float, alpha: Float): Brush =
    Brush.horizontalGradient(
        colors = listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
        startX = if (pivot < 0.5f) 0f else size.width,
        endX = if (pivot < 0.5f) size.width else 0f,
    )

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
 * **Every mode is capped by [capRenderBox], and the reason is [resolutionStep].** The step exists so
 * that a zoomed page is re-rendered more sharply, so the box is the viewport *multiplied by the
 * step* — which is not bounded by the device at all: 1080×2400 at step 3 is a 3240×7200 box, and a
 * portrait comic page fills it with roughly 63 MB of pixels against a 64 MB cache. One page would
 * take the whole budget, and the reader would re-render every page on every turn.
 *
 * Capping does not shrink the page on screen, which is what makes it safe for page fit. The size
 * anything is *drawn* at comes from [drawnPageSize], which fits the bitmap into the viewport — so a
 * box scaled down by a uniform factor yields a bitmap scaled down by the same factor, and the page
 * occupies exactly the same pixels with less sharpness above what the screen can show. What the
 * file controls still gets bounded too, for the original reason: a PDF page tree may declare any
 * MediaBox it likes, and a 30000-point page is a real thing to find in a malformed file.
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
    // Capped on every path, including this one: the fallback is the viewport *times the step*, and
    // at step 3 that is three times the device's width and height in each direction.
    val page = pageSize?.takeIf { it.width > 0 && it.height > 0 }
        ?: return capRenderBox(viewport.width, viewport.height)

    return when (fitMode) {
        PageFitMode.PAGE -> capRenderBox(viewport.width, viewport.height)

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
 * What the page is drawn at when the render is at the reader's *base* resolution.
 *
 * The stored zoom is measured against this rather than against whatever render happens to be on
 * screen, so that crossing a resolution step changes the sharpness and nothing else. Get it wrong
 * and the page changes size in the middle of a pinch, which is the one thing a re-render must never
 * do — and it is not a hypothetical: this returned `drawn / step` for every fit, which is right for
 * two of the three and half the truth for the third.
 *
 * Page fit and width fit size the page from the *viewport*, so the drawn size does not depend on the
 * bitmap's resolution at all: for those, base and current are the same rectangle.
 *
 * Actual size is the one fit whose drawn size is the page's own, and there the answer depends on the
 * decoder — which the reader cannot see. Asked for a box twice as large, pdfium renders a bitmap
 * twice as large, while the archive decoder only ever scales *down* (`PageDecoder`) and hands back
 * the same bitmap as before. So the bitmap on screen cannot say what the base resolution draws; the
 * page's declared size can, because the base box *is* the page's own size.
 */
internal fun referenceDrawnSizeFor(
    fitMode: PageFitMode,
    drawnSize: Size,
    pageSize: PageSize?,
): Size = when (fitMode) {
    PageFitMode.PAGE, PageFitMode.WIDTH -> drawnSize

    PageFitMode.ACTUAL_SIZE -> pageSize
        ?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { Size(it.width.toFloat(), it.height.toFloat()) }
        ?: drawnSize
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
 * Where a page of [drawn] size sits inside its [container]: centred, letterboxed by the grey ground.
 *
 * The page is drawn against this rectangle rather than being handed to a `ContentScale`, because the
 * three fit modes are already resolved by the time [drawnPageSize] returns — it produces exactly the
 * rectangle each scale would have produced — and a curl has to know where the sheet's edges are to
 * bend it, which a scale factor cannot tell it.
 */
internal fun pageFrame(container: Size, drawn: Size): Rect {
    val left = (container.width - drawn.width) / 2f
    val top = (container.height - drawn.height) / 2f
    return Rect(left, top, left + drawn.width, top + drawn.height)
}

/**
 * Whether the page, at [scale], is bigger than the frame it is in.
 *
 * Panning only means something when it is: dragging a contained page around would slide it off the
 * screen for no reason.
 */
internal fun overflows(drawn: Size, container: IntSize, scale: Float): Boolean =
    drawn.width * scale > container.width || drawn.height * scale > container.height

private val PAGE_SPACING = 8.dp

/** A neutral ground behind pages, matching how PDF readers present a paper page. */
private val PAGE_BACKGROUND = Color(0xFF2B2B2B)

/**
 * How far a jump may be and still animate.
 *
 * Above this the pager steps straight to its destination instead of sweeping through it: a slider
 * dragged across a comic would otherwise compose, decode and allocate every page in between. See the
 * jump effect in [PagedReaderContent].
 */
private const val PAGES_THAT_ANIMATE = 2

/**
 * How far the virtual camera sits from a turning page, in density-independent units.
 *
 * Larger values flatten the rotation towards an orthographic projection; this is the range that
 * makes a page at seventy degrees look like paper coming off a block rather than a spinning panel.
 */
private const val CAMERA_DISTANCE_DP = 14f

/** Long enough to be read as movement, short enough not to be waited for. */


/**
 * How far past 1× a page must be before a one-finger drag belongs to it rather than to the pager.
 *
 * A hair above 1 rather than exactly 1, because a pinch that has just started leaves the scale at
 * 1.0000001 and a drag at that magnification is still a page turn.
 */

/** 32 MB of ARGB — several screens' worth, and far short of an out-of-memory on a mid-range phone. */
private const val MAX_RENDER_PIXELS = 8_000_000L

/** No single edge longer than this, whatever the page asks for. */
private const val MAX_RENDER_EDGE_PX = 4096
