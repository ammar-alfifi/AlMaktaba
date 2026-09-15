package com.mylibrary.core.domain.model

/**
 * The document formats MyLibrary can read.
 *
 * The two categories matter more than the individual formats:
 *
 *  - **Paged** documents ([PDF], [CBZ], [CBR]) have fixed pages with fixed dimensions. The reader
 *    can jump to page *n*, render it at any scale, and show a page slider.
 *  - **Reflowable** documents ([EPUB], [TXT]) have no pages at all until they are laid out for a
 *    particular screen and font size. The reader shows chapters and remembers a character offset,
 *    and re-flowing when the user changes the font size never loses the reading position.
 *
 * The UI branches on `isPaged` / `isReflowable` instead of on individual formats.
 */
enum class BookFormat(
    val fileExtension: String,
    val mimeTypes: List<String>,
    val displayName: String,
) {
    PDF(
        fileExtension = "pdf",
        mimeTypes = listOf("application/pdf"),
        displayName = "PDF",
    ),
    EPUB(
        fileExtension = "epub",
        mimeTypes = listOf("application/epub+zip"),
        displayName = "EPUB",
    ),
    TXT(
        fileExtension = "txt",
        mimeTypes = listOf("text/plain"),
        displayName = "TXT",
    ),
    CBZ(
        fileExtension = "cbz",
        mimeTypes = listOf(
            "application/x-cbz",
            "application/vnd.comicbook+zip",
        ),
        displayName = "CBZ",
    ),
    CBR(
        fileExtension = "cbr",
        mimeTypes = listOf(
            "application/x-cbr",
            "application/vnd.comicbook-rar",
        ),
        displayName = "CBR",
    ),
    ;

    /** True when the format has fixed pages of fixed size. */
    val isPaged: Boolean get() = this == PDF || this == CBZ || this == CBR

    /** True when the format re-flows to fit the screen and the chosen font size. */
    val isReflowable: Boolean get() = this == EPUB || this == TXT

    /** True when the format is a container of images rather than of text. */
    val isImageBased: Boolean get() = this == CBZ || this == CBR

    companion object {
        /**
         * Resolves a format from a file extension, ignoring case and a leading dot.
         *
         * Returns `null` for anything unsupported so callers can decide whether to skip the file
         * (during a folder import) or report it (when the user picked a single file).
         */
        fun fromExtension(extension: String?): BookFormat? {
            val normalized = extension?.removePrefix(".")?.lowercase()?.trim().orEmpty()
            return entries.firstOrNull { it.fileExtension == normalized }
        }

        /**
         * Resolves a format from a MIME type.
         *
         * Storage providers are inconsistent — a CBZ often arrives as a bare `application/zip` and
         * an EPUB occasionally as `application/octet-stream` — so the extension is the more
         * reliable signal and callers should prefer it, falling back to this.
         */
        fun fromMimeType(mimeType: String?): BookFormat? {
            val normalized = mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { format -> format.mimeTypes.any { it == normalized } }
        }

        /**
         * MIME types to hand the Storage Access Framework when asking the user to pick documents.
         *
         * `application/zip` and `application/octet-stream` are included because many file
         * providers tag comic archives and EPUBs with those generic types; without them those books
         * would be greyed out in the system picker.
         */
        val pickerMimeTypes: List<String> = buildList {
            entries.forEach { addAll(it.mimeTypes) }
            add("application/zip")
            add("application/octet-stream")
            add("application/x-rar-compressed")
        }
    }
}
