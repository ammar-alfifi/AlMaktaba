package com.mylibrary.format.epub

import com.mylibrary.core.domain.model.EmbeddedFont

/** What a document's stylesheets say about type: the faces it carries, and the family its body asks for. */
internal class DocumentStyles(
    val fonts: List<EmbeddedFont>,
    val bodyFontFamily: String?,
)

/**
 * Where a document's CSS comes from.
 *
 * The two kinds are kept apart because they behave differently: a stylesheet is a file that still has
 * to be read, and every `url()` inside it is relative to the stylesheet itself, while a `<style>`
 * element sits inside the package document and is relative to that. An empty list here is the normal
 * case — most books carry no CSS at all — and the reader must treat it as "no typography declared"
 * rather than as an error.
 */
internal class StylesheetSources(
    /** Archive paths of the manifest items that are stylesheets, in manifest order. */
    val paths: List<String>,
    /** The text of each `<style>` element in the package document, in document order. */
    val inlineCss: List<String>,
) {
    companion object {
        fun of(epubPackage: EpubPackage): StylesheetSources = StylesheetSources(
            paths = epubPackage.manifest.values.filter { it.isStylesheet }.mapNotNull { it.path },
            inlineCss = epubPackage.inlineStyles,
        )
    }
}

/** One `@font-face` rule, with its `url()` still as written — relative to the sheet that declared it. */
private class FontFaceDeclaration(
    val family: String,
    val weight: Int,
    val italic: Boolean,
    val href: String,
)

/** What one stylesheet says, before any of it is resolved against the archive. */
private class CssRules {
    val fontFaces = ArrayList<FontFaceDeclaration>()

    /** `@import` targets as written, in the order they appear. */
    val imports = ArrayList<String>()

    /** The first family declared for `body`, then for `html` — the body rule is the more specific one. */
    var bodyFamily: String? = null
    var htmlFamily: String? = null
}

/** A stylesheet that has been read and parsed, with the directory its `url()`s are relative to. */
private class ParsedSheet(val rules: CssRules, val baseDir: String)

/**
 * What makes two `@font-face` rules the same face.
 *
 * The family is compared without case and the descriptors exactly, because that is what a reader
 * chooses between: one file per family, weight and slant. A book that declares the same three twice
 * is describing one font, and listing it twice would offer the reader a choice it cannot make.
 */
private data class FaceId(val family: String, val weight: Int, val italic: Boolean)

/**
 * Reads the typography out of a book's CSS: which fonts it embeds, and which family its body text
 * asks for.
 *
 * **Why not a CSS engine.** The reader styles the book itself — it owns font size, line height,
 * colours, margins and page direction — so there is almost nothing a publisher's stylesheet could
 * say that the reader would honour even if it understood all of it. Two things are worth honouring,
 * and both are facts rather than styling: a face the book carries inside it, and the family the body
 * was typeset in. Those live in `@font-face` blocks and in one `body` rule, which is pattern
 * matching over a few kilobytes of text, not a cascade. A CSS engine would be a dependency the size
 * of jsoup to read the same two facts, and it would arrive with selector matching, specificity and
 * inheritance that this engine has no way to apply — the reader does not lay the book out with a
 * browser.
 *
 * **What is understood.** `@font-face` (`font-family`, the first `url(...)` of `src`, `font-weight`,
 * `font-style`); `font-family` on a rule whose last compound selector is `body`, falling back to
 * `html`; and `@import`, followed one level deep. Everything else — `@media`, `@supports`,
 * `@keyframes`, `local(...)` sources, `format(...)` hints, `unicode-range`, every selector this
 * reader does not ask about — is skipped by brace matching rather than interpreted. A stylesheet
 * that would defeat a regex cannot defeat that.
 *
 * **Nothing here throws.** A stylesheet the book does not have, a `url()` that climbs out of the
 * archive, a font the manifest promised and the zip never shipped: each of them means one rule is
 * unusable, and the answer is to leave that rule out. A book whose CSS is broken is still a
 * readable book, in the reader's own font.
 *
 * **Every path goes through [EpubPaths]** and is decoded exactly once, by [EpubPaths.resolve]. That
 * one decoding is also why no `url()` is percent-decoded here as well: a second pass would turn a
 * font whose name really contains `%20` — written `%2520` in the href — into a font named with a
 * space, which is a different file.
 */
