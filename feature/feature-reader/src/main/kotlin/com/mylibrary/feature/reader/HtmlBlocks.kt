package com.mylibrary.feature.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * One renderable piece of a chapter.
 *
 * A block list rather than one giant `AnnotatedString`: Compose cannot justify, space or page-break
 * a paragraph inside a single string, and Material 3 gives headings their own type scale. Keeping
 * structure means a heading is a `headlineSmall` rather than "body text that happens to be bold",
 * and it is also what makes pagination (splitting a chapter into screens) and text selection
 * (mapping a screen coordinate back to a character) possible at all.
 */
sealed interface ContentBlock {
    data class Paragraph(val text: AnnotatedString) : ContentBlock

    data class Heading(val level: Int, val text: AnnotatedString) : ContentBlock

    data class ListItem(
        val text: AnnotatedString,
        val ordered: Boolean,
        val number: Int,
        /** Nesting depth, 0-based. Drives indentation in the renderer. */
        val depth: Int,
    ) : ContentBlock

    data class Quote(val text: AnnotatedString) : ContentBlock

    /**
     * An image inside the document; [path] is relative to the package document.
     *
     * [caption] carries the `<figcaption>` that belonged to the same `<figure>`. It used to be
     * emitted as a separate paragraph, which detached a caption from the picture it describes —
     * a real problem for a figure whose caption reads "الشكل ٣: …".
     */
    data class Image(
        val path: String,
        val alt: String?,
        val caption: AnnotatedString? = null,
    ) : ContentBlock

    /** A table, kept as a grid so cells stay in their row and column. */
    data class Table(val rows: List<TableRow>) : ContentBlock

    data object Divider : ContentBlock
}

/** One row of a [ContentBlock.Table]. */
data class TableRow(val cells: List<TableCell>, val isHeader: Boolean)

/** One cell of a [ContentBlock.Table]. */
data class TableCell(
    val text: AnnotatedString,
    val colSpan: Int,
    val rowSpan: Int,
    val isHeader: Boolean,
)

/**
 * A parsed chapter: what to draw, and where its anchors are.
 *
 * [anchorBlocks] maps an element `id` to the index of the block that carries it. That is what makes
 * a footnote reference tappable: the engine resolves an `href` to *which chapter and which anchor*,
 * and this map turns the anchor into a place on the page. Ids are kept in document order and the
 * first occurrence wins, which is the only sane reading of a document that reuses an id.
 */
data class ParsedChapter(
    val blocks: List<ContentBlock>,
    val anchorBlocks: Map<String, Int>,
) {
    companion object {
        val Empty = ParsedChapter(blocks = emptyList(), anchorBlocks = emptyMap())
    }
}

/**
 * How to render links, supplied by the reader.
 *
 * The parser cannot know the theme colour or what tapping should do, and the reader cannot know
 * which character range a link covers. Passing this in is the seam between them — and passing
 * `null`, as the tests and any non-interactive caller do, yields plain underlined text with no
 * annotations at all.
 */
data class LinkStyling(
    val color: Color,
    val onClick: (href: String) -> Unit,
)

/**
 * Converts the sanitised HTML that `:format:format-epub` and `:format:format-text` produce into
 * Compose blocks, an anchor index, and tappable links.
 *
 * The input is a documented subset — paragraphs, headings, lists, tables, quotes, emphasis, images
 * and rules — because those decoders strip everything else before it ever reaches this layer. That
 * is what makes a small parser like this safe: it is not defending against arbitrary HTML, it is
 * reading markup whose shape is already known. Anything unrecognised is unwrapped to its text
 * rather than dropped, so an unexpected tag never silently deletes a paragraph.
 *
 * Parsing is deliberately tolerant: a malformed chapter must render as best-effort text, never as an
 * exception on the reading screen.
 */
fun parseChapterHtml(html: String, links: LinkStyling? = null): ParsedChapter {
    val body = runCatching { Jsoup.parseBodyFragment(html).body() }.getOrNull()
        ?: return ParsedChapter.Empty

    val context = ParseContext(links)
    body.children().forEach { child -> child.emitBlocks(depth = 0, context = context) }

    // A chapter that is bare text with no block elements still has to render.
    if (context.blocks.isEmpty()) {
        val text = body.text().trim()
        if (text.isNotEmpty()) context.blocks += ContentBlock.Paragraph(AnnotatedString(text))
    }
    return ParsedChapter(blocks = context.blocks, anchorBlocks = context.anchors)
}

