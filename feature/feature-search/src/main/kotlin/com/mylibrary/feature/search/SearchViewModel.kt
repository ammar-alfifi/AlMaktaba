package com.mylibrary.feature.search

import androidx.lifecycle.viewModelScope
import com.mylibrary.core.common.AppError
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.usecase.ObserveLibraryUseCase
import com.mylibrary.core.domain.usecase.BuildSearchIndexUseCase
import com.mylibrary.core.domain.usecase.SearchIndexedBooksUseCase
import com.mylibrary.core.domain.usecase.SearchLibraryUseCase
import com.mylibrary.core.domain.repository.SearchHistoryRepository
import com.mylibrary.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The search screen's logic: two searches of very different cost, behind one intent.
 *
 * The first, [SearchLibraryUseCase], runs over titles and authors already in memory and answers in
 * milliseconds. The second, [SearchIndexedBooksUseCase], searches the persisted full-text index the
 * library builds once — a book is opened to be indexed, not on every query. This class exists
 * because those two must not be conflated: the cheap one runs as the user types, the indexed one
 * only when they explicitly ask for it, and the screen shows a progress indicator while the index is
 * first built rather than pretending a scan of a whole library is instant.
 *
 * `SearchInDocumentUseCase` is deliberately absent. Searching inside one book needs an
 * already-open document, which only the reader owns; the reader screen searches its own book, and
 * this screen points at it by navigating to a locator.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchLibrary: SearchLibraryUseCase,
    private val observeLibrary: ObserveLibraryUseCase,
    private val searchHistory: SearchHistoryRepository,
    private val buildSearchIndex: BuildSearchIndexUseCase,
    private val searchIndexedBooks: SearchIndexedBooksUseCase,
) : MviViewModel<SearchUiState, SearchIntent, SearchEffect>(SearchUiState()) {

    /**
     * The library as it currently stands, newest first.
     *
     * Held outside the state object because the screen never draws the whole library — it is only
     * the pool the in-book scan takes its books from.
     */
    private var books: List<Book> = emptyList()
    private var booksById: Map<Long, Book> = emptyMap()

    /** The query currently being searched, if any. Cancelled and replaced on every new query. */
    private var searchJob: Job? = null

    init {
        launch {
            observeLibrary().collect { items ->
                books = items.map { it.book }
                booksById = books.associateBy { it.id }

                // A book deleted from the library — from another screen, or another process — must
                // not leave a group of matches behind pointing at something that is gone.
                setState {
                    copy(
                        contentResults = contentResults.filterKeys { it in booksById },
                        contentBooks = contentBooks.filterKeys { it in booksById },
                    )
                }
            }
        }

        // The history is persisted, so it is read back rather than seeded empty: a query submitted
        // in a previous session is still offered on this one.
        launch {
            searchHistory.recentQueries.collect { queries ->
                setState { copy(recentQueries = queries) }
            }
        }
    }

    override fun onIntent(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.QueryChanged -> onQueryChanged(intent.query)
            SearchIntent.Submit -> onSubmit()
            SearchIntent.ClearQuery -> clearQuery()
            is SearchIntent.SetSearchInsideBooks -> setSearchBookContents(intent.enabled)
            is SearchIntent.OpenBook -> openBook(intent.bookId)
            is SearchIntent.OpenHit -> openHit(intent.bookId, intent)
            is SearchIntent.RemoveRecentQuery -> removeRecentQuery(intent.query)
            SearchIntent.ClearRecentQueries -> clearRecentQueries()
        }
    }

    /**
     * A keystroke: record it, then schedule a search once the typing stops.
     *
     * The debounce is the whole reason typing stays smooth. A library search re-reads every book
     * row and filters it, and a fast typist produces a keystroke every ~80 ms, so searching per
     * keystroke would run a dozen queries to answer one question — and, worse, would make the
     * results list flicker through the intermediate prefixes ("d", "du", "dun") that the user never
     * meant to search for. Waiting [LIBRARY_DEBOUNCE_MS] after the last keystroke collapses all of
     * them into the one query the user actually finished typing; every earlier attempt is cancelled
     * before it runs, so only one search ever reaches the repository.
     *
     * The spinner is raised immediately rather than after the delay: it reports that the screen
     * *owes* the user results, and a field that sits silent for a quarter of a second after a
     * keystroke reads as a screen that missed the tap.
     */
    private fun onQueryChanged(query: String) {
        setState { copy(query = query, error = null) }

        if (query.isBlank()) {
            // Emptying the field is not a search for "": the results and the spinner go at once, so
            // the screen falls straight back to the recent-query chips.
            cancelSearch()
            setState {
                copy(
                    isSearching = false,
                    libraryResults = emptyList(),
                    contentResults = emptyMap(),
                    contentBooks = emptyMap(),
                )
            }
            return
        }

        startSearch(query.trim(), debounce = true)
    }

    /** The user asked for this query outright, so it runs now and is remembered. */
    private fun onSubmit() {
        val query = currentState.submittedQuery
        if (query.isEmpty()) {
            launch { sendEffect(SearchEffect.ShowMessage(R.string.search_message_empty_query)) }
            return
        }
        rememberQuery(query)
        startSearch(query, debounce = false)
    }

    private fun clearQuery() {
        cancelSearch()
        setState {
            copy(
                query = "",
                isSearching = false,
                libraryResults = emptyList(),
                contentResults = emptyMap(),
                contentBooks = emptyMap(),
                error = null,
            )
        }
    }

    /**
     * Switches the in-book scan on or off, and acts on it immediately.
     *
     * With a query already in the field, both directions re-run that query: turning the scan on
     * should start it rather than wait for another keystroke, and turning it off must stop one that
     * is already opening books. With an empty field there is nothing to run, so the flag alone is
     * the whole change and the next query picks it up.
     */
    private fun setSearchBookContents(enabled: Boolean) {
        if (enabled == currentState.searchBookContents) return
        setState { copy(searchBookContents = enabled) }

        val query = currentState.submittedQuery
        if (query.isEmpty()) {
            cancelSearch()
            setState { copy(isSearching = false, contentResults = emptyMap(), contentBooks = emptyMap()) }
            return
        }
        startSearch(query, debounce = false)
    }

    /**
     * Opens a book, remembering the query that led there: a result the user opened is the best
     * evidence there is that the query was worth keeping.
     */
    private fun openBook(bookId: Long) {
        rememberQuery(currentState.submittedQuery)
        launch { sendEffect(SearchEffect.OpenBook(bookId)) }
    }

    /** Opens a book *at* the match, so the reader lands on the page that was searched for. */
    private fun openHit(bookId: Long, intent: SearchIntent.OpenHit) {
        rememberQuery(currentState.submittedQuery)
        launch { sendEffect(SearchEffect.OpenLocation(bookId, intent.locator)) }
    }

    /**
     * Adds a submitted query to the history, and writes the history down.
     *
     * Only explicit submissions are remembered, never the intermediate states of typing: a debounced
     * search runs for "d", "du" and "dun" on the way to "dune", and a history holding all four would
     * be a history of the keyboard, not of the user's intentions.
     *
     * The history outlives the screen: [persistHistory] writes it to `SearchHistoryRepository`, so a
     * reader who found a useful query still has it after closing the app. The trimming and
     * de-duplication stay here — [addRecentQuery] — because what makes a history useful is a
     * presentation decision, and the store only remembers the result.
     */
    private fun rememberQuery(query: String) {
        if (query.isEmpty()) return
        persistHistory(addRecentQuery(currentState.recentQueries, query))
    }

    /** Drops one query from the history, in the state and in the store together. */
    private fun removeRecentQuery(query: String) {
        persistHistory(currentState.recentQueries - query)
    }

    /** Forgets the whole history, in the state and in the store together. */
    private fun clearRecentQueries() {
        persistHistory(emptyList())
    }

    /**
     * Shows [queries] immediately and writes them behind the scenes.
     *
     * The state is updated first so a submitted query appears as a chip the instant it is submitted;
     * the write is what makes it survive leaving the screen. The repository's own flow then emits
     * the same list, which is a no-op for the state.
     */
    private fun persistHistory(queries: List<String>) {
        setState { copy(recentQueries = queries) }
        launch { searchHistory.save(queries) }
    }

    /** Replaces whatever search is running with a fresh one for [query]. */
    private fun startSearch(query: String, debounce: Boolean) {
        if (query.isBlank()) return
        cancelSearch()
        setState { copy(isSearching = true) }

        searchJob = viewModelScope.launch {
            if (debounce) delay(LIBRARY_DEBOUNCE_MS)
            runSearch(query)
        }
    }

    private suspend fun runSearch(query: String) {
        val results = try {
            // A snapshot rather than a live subscription. The flow behind `SearchLibraryUseCase`
            // re-emits whenever the library changes, and a results list that reshuffles under the
            // user's thumb while they are reading it is worse than one that is stale by seconds —
            // and the next keystroke refreshes it anyway.
            searchLibrary(query).first()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            setState { copy(isSearching = false, error = AppError.Unexpected(failure)) }
            return
        }

        setState {
            copy(
                libraryResults = results,
                // Matches from the previous query are dropped the instant a new one runs. Keeping
                // them would show snippets that do not contain what is now in the search field,
                // which reads as the search being broken.
                contentResults = emptyMap(),
                contentBooks = emptyMap(),
                // Still searching only if the in-book scan below is about to start.
                isSearching = searchBookContents,
                error = null,
            )
        }

        if (currentState.searchBookContents) {
            scanBookContents(query)
        }
        setState { copy(isSearching = false) }
    }

    /**
     * Searches the persisted index, building whatever part of it is missing first.
     *
     * [buildSearchIndex] skips every book it has already looked at, so on all but the first search
     * it is one query and a set difference — cheap enough to run before each search, which is also
     * what picks up a book imported since the last one. The whole library is searched: there is no
     * cap, because there is no longer a reason for one.
     *
     * Failures are reported rather than thrown, and the results already on screen are kept: an index
     * that could not be built must not empty a list the user is reading.
     */
    private suspend fun scanBookContents(query: String) {
        if (books.isEmpty()) return

        try {
            buildSearchIndex()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            setState { copy(error = AppError.Unexpected(failure)) }
            return
        }

        val hits = try {
            searchIndexedBooks(query)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            setState { copy(error = AppError.Unexpected(failure)) }
            return
        }

        setState {
            copy(
                contentResults = hits,
                contentBooks = hits.keys.mapNotNull(booksById::get).associateBy { it.id },
            )
        }
    }

    private fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
    }

    private companion object {
        /**
         * How long typing has to pause before the library query runs.
         *
         * Long enough to swallow the gap between keystrokes for an average typist, short enough
         * that a deliberate pause feels answered immediately. It is a pause after the *last*
         * keystroke, so it costs a fast typist nothing.
         */
        const val LIBRARY_DEBOUNCE_MS = 250L
    }
}
