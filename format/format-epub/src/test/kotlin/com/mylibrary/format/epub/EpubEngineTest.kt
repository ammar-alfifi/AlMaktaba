package com.mylibrary.format.epub

import com.google.common.truth.Truth.assertThat
import com.mylibrary.core.common.AppError
import com.mylibrary.core.common.AppResult
import com.mylibrary.core.common.errorOrNull
import com.mylibrary.core.common.getOrNull
import com.mylibrary.core.domain.engine.DocumentSource
import com.mylibrary.core.domain.engine.OpenDocument
import com.mylibrary.core.domain.engine.ReflowableDocument
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.ReadingLocator
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Test

/**
 * The engine, exercised through its public contract.
 *
 * The fixture is a real zip built in memory (see [EpubBuilder]), because the failures this format
 * produces — a percent-encoded href, a `..` that escapes the archive, a nav document that resolves
 * to nothing — only appear once the bytes actually go through `java.util.zip`.
 */
class EpubEngineTest {

    private val engine = EpubEngine()

    private fun open(bytes: ByteArray): AppResult<OpenDocument> =
        runBlocking { engine.open(FakeDocumentSource(bytes)) }

    private fun openBook(bytes: ByteArray = EpubBuilder.standard().build()): ReflowableDocument {
        val result = open(bytes)
        assertThat(result.errorOrNull()).isNull()
        return result.getOrNull() as ReflowableDocument
    }

    private fun ReflowableDocument.textOf(index: Int): String = runBlocking { chapterText(index) }

    private fun ReflowableDocument.htmlOf(index: Int): String = runBlocking { chapterHtml(index) }

    @Test
    fun `supports only epub`() {
        assertThat(engine.supports(BookFormat.EPUB)).isTrue()
        assertThat(engine.supports(BookFormat.PDF)).isFalse()
        assertThat(engine.supports(BookFormat.TXT)).isFalse()
    }

    @Test
    fun `reads the package metadata`() {
        val document = openBook()

        assertThat(document.metadata.title).isEqualTo("The Shifting Sands")
        assertThat(document.metadata.author).isEqualTo("Ammar K.")
        assertThat(document.metadata.language).isEqualTo("ar")
        assertThat(document.metadata.publisher).isEqualTo("MyLibrary Press")
        assertThat(document.metadata.description).isEqualTo("A book about sand.")
        assertThat(document.metadata.identifier).isEqualTo("urn:uuid:6f4a1b90")
    }

    @Test
    fun `opens as a reflowable document`() {
        assertThat(openBook()).isInstanceOf(EpubDocument::class.java)
    }

    @Test
    fun `declares what the reader may show`() {
        val document = openBook()

        assertThat(document.capabilities.canSearch).isTrue()
        assertThat(document.capabilities.canExtractText).isTrue()
        assertThat(document.capabilities.canRenderPages).isFalse()
        assertThat(document.capabilities.hasOutline).isTrue()
        assertThat(document.format).isEqualTo(BookFormat.EPUB)
    }

    @Test
    fun `spine order is the reading order`() {
        val document = openBook()

        // The stylesheet in the spine is not a chapter, and the item whose href escapes the archive
        // is not either, so three chapters remain: cover, chapter one, chapter two.
        assertThat(document.chapterCount).isEqualTo(3)
        assertThat(document.chapter(0).title).isEqualTo("Cover")
        assertThat(document.chapter(1).title).isEqualTo("Chapter One")
        assertThat(document.chapter(2).title).isEqualTo("Chapter Two")

        assertThat(document.textOf(0)).contains("A cover page")
        assertThat(document.textOf(1)).contains("The quick brown fox")
        assertThat(document.textOf(2)).contains("The second chapter mentions sand")
        assertThat(document.textOf(0)).doesNotContain("quick brown fox")

        assertThat(document.chapter(1).locator).isEqualTo(ReadingLocator.Reflowable(1, 0))
    }

    @Test
    fun `linear no items are still chapters`() {
        // The cover is marked linear="no". It is reachable content and a TOC target, so it stays in
        // the reading order rather than vanishing from the book.
        val document = openBook()

        assertThat(document.textOf(0)).contains("A cover page")
    }

