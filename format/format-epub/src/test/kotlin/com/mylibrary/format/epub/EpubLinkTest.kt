package com.mylibrary.format.epub

import com.google.common.truth.Truth.assertThat
import com.mylibrary.core.common.getOrNull
import com.mylibrary.core.domain.engine.LinkTarget
import com.mylibrary.core.domain.engine.ReflowableDocument
import com.mylibrary.core.domain.model.ReadingLocator
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Test

/**
 * Hyperlinks: the one thing the sanitiser keeps about them, and where `resolveLink` says one points.
 *
 * A file of its own rather than another section of [EpubEngineTest], because this is a feature with
 * its own rules — fragment-only, relative, absolute, escaping, unresolvable — each of which fails
 * differently in the wild, and because the failures are silent: a link that resolves to the wrong
 * chapter opens a real chapter, just not the one the author meant.
 *
 * The fixture (see [CHAPTER_ONE_XHTML]) deliberately contains all three kinds of link a reader has
 * to tell apart, and the chapter numbers below are the fixture's spine: 0 cover, 1 chapter one,
 * 2 chapter two.
 */
class EpubLinkTest {

    private fun openBook(): ReflowableDocument =
        runBlocking { EpubEngine().open(FakeDocumentSource(EpubBuilder.standard().build())) }
            .getOrNull() as ReflowableDocument

    private fun ReflowableDocument.resolve(chapterIndex: Int, href: String): LinkTarget? =
        runBlocking { resolveLink(chapterIndex, href) }

    private fun ReflowableDocument.htmlOf(index: Int): String = runBlocking { chapterHtml(index) }

    private fun internalTarget(chapterIndex: Int, anchor: String? = null): LinkTarget =
        LinkTarget.Internal(ReadingLocator.Reflowable(chapterIndex, charOffset = 0), anchor)

    // --- Resolving an href -------------------------------------------------------------------

    @Test
    fun `a fragment only href points into the chapter it was written in`() {
        val document = openBook()

        assertThat(document.resolve(1, "#opening")).isEqualTo(internalTarget(1, "opening"))
    }

    @Test
    fun `the engine resolves the chapter and never guesses a character offset`() {
        // The offset is zero for every target, whatever the anchor: this engine owns the href grammar
        // and the spine, but it does not lay a chapter out, so it cannot know where the anchor lands
        // in the rendered text. Guessing here would put the reader at a plausible wrong place.
        val document = openBook()

        val target = document.resolve(1, "#opening") as LinkTarget.Internal
        assertThat(target.locator).isEqualTo(ReadingLocator.Reflowable(1, 0))
    }

    @Test
    fun `an empty fragment means the top of the chapter`() {
        val document = openBook()

        assertThat(document.resolve(1, "#")).isEqualTo(internalTarget(1, anchor = null))
        assertThat(document.resolve(1, "chapter-two.xhtml#")).isEqualTo(internalTarget(2, anchor = null))
    }

    @Test
    fun `a relative href resolves to the chapter it names`() {
        val document = openBook()

        // Within the chapter's own directory, and climbing out of it — `OEBPS/Text` to `OEBPS/Text`
        // through `..` — which is the same file by a longer road.
        assertThat(document.resolve(1, "chapter-two.xhtml")).isEqualTo(internalTarget(2))
        assertThat(document.resolve(1, "../Text/chapter-two.xhtml")).isEqualTo(internalTarget(2))
        // Backwards in the spine, and across a directory: an href is relative to the chapter that
        // wrote it, never to the package document.
        assertThat(document.resolve(2, "cover.xhtml")).isEqualTo(internalTarget(0))
    }

    @Test
    fun `a relative href carries its fragment as the anchor`() {
        val document = openBook()

        assertThat(document.resolve(1, "chapter-two.xhtml#end")).isEqualTo(internalTarget(2, "end"))
        // The percent-encoded path, which the fixture's manifest and TOC also write that way: the
        // path decodes to the chapter that exists, and the fragment rides along.
        assertThat(document.resolve(2, "../Text/chapter%20one.xhtml#opening"))
            .isEqualTo(internalTarget(1, "opening"))
    }

    @Test
    fun `a percent-encoded fragment is decoded`() {
        val document = openBook()

        // `%D9%85...` is "ملاحظة". A fragment is a document identifier rather than a path, but it is
        // percent-encoded in the href all the same, and an element's `id` is the decoded text — so an
        // anchor handed over still encoded would never match anything in the reader's anchor map.
        assertThat(document.resolve(1, "#%D9%85%D9%84%D8%A7%D8%AD%D8%B8%D8%A9"))
            .isEqualTo(internalTarget(1, "ملاحظة"))
        assertThat(document.resolve(1, "chapter-two.xhtml#%D9%85%D9%84%D8%A7%D8%AD%D8%B8%D8%A9"))
            .isEqualTo(internalTarget(2, "ملاحظة"))
    }

