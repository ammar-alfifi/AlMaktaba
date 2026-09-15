package com.mylibrary.feature.search

import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the pure half of the search screen.
 *
 * These cover the two places the screen can go wrong without anyone noticing: a highlight that
 * lands on the wrong characters, and a results list that shows a heading for a book that is not
 * there any more. Both are silent failures — the screen still renders — which is exactly why they
 * are worth asserting rather than eyeballing.
 */
class SearchResultsTest {

    // region highlightRange

    @Test
    fun `highlight covers the matched run and stops before the next character`() {
        // "the quick brown fox", match "quick" at 4..9 — the end is exclusive, which is how every
        // decoder in the project fills these two fields (`matchStart + query.length`).
        val hit = hit(snippet = "the quick brown fox", matchStart = 4, matchEnd = 9)

        assertEquals(4..8, hit.highlightRange())
    }

    @Test
    fun `highlight of a match at the very start of the snippet`() {
        val hit = hit(snippet = "quick brown fox", matchStart = 0, matchEnd = 5)

        assertEquals(0..4, hit.highlightRange())
    }

    @Test
    fun `an end past the snippet is clamped rather than allowed to throw`() {
        // A decoder that reports a length instead of an end index must not take the results list
        // down with it: the worst acceptable outcome is a highlight that stops at the edge.
        val hit = hit(snippet = "short", matchStart = 3, matchEnd = 999)

        assertEquals(3..4, hit.highlightRange())
    }

    @Test
    fun `an inverted or empty range highlights nothing`() {
        assertNull(hit(snippet = "short", matchStart = 3, matchEnd = 3).highlightRange())
        assertNull(hit(snippet = "short", matchStart = 4, matchEnd = 1).highlightRange())
    }

    @Test
    fun `a start outside the snippet is abandoned rather than clamped to the edge`() {
        // Clamping would underline the first characters of a snippet the offset does not describe.
        assertNull(hit(snippet = "short", matchStart = -4, matchEnd = 2).highlightRange())
        assertNull(hit(snippet = "short", matchStart = 5, matchEnd = 9).highlightRange())
    }

    @Test
    fun `an empty snippet highlights nothing`() {
        assertNull(hit(snippet = "", matchStart = 0, matchEnd = 4).highlightRange())
    }

    // endregion

    // region matchRangeIn

    @Test
    fun `title match ignores case, as the library search does`() {
        assertEquals(0..3, matchRangeIn(text = "Dune Messiah", query = "dune"))
        // "The Hobbit": H is at index 4, so the matched run is 4..6.
        assertEquals(4..6, matchRangeIn(text = "The Hobbit", query = "HOB"))
    }

    @Test
    fun `a query that does not occur has nothing to highlight`() {
        assertNull(matchRangeIn(text = "Dune", query = "Foundation"))
        assertNull(matchRangeIn(text = "Dune", query = ""))
    }

    @Test
    fun `an occurrence at the end of the text stays inside it`() {
        // The range is used to slice an AnnotatedString, so it must never address an index past the
        // text it came from — a match flush against the end is the case that would.
        assertEquals(4..5, matchRangeIn(text = "abc de", query = "de"))
    }

    // endregion

    // region addRecentQuery

    @Test
    fun `a new query goes to the front of the history`() {
        assertEquals(listOf("asimov", "dune"), addRecentQuery(listOf("dune"), "asimov"))
    }

    @Test
    fun `re-searching a query moves it rather than duplicating it`() {
        val recents = listOf("dune", "asimov")

        assertEquals(listOf("asimov", "dune"), addRecentQuery(recents, "asimov"))
    }

    @Test
    fun `de-duplication ignores case so the list cannot show near-duplicates`() {
        val recents = listOf("Dune", "asimov")

        assertEquals(listOf("dune", "asimov"), addRecentQuery(recents, "dune"))
    }

    @Test
    fun `queries are trimmed before they are stored`() {
        assertEquals(listOf("dune"), addRecentQuery(emptyList(), "  dune  "))
    }

    @Test
    fun `a blank query is not worth remembering`() {
        val recents = listOf("dune")

        assertEquals(recents, addRecentQuery(recents, "   "))
    }

    @Test
    fun `the history is capped at the most recent entries`() {
        val full = (1..MAX_RECENT_QUERIES).map { "query$it" }

        val updated = addRecentQuery(full, "newest")

        assertEquals(MAX_RECENT_QUERIES, updated.size)
        assertEquals("newest", updated.first())
        // The oldest entry is the one that falls off the end.
        assertEquals(full.dropLast(1), updated.drop(1))
    }

    // endregion

    // region groupContentResults

    @Test
    fun `every group carries the book its matches belong to`() {
        val dune = book(id = 1L, title = "Dune")
        val foundation = book(id = 2L, title = "Foundation")

        val groups = groupContentResults(
            results = linkedMapOf(1L to listOf(hit(), hit()), 2L to listOf(hit())),
            books = mapOf(1L to dune, 2L to foundation),
        )

        assertEquals(listOf(dune, foundation), groups.map { it.book })
        assertEquals(listOf(2, 1), groups.map { it.hits.size })
    }

    @Test
    fun `the library's order is preserved rather than resorted`() {
        val results = linkedMapOf(
            3L to listOf(hit()),
            1L to listOf(hit()),
            2L to listOf(hit()),
        )

        val groups = groupContentResults(
            results = results,
            books = mapOf(1L to book(1L, "A"), 2L to book(2L, "B"), 3L to book(3L, "C")),
        )

        assertEquals(listOf(3L, 1L, 2L), groups.map { it.book.id })
    }

    @Test
    fun `a book deleted while the scan was running leaves no group behind`() {
        val groups = groupContentResults(
            results = mapOf(1L to listOf(hit()), 404L to listOf(hit())),
            books = mapOf(1L to book(1L, "Dune")),
        )

        assertEquals(listOf(1L), groups.map { it.book.id })
    }

    @Test
    fun `a book with no matches is not drawn as an empty card`() {
        val groups = groupContentResults(
            results = mapOf(1L to emptyList(), 2L to listOf(hit())),
            books = mapOf(1L to book(1L, "Dune"), 2L to book(2L, "Foundation")),
        )

        assertEquals(listOf(2L), groups.map { it.book.id })
    }

    @Test
    fun `an empty result set groups into nothing at all`() {
        assertEquals(emptyList<ContentMatchGroup>(), groupContentResults(emptyMap(), emptyMap()))
    }

    // endregion

    private fun hit(
        snippet: String = "snippet",
        matchStart: Int = 0,
        matchEnd: Int = 3,
    ) = SearchHit(
        locator = ReadingLocator.Paged(pageIndex = 0),
        label = "Page 1",
        snippet = snippet,
        matchStart = matchStart,
        matchEnd = matchEnd,
    )

    private fun book(id: Long, title: String) = Book(
        id = id,
        title = title,
        uri = "content://book/$id",
        format = BookFormat.EPUB,
    )
}
