package com.mylibrary.format.epub

import com.mylibrary.core.domain.model.DocumentMetadata
import org.jsoup.nodes.Element

/** One `<item>` of the package document's manifest. */
internal data class ManifestItem(
    val id: String,
    /** The href exactly as the package document wrote it, which is what error messages quote. */
    val href: String,
    /** The archive path the href resolves to, or `null` when it escapes the archive root. */
    val path: String?,
    val mediaType: String?,
    val properties: Set<String>,
) {
    /** True for the EPUB 3 navigation document, the one marked `properties="nav"`. */
    val isNavigationDocument: Boolean get() = NAVIGATION_PROPERTY in properties

    /** True for the EPUB 2 NCX, identified by its media type. */
    val isNcx: Boolean get() = mediaType.equals(NCX_MEDIA_TYPE, ignoreCase = true)

    /**
     * True for a stylesheet the document carries.
     *
     * Matched on the media type, not on the `.css` extension: the manifest is what the document
     * declares and the extension is what its producer happened to name the file, and a book whose
     * fonts live in a stylesheet called `book.CSS` still needs its fonts found. This is the one place
     * the two disagree that costs something worth having.
     */
    val isStylesheet: Boolean get() = mediaType.equals(CSS_MEDIA_TYPE, ignoreCase = true)

    /**
     * True when the item is a document the reader can pull text out of.
     *
     * A denylist, not a list of blessed media types: a spine item with a missing or misspelled
     * `media-type` is far more common in the wild than one that is genuinely audio, video or a
     * stylesheet, and the cost of guessing wrong is an unreadable book. Images are excluded for a
     * different reason — this is a reflowable text engine, so a spine item that *is* an image
     * (`image/svg+xml` included, which is how fixed-layout comics are authored) would open as a
     * blank chapter and quietly break the promise that a chapter has text.
     */
    val isFlowable: Boolean
        get() {
            val type = mediaType?.trim()?.lowercase().orEmpty()
            if (type.isEmpty()) return true
            if (type == CSS_MEDIA_TYPE) return false
            return NON_FLOWABLE_PREFIXES.none { type.startsWith(it) }
        }

    private companion object {
        const val NAVIGATION_PROPERTY = "nav"
        const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
        const val CSS_MEDIA_TYPE = "text/css"
        val NON_FLOWABLE_PREFIXES = listOf("image/", "audio/", "video/", "font/")
    }
}

/** One `<itemref>` of the spine, with the manifest item it points at. */
internal data class SpineItem(val item: ManifestItem, val linear: Boolean)

/** Everything the reader needs out of the package document. */
internal data class EpubPackage(
    /** Archive path of the package document itself. */
    val opfPath: String,
    /** Directory the package document sits in; every manifest href is relative to it. */
    val opfDir: String,
    val metadata: DocumentMetadata,
    val manifest: Map<String, ManifestItem>,
    /** The reading order: resolved `<itemref>`s, in document order. */
    val spine: List<SpineItem>,
    /** Archive path of the EPUB 3 navigation document, when the book has one. */
    val navigationPath: String?,
    /** Archive path of the EPUB 2 NCX, when the book has one. */
    val ncxPath: String?,
    /**
     * CSS written inside a `<style>` element of the package document.
     *
     * Rare, and not how EPUB is meant to carry styling — a stylesheet belongs in the manifest, linked
     * from the documents that use it — but producers do emit it, and it is the one place a
     * `@font-face` can hide with no stylesheet item in the manifest to find it through.
     */
    val inlineStyles: List<String>,
)

/** Reads `META-INF/container.xml`, the fixed entry point of every EPUB. */
internal object EpubContainer {

    const val CONTAINER_PATH = "META-INF/container.xml"

    /**
     * The `full-path` of the first `<rootfile>`.
     *
     * A container may describe several renditions; the first is the one the publisher listed as
     * default, and picking it is what every reader does. The path is relative to the archive root
     * and may itself be percent-encoded.
     */
    fun rootfilePath(bytes: ByteArray): String? {
        val document = EpubXml.parseXml(bytes) ?: return null
        val rootfile = document.descendantsNamed("rootfile").firstOrNull() ?: return null
        val fullPath = rootfile.attrNamed("full-path").orEmpty().trim()
        if (fullPath.isEmpty()) return null
        return EpubPaths.normalise(EpubPaths.decodePercent(fullPath))
    }
}

/** Reads the OPF package document: metadata, manifest, spine and both navigation candidates. */
internal object OpfParser {

