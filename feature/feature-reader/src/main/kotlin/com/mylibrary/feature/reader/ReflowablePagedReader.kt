package com.mylibrary.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.dp
import com.mylibrary.core.domain.model.PageTurnEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Only reached if a font size is set beyond the settings' own ceiling, so it is a backstop. */
private const val MAX_MEASURED_LINES = 400

/**
 * The paged view for reflowable documents.
 *
 * A window of chapters is measured, split into pages by [paginate], and then turned with the same
 * gestures as a PDF or a comic — which is the point: a text file and a scanned book should not feel
 * like two different applications. What differs is only how a page comes to exist. A PDF page is an
 * image the decoder hands over; a page here is a decision the reader makes, and the decision is
 * remade whenever the text is laid out differently.
 *
 * Re-measuring is why the position is kept as a character offset rather than a page number. Change
 * the font size and the chapter may go from twelve pages to fifteen; the reader stays where they
 * were reading, because the text they were looking at is still the text they were looking at.
 *
 * **And a turn crosses chapters.** The pager holds [windowChapters] — the chapter being read and one
 * on each side of it — rather than the one chapter, so a chapter's first page follows the last page
 * of the one before it and a turn reaches it the way it reaches any other page, in both directions.
 * Nothing special happens at the seam, which is the whole of the design: no button, and no case for
 * the end of a chapter anywhere below.
 */
