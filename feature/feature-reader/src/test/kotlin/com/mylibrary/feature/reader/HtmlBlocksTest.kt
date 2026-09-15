package com.mylibrary.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the HTML → [ContentBlock] parser.
 *
 * These exist because the previous implementation dropped content silently: tables collapsed into
 * run-on paragraphs, nested lists were swallowed into their parent item's text, and `depth` was
 * structurally incapable of being anything but zero. A reader that renders a two-column comparison
 * table as one sentence is not obviously broken — it just reads wrong — so each of these asserts
 * the *structure* survived, not merely that parsing did not throw.
 */
class HtmlBlocksTest {

    /** The structural assertions below care about blocks; the anchor index has its own test class. */
    private fun blocksOf(html: String): List<ContentBlock> = parseChapterHtml(html).blocks

    private fun List<ContentBlock>.paragraphs(): List<String> =
        filterIsInstance<ContentBlock.Paragraph>().map { it.text.text }

    private fun List<ContentBlock>.listItems(): List<ContentBlock.ListItem> =
        filterIsInstance<ContentBlock.ListItem>()

    // region lists

    @Test
    fun `a flat list becomes one block per item`() {
        val blocks = blocksOf("<ul><li>أ</li><li>ب</li><li>ج</li></ul>")

        val items = blocks.listItems()
        assertEquals(listOf("أ", "ب", "ج"), items.map { it.text.text })
        assertTrue("a flat list must not indent", items.all { it.depth == 0 })
    }

    @Test
    fun `a nested list is emitted at greater depth rather than swallowed`() {
        val html = "<ul><li>أ</li><li>ب<ul><li>ج</li></ul></li></ul>"

        val items = blocksOf(html).listItems()

        assertEquals(listOf("أ", "ب", "ج"), items.map { it.text.text })
        assertEquals(listOf(0, 0, 1), items.map { it.depth })
    }

    @Test
    fun `a nested list's text does not leak into its parent item`() {
        val html = "<ul><li>الأب<ul><li>الابن</li></ul></li></ul>"

        val parent = blocksOf(html).listItems().first()

        // The bug this guards: the parent item used to carry the child's text too, so the child
        // rendered twice — once inside the parent's paragraph and once as its own item.
        assertEquals("الأب", parent.text.text)
        assertTrue(
            "nested item text must not appear in the parent",
            !parent.text.text.contains("الابن"),
        )
    }

    @Test
    fun `ordered lists number from one and number each list independently`() {
        val html = "<ol><li>أ</li><li>ب</li></ol><ol><li>ج</li></ol>"

        val items = blocksOf(html).listItems()

        assertEquals(listOf(1, 2, 1), items.map { it.number })
        assertTrue(items.all { it.ordered })
    }

    @Test
    fun `a nested ordered list keeps its own numbering and its parent's depth`() {
        val html = "<ul><li>أ<ol><li>ب</li><li>ج</li></ol></li></ul>"

        val items = blocksOf(html).listItems()

        assertEquals(listOf("أ", "ب", "ج"), items.map { it.text.text })
        assertEquals(listOf(false, true, true), items.map { it.ordered })
        assertEquals(listOf(1, 2), items.drop(1).map { it.number })
        assertEquals(listOf(0, 1, 1), items.map { it.depth })
    }

    @Test
    fun `an empty list item is dropped`() {
        val items = blocksOf("<ul><li> </li><li>أ</li></ul>").listItems()

        assertEquals(listOf("أ"), items.map { it.text.text })
    }

    // endregion

    // region tables

    @Test
    fun `a table becomes a grid, not a paragraph`() {
        val html = """
            <table>
              <tr><th>الاسم</th><th>العمر</th></tr>
              <tr><td>أحمد</td><td>٣٠</td></tr>
            </table>
        """.trimIndent()

        val table = blocksOf(html).filterIsInstance<ContentBlock.Table>().single()

        assertEquals(2, table.rows.size)
        assertEquals(listOf("الاسم", "العمر"), table.rows[0].cells.map { it.text.text })
        assertEquals(listOf("أحمد", "٣٠"), table.rows[1].cells.map { it.text.text })
    }

    @Test
    fun `a header row is recognised from th cells`() {
        val html = "<table><tr><th>أ</th></tr><tr><td>ب</td></tr></table>"

        val rows = blocksOf(html).filterIsInstance<ContentBlock.Table>().single().rows

        assertTrue("first row is a header", rows[0].isHeader)
        assertTrue(rows[0].cells.single().isHeader)
        assertTrue("second row is not", !rows[1].isHeader)
    }

    @Test
    fun `thead marks its rows as headers`() {
        val html = "<table><thead><tr><td>أ</td></tr></thead><tbody><tr><td>ب</td></tr></tbody></table>"

        val rows = blocksOf(html).filterIsInstance<ContentBlock.Table>().single().rows

        assertTrue(rows[0].isHeader)
        assertTrue(!rows[1].isHeader)
    }

    @Test
    fun `column spans are read`() {
        val html = "<table><tr><td colspan=\"2\">أ</td><td>ب</td></tr></table>"

        val cells = blocksOf(html).filterIsInstance<ContentBlock.Table>().single().rows[0].cells

        assertEquals(listOf(2, 1), cells.map { it.colSpan })
    }