/**
 * The state of one chapter parse.
 *
 * Bundling the output list, the anchor map and the link styling into one value is what lets every
 * branch of the walk append in document order and record an anchor against the index it will
 * actually occupy. Returning lists from each branch instead would mean tracking the running size by
 * hand, which is how the previous version ended up unable to give a nested list item a depth.
 */
private class ParseContext(val links: LinkStyling?) {
    val blocks: MutableList<ContentBlock> = mutableListOf()
    val anchors: MutableMap<String, Int> = mutableMapOf()
}

/**
 * Appends this element's blocks, recording any `id` it carries against the block it lands in.
 */
private fun Element.emitBlocks(depth: Int, context: ParseContext) {
    // Recorded before emitting: the id belongs to the block that is about to be appended.
    //
    // A list is the exception: `appendListItems` records one anchor per item so that `<li id="fn7">`
    // resolves to that footnote, and this element's blanket descendant scan would otherwise claim
    // every item for the list's own block index first.
    recordAnchors(
        blockIndex = context.blocks.size,
        into = context.anchors,
        includeDescendants = !isList(),
    )

    when (tagName().lowercase()) {
        "p" -> context.blocks += paragraphBlocks(context)

        "h1", "h2", "h3", "h4", "h5", "h6" ->
            paragraphOrNull(context)?.let { context.blocks += ContentBlock.Heading(tagName()[1].digitToInt(), it.text) }

        "blockquote" -> paragraphOrNull(context)?.let { context.blocks += ContentBlock.Quote(it.text) }

        "hr" -> context.blocks += ContentBlock.Divider

        "ul", "ol" -> appendListItems(ordered = isOrderedList(), depth = depth, context = context)

        "table" -> tableOrNull(context)?.let { context.blocks += it }

        "img" -> imageOrNull()?.let { context.blocks += it }

        "figure" -> figureOrNull(context)?.let { context.blocks += it }

        // Unknown containers — `div`, `section`, `article`, `dl` — are unwrapped rather than
        // dropped, so nothing inside them is lost.
        else -> children().forEach { child -> child.emitBlocks(depth, context) }
    }
}

/**
 * Records this element's own id, and those of its descendants, against [blockIndex].
 *
 * Descendants map to the enclosing block because an `<a id="x">` inside a paragraph has no block of
 * its own — the block *is* the paragraph, and that is what the reader scrolls to.
 */
private fun Element.recordAnchors(
    blockIndex: Int,
    into: MutableMap<String, Int>,
    includeDescendants: Boolean = true,
) {
    id().takeIf { it.isNotEmpty() }?.let { into.putIfAbsent(it, blockIndex) }
    if (!includeDescendants) return

    select("[id]").forEach { descendant ->
        descendant.id().takeIf { it.isNotEmpty() }?.let { into.putIfAbsent(it, blockIndex) }
    }
}

private fun Element.isOrderedList(): Boolean = tagName().equals("ol", ignoreCase = true)

private fun Element.isList(): Boolean = tagName().equals("ul", ignoreCase = true) ||
    tagName().equals("ol", ignoreCase = true)

/**
 * Appends a list's items, recursing into nested lists at increasing depth.
 *
 * Anchors are recorded per item, so `<ol><li id="fn7">` navigates to that footnote rather than to
 * the top of the list. HTML nests an inner list *inside* the `li`, so the recursion has to happen
 * from here — the previous implementation called `toAnnotatedString()` on each item, which swallowed
 * the nested list into its parent's text and left `depth` structurally unable to be anything but 0.
 */
private fun Element.appendListItems(ordered: Boolean, depth: Int, context: ParseContext) {
    var number = 0
    children().filter { it.tagName().equals("li", ignoreCase = true) }.forEach { item ->
        number++

        // The item's own inline content, with any nested list removed first — the nested list is
        // emitted separately below rather than being flattened into this text.
        val ownContent = item.clone().apply {
            children().filter { it.isList() }.forEach { nested -> nested.remove() }
        }
        val text = ownContent.toAnnotatedString(context)
        if (text.text.isNotBlank()) {
            item.recordAnchors(blockIndex = context.blocks.size, into = context.anchors)
            context.blocks += ContentBlock.ListItem(
                text = text,
                ordered = ordered,
                number = number,
                depth = depth,
            )
        }

        item.children().filter { it.isList() }.forEach { nested ->
            nested.appendListItems(ordered = nested.isOrderedList(), depth = depth + 1, context = context)
        }
    }
}

