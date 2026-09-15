package com.mylibrary.format.archive

import com.mylibrary.core.common.fileNameParts
import com.mylibrary.core.common.naturalSortKey
import java.util.Locale

/**
 * Decides which archive entries are comic pages, and in which order they are read.
 *
 * This is the one part of the engine that has to be exactly right: a comic's page order *is* the
 * book, and getting it wrong does not fail — it silently scrambles the story. Two decisions follow
 * from that:
 *
 *  - **Numbers are compared as numbers, never as text.** Archives routinely name pages
 *    `page1.png` … `page10.png`, and a plain string sort would place page 10 between page 1 and
 *    page 2. Ordering therefore goes through [naturalSortKey], which is the project-wide answer to
 *    this problem rather than a local reimplementation.
 *  - **Cruft is filtered out, not tolerated.** A CBZ that was downloaded rather than packed by its
 *    author very often carries a `.DS_Store` and `__MACOSX/._page3.png` AppleDouble sidecars, put
 *    there by the macOS machine that zipped it. Those entries sit next to the real pages, and if
 *    they were kept they would render as blank pages *and* shift the index of every page after
 *    them, which the reader's saved reading position depends on.
 *
 * Entry names are compared after [normalize] so that a zip written by a Windows tool (backslash
 * separators) or one that prefixes entries with `./` still sorts and filters the same way. The
 * names kept for extraction are always the raw ones, because that is what the container must be
 * asked for.
 */
internal object ComicPageOrder {

    /** Extensions a comic page may have. Compared case-insensitively, so `.PNG` counts too. */
    private val PAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

    /**
     * Directory names that only ever contain the packing machine's droppings.
     *
     * `__MACOSX` holds the AppleDouble resource forks; the rest are macOS volume metadata that
     * occasionally ends up inside an archive made by copying a folder.
     */
    private val CRUFT_DIRECTORIES = setOf("__macosx", ".spotlight-v100", ".trashes", ".fseventsd")

    /** Files that describe the folder they came from rather than depicting a page. */
    private val CRUFT_FILES = setOf(".ds_store", "thumbs.db", "desktop.ini")

    /**
     * Orders entry names the way a reader expects: `page2` before `page10`, `cover` before
     * `page1`, and subdirectory order preserved (`chapter1/page1` before `chapter2/page1`).
     *
     * Ties (two entries whose sort keys are equal, e.g. `Page1.PNG` and `page1.png`, which cannot
     * both exist in one zip but can in a rar on a case-sensitive host) keep their archive order,
     * because `sortedWith` is stable.
     */
    val comparator: Comparator<String> = compareBy { sortKey(it) }

    /** The sort key of one entry name; exposed for tests and for callers that sort elsewhere. */
    fun sortKey(rawName: String): String = naturalSortKey(normalize(rawName))

    /**
     * True when the entry should become a page: a file (not a directory), not macOS/Windows cruft,
     * and carrying a known image extension.
     *
     * @param isDirectory the container's own answer, which is more reliable than inspecting the
     *   name: RAR keeps a directory flag in the header, and a zip marks directories with a
     *   trailing `/`.
     */
    fun isPage(rawName: String, isDirectory: Boolean): Boolean {
        if (isDirectory) return false
        val name = normalize(rawName)
        if (name.isEmpty()) return false

        val segments = name.split('/')
        if (segments.any { it.lowercase(Locale.ROOT) in CRUFT_DIRECTORIES }) return false

        val leaf = segments.last()
        if (leaf.lowercase(Locale.ROOT) in CRUFT_FILES) return false
        // No page name starts with a dot. This is what rejects `._page3.png`, the AppleDouble
        // sidecar whose name is the real page's name with a `._` prefix and which decodes to a
        // 4 KB blob of filesystem metadata rather than an image.
        if (leaf.startsWith(".")) return false

        return fileNameParts(leaf).second in PAGE_EXTENSIONS
    }

    /** Canonical form of an entry name for filtering and sorting: `/` separators, no `./` prefix. */
    fun normalize(rawName: String): String {
        var name = rawName.replace('\\', '/')
        while (name.startsWith("./")) name = name.removePrefix("./")
        return name
    }
}
