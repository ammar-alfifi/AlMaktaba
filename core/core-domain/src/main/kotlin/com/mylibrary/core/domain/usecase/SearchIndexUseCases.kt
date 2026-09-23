package com.mylibrary.core.domain.usecase

import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.common.getOrNull
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.engine.PagedDocument
import com.mylibrary.core.domain.engine.ReflowableDocument
import com.mylibrary.core.domain.model.LibrarySort
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.SearchHit
import com.mylibrary.core.domain.repository.DocumentRepository
import com.mylibrary.core.domain.repository.IndexedChunk
import com.mylibrary.core.domain.repository.LibraryRepository
import com.mylibrary.core.domain.repository.SearchIndexRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Builds the full-text index for every book that does not have one yet.
 *
 * Opening a book is the expensive part, so this opens each book *once* and never again: a book that
 * has already been indexed is skipped, and a book that cannot be opened at all is still marked as
 * looked at, so a file that will never open does not get re-tried on every pass. New books are picked
 * up because the pass re-reads the library each time it runs — the cost of a second pass over an
 * unchanged library is one query and a set difference.
 */
class BuildSearchIndexUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val documentRepository: DocumentRepository,
    private val searchIndex: SearchIndexRepository,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Indexes every unindexed book, reporting progress as `(done, total)` after each one.
     *
     * @return how many books were looked at in this pass.
     */
    suspend operator fun invoke(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(dispatchers.io) {
        val books = libraryRepository.observeBooks(LibrarySort.RECENTLY_ADDED).first()
        val alreadyIndexed = searchIndex.indexedBookIds()
        val pending = books.filterNot { it.id in alreadyIndexed }
        var done = 0
        for (book in pending) {
            // A book that will not open indexes to nothing and is marked anyway: the index answers
            // "what text is in this library", and a book that cannot be read has none to offer.
            val chunks = documentRepository.open(book).getOrNull()?.let { document ->
                try {
                    document.indexableChunks()
                } finally {
                    document.close()
                }
            } ?: emptyList()

            searchIndex.replaceIndex(book.id, chunks)
            done++
            onProgress(done, pending.size)
        }
        done
    }
}

/**
 * Searches the persisted index, instead of opening books.
 *
 * The whole library is searched, not a slice of it: the cost is a scan of text already stored, so
 * there is no cap to report and nothing to apologise for.
 */
class SearchIndexedBooksUseCase @Inject constructor(
    private val searchIndex: SearchIndexRepository,
    private val dispatchers: DispatcherProvider,
) {

    suspend operator fun invoke(
        query: String,
        limitPerBook: Int = DEFAULT_LIMIT_PER_BOOK,
    ): Map<Long, List<SearchHit>> = withContext(dispatchers.io) {
        val normalized = query.trim()
        if (normalized.isEmpty()) emptyMap() else searchIndex.search(normalized, limitPerBook)
    }

    private companion object {
        const val DEFAULT_LIMIT_PER_BOOK = 20
    }
}

/**
 * The units of [this] document worth indexing.
 *
 * A reflowable document divides by chapter, a paged one by page — and only when it actually has a
 * text layer. A comic has neither, which is why the result is empty rather than an error: there is
 * simply nothing to search, and the index records that the look happened.
 *
 * Each unit's text is read defensively: a single malformed chapter or page must cost that unit, not
 * the whole book's index.
 */
private suspend fun OpenDocument.indexableChunks(): List<IndexedChunk> = when (this) {
    is ReflowableDocument -> (0 until chapterCount).mapNotNull { index ->
        val text = runCatching { chapterText(index) }.getOrNull().orEmpty()
        if (text.isBlank()) {
            null
        } else {
            IndexedChunk(
                label = chapter(index).title,
                locator = ReadingLocator.Reflowable(index, 0),
                text = text,
            )
        }
    }

    is PagedDocument -> if (!capabilities.canExtractText) {
        emptyList()
    } else {
        (0 until pageCount).mapNotNull { index ->
            val text = runCatching { pageText(index) }.getOrNull().orEmpty()
            if (text.isBlank()) null else IndexedChunk(null, ReadingLocator.Paged(index), text)
        }
    }

    else -> emptyList()
}
