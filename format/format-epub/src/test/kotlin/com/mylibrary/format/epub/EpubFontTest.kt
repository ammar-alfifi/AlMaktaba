package com.mylibrary.format.epub

import com.google.common.truth.Truth.assertThat
import com.mylibrary.core.common.getOrNull
import com.mylibrary.core.domain.engine.ReflowableDocument
import com.mylibrary.core.domain.model.EmbeddedFont
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Embedded fonts: the faces a book carries inside itself, and the family its body was typeset in.
 *
 * A file of its own rather than another section of [EpubEngineTest], because this is the one part of
 * the engine that reads CSS, and because the failures are quiet ones: a `url()` resolved against the
 * wrong directory finds nothing, a `local(...)` taken as a source hands the reader a font from its
 * own device, and a book whose `@font-face` is missed simply looks like a book that has none. None
 * of those is an error the reader can see; every one of them is a book that does not look like
 * itself.
 *
 * The fixture is the standard book with its stylesheet — which sits in `OEBPS/Styles`, a directory of
 * its own — replaced by the CSS under test, so a font placed "beside the stylesheet" and a font
 * placed "beside the package document" are two different places, as they are in a real archive.
 */
class EpubFontTest {

    private fun open(builder: EpubBuilder): ReflowableDocument =
        runBlocking { EpubEngine().open(FakeDocumentSource(builder.build())) }
            .getOrNull() as ReflowableDocument

    /** The standard book, with [css] as its only stylesheet and [files] added to the archive. */
    private fun bookStyledBy(css: String, vararg files: Pair<String, ByteArray>): ReflowableDocument {
        var builder = EpubBuilder.standard().file(STYLESHEET_PATH, css)
        for ((path, bytes) in files) builder = builder.file(path, bytes)
        return open(builder)
    }

    /** Font bytes placed beside the stylesheet, which is where a relative `url()` in it points. */
    private fun font(name: String): Pair<String, ByteArray> =
        "${EpubPaths.directoryOf(STYLESHEET_PATH)}/$name" to FONT_BYTES

    private fun ReflowableDocument.fonts(): List<EmbeddedFont> = runBlocking { embeddedFonts() }

    private fun ReflowableDocument.bodyFamily(): String? = runBlocking { defaultFontFamily() }

    private fun ReflowableDocument.resourceOf(path: String): ByteArray? = runBlocking { resource(path) }

    private fun List<EmbeddedFont>.face(family: String): EmbeddedFont =
        firstOrNull { it.family == family } ?: error("no face for $family in ${map { it.family }}")

    // --- `@font-face` -------------------------------------------------------------------------

    @Test
    fun `a font face becomes a face the reader can load`() {
        val document = bookStyledBy(
            """
            @font-face {
              font-family: "Amiri Naskh";
              font-weight: bold;
              font-style: italic;
              src: url("Amiri-BoldItalic.otf") format("opentype");
            }
            """.trimIndent(),
            font("Amiri-BoldItalic.otf"),
        )

        val face = document.fonts().single()
        assertThat(face.family).isEqualTo("Amiri Naskh")
        assertThat(face.weight).isEqualTo(700)
        assertThat(face.italic).isTrue()
        // Relative to the stylesheet that declared it, and spelled the way `resource` takes a path:
        // the reader loads a face with the same call it loads an image with.
        assertThat(face.path).isEqualTo("Styles/Amiri-BoldItalic.otf")
        assertThat(document.resourceOf(face.path)).isEqualTo(FONT_BYTES)
    }

    @Test
    fun `a family is read however it is quoted`() {
        val document = bookStyledBy(
            """
            @font-face { font-family: "Double Quoted"; src: url("double.otf") }
            @font-face { font-family: 'Single Quoted'; src: url("single.otf") }
            @font-face { font-family: Bare; src: url("bare.otf") }
            """.trimIndent(),
            font("double.otf"),
            font("single.otf"),
            font("bare.otf"),
        )

        // CSS allows all three spellings and publishers use all three. The quotes are syntax rather
        // than part of the name: a reader matching a `font-family` of `"Amiri"` against a face named
        // `Amiri` has to see one string, not two. The order is the document's, which is the order
        // the reader gets to list them in.
        assertThat(document.fonts().map { it.family })
            .containsExactly("Double Quoted", "Single Quoted", "Bare")
            .inOrder()
    }

