package com.mylibrary.feature.reader

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the block → character-offset map.
 *
 * This map is what lets a reading position, a search hit and a highlight all mean the same thing:
 * they are offsets into the chapter text the *decoder* produced, while the renderer works in blocks.
 * If it drifts, a stored highlight is drawn over the wrong words — the kind of bug that looks like
 * corruption rather than a layout problem.
 */
class ChapterTextMapTest {

    private fun paragraph(text: String) = ContentBlock.Paragraph(AnnotatedString(text))
    private fun heading(text: String) = ContentBlock.Heading(level = 2, text = AnnotatedString(text))

    @Test
    fun `each block starts where its text starts in the chapter text`() {
        val blocks = listOf(paragraph("أولاً"), paragraph("ثانياً"), paragraph("ثالثاً"))
        val chapterText = "أولاً ثانياً ثالثاً"

        val map = buildChapterTextMap(blocks, chapterText)

        assertEquals(0, map.startOf(0))
        assertEquals(chapterText.indexOf("ثانياً"), map.startOf(1))
        assertEquals(chapterText.indexOf("ثالثاً"), map.startOf(2))
    }

    @Test
    fun `the offset really indexes the block's text`() {
        // The property that matters: substring at the recorded offset must reproduce the block.
        val blocks = listOf(paragraph("مقدمة"), paragraph("الفصل"), paragraph("خاتمة"))
        val chapterText = "مقدمة الفصل خاتمة"

        val map = buildChapterTextMap(blocks, chapterText)

        blocks.forEachIndexed { index, block ->
            val start = map.startOf(index)
            assertEquals(
                block.plainText(),
                chapterText.substring(start, start + block.plainText().length),
            )
        }
    }

    @Test
    fun `repeated identical blocks get distinct offsets`() {
        // The reason the search runs from a running cursor rather than from zero: three paragraphs
        // that all read "نعم." would otherwise collapse onto the same offset, and every later
        // position would point backwards.
        val blocks = listOf(paragraph("نعم."), paragraph("لا."), paragraph("نعم."))
        val chapterText = "نعم. لا. نعم."

        val map = buildChapterTextMap(blocks, chapterText)

        assertEquals(0, map.startOf(0))
        assertTrue("the second 'نعم.' must not reuse the first offset", map.startOf(2) > map.startOf(0))
        assertEquals(chapterText.lastIndexOf("نعم."), map.startOf(2))
    }

    @Test
    fun `an empty block does not advance the cursor`() {
        val blocks = listOf(paragraph("أ"), ContentBlock.Divider, paragraph("ب"))
        val chapterText = "أ ب"

        val map = buildChapterTextMap(blocks, chapterText)

        // A divider occupies no characters, so it sits exactly where the previous block ended —
        // and crucially it must not push the following block out of position.
        assertEquals(map.endOf(0, blocks), map.startOf(1))
        assertEquals(chapterText.indexOf("ب"), map.startOf(2))
    }

    @Test
    fun `an unmatched block is pinned to the cursor rather than given a wrong offset`() {
        // A table's engine text uses different separators, so it will not be found verbatim.
        // Pinning it keeps every *later* block correct, which matters far more than the table's own
        // offset being exact.
        val table = ContentBlock.Table(
            rows = listOf(TableRow(listOf(TableCell(AnnotatedString("خلية"), 1, 1, false)), isHeader = false)),
        )
        val blocks = listOf(paragraph("قبل"), table, paragraph("بعد"))
        val chapterText = "قبل بعد"

        val map = buildChapterTextMap(blocks, chapterText)

        assertEquals(0, map.startOf(0))
        assertEquals(chapterText.indexOf("بعد"), map.startOf(2))
    }

    @Test
    fun `endOf covers exactly the block's text`() {
        val blocks = listOf(paragraph("أولاً"), paragraph("ثانياً"))
        val chapterText = "أولاً ثانياً"

        val map = buildChapterTextMap(blocks, chapterText)

        assertEquals(map.startOf(0) + "أولاً".length, map.endOf(0, blocks))
    }

