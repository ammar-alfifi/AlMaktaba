package com.mylibrary.feature.search

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.mylibrary.core.common.AppError
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.usecase.LibrarySearchResult

/**
 * Everything the search screen draws, as one value.
 *
 * The three "nothing to show" situations a search screen can be in — nothing typed, a query that
 * matched nothing, and a query whose in-book half is switched off — are *derived* from these fields
 * by [SearchUiState]'s own properties rather than tracked as a separate `mode` enum. A second source
 * of truth for "which empty state am I in" is exactly the kind of state that goes stale, and this
 * screen has to be exhaustive about it because a search screen that renders nothing at all is
 * indistinguishable from one that is still working.
 */
@Immutable
data class SearchUiState(
    /** The raw text in the field, updated on every keystroke, whitespace included. */
    val query: String = "",
    /** True while a query is in flight, including the debounce window and the in-book scan. */
    val isSearching: Boolean = false,
    /** Books whose title or author matched the query. */
    val libraryResults: List<LibrarySearchResult> = emptyList(),
    /** In-book matches, keyed by book id, in the order the scan produced them. */
    val contentResults: Map<Long, List<SearchHit>> = emptyMap(),
    /**
     * The books [contentResults] is keyed by.
     *
     * A search match knows only its book's id, and grouping matches by book needs more than an id
     * to draw a heading. Resolving them here rather than in the screen keeps the composables free of
     * use cases — a stateless screen cannot observe the library, and looking ids up in the results
     * list would drop every book that matched on its contents but not on its title.
     */
    val contentBooks: Map<Long, Book> = emptyMap(),
    /**
     * Whether to search the text of every book, not just titles and authors.
     *
     * Off by default and only ever turned on by an explicit tap: see [SearchViewModel] for what it
     * costs.
     */
    val searchBookContents: Boolean = false,
    /** Queries the user explicitly submitted, most recent first. Session-scoped, not persisted. */
    val recentQueries: List<String> = emptyList(),
    /** The last failure, or `null`. Cleared as soon as the next search starts. */
    val error: AppError? = null,
) {
    /** The query without the surrounding whitespace, which is the one that is actually searched. */
    val submittedQuery: String get() = query.trim()

    /** True once there is something to search for. Drives every "nothing typed yet" branch. */
    val hasQuery: Boolean get() = submittedQuery.isNotEmpty()

    /** True when there is at least one result of either kind to show. */
    val hasResults: Boolean get() = libraryResults.isNotEmpty() || contentResults.isNotEmpty()
}

/** Everything the user can do on the search screen. */
sealed interface SearchIntent {

    /** A keystroke. Debounced by the ViewModel, so this fires far more often than a query runs. */
    data class QueryChanged(val query: String) : SearchIntent

    /** The user asked for this query explicitly — the keyboard's search key, or a recent chip. */
    data object Submit : SearchIntent

    /** Empty the field and the results with it. */
    data object ClearQuery : SearchIntent

    /**
     * Turn the in-book scan on or off.
     *
     * The desired state is carried rather than implied, so the intent means the same thing whether
     * the chip, a deep link or a restored screen produced it.
     */
    data class SetSearchInsideBooks(val enabled: Boolean) : SearchIntent

    /** Open a book at wherever the reader left off. */
    data class OpenBook(val bookId: Long) : SearchIntent

    /** Open a book *at* an in-book match, rather than at the last reading position. */
    data class OpenHit(val bookId: Long, val locator: ReadingLocator) : SearchIntent

    /** Forget one query from the recent list. */
    data class RemoveRecentQuery(val query: String) : SearchIntent

    /** Forget the whole recent list. */
    data object ClearRecentQueries : SearchIntent
}

/**
 * One-shot outcomes the screen cannot express as state.
 *
 * The two navigations carry a book id rather than a route string: the search feature does not know
 * what the app's navigation graph looks like, and routing belongs to `:app`.
 */
sealed interface SearchEffect {

    /** Go to a book's reading position. */
    data class OpenBook(val bookId: Long) : SearchEffect

    /** Go to a book and jump straight to [locator]. */
    data class OpenLocation(val bookId: Long, val locator: ReadingLocator) : SearchEffect

    /** A transient explanation, shown as a snackbar. A resource id, so it localizes at the edge. */
    data class ShowMessage(@param:StringRes val messageRes: Int) : SearchEffect
}