    @Test
    fun `font weight is read as CSS weight`() {
        val document = bookStyledBy(
            """
            @font-face { font-family: "Normal"; src: url("normal.otf"); font-weight: normal }
            @font-face { font-family: "Bold"; src: url("bold.otf"); font-weight: bold }
            @font-face { font-family: "Light"; src: url("light.otf"); font-weight: 300 }
            @font-face { font-family: "Range"; src: url("range.otf"); font-weight: 400 700 }
            @font-face { font-family: "Nonsense"; src: url("nonsense.otf"); font-weight: banana }
            @font-face { font-family: "Heavy"; src: url("heavy.otf"); font-weight: 1200 }
            @font-face { font-family: "Feather"; src: url("feather.otf"); font-weight: 50 }
            """.trimIndent(),
            font("normal.otf"),
            font("bold.otf"),
            font("light.otf"),
            font("range.otf"),
            font("nonsense.otf"),
            font("heavy.otf"),
            font("feather.otf"),
        )

        val faces = document.fonts()
        assertThat(faces.face("Normal").weight).isEqualTo(400)
        assertThat(faces.face("Bold").weight).isEqualTo(700)
        assertThat(faces.face("Light").weight).isEqualTo(300)
        // A variable font declares a range. The reader wants one number per face, and the range's
        // first value is the weight the face is normally used at.
        assertThat(faces.face("Range").weight).isEqualTo(400)
        // Nothing usable: 400 is the only defensible answer, because it is what a rule that declares
        // no weight at all means — the family's regular face.
        assertThat(faces.face("Nonsense").weight).isEqualTo(400)
        // Out of range clamps rather than being discarded: `1000` is a producer writing bold badly,
        // and dropping the face would substitute a font over a typo.
        assertThat(faces.face("Heavy").weight).isEqualTo(900)
        assertThat(faces.face("Feather").weight).isEqualTo(100)
    }

    @Test
    fun `a face is slanted when the document says it is`() {
        val document = bookStyledBy(
            """
            @font-face { font-family: "Italic"; src: url("italic.otf"); font-style: italic }
            @font-face { font-family: "Oblique"; src: url("oblique.otf"); font-style: oblique }
            @font-face { font-family: "Upright"; src: url("upright.otf"); font-style: normal }
            @font-face { font-family: "Undeclared"; src: url("undeclared.otf") }
            """.trimIndent(),
            font("italic.otf"),
            font("oblique.otf"),
            font("upright.otf"),
            font("undeclared.otf"),
        )

        val faces = document.fonts()
        assertThat(faces.face("Italic").italic).isTrue()
        // A reader picks one slanted face per family; `oblique` is that face spelled the other way.
        assertThat(faces.face("Oblique").italic).isTrue()
        assertThat(faces.face("Upright").italic).isFalse()
        assertThat(faces.face("Undeclared").italic).isFalse()
    }

    @Test
    fun `two weights of one family are two faces`() {
        val document = bookStyledBy(
            """
            @font-face { font-family: "Amiri"; src: url("Amiri-Regular.otf"); font-weight: normal }
            @font-face { font-family: "Amiri"; src: url("Amiri-Bold.otf"); font-weight: bold }
            """.trimIndent(),
            font("Amiri-Regular.otf"),
            font("Amiri-Bold.otf"),
        )

        // The shape almost every Arabic book ships: one family, a face per weight. Keying a face by
        // its family alone would leave the reader with the regular one and a bold it has to fake.
        assertThat(document.fonts().map { it.family }).containsExactly("Amiri", "Amiri")
        assertThat(document.fonts().map { it.path })
            .containsExactly("Styles/Amiri-Regular.otf", "Styles/Amiri-Bold.otf")
            .inOrder()
    }

    @Test
    fun `a url is read whatever case or quoting it is written in`() {
        val document = bookStyledBy(
            """
            @font-face { font-family: "Bare"; src: url(bare.otf) }
            @font-face { font-family: "Shouted"; src: URL("shouted.otf") format("opentype") }
            """.trimIndent(),
            font("bare.otf"),
            font("shouted.otf"),
        )

        // CSS writes `url()` as a function, which is case-insensitive, and allows the argument
        // unquoted. Both spellings are in circulation, and neither changes what the rule means.
        assertThat(document.fonts().map { it.family }).containsExactly("Bare", "Shouted").inOrder()
        assertThat(document.fonts().map { it.path })
            .containsExactly("Styles/bare.otf", "Styles/shouted.otf")
            .inOrder()
    }