    @Test
    fun `reads a percent-encoded href`() {
        // The manifest says `Text/chapter%20one.xhtml`; the zip stores `Text/chapter one.xhtml`.
        // Chapter one existing at all is the assertion; its text proves it is the right file.
        val document = openBook()

        assertThat(document.textOf(1)).contains("Kafkaesque marmalade")
    }

    @Test
    fun `a spine item that resolves to nothing is not a chapter`() {
        // Two of the fixture's spine items are unreadable: `../../Notes/notes.xhtml` climbs past the
        // archive root, and nothing in the book is that file. Neither becomes a chapter.
        val document = openBook()

        assertThat(document.chapterCount).isEqualTo(3)
        for (index in 0 until document.chapterCount) {
            assertThat(document.textOf(index)).doesNotContain("notes")
        }
    }

    // --- Table of contents ------------------------------------------------------------------

    @Test
    fun `epub 3 nav document gives the outline, with nesting`() {
        val outline = openBook().outline

        assertThat(outline.map { it.title })
            .containsExactly("Cover", "Chapter One", "Chapter Two", "Cover Again")
            .inOrder()

        val chapterOne = outline[1]
        assertThat(chapterOne.level).isEqualTo(0)
        assertThat(chapterOne.locator).isEqualTo(ReadingLocator.Reflowable(1, 0))
        assertThat(chapterOne.children.map { it.title }).containsExactly("The Opening")
        assertThat(chapterOne.children[0].level).isEqualTo(1)

        // "Chapter Two" was nested under an entry pointing at a file that is not in the book, so it
        // is hoisted to the level its parent would have occupied, and keeps its own child.
        val chapterTwo = outline[2]
        assertThat(chapterTwo.level).isEqualTo(0)
        assertThat(chapterTwo.locator).isEqualTo(ReadingLocator.Reflowable(2, 0))
        assertThat(chapterTwo.children.map { it.title }).containsExactly("The End")
        assertThat(chapterTwo.children[0].level).isEqualTo(1)
    }

    @Test
    fun `the nav document wins over the ncx when a book has both`() {
        // The fixture's NCX is deliberately shaped differently, so this fails loudly if the NCX is
        // read instead: it says "NCX Only", which the nav document never says.
        val outline = openBook().outline

        assertThat(outline.map { it.title }).doesNotContain("NCX Only")
        assertThat(outline.map { it.title }).contains("Chapter One")
    }

    @Test
    fun `falls back to the ncx when there is no nav document`() {
        val bytes = EpubBuilder.standard()
            .without("OEBPS/nav.xhtml")
            .file("OEBPS/content.opf", OPF_XML_WITHOUT_NAV)
            .build()
        val document = openBook(bytes)

        assertThat(document.outline.map { it.title }).containsExactly("NCX Only", "NCX Second")

        val nested = document.outline[1]
        assertThat(nested.level).isEqualTo(0)
        // `Text/chapter%20one.xhtml` in the NCX resolves to the same file the manifest names.
        assertThat(nested.locator).isEqualTo(ReadingLocator.Reflowable(1, 0))
        assertThat(nested.children.map { it.title }).containsExactly("NCX Nested")
        assertThat(nested.children[0].locator).isEqualTo(ReadingLocator.Reflowable(0, 0))
    }

    @Test
    fun `a book with neither navigation format still opens`() {
        val bytes = EpubBuilder.standard()
            .without("OEBPS/nav.xhtml")
            .without("OEBPS/toc.ncx")
            .file("OEBPS/content.opf", OPF_XML_WITHOUT_NAV)
            .build()
        val document = openBook(bytes)

        assertThat(document.outline).isEmpty()
        assertThat(document.capabilities.hasOutline).isFalse()
        assertThat(document.chapterCount).isEqualTo(3)
    }

    // --- Sanitisation -----------------------------------------------------------------------

