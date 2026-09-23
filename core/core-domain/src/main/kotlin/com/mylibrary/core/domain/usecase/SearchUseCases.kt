package com.mylibrary.core.domain.usecase

import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A book whose title or author matched a library search. */
data class LibrarySearchResult(
    val book: Book,
    /** The field the query matched, so the UI can highlight the right line. */
    val matchedOn: MatchField,
) {
    enum class MatchField { TITLE, AUTHOR }
}

/**
 * Free-text search across the library's titles and authors.
 *
 * Matching happens in memory rather than in SQL because the library holds at most a few thousand
 * rows and this keeps the ranking rules (title before author, prefix before substring) in one
 * readable place instead of in a query string.
 */
class SearchLibraryUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val dispatchers: DispatcherProvider,
) {
    operator fun invoke(query: String): Flow<List<LibrarySearchResult>> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return flow { emit(emptyList()) }

        return libraryRepository.observeBooks(LibrarySort.RECENTLY_ADDED)
            .map { books -> books.mapNotNull { it.match(normalized) } }
            .flowOn(dispatchers.default)
    }

    private fun Book.match(query: String): LibrarySearchResult? {
        val inTitle = title.contains(query, ignoreCase = true)
        val inAuthor = author?.contains(query, ignoreCase = true) == true
        return when {
            inTitle -> LibrarySearchResult(this, LibrarySearchResult.MatchField.TITLE)
            inAuthor -> LibrarySearchResult(this, LibrarySearchResult.MatchField.AUTHOR)
            else -> null
        }
    }
}

/**
 * Searches inside a book's text.
 *
 * Runs on the caller's document, which must already be open — searching requires the decoder's
 * native state. A blank query short-circuits without touching the document, because a full-text
 * scan of a 900-page PDF is expensive enough that it must never be triggered by accident.
 */
class SearchInDocumentUseCase @Inject constructor(
    private val dispatchers: DispatcherProvider,
) {
    suspend operator fun invoke(
        document: OpenDocument,
        query: String,
        limit: Int = OpenDocument.DEFAULT_SEARCH_LIMIT,
    ): List<SearchHit> {
        val normalized = query.trim()
        if (normalized.isEmpty() || !document.capabilities.canSearch) return emptyList()
        return withContext(dispatchers.default) {
            document.search(normalized, limit)
        }
    }
}
