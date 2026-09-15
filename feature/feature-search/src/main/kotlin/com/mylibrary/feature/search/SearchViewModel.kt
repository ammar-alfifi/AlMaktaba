package com.mylibrary.feature.search

import androidx.lifecycle.viewModelScope
import com.mylibrary.core.common.AppError
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.usecase.ObserveLibraryUseCase
import com.mylibrary.core.domain.usecase.SearchAcrossBooksUseCase
import com.mylibrary.core.domain.usecase.SearchLibraryUseCase
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
 * milliseconds. The second, [SearchAcrossBooksUseCase], opens every book in turn, runs a full-text
 * search and closes it again — seconds to minutes, depending on the library. This class exists
 * because those two must not be conflated: the cheap one runs as the user types, the expensive one
 * only when they explicitly ask for it, and the screen shows a progress indicator while it runs
 * rather than pretending a scan of a whole library is instant.
 *
 * `SearchInDocumentUseCase` is deliberately absent. Searching inside one book needs an
 * already-open document, which only the reader owns; the reader screen searches its own book, and
 * this screen points at it by navigating to a locator.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchLibrary: SearchLibraryUseCase,
    private val searchAcrossBooks: SearchAcrossBooksUseCase,
    private val observeLibrary: ObserveLibraryUseCase,
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
    }

    override fun onIntent(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.QueryChanged -> onQueryChanged(intent.query)
            SearchIntent.Submit -> onSubmit()
            SearchIntent.ClearQuery -> clearQuery()
            is SearchIntent.SetSearchInsideBooks -> setSearchBookContents(intent.enabled)
            is SearchIntent.OpenBook -> openBook(intent.bookId)
            is SearchIntent.OpenHit -> openHit(intent.bookId, intent)
            is SearchIntent.RemoveRecentQuery ->
                setState { copy(recentQueries = recentQueries - intent.query) }
            SearchIntent.ClearRecentQueries -> setState { copy(recentQueries = emptyList()) }
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
     * Adds a submitted query to this session's history.
     *
     * Only explicit submissions are remembered, never the intermediate states of typing: a debounced
     * search runs for "d", "du" and "dun" on the way to "dune", and a history holding all four would
     * be a history of the keyboard, not of the user's intentions.
     *
     * The history lives in this ViewModel and is gone when the screen is closed for good. Persisting
     * it — a DataStore key or a small table in `:core:core-data` — is a reasonable follow-up, but it
     * is not done here: it would add a storage dependency to a feature that works without one, and
     * search history is not worth a dependency the project does not already have.
     */
    private fun rememberQuery(query: String) {
        if (query.isEmpty()) return
        setState { copy(recentQueries = addRecentQuery(recentQueries, query)) }
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
     * Runs the in-book scan over the library, capped at [MAX_BOOKS_SCANNED] books.
     *
     * Failures are reported rather than thrown: `SearchAcrossBooksUseCase` already skips books it
     * cannot open, so anything reaching here is a failure of the scan as a whole, and the partial
     * results the user is looking at are still worth keeping on screen.
     */
    private suspend fun scanBookContents(query: String) {
        val scan = books.take(MAX_BOOKS_SCANNED)
        if (scan.isEmpty()) return

        val hits = try {
            searchAcrossBooks(scan, query)
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

        if (books.size > scan.size) {
            // Say so rather than let an empty card read as "not in any of your books": the scan
            // stopped early, and a user who is told that can narrow the library instead of
            // concluding the text is not there. The count is passed with it — the string has a
            // placeholder, and leaving it unformatted printed "%1$d" on screen.
            sendEffect(
                SearchEffect.ShowMessage(
                    messageRes = R.string.search_message_scan_capped,
                    formatArgs = listOf(scan.size),
                ),
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

        /**
         * The most books one in-book scan will open.
         *
         * `SearchAcrossBooksUseCase` opens every book, scans its full text and closes it again —
         * per book, that is a container parse plus a text scan, and on a library of a few hundred
         * books it is minutes of work and a visible amount of battery. The scan therefore stops at
         * the first [MAX_BOOKS_SCANNED] books, which `ObserveLibraryUseCase` orders by
         * `RECENTLY_ADDED`, so the cap falls on the books the user is least likely to be thinking
         * about. The user is told when it happens; scanning the remainder belongs in a background
         * worker, not in a screen the user is waiting on.
         */
        const val MAX_BOOKS_SCANNED = 20
    }
}
