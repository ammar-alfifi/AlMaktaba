package com.mylibrary.format.text.internal

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.mozilla.universalchardet.UniversalDetector

/**
 * The charset a document will be decoded with, and how many leading bytes to drop before decoding.
 *
 * [bomLength] is non-zero only for the byte-order marks we recognise ourselves: a `UTF-16` decoder
 * would consume its own mark, but the explicit `UTF-16LE` / `UTF-16BE` and `UTF-8` decoders used
 * here would turn it into a stray zero-width character at the top of every chapter.
 */
internal data class DetectedCharset(val charset: Charset, val bomLength: Int)

/**
 * Picks the charset for a plain-text file from a sample of its first bytes.
 *
 * The ladder, in order, and why each rung is where it is:
 *
 *  1. **Byte-order marks.** A BOM is the only unambiguous statement a file can make about its own
 *     encoding, so it wins over everything else. UTF-16LE with a BOM is one of the two shapes
 *     Arabic TXT files most often arrive in.
 *  2. **BOM-less UTF-16.** Windows tools that write UTF-16 usually write a BOM, but not always; the
 *     byte-parity signature (every other byte is a control byte that could only be the high half of
 *     a low BMP code point) is unmistakable enough to act on and cannot be produced by any
 *     single-byte or UTF-8 text.
 *  3. **Valid UTF-8.** A *positive* test rather than a guess, which is how this engine implements
 *     "prefer UTF-8 for tiny files": an ASCII or UTF-8 file of any size — including a 20-byte one —
 *     is recognised here and never reaches the detector, so the detector's unreliability on short
 *     samples cannot hurt it.
 *  4. **[looksLikeWindows1256Arabic].** `juniversalchardet` 1.0.3 ships sequence models for
 *     Cyrillic, Greek, Hebrew, Thai and the CJK families but **none for Arabic**, so Windows-1256
 *     Arabic is not merely missed — it is confidently mis-read as a *Cyrillic* page (measured:
 *     `MACCYRILLIC` for real Arabic prose, at every sample size from 32 bytes up). Arabic is the
 *     case this module exists for, so it gets a hand-written test of its own instead of the library.
 *  5. **`UniversalDetector`**, still the best answer for the Latin, Cyrillic, Greek, Hebrew and CJK
 *     books that are not Arabic.
 *  6. **UTF-8 as the last resort**, per the storage-layer convention that undecodable bytes should
 *     degrade to replacement characters rather than fail the open.
 *
 * The detector is skipped entirely for a sample below [TINY_SAMPLE_BYTES]: on a handful of bytes it
 * guesses (its confidence threshold is low by design), and a wrong guess produces silent mojibake
 * where the UTF-8 fallback at least produces visible replacement characters. The Arabic test in
 * step 4 is not a guess — it is a byte-distribution claim that no Latin, Cyrillic, Greek, Hebrew or
 * Thai sample measured for it came close to satisfying — so it is allowed to speak for tiny files.
 */
internal object TextCharsetDetector {

    /** How many leading bytes are worth sniffing. Beyond this the distribution stops changing. */
    const val SAMPLE_BYTES: Int = 64 * 1024

    /** Samples shorter than this came from a file this small, where detection is unreliable. */
    private const val TINY_SAMPLE_BYTES: Int = 512

    /**
     * Windows-1256, the code page Arabic Windows has written since the 1990s.
     *
     * Preferred over ISO-8859-6 because it also covers the Persian letters and the Arabic comma and
     * question mark that real Arabic prose uses; the two agree on the letter range that matters.
     * Resolved defensively: were a runtime to lack it, ISO-8859-6 is the closest relative and
     * ISO-8859-1 the never-fails placeholder, and an approximate decode beats a failed open.
     */
    private val WINDOWS_1256: Charset =
        charsetOrNull("windows-1256") ?: charsetOrNull("ISO-8859-6") ?: StandardCharsets.ISO_8859_1