    @Test
    fun `anything with a scheme is external and left exactly as written`() {
        val document = openBook()

        for (href in listOf(
            "http://example.com/sand",
            "https://example.com/sand?page=2#top",
            "mailto:editor@example.com?subject=The%20Shifting%20Sands",
            "tel:+441234567890",
            // Not a scheme the platform is likely to know, and still not this decoder's business to
            // judge: rewriting or rejecting it here would only lose information the caller needs.
            "urn:isbn:9780000000000",
        )) {
            assertThat(document.resolve(1, href)).isEqualTo(LinkTarget.External(href))
        }
    }

    @Test
    fun `an href naming no chapter resolves to nothing`() {
        val document = openBook()

        // A file the book never shipped, a stylesheet that is in the manifest and even in the spine
        // but is not a chapter, and an image: none of them is somewhere to read on to.
        assertThat(document.resolve(1, "nowhere.xhtml")).isNull()
        assertThat(document.resolve(1, "../Styles/book.css")).isNull()
        assertThat(document.resolve(1, "../Images/plate.png")).isNull()
    }

    @Test
    fun `an href that leaves the archive resolves to nothing`() {
        val document = openBook()

        // Three levels up from `OEBPS/Text` climbs past the root, where there is nothing at all —
        // and the encoded spelling has to fail the same way, or the guard is decorative.
        assertThat(document.resolve(1, "../../../outside.xhtml")).isNull()
        assertThat(document.resolve(1, "%2E%2E%2F%2E%2E%2F%2E%2E%2Foutside.xhtml")).isNull()
        // Two levels up is the archive root: a real place that simply holds no such file. It resolves
        // to nothing for the other reason, which is worth stating because the two look alike.
        assertThat(document.resolve(1, "../../outside.xhtml")).isNull()
    }

    @Test
    fun `a blank href resolves to nothing`() {
        val document = openBook()

        assertThat(document.resolve(1, "")).isNull()
        assertThat(document.resolve(1, "   ")).isNull()
    }

    @Test
    fun `a chapter the book does not have resolves to nothing`() {
        // A caller passing an index outside the spine is a bug, but taking the reading screen down
        // over it would be a worse one.
        val document = openBook()

        assertThat(document.resolve(99, "#opening")).isNull()
        assertThat(document.resolve(-1, "chapter-two.xhtml")).isNull()
    }

    @Test
    fun `a closed document resolves nothing`() {
        val document = openBook()

        document.close()

        assertThat(document.resolve(1, "#opening")).isNull()
        assertThat(document.resolve(1, "https://example.com/sand")).isNull()
    }

    // --- What the sanitiser keeps ------------------------------------------------------------

    @Test
    fun `the epub type of a link survives when it means something to a reader`() {
        val html = openBook().htmlOf(1)

        // `epub:type` is the only machine-readable signal that separates a footnote reference from an
        // ordinary web address: nothing else in the markup says so, and a reader that cannot tell the
        // two apart cannot offer "jump to the note" on one and "open in a browser" on the other.
        assertThat(html).contains("<a href=\"#note-1\" epub:type=\"noteref\">1</a>")
        // The value is a property list, so a term the reader ignores must not cost it the term it
        // understands — and the terms are written back in the specification's lowercase spelling.
        assertThat(html).contains("<a href=\"chapter-two.xhtml#end\" epub:type=\"noteref footnote\">")
    }

    @Test
    fun `epub type vocabulary the reader has no use for is dropped`() {
        val html = openBook().htmlOf(1)

        // `landmarks` is real EPUB vocabulary and `banana` is nonsense; neither changes what a tap on
        // the link should do, so neither reaches the reader. Letting arbitrary vocabulary through
        // would only give a document a way to say something the reader must then ignore.
        assertThat(html).doesNotContain("landmarks")
        assertThat(html).doesNotContain("banana")
        // The links themselves are content and stay, with their hrefs verbatim: resolving one is the
        // engine's job at tap time, and it needs the href as the author wrote it to do it.
        assertThat(html).contains("<a href=\"https://example.com/sand\">Sand</a>")
        assertThat(html).contains("<a href=\"mailto:editor@example.com\">the editor</a>")
    }

    @Test
    fun `every link in a chapter can be resolved`() {
        // The two halves of the feature have to agree: whatever the sanitiser leaves in a chapter is
        // what the reader will hand back, so a link that survives sanitisation must be resolvable.
        val document = openBook()
        val links = Jsoup.parseBodyFragment(document.htmlOf(1)).select("a[href]")

        assertThat(links).hasSize(5)
        for (link in links) {
            assertThat(document.resolve(1, link.attr("href"))).isNotNull()
        }
    }
}
