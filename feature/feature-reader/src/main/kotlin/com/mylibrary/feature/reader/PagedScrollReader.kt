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
import com.mylibrary.core.domain.model.ReadingLocator
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
 * **Where it ends.** A device folder is a series, so the column no longer holds one book: the volume
 * before the open one is drawn above it, the volume after it below, and the blank page where two
 * books meet is an entry of the column like any other. What those entries are, and how a column index
 * turns back into a page of the open book, is [readingOrder]'s business — decided once for all four
 * presentations — and this file only has to say what a unit of a page-image book is.
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
    // The book the column is anchored on: its pages sit between its neighbours', and its id is what
    // tells a page of a neighbour from a page of the open book. `!!` because this presentation is only
    // ever composed with a document open — see the empty-document case in ReaderScreen.
    val bookId = state.book!!.id

    // The order the column scrolls, rather than the open book's pages. A folder is a series, so the
    // volume before this one is drawn above it and the volume after it below, with the blank seam
    // wherever two books meet; [readingOrder] does that once for all four presentations, and this one
    // only has to say what a unit of a page-image book is. Remembered on the ids and the lengths of
    // the books involved, because those are what the order is made of: rebuilt per frame it would
    // hand the list a new list every frame, and `readingKey` — not the index — is what keeps the
    // reader's place through that.
    val order = remember(
        bookId,
        state.totalUnits,
        state.previousSegment?.book?.id,
        state.previousSegment?.unitCount,
        state.nextSegment?.book?.id,
        state.nextSegment?.unitCount,
        state.sequence?.previous?.id,
        state.sequence?.next?.id,
    ) {
        readingOrder(
            primaryBookId = bookId,
            primary = List(state.totalUnits) { ReadingEntry.Page(bookId, it) },
            previous = state.previousNeighbour { id, count ->
                List(count) { ReadingEntry.Page(id, it) }
            },
            next = state.nextNeighbour { id, count ->
                List(count) { ReadingEntry.Page(id, it) }
            },
        )
    }

    val listState = rememberLazyListState(
        // Opened where the reader left off, resolved through the order: the volume above the open book
        // is part of the column too, so the page they were on is not the index they were on.
        initialFirstVisibleItemIndex = order.indexOfPage(bookId, state.currentUnit).coerceAtLeast(0),
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

    // Column -> state. The index is mapped through `readingOrder` before it reaches the ViewModel —
    // the same reasoning the old `contentIndex` clamping carried, with the order in its place: a
    // column index no longer names a page of the open book. The column holds the pages of the volume
    // above and below as well, and the seam between two books belongs to neither — a reader who has
    // scrolled onto one of those has not moved *within* the open book at all, and sending the index on
    // as a page number would step the progress bar by a volume and carry the reader into a book they
    // have not crossed into. Naming the book instead is what turns the crossing into a handover.
    // `distinctUntilChanged` keeps the effect below from ping-ponging with it.
    LaunchedEffect(listState, order) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .map { index -> order.entryAt(index) }
            .distinctUntilChanged()
            .collect { entry ->
                when (entry) {
                    is ReadingEntry.Page ->
                        if (entry.bookId == bookId) {
                            onIntent(ReaderIntent.PageChanged(entry.pageIndex))
                        } else {
                            onIntent(
                                ReaderIntent.EnteredBook(
                                    bookId = entry.bookId,
                                    locator = ReadingLocator.Paged(entry.pageIndex),
                                ),
                            )
                        }

                    // Nothing to report. A seam belongs to neither book, and past either end of the
                    // order there is no entry at all — in both cases the open book's position has not
                    // moved, which is the truthful answer for the progress bar. The remaining unit
                    // kinds cannot be in a page-image book's order.
                    else -> Unit
                }
            }
    }

    // State -> column, for a jump that did not come from scrolling: an outline entry, a search result,
    // a bookmark, or the slider in the bottom bar. Both sides are resolved through the order before
    // they are compared — the state names a page of the open book, while the column sits on an entry
    // that is the same thing only while it belongs to the open book. The seam and the neighbour
    // either side of it are credited with the open book's nearest page instead, which is the rounding
    // `contentIndex` used to do for the end-of-book panel and for the same reason: a renumbered list
    // must not be mistaken for a jump the reader did not ask for. Without it, a neighbour finishing
    // its move into the column would drag the reader off the seam they were reading, or back out of
    // the volume they had just crossed into.
    LaunchedEffect(state.currentUnit, order) {
        val target = order.indexOfPage(bookId, state.currentUnit)
        if (target < 0) return@LaunchedEffect

        val onScreen = listState.firstVisibleItemIndex
        val visible = order.entryAt(onScreen) as? ReadingEntry.Page
        val reading = when {
            visible != null && visible.bookId == bookId -> visible.pageIndex
            onScreen < target -> 0
            else -> state.totalUnits - 1
        }
        if (reading != state.currentUnit) listState.scrollToItem(target)
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
            // The order is a key as well as the opened page: the double-tap resolves what it landed on
            // through it, and a gesture held across a handover must not read a column of the list
            // that no longer exists.
            .pointerInput(inspection.page, order) {
                detectTapGestures(
                    onDoubleTap = { position ->
                        if (inspection.page == null) {
                            pageAt(position, listState, order)?.let { (page, fraction) ->
                                inspection.frame(page, fraction)
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
            items(count = order.size, key = { index -> order[index].readingKey() }) { index ->
                when (val entry = order[index]) {
                    is ReadingEntry.Page -> ScrollPage(
                        bookId = entry.bookId,
                        pageIndex = entry.pageIndex,
                        listState = listState,
                        viewModel = viewModel,
                        onIntent = onIntent,
                        onMagnify = { page, pinch -> inspection.magnify(page, pinch) },
                    )

                    // The end of one book and the start of the next, as the page between them. Every
                    // book of the folder gets one of these on the side it has a neighbour — the seam
                    // is what makes the column continuous rather than a document with a panel stuck
                    // on the end of it, and it is read from both directions.
                    is ReadingEntry.Seam -> ReaderSeamPage(
                        fromTitle = state.titleOf(entry.fromBookId).orEmpty(),
                        toTitle = state.titleOf(entry.toBookId).orEmpty(),
                        // Only the seam *into* the next volume ever offers the fallback, and only when
                        // that volume could not be opened ahead of time — a password, a file that will
                        // not open, machinery this reader draws differently. The book behind the reader
                        // is already behind them, and its saved position rather than its first page is
                        // where they would want to arrive.
                        onOpenNext = if (entry.fromBookId == bookId &&
                            entry.toBookId in state.unavailableNeighbours
                        ) {
                            { onIntent(ReaderIntent.OpenNeighbour(entry.toBookId)) }
                        } else {
                            null
                        },
                        // A page of the column, so a whole viewport of it: a seam is where the reader
                        // arrives between two books and has to be able to rest while the next one
                        // opens, not a strip under the last page of the volume. The chrome's own
                        // height comes off the bottom of that so the fallback button stays reachable
                        // with the toolbars showing — the clearance the end-of-book panel used to
                        // carry, now that the seam is the only thing at the end of a book.
                        modifier = Modifier
                            .fillParentMaxHeight()
                            .padding(bottom = ReaderChromeClearance),
                    )

                    // The order of a page-image book holds its pages and the seams between books;
                    // nothing else can be in it.
                    else -> Unit
                }
            }
        }

        inspection.page?.let { page ->
            PageInspection(
                page = page,
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
 * The page itself and not the column index it was drawn at: the column holds the pages of the volumes
 * either side of the open one as well, so the index is only a place in the list until the order says
 * what is at it — and page three of the volume below the open book is not page three of the open book.
 * A seam answers with nothing, because it is not a page of anything and has nothing to magnify.
 *
 * The fraction rather than the point, because the page opened over the column is fitted differently:
 * "the place under the finger" is a different coordinate in each of the two rectangles.
 *
 * This is asked at the *list* rather than by each page, and that is deliberate. A tap detector on a
 * page would sit above the list's own and swallow every single tap — which is how a version of this
 * briefly stopped tap-to-turn and the toolbar from working anywhere over a page.
 */
private fun pageAt(
    position: Offset,
    listState: LazyListState,
    order: List<ReadingEntry>,
): Pair<ReadingEntry.Page, Offset>? {
    val layout = listState.layoutInfo
    val y = position.y + layout.viewportStartOffset
    val item = layout.visibleItemsInfo.firstOrNull { info ->
        y >= info.offset && y < info.offset + info.size
    } ?: return null
    if (item.size <= 0) return null
    val page = order.entryAt(item.index) as? ReadingEntry.Page ?: return null
    return page to Offset(
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
    bookId: Long,
    pageIndex: Int,
    listState: LazyListState,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    onMagnify: (ReadingEntry.Page, ColumnMagnify) -> Unit,
) {
    // The entry this item stands for, built once: a pinch names it on every frame of the gesture, and
    // the page's identity now has a book in it as well as a number.
    val page = remember(bookId, pageIndex) { ReadingEntry.Page(bookId, pageIndex) }
    val pageSize by produceState<PageSize?>(initialValue = null, bookId, pageIndex) {
        value = viewModel.pageSize(bookId, pageIndex)
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
            bookId = bookId,
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
                // Keyed on the entry rather than the page number: the column holds pages of more than
                // one book, and this node belongs to one of them.
                .pointerInput(page) {
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
                                        centroid.y + viewportOffsetOf(page, listState),
                                    ),
                                    zoom = 1f,
                                    columnDrawn = drawn,
                                )
                            }
                            anchor?.let { onMagnify(page, it.copy(zoom = zoom)) }

                            if (zoom > ZOOMED_THRESHOLD) {
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        // Back to a single page-width and the page returns to the column, which is
                        // how a reader says they have finished with it.
                        if (zoom <= ZOOMED_THRESHOLD) anchor?.let { onMagnify(page, it.copy(zoom = zoom)) }
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
private fun viewportOffsetOf(page: ReadingEntry.Page, listState: LazyListState): Float {
    val layout = listState.layoutInfo
    // Found by the entry's own key rather than by its index: a page number no longer names an item
    // once the column holds the pages of more than one book, while the key the column files it under
    // does — see [ReadingEntry.readingKey].
    val key = page.readingKey()
    val item = layout.visibleItemsInfo.firstOrNull { info -> info.key == key } ?: return 0f
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
    page: ReadingEntry.Page,
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
            .pointerInput(page) {
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
            .pointerInput(page) {
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
            bookId = page.bookId,
            pageIndex = page.pageIndex,
            pageOffset = { 0f },
            isCurrentPage = true,
            layerTransform = layerTransform,
            resolutionStep = inspection.resolutionStep,
            fitMode = PageFitMode.PAGE,
            pageTurnEffect = PageTurnEffect.CURL,
            viewModel = viewModel,
            onIntent = onIntent,
            onGeometry = { measured: PageGeometry -> inspection.report(page, measured) },
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
    /**
     * The page opened over the column, or `null` while the reader is scrolling.
     *
     * An entry rather than a page number, because the column holds the pages of more than one book:
     * page three of the volume below the open one and page three of the open one are different pages,
     * and the overlay has to draw the one the reader put their fingers on.
     */
    var page by mutableStateOf<ReadingEntry.Page?>(null)
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
    private var geometryPage by mutableStateOf<ReadingEntry.Page?>(null)

    /** Whether a double-tap looks for a speech bubble first. Read from settings by the caller. */
    var bubbleZoom by mutableStateOf(true)

    /** The opened page reporting where it is drawn and how big. */
    fun report(page: ReadingEntry.Page, measured: PageGeometry) {
        geometry = measured
        geometryPage = page
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
     * Opens [entry] over the column, carrying on from the pinch that asked for it.
     *
     * The page must not move or change size as it is handed over, which is why the transform is
     * built from [ColumnMagnify] — what the column was showing — rather than from the pinch's own
     * magnification applied to the overlay's quite different idea of "unzoomed".
     */
    fun magnify(entry: ReadingEntry.Page, pinch: ColumnMagnify) {
        if (page != entry) {
            page = entry
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

    /** Opens [entry] over the column at fit, asking for [fraction] of it to be framed. */
    fun frame(entry: ReadingEntry.Page, fraction: Offset) {
        if (page != entry) {
            page = entry
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