    /**
     * Single-byte charsets whose 0x80..0xFF range overlaps Windows-1256's often enough to be a
     * plausible mis-reading of the same bytes.
     *
     * Only these may be overruled by the Arabic test. A multi-byte verdict — UTF-8, UTF-16, the CJK
     * families, ISO-2022 — is never overruled, because those encodings use the same bytes in
     * structurally different ways and a byte-frequency argument cannot speak for them.
     *
     * Names are written the way a charset is normally spelled and reduced by [normalizeCharsetName]
     * before the lookup. A charset the library and the platform spell differently — `MACCYRILLIC`
     * against `x-MacCyrillic` — appears under both, since those differ by more than punctuation.
     */
    private val OVERRULABLE_BY_ARABIC: Set<String> = hashSetOf(
        // Cyrillic pages: 0xC0..0xDF is uppercase, which real Arabic uses for its most common letters.
        // Mac Cyrillic is the one juniversalchardet actually names for Windows-1256 Arabic, and it is
        // listed under both spellings: the library answers "MACCYRILLIC", the JDK canonicalises that
        // charset to "x-MacCyrillic", and the two are different strings even after normalisation.
        "WINDOWS-1251", "KOI8-R", "KOI8-U", "ISO-8859-5", "IBM866", "IBM855",
        "MACCYRILLIC", "X-MAC-CYRILLIC",
        // Greek pages: same uppercase-vs-common-letter split.
        "WINDOWS-1253", "ISO-8859-7",
        // Hebrew pages: Hebrew letters live at 0xE0..0xFA, overlapping Arabic's second letter range.
        "WINDOWS-1255", "ISO-8859-8",
        // Latin, Turkish, Baltic and Thai pages, whose high half is entirely letters too.
        "WINDOWS-1250", "WINDOWS-1252", "WINDOWS-1254", "WINDOWS-1257", "WINDOWS-1258",
        "ISO-8859-1", "ISO-8859-2", "ISO-8859-3", "ISO-8859-4", "ISO-8859-9", "ISO-8859-13",
        "ISO-8859-14", "ISO-8859-15", "ISO-8859-16",
        "TIS-620", "WINDOWS-874", "ISO-8859-11",
    ).mapTo(hashSetOf(), ::normalizeCharsetName)

    // The two halves of the high byte range that the Arabic test measures. In Windows-1256 the first
    // holds alif .. ghain (0xC1..0xDA) and most of the second holds tatweel and lam .. yeh; in the
    // Cyrillic and Greek pages the first holds the upper case alphabet.
    private const val HIGH_BAND_START = 0xC0
    private const val HIGH_BAND_END = 0xDF
    private const val LOW_BAND_START = 0xE0
    private const val LOW_BAND_END = 0xFF

    // The two most frequent letters in Arabic, alif (0xC7) and lam (0xE1).
    private const val ALIF = 0xC7
    private const val LAM = 0xE1

    /**
     * Thresholds for [looksLikeWindows1256Arabic], as whole percentages so the comparisons below stay
     * in integer arithmetic — with a byte sample there is no precision to gain from a `Double`, only a
     * rounding rule to reason about.
     *
     * Measured on real prose samples of about a kilobyte: Arabic in Windows-1256 scores 32%..59% on
     * the high band and 14%..34% on alif and lam together, while Russian and Ukrainian in
     * Windows-1251, Greek in 1253, Hebrew in 1255, Turkish in 1254 and Spanish, German and French in
     * 1252 stay at or below 8% and 12% — the bands and the thresholds are an order of magnitude
     * apart, not a close call.
     */
    private const val HIGH_BAND_PERCENT = 30
    private const val LOW_BAND_PERCENT = 20
    private const val ALIF_LAM_PERCENT = 8
    private const val MIN_HIGH_BYTES = 8

    fun detect(sample: ByteArray, length: Int = sample.size): DetectedCharset {
        detectBom(sample, length)?.let { return it }
        detectUtf16WithoutBom(sample, length)?.let { return it }

        if (isValidUtf8(sample, 0, length)) {
            return DetectedCharset(StandardCharsets.UTF_8, bomLength = 0)
        }

        val detected = if (length >= TINY_SAMPLE_BYTES) detectWithUniversalDetector(sample, length) else null
        val arabic = looksLikeWindows1256Arabic(sample, length)

        val charset = when {
            detected == null -> if (arabic) WINDOWS_1256 else StandardCharsets.UTF_8
            arabic && isOverrulableByArabic(detected) -> WINDOWS_1256
            else -> detected
        }
        return DetectedCharset(charset, bomLength = 0)
    }