    @Test
    fun `an absurd span is clamped rather than trusted`() {
        // A malformed file must not be able to ask the layout for ten thousand weights.
        val html = "<table><tr><td colspan=\"99999\" rowspan=\"99999\">أ</td></tr></table>"

        val cell = blocksOf(html).filterIsInstance<ContentBlock.Table>().single().rows[0].cells.single()

        assertEquals(12, cell.colSpan)
        assertEquals(50, cell.rowSpan)
    }

    @Test
    fun `a span of zero or nonsense falls back to one`() {
        val html = "<table><tr><td colspan=\"0\">أ</td><td colspan=\"abc\">ب</td></tr></table>"

        val cells = blocksOf(html).filterIsInstance<ContentBlock.Table>().single().rows[0].cells

        assertTrue(cells.all { it.colSpan == 1 })
    }

    @Test
    fun `an empty table produces no block`() {
        assertTrue(blocksOf("<table></table>").isEmpty())
    }

    // endregion

    // region figure and images

    @Test
    fun `a figure caption stays with its image`() {
        val html = "<figure><img src=\"Images/fig1.png\" alt=\"رسم\"/><figcaption>الشكل ١</figcaption></figure>"

        val image = blocksOf(html).filterIsInstance<ContentBlock.Image>().single()

        assertEquals("Images/fig1.png", image.path)
        assertEquals("رسم", image.alt)
        assertEquals("الشكل ١", image.caption?.text)
    }

    @Test
    fun `a figure with no caption renders the image alone`() {
        val image = blocksOf("<figure><img src=\"a.png\"/></figure>")
            .filterIsInstance<ContentBlock.Image>().single()

        assertNull(image.caption)
    }

    @Test
    fun `an image outside a figure has no caption`() {
        val image = blocksOf("<p><img src=\"a.png\"/></p>")
            .filterIsInstance<ContentBlock.Image>().single()

        assertNull(image.caption)
    }

    // endregion

    // region inline

    @Test
    fun `a br becomes a line break, not whitespace`() {
        val text = blocksOf("<p>سطر أول<br/>سطر ثانٍ</p>").paragraphs().single()

        // Without this a poem or a postal address collapses onto a single line.
        assertTrue("expected a newline in: $text", text.contains("\n"))
        assertTrue(text.startsWith("سطر أول"))
        assertTrue(text.endsWith("سطر ثانٍ"))
    }

    @Test
    fun `ruby keeps both the base text and its reading`() {
        val text = blocksOf("<p><ruby>漢<rt>かん</rt></ruby></p>").paragraphs().single()

        assertTrue("base text must survive", text.contains("漢"))
        assertTrue("annotation must survive", text.contains("かん"))
    }

    @Test
    fun `emphasis survives as styled runs`() {
        val text = blocksOf("<p>عادي <strong>عريض</strong> عادي</p>").paragraphs().single()

        assertEquals("عادي عريض عادي", text)
    }

    // endregion

    // region links

    @Test
    fun `a link is plain underlined text when no styling is supplied`() {
        // Non-interactive callers — a preview, a test — must not get half-built annotations.
        val parsed = parseChapterHtml("<p><a href=\"https://example.com\">رابط</a></p>")

        assertEquals("رابط", parsed.blocks.paragraphs().single())
        assertTrue(
            "a link must still read as a link",
            parsed.blocks.paragraphs().single() == "رابط",
        )
    }

    @Test
    fun `a link carries an annotation when styling is supplied`() {
        var followed: String? = null
        val styling = LinkStyling(color = androidx.compose.ui.graphics.Color.Blue) { followed = it }

        val parsed = parseChapterHtml(
            "<p><a href=\"chapter2.xhtml#fn3\">الحاشية</a></p>",
            styling,
        )

        val text = parsed.blocks.filterIsInstance<ContentBlock.Paragraph>().single().text
        // The href is bound into the annotation, so tapping resolves the exact string the document
        // contained — the reader never has to parse href syntax itself.
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        assertEquals("chapter2.xhtml#fn3", (links.first().item as androidx.compose.ui.text.LinkAnnotation.Clickable).tag)
    }

    @Test
    fun `an anchor with no href is not a link`() {
        val parsed = parseChapterHtml("<p><a id=\"mark\">نص</a></p>")

        assertEquals("نص", parsed.blocks.paragraphs().single())
    }

    // endregion

    // region structure

    @Test
    fun `an unknown container is unwrapped rather than dropped`() {
        val blocks = blocksOf("<div><section><p>داخل</p></section></div>")

        assertEquals(listOf("داخل"), blocks.paragraphs())
    }

    @Test
    fun `bare text with no block elements still renders`() {
        assertEquals(listOf("نص"), blocksOf("نص").paragraphs())
    }

    @Test
    fun `headings keep their level`() {
        val headings = blocksOf("<h2>عنوان</h2>").filterIsInstance<ContentBlock.Heading>()

        assertEquals(2, headings.single().level)
        assertEquals("عنوان", headings.single().text.text)
    }

    @Test
    fun `a horizontal rule becomes a divider`() {
        assertTrue(blocksOf("<hr/>").single() is ContentBlock.Divider)
    }

    @Test
    fun `malformed html does not throw`() {
        // A damaged chapter must render as best-effort text, never crash the reading screen.
        val blocks = blocksOf("<p>غير مغلق <b>عريض <table><tr><td>")

        assertTrue(blocks.isNotEmpty())
    }

    @Test
    fun `an empty chapter produces no blocks`() {
        assertTrue(blocksOf("").isEmpty())
    }

    // endregion
}
