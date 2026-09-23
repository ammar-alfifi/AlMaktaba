package com.mylibrary.core.data.repository

import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.data.local.dao.SearchIndexDao
import com.mylibrary.core.data.local.entity.IndexedBookEntity
import com.mylibrary.core.data.local.entity.SearchIndexEntity
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.repository.IndexedChunk
import com.mylibrary.core.domain.repository.SearchIndexRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * The full-text index over Room.
 *
 * Matching is `LIKE` and snippets are built here, in Kotlin, rather than in SQL. The alternative —
 * SQLite's `snippet()` — only exists for FTS, and the index is deliberately a plain table (see
 * `SearchIndexDao`): a personal library's text is small enough that a scan answers instantly, and a
 * plain table keeps the schema, the migration and the queries in the dialect the rest of the data
 * layer already speaks.
 */
@Singleton
class SearchIndexRepositoryImpl @Inject constructor(
    private val dao: SearchIndexDao,
    private val dispatchers: DispatcherProvider,
) : SearchIndexRepository {

    override suspend fun indexedBookIds(): Set<Long> =
        withContext(dispatchers.io) { dao.indexedBookIds().toSet() }

    override suspend fun replaceIndex(bookId: Long, chunks: List<IndexedChunk>) =
        withContext(dispatchers.io) {
            dao.deleteChunks(bookId)
            if (chunks.isNotEmpty()) {
                dao.insertChunks(chunks.map { it.toEntity(bookId) })
            }
            // Marked even when there was nothing to store, so a comic is not re-opened forever.
            dao.markIndexed(IndexedBookEntity(bookId = bookId, indexedAt = System.currentTimeMillis()))
        }

    override suspend fun clearIndex(bookId: Long) = withContext(dispatchers.io) {
        dao.deleteChunks(bookId)
        dao.unmarkIndexed(bookId)
    }

    override suspend fun search(query: String, limitPerBook: Int): Map<Long, List<SearchHit>> =
        withContext(dispatchers.io) {
            val pattern = "%${query.toLikePatternBody()}%"
            dao.searchChunks(pattern, MAX_ROWS)
                .groupBy { it.bookId }
                .mapValues { (_, chunks) ->
                    chunks.mapNotNull { it.toHit(query) }.take(limitPerBook)
                }
                .filterValues { it.isNotEmpty() }
        }

    private companion object {
        /**
         * A memory guard on the raw scan, not a result cap.
         *
         * A one-letter query can match most of the library, and every row carries its unit's full
         * text. Two thousand units is far more than any reader will look through, and it bounds what
         * a single keystroke can allocate.
         */
        const val MAX_ROWS = 2000
    }
}

/** Stores a chunk with its locator flattened to the compact string the reader already parses. */
private fun IndexedChunk.toEntity(bookId: Long): SearchIndexEntity = SearchIndexEntity(
    bookId = bookId,
    label = label,
    locator = locator.encoded(),
    text = text,
)

/** Turns one indexed unit into a hit, or `null` when its locator no longer parses. */
private fun SearchIndexEntity.toHit(query: String): SearchHit? {
    val snippet = buildSnippet(text, query) ?: return null
    val parsed = ReadingLocator.parse(locator) ?: return null
    return SearchHit(
        locator = parsed,
        label = label,
        snippet = snippet.text,
        matchStart = snippet.matchStart,
        matchEnd = snippet.matchEnd,
    )
}

/** A snippet of a chunk, with the matched run's offsets inside it. */
internal data class Snippet(val text: String, val matchStart: Int, val matchEnd: Int)

/**
 * A window of [rawText] around the first occurrence of [query], or `null` when it does not occur.
 *
 * Whitespace is collapsed *before* the match is located, so the offsets describe the text that is
 * actually shown rather than the text it was cut from — a snippet whose highlight is off by the
 * number of newlines before the match is worse than no highlight at all.
 */
internal fun buildSnippet(rawText: String, query: String): Snippet? {
    if (query.isEmpty()) return null

    val text = rawText.replace(WHITESPACE, " ").trim()
    val start = text.indexOf(query, ignoreCase = true)
    if (start < 0) return null
    val end = start + query.length

    val from = (start - SNIPPET_BEFORE).coerceAtLeast(0)
    val to = (end + SNIPPET_AFTER).coerceAtMost(text.length)
    val prefix = if (from > 0) ELLIPSIS else ""
    val suffix = if (to < text.length) ELLIPSIS else ""

    return Snippet(
        text = prefix + text.substring(from, to) + suffix,
        matchStart = prefix.length + (start - from),
        matchEnd = prefix.length + (end - from),
    )
}

/** Mirrors `LibraryRepositoryImpl.toLikePattern` so both searches escape input the same way. */
private fun String.toLikePatternBody(): String = trim()
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

private val WHITESPACE = Regex("\\s+")
private const val SNIPPET_BEFORE = 60
private const val SNIPPET_AFTER = 90
private const val ELLIPSIS = "…"
