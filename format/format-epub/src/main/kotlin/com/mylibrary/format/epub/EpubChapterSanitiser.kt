package com.mylibrary.format.epub

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag

/** A chapter's content in the two forms the reader needs, both derived from the same tree. */
internal class SanitisedChapter(val html: String, val text: String)

/**
 * Turns a chapter's XHTML into the small, safe subset the reader styles itself.
 *
 * The reader owns typography: font size, line height, margins, colours and page direction are the
 * user's choices, and a chapter that carries `style="font-size:9pt; color:#333"` on every paragraph
 * fights them. So the policy is a whitelist in both directions — an element survives only if it
 * carries meaning (`p`, a heading, a list, emphasis, a table, an image), and an attribute survives
 * only if it carries meaning *the stylesheet cannot reconstruct*:
 *
 *  - `dir` and `lang` (and their XHTML spelling `xml:lang`) are kept. They are not decoration: an
 *    Arabic or Hebrew chapter inside an English UI renders as broken bidi text without them, and no
 *    stylesheet can recover information that was thrown away.
 *  - `id` is kept so that in-book links and TOC fragments still have something to land on.
 *  - `href`, `src`, `alt` and the table cell spans are kept because they are content.
 *  - `epub:type` on an `<a>` is kept, but only the terms `noteref` and `footnote`. It is the one
 *    attribute whose value is filtered rather than passed through, because its vocabulary is
 *    open-ended: a book may write anything there, and everything but those two terms means nothing
 *    to a reader that decides its own presentation.
 *  - Everything else — `style`, `class`, `font`, `color`, `bgcolor`, `width`, `height`, `align`,
 *    `face`, `size`, and every `on*` handler — is dropped.
 *
 * Non-whitelisted *elements* are unwrapped rather than deleted, so text inside an unknown wrapper
 * still reaches the reader; elements that are not content at all (`script`, `style`, `link`,
 * `iframe`, `object`, `embed`, media and form controls) are deleted with their contents, because a
 * script's source text is not part of the book.
 *
 * Document-level directionality is lifted onto a wrapper `<div>`: `dir` usually sits on `<html>` or
 * `<body>`, and since only the body's *inner* HTML is returned, an Arabic book would otherwise lose
 * the one attribute that makes it readable.
 */
internal object EpubChapterSanitiser {

