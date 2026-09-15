package com.mylibrary.format.epub

import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.TocEntry
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Maps an archive path back to the chapter it became.
 *
 * A table of contents names destinations by *file*, but the reader addresses chapters by index, and
 * the two only line up through the spine. Paths are compared exactly first and case-insensitively
 * second, for the same reason [EpubArchive.findEntry] is: books assembled on case-insensitive
 * filesystems ship hrefs that disagree with the zip.
 */
internal class ChapterIndex(paths: List<String>) {

    private val exactIndex = HashMap<String, Int>(paths.size)
    private val lowerCaseIndex = HashMap<String, Int>(paths.size)

    init {
        paths.forEachIndexed { index, path ->
            exactIndex.putIfAbsent(path, index)
            lowerCaseIndex.putIfAbsent(path.lowercase(), index)
        }
    }

    /** The chapter [archivePath] became, or `null` when no chapter was built from it. */
    fun indexOf(archivePath: String?): Int? =
        archivePath?.let { exactIndex[it] ?: lowerCaseIndex[it.lowercase()] }
}

/** One parsed navigation entry, before it is given a level and turned into a [TocEntry]. */
private class RawEntry(val title: String, val chapterIndex: Int, val children: List<RawEntry>)

/**
 * Reads a table of contents out of either navigation format EPUB defines.
 *
 * **EPUB 3** ships a nav document — XHTML, marked `properties="nav"` in the manifest — whose
 * `<nav epub:type="toc">` holds nested `<ol>`/`<li>`/`<a>` lists. **EPUB 2** ships an NCX, whose
 * `navMap` holds nested `navPoint`s pointing at files through `content/@src`. Both are supported
 * because both are still shipped: a book can be EPUB 3 and carry an NCX for older readers, and a
 * book can be EPUB 2 with no nav document at all. When a book has both, the nav document wins —
 * it is the one the specification makes authoritative for EPUB 3, and it is the one the publisher
 * curated last.
 *
 * Two decisions worth stating, because they differ from a naive walk of the markup:
 *
 *  - **Entries that resolve to no chapter are hoisted, not dropped.** A nav is allowed to have
 *    section headings with no target of their own, and a book's nav routinely names a file the
 *    spine does not contain. Dropping the entry would drop everything under it, so its children are
 *    spliced into its place instead, and the tree keeps every destination that can actually be
 *    reached.
 *  - **Levels are positional.** `TocEntry.level` is the depth the entry ends up at after hoisting,
 *    not the depth it was written at, so an indented TOC never renders with a missing parent line.
 *    The root level is `0`, matching the model's default.
 */
internal object EpubNavigation {

    /** Parses an EPUB 3 nav document. */
    fun parseNavDocument(bytes: ByteArray, path: String, chapters: ChapterIndex): List<TocEntry> {
        val document = EpubXml.parseHtml(bytes)
        val nav = findTocNav(document) ?: return emptyList()
        val list = nav.childrenNamed("ol").firstOrNull()
            ?: nav.childrenNamed("ul").firstOrNull()
            ?: nav.descendantsNamed("ol").firstOrNull()
            ?: return emptyList()
        return toTocEntries(parseNavList(list, EpubPaths.directoryOf(path), chapters), level = 0)
    }

    /** Parses an EPUB 2 NCX. */
    fun parseNcx(bytes: ByteArray, path: String, chapters: ChapterIndex): List<TocEntry> {
        val document = EpubXml.parseXml(bytes) ?: return emptyList()
        val navMap = document.descendantsNamed("navMap").firstOrNull() ?: return emptyList()
        return toTocEntries(parseNavPoints(navMap, EpubPaths.directoryOf(path), chapters), level = 0)
    }

