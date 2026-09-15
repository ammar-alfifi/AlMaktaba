package com.mylibrary.feature.reader

import androidx.compose.ui.text.AnnotatedString

/**
 * A run of one block that lands on one page.
 *
 * [start] and [end] are character offsets into the block's own text, and a block drawn whole — an
 * image, a table, a divider, or a paragraph that simply fitted — is the range `0 until its length`.
 * Carrying one shape rather than two means the renderer has one case to handle instead of a union
 * it has to destructure at every use.
 */
internal data class BlockSlice(val blockIndex: Int, val start: Int, val end: Int)

/** One screenful of a reflowable chapter. */
internal data class ReaderPage(val slices: List<BlockSlice>) {

    /**
     * Where this page begins, as an offset into the text of the block it starts in.
     *
     * Not yet an offset into the *chapter*: blocks each carry their own text, and turning one into
     * the other is [ChapterTextMap]'s job. The caller has the map; the paginator does not, and
     * should not — it is given blocks and nothing else.
     */
    val startOffset: Int get() = slices.firstOrNull()?.start ?: 0
}

/**
 * What the paginator needs to know about a block it is not allowed to lay out itself.
 *
 * Splitting text into pages is really two jobs: deciding *which* characters go on which page, and
 * knowing how tall they will be. Only the second needs a text shaper, so only the second is an
 * interface — which leaves the first as an ordinary function that can be tested with a measure that
 * answers instantly and lies freely. That separation is the whole point of this type: page-break
 * arithmetic is where the bugs are, and it is not testable if it is entangled with a `TextMeasurer`.
 */
internal interface BlockMeasure {

    /** How many lines [block] occupies at [widthPx]. */
    fun lineCount(block: ContentBlock, widthPx: Int): Int

    /** The height of line [line] of [block]. */
    fun lineHeight(block: ContentBlock, line: Int, widthPx: Int): Float

    /** The character offset at which line [line] of [block] begins. */
    fun lineStart(block: ContentBlock, line: Int, widthPx: Int): Int

    /** The character offset at which line [line] of [block] ends. */
    fun lineEnd(block: ContentBlock, line: Int, widthPx: Int): Int

    /** The height [block] occupies as a whole, for one that cannot be split. */
    fun wholeHeight(block: ContentBlock, widthPx: Int): Float
}

/**
 * Splits [blocks] into pages [pageHeightPx] tall.
 *
 * Text blocks are cut at line boundaries, so a page never ends mid-line and the continuation
 * re-wraps identically — the same characters at the same width break in the same places. Blocks
 * that cannot be divided (an image, a table, a rule) move to the next page whole rather than being
 * sliced, because half a table is worse than a short page.
 *
 * A block taller than a whole page is placed anyway and overflows. That is deliberate: the
 * alternative is a page containing nothing, and an image the reader can see most of beats an image
 * they cannot see at all.
 */
internal fun paginate(
    blocks: List<ContentBlock>,
    widthPx: Int,
    pageHeightPx: Float,
    spacingPx: Float,
    measure: BlockMeasure,
): List<ReaderPage> {
    if (blocks.isEmpty() || widthPx <= 0 || pageHeightPx <= 0f) return emptyList()

    val pages = mutableListOf<ReaderPage>()
    var current = mutableListOf<BlockSlice>()
    var used = 0f

    fun flush() {
        if (current.isNotEmpty()) {
            pages += ReaderPage(current)
            current = mutableListOf()
            used = 0f
        }
    }

    blocks.forEachIndexed { index, block ->
        if (!block.isSplittable()) {
            val height = measure.wholeHeight(block, widthPx)
            if (current.isNotEmpty() && used + spacingPx + height > pageHeightPx) flush()
            // Re-read after the flush: a block that starts a page carries no leading gap, which is
            // what keeps the top of a page flush instead of indented by one inter-block space.
            val gap = if (current.isEmpty()) 0f else spacingPx
            current += BlockSlice(index, 0, block.bodyText().length)
            used += gap + height
            return@forEachIndexed
        }

        val lines = measure.lineCount(block, widthPx)
        if (lines <= 0) {
            // An empty paragraph still separates the text around it; dropping it would silently
            // change the spacing the document asked for.
            current += BlockSlice(index, 0, 0)
            return@forEachIndexed
        }

        var line = 0
        while (line < lines) {
            val gap = if (current.isEmpty()) 0f else spacingPx
            var remaining = pageHeightPx - used - gap
            var taken = 0f
            var end = line
            while (end < lines) {
                val height = measure.lineHeight(block, end, widthPx)
                if (taken + height > remaining) break
                taken += height
                end++
            }

            if (end == line) {
                // Not one line fits in what is left of this page.
                if (current.isNotEmpty()) {
                    flush()
                    continue
                }
                // ...and the page is empty, which means a page shorter than a single line — a
                // landscape phone at a large font. Take the line anyway; refusing would spin here.
                taken = measure.lineHeight(block, line, widthPx)
                end = line + 1
            }

            current += BlockSlice(
                blockIndex = index,
                start = measure.lineStart(block, line, widthPx),
                end = measure.lineEnd(block, end - 1, widthPx),
            )
            used += gap + taken
            line = end

            // Whatever is left of this block starts a new page.
            if (line < lines) flush()
        }
    }

    flush()
    return pages
}

/**
 * Whether a block may be divided between two pages.
 *
 * A table is the interesting case: it *could* be split by rows, and a reader would rather scroll a
 * long one than lose it, but a grid cut in half mid-row reads as a rendering fault. It moves whole.
 */
internal fun ContentBlock.isSplittable(): Boolean = when (this) {
    is ContentBlock.Paragraph, is ContentBlock.Heading, is ContentBlock.ListItem, is ContentBlock.Quote -> true
    is ContentBlock.Image, is ContentBlock.Table, ContentBlock.Divider -> false
}

/** The block's text, or an empty string for a block that has none. */
internal fun ContentBlock.bodyText(): AnnotatedString = when (this) {
    is ContentBlock.Paragraph -> text
    is ContentBlock.Heading -> text
    is ContentBlock.ListItem -> text
    is ContentBlock.Quote -> text
    is ContentBlock.Image -> AnnotatedString("")
    is ContentBlock.Table -> AnnotatedString("")
    ContentBlock.Divider -> AnnotatedString("")
}
