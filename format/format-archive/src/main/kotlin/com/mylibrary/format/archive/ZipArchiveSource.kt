package com.mylibrary.format.archive

import com.mylibrary.core.domain.engine.DocumentSource
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

/**
 * A CBZ: a zip full of images, read with `java.util.zip`.
 *
 * **Why this streams instead of using the zip central directory.** The tidy way to list a zip is
 * `java.util.zip.ZipFile`, which reads the directory at the end of the file and can then seek
 * straight to any entry. `ZipFile` needs a *file path*, and a [DocumentSource] deliberately is not
 * one — it is a `content://` URI or an opaque stream with no path at all. ([DocumentSource.openChannel]
 * does offer random access, but that only moves the problem: the zip directory lookup would then
 * have to be re-implemented by hand on top of it, including the zip64 records a multi-gigabyte
 * archive uses.) So the archive is read twice instead:
 *
 *  1. [open] streams the whole archive once to collect entry names.
 *  2. [readPage] re-opens the stream and inflates forward to the requested entry.
 *
 * The listing pass is genuinely O(archive size), not O(entries): a zip's local headers do not
 * reliably carry the entry's size, because a streaming zip writes the size *after* the data in a
 * data descriptor, so `ZipInputStream` has to inflate through each entry to reach the next header.
 * That is the cost of the trade, and it is paid once per document open. Reading a page afterwards
 * costs a scan plus one entry's decompression, which is acceptable because pages are read one at a
 * time and then held by the reader's own byte-bounded cache.
 *
 * The alternative — unpacking the archive into a temp directory at open, then using `ZipFile` — puts
 * the size of the whole book on the device's storage and leaves it behind if the process dies
 * mid-read, which on Android is routine.
 */
internal class ZipArchiveSource private constructor(
    private val documentSource: DocumentSource,
    override val pageNames: List<String>,
) : ArchiveSource {

    override fun readPage(pageIndex: Int): ByteArray {
        val wanted = pageNames.getOrNull(pageIndex)
            ?: throw PageReadException("Page $pageIndex is outside 0 until ${pageNames.size}")

        val zip = ZipInputStream(BufferedInputStream(documentSource.openStream()))
        zip.use { stream ->
            var entry = stream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == wanted) return readEntryBytes(stream)
                entry = stream.nextEntry
            }
        }
        // The entry was in the listing a moment ago, so this means the source changed under us or
        // the archive is damaged further along than the entry itself.
        throw PageReadException("Entry '$wanted' is missing from the archive")
    }

    override fun close() {
        // Nothing is held open between calls: every read opens and closes its own stream, which is
        // also what makes concurrent page renders safe without a lock.
    }

    companion object {
        /**
         * Lists the archive's image entries, in reading order.
         *
         * @throws java.io.IOException when the source cannot be read.
         * @throws java.util.zip.ZipException when the bytes are a malformed zip; a file that is not
         *   a zip at all simply yields no entries, which the engine reports as
         *   `AppError.EmptyDocument`.
         */
        fun open(documentSource: DocumentSource): ZipArchiveSource {
            val names = ArrayList<String>()
            ZipInputStream(BufferedInputStream(documentSource.openStream())).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (ComicPageOrder.isPage(entry.name, entry.isDirectory)) names += entry.name
                    entry = zip.nextEntry
                }
            }
            names.sortWith(ComicPageOrder.comparator)
            return ZipArchiveSource(documentSource, names)
        }
    }
}
