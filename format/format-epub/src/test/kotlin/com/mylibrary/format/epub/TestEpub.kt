package com.mylibrary.format.epub

import com.mylibrary.core.domain.engine.DocumentSource
import com.mylibrary.core.domain.model.BookFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A [DocumentSource] over bytes held in memory, which is what a `content://` URI ends up as. */
internal class FakeDocumentSource(
    private val bytes: ByteArray,
    private val failOnOpen: Boolean = false,
) : DocumentSource {

    /** Set by a test that needs the reader to observe a close. */
    var openCount: Int = 0
        private set

    override val id: String = "test:${bytes.size}"
    override val displayName: String = "book.epub"
    override val mimeType: String = "application/epub+zip"
    override val sizeBytes: Long = bytes.size.toLong()
    override val format: BookFormat = BookFormat.EPUB

    override fun openStream(): InputStream {
        openCount++
        if (failOnOpen) throw IOException("the URI grant was revoked")
        return ByteArrayInputStream(bytes)
    }

    override fun openChannel(): SeekableByteChannel =
        throw UnsupportedOperationException("EPUB is read sequentially; there is no random access")
}

/** Writes a zip in memory, the way an EPUB is laid out. */
internal fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

internal fun String.asBytes(): ByteArray = toByteArray(Charsets.UTF_8)

/**
 * Builds the test book.
 *
 * The fixture is deliberately awkward in the places real books are awkward: a percent-encoded href,
 * a manifest href that climbs out of the archive, a spine item that is a stylesheet, a nav entry
 * that resolves to nothing, and a nav entry with no target of its own at all.
 */
internal class EpubBuilder private constructor(
    private val entries: LinkedHashMap<String, ByteArray>,
) {

    fun file(path: String, content: String): EpubBuilder = file(path, content.asBytes())

    fun file(path: String, content: ByteArray): EpubBuilder = apply { entries[path] = content }

    fun without(path: String): EpubBuilder = apply { entries.remove(path) }

    fun build(): ByteArray = zipOf(entries.map { it.key to it.value })

    companion object {
        /** The standard fixture: EPUB 3 with a nav document *and* an NCX beside it. */
        fun standard(): EpubBuilder = EpubBuilder(
            linkedMapOf(
                "mimetype" to MIMETYPE.asBytes(),
                "META-INF/container.xml" to CONTAINER_XML.asBytes(),
                "OEBPS/content.opf" to OPF_XML.asBytes(),
                "OEBPS/nav.xhtml" to NAV_XHTML.asBytes(),
                "OEBPS/toc.ncx" to NCX_XML.asBytes(),
                "OEBPS/Text/cover.xhtml" to COVER_XHTML.asBytes(),
                // Stored with the space the href writes as %20, which is what the container format
                // requires: zip names are the decoded form, hrefs the encoded one.
                "OEBPS/Text/chapter one.xhtml" to CHAPTER_ONE_XHTML.asBytes(),
                "OEBPS/Text/chapter-two.xhtml" to CHAPTER_TWO_XHTML.asBytes(),
                "OEBPS/Images/plate.png" to PLATE_PNG,
                "OEBPS/Styles/book.css" to "p { color: red }".asBytes(),
            ),
        )
    }
}

internal const val MIMETYPE = "application/epub+zip"

internal const val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

/**
 * The package document.
 *
 * `id="notes"` climbs past the archive root, `id="styles"` is a stylesheet that the
 * spine nevertheless lists, and `id="ch1"` is percent-encoded.
 */
internal const val OPF_XML = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>The Shifting Sands</dc:title>
    <dc:creator>Ammar K.</dc:creator>
    <dc:language>ar</dc:language>
    <dc:identifier id="pub-id">urn:uuid:6f4a1b90</dc:identifier>
    <dc:publisher>MyLibrary Press</dc:publisher>
    <dc:description>A book about sand.</dc:description>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="cover" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch1" href="Text/chapter%20one.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="Text/chapter-two.xhtml" media-type="application/xhtml+xml"/>
    <item id="notes" href="../../Notes/notes.xhtml" media-type="application/xhtml+xml"/>
    <item id="plate" href="Images/plate.png" media-type="image/png"/>
    <item id="styles" href="Styles/book.css" media-type="text/css"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="cover" linear="no"/>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
    <itemref idref="notes"/>
    <itemref idref="styles"/>
  </spine>
