package com.mylibrary.format.text

import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.ReadingLocator
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a book with no structure in it is cut into chapters, and what the reader gets for each. */
class PlainTextDocumentTest {

    @Test
    fun `form feeds separate the chapters of a file that has them`() {
        val document = open(FORM_FEED_BOOK)

        assertEquals(3, document.chapterCount)
        assertEquals("الفصل الأول\n\nنص الفصل الأول.\n", textOf(document, 0))
        assertEquals("الفصل الثاني\n\nنص الفصل الثاني.\n", textOf(document, 1))
        assertEquals("الفصل الثالث\n\nنص الفصل الثالث.", textOf(document, 2))
    }

    @Test
    fun `blank sections between form feeds are not chapters`() {
        val document = open("first chaptersecond chapter")

        assertEquals(2, document.chapterCount)
        assertEquals("first chapter", textOf(document, 0))
        assertEquals("second chapter", textOf(document, 1))
    }

    @Test
    fun `a long file without form feeds is cut at paragraph boundaries`() {
        val document = open(paragraphs(count = 1_000))

        assertTrue("expected the text to be split, got ${document.chapterCount}", document.chapterCount >= 2)
        for (index in 0 until document.chapterCount) {
            val chapter = textOf(document, index)
            assertTrue("chapter $index is blank", chapter.isNotBlank())
            assertTrue(
                "chapter $index is ${chapter.length} characters, past the target segment size",
                chapter.length <= TARGET_SEGMENT_CHARS + PARAGRAPH_LENGTH,
            )
        }
        // Every chapter after the first starts where a paragraph starts: the cut is taken at the
        // blank line between paragraphs, not in the middle of one.
        assertTrue(textOf(document, 1).startsWith("["))
    }

    @Test
    fun `a file with no line breaks in it is cut at fixed offsets`() {
        val document = open("ا".repeat(120_000))

        assertEquals(3, document.chapterCount)
        assertEquals(TARGET_SEGMENT_CHARS, textOf(document, 0).length)
        assertEquals(TARGET_SEGMENT_CHARS, textOf(document, 1).length)
        assertEquals(20_000, textOf(document, 2).length)
    }

    @Test
    fun `a short file is a single chapter`() {
        val document = open(ARABIC_SAMPLE)

        assertEquals(1, document.chapterCount)
        assertEquals(ARABIC_SAMPLE, textOf(document, 0))
    }

    @Test
    fun `a chapter carries no title and starts at its own beginning`() {
        val document = open(FORM_FEED_BOOK)

        val chapter = document.chapter(1)

        // No title: the domain layer holds no user-visible string the UI cannot translate, and a TXT
        // file has no title to report.
        assertNull(chapter.title)
        assertEquals(1, chapter.index)
        assertEquals(ReadingLocator.Reflowable(chapterIndex = 1, charOffset = 0), chapter.locator)
    }

    @Test
    fun `reports the capabilities of a plain text book`() {
        val document = open(ARABIC_SAMPLE)

        assertEquals(BookFormat.TXT, document.format)
        assertTrue(document.capabilities.canSearch)
        assertTrue(document.capabilities.canExtractText)
        assertFalse(document.capabilities.canRenderPages)
        assertFalse(document.capabilities.hasOutline)
        assertTrue(document.outline.isEmpty())
    }

    @Test
    fun `resolves no resources`() {
        val document = open(ARABIC_SAMPLE)

        assertNull(resourceOf(document, "images/cover.png"))
        assertNull(resourceOf(document, ""))
    }

    @Test
    fun `closing twice is harmless`() {
        val document = open(ARABIC_SAMPLE)

        document.close()
        document.close()

        assertEquals(1, document.chapterCount)
    }

    @Test
    fun `reading a closed document fails rather than showing an empty page`() {
        val document = open(ARABIC_SAMPLE)
        document.close()

        assertThrows(IllegalStateException::class.java) { runBlocking { document.chapterText(0) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { document.chapterHtml(0) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { document.search("ملك") } }
    }

    @Test
    fun `a chapter index outside the book is rejected`() {
        val document = open(ARABIC_SAMPLE)

        assertThrows(IllegalArgumentException::class.java) { document.chapter(1) }
        assertThrows(IllegalArgumentException::class.java) { document.chapter(-1) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { document.chapterText(1) } }
    }

    @Test
    fun `reports the charset it decoded with`() {
        val document = open(ARABIC_SAMPLE.toByteArray(Charset.forName("windows-1256")))

        assertEquals("windows-1256", document.charsetName)
    }

    private companion object {
        /** The segment size the engine aims for, and the paragraph size these tests build with. */
        const val TARGET_SEGMENT_CHARS = 50_000
        const val PARAGRAPH_LENGTH = 68

        val FORM_FEED_BOOK = "الفصل الأول\n\nنص الفصل الأول.\n" +
            "الفصل الثاني\n\nنص الفصل الثاني.\n" +
            "الفصل الثالث\n\nنص الفصل الثالث."

        /** [count] numbered paragraphs, each long enough to make the arithmetic visible. */
        fun paragraphs(count: Int): String = buildString {
            repeat(count) { index ->
                append('[').append(index).append("] ").append("نص ".repeat(20)).append('\n').append('\n')
            }
        }
    }
}
