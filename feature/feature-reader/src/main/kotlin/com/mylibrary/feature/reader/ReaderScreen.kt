package com.mylibrary.feature.reader

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mylibrary.core.domain.model.ReadingDirection
import com.mylibrary.core.ui.component.ErrorState
import com.mylibrary.core.ui.component.LoadingState
import androidx.compose.material3.ExperimentalMaterial3Api
import com.mylibrary.core.ui.mvi.ObserveEffects
import com.mylibrary.core.ui.theme.ProvideLayoutDirection
import androidx.compose.ui.unit.LayoutDirection as ComposeLayoutDirection

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
    modifier: Modifier = Modifier,
) {
    val viewModel: ReaderViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingMessage by remember { mutableStateOf<ReaderMessage?>(null) }
    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            ReaderEffect.NavigateBack -> onBack()
            is ReaderEffect.ShowMessage -> pendingMessage = effect.message
        }
    }

    val messageText = pendingMessage?.let { message -> readerMessageText(message) }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            pendingMessage = null
        }
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

@OptIn(ExperimentalMaterial3Api::class)
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

            state.totalUnits == 0 -> LoadingState()

            else -> ProvideLayoutDirection(contentDirection) {
                if (state.isPaged) {
                    PagedReaderContent(
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
                .padding(bottom = 96.dp),
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
 * The reader's toolbar: where you are, and everything you can do from here.
 *
 * Bookmark is a toggle in the toolbar rather than buried in a menu because it is the action people
 * take mid-page; everything else is one tap away and none of it is destructive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    onBack: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(com.mylibrary.core.ui.R.string.ui_close),
                    )
                }
            },
            title = {
                Column {
                    Text(
                        text = state.book?.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val label = state.positionLabel
                    if (label != null) {
                        Text(
                            text = if (state.isPaged) {
                                stringResource(
                                    com.mylibrary.core.ui.R.string.ui_page_of,
                                    state.currentUnit + 1,
                                    state.totalUnits,
                                )
                            } else {
                                label
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = { onIntent(ReaderIntent.ToggleBookmark) }) {
                    Icon(
                        imageVector = if (state.isBookmarked) {
                            Icons.Filled.Bookmark
                        } else {
                            Icons.Filled.BookmarkBorder
                        },
                        contentDescription = stringResource(R.string.reader_bookmark),
                        tint = if (state.isBookmarked) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = { onIntent(ReaderIntent.OpenPanel(ReaderPanel.TABLE_OF_CONTENTS)) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(R.string.reader_toc),
                    )
                }
                IconButton(onClick = { onIntent(ReaderIntent.OpenPanel(ReaderPanel.SEARCH)) }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.reader_search_action),
                    )
                }
                IconButton(onClick = { onIntent(ReaderIntent.OpenPanel(ReaderPanel.BOOKMARKS)) }) {
                    Icon(
                        imageVector = Icons.Filled.BookmarkBorder,
                        contentDescription = stringResource(R.string.reader_bookmarks),
                    )
                }
                IconButton(onClick = { onIntent(ReaderIntent.OpenPanel(ReaderPanel.SETTINGS)) }) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.reader_settings),
                    )
                }
            },
        )
    }
}

/**
 * The progress bar and page slider.
 *
 * The slider is the one control that makes a 900-page PDF navigable: dragging it is debounced
 * upstream in the ViewModel, so scrubbing across the whole book writes to the database once, at the
 * end, rather than nine hundred times.
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
                    text = stringResource(
                        com.mylibrary.core.ui.R.string.ui_page_of,
                        (state.currentUnit + 1).coerceAtMost(state.totalUnits),
                        state.totalUnits,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(2.dp),
                )
            }

            Slider(
                value = state.currentUnit.toFloat(),
                onValueChange = { value -> onIntent(ReaderIntent.PageChanged(value.toInt())) },
                valueRange = 0f..((state.totalUnits - 1).coerceAtLeast(1)).toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

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
        ReaderMessage.NoSearchResults -> R.string.reader_no_results
        ReaderMessage.SearchUnavailable -> R.string.reader_search_unavailable
    },
)