    @Test
    fun `strips scripts and everything that runs`() {
        val html = openBook().htmlOf(1)

        assertThat(html).doesNotContain("<script")
        assertThat(html).doesNotContain("alert")
        assertThat(html).doesNotContain("<style")
        assertThat(html).doesNotContain("<link")
        assertThat(html).doesNotContain("<iframe")
        assertThat(html).doesNotContain("onclick")
        assertThat(html).doesNotContain("onerror")
        assertThat(html).doesNotContain("evil()")
    }

    @Test
    fun `strips presentation but keeps structure`() {
        val html = openBook().htmlOf(1)

        assertThat(html).doesNotContain("style=")
        assertThat(html).doesNotContain("class=")
        assertThat(html).doesNotContain("<font")
        assertThat(html).doesNotContain("width=")
        assertThat(html).doesNotContain("color=")
        assertThat(html).doesNotContain("<!--")

        assertThat(html).contains("<h1 id=\"opening\">")
        assertThat(html).contains("<em>بك</em>")
        assertThat(html).contains("<ul>")
        assertThat(html).contains("<li>first item</li>")
        assertThat(html).contains("<table>")
        assertThat(html).contains("<td colspan=\"2\">a cell</td>")
        assertThat(html).contains("<b>bold</b>")
        assertThat(html).contains("<blockquote dir=\"ltr\">")
    }

    @Test
    fun `keeps direction and language`() {
        val html = openBook().htmlOf(1)

        // On the paragraph the author wrote them on...
        assertThat(html).contains("<p dir=\"rtl\" lang=\"ar\">")
        // ...and lifted onto a wrapper, because they were declared on <html> and only the body's
        // contents are returned. The chapter declares its language as `xml:lang` — the XHTML
        // spelling, which HTML ignores — so the wrapper states it as `lang` as well. Without both
        // of these an Arabic chapter renders left-to-right, in the wrong language.
        assertThat(html).contains("<div dir=\"rtl\" lang=\"ar\" xml:lang=\"ar\">")
    }

    @Test
    fun `unwraps elements the reader has no styling for`() {
        val html = openBook().htmlOf(1)

        assertThat(html).doesNotContain("<div class=\"wrapper\">")
        assertThat(html).contains("Wrapped text survives.")
    }

    @Test
    fun `rewrites image sources to paths the resource reader accepts`() {
        val html = openBook().htmlOf(1)

        // `../Images/plate.png` from `OEBPS/Text/` is `OEBPS/Images/plate.png`, which is
        // `Images/plate.png` relative to the package document.
        assertThat(html).contains("<img src=\"Images/plate.png\" alt=\"A plate\">")
        assertThat(html).doesNotContain("../")
    }

    @Test
    fun `drops an image that points outside the archive`() {
        val html = openBook().htmlOf(1)

        // Its href climbs past the archive root, so there is nothing to point at. The element has
        // alt text, so it survives without a source and a reader can still show something.
        assertThat(html).contains("alt=\"Outside\"")
        assertThat(html).doesNotContain("secret.png")
    }

    // --- Text -------------------------------------------------------------------------------

    @Test
    fun `chapter text is the readable words only`() {
        val text = openBook().textOf(1)

        assertThat(text).contains("The quick brown fox jumps over the lazy dog.")
        assertThat(text).contains("مرحبا بك في الكتاب")
        assertThat(text).contains("first item")
        assertThat(text).contains("Wrapped text survives.")
        assertThat(text).doesNotContain("alert")
        assertThat(text).doesNotContain("color: red")
        assertThat(text).doesNotContain("<")
    }

    @Test
    fun `html and text describe the same content`() {
        // The two are computed from one sanitised tree, so the text must equal the text of the HTML
        // that was handed out — otherwise a search offset would point at a different word than the
        // one the reader highlights.
        val document = openBook()

        for (index in 0 until document.chapterCount) {
            val html = document.htmlOf(index)
            assertThat(Jsoup.parseBodyFragment(html).text()).isEqualTo(document.textOf(index))
        }
    }

    // --- Resources --------------------------------------------------------------------------

    @Test
    fun `resource returns image bytes`() {
        val document = openBook()

        val bytes = runBlocking { document.resource("Images/plate.png") }

        assertThat(bytes).isEqualTo(PLATE_PNG)
    }

