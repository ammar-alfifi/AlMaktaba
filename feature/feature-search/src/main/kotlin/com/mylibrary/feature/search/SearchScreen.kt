package com.mylibrary.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.usecase.LibrarySearchResult
import com.mylibrary.core.ui.component.BookCover
import com.mylibrary.core.ui.component.EmptyState
import com.mylibrary.core.ui.component.ErrorState
import com.mylibrary.core.ui.component.FeatureScaffold
import com.mylibrary.core.ui.component.LoadingState
import com.mylibrary.core.ui.mvi.ObserveEffects
import kotlinx.coroutines.launch

/**
 * Hosts the search screen: the ViewModel, its effects and the navigation they ask for.
 *
 * `:app` calls this from its navigation graph. Note the split from [SearchScreen]: everything that
 * needs a ViewModel, a coroutine scope or a `Context` lives here, which is what keeps the screen
 * itself a pure function of its state and previewable from a hand-written one.
 *
 * @param onOpenBook opens a book at its last reading position.
 * @param onOpenLocation opens a book *at* an in-book match. Defaults to [onOpenBook], which is the
 *   honest fallback for a host that has no way to pass a locator along: opening the book without
 *   jumping to the hit still gets the user to the right book, whereas dropping the effect would
 *   make the row look broken. Pass it once the reader accepts a start locator.
 */
@Composable
fun SearchRoute(
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onOpenLocation: (Long, ReadingLocator) -> Unit = { bookId, _ -> onOpenBook(bookId) },
) {
    val viewModel: SearchViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    ObserveEffects(viewModel.effects) { effect ->
        when (effect) {
            is SearchEffect.OpenBook -> onOpenBook(effect.bookId)
            is SearchEffect.OpenLocation -> onOpenLocation(effect.bookId, effect.locator)
            is SearchEffect.ShowMessage -> scope.launch {
                // Resolved here rather than in the ViewModel: a message that outlives a locale
                // change should come back in the new language, which a stored String would not.
                snackbarHostState.showSnackbar(
                    context.getString(effect.messageRes, *effect.formatArgs.toTypedArray()),
                )
            }
        }
    }

    FeatureScaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        SearchScreen(
            state = state,
            onIntent = viewModel::onIntent,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * The search screen, as a function of its state.
 *
 * It holds no logic: every branch below is a rendering decision, and every action is reported as an
 * intent. That is what makes it previewable and testable without a ViewModel — and it is why the
 * three "nothing to show" cases are spelled out rather than left to fall through, since a search
 * screen that renders nothing at all is indistinguishable from one that is still working.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onIntent: (SearchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SearchField(
            query = state.query,
            onQueryChange = { onIntent(SearchIntent.QueryChanged(it)) },
            onSubmit = { onIntent(SearchIntent.Submit) },
            onClear = { onIntent(SearchIntent.ClearQuery) },
        )

        SearchOptions(
            searchBookContents = state.searchBookContents,
            isSearching = state.isSearching,
            onToggle = { enabled -> onIntent(SearchIntent.SetSearchInsideBooks(enabled)) },
        )

        SearchBody(
            state = state,
            onIntent = onIntent,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The query field.
 *
 * A Material 3 `SearchBar` pinned to its collapsed state, rather than a plain text field or the
 * expanded search surface. The results are the screen's whole content and they scroll *below* the
 * field, so an expanding overlay would cover the very list the user is scanning; what is wanted
 * from the component is its shape, height and search affordances, not its expansion. The search key
 * on the keyboard still submits through `onSearch`, which is what promotes a query into the recent
 * list.
 *
 * The clear button only appears once there is something to clear, so the field is not cluttered
 * with a control that would do nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { onSubmit() },
                expanded = false,
                onExpandedChange = { },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(text = stringResource(R.string.search_field_placeholder)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.search_cd_clear_query),
                            )
                        }
                    }
                },
            )
        },
        expanded = false,
        onExpandedChange = { },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { }
}

/**
 * The "search inside books" switch, with its own progress.
 *
 * The switch is a chip rather than a row in a settings screen because its cost is a property of the
 * query at hand, not a preference: it is worth turning on for one search and not for the next.
 */
