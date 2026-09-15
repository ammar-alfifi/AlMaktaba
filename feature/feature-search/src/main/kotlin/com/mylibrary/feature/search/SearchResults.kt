package com.mylibrary.feature.search

import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.SearchHit

/**
 * The presentation-only arithmetic behind the search screen, deliberately kept out of both the
 * ViewModel and the composables.
 *
 * These functions are pure and free of Compose and Android types, which is what makes the fiddly
 * parts — offset clamping and history trimming — testable as plain JVM unit tests instead of
 * through a rendered screen. The composables own only the last step: painting the ranges these
 * functions return.
 */

/** How many recent queries the screen keeps. Long enough to be useful, short enough to scan. */
internal const val MAX_RECENT_QUERIES = 8

/** One book's in-book matches, ready to be drawn as a single section of the results list. */
internal data class ContentMatchGroup(
    val book: Book,
    val hits: List<SearchHit>,
)

/**
 * The half-open range of [SearchHit.snippet] the match covers, or `null` when there is nothing to
 * highlight.
 *
 * Every decoder in the project fills `matchStart`/`matchEnd` as `matchStart + query.length`, so the
 * end is exclusive and the range to style is `start until end`.
 *
 * The bounds checking is not defensive noise: `SearchHit` is a plain data class that any decoder —
 * or any future one — can construct, and an index past the end of the snippet would throw inside
 * `AnnotatedString.addStyle` and take the whole results list, not just the highlight, down with it.
 * A snippet rendered unhighlighted is a far better failure than a crashed screen.
 *
 * The two kinds of bad offset are treated differently on purpose. An over-long *end* is clamped,
 * because the text before it is still the match and highlighting "qui" of "quick" is a small,
 * visible truth. A *start* outside the snippet is not clamped but abandoned: it means the decoder's
 * offsets are not describing this snippet at all, and guessing which characters it meant would
 * underline text the user never searched for.
 */
internal fun SearchHit.highlightRange(): IntRange? {
    val lastIndex = snippet.lastIndex
    if (lastIndex < 0 || matchStart !in 0..lastIndex) return null

    val end = matchEnd.coerceIn(matchStart, lastIndex + 1)
    return if (end > matchStart) matchStart until end else null
}

/**
 * The range of [text] that [query] matches, ignoring case, or `null` when it does not occur.
 *
 * This mirrors the matching rule in `SearchLibraryUseCase` (`contains(query, ignoreCase = true)`)
 * so a book that reached the results list always has something to highlight in the row that shows
 * why it matched.
 */
internal fun matchRangeIn(text: String, query: String): IntRange? {
    if (query.isEmpty()) return null
    val start = text.indexOf(query, ignoreCase = true)
    if (start < 0) return null
    // No clamping needed here, unlike [highlightRange]: the index comes from `indexOf`, which can
    // only return a position the whole query fits behind.
    return start until start + query.length
}

/**
 * [query] moved to the front of [recents], de-duplicated and capped.
 *
 * Matching is case-insensitive so that searching "dune" after "Dune" corrects the entry instead of
 * adding a second one that looks like a duplicate to the user but is not to `equals`.
 */
internal fun addRecentQuery(recents: List<String>, query: String): List<String> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return recents

    return (listOf(normalized) + recents.filterNot { it.equals(normalized, ignoreCase = true) })
        .take(MAX_RECENT_QUERIES)
}

/**
 * Pairs each entry of [results] with the book it belongs to, dropping anything that cannot be drawn.
 *
 * A book deleted while the scan was running leaves an id behind in the map with no book to name it;
 * dropping that group is the only honest option, since rendering it would produce a heading with no
 * title and a row that navigates to a book that no longer exists. The incoming order is preserved
 * because it is the library's order, which is the order the user just saw on the library screen.
 */
internal fun groupContentResults(
    results: Map<Long, List<SearchHit>>,
    books: Map<Long, Book>,
): List<ContentMatchGroup> = results.entries.mapNotNull { (bookId, hits) ->
    val book = books[bookId] ?: return@mapNotNull null
    if (hits.isEmpty()) return@mapNotNull null
    ContentMatchGroup(book = book, hits = hits)
}