/**
 * Reads a table.
 *
 * `colspan` and `rowspan` are the only two attributes the sanitiser keeps besides `href`/`src`, and
 * they were previously unreachable because the whole table was flattened at parse time. A cell's
 * span is clamped rather than trusted: a malformed `colspan="9999"` would otherwise ask the
 * renderer to allocate a row of ten thousand weights.
 */
private fun Element.tableOrNull(context: ParseContext): ContentBlock.Table? {
    val rowElements = select("tr")
    if (rowElements.isEmpty()) return null

    val rows = rowElements.mapNotNull { row ->
        val cells = row.children()
            .filter { it.tagName().equals("td", ignoreCase = true) || it.tagName().equals("th", ignoreCase = true) }
            .map { cell ->
                TableCell(
                    text = cell.toAnnotatedString(context),
                    colSpan = cell.spanAttribute("colspan", max = MAX_COL_SPAN),
                    rowSpan = cell.spanAttribute("rowspan", max = MAX_ROW_SPAN),
                    isHeader = cell.tagName().equals("th", ignoreCase = true),
                )
            }
        if (cells.isEmpty()) {
            null
        } else {
            TableRow(
                cells = cells,
                isHeader = row.parent()?.tagName()?.equals("thead", ignoreCase = true) == true ||
                    cells.all { it.isHeader },
            )
        }
    }

    return if (rows.isEmpty()) null else ContentBlock.Table(rows)
}

private fun Element.spanAttribute(name: String, max: Int): Int =
    attr(name).toIntOrNull()?.coerceIn(1, max) ?: 1

/** A figure: its image, and the caption that belongs to it. */
private fun Element.figureOrNull(context: ParseContext): ContentBlock.Image? {
    val image = selectFirst("img") ?: return null
    val caption = selectFirst("figcaption")?.toAnnotatedString(context)?.takeIf { it.text.isNotBlank() }
    return imageOrNull(image)?.copy(caption = caption)
}

/**
 * A paragraph, split around any inline images it contains.
 *
 * `<p>text <img/> text</p>` is common in real EPUBs, and treating the paragraph as a single
 * `AnnotatedString` dropped the image entirely — the old code produced a paragraph whose text was
 * blank and then discarded it, so the picture vanished with no trace.
 */
private fun Element.paragraphBlocks(context: ParseContext): List<ContentBlock> = buildList {
    var run = mutableListOf<Node>()

    fun flushRun() {
        if (run.isEmpty()) return
        val text = annotateNodes(run, context)
        if (text.text.isNotBlank()) add(ContentBlock.Paragraph(text))
        run = mutableListOf()
    }

    childNodes().forEach { node ->
        if (node is Element && node.tagName().equals("img", ignoreCase = true)) {
            flushRun()
            imageOrNull(node)?.let { add(it) }
        } else {
            run += node
        }
    }
    flushRun()
}

private fun Element.paragraphOrNull(context: ParseContext): ContentBlock.Paragraph? {
    val text = toAnnotatedString(context)
    return if (text.text.isBlank()) null else ContentBlock.Paragraph(text)
}

private fun Element.imageOrNull(element: Element = this): ContentBlock.Image? {
    val source = element.attr("src").takeIf { it.isNotBlank() } ?: return null
    return ContentBlock.Image(path = source, alt = element.attr("alt").takeIf { it.isNotBlank() })
}

/**
 * Flattens an element's inline content into an [AnnotatedString].
 *
 * Handles the emphasis, ruby and link tags the sanitiser preserves. Tags it does not know are
 * recursed into, so `<span>text</span>` contributes `text` rather than nothing.
 */
private fun Element.toAnnotatedString(context: ParseContext): AnnotatedString =
    annotateNodes(childNodes(), context)

/** Builds an [AnnotatedString] from a flat run of nodes. */
private fun annotateNodes(nodes: List<Node>, context: ParseContext): AnnotatedString =
    buildAnnotatedString {
        nodes.forEach { node -> appendNode(node, this, context) }
    }

