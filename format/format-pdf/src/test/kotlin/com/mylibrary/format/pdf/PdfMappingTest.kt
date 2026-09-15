package com.mylibrary.format.pdf

import com.google.common.truth.Truth.assertThat
import com.mylibrary.core.domain.model.ReadingLocator
import io.legere.pdfiumandroid.api.Bookmark
import io.legere.pdfiumandroid.api.Meta
import org.junit.Test

class PdfMappingTest {

    @Test
    fun `metadata maps the fields PDF actually has`() {
        val metadata = Meta(
            title = "مقدمة ابن خلدون",
            author = "ابن خلدون",
            subject = "History",
            creator = "LibreOffice",
        ).toDocumentMetadata()

        assertThat(metadata.title).isEqualTo("مقدمة ابن خلدون")
        assertThat(metadata.author).isEqualTo("ابن خلدون")
        assertThat(metadata.description).isEqualTo("History")
    }

    @Test
    fun `metadata pdfium reports as empty strings reads as absent`() {
        // pdfium hands back "" for an Info key the document does not have. Passing that on would put
        // a blank title on the library card instead of letting the UI fall back to the file name.
        val metadata = Meta(title = "", author = "   ", subject = "").toDocumentMetadata()

        assertThat(metadata.title).isNull()
        assertThat(metadata.author).isNull()
        assertThat(metadata.description).isNull()
    }

    @Test
    fun `an outline nests the way the document does`() {
        val outline = listOf(
            bookmark(
                title = "Part One",
                pageIdx = 0,
                children = listOf(
                    bookmark("Chapter 1", pageIdx = 3),
                    bookmark("Chapter 2", pageIdx = 9),
                ),
            ),
            bookmark("Part Two", pageIdx = 20),
        ).toOutline(pageCount = 30)

        assertThat(outline.map { it.title }).containsExactly("Part One", "Part Two").inOrder()
        assertThat(outline[0].level).isEqualTo(0)
        assertThat(outline[0].locator).isEqualTo(ReadingLocator.Paged(0))
        assertThat(outline[0].children.map { it.title }).containsExactly("Chapter 1", "Chapter 2").inOrder()
        assertThat(outline[0].children[1].level).isEqualTo(1)
        assertThat(outline[0].children[1].locator).isEqualTo(ReadingLocator.Paged(9))
        assertThat(outline[1].children).isEmpty()
    }

    @Test
    fun `a bookmark pointing outside the document is pinned to a real page`() {
        // A reader that jumps to a page that does not exist renders nothing and loses the position.
        val outline = listOf(
            bookmark("Broken forward", pageIdx = 99),
            bookmark("Broken back", pageIdx = -1),
        ).toOutline(pageCount = 10)

        assertThat(outline.map { it.locator })
            .containsExactly(ReadingLocator.Paged(9), ReadingLocator.Paged(0))
            .inOrder()
    }

    @Test
    fun `an untitled bookmark is dropped and its children take its place`() {
        // pdfium returns a bookmark with no /Title as an empty string. The entry has nothing to show,
        // but its children are still navigation targets and must not disappear with it.
        val outline = listOf(
            bookmark(
                title = "",
                pageIdx = 1,
                children = listOf(
                    bookmark("Kept", pageIdx = 2),
                    bookmark("Also kept", pageIdx = 5),
                ),
            ),
        ).toOutline(pageCount = 10)

        assertThat(outline.map { it.title }).containsExactly("Kept", "Also kept").inOrder()
        assertThat(outline.map { it.level }).containsExactly(0, 0)
    }

    @Test
    fun `a self-referencing bookmark tree stops instead of overflowing`() {
        // A malformed outline can point at itself. pdfium reports whatever it read, so the depth cap
        // is the only thing between that file and a StackOverflowError on open.
        val cyclic = Bookmark(title = "loop", pageIdx = 0)
        cyclic.children.add(cyclic)

        val outline = cyclic.let { listOf(it) }.toOutline(pageCount = 1)

        assertThat(outline).isNotEmpty()
        assertThat(depthOf(outline[0])).isAtMost(40)
    }

    @Test
    fun `a document with no pages has no outline`() {
        assertThat(listOf(bookmark("Anything", pageIdx = 0)).toOutline(pageCount = 0)).isEmpty()
    }
}

private fun bookmark(
    title: String?,
    pageIdx: Long,
    children: List<Bookmark> = emptyList(),
): Bookmark = Bookmark(children = children.toMutableList(), title = title, pageIdx = pageIdx)

private fun depthOf(entry: com.mylibrary.core.domain.model.TocEntry): Int =
    1 + (entry.children.maxOfOrNull { depthOf(it) } ?: 0)
