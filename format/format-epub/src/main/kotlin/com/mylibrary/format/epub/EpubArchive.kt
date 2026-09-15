package com.mylibrary.format.epub

import com.mylibrary.core.domain.engine.DocumentSource
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Entry-level access to an EPUB's zip container.
 *
 * **Why every read re-opens the stream.** An EPUB is a book: a few megabytes, of which the text is a
 * fraction. Holding a `ZipFile` — or a `ZipInputStream` plus a map of every entry's bytes — for the
 * lifetime of an open document would pin the whole book in memory and keep a file descriptor alive
 * behind the reader's back, for the sake of saving a pass over those few megabytes when the reader
 * turns a page. So there is no long-lived handle: [readEntry] opens a fresh stream from
 * [DocumentSource.openStream], walks to the entry it wants, and closes it. The cost is one inflate
 * pass over a small file, which is milliseconds; the payoff is a decoder whose [EpubDocument.close]
 * has nothing to release and therefore cannot leak.
 *
 * [entryNames] is the one thing collected eagerly, because the spine has to be checked against it to
 * find out whether a book has any readable chapter before the document opens.
 *
 * One entry's bytes are in memory at a time, capped by [MAX_ENTRY_BYTES]: an EPUB from an untrusted
 * source can declare an entry that inflates to gigabytes, and a reader that dies of `OutOfMemoryError`
 * on open is worse than one that reports a corrupt book.
 */
internal class EpubArchive(private val source: DocumentSource) {

    /** The archive's entry names, in central-directory order. */
    val entryNames: Set<String>

    /** Lower-cased entry name to the real spelling, for books whose hrefs disagree on case. */
    private val lowerCaseNames: Map<String, String>

    init {
        val names = LinkedHashSet<String>()
        forEachEntry { name, _ ->
            names.add(name)
            true
        }
        entryNames = names
        lowerCaseNames = names.associateByTo(HashMap(names.size)) { it.lowercase() }
    }

    /**
     * The archive's own spelling of [path], or `null` when no such entry exists.
     *
     * Falls back to a case-insensitive match: EPUB hrefs are case-sensitive by specification, but
     * books assembled on case-insensitive filesystems routinely ship a manifest that disagrees with
     * the zip, and refusing to open them helps nobody.
     */
    fun findEntry(path: String): String? {
        if (path in entryNames) return path
        return lowerCaseNames[path.lowercase()]
    }

    /** Reads one entry, or returns `null` when it is absent or too large to be a book resource. */
    fun readEntry(path: String): ByteArray? {
        val entryName = findEntry(path) ?: return null
        return readEntries(listOf(entryName))[entryName]
    }

    /**
     * Reads several entries in a single pass over the archive.
     *
     * @return the entries that were found, keyed by the request paths (which must be real entry
     *   names — callers resolve them through [findEntry] first).
     */
    fun readEntries(paths: Collection<String>): Map<String, ByteArray> {
        val wanted = paths.toSet()
        if (wanted.isEmpty()) return emptyMap()
        val found = HashMap<String, ByteArray>(wanted.size)
        forEachEntry { name, zip ->
            if (name in wanted) {
                readCurrentEntry(zip)?.let { found[name] = it }
            }
            // Stop early once everything is in hand: entries after the last wanted one would be
            // inflated for nothing.
            found.size < wanted.size
        }
        return found
    }

    /**
     * Calls [action] for every file entry until it returns `false`.
     *
     * The zip is streamed, so entries arrive in the order the container stores them, not in the
     * order the spine lists them.
     */
    private inline fun forEachEntry(action: (name: String, zip: ZipInputStream) -> Boolean) {
        ZipInputStream(BufferedInputStream(source.openStream(), BUFFER_BYTES)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (!action(entry.name, zip)) break
            }
        }
    }

    /** Reads the entry the stream is currently positioned on, abandoning it past [MAX_ENTRY_BYTES]. */
    private fun readCurrentEntry(zip: ZipInputStream): ByteArray? {
        val out = ByteArrayOutputStream(INITIAL_ENTRY_BYTES)
        val chunk = ByteArray(CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = zip.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val BUFFER_BYTES = 16 * 1024
        const val CHUNK_BYTES = 16 * 1024
        const val INITIAL_ENTRY_BYTES = 8 * 1024
        const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
    }
}
