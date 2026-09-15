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
     * Escaping and rendering happen in the same pass: `&`, `<` and `>` are the only characters that
     * can change meaning inside a paragraph, and writing each one straight into a fresh buffer means
     * there is no intermediate string for an ampersand to be escaped in twice.
     */
    private fun appendParagraphs(text: String, from: Int, to: Int, html: StringBuilder) {
        var lineStart = from
        var paragraphStart = -1
        var paragraphEnd = -1

        while (lineStart < to) {
            val breakIndex = text.indexOf('\n', lineStart)
            val lineEnd = if (breakIndex in lineStart until to) breakIndex else to

            var blank = true
            for (index in lineStart until lineEnd) {
                if (!text[index].isWhitespace()) {
                    blank = false
                    break
                }
            }

            if (blank) {
                appendParagraph(text, paragraphStart, paragraphEnd, html)
                paragraphStart = -1
            } else {
                if (paragraphStart < 0) paragraphStart = lineStart
                paragraphEnd = lineEnd
            }
            lineStart = lineEnd + 1
        }
        appendParagraph(text, paragraphStart, paragraphEnd, html)
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
}
