package com.mylibrary.format.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.nio.charset.Charset

/**
 * The module's one XML/HTML front door.
 *
 * Everything — `container.xml`, the OPF, the NCX, the nav document and every chapter — is parsed by
 * jsoup, in both its XML and its HTML mode, rather than by `javax.xml`/`org.w3c.dom`. Three reasons,
 * in order of weight:
 *
 *  - **Tolerance.** Real books carry undeclared HTML entities (`&nbsp;` inside a `dc:title`), stray
 *    `&`, unquoted attributes and markup that is XHTML in name only. A conforming XML parser rejects
 *    all of that outright; jsoup reads it the way a browser would, and a reader that refuses to open
 *    a book because of a stray ampersand in its title is worse than one that shows the title.
 *  - **No XXE by construction.** jsoup never resolves external entities or fetches a DTD, so a
 *    malicious EPUB cannot turn metadata parsing into a file read or an outbound request. A default
 *    `DocumentBuilderFactory` does exactly that unless it is hardened feature by feature.
 *  - **One parser.** `chapterHtml` has to go through jsoup anyway, so metadata and navigation take
 *    the same path instead of a second, differently-behaved one.
 */
internal object EpubXml {

    /**
     * Parses XML, or returns `null` when the bytes are not usable at all.
     *
     * The XML parser is used where the document is machine-written and structure matters — the
     * container, the OPF, the NCX — and it preserves case and namespace prefixes, so `dc:title` and
     * `navPoint` arrive spelled as the specs spell them.
     */
    fun parseXml(bytes: ByteArray): Document? =
        runCatching { Jsoup.parse(decode(bytes), "", Parser.xmlParser()) }.getOrNull()

    /**
     * Parses XHTML as HTML.
     *
     * The HTML parser is used for the nav document and for chapters: it is the tolerant one, it
     * resolves HTML entities that XHTML documents use without declaring, and it repairs the
     * unclosed and mis-nested tags that EPUB producers emit.
     */
    fun parseHtml(bytes: ByteArray): Document = Jsoup.parse(decode(bytes), "")

    /**
     * Decodes [bytes] using, in order: a byte-order mark, a UTF-16 pattern without one, the encoding
     * named in the XML declaration, then UTF-8 — the container format's default.
     *
     * Doing this by hand rather than handing raw bytes to jsoup matters for non-Latin books: jsoup
     * sniffs BOMs and HTML `<meta charset>`, but not `<?xml version="1.0" encoding="windows-1256"?>`,
     * which is how some older Arabic EPUBs are encoded.
     */
    fun decode(bytes: ByteArray): String {
        bomCharset(bytes)?.let { return String(bytes, it).removePrefix("﻿") }
        return String(bytes, charsetFromDeclaration(bytes) ?: Charsets.UTF_8)
    }

    private fun bomCharset(bytes: ByteArray): Charset? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Charsets.UTF_8
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        else -> null
    }

    private fun charsetFromDeclaration(bytes: ByteArray): Charset? {
        if (bytes.size >= 2) {
            // UTF-16 with no BOM: `<?xml` appears as alternating NUL bytes.
            if (bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte()) return Charsets.UTF_16LE
            if (bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte()) return Charsets.UTF_16BE
        }
        // The declaration is ASCII by specification, so reading a prefix as Latin-1 is always safe.
        val head = String(bytes, 0, minOf(bytes.size, DECLARATION_SCAN_BYTES), Charsets.ISO_8859_1)
        if (!head.startsWith("<?xml")) return null
        val match = ENCODING_PATTERN.find(head.substringBefore("?>")) ?: return null
        return runCatching { Charset.forName(match.groupValues[1].trim()) }.getOrNull()
    }

    private const val DECLARATION_SCAN_BYTES = 256
    private val ENCODING_PATTERN = Regex("""encoding\s*=\s*["']([^"']+)["']""")
}

/**
 * The element's name without its namespace prefix, so `dc:title` and `title` are both `title`.
 *
 * Matching on the local name rather than on the namespace URI is deliberate: books in the wild bind
 * the Dublin Core namespace to other prefixes, and a few omit the declaration entirely, which a
 * namespace-aware parser rejects outright.
 */
internal fun Element.localName(): String = tagName().substringAfterLast(':')

/** The element's direct children with the given local name, in document order. */
internal fun Element.childrenNamed(name: String): List<Element> =
    childNodes().filterIsInstance<Element>().filter { it.localName().equals(name, ignoreCase = true) }

/** The first direct child with the given local name. */
internal fun Element.firstChildNamed(name: String): Element? = childrenNamed(name).firstOrNull()

/** Every descendant with the given local name, in document order, excluding this element. */
internal fun Element.descendantsNamed(name: String): List<Element> =
    getAllElements().filter { it !== this && it.localName().equals(name, ignoreCase = true) }

/**
 * The value of an attribute, matched case-insensitively when an exact match is missing.
 *
 * XML attribute names are case-sensitive, but attribute *names* are also the one thing producers
 * mistype most often (`full-path`, `media-type`, `linear`), and a book that misspells one by case is
 * still perfectly readable.
 */
internal fun Element.attrNamed(name: String): String? {
    val attributes = attributes()
    attributes.firstOrNull { it.key == name }?.let { return it.value }
    return attributes.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** The element's text, trimmed, or `null` when it has none. */
internal fun Element.textOrNull(): String? = text().trim().ifEmpty { null }
