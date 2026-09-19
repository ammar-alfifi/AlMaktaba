package com.mylibrary.format.text.internal

/**
 * Where each chapter of a plain-text document begins and ends, as offsets into the decoded text.
 *
 * ## Where chapters come from
 *
 * TXT has no structure to read: there is no markup, no package document, and no convention that
 * every producer follows. Two policies cover the files that exist in practice:
 *
 *  1. **Form feeds.** A file that contains `0x0C` is a file whose producer already decided where its
 *     pages are — that is what the character is for, and it is what the converters that turn PDFs
 *     and scans into TXT emit. Those breaks become the chapters — with one exception, the run before
 *     the *first* break, which is often the book's own title page rather than a chapter and is
 *     carried by the chapter that follows it. See [isTitlePage].
 *  2. **Paragraph boundaries.** Everything else is cut at a paragraph boundary into segments of
 *     about [TARGET_CHAPTER_CHARS] characters. The reader needs *some* unit it can load, render and
 *     report progress against, and "the whole book" is not one: rendering a 20 MB string into HTML
 *     for every scroll position would stall the UI, and a progress bar that can only show 0% or
 *     100% is not a progress bar.
 *
 * Either way no chapter is left unbounded: a form-feed section longer than the target is cut further,
 * so `chapterHtml` and the per-chapter script analysis always work on a bounded slice.
 *
 * ## Why offsets and not strings
 *
 * The index is two `IntArray`s, four bytes per chapter. Holding a `String` per chapter would mean
 * holding a second copy of the book — the text is already in memory once, for search — and a
 * `substring` on demand copies only the one chapter being read.
 *
 * The index is built eagerly, in one pass, because the chapter count has to be correct the moment
 * the document is open. One pass over the text costs a few milliseconds for a book and buys every
 * later call an O(1) lookup.
 */
