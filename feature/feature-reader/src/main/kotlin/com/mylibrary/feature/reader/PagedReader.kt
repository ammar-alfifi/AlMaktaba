package com.mylibrary.feature.reader

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.mylibrary.core.ui.component.ErrorState
import kotlinx.coroutines.flow.distinctUntilChanged

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
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * One page, with pinch-to-zoom, pan and double-tap-to-zoom.
 *
 * The page is rendered at the size of the viewport, and re-rendered at a higher resolution once the
 * user has zoomed past the point where a 1:1 render would look soft. That is the compromise that
 * keeps memory bounded: rendering every page at 3x up front would allocate nine times the memory
 * for pages the user may never zoom into, while never re-rendering would leave zoomed text blurry on
 * exactly the documents — scanned PDFs and comics — where zooming is the whole point.
 */
@Composable
private fun ZoomablePage(
    pageIndex: Int,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Bucketed to whole steps so that a continuous pinch does not request a new render on every
    // frame; only crossing 2x or 3x triggers a sharper render.
    val resolutionStep = remember(scale) { scale.coerceIn(1f, MAX_RENDER_SCALE).toInt().coerceAtLeast(1) }

    val renderState by produceState<PageRenderState>(
        initialValue = PageRenderState.Loading,
        pageIndex,
        containerSize,
        resolutionStep,
    ) {
        if (containerSize.width > 0 && containerSize.height > 0) {
            value = viewModel.renderPage(
                pageIndex = pageIndex,
                widthPx = containerSize.width * resolutionStep,
                heightPx = containerSize.height * resolutionStep,
            )
        }
    }

    Box(
        modifier = modifier
            .background(PAGE_BACKGROUND)
            .onSizeChanged { size -> containerSize = size }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    // Panning is only meaningful when the page overflows the viewport; letting it
                    // drift while the page fits would slide the page off-screen for no reason.
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { viewModel.onIntent(ReaderIntent.ToggleChrome) },
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
                contentScale = ContentScale.Fit,
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
                onRetry = { viewModel.onIntent(ReaderIntent.Retry) },
            )

            PageRenderState.Loading -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private val PAGE_SPACING = 8.dp

/** A neutral ground behind pages, matching how PDF readers present a paper page. */
private val PAGE_BACKGROUND = Color(0xFF2B2B2B)

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val MAX_RENDER_SCALE = 3f
private const val DOUBLE_TAP_SCALE = 2.5f
