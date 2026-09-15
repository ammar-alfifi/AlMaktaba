package com.mylibrary.format.text.internal

/** The writing direction of a piece of text, as the `dir` attribute an HTML renderer expects. */
internal enum class TextDirection(val htmlAttribute: String) {
    LTR("ltr"),
    RTL("rtl"),
}

/**
 * How much of each script that decides a text's direction a piece of text contains.
 *
 * Only three scripts are counted, because only three change an answer: a Latin book must read left
 * to right inside MyLibrary's Arabic interface, and an Arabic book must read right to left inside
 * an English one. Everything else — digits, punctuation, spaces, Cyrillic, CJK — reads the same way
 * in both and is deliberately ignored, so a book of mostly punctuation cannot outvote its own text.
 */
internal class ScriptCensus(
    val arabic: Int = 0,
    val hebrew: Int = 0,
    val latin: Int = 0,
) {
    /** Characters that read right to left. */
    val rightToLeft: Int get() = arabic + hebrew

    /**
     * The direction to lay this text out in.
     *
     * A tie falls to left to right, which is the default direction of the reader's HTML and of the
     * document itself; guessing RTL for a text that is half Latin would be the more disruptive
     * mistake, since RTL reorders punctuation and mixed runs.
     */
    val direction: TextDirection
        get() = if (rightToLeft > latin) TextDirection.RTL else TextDirection.LTR

    /**
     * A BCP-47 tag for the dominant script, or `null` when none clearly dominates.
     *
     * The tag is inferred, not declared — a TXT file carries no metadata — and it is a coarse one:
     * every Latin language reports `en`, because telling French from English would need statistics
     * this engine has no business carrying. It is a hint for the reader's default direction and font
     * stack, not a claim about the book, so `null` is the honest answer whenever the text is too
     * short, too mixed — or Hebrew, which has a direction but no tag this inference can stand behind.
     */
    val languageTag: String?
        get() = when {
            arabic > latin && arabic > hebrew -> "ar"
            latin > arabic && latin > hebrew -> "en"
            else -> null
        }

    operator fun plus(other: ScriptCensus): ScriptCensus = ScriptCensus(
        arabic = arabic + other.arabic,
        hebrew = hebrew + other.hebrew,
        latin = latin + other.latin,
    )
}

/**
 * Counts the direction-bearing scripts in `text[from, to)`.
 *
 * Characters are classified by Unicode script rather than by code-point range, so combining marks
 * (`INHERITED`), punctuation and the Arabic-Indic digits that Arabic books print are not mistaken
 * for letters of a script they do not belong to. Surrogate halves classify as `UNKNOWN` and are
 * skipped: the supplementary planes hold no characters a TXT book uses in practice, and treating a
 * lone surrogate as a letter would be worse than not counting it.
 */
internal fun census(text: CharSequence, from: Int = 0, to: Int = text.length): ScriptCensus {
    var arabic = 0
    var hebrew = 0
    var latin = 0
    for (index in from until to) {
        when (Character.UnicodeScript.of(text[index].code)) {
            Character.UnicodeScript.ARABIC -> arabic++
            Character.UnicodeScript.HEBREW -> hebrew++
            Character.UnicodeScript.LATIN -> latin++
            // Every other script — and every unassigned or surrogate code unit — reads the same way
            // left to right and right to left, so it cannot change the answer.
            else -> Unit
        }
    }
    return ScriptCensus(arabic, hebrew, latin)
}

/**
 * Counts scripts across a whole document, in windows spread through it rather than only at its head.
 *
 * Books do not all begin in their own language: an Arabic edition can open with an English title
 * page, a transliteration, or a translator's note, and a Hebrew book with a Latin colophon. Reading
 * only the first few kilobytes would let those decide the direction of the book. Eight windows of
 * eight kilobytes, spread evenly, cost the same as one 64 KB read and are far harder to fool.
 */
internal fun sampledCensus(text: String): ScriptCensus {
    if (text.length <= SAMPLE_WINDOWS * SAMPLE_WINDOW_CHARS) return census(text)

    val stride = text.length / SAMPLE_WINDOWS
    var total = ScriptCensus()
    for (window in 0 until SAMPLE_WINDOWS) {
        val from = window * stride
        total += census(text, from, (from + SAMPLE_WINDOW_CHARS).coerceAtMost(text.length))
    }
    return total
}

private const val SAMPLE_WINDOWS = 8
private const val SAMPLE_WINDOW_CHARS = 8 * 1024
