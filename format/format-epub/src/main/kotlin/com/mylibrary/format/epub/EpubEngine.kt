package com.mylibrary.format.epub

import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.domain.engine.DocumentEngine
import com.mylibrary.core.domain.engine.DocumentSource
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.util.zip.ZipException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decodes EPUB 2 and EPUB 3, built directly on `java.util.zip` and jsoup.
 *
 * The engine itself holds no state and is safe to share: every call to [open] builds an independent
 * [EpubDocument], which is what lets the library screen read a cover image while the reader has a
 * different book open.
 *
 * The decode is the container walk the format prescribes — `META-INF/container.xml` names the
 * package document, the package names every file and puts the readable ones in a spine order, and
 * navigation comes from either the EPUB 3 nav document or the EPUB 2 NCX. Each step is skip-able in
 * the sense that a missing or unreadable one produces a specific [AppError] rather than an exception:
 * a library that says "this book is corrupt" is useful, a library that crashes is not.
 */
class EpubEngine : DocumentEngine {

    override fun supports(format: BookFormat): Boolean = format == BookFormat.EPUB

    /**
     * Opens an EPUB.
     *
     * [password] is ignored: EPUB has no password-based encryption to unlock. What a "protected"
     * EPUB has is DRM, which no password can open and which is reported as [AppError.Protected].
     *
     * Nothing thrown here escapes — the format is a zip of files a user picked from anywhere, so
     * every failure mode it can present, from a revoked URI grant to a decompression bomb, is turned
     * into an [AppError] the UI can explain. Cancellation is the one exception, because it is control
     * flow rather than a failure.
     */
    override suspend fun open(source: DocumentSource, password: String?): AppResult<OpenDocument> =
        withContext(Dispatchers.IO) {
            try {
                openDocument(source)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (corrupt: ZipException) {
                AppResult.Failure(AppError.CorruptDocument(corrupt.message))
            } catch (truncated: EOFException) {
                // A truncated zip reads as a truncated entry, not as a corrupt one.
                AppResult.Failure(AppError.CorruptDocument(truncated.message))
            } catch (unreadable: IOException) {
                AppResult.Failure(AppError.FileAccess(unreadable.message))
            } catch (outOfMemory: OutOfMemoryError) {
                // Entry sizes come from the file, so a hostile book can ask for more than the heap.
                AppResult.Failure(AppError.OutOfMemory)
            } catch (unexpected: Throwable) {
                AppResult.Failure(AppError.Unexpected(unexpected))
            }
        }

    private fun openDocument(source: DocumentSource): AppResult<OpenDocument> {
        val archive = EpubArchive(source)
        if (archive.entryNames.isEmpty()) return corrupt("the archive contains no entries")

        val containerBytes = archive.readEntry(EpubContainer.CONTAINER_PATH)
            ?: return corrupt("META-INF/container.xml is missing")
        val opfPath = EpubContainer.rootfilePath(containerBytes)
            ?: return corrupt("META-INF/container.xml names no package document")

        // Checked before the package document is read: a DRM-protected book is usually readable as a
        // container, and there is nothing worth parsing in one.
        archive.readEntry(ENCRYPTION_PATH)?.let { bytes ->
            if (EpubEncryption.isProtected(bytes)) return AppResult.Failure(AppError.Protected)
        }

        val opfBytes = archive.readEntry(opfPath)
            ?: return corrupt("the package document $opfPath is missing")
        val epubPackage = OpfParser.parse(opfBytes, opfPath)
            ?: return corrupt("the package document $opfPath is unreadable")

        // The spine, filtered down to items that are both documents and actually present in the zip:
        // a manifest may name a file the archive does not contain, and a chapter the reader cannot
        // read is worse than one it never lists.
        val chapterPaths = epubPackage.spine
            .asSequence()
            .filter { it.item.isFlowable }
            .mapNotNull { it.item.path }
            .mapNotNull { archive.findEntry(it) }
            .toList()
        if (chapterPaths.isEmpty()) return AppResult.Failure(AppError.EmptyDocument)

        val outline = readOutline(epubPackage, archive, ChapterIndex(chapterPaths))
        return AppResult.Success(
            EpubDocument(
                archive = archive,
                opfDir = epubPackage.opfDir,
                chapterPaths = chapterPaths,
                chapterTitles = EpubNavigation.titlesByChapter(outline),
                // Passed rather than read: the package document is already parsed here, and the
                // stylesheets stay unread until the reader asks the document what type it is set in.
                stylesheets = StylesheetSources.of(epubPackage),
                metadata = epubPackage.metadata,
                outline = outline,
            ),
        )
    }

    /**
     * Reads the table of contents, preferring the EPUB 3 nav document over the EPUB 2 NCX.
     *
     * A book that has both is read as EPUB 3: the nav document is the one the format designates as
     * authoritative, and it is the one the producer generated last. The NCX is still used when the
     * nav document is absent, unreadable or empty — a nav that parses to nothing gives the reader no
     * outline, and the NCX beside it may well have one.
     */
    private fun readOutline(epubPackage: EpubPackage, archive: EpubArchive, chapters: ChapterIndex): List<TocEntry> {
        val navigationEntry = epubPackage.navigationPath?.let { archive.findEntry(it) }
        val ncxEntry = epubPackage.ncxPath?.let { archive.findEntry(it) }
        val wanted = listOfNotNull(navigationEntry, ncxEntry)
        if (wanted.isEmpty()) return emptyList()
        // Both candidates in one pass: a book that ships both is the common case, and the pass is
        // over the whole archive either way.
        val contents = archive.readEntries(wanted)

        val navigation = navigationEntry?.let { name -> contents[name]?.let { name to it } }
        if (navigation != null) {
            val entries = EpubNavigation.parseNavDocument(navigation.second, navigation.first, chapters)
            if (entries.isNotEmpty()) return entries
        }
        val ncx = ncxEntry?.let { name -> contents[name]?.let { name to it } } ?: return emptyList()
        return EpubNavigation.parseNcx(ncx.second, ncx.first, chapters)
    }

    private fun corrupt(reason: String): AppResult<OpenDocument> =
        AppResult.Failure(AppError.CorruptDocument(reason))

    private companion object {
        const val ENCRYPTION_PATH = "META-INF/encryption.xml"
    }
}
