package com.mylibrary.core.domain.repository

import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit

/**
 * One indexed unit of a book's text — a chapter, or a page's text layer.
 *
 * [locator] points at the unit's start, so opening a hit lands the reader in the right place without
 * the search having to open the book to work out where it is.
 */
data class IndexedChunk(
    val label: String?,
    val locator: ReadingLocator,
    val text: String,
)

/**
 * The persisted full-text index behind "search inside books".
 *
 * The search screen used to open every book in turn on every query, which is why it was capped at
 * the twenty most recent and off by default. This index is what removes both: a book is opened *once*
 * to be indexed, and a query afterwards is a scan of text already on disk. It is deliberately a
 * storage concern rather than a decoder one — the domain decides *what* is indexed, and this contract
 * says nothing about how it is stored.
 */
interface SearchIndexRepository {

    /**
     * The ids of every book that has been indexed — whether or not it held any text.
     *
     * A comic indexes to nothing and still appears here: "we have looked" is the fact that keeps it
     * from being opened again on every pass.
     */
    suspend fun indexedBookIds(): Set<Long>

    /** Replaces everything indexed for [bookId] with [chunks], and marks the book as indexed. */
    suspend fun replaceIndex(bookId: Long, chunks: List<IndexedChunk>)

    /** Forgets a book's index, so the next pass rebuilds it. */
    suspend fun clearIndex(bookId: Long)

    /** Every indexed match for [query], grouped by book id, at most [limitPerBook] per book. */
    suspend fun search(query: String, limitPerBook: Int): Map<Long, List<SearchHit>>
}
