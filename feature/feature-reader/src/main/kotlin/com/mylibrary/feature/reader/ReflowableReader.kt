package com.mylibrary.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mylibrary.core.domain.model.ReaderFont
import com.mylibrary.core.ui.theme.readerFontFamily
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The reader for reflowable documents: EPUB and TXT.
 *
 * Chapters are laid out lazily by a `LazyColumn` keyed on chapter index, so a book with 300 chapters
 * composes the one being read plus the next. Within a chapter, content is rendered from the block
 * list produced by [parseChapterHtml] rather than from an HTML view: that is what lets the reader
 * apply its own typography, font scale and theme to the text instead of inheriting whatever the
 * publisher's stylesheet demanded.
 *
 * Scroll position is the chapter index, not a pixel offset, which is what makes the position survive
 * a font-size change — the text re-flows, and the reader stays on the same chapter rather than
 * jumping to an arbitrary point.
 */
@Composable
fun ReflowableReaderContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.currentUnit.coerceAtLeast(0),
    )

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { chapterIndex -> onIntent(ReaderIntent.ChapterChanged(chapterIndex)) }
    }

    LaunchedEffect(state.currentUnit, state.totalUnits) {
        if (state.totalUnits > 0 && state.currentUnit != listState.firstVisibleItemIndex) {
            listState.animateScrollToItem(state.currentUnit)
        }
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentOnIntent by rememberUpdatedState(onIntent)
    val currentTapToTurn by rememberUpdatedState(state.settings.tapToTurnPages)
    val currentReverseTapZones by rememberUpdatedState(state.settings.reverseTapZones)
    val currentHapticsEnabled by rememberUpdatedState(state.settings.hapticsEnabled)
    // Read through `rememberUpdatedState`: the gesture loop is not restarted when the direction
    // changes, so a plain read inside it would keep the direction the book was opened in.
    val currentIsRtl by rememberUpdatedState(isRtl)

    // A page turn has no meaning in a reflowed chapter, so the side zones advance by a screenful
    // instead — the same gesture and the same physical direction as the paged reader, which is what
    // keeps the two modes feeling like one reader. The zones are mirrored in a right-to-left
    // document, so in Arabic the left-hand tap is the one that moves forward.
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { position ->
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
                            scope.launch { listState.animateScrollBy(viewport * SCROLL_PAGE_FRACTION) }
                        }

                        TapZone.PREVIOUS -> {
                            if (currentHapticsEnabled) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            scope.launch { listState.animateScrollBy(-viewport * SCROLL_PAGE_FRACTION) }
                        }
                    }
                }
            },
    ) {
        val margin = state.readingMargin()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = margin,
                end = margin,
                top = 24.dp,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(count = state.totalUnits, key = { index -> index }) { chapterIndex ->
                ChapterContentView(
                    chapterIndex = chapterIndex,
                    // Only the chapter being read can be a link target, and handing the anchor to
                    // any other one would scroll a chapter the reader is not looking at.
                    pendingAnchor = state.pendingAnchor.takeIf { chapterIndex == state.currentUnit },
                    viewModel = viewModel,
                    state = state,
                    onIntent = onIntent,
                )
            }
        }
    }
}