internal object EpubStyles {

    /**
     * Reads every stylesheet [sources] names, one `@import` level deep.
     *
     * **Why one level.** `@import` chains are how a book splits its styling — `book.css` importing
     * `fonts.css` is the common shape, and the fonts are in the imported sheet. Following chains to
     * the end, though, means reading as many files as a hostile book cares to name, and two sheets
     * importing each other is a cycle that never ends. One level reaches the fonts in every layout
     * seen in practice and is bounded by the manifest: the imported sheets are never asked for their
     * own imports, and a sheet is read at most once however many ways it is named.
     *
     * Order matters, and it is the document's: [sources]' own sheets first — the manifest's, whose
     * rules the book was built with, before the package document's leftovers — then the sheets they
     * import. The first `body` rule found wins, which for the usual `@import url("fonts.css")`
     * followed by a `body` rule is the right one.
     */
    fun read(archive: EpubArchive, sources: StylesheetSources, opfDir: String): DocumentStyles {
        val sheets = ArrayList<ParsedSheet>()
        val read = HashSet<String>()
        for (path in sources.paths) readSheet(archive, path, read)?.let { sheets += it }
        // An inline `<style>` is resolved against the package document, because that is the file it
        // sits in — not against the directory of some stylesheet that may not exist.
        for (css in sources.inlineCss) sheets += ParsedSheet(parseCss(css), opfDir)

        val imported = ArrayList<ParsedSheet>()
        for (sheet in sheets) {
            for (href in sheet.rules.imports) {
                // An `@import` is relative to the sheet that wrote it, like every other url in CSS.
                val target = EpubPaths.resolve(sheet.baseDir, href) ?: continue
                readSheet(archive, target, read)?.let { imported += it }
            }
        }

        val all = sheets + imported
        return DocumentStyles(
            fonts = fontsIn(archive, all, opfDir),
            bodyFontFamily = all.firstNotNullOfOrNull { it.rules.bodyFamily }
                ?: all.firstNotNullOfOrNull { it.rules.htmlFamily },
        )
    }

    /**
     * Reads one stylesheet, at most once per document.
     *
     * [read] is the visited set, keyed by the archive's own spelling of the path so that a sheet
     * named two ways — the manifest's `Styles/book.css` and an import's `./book.css` — is still one
     * sheet, and so that a self-import terminates instead of looping.
     */
    private fun readSheet(archive: EpubArchive, path: String, read: MutableSet<String>): ParsedSheet? {
        val entry = archive.findEntry(path) ?: return null
        if (!read.add(entry)) return null
        val css = archive.readEntry(entry) ?: return null
        // Decoded through the module's one decoder for the same reason the container is: a BOM and a
        // `<?xml?>` declaration both turn up in files that were never meant to need one. CSS is UTF-8
        // by specification, and that is the fallback.
        return ParsedSheet(parseCss(EpubXml.decode(css)), EpubPaths.directoryOf(entry))
    }