internal class ChapterIndex private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
) {
    /** Total chapters; never zero for an index that was built from a non-blank text. */
    val count: Int get() = starts.size

    fun start(index: Int): Int = starts[index]

    fun end(index: Int): Int = ends[index]

    companion object {
        /** Roughly ten to fifteen screens of text: long enough to read, short enough to build. */
        const val TARGET_CHAPTER_CHARS: Int = 50_000

        /** A paragraph cut earlier than this would leave a stunted chapter, so one is not taken. */
        private const val MIN_CHAPTER_CHARS: Int = TARGET_CHAPTER_CHARS / 2

        /**
         * The longest opening section that can still be a title page rather than a chapter.
         *
         * Generous for a title, and short enough that a converter which puts a form feed after every
         * *page* cannot have its first page of prose swallowed into the next chapter.
         */
        private const val TITLE_PAGE_MAX_CHARS: Int = 200

        private const val FORM_FEED = ''
        private const val LINE_BREAK = '\n'
        private const val BLANK_LINE = "\n\n"

        /** Splits [text] into the chapter boundaries of a document. */
        fun of(text: String): ChapterIndex {
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()

            val firstFeed = text.indexOf(FORM_FEED)
            if (firstFeed < 0) {
                appendChapter(text, 0, text.length, starts, ends)
            } else {
                // A file with form feeds: walk them, treating each run of text between two of them
                // as one chapter. Blank runs — a form feed at the very start, or two in a row — are
                // skipped rather than reported as an empty chapter the reader would have to page past.
                //
                // The run before the first feed is the one exception. A producer that writes a title
                // page puts the book's title there and nothing else, and a title is not a chapter:
                // standing alone it is a page holding a single line, which is what the reader opens
                // the book on. When that run is nothing but a short title it is not a boundary at
                // all — the walk starts at 0, so the title is carried by the chapter that follows it
                // and appears above that chapter's own text.
                var from = 0
                var feed = if (isTitlePage(text, 0, firstFeed)) {
                    text.indexOf(FORM_FEED, firstFeed + 1)
                } else {
                    firstFeed
                }
                while (feed >= 0) {
                    appendChapter(text, from, feed, starts, ends)
                    from = feed + 1
                    feed = text.indexOf(FORM_FEED, from)
                }
                appendChapter(text, from, text.length, starts, ends)
            }

            return ChapterIndex(starts.toIntArray(), ends.toIntArray())
        }

        /**
         * Records one chapter for `[start, end)`, or several when that range is longer than the
         * target, and records nothing when it holds only whitespace.
         */
        private fun appendChapter(
            text: String,
            start: Int,
            end: Int,
            starts: MutableList<Int>,
            ends: MutableList<Int>,
        ) {
            var chapterStart = start
            while (end - chapterStart > TARGET_CHAPTER_CHARS) {
                val cut = paragraphCut(text, chapterStart, chapterStart + TARGET_CHAPTER_CHARS)
                addIfNotBlank(text, chapterStart, cut, starts, ends)
                chapterStart = cut
            }
            addIfNotBlank(text, chapterStart, end, starts, ends)
        }

        /**
         * The offset to cut at, at or before [limit]: the end of a blank-line-separated paragraph if
         * there is one, otherwise just after the last line break, otherwise [limit] itself for text
         * with no break in it at all (a single enormous paragraph, or a file of one long line).
         */
        private fun paragraphCut(text: String, start: Int, limit: Int): Int {
            val paragraphBreak = text.lastIndexOf(BLANK_LINE, limit)
            if (paragraphBreak >= start + MIN_CHAPTER_CHARS) {
                // Cut between the paragraphs: the break itself belongs to neither chapter.
                return paragraphBreak + BLANK_LINE.length
            }
            val lineBreak = text.lastIndexOf(LINE_BREAK, limit)
            return if (lineBreak > start) lineBreak + 1 else limit
        }

        /**
         * Whether the opening section `[start, end)` is a title page — the book's own title, written
         * before the first form feed — rather than a chapter of its own.
         *
         * Two things make it one, and both are needed:
         *
         *  - **One paragraph.** A chapter's own opening is a heading, a blank line and then its text,
         *    which is two paragraphs, so a section holding more than one is prose and keeps its
         *    chapter. This is what leaves a file whose first page is a real one alone.
         *  - **Short.** See [TITLE_PAGE_MAX_CHARS].
         *
         * Nothing is dropped either way: a title page is *carried* by the chapter that follows it, so
         * the title still reads at the top of that chapter's first page. Only the boundary between
         * the two goes.
         */
        private fun isTitlePage(text: String, start: Int, end: Int): Boolean {
            val to = trimEnd(text, start, end)
            if (to <= start || to - start > TITLE_PAGE_MAX_CHARS) return false

            var lineStart = start
            var seenContent = false
            while (lineStart < to) {
                val breakIndex = text.indexOf(LINE_BREAK, lineStart)
                val lineEnd = if (breakIndex in lineStart until to) breakIndex else to

                var blank = true
                for (index in lineStart until lineEnd) {
                    if (!text[index].isWhitespace()) {
                        blank = false
                        break
                    }
                }

                // The range is trimmed, so a blank line can only be an interior one — and an interior
                // one after content begins a second paragraph, which makes this a chapter.
                if (blank) {
                    if (seenContent) return false
                } else {
                    seenContent = true
                }
                lineStart = lineEnd + 1
            }
            return true
        }

        /** [end] with the whitespace before it excluded, so the length test measures the text. */
        private fun trimEnd(text: String, start: Int, end: Int): Int {
            var to = end
            while (to > start && text[to - 1].isWhitespace()) to--
            return to
        }

        private fun addIfNotBlank(
            text: String,
            start: Int,
            end: Int,
            starts: MutableList<Int>,
            ends: MutableList<Int>,
        ) {
            if (end <= start) return
            for (index in start until end) {
                if (!text[index].isWhitespace()) {
                    starts.add(start)
                    ends.add(end)
                    return
                }
            }
        }
    }
}