    @Test
    fun `a local source is passed over for the file the book carries`() {
        val document = bookStyledBy(
            """
            @font-face {
              font-family: "Amiri";
              src: local("Amiri"), local("Amiri Regular"), url("amiri.otf") format("opentype");
            }
            @font-face { font-family: "Installed Only"; src: local("Helvetica Neue") }
            """.trimIndent(),
            font("amiri.otf"),
        )

        // `local(...)` names a font installed on the *reader's* device, which is precisely the
        // substitution this feature exists to avoid. The file the book carries wins, and a face with
        // no file at all is not a face this book can offer.
        assertThat(document.fonts().map { it.family }).containsExactly("Amiri")
        assertThat(document.fonts().single().path).isEqualTo("Styles/amiri.otf")
    }

    @Test
    fun `a font url is resolved against the stylesheet that wrote it`() {
        val document = bookStyledBy(
            """
            @font-face { font-family: "Beside"; src: url("beside.otf") }
            @font-face { font-family: "Below"; src: url("fonts/below.otf") }
            @font-face { font-family: "Above"; src: url("../Fonts/above.otf") }
            """.trimIndent(),
            font("beside.otf"),
            "OEBPS/Styles/fonts/below.otf" to FONT_BYTES,
            "OEBPS/Fonts/above.otf" to FONT_BYTES,
        )

        // The stylesheet is at `OEBPS/Styles/book.css` and the package document at `OEBPS/content.opf`.
        // Every url here resolves against the former: beside it, below it, and a step up into the
        // package document's own directory — for none of which the OPF's directory is the base.
        val paths = document.fonts().associate { it.family to it.path }
        assertThat(paths["Beside"]).isEqualTo("Styles/beside.otf")
        assertThat(paths["Below"]).isEqualTo("Styles/fonts/below.otf")
        assertThat(paths["Above"]).isEqualTo("Fonts/above.otf")
        // And every one of those paths is a path `resource()` answers with the bytes it names: the
        // spelling the reader is handed and the spelling it hands back have to be the same one.
        for (path in paths.values) assertThat(document.resourceOf(path)).isEqualTo(FONT_BYTES)
    }

    @Test
    fun `a font the archive does not contain is skipped`() {
        val document = bookStyledBy(
            """
            @font-face { font-family: "Present"; src: url("present.otf") }
            @font-face { font-family: "Promised"; src: url("promised.otf") }
            """.trimIndent(),
            font("present.otf"),
        )

        // A stylesheet can name a file the zip never shipped. The book is still readable, so one
        // unusable rule is left out and the rest of the sheet is kept — reporting it would be noise,
        // and failing the book over it would be worse.
        assertThat(document.fonts().map { it.family }).containsExactly("Present")
    }

    @Test
    fun `a font url that leaves the archive is skipped`() {
        val document = bookStyledBy(
            """
            @font-face { font-family: "Escaping"; src: url("../../../outside.otf") }
            @font-face { font-family: "Encoded"; src: url("%2E%2E%2F%2E%2E%2F%2E%2E%2Foutside.otf") }
            @font-face { font-family: "Inside"; src: url("inside.otf") }
            """.trimIndent(),
            font("inside.otf"),
        )

        // Three levels up from `OEBPS/Styles` is past the archive root, where there is nothing to
        // read. The percent-encoded spelling has to fail the same way: a guard that decodes after
        // normalising would let `%2E%2E%2F` through as an ordinary segment.
        assertThat(document.fonts().map { it.family }).containsExactly("Inside")
    }

    // --- `@import` ----------------------------------------------------------------------------

    @Test
    fun `an imported stylesheet is read one level deep`() {
        val document = bookStyledBy(
            """
            @import url("fonts.css");
            body { font-family: "Body Face" }
            """.trimIndent(),
            "OEBPS/Styles/fonts.css" to """
                @import url("deeper.css");
                @font-face { font-family: "Imported Face"; src: url("imported.otf") }
            """.trimIndent().asBytes(),
            "OEBPS/Styles/deeper.css" to """
                @font-face { font-family: "Deeper Face"; src: url("deeper.otf") }
            """.trimIndent().asBytes(),
            font("imported.otf"),
            font("deeper.otf"),
        )

        // One level, exactly: the sheet the book imports is read, and the sheet that one imports is
        // not. Books split their fonts into an imported sheet and stop there; a reader that followed
        // the chain to its end would read as many files as a hostile book cares to name.
        assertThat(document.fonts().map { it.family }).containsExactly("Imported Face")
        assertThat(document.bodyFamily()).isEqualTo("Body Face")
    }