    /**
     * Recognises the three byte-order marks the reader cares about, returning the charset to use and
     * the mark's length so the caller can skip it.
     *
     * A `FF FE` prefix is UTF-16LE; `FE FF` is UTF-16BE. UTF-32LE also starts with `FF FE`, but it is
     * refused by the `00 00` that follows, which no UTF-16 text can have as its first character.
     */
    private fun detectBom(sample: ByteArray, length: Int): DetectedCharset? = when {
        length >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte() ->
            DetectedCharset(StandardCharsets.UTF_8, bomLength = 3)

        length >= 4 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte() &&
            sample[2] == 0x00.toByte() && sample[3] == 0x00.toByte() -> null

        length >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte() ->
            DetectedCharset(StandardCharsets.UTF_16LE, bomLength = 2)

        length >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte() ->
            DetectedCharset(StandardCharsets.UTF_16BE, bomLength = 2)

        else -> null
    }

    /**
     * Guesses UTF-16 for a BOM-less file from the shape of its bytes.
     *
     * In UTF-16 every other byte is the high half of a character. For text that lives below U+1000 —
     * Latin, Greek, Cyrillic, Hebrew, Arabic — that half is always a byte that never occurs in text
     * on its own (0x00, or 0x01..0x0F, of which only the tabs and line breaks that a text file
     * legitimately contains are excluded here). Requiring seven in ten of one parity to be such a
     * byte, and at most two in ten of the other parity, cannot be satisfied by any single-byte or
     * UTF-8 text: it would take a file made of control characters.
     *
     * The known blind spot is UTF-16 text entirely above U+0FFF — CJK, mainly — whose high halves
     * are ordinary byte values. Those files still reach [UniversalDetector], which is no worse
     * placed to read them than this test is.
     */
    private fun detectUtf16WithoutBom(sample: ByteArray, length: Int): DetectedCharset? {
        if (length < MIN_UTF16_SAMPLE_BYTES) return null

        var evenHighHalf = 0
        var oddHighHalf = 0
        for (index in 0 until length) {
            if (isUtf16HighHalf(sample[index].toInt() and 0xFF)) {
                if (index % 2 == 0) evenHighHalf++ else oddHighHalf++
            }
        }
        val evenCount = (length + 1) / 2
        val oddCount = length / 2

        // Odd positions are the high half in little-endian, even positions in big-endian.
        return when {
            oddHighHalf * 10 >= oddCount * 7 && evenHighHalf * 10 <= evenCount * 2 ->
                DetectedCharset(StandardCharsets.UTF_16LE, bomLength = 0)

            evenHighHalf * 10 >= evenCount * 7 && oddHighHalf * 10 <= oddCount * 2 ->
                DetectedCharset(StandardCharsets.UTF_16BE, bomLength = 0)

            else -> null
        }
    }

    /**
     * True for a byte that could be the high half of a UTF-16 code point below U+1000.
     *
     * Tab, line feed, vertical tab, form feed and carriage return are excluded: a text file contains
     * them as text, so counting them would let a file of one-character lines masquerade as UTF-16.
     */
    private fun isUtf16HighHalf(byte: Int): Boolean =
        byte <= 0x08 || byte == 0x0E || byte == 0x0F

    /**
     * Validates UTF-8 strictly — malformed sequences, overlong encodings, surrogates and code points
     * above U+10FFFF all fail. A truncated sequence at the very end of the sample is accepted, since
     * the sample is a prefix of a file that may continue.
     */
    private fun isValidUtf8(bytes: ByteArray, from: Int, to: Int): Boolean {
        var index = from
        while (index < to) {
            val lead = bytes[index].toInt() and 0xFF
            val continuations = when (lead) {
                in 0x00..0x7F -> 0
                in 0xC2..0xDF -> 1
                in 0xE0..0xEF -> 2
                in 0xF0..0xF4 -> 3
                // 0x80..0xC1 are continuations or overlong leads; 0xF5..0xFF exceed U+10FFFF.
                else -> return false
            }
            if (index + continuations >= to) return true
            for (offset in 1..continuations) {
                if (bytes[index + offset].toInt() and 0xC0 != 0x80) return false
            }
            // Ranges the continuation check alone would let through.
            when {
                lead == 0xE0 && (bytes[index + 1].toInt() and 0xFF) < 0xA0 -> return false
                lead == 0xED && (bytes[index + 1].toInt() and 0xFF) > 0x9F -> return false
                lead == 0xF0 && (bytes[index + 1].toInt() and 0xFF) < 0x90 -> return false
                lead == 0xF4 && (bytes[index + 1].toInt() and 0xFF) > 0x8F -> return false
            }
            index += continuations + 1
        }
        return true
    }

