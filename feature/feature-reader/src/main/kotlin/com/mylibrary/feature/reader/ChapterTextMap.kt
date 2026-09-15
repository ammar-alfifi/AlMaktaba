package com.mylibrary.feature.reader

/**
 * Where each block of a chapter sits in the document's own chapter text.
 *
 * This is the piece that lets three otherwise-unrelated features agree with each other. A reading
 * position, a search hit and a highlight are all expressed as a character offset into the chapter
 * text the *engine* produced — that is what `ReadingLocator.Reflowable(chapterIndex, charOffset)`
 * means, and it is why a position survives a font-size change. The renderer, meanwhile, works in
 * blocks. Without a map between the two, a tap on a footnote could not become a position, and a
 * stored highlight could not be drawn.
 *
 * Offsets are found by locating each block's text inside the chapter text with a running cursor,
 * rather than by re-deriving them from the HTML. That direction matters: it makes the engine's
 * chapter text the single source of truth, so an offset can never drift from what search returns.
 * Blocks that cannot be located — a table, whose engine text uses different separators — are pinned
 * to the cursor rather than being given a wrong position.
 */
data class ChapterTextMap(
    /** Start offset of each block, parallel to the block list. */
    val blockStarts: List<Int>,
    val chapterLength: Int,
) {
    /** The character offset a block begins at. */
    fun startOf(blockIndex: Int): Int = blockStarts.getOrElse(blockIndex) { chapterLength }

    /** The offset one past the last character of a block. */
    fun endOf(blockIndex: Int, blocks: List<ContentBlock>): Int {
        if (blockIndex !in blocks.indices) return chapterLength
        return (startOf(blockIndex) + blocks[blockIndex].plainText().length).coerceAtMost(chapterLength)
    }

    /** The block containing [offset], or `null` when the chapter has no blocks. */
    fun blockAt(offset: Int, blocks: List<ContentBlock>): Int? {
        if (blocks.isEmpty()) return null
        val index = blockStarts.indexOfLast { it <= offset }
        return index.takeIf { it >= 0 } ?: 0
    }

    companion object {
        val Empty = ChapterTextMap(blockStarts = emptyList(), chapterLength = 0)
    }
}

/**
 * The plain text of a block, as it appears in the chapter text.
 *
 * Images contribute their caption rather than their alt text, because a caption is part of the
 * document's prose and appears in `chapterText`, while `alt` is a description that does not.
 */
fun ContentBlock.plainText(): String = when (this) {
    is ContentBlock.Paragraph -> text.text
    is ContentBlock.Heading -> text.text
    is ContentBlock.Quote -> text.text
    is ContentBlock.ListItem -> text.text
    is ContentBlock.Image -> caption?.text.orEmpty()
    is ContentBlock.Table -> rows.joinToString(separator = "\n") { row ->
        row.cells.joinToString(separator = "\t") { cell -> cell.text.text }
    }

    ContentBlock.Divider -> ""
}

/**
 * Builds the block → character-offset map for a chapter.
 *
 * Sequential `indexOf` from a running cursor rather than a search from zero for each block: two
 * blocks may legitimately contain identical text ("نعم." as its own paragraph, say), and searching
 * from the start each time would collapse them onto the same offset and make every later position
 * point backwards.
 */
fun buildChapterTextMap(blocks: List<ContentBlock>, chapterText: String): ChapterTextMap {
    if (blocks.isEmpty()) return ChapterTextMap(blockStarts = emptyList(), chapterLength = chapterText.length)

    val starts = ArrayList<Int>(blocks.size)
    var cursor = 0

    blocks.forEach { block ->
        val text = block.plainText()
        val searchFrom = cursor.coerceAtMost(chapterText.length)

        if (text.isEmpty()) {
            starts += searchFrom
            return@forEach
        }

        val found = chapterText.indexOf(text, startIndex = searchFrom)
        if (found >= 0) {
            starts += found
            cursor = found + text.length
        } else {
            // Not locatable — a table, whose engine text uses different separators. Pinned to the
            // cursor, and the cursor deliberately does **not** advance: moving it past text that was
            // never matched would push every following block too far, turning one unmatched block
            // into a chapter-wide offset drift.
            starts += searchFrom
        }
    }

    return ChapterTextMap(blockStarts = starts, chapterLength = chapterText.length)
}