    @Test
    fun `resource accepts a path relative to the archive root`() {
        val document = openBook()

        assertThat(runBlocking { document.resource("/OEBPS/Images/plate.png") }).isEqualTo(PLATE_PNG)
        assertThat(runBlocking { document.resource("OEBPS/Images/plate.png") }).isEqualTo(PLATE_PNG)
    }

    @Test
    fun `resource refuses to leave the archive`() {
        val document = openBook()

        // Climbing past the archive root names nothing, and resolving it to "whatever is above the
        // package document" is exactly the bug this guard exists to prevent. Percent-encoded, too.
        assertThat(runBlocking { document.resource("../../outside/secret.png") }).isNull()
        assertThat(runBlocking { document.resource("../../../etc/passwd") }).isNull()
        assertThat(runBlocking { document.resource("%2E%2E%2F%2E%2E%2Foutside/secret.png") }).isNull()
    }

    @Test
    fun `resource returns null for anything absent`() {
        val document = openBook()

        assertThat(runBlocking { document.resource("Images/missing.png") }).isNull()
        assertThat(runBlocking { document.resource("") }).isNull()
        assertThat(runBlocking { document.resource("OEBPS/Styles/book.css") })
            .isEqualTo("p { color: red }".asBytes())
    }

    // --- Search -----------------------------------------------------------------------------

    @Test
    fun `search finds a word and says exactly where it is`() = runBlocking<Unit> {
        val document = openBook()
        val chapterText = document.chapterText(1)
        val expectedOffset = chapterText.indexOf("marmalade")

        val hits = document.search("marmalade")

        assertThat(hits).hasSize(1)
        val hit = hits[0]
        assertThat(hit.locator).isEqualTo(ReadingLocator.Reflowable(1, expectedOffset))
        assertThat(hit.label).isEqualTo("Chapter One")
        assertThat(hit.snippet.substring(hit.matchStart, hit.matchEnd)).isEqualTo("marmalade")
        assertThat(chapterText.substring(expectedOffset)).startsWith("marmalade")
        assertThat(hit.snippet.length).isAtMost(80)
    }

    @Test
    fun `search ignores case and keeps the text's own spelling`() = runBlocking<Unit> {
        val hits = openBook().search("QUICK BROWN")

        assertThat(hits).hasSize(1)
        assertThat(hits[0].snippet.substring(hits[0].matchStart, hits[0].matchEnd))
            .isEqualTo("quick brown")
    }

    @Test
    fun `search walks the book in reading order and respects the limit`() = runBlocking<Unit> {
        val document = openBook()

        val hits = document.search("the", limit = 2)
        assertThat(hits).hasSize(2)
        val chapters = hits.map { (it.locator as ReadingLocator.Reflowable).chapterIndex }
        assertThat(chapters).isInOrder()

        // "Cover" is the first chapter's TOC title, which is what a hit is labelled with.
        val coverHits = document.search("cover page")
        assertThat(coverHits.map { it.label }).containsExactly("Cover")
    }

    @Test
    fun `searching an empty query finds nothing`() = runBlocking<Unit> {
        val document = openBook()

        assertThat(document.search("")).isEmpty()
        assertThat(document.search("x", limit = 0)).isEmpty()
    }

    // --- Failure modes ----------------------------------------------------------------------

    @Test
    fun `bytes that are not a zip are a corrupt document`() {
        val result = open("this is not an epub, it is a sentence".asBytes())

        assertThat(result.errorOrNull()).isInstanceOf(AppError.CorruptDocument::class.java)
    }

    @Test
    fun `a zip with no container is a corrupt document`() {
        val bytes = zipOf(
            listOf(
                "mimetype" to MIMETYPE.asBytes(),
                "OEBPS/content.opf" to OPF_XML.asBytes(),
            ),
        )

        assertThat(open(bytes).errorOrNull()).isInstanceOf(AppError.CorruptDocument::class.java)
    }

