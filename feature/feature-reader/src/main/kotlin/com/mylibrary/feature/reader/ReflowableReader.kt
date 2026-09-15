package com.mylibrary.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged

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

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = READING_MARGIN,
            end = READING_MARGIN,
            top = 24.dp,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = state.totalUnits, key = { index -> index }) { chapterIndex ->
            ChapterContent(
                chapterIndex = chapterIndex,
                viewModel = viewModel,
                state = state,
            )
        }
    }
}

/** One chapter: its title (if any) followed by its blocks. */
@Composable
private fun ChapterContent(
    chapterIndex: Int,
    viewModel: ReaderViewModel,
    state: ReaderUiState,
) {
    val blocks by produceState<List<ContentBlock>>(
        initialValue = emptyList(),
        chapterIndex,
    ) {
        value = viewModel.chapterContent(chapterIndex)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // A chapter with no title — every chapter of a plain TXT file — gets a numbered divider
        // instead, so the reader can still tell where one part ends and the next begins.
        if (chapterIndex > 0) {
            ChapterMarker(index = chapterIndex, title = null)
        }

        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Paragraph -> BodyText(text = block.text, state = state)
                is ContentBlock.Heading -> Text(
                    text = block.text,
                    style = state.headingStyle(block.level),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )

                is ContentBlock.Quote -> QuoteText(text = block.text, state = state)

                is ContentBlock.ListItem -> ListItemText(
                    block = block,
                    state = state,
                )

                is ContentBlock.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                is ContentBlock.Image -> ChapterImage(
                    path = block.path,
                    alt = block.alt,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun BodyText(text: AnnotatedString, state: ReaderUiState) {
    Text(
        text = text,
        style = state.bodyTextStyle(),
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
private fun ListItemText(block: ContentBlock.ListItem, state: ReaderUiState) {
    val marker = if (block.ordered) "${block.number}." else "•"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.depth * 16).dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = marker,
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
 * An image inside a chapter.
 *
 * Decoded at roughly the reading width rather than at its intrinsic size: an EPUB cover image is
 * frequently 2000px wide, and decoding a dozen of those at full size while scrolling is how a
 * reading app runs out of memory on a mid-range phone.
 */
@Composable
private fun ChapterImage(path: String, alt: String?, viewModel: ReaderViewModel) {
    val image by produceState<ImageBitmap?>(initialValue = null, path) {
        value = viewModel.chapterImage(path, targetWidthPx = IMAGE_TARGET_WIDTH_PX)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
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
}

/** A separator announcing where a chapter begins, for chapters that have no title of their own. */
@Composable
private fun ChapterMarker(index: Int, title: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title ?: index.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Body text with the reader's own font, size and leading applied.
 *
 * The scale is applied to the theme's `bodyLarge` rather than to a hardcoded size, so a change to
 * the app's type scale moves the reader with it, and the Arabic-aware line height set in
 * `:core:core-ui` is preserved rather than replaced.
 */
@Composable
private fun ReaderUiState.bodyTextStyle(): TextStyle {
    val base = MaterialTheme.typography.bodyLarge
    val size = base.fontSize.value * settings.fontScale
    return base.copy(
        fontFamily = com.mylibrary.core.ui.theme.readerFontFamily(settings.readerFont),
        fontSize = size.sp,
        lineHeight = (size * settings.lineHeightScale * LINE_HEIGHT_RATIO).sp,
    )
}

/** Headings follow the same scaling as body text so the hierarchy stays proportional. */
@Composable
private fun ReaderUiState.headingStyle(level: Int): TextStyle {
    val base = when (level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.headlineSmall
        3 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }
    val size = base.fontSize.value * settings.fontScale
    return base.copy(
        fontFamily = com.mylibrary.core.ui.theme.readerFontFamily(settings.readerFont),
        fontSize = size.sp,
        lineHeight = (size * settings.lineHeightScale * LINE_HEIGHT_RATIO).sp,
    )
}

/** 1.5 is a comfortable default for running text; the user's line-height slider scales it. */
private const val LINE_HEIGHT_RATIO = 1.5f

private val READING_MARGIN = 20.dp
private const val IMAGE_TARGET_WIDTH_PX = 1080