    /**
     * The first title each chapter appears under, for labelling search hits and chapters.
     *
     * A chapter can be named by several entries — a part title, then the chapter itself — and the
     * first is the broader one, which is the better label of the two.
     */
    fun titlesByChapter(entries: List<TocEntry>): Map<Int, String> {
        val titles = HashMap<Int, String>()
        fun visit(list: List<TocEntry>) {
            for (entry in list) {
                val chapterIndex = (entry.locator as? ReadingLocator.Reflowable)?.chapterIndex
                if (chapterIndex != null) titles.putIfAbsent(chapterIndex, entry.title)
                visit(entry.children)
            }
        }
        visit(entries)
        return titles
    }

    /**
     * The `<nav>` holding the table of contents.
     *
     * Identified by `epub:type="toc"`. When that attribute is missing or misspelled, the first nav
     * that contains a list is used: EPUB 3 requires the toc nav to be the first one in the document,
     * so that fallback picks the right nav for every book that has one at all.
     */
    private fun findTocNav(document: Document): Element? {
        val navs = document.select("nav")
        navs.firstOrNull { nav ->
            val type = nav.attrNamed("epub:type") ?: nav.attrNamed("type") ?: return@firstOrNull false
            type.split(' ').any { it.equals("toc", ignoreCase = true) }
        }?.let { return it }
        return navs.firstOrNull { it.descendantsNamed("ol").isNotEmpty() || it.descendantsNamed("ul").isNotEmpty() }
    }

    private fun parseNavList(list: Element, baseDir: String, chapters: ChapterIndex): List<RawEntry> {
        val entries = ArrayList<RawEntry>()
        for (item in list.childrenNamed("li")) {
            // A list item holds a label (a link, or a bare span when the entry is a heading with no
            // target of its own) and, optionally, a nested list of sub-entries.
            val label = item.childrenNamed("a").firstOrNull()
                ?: item.childrenNamed("span").firstOrNull()
                ?: item.descendantsNamed("a").firstOrNull()
            val nestedList = item.childrenNamed("ol").firstOrNull()
                ?: item.childrenNamed("ul").firstOrNull()
                ?: item.descendantsNamed("ol").firstOrNull()
                ?: item.descendantsNamed("ul").firstOrNull()
            val children = nestedList?.let { parseNavList(it, baseDir, chapters) }.orEmpty()
            val href = label?.takeIf { it.localName().equals("a", ignoreCase = true) }?.attrNamed("href")
            // Links inside a nav document are relative to the nav document, not to the package.
            val target = chapters.indexOf(href?.let { EpubPaths.resolve(baseDir, it) })
            if (target == null) entries.addAll(children) else entries.add(RawEntry(label.labelOrNumber(target), target, children))
        }
        return entries
    }

    private fun parseNavPoints(parent: Element, baseDir: String, chapters: ChapterIndex): List<RawEntry> {
        val entries = ArrayList<RawEntry>()
        for (point in parent.childrenNamed("navPoint")) {
            val children = parseNavPoints(point, baseDir, chapters)
            // `playOrder` is deliberately ignored: it is meant to give the reading order, it is
            // wrong in a large share of the NCX files in circulation, and document order is what the
            // specification calls the real order anyway.
            val title = point.childrenNamed("navLabel").firstOrNull()
                ?.descendantsNamed("text")?.firstOrNull()
                ?.textOrNull()
            val source = point.childrenNamed("content").firstOrNull()?.attrNamed("src")
            val target = chapters.indexOf(source?.let { EpubPaths.resolve(baseDir, it) })
            if (target == null) entries.addAll(children) else entries.add(RawEntry(title ?: (target + 1).toString(), target, children))
        }
        return entries
    }

    /** A label for an entry that carries no text of its own: the chapter's number, human-counted. */
    private fun Element?.labelOrNumber(chapterIndex: Int): String =
        this?.textOrNull() ?: (chapterIndex + 1).toString()

    private fun toTocEntries(entries: List<RawEntry>, level: Int): List<TocEntry> = entries.map { entry ->
        TocEntry(
            title = entry.title,
            locator = ReadingLocator.Reflowable(entry.chapterIndex, charOffset = 0),
            level = level,
            children = toTocEntries(entry.children, level + 1),
        )
    }
}