    @Test
    fun `a container naming a missing package document is a corrupt document`() {
        val bytes = zipOf(
            listOf(
                "mimetype" to MIMETYPE.asBytes(),
                "META-INF/container.xml" to CONTAINER_XML.asBytes(),
            ),
        )

        assertThat(open(bytes).errorOrNull()).isInstanceOf(AppError.CorruptDocument::class.java)
    }

    @Test
    fun `a spine with nothing readable is an empty document`() {
        // Every spine item is either a stylesheet, an href that leaves the archive, or a file the
        // archive does not contain — so the book has nothing to show and says so.
        val bytes = EpubBuilder.standard()
            .file(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Nothing</dc:title></metadata>
                  <manifest>
                    <item id="styles" href="Styles/book.css" media-type="text/css"/>
                    <item id="notes" href="../Notes/notes.xhtml" media-type="application/xhtml+xml"/>
                    <item id="gone" href="Text/gone.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="styles"/>
                    <itemref idref="notes"/>
                    <itemref idref="gone"/>
                  </spine>
                </package>
                """.trimIndent(),
            )
            .build()

        assertThat(open(bytes).errorOrNull()).isInstanceOf(AppError.EmptyDocument::class.java)
    }

    @Test
    fun `an encrypted book is reported as protected`() {
        val bytes = EpubBuilder.standard()
            .file(
                "META-INF/encryption.xml",
                """<?xml version="1.0"?>
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                    <EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
                    <CipherData><CipherReference URI="OEBPS/Text/chapter-one.xhtml"/></CipherData>
                  </EncryptedData>
                </encryption>
                """.trimIndent(),
            )
            .build()

        assertThat(open(bytes).errorOrNull()).isEqualTo(AppError.Protected)
    }

    @Test
    fun `obfuscated fonts are not drm`() {
        // IDPF font obfuscation only scrambles the embedded fonts; the book itself is readable, and
        // a large share of DRM-free commercial books ship exactly this file.
        val bytes = EpubBuilder.standard()
            .file(
                "META-INF/encryption.xml",
                """<?xml version="1.0"?>
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                    <EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                    <CipherData><CipherReference URI="OEBPS/Fonts/serif.otf"/></CipherData>
                  </EncryptedData>
                </encryption>
                """.trimIndent(),
            )
            .build()

        val document = openBook(bytes)
        assertThat(document.chapterCount).isEqualTo(3)
    }

    @Test
    fun `an unreadable source is a file access error`() {
        val result = runBlocking {
            engine.open(FakeDocumentSource(EpubBuilder.standard().build(), failOnOpen = true))
        }

        assertThat(result.errorOrNull()).isInstanceOf(AppError.FileAccess::class.java)
    }

    @Test
    fun `closing twice is harmless and reads afterwards are empty`() = runBlocking<Unit> {
        val document = openBook()

        document.close()
        document.close()

        assertThat(document.chapterHtml(0)).isEmpty()
        assertThat(document.chapterText(0)).isEmpty()
        assertThat(document.search("sand")).isEmpty()
        assertThat(document.resource("Images/plate.png")).isNull()
    }

    @Test
    fun `each chapter read re-opens the source and releases it`() = runBlocking<Unit> {
        // The engine holds no handle between calls: a read is a fresh stream, closed again before
        // it returns, which is what makes closing trivial and leaking impossible.
        val source = FakeDocumentSource(EpubBuilder.standard().build())
        val document = EpubEngine().open(source).getOrNull() as ReflowableDocument

        val afterOpen = source.openCount
        document.chapterHtml(1)
        val afterFirstRead = source.openCount
        document.chapterText(2)
        val afterSecondRead = source.openCount

        assertThat(afterFirstRead).isGreaterThan(afterOpen)
        assertThat(afterSecondRead).isGreaterThan(afterFirstRead)

        document.close()
    }

    @Test
    fun `a source is never asked for random access`() {
        // EPUB is read sequentially; the engine must not need a channel, because a `content://`
        // stream cannot always provide one.
        val source: DocumentSource = FakeDocumentSource(EpubBuilder.standard().build())

        assertThat(runBlocking { engine.open(source) }.getOrNull()).isNotNull()
    }
}
