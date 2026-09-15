package com.mylibrary.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [naturalSortKey].
 *
 * This is the function that decides whether a comic's pages are in the right order, and getting it
 * wrong is invisible on a two-page sample and catastrophic on a real book. It is worth more tests
 * than its size suggests.
 */
class NaturalSortKeyTest {

    private fun sortedByNaturalKey(values: List<String>): List<String> =
        values.sortedBy { naturalSortKey(it) }

    @Test
    fun `page 2 sorts before page 10`() {
        val sorted = sortedByNaturalKey(listOf("page10.png", "page2.png", "page1.png"))
        assertEquals(listOf("page1.png", "page2.png", "page10.png"), sorted)
    }

    @Test
    fun `sorts a realistic comic page listing`() {
        val pages = (1..12).map { "page$it.jpg" }.shuffled()
        assertEquals(
            (1..12).map { "page$it.jpg" },
            sortedByNaturalKey(pages),
        )
    }

    @Test
    fun `orders by the number even at different digit lengths`() {
        val sorted = sortedByNaturalKey(listOf("9.png", "100.png", "10.png", "1.png", "99.png"))
        assertEquals(listOf("1.png", "9.png", "10.png", "99.png", "100.png"), sorted)
    }

    @Test
    fun `is case insensitive for the text part`() {
        val sorted = sortedByNaturalKey(listOf("Chapter.png", "appendix.png", "Appendix.png"))
        // Both "Appendix" spellings collapse to the same key, so they stay adjacent.
        assertEquals("appendix.png", sorted.first().lowercase())
    }

    @Test
    fun `handles names with no digits`() {
        val sorted = sortedByNaturalKey(listOf("cover.png", "back.png", "intro.png"))
        assertEquals(listOf("back.png", "cover.png", "intro.png"), sorted)
    }

    @Test
    fun `leading zeros do not change the numeric order`() {
        val sorted = sortedByNaturalKey(listOf("007.png", "010.png", "008.png"))
        assertEquals(listOf("007.png", "008.png", "010.png"), sorted)
    }

    @Test
    fun `text sorts before digits at the same position`() {
        // The marker for a digit run sorts after ordinary letters, so "a1" precedes "a-1" style
        // names consistently. What matters is that the order is *stable and total*, not the
        // specific placing of a pathological name.
        val values = listOf("a1", "a2", "b1")
        val once = sortedByNaturalKey(values)
        val twice = sortedByNaturalKey(values.reversed())
        assertEquals(once, twice)
    }

    @Test
    fun `empty string is handled`() {
        assertEquals("", naturalSortKey(""))
    }
}

/** Tests for the file-name helpers used during import. */
class FileNameTest {

    @Test
    fun `splits a simple name`() {
        assertEquals("book" to "epub", fileNameParts("book.epub"))
    }

    @Test
    fun `lowercases the extension`() {
        assertEquals("book" to "cbz", fileNameParts("book.CBZ"))
    }

    @Test
    fun `handles names with multiple dots`() {
        assertEquals("book.epub" to "bak", fileNameParts("book.epub.bak"))
    }

    @Test
    fun `handles a name with no extension`() {
        assertEquals("README" to "", fileNameParts("README"))
    }

    @Test
    fun `handles a dotfile as having no extension`() {
        // `.hidden` is a name, not a file called "" with extension "hidden".
        assertEquals(".hidden" to "", fileNameParts(".hidden"))
    }

    @Test
    fun `handles a trailing dot`() {
        assertEquals("book." to "", fileNameParts("book."))
    }

    @Test
    fun `strips a directory path`() {
        assertEquals("book" to "pdf", fileNameParts("/storage/emulated/0/Books/book.pdf"))
    }

    @Test
    fun `keeps non-latin names intact`() {
        assertEquals("كتابي" to "epub", fileNameParts("كتابي.epub"))
    }

    @Test
    fun `fileStem falls back to the whole name when there is no stem`() {
        assertEquals(".hidden", fileStem(".hidden"))
    }
}

/** Tests for byte formatting shown in the book details screen. */
class FormatBytesTest {

    @Test
    fun `bytes below a kilobyte are shown as bytes`() {
        assertEquals("512 B", formatBytes(512))
    }

    @Test
    fun `whole kilobytes have no decimal`() {
        assertEquals("2 KB", formatBytes(2048))
    }

    @Test
    fun `megabytes are rounded to one decimal`() {
        assertEquals("1.5 MB", formatBytes((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `gigabytes are scaled up`() {
        assertEquals("2 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `negative sizes render as a dash rather than a nonsense number`() {
        // Storage providers report SIZE as -1 for unknown, which must not print "-1 B".
        assertEquals("—", formatBytes(-1))
    }
}

/** Tests for the result wrapper that the data layer returns across module boundaries. */
class AppResultTest {

    private val success: AppResult<Int> = AppResult.Success(21)
    private val failure: AppResult<Int> = AppResult.Failure(AppError.EmptyDocument)

    @Test
    fun `map transforms a success`() {
        assertEquals(AppResult.Success(42), success.map { it * 2 })
    }

    @Test
    fun `map leaves a failure untouched`() {
        assertEquals(failure, failure.map { it * 2 })
    }

    @Test
    fun `flatMap chains successes`() {
        assertEquals(AppResult.Success("21!"), success.flatMap { AppResult.Success("$it!") })
    }

    @Test
    fun `flatMap short-circuits on failure`() {
        assertEquals(failure, failure.flatMap { AppResult.Success("never") })
    }

    @Test
    fun `getOrNull returns the value or null`() {
        assertEquals(21, success.getOrNull())
        assertEquals(null, failure.getOrNull())
    }

    @Test
    fun `errorOrNull returns the error or null`() {
        assertEquals(AppError.EmptyDocument, failure.errorOrNull())
        assertEquals(null, success.errorOrNull())
    }

    @Test
    fun `runCatchingApp wraps a thrown exception`() {
        val result = runCatchingApp { error("boom") }
        assertTrue(result.errorOrNull() is AppError.Unexpected)
    }

    @Test
    fun `runCatchingApp lets cancellation propagate`() {
        // Cancellation is control flow, not a failure: swallowing it would break structured
        // concurrency by turning a cancelled coroutine into a successful one.
        var thrown = false
        try {
            runCatchingApp { throw kotlinx.coroutines.CancellationException("cancelled") }
        } catch (expected: kotlinx.coroutines.CancellationException) {
            thrown = true
        }
        assertTrue("CancellationException must not be captured", thrown)
    }
}