    /** Runs `juniversalchardet` over the sample, or returns `null` when it declines to answer. */
    private fun detectWithUniversalDetector(sample: ByteArray, length: Int): Charset? {
        val detector = UniversalDetector(null)
        detector.handleData(sample, 0, length)
        detector.dataEnd()
        val name = detector.getDetectedCharset() ?: return null
        return charsetOrNull(name)
    }

    /**
     * True when a single-byte detection may be overruled by [looksLikeWindows1256Arabic].
     *
     * The name is normalised through the same function the set was built with, because a charset
     * reaches this module in more than one spelling: the library answers `MACCYRILLIC` and the
     * platform canonicalises the same charset to `x-MacCyrillic`.
     */
    private fun isOverrulableByArabic(detected: Charset): Boolean =
        normalizeCharsetName(detected.name()) in OVERRULABLE_BY_ARABIC

    /**
     * Reduces a charset name to the form [OVERRULABLE_BY_ARABIC] is stored in: upper case, separators
     * removed.
     *
     * Dropping the hyphens makes `x-MacCyrillic` and `X-MAC-CYRILLIC` one string, so the set does not
     * have to guess which of a charset's separator spellings a runtime will answer with. It does not
     * merge names that differ in their letters — `MACCYRILLIC` is not `X-MACCYRILLIC` — so a charset
     * the library and the platform genuinely name differently is listed under both.
     */
    private fun normalizeCharsetName(name: String): String =
        name.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /**
     * Decides whether a sample is Windows-1256 Arabic rather than one of the legacy charsets whose
     * byte ranges it shares.
     *
     * The claim rests on one asymmetry between the two scripts' code pages:
     *
     *  - In Windows-1256, `0xC1..0xDA` holds alif, lam, meem, noon and the rest of the letters that
     *    make up the bulk of any Arabic sentence.
     *  - In the Cyrillic and Greek pages that cover the same bytes, `0xC0..0xDF` holds *upper case*
     *    letters, which running prose uses for a few percent of its letters at most.
     *
     * So a sample whose high bytes cluster in `0xC0..0xDF` is not Cyrillic prose, whatever the
     * detector says. [LOW_BAND_PERCENT] rules out the mirror-image mistake (all-caps Cyrillic text,
     * which would otherwise look Arabic because every one of its bytes sits in that band) and
     * [ALIF_LAM_PERCENT] rules out the CJK and Thai pages, where bytes are spread evenly enough that
     * alif and lam are just two of a hundred lead bytes rather than a fifth of the text.
     */
    private fun looksLikeWindows1256Arabic(sample: ByteArray, length: Int): Boolean {
        var highBytes = 0
        var highBand = 0
        var lowBand = 0
        var alifLam = 0
        for (index in 0 until length) {
            val byte = sample[index].toInt() and 0xFF
            if (byte < 0x80) continue
            highBytes++
            when {
                byte in HIGH_BAND_START..HIGH_BAND_END -> highBand++
                byte in LOW_BAND_START..LOW_BAND_END -> lowBand++
            }
            if (byte == ALIF || byte == LAM) alifLam++
            // The middle ranges (0xA0..0xBF) are punctuation in either reading and carry no signal.
        }
        if (highBytes < MIN_HIGH_BYTES) return false
        if (highBand < highBytes * HIGH_BAND_PERCENT / 100) return false
        if (lowBand < highBytes * LOW_BAND_PERCENT / 100) return false
        return alifLam >= highBytes * ALIF_LAM_PERCENT / 100
    }

    /** [Charset.forName] for a name we did not choose, which may be unknown or malformed. */
    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (unsupported: IllegalArgumentException) {
        // UnsupportedCharsetException and IllegalCharsetNameException both land here.
        null
    }

    /** A sample shorter than this cannot show a parity pattern worth acting on. */
    private const val MIN_UTF16_SAMPLE_BYTES = 16
}
