package com.mylibrary.feature.reader

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mylibrary.core.common.DispatcherProvider
import com.mylibrary.core.domain.engine.ReflowableDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Builds a Compose [FontFamily] from the font a document carries inside itself.
 *
 * Publishers embed typefaces for a reason, and for Arabic books the reason is usually decisive: a
 * book set in Naskh or a modern Kufi face looks wrong in the platform's default sans, in the way a
 * novel set in a monospace face would. The reader therefore treats an embedded face as part of the
 * document rather than as a preference — but only when the user has not asked for something else.
 *
 * **No new setting is needed for this.** `ReaderFont.SYSTEM` already means "do not override the
 * text"; the document's own face is what that resolves to when the document has one, and the
 * platform default when it does not. A user who wants their own font picks one, which is a control
 * they already have.
 *
 * Failure is silent by design: a document that names a font it does not actually contain, or whose
 * font bytes will not decode, must still open and read. The reader falls back exactly as it would
 * for a book with no fonts at all.
 */
@Singleton
class DocumentFontLoader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Loads the document's body face, or `null` when it does not embed a usable one.
     *
     * Only the family the document asks for as its *body* text is loaded. A book may embed a dozen
     * faces for headings, drop caps and ornament; extracting all of them would cost megabytes of
     * cache for text the reader mostly does not set.
     */
    suspend fun load(document: ReflowableDocument): FontFamily? = withContext(dispatchers.io) {
        runCatching {
            val requested = document.defaultFontFamily()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@runCatching null

            val faces = document.embeddedFonts()
                .filter { it.family.equals(requested, ignoreCase = true) }
            if (faces.isEmpty()) return@runCatching null

            val fonts = faces.mapNotNull { face ->
                val bytes = document.resource(face.path) ?: return@mapNotNull null
                val file = materialise(bytes, face.path) ?: return@mapNotNull null
                Font(
                    file = file,
                    weight = FontWeight(face.weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT)),
                    style = if (face.italic) FontStyle.Italic else FontStyle.Normal,
                )
            }

            // A family with no faces is not a family; returning one would leave the reader with a
            // FontFamily that resolves to nothing and falls back invisibly.
            if (fonts.isEmpty()) null else FontFamily(fonts)
        }.getOrNull()
    }

    /**
     * Writes the font bytes to the cache and returns the file, reusing an existing copy.
     *
     * Compose's file-backed `Font` needs a real file, so the bytes have to land on disk before they
     * can be used — there is no in-memory overload. The cache is keyed by path *and* size, which is
     * enough to distinguish two faces without hashing several hundred kilobytes on every open; the
     * residual risk is two different books carrying the same-named, same-sized, different font,
     * which would leave one of them rendering in the other's face rather than failing.
     *
     * Written to a temporary file and renamed, so an interrupted write cannot leave a truncated font
     * that Compose would then fail to load with no way to recover but clearing app data.
     */
    private fun materialise(bytes: ByteArray, path: String): File? = runCatching {
        val directory = File(context.cacheDir, FONT_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "${path.hashCode().toUInt()}-${bytes.size}.font")
        if (file.exists() && file.length() == bytes.size.toLong()) return@runCatching file

        val temporary = File(directory, "${file.name}.tmp")
        FileOutputStream(temporary).use { output -> output.write(bytes) }
        if (temporary.renameTo(file)) file else temporary.also { it.deleteOnExit() }
    }.getOrNull()

    private companion object {
        const val FONT_DIRECTORY = "document_fonts"

        /** CSS weights, which is the range `FontWeight` accepts. */
        const val MIN_WEIGHT = 100
        const val MAX_WEIGHT = 900
    }
}
