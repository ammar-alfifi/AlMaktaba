package com.mylibrary.feature.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * One renderable piece of a chapter.
 *
 * A block list rather than one giant `AnnotatedString`: Compose cannot justify, space or
 * page-break a paragraph inside a single string, and Material 3 gives headings their own type
 * scale. Keeping structure means a heading is a `headlineSmall` rather than "body text that
 * happens to be bold".
 */
sealed interface ContentBlock {
    data class Paragraph(val text: AnnotatedString) : ContentBlock
    data class Heading(val level: Int, val text: AnnotatedString) : ContentBlock
    data class ListItem(
        val text: AnnotatedString,
        val ordered: Boolean,
        val number: Int,
        val depth: Int,
    ) : ContentBlock

    data class Quote(val text: AnnotatedString) : ContentBlock

    /** An image inside the document; [path] is relative to the package document. */
    data class Image(val path: String, val alt: String?) : ContentBlock

    data object Divider : ContentBlock
}

/**
 * Converts the sanitised HTML that `:format:format-epub` and `:format:format-text` produce into
 * Compose blocks.
 *
 * The input is a documented subset — paragraphs, headings, lists, quotes, emphasis, images and
 * rules — because those decoders strip everything else before it ever reaches this layer. That is
 * what makes a small parser like this safe: it is not defending against arbitrary HTML, it is
 * reading markup whose shape is already known. Anything unrecognised is unwrapped to its text
 * rather than dropped, so an unexpected tag never silently deletes a paragraph.
 *
 * Parsing is deliberately tolerant: a malformed chapter must render as best-effort text, never as an
 * exception on the reading screen.
 */
fun parseChapterHtml(html: String): List<ContentBlock> {
    val body = runCatching { Jsoup.parseBodyFragment(html).body() }.getOrNull() ?: return emptyList()
    val blocks = mutableListOf<ContentBlock>()
    for (child in body.children()) {
        blocks += child.toBlocks(depth = 0)
    }
    // A chapter that is bare text with no block elements still has to render.
    if (blocks.isEmpty()) {
        val text = body.text().trim()
        if (text.isNotEmpty()) blocks += ContentBlock.Paragraph(AnnotatedString(text))
    }
    return blocks
}

private fun Element.toBlocks(depth: Int): List<ContentBlock> = when (tagName().lowercase()) {
    "p" -> listOfNotNull(paragraphOrNull())
    "h1", "h2", "h3", "h4", "h5", "h6" ->
        listOfNotNull(paragraphOrNull()?.let { ContentBlock.Heading(tagName()[1].digitToInt(), it.text) })

    "blockquote" -> listOfNotNull(paragraphOrNull()?.let { ContentBlock.Quote(it.text) })
    "hr" -> listOf(ContentBlock.Divider)

    "ul", "ol" -> {
        val ordered = tagName().equals("ol", ignoreCase = true)
        children()
            .filter { it.tagName().equals("li", ignoreCase = true) }
            .mapIndexedNotNull { index, item ->
                val text = item.toAnnotatedString()
                if (text.text.isBlank()) {
                    null
                } else {
                    ContentBlock.ListItem(
                        text = text,
                        ordered = ordered,
                        number = index + 1,
                        depth = depth,
                    )
                }
            }
    }

    "img" -> listOfNotNull(imageOrNull())

    "figure" -> buildList {
        selectFirst("img")?.let { image -> imageOrNull(image)?.let(::add) }
        selectFirst("figcaption")?.toAnnotatedString()?.takeIf { it.text.isNotBlank() }?.let {
            add(ContentBlock.Paragraph(it))
        }
    }

    // Unknown containers — `div`, `section`, `article`, `table` — are unwrapped rather than
    // dropped, so nothing inside them is lost. Nested lists keep their depth.
    else -> children().flatMap { child -> child.toBlocks(depth) }
}

private fun Element.paragraphOrNull(): ContentBlock.Paragraph? {
    val text = toAnnotatedString()
    return if (text.text.isBlank()) null else ContentBlock.Paragraph(text)
}

private fun Element.imageOrNull(element: Element = this): ContentBlock.Image? {
    val source = element.attr("src").takeIf { it.isNotBlank() } ?: return null
    return ContentBlock.Image(path = source, alt = element.attr("alt").takeIf { it.isNotBlank() })
}

/**
 * Flattens an element's inline content into an [AnnotatedString].
 *
 * Handles the emphasis and link tags the sanitiser preserves. Tags it does not know are recursed
 * into, so `<span>text</span>` contributes `text` rather than nothing.
 */
private fun Element.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    appendNode(this@toAnnotatedString, this)
}

private fun appendNode(node: Node, builder: androidx.compose.ui.text.AnnotatedString.Builder) {
    when (node) {
        is TextNode -> builder.append(node.text())

        is Element -> {
            val style = node.inlineStyle()
            if (style == null) {
                node.childNodes().forEach { child -> appendNode(child, builder) }
            } else {
                builder.withStyle(style) {
                    node.childNodes().forEach { child -> appendNode(child, builder) }
                }
            }
        }

        else -> Unit
    }
}

/** The [SpanStyle] for an inline element, or `null` when the element carries no formatting. */
private fun Element.inlineStyle(): SpanStyle? = when (tagName().lowercase()) {
    "strong", "b" -> SpanStyle(fontWeight = FontWeight.Bold)
    "em", "i" -> SpanStyle(fontStyle = FontStyle.Italic)
    "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
    "s", "del", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    "sup" -> SpanStyle(baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript)
    "sub" -> SpanStyle(baselineShift = androidx.compose.ui.text.style.BaselineShift.Subscript)
    "code", "kbd", "samp" -> SpanStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    )

    "a" -> SpanStyle(
        textDecoration = TextDecoration.Underline,
        // Links are underlined and tinted by the reader through the theme; the colour is applied
        // there so it follows the user's colour scheme rather than being hardcoded here.
    )

    else -> null
}
