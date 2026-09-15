package com.mylibrary.format.text.internal

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.SequenceInputStream
import java.nio.charset.Charset

/**
 * A whole decoded plain-text file, plus what was noticed while decoding it.
 *
 * [suspiciousCharacters] counts the characters that no real book contains: the replacement character
 * that a decoder emits for a byte it cannot map, and the ISO control characters other than the
 * whitespace that separates text. A high count means the bytes were not text in any charset we
 * tried, which is the difference between "this file is empty" and "this file is not a book".
 */
internal class DecodedText(
    val text: String,
    val charset: Charset,
    val suspiciousCharacters: Int,
) {
    val isUndecodable: Boolean
        get() = text.isNotEmpty() && suspiciousCharacters > text.length * UNDECODABLE_RATIO

    private companion object {
        /**
         * Deliberately generous. A file that is 10% replacement characters or control codes is
         * binary or mis-encoded beyond use; a file with a handful — a stray byte, an old control
         * code in a scanned-and-OCR'd book — is still a book, and reporting the reader's only
         * remaining error for it would be worse than showing it.
         */
        const val UNDECODABLE_RATIO = 0.1
    }
}

/**
 * Turns a byte stream into the one `String` the rest of the engine works from.
 *
 * ## Why the whole file becomes one String
 *
 * A TXT file can be tens of megabytes, so decoding all of it looks wasteful — but the alternative
 * costs more than it saves. Search is defined over the *decoded* text (a query has to be matched
 * against characters, not bytes), and a `ReadingLocator` is a character offset that must keep
 * meaning the same thing between a search hit, the chapter text and the position the reader saves.
 * Decoding chapter by chapter would re-read and re-decode the file on every search — the scan would
 * go from O(n) over a string to O(n) over a file — and slicing a multi-byte encoding at a byte
 * offset is not possible in the first place: for Windows-1256 or UTF-8 the offset of a chapter is
 * only known after decoding everything before it.
 *
 * What the engine does instead is avoid every *other* copy. The bytes are never all held at once
 * (the sample is decoded and then streamed), chapters are remembered as offsets into the one string
 * rather than as a string each, and [DecodedText.charset] is kept so nothing downstream has to guess
 * again. The retained size is the text itself: two bytes per character, so within a factor of two of
 * the file for the Arabic, Hebrew and Cyrillic books this module targets.
 */
internal object PlainTextDecoder {

    /**
     * Reads the sample, decides the charset, and decodes the rest of the stream through it.
     *
     * @param stream consumed to its end but not closed — the caller owns it and closes it, which is
     *   what lets the engine open a source with `use { }` around a single call.
     */
    fun decode(stream: InputStream, sizeHintBytes: Long = 0L): DecodedText {
        val buffered = BufferedInputStream(stream, READ_BUFFER_BYTES)

        // Sniff the head of the file, then hand the reader those same bytes followed by the rest, so
        // the caller's stream is consumed exactly once and the sample is not read twice.
        val sample = ByteArray(TextCharsetDetector.SAMPLE_BYTES)
        val sampleLength = readSample(buffered, sample)
        val detected = TextCharsetDetector.detect(sample, sampleLength)

        val body = ByteArrayInputStream(sample, detected.bomLength, sampleLength - detected.bomLength)
        val reader = InputStreamReader(SequenceInputStream(body, buffered), detected.charset)

        val text = StringBuilder(capacityHint(sizeHintBytes))
        val chunk = CharArray(READ_BUFFER_BYTES)
        var suspicious = 0
        while (true) {
            val read = reader.read(chunk)
            if (read < 0) break
            for (index in 0 until read) {
                if (isSuspicious(chunk[index])) suspicious++
            }
            text.appendRange(chunk, 0, read)
        }

        return DecodedText(normalizeLineEndings(text), detected.charset, suspicious)
    }

    /**
     * Rewrites CRLF and lone CR to LF.
     *
     * TXT files are as likely to come from Windows as from anywhere else, and the line break is the
     * only structure a TXT file has: it decides where paragraphs and chapters are cut, and it is
     * what a snippet around a search hit should look like. Normalising once, here, means every offset
     * in the engine — including the ones persisted as reading positions — refers to one consistent
     * string, and every later stage has a single line break to reason about.
     */
    private fun normalizeLineEndings(text: CharSequence): String {
        var index = text.indexOf('\r')
        if (index < 0) return text.toString()

        val normalized = StringBuilder(text.length)
        normalized.append(text, 0, index)
        while (index < text.length) {
            val character = text[index]
            if (character == '\r') {
                normalized.append('\n')
                // CRLF is one break, not two.
                if (index + 1 < text.length && text[index + 1] == '\n') index++
            } else {
                normalized.append(character)
            }
            index++
        }
        return normalized.toString()
    }

    /** A character that indicates the bytes were not text in the charset we chose. */
    private fun isSuspicious(character: Char): Boolean = when {
        character == REPLACEMENT_CHARACTER -> true
        character == '\t' || character == '\n' || character == '\r' -> false
        // A form feed is how some TXT files mark a page break, so it is structure, not damage.
        character == '' -> false
        else -> character.isISOControl()
    }

    /** Reads up to `sample.size` bytes; a short read from a slow provider is not the end of the file. */
    private fun readSample(stream: InputStream, sample: ByteArray): Int {
        var total = 0
        while (total < sample.size) {
            val read = stream.read(sample, total, sample.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    /**
     * Sizes the character buffer from what the storage layer says the file weighs.
     *
     * The hint is halved because a character is one or two bytes of UTF-8 but always two bytes of
     * `String`, and it is capped because a hint is only worth having if it is cheaper than the
     * doubling it avoids: growing a 20 MB buffer a few times is a few milliseconds, while
     * speculatively allocating a buffer for a 200 MB file would cost real memory on a phone.
     */
    private fun capacityHint(sizeHintBytes: Long): Int {
        if (sizeHintBytes <= 0L) return MIN_TEXT_CAPACITY
        return (sizeHintBytes / 2).coerceIn(MIN_TEXT_CAPACITY.toLong(), MAX_TEXT_CAPACITY.toLong()).toInt()
    }

    private const val READ_BUFFER_BYTES = 16 * 1024
    private const val MIN_TEXT_CAPACITY = 8 * 1024
    private const val MAX_TEXT_CAPACITY = 4 * 1024 * 1024
    private const val REPLACEMENT_CHARACTER = '�'
}