</package>
"""

/** The same book as [OPF_XML], with the nav document no longer marked as navigation. */
internal val OPF_XML_WITHOUT_NAV: String = OPF_XML.replace(" properties=\"nav\"", "")

internal const val NAV_XHTML = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Contents</title></head>
<body>
  <nav epub:type="landmarks"><ol><li><a href="Text/cover.xhtml">Cover</a></li></ol></nav>
  <nav epub:type="toc" id="toc">
    <ol>
      <li><a href="Text/cover.xhtml">Cover</a></li>
      <li><a href="Text/chapter%20one.xhtml">Chapter One</a>
        <ol><li><a href="Text/chapter%20one.xhtml#opening">The Opening</a></li></ol>
      </li>
      <li><a href="Text/nowhere.xhtml">Missing File</a>
        <ol><li><a href="Text/chapter-two.xhtml">Chapter Two</a>
          <ol><li><a href="Text/chapter-two.xhtml#end">The End</a></li></ol>
        </li></ol>
      </li>
      <li><span>A Heading With No Target</span>
        <ol><li><a href="Text/cover.xhtml#top">Cover Again</a></li></ol>
      </li>
    </ol>
  </nav>
</body>
</html>
"""

/** Deliberately shaped differently from the nav document, so a test can tell which one was read. */
internal const val NCX_XML = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="urn:uuid:6f4a1b90"/></head>
  <docTitle><text>The Shifting Sands</text></docTitle>
  <navMap>
    <navPoint id="n1" playOrder="2">
      <navLabel><text>NCX Only</text></navLabel>
      <content src="Text/chapter-two.xhtml"/>
    </navPoint>
    <navPoint id="n2" playOrder="1">
      <navLabel><text>NCX Second</text></navLabel>
      <content src="Text/chapter%20one.xhtml"/>
      <navPoint id="n2a">
        <navLabel><text>NCX Nested</text></navLabel>
        <content src="Text/cover.xhtml"/>
      </navPoint>
    </navPoint>
  </navMap>
</ncx>
"""

internal const val COVER_XHTML = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Cover</title></head>
<body id="top">
  <h1>Cover</h1>
  <p>A cover page, which the spine marks as non-linear.</p>
  <img src="../Images/plate.png" alt="The plate"/>
</body>
</html>
"""

/**
 * The chapter that exercises the sanitiser: scripts, inline styling, presentational attributes,
 * event handlers, an unknown wrapper, a comment, and document-level right-to-left direction.
 */
internal const val CHAPTER_ONE_XHTML = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ar" dir="rtl">
<head>
  <title>Chapter One</title>
  <link rel="stylesheet" href="../Styles/book.css"/>
  <style>p { color: red }</style>
</head>
<body class="chapter" style="font-family: serif">
  <h1 id="opening">الفصل الأول</h1>
  <p style="color:#333; font-size:9pt" dir="rtl" lang="ar" onclick="evil()">مرحبا <em>بك</em> في الكتاب</p>
  <script>alert('xss')</script>
  <p>The quick brown fox jumps over the lazy dog.</p>
  <p>Kafkaesque marmalade appears exactly once in this book.</p>
  <img src="../Images/plate.png" alt="A plate" onerror="evil()" width="600" style="border:0"/>
  <img src="../../../outside/secret.png" alt="Outside"/>
  <ul><li>first item</li><li>second item</li></ul>
  <table><tr><td colspan="2">a cell</td></tr></table>
  <div class="wrapper"><p>Wrapped text survives.</p></div>
  <iframe src="https://example.com/"></iframe>
  <p>A<!-- a comment from the producer's toolchain --> <b>bold</b> and <font color="red">font</font> text.</p>
  <blockquote dir="ltr">Quoted in English</blockquote>
</body>
</html>
"""

internal const val CHAPTER_TWO_XHTML = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Chapter Two</title></head>
<body>
  <h1 id="end">Chapter Two</h1>
  <p>The second chapter mentions sand and the sea.</p>
</body>
</html>
"""

/** A real 1x1 PNG, so that "returns image bytes" means bytes an image decoder could actually read. */
internal val PLATE_PNG: ByteArray = Base64.getDecoder().decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
)
