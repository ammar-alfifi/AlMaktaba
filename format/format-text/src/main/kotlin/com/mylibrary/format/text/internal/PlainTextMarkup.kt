package com.mylibrary.format.text.internal

/**
 * Wraps plain text in the same small, safe subset of HTML the EPUB engine produces.
 *
 * The reader has exactly one rendering path — it does not know whether the string it is handed came
 * out of an XHTML file or a `.txt` file — so this has to produce the same shape: a root element
 * carrying the reading direction, and a flat list of paragraphs under it. Nothing else. No headings
 * are invented, because a TXT file has none and a heading that the file did not contain would be the
 * engine writing the book.
 *
 * The output is a fragment, not a document: the reader inserts it into the page it is already
 * building, and a `<html>`/`<body>` wrapper from one chapter inside another chapter's page would be
 * invalid.
 */
internal object PlainTextMarkup {

    /**
     * Renders `text[from, to)` as paragraphs.
     *
     * [direction] is detected per chapter rather than per document: a bilingual anthology's Arabic
     * chapter and its English chapter are each laid out correctly, which one document-wide answer
     * could not do.
     */
    fun renderChapter(text: String, from: Int, to: Int, direction: TextDirection): String {
        val html = StringBuilder(to - from + ROOT_OVERHEAD_CHARS)
        html.append("<div dir=\"").append(direction.htmlAttribute).append("\">")
        appendParagraphs(text, from, to, html)
        html.append("</div>")
        return html.toString()
    }

    /**
     * Splits the range into paragraphs and escapes it as it goes.
     *
     * A blank line — one holding nothing but whitespace — ends a paragraph, and a single line break
     * inside one becomes `<br/>`. That pairing is what a plain-text book means by its own layout, and
     * it is why the text is never wrapped in a `<p>` that contains other `<p>`s or left as raw
     * newlines that HTML would silently collapse into a single space.
     *
     * A form feed ends a paragraph too, and for the same reason: it is the file's own page break, and
     * a reader whose pages are decided by pagination has no use for it — so all it can still say is
     * that the text either side of it is set apart. It is handled *here* rather than left to survive
     * inside a paragraph, because a control character written into the HTML is one the parser may
     * quietly turn into a space, and the text of that paragraph then no longer matches the chapter
     * text it is supposed to be found in — which is what a reading position, a search hit and a
     * highlight are all expressed in.
     *
     * Escaping and rendering happen in the same pass: `&`, `<` and `>` are the only characters that
     * can change meaning inside a paragraph, and writing each one straight into a fresh buffer means
     * there is no intermediate string for an ampersand to be escaped in twice.
     */
    private fun appendParagraphs(text: String, from: Int, to: Int, html: StringBuilder) {
        var lineStart = from
        var paragraphStart = -1
        var paragraphEnd = -1

        while (lineStart < to) {
            val breakIndex = nextBreak(text, lineStart, to)
            val lineEnd = if (breakIndex >= 0) breakIndex else to

            var blank = true
            for (index in lineStart until lineEnd) {
                if (!text[index].isWhitespace()) {
                    blank = false
                    break
                }
            }

            if (!blank) {
                if (paragraphStart < 0) paragraphStart = lineStart
                paragraphEnd = lineEnd
            }

            if (blank || (breakIndex >= 0 && text[breakIndex] == FORM_FEED)) {
                appendParagraph(text, paragraphStart, paragraphEnd, html)
                paragraphStart = -1
                paragraphEnd = -1
            }
            lineStart = lineEnd + 1
        }
        appendParagraph(text, paragraphStart, paragraphEnd, html)
    }

    /** The first line terminator of `[from, to)`, or `-1` when that range holds none. */
    private fun nextBreak(text: String, from: Int, to: Int): Int {
        for (index in from until to) {
            val character = text[index]
            if (character == '\n' || character == FORM_FEED) return index
        }
        return -1
    }

    private fun appendParagraph(text: String, start: Int, end: Int, html: StringBuilder) {
        if (start < 0 || end <= start) return
        html.append("<p>")
        for (index in start until end) {
            when (val character = text[index]) {
                // A break inside a paragraph: the line wraps, the paragraph does not.
                '\n' -> html.append("<br/>")
                '&' -> html.append("&amp;")
                '<' -> html.append("&lt;")
                '>' -> html.append("&gt;")
                else -> html.append(character)
            }
        }
        html.append("</p>")
    }

    /** Rough size of `<div dir="rtl"></div>`, so a one-paragraph chapter does not reallocate. */
    private const val ROOT_OVERHEAD_CHARS = 32

    /** The form feed the chapter index splits a file on; see [ChapterIndex]. */
    private const val FORM_FEED = ''
}