@Composable
private fun SearchOptions(
    searchBookContents: Boolean,
    isSearching: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilterChip(
                selected = searchBookContents,
                onClick = { onToggle(!searchBookContents) },
                label = { Text(text = stringResource(R.string.search_filter_inside_books)) },
                leadingIcon = {
                    if (searchBookContents) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                },
            )

            if (isSearching) {
                // Progress sits beside the chip that caused it: the scan can still be running while
                // results are already on screen, and a spinner over the list would hide the very
                // results that arrived first. The label is added only when the in-book scan is
                // actually part of the work — "searching inside books" during a title-only search
                // would be a claim about what the app is doing that is not true.
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                if (searchBookContents) {
                    Text(
                        text = stringResource(R.string.search_scanning),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (searchBookContents) {
            Text(
                text = stringResource(R.string.search_filter_inside_books_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Everything under the field: results, or one of the three states where there are none.
 *
 * The order of these branches is the screen's answer to "what is the user waiting for?" — a
 * failure outranks everything, an unfinished search shows progress rather than an empty state, and
 * only once a search has settled does "no results" become a true statement.
 */
@Composable
private fun SearchBody(
    state: SearchUiState,
    onIntent: (SearchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = state.error
    when {
        error != null -> ErrorState(
            error = error,
            modifier = modifier,
            // Retrying a search means running it again, which is exactly what Submit does.
            onRetry = { onIntent(SearchIntent.Submit) },
        )

        !state.hasQuery -> NothingTypedYet(
            recentQueries = state.recentQueries,
            onIntent = onIntent,
            modifier = modifier,
        )

        state.isSearching && !state.hasResults -> LoadingState(modifier = modifier)

        !state.hasResults && state.searchBookContents -> EmptyState(
            icon = Icons.Filled.Info,
            title = stringResource(R.string.search_empty_no_results_title),
            message = stringResource(R.string.search_empty_no_results_message, state.submittedQuery),
            modifier = modifier,
        )

        // The user typed something, nothing matched the library's titles and authors, and the text
        // of the books has not been looked at. Saying "no results" here would be a claim the screen
        // has not earned, so it offers the search that would settle it instead.
        !state.hasResults -> EmptyState(
            // The mirrored list icon, not the outlined book one: it survives RTL, and it reads as
            // "the text inside" where a single book would suggest the book level again.
            icon = Icons.AutoMirrored.Filled.List,
            title = stringResource(R.string.search_empty_inside_off_title),
            message = stringResource(R.string.search_empty_inside_off_message),
            modifier = modifier,
            action = {
                Button(onClick = { onIntent(SearchIntent.SetSearchInsideBooks(true)) }) {
                    Text(text = stringResource(R.string.search_empty_inside_off_action))
                }
            },
        )

        else -> ResultsList(state = state, onIntent = onIntent, modifier = modifier)
    }
}

/**
 * The state the screen opens in.
 *
 * Recent queries are offered first: they are the fastest path back to what the user was doing last,
 * and they spare the user from re-typing a query the app already knows. With no history at all the
 * hint explains what the two search modes do, so the screen never opens as a blank field over an
 * empty page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NothingTypedYet(
    recentQueries: List<String>,
    onIntent: (SearchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (recentQueries.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.search_recent_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onIntent(SearchIntent.ClearRecentQueries) }) {
                    Text(text = stringResource(R.string.search_recent_clear))
                }
            }

            // Chips wrap rather than scroll: a query list that hides half its entries off-screen
            // is a list the user will not use.
            FlowRow(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                recentQueries.forEach { recent ->
                    AssistChip(
                        // Two intents, in order: put the query in the field, then run it. Submit is
                        // what remembers the query and what overrides the debounce, so a chip
                        // answers as fast as the keyboard's search key does.
                        onClick = {
                            onIntent(SearchIntent.QueryChanged(recent))
                            onIntent(SearchIntent.Submit)
                        },
                        label = { Text(text = recent) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.search_cd_remove_recent),
                                modifier = Modifier
                                    .size(AssistChipDefaults.IconSize)
                                    .clickable { onIntent(SearchIntent.RemoveRecentQuery(recent)) },
                            )
                        },
                    )
                }
            }
        }

        EmptyState(
            icon = Icons.Filled.Search,
            title = stringResource(R.string.search_empty_hint_title),
            message = stringResource(R.string.search_empty_hint_message),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The results, grouped by where they matched.
 *
 * Library matches come first because they answer the question the user probably asked; in-book
 * matches follow, one card per book, because they are the ones that needed to be asked for.
 */
@Composable
private fun ResultsList(
    state: SearchUiState,
    onIntent: (SearchIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Grouped once per result set rather than per recomposition: joining ids to books is cheap but
    // it is not free, and it allocates on every scroll frame otherwise.
    val groups = remember(state.contentResults, state.contentBooks) {
        groupContentResults(state.contentResults, state.contentBooks)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (state.isSearching) {
            // Results are on screen but the scan is not finished, so the list keeps growing.
            item(key = "scan-progress") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (state.libraryResults.isNotEmpty()) {
            item(key = "library-header") {
                SectionHeader(text = stringResource(R.string.search_section_library))
            }
            items(
                items = state.libraryResults,
                key = { result -> "library-${result.book.id}" },
            ) { result ->
                LibraryResultRow(
                    result = result,
                    query = state.submittedQuery,
                    onClick = { onIntent(SearchIntent.OpenBook(result.book.id)) },
                )
            }
        }

        groups.forEach { group ->
            item(key = "book-${group.book.id}") {
                ContentMatchCard(
                    group = group,
                    onOpenHit = { hit -> onIntent(SearchIntent.OpenHit(group.book.id, hit.locator)) },
                )
            }
        }
    }
}

/** One library match: which book, why it matched, and where it leads. */
@Composable
private fun LibraryResultRow(
    result: LibrarySearchResult,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = result.book
    val author = book.author
    val highlightColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        leadingContent = {
            BookCover(book = book, modifier = Modifier.width(40.dp), contentDescription = null)
        },
        overlineContent = {
            // Named explicitly rather than implied by the highlight alone: the same word can appear
            // in both fields, and "which one matched" is what tells the user whether the book is
            // the one they meant.
            Text(
                text = stringResource(
                    when (result.matchedOn) {
                        LibrarySearchResult.MatchField.TITLE -> R.string.search_match_in_title
                        LibrarySearchResult.MatchField.AUTHOR -> R.string.search_match_in_author
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(
                text = highlighted(
                    text = book.title,
                    range = if (result.matchedOn == LibrarySearchResult.MatchField.TITLE) {
                        matchRangeIn(book.title, query)
                    } else {
                        null
                    },
                    color = highlightColor,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = if (author.isNullOrBlank()) {
                    AnnotatedString(book.format.displayName)
                } else {
                    highlighted(
                        text = author,
                        range = if (result.matchedOn == LibrarySearchResult.MatchField.AUTHOR) {
                            matchRangeIn(author, query)
                        } else {
                            null
                        },
                        color = highlightColor,
                    )
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = onSurfaceVariant,
            )
        },
    )
}

/**
 * One book's in-book matches, as a card.
 *
 * A card per book rather than a flat list of hits: the book is the unit the user thinks in ("which
 * of my books mentions this?"), and the boundary between one book's matches and the next is
 * otherwise invisible once the snippets start scrolling.
 */
@Composable
private fun ContentMatchCard(
    group: ContentMatchGroup,
    onOpenHit: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightStyle = SpanStyle(
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        background = MaterialTheme.colorScheme.tertiaryContainer,
    )
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BookCover(book = group.book, modifier = Modifier.width(32.dp), contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.book.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.search_result_count,
                            group.hits.size,
                            group.hits.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurfaceVariant,
                    )
                }
            }

            group.hits.forEachIndexed { index, hit ->
                ListItem(
                    modifier = Modifier.clickable { onOpenHit(hit) },
                    overlineContent = {
                        hit.label?.let { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = onSurfaceVariant,
                            )
                        }
                    },
                    headlineContent = {
                        Text(
                            text = highlighted(
                                text = hit.snippet,
                                range = hit.highlightRange(),
                                style = highlightStyle,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                // A hairline between this book's matches, but not after the last one, which is
                // followed by the card's own edge.
                if (index < group.hits.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

/** The heading above a group of results. */
@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** [text] with [range] emphasized, or [text] untouched when there is nothing to emphasize. */
private fun highlighted(
    text: String,
    range: IntRange?,
    color: Color? = null,
    style: SpanStyle? = null,
): AnnotatedString = buildAnnotatedString {
    append(text)
    if (range == null) return@buildAnnotatedString

    val span = style ?: SpanStyle(color = color ?: Color.Unspecified, fontWeight = FontWeight.SemiBold)
    // `range` comes from the pure helpers above, which return an exclusive end already clamped to
    // the text, so `first until last + 1` can never address an index past its end.
    addStyle(style = span, start = range.first, end = range.last + 1)
}
