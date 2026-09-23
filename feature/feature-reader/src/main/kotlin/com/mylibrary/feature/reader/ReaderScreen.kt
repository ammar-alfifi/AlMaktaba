package com.mylibrary.feature.reader

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mylibrary.core.common.AppError
import com.mylibrary.core.domain.model.ReadingDirection
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.ui.component.ErrorState
import com.mylibrary.core.ui.component.LoadingState
import com.mylibrary.core.ui.mvi.ObserveEffects
import com.mylibrary.core.ui.theme.ProvideLayoutDirection
import androidx.compose.ui.unit.LayoutDirection as ComposeLayoutDirection
import kotlin.math.roundToInt

/**
 * The reader destination.
 *
 * The reader is deliberately outside the app shell — the shell renders no navigation bar for it —
 * so this is the one screen that manages its own back handling: back closes an open panel first,
 * then shows the chrome if it was hidden, and only then leaves the book. That ordering is what stops
 * a back gesture from silently abandoning a page the user was looking at.
 */
@Composable
fun ReaderRoute(
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ReaderViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var pendingMessage by remember { mutableStateOf<ReaderMessage?>(null) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            ReaderEffect.NavigateBack -> onBack()

            // Moving on to the next volume of a series. The reader reports it upward rather than
            // navigating itself, so this screen stays usable from a preview and a test without a
            // navigation host — the same rule the library screen follows.
            is ReaderEffect.OpenBook -> onOpenBook(effect.bookId)

            is ReaderEffect.ShowMessage -> pendingMessage = effect.message

            is ReaderEffect.OpenExternalUrl -> {
                // Opening a URL needs a platform context, which is exactly why the ViewModel emits
                // this as an effect rather than trying to do it itself. A device with no browser —
                // or a scheme nothing handles — must not crash the reader, hence the guard.
                val opened = runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }.isSuccess
                if (!opened) pendingMessage = ReaderMessage.LinkUnavailable
            }
        }
    }

    val message = pendingMessage
    val messageText = message?.let { message -> readerMessageText(message) }
    val undoLabel = stringResource(com.mylibrary.core.ui.R.string.ui_undo)
    LaunchedEffect(message) {
        when {
            // Deletion is the only message with an undo action, so it is the only one shown as a
            // snackbar that stays long enough to be read — the panel it was deleted from closes
            // over the bottom of the screen, where snackbars appear.
            message is ReaderMessage.BookmarkDeleted -> {
                val result = snackbarHostState.showSnackbar(
                    message = messageText.orEmpty(),
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.onIntent(ReaderIntent.UndoDeleteBookmark(message.bookmark))
                }
            }

            messageText != null -> snackbarHostState.showSnackbar(messageText)
        }
        pendingMessage = null
    }

    BackHandler(enabled = state.openPanel != null || state.isChromeVisible) {
        when {
            state.openPanel != null -> viewModel.onIntent(ReaderIntent.ClosePanel)
            else -> viewModel.onIntent(ReaderIntent.ToggleChrome)
        }
    }

    ReaderScreen(
        state = state,
        viewModel = viewModel,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@Composable
fun ReaderScreen(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onIntent: (ReaderIntent) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    KeepScreenOn(enabled = state.settings.keepScreenOn)

    val contentDirection = when {
        state.settings.readingDirection == ReadingDirection.SYSTEM ->
            if (state.isRtlContent) ComposeLayoutDirection.Rtl else ComposeLayoutDirection.Ltr

        state.settings.readingDirection == ReadingDirection.RIGHT_TO_LEFT -> ComposeLayoutDirection.Rtl
        else -> ComposeLayoutDirection.Ltr
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> LoadingState()

            state.error != null -> ErrorState(
                error = state.error,
                onRetry = { onIntent(ReaderIntent.Retry) },
            )

            // Not loading — open and genuinely empty. A document that opens to zero units would
            // otherwise sit on the spinner forever; an error state at least says so and offers a
            // way out.
            state.totalUnits == 0 -> ErrorState(
                error = AppError.EmptyDocument,
                onRetry = { onIntent(ReaderIntent.Retry) },
            )

            else -> ProvideLayoutDirection(contentDirection) {
                // Two questions, asked in that order: what the file is made of, and how the reader
                // asked to see it. Four presentations, one for each answer, and each pair is the
                // same two presentations the other family has — pages to turn, or a column to
                // scroll. The layout setting is the reader's, so it decides for both.
                //
                // The paper is applied inside the direction and outside the `when`, so every
                // presentation gets it — including the seam between two volumes — while the toolbar,
                // the progress bar and the sheets above stay on the app's own theme.
                ReaderPaperSurface(state.settings.readerPaper) {
                    when {
                        !state.hasPages -> if (state.isPageImages) {
                            PagedScrollReaderContent(
                                state = state,
                                viewModel = viewModel,
                                onIntent = onIntent,
                            )
                        } else {
                            ReflowableReaderContent(
                                state = state,
                                viewModel = viewModel,
                                onIntent = onIntent,
                            )
                        }

                        state.isPageImages -> PagedReaderContent(
                            state = state,
                            viewModel = viewModel,
                            onIntent = onIntent,
                        )

                        // Same document, two answers to "how much text is a screenful". The paged
                        // view measures the chapter and turns it in pages; the scrolling one leaves
                        // the text in a single column and lets it move.
                        else -> ReflowablePagedContent(
                            state = state,
                            viewModel = viewModel,
                            onIntent = onIntent,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.isChromeVisible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(state = state, onIntent = onIntent, onBack = onBack)
        }

        AnimatedVisibility(
            visible = state.isChromeVisible && state.settings.showProgressIndicator,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(state = state, onIntent = onIntent)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ReaderChromeClearance),
        ) { data -> Snackbar(snackbarData = data) }
    }

    val openPanel = state.openPanel
    if (openPanel != null) {
        ReaderPanelSheet(panel = openPanel, state = state, onIntent = onIntent)
    }

    if (state.isAwaitingPassword) {
        PasswordDialog(
            wasWrong = state.lastPasswordWasWrong,
            onSubmit = { password -> onIntent(ReaderIntent.PasswordSubmitted(password)) },
            onDismiss = { onIntent(ReaderIntent.PasswordDismissed) },
        )
    }
}

/**
 * The progress bar and page slider.
 *
 * The slider is the one control that makes a 900-page PDF navigable: dragging it is debounced
 * upstream in the ViewModel, so scrubbing across the whole book writes to the database once, at the
 * end, rather than nine hundred times.
 *
 * The label is deliberately built per document kind. A reflowable book has no pages — its position
 * is a chapter — and printing "page 4 of 40" over an EPUB chapter index states something untrue
 * about the file, so the left-hand text names a page only when there are pages, and otherwise names
 * the chapter the reader is actually in.
 */
@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.progressDescription(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = stringResource(
                        com.mylibrary.core.ui.R.string.ui_percent_read,
                        (state.progress * 100).toInt().coerceIn(0, 100),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            ProgressSlider(state = state, onIntent = onIntent)
        }
    }
}

/**
 * The slider under the bar.
 *
 * It measures whatever the bar above it measures. Without a measured book that is the chapter index,
 * as it has always been — the only scale available for a reflowable document, and the right one for a
 * PDF, where a chapter is a page. Once the book has been measured it is the book's *pages*, which
 * makes the slider the same instrument as the percentage beside it: dragging it to the middle of the
 * track puts the reader in the middle of the book, rather than at the start of whichever chapter
 * happens to sit there.
 *
 * A drag sends the reader to the page it lands on through its character offset, which is the only
 * kind of position that survives the text being laid out again — so the jump is handled by the same
 * path a bookmark and a search result take, and lands as precisely as they do.
 */
@Composable
private fun ProgressSlider(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
) {
    val index = state.bookIndex
    val page = state.bookPageNumber
    val total = state.bookPageCount
    if (index != null && page != null && total != null) {
        Slider(
            value = (page - 1).toFloat(),
            onValueChange = { value ->
                index.locate(value.roundToInt() + 1)?.let { target ->
                    onIntent(
                        ReaderIntent.JumpTo(
                            ReadingLocator.Reflowable(target.chapterIndex, target.charOffset),
                        ),
                    )
                }
            },
            valueRange = 0f..(total - 1).coerceAtLeast(1).toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Slider(
            value = state.currentUnit.toFloat(),
            onValueChange = { value -> onIntent(ReaderIntent.PageChanged(value.toInt())) },
            valueRange = 0f..((state.totalUnits - 1).coerceAtLeast(1)).toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * How tall the reader's bottom chrome is, in the content's own terms.
 *
 * Anything the reader must be able to reach at the *end* of a book has to sit above the progress bar
 * and its slider: a scrolling column is drawn under the chrome, so the last thing in the column is
 * otherwise behind it and cannot be scrolled clear. Named once because it is now three callers'
 * number — the snackbar, the reflowable column and the end-of-book panel — and a length written out
 * three times is how a control ends up half-covered by the bar it was measured against.
 */
internal val ReaderChromeClearance = 96.dp

/**
 * Keeps the screen awake while a book is open.
 *
 * Set on the View rather than by acquiring a wake lock: the flag is scoped to the window and is
 * released automatically when the reader goes away, so a crashed or backgrounded reader can never
 * leave the device's screen pinned on.
 */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun readerMessageText(message: ReaderMessage): String = stringResource(
    when (message) {
        ReaderMessage.BookmarkAdded -> R.string.reader_bookmark_added
        ReaderMessage.BookmarkRemoved -> R.string.reader_bookmark_removed
        is ReaderMessage.BookmarkDeleted -> R.string.reader_bookmark_deleted
        ReaderMessage.NoSearchResults -> R.string.reader_no_results
        ReaderMessage.SearchUnavailable -> R.string.reader_search_unavailable
        ReaderMessage.SettingsReset -> R.string.reader_settings_reset_done
        ReaderMessage.LinkUnavailable -> R.string.reader_link_unavailable
    },
)