    /** Elements that carry meaning and survive as themselves. */
    private val KEPT_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6",
        "em", "strong", "i", "b", "u", "s", "sup", "sub",
        "ul", "ol", "li", "blockquote", "br", "hr",
        "img", "figure", "figcaption",
        "table", "thead", "tbody", "tr", "td", "th",
        "a", "ruby", "rt",
    )

    /**
     * Elements removed with everything inside them.
     *
     * `svg` is on this list even though it can hold an image: this engine is a reflowable text
     * engine, and a fixed-layout page drawn as SVG has no text to reflow. Keeping a bare `<svg>`
     * without its geometry would render worse than dropping it.
     */
    private val REMOVED_TAGS = setOf(
        "script", "style", "link", "iframe", "frame", "frameset", "object", "embed", "applet",
        "param", "form", "input", "select", "option", "textarea", "button", "meta", "base",
        "noscript", "template", "canvas", "audio", "video", "source", "track", "svg", "math",
    )

    /** Attributes kept on any element: direction and language, plus anchor targets. */
    private val GLOBAL_ATTRIBUTES = setOf("dir", "lang", "xml:lang", "id")

    /**
     * The link attribute that survives, spelled as XHTML spells it.
     *
     * The `epub:` prefix is part of the attribute *name*, not a namespace jsoup resolves: jsoup's HTML
     * parser lower-cases attribute names but leaves the prefix attached, so `epub:type` is exactly
     * what arrives here whatever case the document wrote it in. Matching the unprefixed `type` would
     * also catch the XHTML attribute of that name, which says nothing about linking.
     */
    private const val LINK_TYPE_ATTRIBUTE = "epub:type"

    /**
     * The `epub:type` terms that mean something to a reader, and the only ones that survive.
     *
     * `epub:type` is EPUB 3's open vocabulary: a book may write anything there, and most of it
     * describes structure the reader already decides for itself (`landmarks`, `pagebreak`, a
     * publisher's own terms). `noteref` and `footnote` are the exception — they are the only
     * machine-readable signal separating a footnote reference from an ordinary web link, and nothing
     * in the markup recovers that distinction once the attribute is dropped. Everything else is
     * dropped rather than passed through: forwarding arbitrary vocabulary would let any document push
     * its own terms into the reader's output for no benefit.
     */
    private val LINK_TYPE_VALUES = setOf("noteref", "footnote")

    /** Attributes kept on specific elements, because they are content rather than presentation. */
    private val ELEMENT_ATTRIBUTES = mapOf(
        "a" to setOf("href", LINK_TYPE_ATTRIBUTE),
        "img" to setOf("src", "alt"),
        "td" to setOf("colspan", "rowspan"),
        "th" to setOf("colspan", "rowspan"),
    )

    /**
     * Sanitises one chapter.
     *
     * @param resourcePath rewrites an href written in this chapter's markup into the form
     *   [EpubDocument.resource] accepts, or returns `null` when it points outside the archive. The
     *   caller closes over the chapter's own directory, which is what makes the rewrite possible.
     */
    fun sanitise(bytes: ByteArray, resourcePath: (String) -> String?): SanitisedChapter {
        val document = EpubXml.parseHtml(bytes)
        // jsoup's HTML parser always materialises a body, even for a fragment or an empty file.
        val source = document.body()
        val content = Element(Tag.valueOf("div"), "")
        copyChildren(source, content, resourcePath)
        val direction = documentDirectionality(document)
        if (direction.isNotEmpty()) direction.forEach { (name, value) -> content.attr(name, value) }
        // `text()` is taken from the same cleaned tree the HTML is serialised from, so a search
        // offset into the text and a position in the HTML can never disagree about what the chapter
        // says: both describe exactly the nodes that survived the whitelist.
        val text = content.text()
        val html = if (direction.isEmpty()) content.html() else content.outerHtml()
        return SanitisedChapter(html, text)
    }

    /**
     * `dir`/`lang` declared on `<body>` or `<html>`, most specific first.
     *
     * Only the body's contents are returned, so these have to be re-stated on a wrapper or they are
     * lost — and for a right-to-left book they are the difference between a readable chapter and a
     * reversed one.
     */
    private fun documentDirectionality(document: Document): Map<String, String> {
        val declared = LinkedHashMap<String, String>()
        for (element in listOfNotNull(document.body(), document.children().firstOrNull())) {
            for (name in listOf("dir", "lang", "xml:lang")) {
                val value = element.attrNamed(name)?.trim().orEmpty()
                if (value.isNotEmpty()) declared.putIfAbsent(name, value)
            }
        }
        // The wrapper is written as HTML, where the language attribute is `lang` and `xml:lang` means
        // nothing to CSS `:lang()` matching. A chapter that declares only the XHTML spelling would
        // otherwise lose its language the moment it is re-serialised, so the wrapper states both.
        declared["xml:lang"]?.let { declared.putIfAbsent("lang", it) }
        // Rebuilt in a fixed order so the wrapper is spelled the same way for every chapter.
        val ordered = LinkedHashMap<String, String>(declared.size)
        for (name in listOf("dir", "lang", "xml:lang")) declared[name]?.let { ordered[name] = it }
        return ordered
    }

    private fun copyChildren(source: Element, target: Element, resourcePath: (String) -> String?) {
        for (node in source.childNodes()) {
            when (node) {
                is TextNode -> target.appendChild(TextNode(node.wholeText))
                is Element -> copyElement(node, target, resourcePath)
                // Comments, CDATA and processing instructions: nothing the reader can style, and
                // comments in particular leak the producer's toolchain into every chapter.
                else -> Unit
            }
        }
    }

    private fun copyElement(source: Element, target: Element, resourcePath: (String) -> String?) {
        val name = source.normalName()
        if (name in REMOVED_TAGS) return
        if (name !in KEPT_TAGS) {
            // An element the reader has no styling for: keep its text, drop the element.
            copyChildren(source, target, resourcePath)
            return
        }
        val resolvedSource = if (name == "img") resolveImageSource(source, resourcePath) else null
        if (name == "img" && resolvedSource == null && source.attrNamed("alt").isNullOrBlank()) {
            // An image with no usable file and no alt text is a broken-image icon; nothing else.
            return
        }
        val copy = Element(Tag.valueOf(name), "")
        copyAttributes(source, copy, name, resolvedSource)
        target.appendChild(copy)
        copyChildren(source, copy, resourcePath)
    }

    /**
     * Rewrites an `<img src>` into the form the reader can resolve.
     *
     * The reader receives the sanitised HTML with no idea where the chapter lived inside the
     * archive, so a relative `../Images/plate.png` would be unusable. The href is resolved against
     * the chapter's own directory and re-expressed relative to the package document, which is the
     * path [EpubDocument.resource] documents and takes.
     */
    private fun resolveImageSource(source: Element, resourcePath: (String) -> String?): String? {
        val href = source.attrNamed("src")?.trim().orEmpty()
        if (href.isEmpty()) return null
        // A data URI is already self-contained; only archive-relative hrefs need resolving.
        if (href.startsWith("data:")) return href
        return resourcePath(href)
    }

    private fun copyAttributes(source: Element, target: Element, tag: String, resolvedSource: String?) {
        val specific = ELEMENT_ATTRIBUTES[tag].orEmpty()
        for (attribute in source.attributes()) {
            val name = attribute.key.lowercase()
            when {
                // The one attribute whose value is rewritten, so it is applied by the caller.
                name == "src" -> resolvedSource?.let { target.attr("src", it) }
                name !in GLOBAL_ATTRIBUTES && name !in specific -> Unit
                name == LINK_TYPE_ATTRIBUTE -> meaningfulLinkTypes(attribute.value)?.let { target.attr(name, it) }
                // `alt=""` is a real statement — "this image is decorative, announce nothing" — so
                // it survives; an empty `dir=""` or `id=""` is just noise the reader has to ignore.
                name == "alt" -> target.attr(name, attribute.value)
                attribute.value.isBlank() -> Unit
                else -> target.attr(name, attribute.value)
            }
        }
    }

    /**
     * The reader-meaningful terms of an `epub:type` value, or `null` when it has none.
     *
     * The value is a space-separated property list (`epub:type="noteref footnote"` is legal), so the
     * terms are filtered one at a time rather than the whole string being matched against a list —
     * a book that tags a link with a footnote term *and* a term the reader ignores still gets a
     * tappable footnote out of it. Terms are compared without regard to case and written back in the
     * lowercase form the specification defines, because producers do mistype the case and the reader
     * should have exactly one spelling to compare against.
     */
    private fun meaningfulLinkTypes(value: String): String? =
        value.split(' ', '\t', '\n', '\r')
            .map { it.lowercase() }
            .filter { it in LINK_TYPE_VALUES }
            .joinToString(" ")
            .ifEmpty { null }
}