    /**
     * Turns the parsed rules into the faces the reader can actually load.
     *
     * Two rules are dropped here rather than in the parser, because both depend on the archive and
     * not on the CSS: a `url()` that climbs out of the archive resolves to nothing ([EpubPaths.resolve]
     * answers `null` for it), and a `url()` naming a file the book does not contain is a promise the
     * container did not keep. Neither is reported — a book that names a missing font is common enough
     * that refusing to open it, or failing a chapter over it, would be worse than the substitution
     * the reader falls back to anyway.
     */
    private fun fontsIn(archive: EpubArchive, sheets: List<ParsedSheet>, opfDir: String): List<EmbeddedFont> {
        val fonts = ArrayList<EmbeddedFont>()
        val seen = HashSet<FaceId>()
        for (sheet in sheets) {
            for (face in sheet.rules.fontFaces) {
                // Relative to the stylesheet, never to the package document: a font declared in
                // `Styles/fonts/book.css` sits in `Styles/fonts/`, not beside the OPF.
                val entry = EpubPaths.resolve(sheet.baseDir, face.href)?.let { archive.findEntry(it) } ?: continue
                // The same face declared twice is one face. The first declaration wins; a reader has
                // no way to choose between two files for one family and weight, and a list that
                // changes with reading order would be worse than one that does not.
                if (!seen.add(FaceId(face.family.lowercase(), face.weight, face.italic))) continue
                fonts += EmbeddedFont(
                    family = face.family,
                    weight = face.weight,
                    italic = face.italic,
                    // Spelled the way a chapter's `<img src>` is, so the reader loads a font through
                    // `resource()` with no idea which stylesheet declared it or where it sat.
                    path = EpubPaths.resourcePathFor(entry, opfDir),
                )
            }
        }
        return fonts
    }

    // --- The CSS subset ----------------------------------------------------------------------

    /**
     * Reads the slice of CSS described on [EpubStyles] out of one stylesheet.
     *
     * A walk over the text rather than a parse tree: rules arrive as a prelude and a body (`{`…`}`) or
     * as a bare statement (`…;`), and anything the reader has no use for is stepped over whole by
     * brace matching. Malformed CSS ends the walk rather than throwing — an unterminated block has no
     * end to resume from, so there is nothing after it to read.
     */
    private fun parseCss(css: String): CssRules {
        val rules = CssRules()
        val text = stripComments(css)
        var index = 0
        while (index < text.length) {
            val open = text.indexOf('{', index)
            val semicolon = text.indexOf(';', index)
            when {
                open >= 0 && (semicolon < 0 || open < semicolon) -> {
                    val close = matchingBrace(text, open) ?: return rules
                    collectBlock(text.substring(index, open).trim(), text.substring(open + 1, close), rules)
                    index = close + 1
                }
                semicolon >= 0 -> {
                    collectStatement(text.substring(index, semicolon).trim(), rules)
                    index = semicolon + 1
                }
                else -> {
                    // What is left is a statement whose `;` was left off — which a sheet that ends in
                    // an `@import` sometimes does. A statement this reader has no use for is ignored
                    // by [collectStatement] anyway, so the tail is always worth one look.
                    collectStatement(text.substring(index).trim(), rules)
                    return rules
                }
            }
        }
        return rules
    }

    private fun collectBlock(prelude: String, body: String, rules: CssRules) {
        if (!prelude.startsWith("@")) {
            collectStyleRule(prelude, body, rules)
            return
        }
        // A conditional group rule — `@media`, `@supports` — is skipped whole, and that is a decision
        // rather than an omission. A rule inside one applies only under a condition this reader
        // cannot evaluate, and taking `@media print { body { font-family: serif } }` as the reading
        // font would be worse than reading no family at all. The `@font-face` rules publishers write
        // are written unconditionally.
        if (atKeyword(prelude) == FONT_FACE) parseFontFace(body)?.let { rules.fontFaces += it }
    }

    private fun collectStatement(prelude: String, rules: CssRules) {
        if (atKeyword(prelude) != IMPORT) return
        // `@import url("fonts.css")` and `@import "fonts.css"` are both legal, and both are written.
        val target = firstUrl(prelude) ?: firstQuoted(prelude) ?: return
        rules.imports += target
    }

    /**
     * Records the body font family a style rule declares, if it declares one.
     *
     * The first rule for each of `body` and `html` wins. Within one sheet that is document order,
     * which is what CSS itself falls back on when specificity ties; across sheets it is the reading
     * order [read] fixes. `body` outranks `html` because it is the element the text is actually in.
     */
    private fun collectStyleRule(selectorList: String, body: String, rules: CssRules) {
        val family = declarationsIn(body)["font-family"]?.let { firstFamily(it) } ?: return
        if (rules.bodyFamily == null && selectsElement(selectorList, "body")) rules.bodyFamily = family
        if (rules.htmlFamily == null && selectsElement(selectorList, "html")) rules.htmlFamily = family
    }