/** `ruby` needs its base and annotation handled together, so it cannot be a plain span style. */
private fun Element.isRuby(): Boolean = tagName().equals("ruby", ignoreCase = true)

private fun appendNode(
    node: Node,
    builder: AnnotatedString.Builder,
    context: ParseContext,
) {
    when (node) {
        is TextNode -> builder.append(node.text())

        is Element -> when {
            node.isRuby() -> node.appendRuby(builder, context)

            // `<br>` is a line break, not whitespace. jsoup hands it over as an element with no
            // children, so without this a poem or an address collapses onto a single line.
            node.tagName().equals("br", ignoreCase = true) -> builder.append("\n")

            node.tagName().equals("a", ignoreCase = true) -> node.appendLink(builder, context)

            else -> {
                val style = node.inlineStyle()
                if (style == null) {
                    node.childNodes().forEach { child -> appendNode(child, builder, context) }
                } else {
                    builder.withStyle(style) {
                        node.childNodes().forEach { child -> appendNode(child, builder, context) }
                    }
                }
            }
        }

        else -> Unit
    }
}

/**
 * Renders a link as tappable text when the reader supplied styling, and as plain underlined text
 * otherwise.
 *
 * The href is bound into the annotation rather than looked up later, so tapping resolves against
 * the exact string the document contained — including `#fragment` and `../relative` forms — and the
 * engine remains the only layer that has to understand that grammar.
 */
private fun Element.appendLink(builder: AnnotatedString.Builder, context: ParseContext) {
    val href = attr("href").takeIf { it.isNotBlank() }
    val styling = context.links

    val body: AnnotatedString.Builder.() -> Unit = {
        childNodes().forEach { child -> appendNode(child, builder = this, context = context) }
    }

    if (href == null || styling == null) {
        builder.withStyle(SpanStyle(textDecoration = TextDecoration.Underline), body)
        return
    }

    builder.withLink(
        LinkAnnotation.Clickable(
            tag = href,
            styles = TextLinkStyles(
                style = SpanStyle(color = styling.color, textDecoration = TextDecoration.Underline),
            ),
            linkInteractionListener = { styling.onClick(href) },
        ),
        body,
    )
}

/**
 * Renders ruby as base text followed by its reading annotation, raised and reduced.
 *
 * True ruby needs a second text run positioned above the base, which `AnnotatedString` cannot
 * express. This approximation at least keeps the base readable and the annotation attached to it,
 * where the previous code emitted both as one undifferentiated run — so a Japanese or Chinese book
 * read as if the furigana were part of the sentence.
 */
private fun Element.appendRuby(builder: AnnotatedString.Builder, context: ParseContext) {
    // `childNodes()`, not `children()`: in `<ruby>漢<rt>かん</rt></ruby>` the base text is a bare
    // text node, which `children()` does not return at all — filtering on elements alone silently
    // dropped the very character the annotation belongs to.
    val children = childNodes()
    val base = children.filterNot { node ->
        node is Element && (node.tagName().equals("rt", true) || node.tagName().equals("rp", true))
    }
    val annotation = children.filterIsInstance<Element>().filter { it.tagName().equals("rt", true) }

    base.forEach { child -> appendNode(child, builder, context) }
    annotation.forEach { rt ->
        builder.withStyle(
            SpanStyle(
                fontSize = RUBY_ANNOTATION_SCALE.em,
                baselineShift = BaselineShift.Superscript,
            ),
        ) {
            appendNode(rt, builder, context)
        }
    }
}

/** The [SpanStyle] for an inline element, or `null` when the element carries no formatting. */
private fun Element.inlineStyle(): SpanStyle? = when (tagName().lowercase()) {
    "strong", "b" -> SpanStyle(fontWeight = FontWeight.Bold)
    "em", "i" -> SpanStyle(fontStyle = FontStyle.Italic)
    "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
    "s", "del", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript)
    "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript)
    "code", "kbd", "samp" -> SpanStyle(fontFamily = FontFamily.Monospace)
    else -> null
}

/** Caps on malformed span attributes, so a bad file cannot ask for an absurd grid. */
private const val MAX_COL_SPAN = 12
private const val MAX_ROW_SPAN = 50

private const val RUBY_ANNOTATION_SCALE = 0.6f