@Composable
fun ReflowablePagedContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentOnIntent by rememberUpdatedState(onIntent)
    val currentHapticsEnabled by rememberUpdatedState(state.settings.hapticsEnabled)
    val linkColor = MaterialTheme.colorScheme.primary

    // The chapters the pager holds at once. A pure function of the reader's own chapter, which is
    // what keeps a window slide to one per chapter crossed — see `windowChapters`.
    val chapters = windowChapters(currentUnit = state.currentUnit, totalUnits = state.totalUnits)

    // One parse per chapter in the window rather than one for the chapter being read: a page has to
    // be drawn from the chapter it is *in*, and once a turn can cross a boundary that is the
    // neighbour's chapter as often as it is this one's.
    val contents by produceState<Map<Int, ChapterContent>>(emptyMap(), chapters, linkColor) {
        value = chapters.associateWith { chapter ->
            viewModel.chapterContent(
                chapterIndex = chapter,
                links = LinkStyling(color = linkColor) { href ->
                    currentOnIntent(ReaderIntent.FollowLink(href))
                },
            )
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The margins, and the gap between blocks, are the reader's to set. Both are read here
        // rather than inside the `remember` below, because both change the width a page is measured
        // into and the space its blocks need — a margin or a spacing the paginator did not know
        // about is a page with a line of text past its bottom edge.
        val margin = state.readingMargin()
        val widthPx = with(density) { (maxWidth - margin * 2).roundToPx() }
        val heightPx = with(density) { (maxHeight - PAGE_TOP_MARGIN - PAGE_BOTTOM_MARGIN).toPx() }
        val spacingPx = with(density) { state.paragraphSpacing().toPx() }

        // Measured outside the pagination so the styles are plain values the remember can compare:
        // keying on the whole reader state would re-paginate the chapter on every page turn.
        val bodyStyle = state.bodyTextStyle()
        val paragraphStyle = state.paragraphTextStyle()
        val headingStyles = (1..4).map { level -> state.headingStyle(level) }
        val layoutDirection = LocalLayoutDirection.current

        // A measurer of the book's own, used only by the pass below and only from that pass's thread.
        //
        // `TextMeasurer` keeps every layout it has made in a cache of its own, and that cache is an
        // unsynchronised `LruCache` behind two plain fields — so one instance shared between the page
        // being drawn on the main thread and a sweep of the whole book on another is two threads
        // writing the same map, and the measurements that come back can be another block's. Nothing
        // about the measurer is expensive to have twice, so the composition's is left entirely to the
        // composition and this one is never touched from anywhere else.
        val fontFamilyResolver = LocalFontFamilyResolver.current
        val bookMeasurer = remember(fontFamilyResolver, density, layoutDirection) {
            TextMeasurer(fontFamilyResolver, density, layoutDirection)
        }

        // A paragraph is measured in the style it is drawn in, indent and all: the drawer decides the
        // indent from the same setting, so the two cannot disagree about how wide the first line is.
        // Everything else — a quotation, a list item, a caption — is set flush and measured flush.
        //
        // Shared with the book-wide measurement below, and it has to be: the two must cut a chapter
        // into the same pages, or the page count the reader is shown would be a count of pages the
        // pager never draws.
        val styleFor: (ContentBlock) -> TextStyle = { block ->
            when (block) {
                is ContentBlock.Heading -> headingStyles[(block.level - 1).coerceIn(0, 3)]
                is ContentBlock.Paragraph -> paragraphStyle
                else -> bodyStyle
            }
        }

        // One chapter's pages: cut from the chapter's own text, at this width and this font. Keyed on
        // the parsed chapters rather than on the window, so a chapter carried over a slide is not
        // measured a second time on the frame that slid it.
        val chapterPages = remember(
            contents,
            widthPx,
            heightPx,
            spacingPx,
            bodyStyle,
            paragraphStyle,
            headingStyles,
        ) {
            val measure = TextLayoutBlockMeasure(
                measurer = measurer,
                layoutDirection = layoutDirection,
                pageHeightPx = heightPx,
                styleFor = styleFor,
            )
            chapters.associateWith { chapter ->
                paginate(
                    blocks = contents[chapter]?.blocks.orEmpty(),
                    widthPx = widthPx,
                    pageHeightPx = heightPx,
                    spacingPx = spacingPx,
                    measure = measure,
                )
            }
        }

        // The whole book measured, so that the progress bar can count its pages rather than its
        // chapters — see `BookPageIndex`. The reader is the only side that knows the width, the
        // height, the spacing and the text styles a page is made at, which is why this runs here and
        // not in the ViewModel.
        //
        // Keyed on the layout and on nothing else. Keying on `contents` would re-measure the book
        // every time the theme colour changed, and keying on the state would do it on every page
        // turn; what it answers is "how long is this book, laid out *like this*", and that changes
        // only with the things below.
        LaunchedEffect(
            state.countsBookPages,
            state.totalUnits,
            widthPx,
            heightPx,
            spacingPx,
            bodyStyle,
            paragraphStyle,
            headingStyles,
            layoutDirection,
        ) {
            // A reader counting chapters is not owed the measurement, and asking for one would take
            // the cost of the feature away from the setting that is supposed to control it. The
            // index is dropped on the way out so that what is left behind cannot be read.
            if (!state.countsBookPages) {
                currentOnIntent(ReaderIntent.BookPageIndexCleared)
                return@LaunchedEffect
            }

            // Dropped before the pass starts rather than after it finishes: a font-size change
            // restarts this effect, and the reader must fall back to counting chapters during the
            // new pass instead of going on counting the pages of the layout that is gone.
            currentOnIntent(ReaderIntent.BookPageIndexCleared)

            val index = withContext(Dispatchers.Default) {
                val measure = TextLayoutBlockMeasure(
                    measurer = bookMeasurer,
                    layoutDirection = layoutDirection,
                    pageHeightPx = heightPx,
                    styleFor = styleFor,
                )
                val measured = ArrayList<IntArray?>(state.totalUnits)
                for (chapter in 0 until state.totalUnits) {
                    // Co-operative rather than merely cancellable: a chapter is one long measurement
                    // with no suspension point inside it, so the check belongs between them.
                    coroutineContext.ensureActive()
                    val content = viewModel.chapterContent(chapter)
                    measured += paginate(
                        blocks = content.blocks,
                        widthPx = widthPx,
                        pageHeightPx = heightPx,
                        spacingPx = spacingPx,
                        measure = measure,
                    ).map { page -> pageOffset(page, content.offsets) }.toIntArray()
                }
                BookPageIndex.of(measured)
            }
            currentOnIntent(ReaderIntent.BookPageIndexReady(index))
        }

        // The pager's pages: every page of every chapter in the window, in one flat sequence. A
        // chapter still being parsed, or one that paginated to nothing, contributes none — so the
        // turn across it happens between two pages that exist.
        val pageRefs = remember(chapters, chapterPages) {
            windowPages(chapters, chapterPages.mapValues { it.value.size })
        }

        val pagerState = rememberPagerState(pageCount = { pageRefs.size })

        // What turns a page into a position: the block → character offset map of each chapter in the
        // window, so a page can say which character of *its own* chapter it begins at. Kept apart
        // from `contents` because it is the only part the position arithmetic needs.
        val offsets = remember(contents) { contents.mapValues { it.value.offsets } }

        // Puts the pager on the page the reader's position is on.
        //
        // The position is the state's and not one remembered here, because the state is where every
        // way of moving the reader arrives — an outline entry, the page slider, a link followed and a
        // link returned from, and the reports of the reader's own turns — and a second copy of it
        // here would be the one that is right when a *jump* arrives and wrong when anything else
        // does. What it must not be is a page *number*: that names a pagination which no longer
        // exists. The character offset is the part that survives the text being laid out again, and
        // it is what both the state and the saved position are kept as.
        //
        // A page turn is the case that needs nothing done and gets nothing: the turn reports the page
        // it arrived on, this finds that same page again, and the reader stays where they put
        // themselves.
        LaunchedEffect(state.currentUnit, state.pendingAnchor, state.reflowOffset, chapterPages, contents) {
            if (pageRefs.isEmpty()) return@LaunchedEffect

            val chapter = state.currentUnit
            val pagesInChapter = chapterPages[chapter].orEmpty()
            val chapterOffsets = offsets[chapter] ?: ChapterTextMap.Empty
            val anchorBlock = state.pendingAnchor?.let { id ->
                contents[chapter]?.anchorBlocks?.get(id)
            }
            val landing = landingPageIn(pagesInChapter, chapterOffsets, state.reflowOffset, anchorBlock)
            if (landing < 0) return@LaunchedEffect

            val target = indexOfPage(pageRefs, chapter, landing)
            if (target < 0) return@LaunchedEffect
            // Against the page the pager is *resting* on, not the one a gesture is turning to: a
            // drag in progress must not be snapped back to where it started.
            if (target != pagerState.settledPage) pagerState.scrollToPage(target)

            if (anchorBlock != null) {
                currentOnIntent(ReaderIntent.AnchorReached)
                // A link names a block and the page holding it may begin before it, and the state is
                // still holding the offset of the chapter the link was followed *from*. So say where
                // the jump actually landed — otherwise the reader's position, and the position that
                // gets saved, is a place in a chapter they are no longer in.
                currentOnIntent(
                    ReaderIntent.ReflowPositionChanged(
                        chapterIndex = chapter,
                        pageIndex = landing,
                        pageCount = pagesInChapter.size,
                        offset = pageOffset(pagesInChapter[landing], chapterOffsets),
                    ),
                )
            }
        }

        // Where the reader has got to, reported as they read. Taken from the *settled* page rather
        // than the one being turned to, so the chapter and the page within it always come from the
        // same page — a report taken mid-turn could name a page of one chapter inside another.
        //
        // This collector is started once and never restarted, which is the whole reason it reads the
        // window through `rememberUpdatedState`: a restart would re-announce the page already on
        // screen, and a report is also read as a page turn. After a jump the pager is still on the
        // old chapter's page when the window changes, so that re-announcement would arrive as the
        // reader turning back — and cancel the jump that started it.
        val latestPages by rememberUpdatedState(pageRefs)
        val latestChapterPages by rememberUpdatedState(chapterPages)
        val latestOffsets by rememberUpdatedState(offsets)
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }
                .distinctUntilChanged()
                .collect { settled ->
                    val ref = latestPages.getOrNull(settled) ?: return@collect
                    val page = latestChapterPages[ref.chapterIndex]
                        ?.getOrNull(ref.pageIndexInChapter) ?: return@collect
                    val offset = pageOffset(page, latestOffsets[ref.chapterIndex] ?: ChapterTextMap.Empty)
                    currentOnIntent(
                        ReaderIntent.ReflowPositionChanged(
                            chapterIndex = ref.chapterIndex,
                            pageIndex = ref.pageIndexInChapter,
                            pageCount = latestChapterPages[ref.chapterIndex]?.size ?: 0,
                            offset = offset,
                        ),
                    )
                }
        }

        val currentPages by rememberUpdatedState(pageRefs)
        val currentSettings by rememberUpdatedState(state.settings)
        // Read through `rememberUpdatedState`: the gesture loop is not restarted when the direction
        // changes, so a plain read inside it would keep the direction the chapter was opened in.
        val currentIsRtl by rememberUpdatedState(isRtl)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { position ->
                        val settings = currentSettings
                        val pageCount = currentPages.size
                        val zone = if (settings.tapToTurnPages) {
                            tapZoneFor(
                                x = position.x,
                                width = size.width.toFloat(),
                                isRtl = currentIsRtl,
                                reversed = settings.reverseTapZones,
                            )
                        } else {
                            TapZone.CENTER
                        }

                        when (zone) {
                            TapZone.CENTER -> currentOnIntent(ReaderIntent.ToggleChrome)

                            TapZone.NEXT -> {
                                val next = pagerState.currentPage + 1
                                if (currentHapticsEnabled) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                if (next < pageCount) {
                                    scope.launch { pagerState.animateScrollToPage(next) }
                                } else {
                                    // Past the last page of the chapter, the gesture keeps meaning
                                    // "forward" and moves on to the next chapter.
                                    currentOnIntent(ReaderIntent.NextUnit)
                                }
                            }

                            TapZone.PREVIOUS -> {
                                val previous = pagerState.currentPage - 1
                                if (currentHapticsEnabled) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                if (previous >= 0) {
                                    scope.launch { pagerState.animateScrollToPage(previous) }
                                } else {
                                    currentOnIntent(ReaderIntent.PreviousUnit)
                                }
                            }
                        }
                    }
                },
        ) {
            if (pageRefs.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    pageSpacing = PAGE_SPACING,
                    // The page's own identity rather than its place in the list. A window slide
                    // renumbers every page in it, and this is what lets the pager find the page it
                    // was on again — inside the measure pass that renumbered them, so no frame is
                    // ever drawn with the new list and the old index. `(chapter, page)` names exactly
                    // one page in the book; see `pagerKey` for why it travels as a number.
                    key = { index -> pageRefs[index].pagerKey() },
                ) { pageIndex ->
                    val ref = pageRefs[pageIndex]
                    val page = chapterPages[ref.chapterIndex]?.getOrNull(ref.pageIndexInChapter)
                    if (page != null) {
                        PageContent(
                            ref = ref,
                            pageIndex = pageIndex,
                            page = page,
                            pagerState = pagerState,
                            isRtl = isRtl,
                            // The same turn effect as the fixed-page reader, driven by the same
                            // value, so a text file split into pages and a comic turn alike — which
                            // is the whole reason the reader has one toolbar and one set of gestures
                            // for five formats.
                            pageTurnEffect = currentSettings.pageTurnEffect,
                            content = contents[ref.chapterIndex] ?: ChapterContent.Empty,
                            viewModel = viewModel,
                            state = state,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One page of one chapter.
 *
 * **How a page of live text gets a paper curl.** Every other format's page is a bitmap, so bending
 * it means re-drawing its pixels band by band. Text has no pixels: it is a tree of composables that
 * draws itself into whatever canvas the composition hands it. So the page is drawn once into a
 * [GraphicsLayer] — an offscreen display list — and the layer *is* rasterised, once, into the pixels
 * the bend then re-draws under the same transforms a comic's page goes through. From the first frame
 * of a turn to the last, the bend's own work is identical to a comic's, because the sheet is the same
 * kind of thing — the one frame that pays more is the first, which also rasterises. See [Sheet].
 *
 * A settled page takes none of this. It is drawn straight to the canvas exactly as it was before any
 * of it existed, which is the property that matters most — a page nobody is touching cannot have
 * been made slower or different by a page turn.
 */
@Composable
private fun PageContent(
    ref: PageRef,
    page: ReaderPage,
    pageIndex: Int,
    pagerState: PagerState,
    isRtl: Boolean,
    pageTurnEffect: PageTurnEffect,
    content: ChapterContent,
    viewModel: ReaderViewModel,
    state: ReaderUiState,
) {
    val density = LocalDensity.current
    val layer = rememberGraphicsLayer()

    // The sheet the curl bends has to be opaque, or the reader sees the page behind it through the
    // fold. It is painted the colour the page is already drawn on — the reader's own background — so
    // a settled page is unchanged to the pixel, and only a page in the act of turning has a sheet.
    val paper = MaterialTheme.colorScheme.background

    // Keyed on the page's own identity, the page and the paper, so that anything that changes how
    // the page looks — a new pagination, a colour scheme — throws the old pixels away rather than
    // bending them, while a window slide, which renumbers every page in the list, does not: the
    // sheet is the pixels the turn bends, and rebuilding it costs the frame that rasterises it.
    val sheet = remember(ref, page, paper) { Sheet() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The bands are cut across the whole slot and can reach past the page's own rectangle —
            // at a high roll the fold has travelled beyond its edge — so the slot clips them, the
            // same way the fixed-page reader's does.
            .clipToBounds(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val offset = pagerState.offsetFor(pageIndex)
                    // A curl is the page bending, and the page draws that itself. Rotating the slot
                    // as well would bend it twice; the identity here is continuous with the rotation
                    // it replaces, so nothing jumps at the moment a gesture starts.
                    val turn = if (paperCurlOwns(pageTurnEffect, offset)) {
                        PageTurnTransform.Identity
                    } else {
                        pageTurnTransform(offset, pageTurnEffect, isRtl)
                    }
                    scaleX = turn.scale
                    scaleY = turn.scale
                    alpha = turn.alpha
                    rotationY = turn.rotationY
                    transformOrigin = TransformOrigin(pageTurnPivotX(offset, isRtl), 0.5f)
                    cameraDistance = PAGE_TURN_CAMERA_DISTANCE * density.density
                }
                .drawWithContent {
                    val offset = pagerState.offsetFor(pageIndex)
                    if (!paperCurlOwns(pageTurnEffect, offset) || size.width <= 0f || size.height <= 0f) {
                        // A settled page gives its pixels back and goes on being drawn straight to the
                        // canvas — the one code path that has always drawn it.
                        sheet.drop()
                        drawContent()
                        return@drawWithContent
                    }

                    val frame = Rect(Offset.Zero, size)
                    val image = sheet.image ?: rasterise(layer) {
                        this@drawWithContent.drawContent()
                    }.also { sheet.image = it }

                    drawPaperCurl(
                        page = image,
                        frame = frame,
                        curl = paperCurl(
                            roll = paperCurlRoll(offset),
                            frame = frame,
                            hingeAtLeft = pageTurnPivotX(offset, isRtl) < 0.5f,
                        ),
                    )
                }
                .background(paper)
                // A safety valve rather than a feature: an image taller than the page is placed
                // anyway because the alternative is a page with nothing on it, and this is what lets
                // a reader see the rest of it.
                .verticalScroll(rememberScrollState())
                .padding(
                    start = state.readingMargin(),
                    end = state.readingMargin(),
                    top = PAGE_TOP_MARGIN,
                    bottom = PAGE_BOTTOM_MARGIN,
                ),
            verticalArrangement = Arrangement.spacedBy(state.paragraphSpacing()),
        ) {
            page.slices.forEach { slice ->
                val block = content.blocks.getOrNull(slice.blockIndex) ?: return@forEach
                BlockSliceView(block = block, slice = slice, viewModel = viewModel, state = state)
            }
        }
    }
}

/**
 * How far [pageIndex] is from the settled position, in pages.
 *
 * `getOffsetDistanceInPages` itself refuses an index outside the pager, and a window slide hands it
 * one: the slide renumbers the pages in one frame, and a page composed under the old numbering can
 * still re-run its layer lambda after the count has changed — the index it was given no longer
 * exists. Rather than throwing in a draw pass, such a page draws settled: it is on its way out of
 * the pager, where the identity transform is what a page not being turned draws anyway.
 */
private fun PagerState.offsetFor(pageIndex: Int): Float =
    if (pageIndex in 0 until pageCount) getOffsetDistanceInPages(pageIndex) else 0f

/**
 * The sheet a turn bends, as pixels.
 *
 * **Why pixels, and why once.** The bend re-draws the sheet once per band — some fifty draws a
 * frame — and for a comic each of those is a `drawImage` and costs nothing. Handing it the page's
 * live display list instead, which is what 1.5.0 did, makes each of those fifty draws a replay of a
 * page's worth of glyph runs. Measured on the API 35 emulator with the reader's own frame stats,
 * that was 150 ms for the median frame against 16 ms for the same page slid, with 85% of frames
 * janky against 6% — a stutter on every turn of every text file, which is what the reader reported.
 * Pixels cost the same whatever the page is made of, so the page is rasterised on the frame the turn
 * begins and the bend from then on takes the path a PDF page takes.
 *
 * The cost is moved rather than removed — one rasterisation a turn instead of one recording a frame
 * — and it is paid once, as the finger starts to move, rather than on every frame of the drag. What
 * is held between turns is nothing: a page that settles gives its pixels back, so a page nobody is
 * touching is the page it always was, drawn straight to the canvas, holding no memory.
 *
 * A turn in flight bends the pixels it was rasterised from, not the page's live composition: the
 * keys on [remember] — the pagination and the paper colour — are what throw a sheet away. Anything
 * else that changes how a page looks mid-turn (a late image landing, the chapter footer appearing)
 * shows up when the page settles, within a turn's duration.
 */
private class Sheet {
    var image: ImageBitmap? = null

    fun drop() {
        image = null
    }
}

/**
 * Records the page's content into [layer] and hands back what it looks like, as pixels.
 *
 * Two steps, both of them synchronous and both of them in the draw pass that asked for them.
 * `GraphicsLayer.toImageBitmap` would be the obvious one call, but it is `suspend`, and a draw pass
 * has nowhere to suspend. It also does exactly this underneath — a software canvas over an
 * `ARGB_8888` bitmap with the layer drawn into it — so doing it by hand costs nothing and keeps the
 * whole turn inside a single frame.
 */
private fun DrawScope.rasterise(
    layer: GraphicsLayer,
    content: DrawScope.() -> Unit,
): ImageBitmap {
    // `drawContent` is called through the outer scope deliberately: recording swaps the canvas the
    // draw context points at, so the content lands in the layer rather than on the screen it was
    // about to be drawn to.
    layer.record(size.toIntSize()) { content() }

    val pixels = ImageBitmap(size.width.roundToInt(), size.height.roundToInt(), hasAlpha = false)
    CanvasDrawScope().draw(
        density = this,
        layoutDirection = layoutDirection,
        canvas = Canvas(pixels),
        size = size,
    ) {
        drawLayer(layer)
    }
    return pixels
}

/**
 * Measures blocks with the same text shaper that will draw them.
 *
 * Measuring here and drawing there is not duplication: the paginator asks how tall a line is so it
 * can decide where a page ends, and the renderer draws the lines it was given. Both go through
 * `TextMeasurer`, so the answer and the result come from one implementation — a hand-rolled height
 * estimate would disagree with the layout at exactly the font sizes people actually use.
 */
private class TextLayoutBlockMeasure(
    private val measurer: TextMeasurer,
    private val layoutDirection: LayoutDirection,
    private val pageHeightPx: Float,
    private val styleFor: (ContentBlock) -> TextStyle,
) : BlockMeasure {

    private val layouts = HashMap<ContentBlock, TextLayoutResult>()
    private var measuredWidth = -1

    private fun layout(block: ContentBlock, widthPx: Int): TextLayoutResult {
        // One width per pagination run. Clearing rather than keying on both keeps the cache from
        // growing with every rotation the device has ever been through.
        if (widthPx != measuredWidth) {
            layouts.clear()
            measuredWidth = widthPx
        }
        return layouts.getOrPut(block) {
            measurer.measure(
                text = block.bodyText(),
                style = styleFor(block),
                maxLines = MAX_MEASURED_LINES,
                constraints = Constraints(maxWidth = widthPx),
                layoutDirection = layoutDirection,
            )
        }
    }

    override fun lineCount(block: ContentBlock, widthPx: Int): Int = layout(block, widthPx).lineCount

    /**
     * The height of one line, as the distance to the next line's top.
     *
     * Not `bottom - top` of the line itself: with a large line height the leading is allocated
     * *between* lines, so per-line measurements would each miss a share of it and a page would come
     * out taller than the text the paginator thought it had placed. Measured this way the lines of a
     * block add up to exactly the block's height, which is the property the paginator relies on.
     */
    override fun lineHeight(block: ContentBlock, line: Int, widthPx: Int): Float {
        val layout = layout(block, widthPx)
        return if (line < layout.lineCount - 1) {
            layout.getLineTop(line + 1) - layout.getLineTop(line)
        } else {
            layout.getLineBottom(line) - layout.getLineTop(line)
        }
    }

    override fun lineStart(block: ContentBlock, line: Int, widthPx: Int): Int =
        layout(block, widthPx).getLineStart(line)

    override fun lineEnd(block: ContentBlock, line: Int, widthPx: Int): Int =
        layout(block, widthPx).getLineEnd(line, visibleEnd = true)

    /**
     * A block that cannot be split is given a whole page.
     *
     * An image's height is not knowable without decoding it, and a table's would need a layout pass
     * of its own; guessing either would put text on top of a picture or leave a gap where one should
     * have been. Giving them a page and drawing them scaled to fit inside it is the arrangement that
     * cannot be wrong — it costs some white space around a small illustration, which is a great deal
     * better than a caption printed over one.
     *
     * A rule is the exception: its height is a constant the renderer fixes, so it flows with the
     * text around it the way the document intended.
     */
    override fun wholeHeight(block: ContentBlock, widthPx: Int): Float = when (block) {
        ContentBlock.Divider -> DIVIDER_HEIGHT_PX
        else -> pageHeightPx
    }
}

/** A `HorizontalDivider` with its vertical padding, near enough at any density. */
private const val DIVIDER_HEIGHT_PX = 17f

// The side margin and the gap between blocks are not here: both are the reader's own settings now,
// and both are read from the state — see `readingMargin` and `paragraphSpacing`.
private val PAGE_TOP_MARGIN = 24.dp
private val PAGE_BOTTOM_MARGIN = 24.dp
private val PAGE_SPACING = 8.dp

/** Camera distance for the page-turn rotation, matching the fixed-page reader's. */
private const val PAGE_TURN_CAMERA_DISTANCE = 14f