    @Test
    fun `blockAt finds the block containing an offset`() {
        val blocks = listOf(paragraph("أأأ"), paragraph("ببب"), paragraph("ججج"))
        val chapterText = "أأأ ببب ججج"

        val map = buildChapterTextMap(blocks, chapterText)

        assertEquals(0, map.blockAt(map.startOf(0), blocks))
        assertEquals(1, map.blockAt(map.startOf(1), blocks))
        assertEquals(2, map.blockAt(map.startOf(2), blocks))
    }

    @Test
    fun `blockAt returns null for a chapter with no blocks`() {
        val map = buildChapterTextMap(emptyList(), "")

        assertNull(map.blockAt(0, emptyList()))
    }

    @Test
    fun `an image contributes its caption, which is prose, not its alt text`() {
        val image = ContentBlock.Image(path = "a.png", alt = "وصف بديل", caption = AnnotatedString("الشكل ١"))
        val blocks = listOf(image)
        val chapterText = "الشكل ١"

        val map = buildChapterTextMap(blocks, chapterText)

        // Alt text is a description that does not appear in `chapterText`; anchoring to it would
        // place the image at a wrong offset.
        assertEquals(0, map.startOf(0))
        assertEquals("الشكل ١".length, map.endOf(0, blocks))
    }

    @Test
    fun `headings and paragraphs map independently`() {
        val blocks = listOf(heading("الفصل الأول"), paragraph("نص الفصل"))
        val chapterText = "الفصل الأول نص الفصل"

        val map = buildChapterTextMap(blocks, chapterText)

        assertEquals(0, map.startOf(0))
        assertEquals(chapterText.indexOf("نص الفصل"), map.startOf(1))
    }

    @Test
    fun `an empty chapter yields an empty map`() {
        val map = buildChapterTextMap(emptyList(), "")

        assertEquals(0, map.chapterLength)
        assertTrue(map.blockStarts.isEmpty())
    }
}

/**
 * Tests for anchor tracking, which is what makes a footnote reference land on the right paragraph.
 */
class ChapterAnchorTest {

    @Test
    fun `an id on a block maps to that block's index`() {
        val html = "<p id=\"fn1\">الحاشية الأولى</p><p>نص عادي</p>"

        val parsed = parseChapterHtml(html)

        assertEquals(0, parsed.anchorBlocks["fn1"])
    }

    @Test
    fun `an id further down maps to the right block`() {
        val html = "<p>أولاً</p><p>ثانياً</p><p id=\"target\">ثالثاً</p>"

        assertEquals(2, parseChapterHtml(html).anchorBlocks["target"])
    }

    @Test
    fun `an id on a list item maps to that item, not to the top of the list`() {
        // Footnotes are commonly an <ol> of <li id="fnN">. Mapping them all to the list's first
        // item would send every footnote reference to the same place.
        val html = """
            <ol>
              <li id="fn1">الأولى</li>
              <li id="fn2">الثانية</li>
              <li id="fn3">الثالثة</li>
            </ol>
        """.trimIndent()

        val parsed = parseChapterHtml(html)

        assertEquals(0, parsed.anchorBlocks["fn1"])
        assertEquals(1, parsed.anchorBlocks["fn2"])
        assertEquals(2, parsed.anchorBlocks["fn3"])
    }

    @Test
    fun `an id on an inline element maps to its enclosing block`() {
        // An <a id="x"> has no block of its own; the paragraph is what the reader scrolls to.
        val html = "<p>قبل <span id=\"mark\">هنا</span> بعد</p>"

        assertEquals(0, parseChapterHtml(html).anchorBlocks["mark"])
    }

    @Test
    fun `a heading id maps to the heading block`() {
        val html = "<p>مقدمة</p><h2 id=\"ch1\">الفصل الأول</h2>"

        assertEquals(1, parseChapterHtml(html).anchorBlocks["ch1"])
    }

    @Test
    fun `a duplicated id keeps its first occurrence`() {
        val html = "<p id=\"dup\">أولاً</p><p id=\"dup\">ثانياً</p>"

        assertEquals(0, parseChapterHtml(html).anchorBlocks["dup"])
    }

    @Test
    fun `a chapter with no ids has no anchors`() {
        assertTrue(parseChapterHtml("<p>نص</p>").anchorBlocks.isEmpty())
    }
}