    fun parse(bytes: ByteArray, opfPath: String): EpubPackage? {
        val document = EpubXml.parseXml(bytes) ?: return null
        val packageElement = document.children().firstOrNull { it.localName().equals("package", ignoreCase = true) }
            ?: document.descendantsNamed("package").firstOrNull()
            ?: return null
        val opfDir = EpubPaths.directoryOf(opfPath)
        val manifest = parseManifest(packageElement, opfDir)
        // The NCX is normally identifiable by its media type alone. A few books ship it without one,
        // and then the spine's `toc` attribute names the same item — which is the reference the
        // specification calls authoritative anyway.
        val ncxById = packageElement.firstChildNamed("spine")
            ?.attrNamed("toc")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { manifest[it] }
        return EpubPackage(
            opfPath = opfPath,
            opfDir = opfDir,
            metadata = parseMetadata(packageElement),
            manifest = manifest,
            spine = parseSpine(packageElement, manifest),
            navigationPath = manifest.values.firstOrNull { it.isNavigationDocument }?.path,
            ncxPath = (manifest.values.firstOrNull { it.isNcx } ?: ncxById)?.path,
            // Every descendant, not just a direct child: producers put `<style>` in the places the
            // schema of their day allowed it, which was not always the same place.
            inlineStyles = packageElement.descendantsNamed("style")
                .mapNotNull { it.wholeText().trim().ifEmpty { null } },
        )
    }

    /**
     * The `dc:` elements the library shows on a book's card.
     *
     * Only the first of each is read. EPUB 3's refines machinery — `dc:title` variants tagged with
     * `title-type`, `dc:creator` elements with a `role` — exists to describe display choices the
     * reader does not make, and the unrefined element is the one the specification says to fall back
     * to regardless.
     */
    private fun parseMetadata(packageElement: Element): DocumentMetadata {
        val metadata = packageElement.firstChildNamed("metadata") ?: return DocumentMetadata()
        return DocumentMetadata(
            title = metadata.descendantsNamed("title").firstOrNull()?.textOrNull(),
            author = metadata.descendantsNamed("creator").firstOrNull()?.textOrNull(),
            language = metadata.descendantsNamed("language").firstOrNull()?.textOrNull(),
            publisher = metadata.descendantsNamed("publisher").firstOrNull()?.textOrNull(),
            description = metadata.descendantsNamed("description").firstOrNull()?.textOrNull(),
            identifier = metadata.descendantsNamed("identifier").firstOrNull()?.textOrNull(),
        )
    }

    private fun parseManifest(packageElement: Element, opfDir: String): Map<String, ManifestItem> {
        val manifestElement = packageElement.firstChildNamed("manifest") ?: return emptyMap()
        val manifest = LinkedHashMap<String, ManifestItem>()
        for (element in manifestElement.childrenNamed("item")) {
            val id = element.attrNamed("id").orEmpty().trim()
            val href = element.attrNamed("href").orEmpty().trim()
            if (id.isEmpty() || href.isEmpty()) continue
            manifest[id] = ManifestItem(
                id = id,
                href = href,
                path = EpubPaths.resolve(opfDir, href),
                mediaType = element.attrNamed("media-type")?.trim(),
                properties = element.attrNamed("properties")
                    .orEmpty()
                    .split(' ')
                    .filter { it.isNotEmpty() }
                    .toSet(),
            )
        }
        return manifest
    }

    /**
     * The spine, in reading order.
     *
     * `linear="no"` items are kept. The attribute marks documents outside the linear reading order —
     * a cover, a colophon, the notes a footnote link points at — but those documents exist, they are
     * frequently TOC targets, and dropping them would both lose reachable content and invalidate
     * every href-to-chapter mapping that names one. A book that marked its whole spine non-linear
     * would otherwise open as empty.
     */
    private fun parseSpine(packageElement: Element, manifest: Map<String, ManifestItem>): List<SpineItem> {
        val spineElement = packageElement.firstChildNamed("spine") ?: return emptyList()
        return spineElement.childrenNamed("itemref").mapNotNull { element ->
            val idref = element.attrNamed("idref").orEmpty().trim()
            // A dangling itemref is a broken manifest, not a broken book: skip it and keep reading.
            val item = manifest[idref] ?: return@mapNotNull null
            SpineItem(item = item, linear = !element.attrNamed("linear").equals("no", ignoreCase = true))
        }
    }
}
