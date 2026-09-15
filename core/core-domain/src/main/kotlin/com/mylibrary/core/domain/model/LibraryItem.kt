package com.mylibrary.core.domain.model

/**
 * A book as the library screen needs it: the book itself plus how far through it the reader is.
 *
 * Combining the two here rather than in the UI keeps the join in one place and means the library
 * grid, the "continue reading" shelf and the search results all show identical progress.
 */
data class LibraryItem(
    val book: Book,
    val position: ReadingPosition?,
) {
    /** Reading progress in 0f..1f, or `null` if the book has never been opened. */
    val progress: Float? get() = position?.percent

    /** True once the reader has reached the end, within a small tolerance. */
    val isFinished: Boolean get() = (position?.percent ?: 0f) >= FINISHED_THRESHOLD

    private companion object {
        const val FINISHED_THRESHOLD = 0.995f
    }
}