    private fun parseFontFace(body: String): FontFaceDeclaration? {
        val declarations = declarationsIn(body)
        val family = declarations["font-family"]?.let { firstFamily(it) } ?: return null
        // `src` is the one descriptor that must be there: a face with no file behind it is a rule
        // about nothing, and its absence is also how a book that only names `local(...)` faces
        // announces that it expects the reader's device to have the font.
        val href = declarations["src"]?.let { firstUrl(it) } ?: return null
        return FontFaceDeclaration(
            family = family,
            weight = weightOf(declarations["font-weight"]),
            italic = isItalic(declarations["font-style"]),
            href = href,
        )
    }

    /**
     * The first `url(...)` in a value, as the path it names — or `null` when there is none.
     *
     * The first, not the best: `format(...)` hints order a browser's preferences (`woff2` before
     * `woff`), and a reader loading a file off a disk has no such preference to express. `data:` URIs
     * and `local(...)` sources fall through the same way — one names bytes this engine has no way to
     * hand on, the other names a font on the reader's device rather than in the book, which is
     * exactly the substitution this whole reader exists to avoid.
     */
    private fun firstUrl(value: String): String? {
        val start = value.indexOf("url(", ignoreCase = true)
        if (start < 0) return null
        var index = start + URL_PREFIX.length
        while (index < value.length && value[index].isWhitespace()) index++
        val quote = value.getOrNull(index)?.takeIf { it == '"' || it == '\'' }
        val end = if (quote == null) value.indexOf(')', index) else value.indexOf(quote, index + 1)
        if (end < 0) return null
        return value.substring(if (quote == null) index else index + 1, end).trim().ifEmpty { null }
    }

    /** The first quoted string in a value, which is how `@import "fonts.css"` names its sheet. */
    private fun firstQuoted(value: String): String? {
        val start = value.indexOfFirst { it == '"' || it == '\'' }
        if (start < 0) return null
        val end = value.indexOf(value[start], start + 1)
        if (end < 0) return null
        return value.substring(start + 1, end).trim().ifEmpty { null }
    }

    /** The first family in a `font-family` value, without the quotes CSS allows around the name. */
    private fun firstFamily(value: String): String? {
        val first = splitTopLevel(withoutImportant(value), ',').firstOrNull()?.trim().orEmpty()
        return unquote(first).ifEmpty { null }
    }

    /**
     * A `font-weight` descriptor as CSS weight.
     *
     * A value this reader has no number for is 400, which is the weight of the regular face and what
     * CSS itself defaults to: `normal` says so explicitly, `bolder` and `lighter` are relative to a
     * parent this reader does not have, and a misspelling says nothing at all.
     */
    private fun weightOf(value: String?): Int = when (val first = firstToken(value).lowercase()) {
        "bold" -> BOLD_WEIGHT
        "normal" -> NORMAL_WEIGHT
        // A range (`400 700`, which a variable font declares) is read as its first value, matching
        // how the range is meant to be used: the multiple of 100 nearest the requested weight.
        else -> first.toIntOrNull()?.coerceIn(MIN_WEIGHT, MAX_WEIGHT) ?: NORMAL_WEIGHT
    }

    /**
     * Whether a `font-style` descriptor asks for a slanted face.
     *
     * `oblique` counts as italic because that is what it means to a reader that picks one slanted
     * face per family — the publisher shipped one, and it is the one to use. Reading only the first
     * token also covers `oblique 14deg`, the variable-font spelling.
     */
    private fun isItalic(value: String?): Boolean =
        firstToken(value).lowercase().let { it == "italic" || it == "oblique" }

    /**
     * True when one of a rule's selectors styles [element] itself.
     *
     * Only the last compound selector counts. `html body` and `body.chapter` do style the body;
     * `body p` styles paragraphs, and reading its family as the body's would make the book's body
     * font depend on how its paragraphs are decorated.
     */
    private fun selectsElement(selectorList: String, element: String): Boolean =
        splitTopLevel(selectorList, ',').any { selector ->
            val compound = selector.split(' ', '\t', '\n', '\r', '>', '+', '~')
                .lastOrNull { it.isNotEmpty() }
                .orEmpty()
            val name = compound.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            name.equals(element, ignoreCase = true)
        }

