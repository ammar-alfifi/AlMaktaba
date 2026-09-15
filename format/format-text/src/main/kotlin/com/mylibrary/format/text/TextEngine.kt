package com.mylibrary.format.text

import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.engine.DocumentEngine
import com.mylibrary.core.domain.engine.DocumentSource
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.DocumentMetadata
import com.mylibrary.format.text.internal.ChapterIndex
import com.mylibrary.format.text.internal.PlainTextDecoder
import com.mylibrary.format.text.internal.sampledCensus
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes `.txt` books.
 *
 * Everything this engine does happens in [open]: the file is read once, its encoding is worked out,
 * it is decoded into one string and indexed into chapters. That shape is a consequence of what a TXT
 * file is — a stream of characters with no structure to seek to — and it is why an opened TXT
 * document holds no file handle and cannot fail later. The alternative, re-reading and re-decoding
 * the file per chapter, would spend the decode again on every page turn and still could not answer a
 * search without walking the whole file.
 *
 * The hard part is not the reading, it is the *encoding*: a TXT file does not say what it is, and
 * guessing wrong turns an entire Arabic novel into mojibake. See
 * [com.mylibrary.format.text.internal.TextCharsetDetector] for how the guess is made — and for why
 * `juniversalchardet` on its own is not enough for Arabic.
 *
 * Stateless and thread-safe: one instance can be shared, and every [open] returns an independent
 * [PlainTextDocument].
 */
class TextEngine : DocumentEngine {

    override fun supports(format: BookFormat): Boolean = format == BookFormat.TXT

    /**
     * Reads and decodes [source].
     *
     * @param password ignored. TXT has no encryption and no password-protected variant; the parameter
     *   exists because the reader prompts for a password before it knows which engine will open the
     *   file.
     *
     * Never throws: every failure is mapped to an [AppError] the presentation layer can put into
     * words, including the ones a decoder would normally let escape — a revoked URI grant arrives as
     * an [IOException] and becomes [AppError.FileAccess], a file whose bytes are not text in any
     * charset becomes [AppError.EmptyDocument], and a book too large for the heap becomes
     * [AppError.OutOfMemory] rather than an `OutOfMemoryError` that would take the process down.
     */
    override suspend fun open(source: DocumentSource, password: String?): AppResult<OpenDocument> =
        withContext(Dispatchers.IO) {
            if (!supports(source.format)) {
                return@withContext AppResult.Failure(
                    AppError.UnsupportedFormat(
                        mimeType = source.mimeType,
                        extension = source.displayName
                            .substringAfterLast('.', missingDelimiterValue = "")
                            .ifEmpty { null },
                    ),
                )
            }

            try {
                val decoded = source.openStream().use { stream ->
                    PlainTextDecoder.decode(stream, source.sizeBytes)
                }

                // Blank is as empty as empty: a file of nothing but line breaks has no chapter to
                // show, and reporting it as a one-chapter book would open the reader on a white page.
                if (decoded.text.isBlank() || decoded.isUndecodable) {
                    return@withContext AppResult.Failure(AppError.EmptyDocument)
                }

                val chapters = ChapterIndex.of(decoded.text)
                if (chapters.count == 0) {
                    return@withContext AppResult.Failure(AppError.EmptyDocument)
                }

                AppResult.Success(
                    PlainTextDocument(
                        // No title: the file name the user chose the book by is a better name than
                        // anything that could be parsed out of the first line of the text, and the
                        // caller already falls back to it. The language is inferred from the script
                        // the text is written in, which is the reader's cue for a default direction.
                        metadata = DocumentMetadata(language = sampledCensus(decoded.text).languageTag),
                        charsetName = decoded.charset.name(),
                        chapters = chapters,
                        text = decoded.text,
                    ),
                )
            } catch (cancellation: CancellationException) {
                // Cancellation is control flow, not a failure: the reader left the screen.
                throw cancellation
            } catch (io: IOException) {
                AppResult.Failure(AppError.FileAccess(io.message))
            } catch (outOfMemory: OutOfMemoryError) {
                // A book larger than the heap is an outcome the UI has words for.
                AppResult.Failure(AppError.OutOfMemory)
            } catch (error: Throwable) {
                AppResult.Failure(AppError.Unexpected(error))
            }
        }
}