    @Test
    fun `a stylesheet that imports itself terminates`() {
        val document = bookStyledBy(
            """
            @import url("book.css");
            @import url("fonts.css");
            @import url("fonts.css");
            body { font-family: "Self" }
            """.trimIndent(),
            "OEBPS/Styles/fonts.css" to """
                @import url("book.css");
                @font-face { font-family: "Mutual"; src: url("mutual.otf") }
            """.trimIndent().asBytes(),
            font("mutual.otf"),
        )

        // A sheet that imports itself, and two sheets that import each other. Each is read once
        // however many ways it is named, so the book opens: a self-import is discovered by looking in
        // the set of sheets already read, not by running out of stack.
        assertThat(document.fonts().map { it.family }).containsExactly("Mutual")
        assertThat(document.bodyFamily()).isEqualTo("Self")
    }

    @Test
    fun `an import is read even when its semicolon is missing`() {
        val document = bookStyledBy(
            """@import url("fonts.css")""",
            "OEBPS/Styles/fonts.css" to """
                @font-face { font-family: "Sloppy"; src: url("sloppy.otf") }
            """.trimIndent().asBytes(),
            font("sloppy.otf"),
        )

        // A sheet whose last statement has no `;` is malformed rather than wrong, and books are
        // written by tools that are not always careful. Reading it costs one look at the tail.
        assertThat(document.fonts().map { it.family }).containsExactly("Sloppy")
    }

    // --- The body family ----------------------------------------------------------------------

    @Test
    fun `the body family is the first of the list, without its quotes`() {
        val document = bookStyledBy("""body { font-family: "My Naskh", serif; }""")

        assertThat(document.bodyFamily()).isEqualTo("My Naskh")
    }

    @Test
    fun `a generic family is reported as written`() {
        val document = bookStyledBy("body { font-family: serif, sans-serif }")

        // The decoder reports what the book asks for and stops there: only the reader knows what it
        // can render, and a book that asks for `serif` is still stating a preference worth honouring.
        assertThat(document.bodyFamily()).isEqualTo("serif")
    }

    @Test
    fun `an html rule is the fallback for a body rule that declares no family`() {
        val document = bookStyledBy(
            """
            html { font-family: 'Kufi' }
            body { color: #333; margin: 0 }
            """.trimIndent(),
        )

        // The body rule is the more specific statement, so it is looked at first — and it is silent
        // about type, which is not the same as saying the body has no family.
        assertThat(document.bodyFamily()).isEqualTo("Kufi")
    }

    @Test
    fun `the body rule outranks the html rule in either order`() {
        val bodyFirst = bookStyledBy(
            """
            body { font-family: "Body" }
            html { font-family: "Html" }
            """.trimIndent(),
        )
        val bodyLast = bookStyledBy(
            """
            html { font-family: "Html" }
            body { font-family: "Body" }
            """.trimIndent(),
        )

        assertThat(bodyFirst.bodyFamily()).isEqualTo("Body")
        assertThat(bodyLast.bodyFamily()).isEqualTo("Body")
    }

    @Test
    fun `a rule for the body's descendants is not the body's own family`() {
        val document = bookStyledBy(
            """
            body p { font-family: "Not The Body" }
            html body { font-family: "The Body" !important }
            """.trimIndent(),
        )

        // `body p` styles paragraphs, and reading its family as the body's would make a book's body
        // font depend on how its paragraphs are decorated. `html body` writes the selector the long
        // way and does reach the body, and `!important` is syntax rather than part of the name.
        assertThat(document.bodyFamily()).isEqualTo("The Body")
    }

    // --- Where the CSS can come from, and what is kept ----------------------------------------

    @Test
    fun `css inlined in the package document is read too`() {
        val document = open(
            EpubBuilder.standard()
                .file("OEBPS/content.opf", OPF_XML_WITH_INLINE_STYLE)
                .file("OEBPS/Fonts/inline.otf", FONT_BYTES),
        )

        // No manifest item declares this face, so a reader that only read `text/css` items would
        // substitute its own font for the book's.
        val face = document.fonts().single()
        assertThat(face.family).isEqualTo("Inline Face")
        // Inline CSS is relative to the package document it sits in, which is the OPF's directory —
        // not to a stylesheet, because there is none.
        assertThat(face.path).isEqualTo("Fonts/inline.otf")
    }

