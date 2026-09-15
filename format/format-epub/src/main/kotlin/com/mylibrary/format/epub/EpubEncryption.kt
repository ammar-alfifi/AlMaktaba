package com.mylibrary.format.epub

/**
 * Decides whether `META-INF/encryption.xml` means the book is locked.
 *
 * The presence of that file is *not* the same question as DRM, which is why this is not a one-line
 * `containsKey` check. Two of the algorithms the format defines encrypt nothing but the embedded
 * fonts: IDPF's obfuscation, and Adobe's older equivalent. They ship in a large share of perfectly
 * readable commercial books, as a licensing measure — the publisher's font may only be used by *this*
 * book — and refusing to open those books would be a bug users would notice immediately.
 *
 * So the file is scanned for what it actually encrypts: any `EncryptedData` whose algorithm is not
 * one of those two means real content is locked, and the caller reports [com.mylibrary.core.common.AppError.Protected].
 * An `encryption.xml` that cannot be parsed is treated as protecting the book — a false "this book is
 * protected" is recoverable (the user can see it is not), while a false "here is a book" hands the
 * reader a chapter of binary noise.
 */
internal object EpubEncryption {

    /** IDPF's font obfuscation: the key is the book's identifier, and only the fonts are scrambled. */
    private const val IDPF_FONT_ALGORITHM = "http://www.idpf.org/2008/embedding"

    /** Adobe's equivalent, used by books produced for Digital Editions. */
    private const val ADOBE_FONT_ALGORITHM = "http://ns.adobe.com/pdf/enc#RC"

    private val FONT_ONLY_ALGORITHMS = setOf(IDPF_FONT_ALGORITHM, ADOBE_FONT_ALGORITHM)

    fun isProtected(bytes: ByteArray): Boolean {
        val document = EpubXml.parseXml(bytes) ?: return true
        val encryptedData = document.descendantsNamed("EncryptedData")
        if (encryptedData.isEmpty()) {
            // An `<encryption/>` placeholder, or a file that names nothing: nothing is encrypted.
            return false
        }
        return encryptedData.any { data ->
            val algorithm = data.descendantsNamed("EncryptionMethod")
                .firstOrNull()
                ?.attrNamed("Algorithm")
                ?.trim()
            // No declared algorithm at all: assume the content is locked.
            algorithm == null || algorithm !in FONT_ONLY_ALGORITHMS
        }
    }
}
