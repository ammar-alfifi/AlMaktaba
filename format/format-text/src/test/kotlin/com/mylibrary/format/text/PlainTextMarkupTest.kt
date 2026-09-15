package com.mylibrary.format.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapter markup: one shape, whichever format it came from, so the reader has one rendering path.
 */
class PlainTextMarkupTest {

    @Test
    fun `wraps paragraphs and turns single line breaks into breaks`() {
        val document = open("first line\nsecond line\n\nsecond paragraph")

        assertEquals(
            "<div dir=\"ltr\"><p>first line<br/>second line</p><p>second paragraph</p></div>",
            htmlOf(document, 0),
        )
    }

    @Test
    fun `escapes the characters that would otherwise be markup`() {
        val document = open("المؤلف <b>أحمد</b> & الناشر")

        val html = htmlOf(document, 0)

        assertFalse("raw markup leaked into the output: $html", html.contains("<b>"))
        assertFalse("an unescaped ampersand leaked into the output: $html", html.contains(" & "))
        assertTrue(html.contains("&lt;b&gt;أحمد&lt;/b&gt;"))
        assertTrue(html.contains("&amp;"))
    }

    @Test
    fun `an arabic chapter is marked right to left`() {
        val html = htmlOf(open(ARABIC_SAMPLE), 0)

        // The direction is what makes an Arabic book read correctly inside MyLibrary's English UI.
        assertTrue("expected an RTL root element, got $html", html.startsWith("<div dir=\"rtl\">"))
    }

    @Test
    fun `an english chapter is marked left to right`() {
        val html = htmlOf(open(ENGLISH_SAMPLE), 0)

        // And this is what makes an English book read correctly inside MyLibrary's Arabic interface.
        assertTrue("expected an LTR root element, got $html", html.startsWith("<div dir=\"ltr\">"))
    }

    @Test
    fun `a line of spaces still ends a paragraph`() {
        val document = open("first\n   \nsecond")

        assertEquals("<div dir=\"ltr\"><p>first</p><p>second</p></div>", htmlOf(document, 0))
    }

    @Test
    fun `each chapter renders only its own text`() {
        val document = open(ARABIC_SAMPLE + "" + ENGLISH_SAMPLE)

        val arabicHtml = htmlOf(document, 0)
        val englishHtml = htmlOf(document, 1)

        assertTrue(arabicHtml.startsWith("<div dir=\"rtl\">"))
        assertTrue(englishHtml.startsWith("<div dir=\"ltr\">"))
        assertFalse(arabicHtml.contains("clocks were striking"))
        assertFalse(englishHtml.contains("كان يا ما كان"))
    }

    @Test
    fun `a chapter is a well formed fragment`() {
        val document = open("first line\nsecond line\n\nsecond paragraph & more <text>")

        val html = htmlOf(document, 0)

        assertTrue(html.startsWith("<div dir="))
        assertTrue(html.endsWith("</div>"))
        // Every angle bracket left in the output belongs to one of the tags this engine emits.
        val textOnly = html
            .replace("<div dir=\"ltr\">", "")
            .replace("</div>", "")
            .replace("<p>", "")
            .replace("</p>", "")
            .replace("<br/>", "")
        assertFalse("unexpected markup in: $html", textOnly.contains('<'))
    }
}