    @Test
    fun `a publisher's stylesheet is read for the two facts and no more`() {
        // The shape this feature exists for: an Arabic book, its face imported from a sheet of its
        // own, and everything else in the file — a charset statement, a comment, a print rule, a
        // selector list, a pseudo-element with a `content` string — being none of this reader's
        // business. If any of it were read as styling, the book would be typeset by its CSS instead
        // of by the reader.
        val document = bookStyledBy(
            """
            @charset "utf-8";
            /* Amiri, under the OFL. */
            @import url("fonts/amiri.css");
            @media print { body { font-family: serif } }
            html, body { direction: rtl }
            body { font-family: "Amiri", "Times New Roman", serif; line-height: 1.6 }
            p { text-indent: 1.5em; margin: 0 }
            h1::before { content: "❖ " }
            """.trimIndent(),
            "OEBPS/Styles/fonts/amiri.css" to """
                @font-face { font-family: "Amiri"; src: url("amiri-regular.otf") format("opentype") }
                @font-face { font-family: "Amiri"; src: url("amiri-bold.otf"); font-weight: bold }
            """.trimIndent().asBytes(),
            "OEBPS/Styles/fonts/amiri-regular.otf" to FONT_BYTES,
            "OEBPS/Styles/fonts/amiri-bold.otf" to FONT_BYTES,
        )

        // The `@media print` rule is skipped rather than evaluated: it is the first rule in the file
        // that says anything about the body's font, and honouring it would set the reading font to
        // `serif` — the one moment a `serif` body is right is the moment this reader is not.
        assertThat(document.bodyFamily()).isEqualTo("Amiri")
        // The imported sheet's fonts are resolved against the imported sheet's own directory.
        assertThat(document.fonts().map { it.path })
            .containsExactly("Styles/fonts/amiri-regular.otf", "Styles/fonts/amiri-bold.otf")
            .inOrder()
    }

    @Test
    fun `a book with no fonts or css declares no type of its own`() {
        // The stock fixture: real CSS, and nothing in it about type. This is what most books look
        // like, and it must cost nothing — no fonts, no family, and no change to how the book reads.
        val document = open(EpubBuilder.standard())

        assertThat(document.fonts()).isEmpty()
        assertThat(document.bodyFamily()).isNull()
        assertThat(document.chapterCount).isEqualTo(3)
        assertThat(runBlocking { document.chapterText(1) }).contains("The quick brown fox")
    }

    @Test
    fun `a book whose stylesheet is missing is still readable`() {
        val document = open(EpubBuilder.standard().without(STYLESHEET_PATH))

        // The manifest promises a stylesheet the zip does not contain, which is a broken book rather
        // than a broken reader: it keeps its text, and only loses its typography.
        assertThat(document.fonts()).isEmpty()
        assertThat(document.bodyFamily()).isNull()
        assertThat(runBlocking { document.chapterText(1) }).contains("The quick brown fox")
    }

    @Test
    fun `the stylesheets are read once per document`() {
        val source = FakeDocumentSource(
            EpubBuilder.standard()
                .file(STYLESHEET_PATH, """body { font-family: "My Naskh" }""")
                .build(),
        )
        val document = runBlocking { EpubEngine().open(source) }.getOrNull() as ReflowableDocument

        // Typography is a fact about the document, not about the chapter being opened in front of
        // it: a reader that asked on every chapter would pay a pass over the archive each time for
        // an answer that cannot have changed while the book is open. The fake source counts the
        // passes, which is the only way to see the difference from outside.
        val beforeReading = source.openCount
        document.fonts()
        val afterReading = source.openCount
        document.bodyFamily()
        document.fonts()

        assertThat(afterReading).isGreaterThan(beforeReading)
        assertThat(source.openCount).isEqualTo(afterReading)
    }

    @Test
    fun `a closed document declares nothing`() {
        val document = bookStyledBy(
            """@font-face { font-family: "Gone"; src: url("gone.otf") } body { font-family: "Gone" }""",
            font("gone.otf"),
        )

        document.close()

        // Closing happens when the reader leaves the screen, and a question already in flight should
        // answer with nothing rather than with a failure.
        assertThat(document.fonts()).isEmpty()
        assertThat(document.bodyFamily()).isNull()
    }
}