/** One chapter: its title, then its blocks. */
@Composable
private fun ChapterContentView(
    chapterIndex: Int,
    pendingAnchor: String?,
    viewModel: ReaderViewModel,
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val currentOnIntent by rememberUpdatedState(onIntent)

    val content by produceState<ChapterContent>(
        initialValue = ChapterContent.Empty,
        chapterIndex,
        // Re-parsed when the theme changes so links take the new accent colour rather than keeping
        // the one they were first drawn with.
        linkColor,
    ) {
        value = viewModel.chapterContent(
            chapterIndex = chapterIndex,
            links = LinkStyling(color = linkColor) { href ->
                currentOnIntent(ReaderIntent.FollowLink(href))
            },
        )
    }

    // A link anchor is scrolled to by asking the block to bring itself into view, rather than by
    // computing an index into the chapter list: a chapter lays out as a single item, so a block has
    // no list position of its own to scroll to.
    val anchorRequester = remember { BringIntoViewRequester() }
    val anchorBlockIndex = pendingAnchor?.let { anchor -> content.anchorBlocks[anchor] }

    LaunchedEffect(pendingAnchor, anchorBlockIndex, content.blocks.size) {
        if (pendingAnchor != null && anchorBlockIndex != null) {
            anchorRequester.bringIntoView()
            currentOnIntent(ReaderIntent.AnchorReached)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(state.paragraphSpacing())) {
        // A document that names its chapters announces them; one that does not — a plain TXT file —
        // falls back to the chapter number so the reader can still tell where a part begins.
        if (chapterIndex > 0 || content.title != null) {
            ChapterMarker(index = chapterIndex, title = content.title)
        }

        content.blocks.forEachIndexed { blockIndex, block ->
            val blockModifier =
                if (blockIndex == anchorBlockIndex) {
                    Modifier.bringIntoViewRequester(anchorRequester)
                } else {
                    Modifier
                }

            Box(modifier = blockModifier) {
                BlockView(block = block, viewModel = viewModel, state = state)
            }
        }
    }
}

/**
 * Draws one slice of a block: the block whole, or the run of its text that landed on this page.
 *
 * Only the paged view produces partial blocks — a scrolling chapter always passes a slice covering
 * the whole thing — so this deliberately falls straight through to [BlockView] in that case, and the
 * scrolling reader's rendering stays exactly what it was.
 */
@Composable
internal fun BlockSliceView(
    block: ContentBlock,
    slice: BlockSlice,
    viewModel: ReaderViewModel,
    state: ReaderUiState,
) {
    val text = block.bodyText()
    if (!block.isSplittable() || (slice.start <= 0 && slice.end >= text.length)) {
        BlockView(block = block, viewModel = viewModel, state = state)
        return
    }

    val from = slice.start.coerceIn(0, text.length)
    val to = slice.end.coerceIn(from, text.length)
    val part = text.subSequence(from, to)

    when (block) {
        // The indent belongs to the paragraph's *first* line, so a slice that begins part-way
        // through one is set flush: it is a continuation, and indenting it would read as a new
        // paragraph starting mid-sentence. It is also what the pagination measured — the lines after
        // the first were laid out at full width.
        is ContentBlock.Paragraph -> BodyText(
            text = part,
            state = state,
            asParagraph = slice.start <= 0,
        )

        is ContentBlock.Heading -> Text(
            text = part,
            style = state.headingStyle(block.level),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )

        is ContentBlock.Quote -> QuoteText(text = part, state = state)

        // The marker belongs to the item, not to the page: repeating the bullet at the top of a
        // continuation reads as a second item that happens to have no text in it.
        is ContentBlock.ListItem -> ListItemText(
            block = block.copy(text = part),
            state = state,
            showMarker = slice.start <= 0,
        )

        is ContentBlock.Image, is ContentBlock.Table, ContentBlock.Divider ->
            BlockView(block = block, viewModel = viewModel, state = state)
    }
}

/** Draws one block. Split out so the anchor wrapper above stays readable. */
@Composable
private fun BlockView(
    block: ContentBlock,
    viewModel: ReaderViewModel,
    state: ReaderUiState,
) {
    when (block) {
        is ContentBlock.Paragraph -> BodyText(text = block.text, state = state, asParagraph = true)

        is ContentBlock.Heading -> Text(
            text = block.text,
            style = state.headingStyle(block.level),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )

        is ContentBlock.Quote -> QuoteText(text = block.text, state = state)

        is ContentBlock.ListItem -> ListItemText(block = block, state = state)

        is ContentBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        is ContentBlock.Image -> ChapterImage(
            path = block.path,
            alt = block.alt,
            caption = block.caption,
            viewModel = viewModel,
            state = state,
        )

        is ContentBlock.Table -> TableBlock(table = block, state = state)
    }
}

/**
 * A table, drawn as a grid.
 *
 * Cells keep their row and column because the parser now returns a grid rather than a flat run of
 * text — previously every cell of a table collapsed into the enclosing paragraph and a two-column
 * comparison table read as one long sentence.
 *
 * [TableCell.rowSpan] is preserved in the model but is **not** merged vertically here: true row
 * spanning needs a layout pass that places cells after their spanning neighbour, which this simple
 * `Row`-of-`Column` composition cannot express. The information is kept so the layout can be
 * upgraded without re-parsing the chapter.
 */
@Composable
private fun TableBlock(table: ContentBlock.Table, state: ReaderUiState) {
    val outline = MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .border(width = 1.dp, color = outline, shape = MaterialTheme.shapes.small),
    ) {
        table.rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) {
                HorizontalDivider(color = outline)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // IntrinsicSize.Min lets the vertical rules between cells span the tallest cell
                    // in the row rather than collapsing to zero height.
                    .height(IntrinsicSize.Min),
            ) {
                row.cells.forEachIndexed { cellIndex, cell ->
                    if (cellIndex > 0) {
                        VerticalDivider(color = outline)
                    }
                    Text(
                        text = cell.text,
                        style = state.bodyTextStyle().let { style ->
                            if (cell.isHeader || row.isHeader) {
                                style.copy(fontWeight = FontWeight.SemiBold)
                            } else {
                                style
                            }
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(cell.colSpan.toFloat())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * A run of the document's body text.
 *
 * [asParagraph] decides only whether the first line is set in: the indent is a paragraph's, and a
 * heading, a quotation, a list item, a table cell or a caption is not one — this is why the flag is
 * passed at the call site rather than folded into [ReaderUiState.bodyTextStyle].
 */
@Composable
private fun BodyText(
    text: AnnotatedString,
    state: ReaderUiState,
    asParagraph: Boolean = false,
) {
    Text(
        text = text,
        style = if (asParagraph) state.paragraphTextStyle() else state.bodyTextStyle(),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * A quotation.
 *
 * Set on a tinted surface rather than in italics: italics are not a convention for quotations in
 * Arabic typography, and a background reads identically in both writing directions where a leading
 * rule has to be mirrored.
 */
@Composable
private fun QuoteText(text: AnnotatedString, state: ReaderUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = state.bodyTextStyle().copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ListItemText(
    block: ContentBlock.ListItem,
    state: ReaderUiState,
    /** False for the continuation of an item that was split across a page break. */
    showMarker: Boolean = true,
) {
    val marker = if (block.ordered) "${block.number}." else "•"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.depth * 16).dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // An empty marker rather than a missing one, so a continuation stays aligned with the item
        // it continues instead of snapping back to the margin.
        Text(
            text = if (showMarker) marker else "",
            style = state.bodyTextStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = block.text,
            style = state.bodyTextStyle(),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * An image inside a chapter, with the caption that belongs to it.
 *
 * Decoded at roughly the reading width rather than at its intrinsic size: an EPUB cover image is
 * frequently 2000px wide, and decoding a dozen of those at full size while scrolling is how a
 * reading app runs out of memory on a mid-range phone.
 */
@Composable
private fun ChapterImage(
    path: String,
    alt: String?,
    caption: AnnotatedString?,
    viewModel: ReaderViewModel,
    state: ReaderUiState,
) {
    val image by produceState<ImageBitmap?>(initialValue = null, path) {
        value = viewModel.chapterImage(path, targetWidthPx = IMAGE_TARGET_WIDTH_PX)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val loaded = image
            when {
                loaded != null -> Image(
                    bitmap = loaded,
                    contentDescription = alt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)),
                )

                alt != null -> Text(
                    text = alt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                else -> CircularProgressIndicator()
            }
        }

        // The caption sits with its picture: it used to be emitted as an unrelated paragraph, so a
        // figure captioned "الشكل ٣" appeared with its label floating somewhere nearby.
        if (caption != null) {
            Text(
                text = caption,
                style = state.bodyTextStyle().copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
            )
        }
    }
}

/**
 * The separator announcing where a chapter begins.
 *
 * A document that names its chapters gets that name in a readable weight; one that does not gets
 * the chapter number, which is all there is to say about it.
 */
@Composable
private fun ChapterMarker(index: Int, title: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title ?: index.toString(),
            style = if (title != null) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.labelMedium
            },
            color = if (title != null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
        )
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * The family the reader sets body text in.
 *
 * `SYSTEM` is the only setting that defers to the document, and it should: a publisher who embedded
 * a face did so because the book is *set* in it, and "no preference" is not a preference. Choosing
 * any other font overrides the book, which is the point of choosing it.
 */
@Composable
internal fun ReaderUiState.readingFontFamily(): FontFamily =
    if (settings.readerFont == ReaderFont.SYSTEM && documentFont != null) {
        documentFont
    } else {
        readerFontFamily(settings.readerFont)
    }

/**
 * Body text with the reader's own font, size and leading applied.
 *
 * The scale is applied to the theme's `bodyLarge` rather than to a hardcoded size, so a change to
 * the app's type scale moves the reader with it, and the Arabic-aware line height set in
 * `:core:core-ui` is preserved rather than replaced.
 */
@Composable
internal fun ReaderUiState.bodyTextStyle(): TextStyle {
    val base = MaterialTheme.typography.bodyLarge
    val size = base.fontSize.value * settings.fontScale
    return base.copy(
        fontFamily = readingFontFamily(),
        fontSize = size.sp,
        lineHeight = (size * settings.lineHeightScale * LINE_HEIGHT_RATIO).sp,
    )
}

/**
 * Body text as a paragraph: the reader's body style, with the first line set in if asked.
 *
 * A paragraph's own style rather than a modification of [bodyTextStyle] at the call site, because
 * the indent is measured in *ems* — it is a proportion of the type, so it follows the font-size
 * slider and stays the same shape of indent at every size a reader might choose.
 *
 * The paged reader measures with this style and draws with it, and the two have to agree: the
 * indent shortens the paragraph's first line, which is a line of text the paginator has to know is
 * shorter. Keying it off the same function is what stops a paginated page from spilling a line past
 * its bottom.
 */
@Composable
internal fun ReaderUiState.paragraphTextStyle(): TextStyle {
    val style = bodyTextStyle()
    if (!settings.firstLineIndent) return style
    return style.copy(
        textIndent = TextIndent(firstLine = (style.fontSize.value * FIRST_LINE_INDENT_EM).sp),
    )
}

/** Headings follow the same scaling as body text so the hierarchy stays proportional. */
@Composable
internal fun ReaderUiState.headingStyle(level: Int): TextStyle {
    val base = when (level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.headlineSmall
        3 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }
    val size = base.fontSize.value * settings.fontScale
    return base.copy(
        fontFamily = readingFontFamily(),
        fontSize = size.sp,
        lineHeight = (size * settings.lineHeightScale * LINE_HEIGHT_RATIO).sp,
    )
}

/** 1.5 is a comfortable default for running text; the user's line-height slider scales it. */
private const val LINE_HEIGHT_RATIO = 1.5f

/**
 * The reader's side margins, scaled by the setting.
 *
 * Read through the state rather than passed in, because both readers need the same number for
 * different purposes — one pads a column with it, the other subtracts twice it from the width it
 * paginates into — and computing it twice is how the two come apart.
 */
internal fun ReaderUiState.readingMargin(): Dp = READING_MARGIN * settings.marginScale

/**
 * The space between two blocks of text, scaled by the setting.
 *
 * Zero is a real value: a document that already separates its paragraphs with blank lines of its own
 * does not need the reader to add another, and a reader who set the slider there has said so.
 */
internal fun ReaderUiState.paragraphSpacing(): Dp =
    PARAGRAPH_SPACING_BASE * settings.paragraphSpacingScale

/**
 * How much of a screenful a side tap moves.
 *
 * Not a whole one: repeating a line or two at the top is what stops a reader losing their place at
 * a page boundary, and it is why the gesture stays useful when the text is being skimmed rather than
 * read closely.
 */

private val READING_MARGIN = 20.dp

/**
 * The gap between two blocks, before the reader's own multiplier is applied.
 *
 * Shared with the paged reader rather than written twice: the paginator has to be told the same
 * number the column is laid out with, or a page comes out with a gap in it the text was not
 * measured to leave.
 */
internal val PARAGRAPH_SPACING_BASE = 10.dp

/** An indent of one and a half ems, the printed-book proportion at any font size. */
private const val FIRST_LINE_INDENT_EM = 1.5f

private const val IMAGE_TARGET_WIDTH_PX = 1080
