package com.mylibrary.feature.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Only reached if a font size is set beyond the settings' own ceiling, so it is a backstop. */
private const val MAX_MEASURED_LINES = 400

/**
 * The paged view for reflowable documents.
 *
 * The chapter is measured once, split into pages by [paginate], and then turned with the same
 * gestures as a PDF or a comic — which is the point: a text file and a scanned book should not feel
 * like two different applications. What differs is only how a page comes to exist. A PDF page is an
 * image the decoder hands over; a page here is a decision the reader makes, and the decision is
 * remade whenever the text is laid out differently.
 *
 * Re-measuring is why the position is kept as a character offset rather than a page number. Change
 * the font size and the chapter may go from twelve pages to fifteen; the reader stays where they
 * were reading, because the text they were looking at is still the text they were looking at.
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
    val linkColor = MaterialTheme.colorScheme.primary

    val content by produceState<ChapterContent>(ChapterContent.Empty, state.currentUnit, linkColor) {
        value = viewModel.chapterContent(
            chapterIndex = state.currentUnit,
            links = LinkStyling(color = linkColor) { href ->
                currentOnIntent(ReaderIntent.FollowLink(href))
            },
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(density) { (maxWidth - READING_MARGIN * 2).roundToPx() }
        val heightPx = with(density) { (maxHeight - PAGE_TOP_MARGIN - PAGE_BOTTOM_MARGIN).toPx() }
        val spacingPx = with(density) { PAGE_BLOCK_SPACING.toPx() }

        // Measured outside the pagination so the styles are plain values the remember can compare:
        // keying on the whole reader state would re-paginate the chapter on every page turn.
        val bodyStyle = state.bodyTextStyle()
        val headingStyles = (1..4).map { level -> state.headingStyle(level) }
        val layoutDirection = LocalLayoutDirection.current

        val pages = remember(content.blocks, widthPx, heightPx, spacingPx, bodyStyle, headingStyles) {
            val measure = TextLayoutBlockMeasure(
                measurer = measurer,
                layoutDirection = layoutDirection,
                pageHeightPx = heightPx,
                styleFor = { block ->
                    when (block) {
                        is ContentBlock.Heading -> headingStyles[(block.level - 1).coerceIn(0, 3)]
                        else -> bodyStyle
                    }
                },
            )
            paginate(
                blocks = content.blocks,
                widthPx = widthPx,
                pageHeightPx = heightPx,
                spacingPx = spacingPx,
                measure = measure,
            )
        }

        val pagerState = rememberPagerState(pageCount = { pages.size })

        // Where the reader is, as a character offset into the chapter. It is what survives
        // re-pagination, and what a restored position lands on.
        var anchor by remember { mutableIntStateOf(state.reflowOffset) }
        var anchoredChapter by remember { mutableIntStateOf(state.currentUnit) }

        fun chapterOffsetOf(page: ReaderPage): Int =
            page.slices.firstOrNull()?.let { content.offsets.startOf(it.blockIndex) + it.start } ?: 0

        // A chapter the reader has just moved to starts at its own beginning: an offset from the
        // chapter they came from would land them somewhere arbitrary in the new one.
        LaunchedEffect(state.currentUnit) {
            if (state.currentUnit != anchoredChapter) {
                anchoredChapter = state.currentUnit
                anchor = 0
            }
        }

        LaunchedEffect(pages, content.anchorBlocks, state.pendingAnchor) {
            if (pages.isEmpty()) return@LaunchedEffect

            // A followed link wins over the remembered offset: it is where the reader just asked to
            // go, and the offset is where they were before they asked.
            val anchorBlock = state.pendingAnchor?.let { content.anchorBlocks[it] }
            val target = if (anchorBlock != null) {
                pages.indexOfFirst { page -> page.slices.any { it.blockIndex == anchorBlock } }
            } else {
                pages.indexOfLast { page -> chapterOffsetOf(page) <= anchor }
            }.coerceAtLeast(0)

            if (target != pagerState.currentPage) pagerState.scrollToPage(target)

            if (anchorBlock != null) {
                // Record where the jump landed before clearing the anchor, so clearing it does not
                // send the reader back to where they were before the link.
                anchor = chapterOffsetOf(pages[target])
                currentOnIntent(ReaderIntent.AnchorReached)
            }
        }

        LaunchedEffect(pagerState, pages, content.offsets) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { page ->
                    val current = pages.getOrNull(page) ?: return@collect
                    val offset = chapterOffsetOf(current)
                    anchor = offset
                    currentOnIntent(ReaderIntent.ReflowPositionChanged(page, pages.size, offset))
                }
        }

        val currentPages by rememberUpdatedState(pages)
        val currentSettings by rememberUpdatedState(state.settings)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { position ->
                        val settings = currentSettings
                        val pageCount = currentPages.size
                        val zone = if (settings.tapToTurnPages) {
                            tapZoneFor(position.x, size.width.toFloat(), isRtl)
                        } else {
                            TapZone.CENTER
                        }

                        when (zone) {
                            TapZone.CENTER -> currentOnIntent(ReaderIntent.ToggleChrome)

                            TapZone.NEXT -> {
                                val next = pagerState.currentPage + 1
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
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
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
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
            if (pages.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    pageSpacing = PAGE_SPACING,
                ) { pageIndex ->
                    PageContent(
                        page = pages[pageIndex],
                        content = content,
                        viewModel = viewModel,
                        state = state,
                        isLastPage = pageIndex == pages.lastIndex,
                        // The same turn effect as the fixed-page reader, driven by the same value, so
                        // a text file split into pages and a comic turn alike — which is the whole
                        // reason the reader has one toolbar and one set of gestures for five formats.
                        turnModifier = Modifier.graphicsLayer {
                            val offset = pagerState.getOffsetDistanceInPages(pageIndex)
                            val turn = pageTurnTransform(offset, currentSettings.pageTurnEffect, isRtl)
                            scaleX = turn.scale
                            scaleY = turn.scale
                            alpha = turn.alpha
                            rotationY = turn.rotationY
                            transformOrigin = TransformOrigin(pageTurnPivotX(offset, isRtl), 0.5f)
                            cameraDistance = PAGE_TURN_CAMERA_DISTANCE * density.density
                        },
                    )
                }
            }
        }
    }
}

/** One page: its slices, and — on the last one — a way onward when the chapter has a next. */
@Composable
private fun PageContent(
    page: ReaderPage,
    content: ChapterContent,
    viewModel: ReaderViewModel,
    state: ReaderUiState,
    isLastPage: Boolean,
    turnModifier: Modifier = Modifier,
) {
    Column(
        modifier = turnModifier
            .fillMaxSize()
            // A safety valve rather than a feature: an image taller than the page is placed anyway
            // because the alternative is a page with nothing on it, and this is what lets a reader
            // see the rest of it.
            .verticalScroll(rememberScrollState())
            .padding(
                start = READING_MARGIN,
                end = READING_MARGIN,
                top = PAGE_TOP_MARGIN,
                bottom = PAGE_BOTTOM_MARGIN,
            ),
        verticalArrangement = Arrangement.spacedBy(PAGE_BLOCK_SPACING),
    ) {
        page.slices.forEach { slice ->
            val block = content.blocks.getOrNull(slice.blockIndex) ?: return@forEach
            BlockSliceView(block = block, slice = slice, viewModel = viewModel, state = state)
        }

        if (isLastPage && state.currentUnit < state.totalUnits - 1) {
            ChapterEndFooter(onClick = { viewModel.onIntent(ReaderIntent.NextUnit) })
        }
    }
}

/**
 * The end of a chapter, said out loud.
 *
 * Swiping stops at the last page — a pager has nowhere to go — so the reader is told what comes
 * next and given one control that goes there, rather than being left to discover that the edge of
 * the screen turns the page but the end of the chapter does not.
 */
@Composable
private fun ChapterEndFooter(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClick) {
            Text(stringResource(R.string.reader_next_chapter))
        }
    }
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

private val READING_MARGIN = 20.dp
private val PAGE_TOP_MARGIN = 24.dp
private val PAGE_BOTTOM_MARGIN = 24.dp
private val PAGE_BLOCK_SPACING = 10.dp
private val PAGE_SPACING = 8.dp

/** Camera distance for the page-turn rotation, matching the fixed-page reader's. */
private const val PAGE_TURN_CAMERA_DISTANCE = 14f
