package com.mylibrary.format.epub

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Path arithmetic on its own.
 *
 * This is the part of the decoder that a book-breaking bug hides in, so it is tested directly rather
 * than only through a book that happens to exercise it: percent-encoding, `..` in both directions,
 * fragments, and the encoded-traversal case that decoding *after* normalising would let through.
 */
class EpubPathsTest {

    @Test
    fun `resolves an href against the document's own directory`() {
        assertThat(EpubPaths.resolve("OEBPS", "Text/ch1.xhtml")).isEqualTo("OEBPS/Text/ch1.xhtml")
        assertThat(EpubPaths.resolve("OEBPS/Text", "../Images/plate.png")).isEqualTo("OEBPS/Images/plate.png")
        assertThat(EpubPaths.resolve("", "content.opf")).isEqualTo("content.opf")
        assertThat(EpubPaths.resolve("OEBPS", "/Images/plate.png")).isEqualTo("Images/plate.png")
        assertThat(EpubPaths.resolve("OEBPS", "./Text/./ch1.xhtml")).isEqualTo("OEBPS/Text/ch1.xhtml")
    }

    @Test
    fun `walks up with dot dot, but not out of the archive`() {
        // `..` inside the archive is ordinary path arithmetic: `OEBPS/../x` is the file `x` at the
        // archive root, which is a real place a resource can live.
        assertThat(EpubPaths.resolve("OEBPS", "../outside.xhtml")).isEqualTo("outside.xhtml")
        assertThat(EpubPaths.resolve("OEBPS/Text", "../../plate.png")).isEqualTo("plate.png")
        // Climbing past the root is not: there is nothing above the archive, so the href names
        // nothing and every caller has to say so rather than guess.
        assertThat(EpubPaths.resolve("OEBPS", "../../outside.xhtml")).isNull()
        assertThat(EpubPaths.resolve("OEBPS/Text", "../../../outside.xhtml")).isNull()
        assertThat(EpubPaths.resolve("", "../x")).isNull()
        assertThat(EpubPaths.normalise("../x")).isNull()
    }

    @Test
    fun `decodes percent-escapes before normalising, so encoded traversal is caught`() {
        // The order matters: decoding first turns %2E%2E%2F into a real `..` that the traversal check
        // then sees. Normalising first would pass it through as a harmless file name, and the
        // decoded `..` would only appear later, where nothing checks it.
        // Three levels of encoded `..` climb out of a two-level base, so the guard has to fire.
        assertThat(EpubPaths.resolve("OEBPS/Text", "%2E%2E%2F%2E%2E%2F%2E%2E%2Foutside.xhtml")).isNull()
        // Two levels do not: `OEBPS/Text/../../plate.png` is the file `plate.png` at the root.
        assertThat(EpubPaths.resolve("OEBPS/Text", "%2E%2E%2F%2E%2E%2Fplate.png")).isEqualTo("plate.png")
    }

    @Test
    fun `decodes percent-escapes as utf 8`() {
        assertThat(EpubPaths.resolve("OEBPS", "Text/chapter%20one.xhtml"))
            .isEqualTo("OEBPS/Text/chapter one.xhtml")
        assertThat(EpubPaths.resolve("OEBPS", "Text/%D9%83%D8%AA%D8%A7%D8%A8.xhtml"))
            .isEqualTo("OEBPS/Text/كتاب.xhtml")
        // A plus is a plus: it means a space only in query strings, and these are paths.
        assertThat(EpubPaths.decodePercent("a+b%20c")).isEqualTo("a+b c")
        // Malformed escapes are left as written rather than dropped.
        assertThat(EpubPaths.decodePercent("100%25%zz%d")).isEqualTo("100%%zz%d")
    }

    @Test
    fun `separates a fragment from its path`() {
        assertThat(EpubPaths.pathOf("Text/ch1.xhtml#opening")).isEqualTo("Text/ch1.xhtml")
        assertThat(EpubPaths.fragmentOf("Text/ch1.xhtml#opening")).isEqualTo("opening")
        assertThat(EpubPaths.fragmentOf("Text/ch1.xhtml")).isNull()
        assertThat(EpubPaths.fragmentOf("Text/ch1.xhtml#")).isNull()
        assertThat(EpubPaths.resolve("OEBPS", "Text/ch1.xhtml#opening")).isEqualTo("OEBPS/Text/ch1.xhtml")
    }

    @Test
    fun `expresses a resource path relative to the package document`() {
        assertThat(EpubPaths.resourcePathFor("OEBPS/Images/plate.png", "OEBPS")).isEqualTo("Images/plate.png")
        assertThat(EpubPaths.resourcePathFor("OEBPS/plate.png", "OEBPS")).isEqualTo("plate.png")
        assertThat(EpubPaths.resourcePathFor("Images/plate.png", "")).isEqualTo("Images/plate.png")
        // Outside the package directory there is no honest relative spelling, so it is rooted
        // instead of being written with `..`, which would look like a traversal attempt.
        assertThat(EpubPaths.resourcePathFor("Images/plate.png", "OEBPS")).isEqualTo("/Images/plate.png")
    }

    @Test
    fun `finds the directory of a path`() {
        assertThat(EpubPaths.directoryOf("OEBPS/Text/ch1.xhtml")).isEqualTo("OEBPS/Text")
        assertThat(EpubPaths.directoryOf("content.opf")).isEmpty()
    }
}
