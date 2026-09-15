package com.mylibrary.format.epub

import java.io.ByteArrayOutputStream

/**
 * Href arithmetic for EPUB container paths.
 *
 * Every href inside an EPUB is relative to the document that contains it — manifest hrefs to the
 * package document, TOC links to the nav document or the NCX, an `<img src>` to its own chapter —
 * and it may be percent-encoded and carry a `#fragment`. Getting that wrong is the single most
 * common source of broken EPUB readers, so all of it lives here, under three rules:
 *
 *  1. Split the fragment off first: it is a location *inside* a document, never part of the path.
 *  2. Percent-decode **before** normalising, never after. Decoding later would let `%2E%2E%2F` in
 *     as an ordinary segment and only then turn into a real `../`, which is a traversal bypass.
 *  3. Collapse `.` and `..`, and fail — return `null` — when a `..` walks past the archive root.
 *     Callers treat `null` as "this href points nowhere", which is also what a path escaping the
 *     archive means.
 *
 * Paths produced here are `/`-separated, normalised, and never start with `/`: they name a zip
 * entry, and zip entry names have no leading slash.
 */
internal object EpubPaths {

    /** The path part of [href], with any `#fragment` removed. */
    fun pathOf(href: String): String = href.substringBefore('#')

    /** The `#fragment` of [href] without the `#`, or `null` when there is none or it is empty. */
    fun fragmentOf(href: String): String? = href.substringAfter('#', "").ifEmpty { null }

    /** The directory containing [path]: `OEBPS/Text/ch1.xhtml` becomes `OEBPS/Text`. */
    fun directoryOf(path: String): String = path.substringBeforeLast('/', "")

    /**
     * Resolves [href] against [baseDir], the directory of the document that wrote it.
     *
     * @return the normalised archive path, or `null` when [href] names no file or escapes the
     *   archive root.
     */
    fun resolve(baseDir: String, href: String): String? {
        val raw = pathOf(href).trim()
        if (raw.isEmpty()) return null
        val decoded = decodePercent(raw)
        val combined = when {
            decoded.startsWith('/') -> decoded.substring(1)
            baseDir.isEmpty() -> decoded
            else -> "$baseDir/$decoded"
        }
        return normalise(combined)
    }

    /**
     * Collapses empty, `.` and `..` segments.
     *
     * @return the normalised path, or `null` when a `..` escapes the root.
     */
    fun normalise(path: String): String? {
        val segments = ArrayList<String>(8)
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    /**
     * Percent-decodes [value] as UTF-8, leaving malformed escapes (`%zz`, a trailing `%`) as written.
     *
     * `+` is deliberately *not* turned into a space: it means a space only in query strings, and
     * these are paths. Treating it as one would break every file whose name contains a plus.
     */
    fun decodePercent(value: String): String {
        if (!value.contains('%')) return value
        val out = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high >= 0 && low >= 0) {
                    out.write((high shl 4) or low)
                    index += 3
                    continue
                }
            }
            // Not an escape: copy the character through. Encoding one character at a time is fine
            // because the buffer only mixes literal characters with percent-decoded bytes, and the
            // whole thing is read back as UTF-8 — literal characters arrive already in UTF-8.
            out.write(char.toString().toByteArray(Charsets.UTF_8))
            index++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Expresses an archive path as the relative path `chapterHtml` puts in an `<img src>`.
     *
     * Resources normally live under the package document's directory, and then the answer is the
     * path with that directory stripped. When one does not (a book whose OPF sits in `OEBPS/` but
     * whose images sit beside it at the archive root), the only honest spelling is a `/`-rooted one:
     * inventing `../` segments would make an ordinary image look like a traversal attempt.
     */
    fun resourcePathFor(archivePath: String, opfDir: String): String = when {
        opfDir.isEmpty() -> archivePath
        archivePath.startsWith("$opfDir/") -> archivePath.substring(opfDir.length + 1)
        else -> "/$archivePath"
    }
}