    /**
     * A declaration block as property/value pairs, property names lower-cased.
     *
     * A property declared twice keeps the later value, as CSS does within one block.
     */
    private fun declarationsIn(block: String): Map<String, String> {
        val declarations = LinkedHashMap<String, String>()
        for (declaration in splitTopLevel(block, ';')) {
            val colon = declaration.indexOf(':')
            if (colon <= 0) continue
            val value = declaration.substring(colon + 1).trim()
            if (value.isNotEmpty()) declarations[declaration.substring(0, colon).trim().lowercase()] = value
        }
        return declarations
    }

    /**
     * Splits [text] on [delimiter], ignoring delimiters inside quotes or parentheses.
     *
     * One splitter serves selector lists and declaration blocks because both need the same care: a
     * `content: "a;b"` is one declaration, and a `url(data:font/woff2;base64,…)` is one value even
     * though it contains the delimiter, a comma and a colon of its own.
     */
    private fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (char in text) {
            val open = quote
            if (open != null) {
                if (char == open) quote = null
            } else {
                when {
                    char == '"' || char == '\'' -> quote = char
                    char == '(' -> depth++
                    char == ')' -> if (depth > 0) depth--
                    char == delimiter && depth == 0 -> {
                        parts += current.toString()
                        current.setLength(0)
                        continue
                    }
                }
            }
            current.append(char)
        }
        parts += current.toString()
        return parts
    }

    /** The at-rule's name, lower-cased: `@font-face` for `@font-face {`, `@import` for `@import url(x);`. */
    private fun atKeyword(prelude: String): String {
        val end = prelude.indexOfFirst { it.isWhitespace() || it == '(' }
        return (if (end < 0) prelude else prelude.substring(0, end)).lowercase()
    }

    /** The first whitespace-separated token of [value], or `""` when it has none. */
    private fun firstToken(value: String?): String =
        value.orEmpty().trim().split(' ', '\t', '\n', '\r').firstOrNull { it.isNotEmpty() }.orEmpty()

    /** [value] without the surrounding quotes CSS allows around a family name or a url. */
    private fun unquote(value: String): String {
        val trimmed = value.trim()
        val first = trimmed.firstOrNull() ?: return ""
        val last = trimmed.last()
        val quoted = (first == '"' && last == '"') || (first == '\'' && last == '\'')
        return if (trimmed.length >= 2 && quoted) trimmed.substring(1, trimmed.length - 1).trim() else trimmed
    }

    /** [value] without a trailing `!important`, which modifies a declaration rather than being its value. */
    private fun withoutImportant(value: String): String = value.replace(IMPORTANT, "").trim()

    /** [css] without its comments. An unterminated comment swallows the rest, as it does in a browser. */
    private fun stripComments(css: String): String {
        if (!css.contains("/*")) return css
        val out = StringBuilder(css.length)
        var index = 0
        while (index < css.length) {
            val open = css.indexOf("/*", index)
            if (open < 0) {
                out.append(css, index, css.length)
                break
            }
            out.append(css, index, open)
            val close = css.indexOf("*/", open + 2)
            if (close < 0) break
            index = close + 2
        }
        return out.toString()
    }

    /** The index of the `}` matching the `{` at [open], or `null` when the block is never closed. */
    private fun matchingBrace(css: String, open: Int): Int? {
        var depth = 0
        for (index in open until css.length) {
            when (css[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private const val FONT_FACE = "@font-face"
    private const val IMPORT = "@import"
    private const val URL_PREFIX = "url("
    private const val NORMAL_WEIGHT = 400
    private const val BOLD_WEIGHT = 700
    private const val MIN_WEIGHT = 100
    private const val MAX_WEIGHT = 900

    private val IMPORTANT = Regex("""\s*!\s*important\s*$""", RegexOption.IGNORE_CASE)
}
