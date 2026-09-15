package com.mylibrary.format.pdf

import com.mylibrary.core.domain.model.DocumentMetadata
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.TocEntry
import io.legere.pdfiumandroid.api.Bookmark
import io.legere.pdfiumandroid.api.Meta

/**
 * How deep a bookmark tree may nest before the rest is discarded.
 *
 * A PDF's outline is a linked list of dictionaries. A malformed one can point at itself, and
 * pdfium hands back whatever it read, so an unbounded recursion here is a `StackOverflowError` on
 * a file the user merely opened. Real outlines are two or three levels deep; anything past this is
 * damage, and half an outline is better than a crash.
 */
private const val MAX_OUTLINE_DEPTH = 32

/**
 * Reads the PDF's Info dictionary into the domain's metadata.
 *
 * PDF's Info dictionary has no field for language (`/Lang` lives in the document catalogue, which
 * pdfium does not expose here) and none for an identifier, so those stay `null` rather than being
 * filled from `Creator`/`Producer`, which mean something else. An empty string is what pdfium
 * returns for a key that is absent — the same thing as "the document does not say" — so blanks are
 * normalised to `null` and the UI can fall back to the file name.
 */
internal fun Meta.toDocumentMetadata(): DocumentMetadata =
    DocumentMetadata(
        title = title.orNullWhenBlank(),
        author = author.orNullWhenBlank(),
        description = subject.orNullWhenBlank(),
    )

/**
 * Builds the table of contents from pdfium's bookmark tree.
 *
 * pdfium reports one flat `Bookmark` per node, each carrying its own children and the page its
 * target resolves to, so the nesting in [TocEntry] is a direct translation of the PDF's structure.
 *
 * A bookmark whose title is empty has nothing to show in a list, but its children are still
 * navigation targets the document declared — so the entry is dropped and its children take its
 * place one level up, instead of taking a whole subtree with it.
 *
 * @param pageCount used to keep every locator inside the document. A bookmark can name a page that
 *   does not exist (or -1 for a target pdfium could not resolve), and a reader that jumps there
 *   would render nothing.
 */
internal fun List<Bookmark>.toOutline(pageCount: Int, depth: Int = 0): List<TocEntry> {
    if (pageCount <= 0 || depth >= MAX_OUTLINE_DEPTH) return emptyList()

    return flatMap { bookmark ->
        val title = bookmark.title?.trim().orEmpty()
        if (title.isEmpty()) {
            bookmark.children.toOutline(pageCount, depth)
        } else {
            listOf(
                TocEntry(
                    title = title,
                    locator = ReadingLocator.Paged(bookmark.pageIdx.coerceToPage(pageCount)),
                    level = depth,
                    children = bookmark.children.toOutline(pageCount, depth + 1),
                ),
            )
        }
    }
}

private fun Long.coerceToPage(pageCount: Int): Int =
    coerceIn(0L, (pageCount - 1).toLong()).toInt()

private fun String?.orNullWhenBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
