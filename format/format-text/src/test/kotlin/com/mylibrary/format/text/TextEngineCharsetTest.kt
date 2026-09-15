package com.mylibrary.format.text

import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.model.BookFormat
import java.io.IOException
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tests that matter most in this module: a TXT file does not say what encoding it is in, and
 * every one of these cases is a file a user could actually have.
 */
class TextEngineCharsetTest {

    @Test
    fun `opens a utf-8 arabic book`() {
        val document = open(ARABIC_SAMPLE.toByteArray(Charsets.UTF_8))

        assertEquals(ARABIC_SAMPLE, textOf(document, 0))
        assertEquals("ar", document.metadata.language)
    }

    @Test
    fun `opens a windows-1256 arabic book`() {
        // The case the whole detection ladder exists for: juniversalchardet has no Arabic model, so
        // it reads these same bytes as Cyrillic.
        val bytes = ARABIC_SAMPLE.toByteArray(Charset.forName("windows-1256"))

        val document = open(bytes)

        assertEquals(ARABIC_SAMPLE, textOf(document, 0))
        assertEquals("ar", document.metadata.language)
        assertTrue(
            "windows-1256 was not the chosen charset: ${document.charsetName}",
            document.charsetName.equals("windows-1256", ignoreCase = true),
        )
    }

    @Test
    fun `opens a short windows-1256 file, where the detector is not consulted at all`() {
        // Under 512 bytes the detector is skipped: on this little data its guesses are not worth
        // trusting, and the Arabic byte-distribution test is a claim rather than a guess.
        val short = "بسم الله الرحمن الرحيم، الحمد لله رب العالمين، والصلاة والسلام على أشرف المرسلين."
        val bytes = short.toByteArray(Charset.forName("windows-1256"))
        assertTrue("the sample must stay under the detector threshold to test this", bytes.size < 512)

        val document = open(bytes)

        assertEquals(short, textOf(document, 0))
    }

    @Test
    fun `opens a utf-16 little endian book with a byte order mark`() {
        val bytes = BOM_UTF16_LE + ARABIC_SAMPLE.toByteArray(Charsets.UTF_16LE)

        val document = open(bytes)

        // The mark itself is dropped: leaving it in would put a zero-width character at the top of
        // every chapter and shift every offset in the book by one.
        assertEquals(ARABIC_SAMPLE, textOf(document, 0))
        assertEquals("ar", document.metadata.language)
    }

    @Test
    fun `opens a utf-16 big endian book with a byte order mark`() {
        val bytes = BOM_UTF16_BE + ARABIC_SAMPLE.toByteArray(Charsets.UTF_16BE)

        assertEquals(ARABIC_SAMPLE, textOf(open(bytes), 0))
    }

    @Test
    fun `opens a utf-16 little endian book that has no byte order mark`() {
        val bytes = ARABIC_SAMPLE.toByteArray(Charsets.UTF_16LE)

        val document = open(bytes)

        assertEquals(ARABIC_SAMPLE, textOf(document, 0))
    }

    @Test
    fun `strips a utf-8 byte order mark`() {
        val bytes = BOM_UTF8 + ENGLISH_SAMPLE.toByteArray(Charsets.UTF_8)

        val text = textOf(open(bytes), 0)

        assertEquals(ENGLISH_SAMPLE, text)
        assertFalse("the mark leaked into the text", text.startsWith('﻿'))
    }

    @Test
    fun `opens a short ascii file as utf-8`() {
        val document = open("Hello world.\nA second line.")

        assertEquals("Hello world.\nA second line.", textOf(document, 0))
        assertEquals("en", document.metadata.language)
    }

    @Test
    fun `opens a windows-1252 latin book`() {
        // A CP1252 file that is not valid UTF-8 and has no BOM. Its accented characters live in the
        // same bytes in ISO-8859-1, so this asserts the text whichever of the two the detector names.
        val bytes = SPANISH_SAMPLE.toByteArray(Charset.forName("windows-1252"))
        assertTrue("the sample must be large enough for the detector to run", bytes.size > 512)

        val document = open(bytes)

        assertEquals(SPANISH_SAMPLE, textOf(document, 0))
        assertEquals("en", document.metadata.language)
    }

    @Test
    fun `normalises windows line endings`() {
        val document = open("first\r\nsecond\rthird\n")

        assertEquals("first\nsecond\nthird\n", textOf(document, 0))
    }

    @Test
    fun `treats an empty file as an empty document`() {
        assertEquals(AppError.EmptyDocument, openError(ByteArray(0)))
    }

    @Test
    fun `treats a whitespace only file as an empty document`() {
        assertEquals(AppError.EmptyDocument, openError("\n\n   \n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `treats bytes that are not text as an empty document`() {
        // Control bytes and unassigned bytes: not UTF-8, not a code page's letters, not Arabic.
        val binary = ByteArray(2048) { index -> BINARY_PATTERN[index % BINARY_PATTERN.size].toByte() }

        assertEquals(AppError.EmptyDocument, openError(binary))
    }

    @Test
    fun `reports a revoked file as a file access error`() {
        val result = runBlocking {
            engine.open(FailingDocumentSource(IOException("the grant was revoked")))
        }

        val failure = result as AppResult.Failure
        assertTrue("got ${failure.error}", failure.error is AppError.FileAccess)
    }

    @Test
    fun `refuses a source that is not a txt file`() {
        val result = runBlocking {
            engine.open(ByteArrayDocumentSource(ByteArray(0), displayName = "book.epub", format = BookFormat.EPUB))
        }

        val failure = result as AppResult.Failure
        assertTrue("got ${failure.error}", failure.error is AppError.UnsupportedFormat)
    }

    @Test
    fun `supports txt and nothing else`() {
        assertTrue(engine.supports(BookFormat.TXT))
        assertFalse(engine.supports(BookFormat.EPUB))
        assertFalse(engine.supports(BookFormat.PDF))
    }

    @Test
    fun `reports no title, leaving the file name to the caller`() {
        assertNull(open(ARABIC_SAMPLE).metadata.title)
    }

    private companion object {
        val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val BOM_UTF16_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val BOM_UTF16_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

        /** Bytes that no charset maps to letters: unassigned control codes and unassigned slots. */
        val BINARY_PATTERN = intArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x0E, 0x0F,
            0xFF, 0xFE, 0xFD, 0x80, 0x81, 0x8D, 0x90, 0x9D,
        )
    }
}