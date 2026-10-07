package com.mylibrary.core.common

import java.text.CollationKey
import java.text.Collator

/**
 * A sort key that orders names the way a phone's file manager does.
 *
 * Two things separate it from a plain string comparison, and both showed up as "the shelf's order is
 * not the folder's order":
 *
 *  - **Numbers compare as numbers.** `المجلد 2` precedes `المجلد 10`; a byte comparison puts the
 *    tenth volume second.
 *  - **Text compares with the locale's own collation.** A byte comparison of Arabic orders the alef
 *    variants (`ا أ إ آ`) by code point, and ignores where the language actually puts a hamza or a
 *    taa marbuta — so the shelf disagreed with the file manager the reader had just come from, on
 *    the very folder they were looking at. [Collator] is the comparison Android's own documents UI
 *    uses, at [Collator.SECONDARY] strength so case and the like do not split otherwise equal names.
 *
 * The key is built once per name and then compared, rather than re-tokenising the string on every
 * comparison of a sort.
 */
class NameOrderKey internal constructor(private val parts: List<Part>) : Comparable<NameOrderKey> {

    internal sealed interface Part : Comparable<Part> {
        /** A run of non-digits, compared by the locale's collation. */
        class Text(val key: CollationKey) : Part {
            override fun compareTo(other: Part): Int = when (other) {
                is Text -> key.compareTo(other.key)
                is Number -> 1
            }
        }

        /**
         * A run of digits, compared by value and then by how it was written, so `1` precedes `01`
         * but both precede `2`.
         */
        class Number(val value: Long, val digits: Int) : Part {
            override fun compareTo(other: Part): Int = when (other) {
                is Number ->
                    value.compareTo(other.value).takeIf { it != 0 } ?: digits.compareTo(other.digits)

                is Text -> -1
            }
        }
    }

    override fun compareTo(other: NameOrderKey): Int {
        val shared = minOf(parts.size, other.parts.size)
        for (index in 0 until shared) {
            val result = parts[index].compareTo(other.parts[index])
            if (result != 0) return result
        }
        // Everything shared is equal: the shorter name sorts first, so `Vol 2` precedes `Vol 2A`.
        return parts.size - other.parts.size
    }
}

/**
 * The collator a file manager compares names with.
 *
 * A fresh instance per sort, because [Collator] is not thread-safe and the library is ordered off
 * the main thread. The default locale is deliberate: it is the one the file manager used, and the
 * one the reader's device is set to.
 */
fun nameCollator(): Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

/**
 * The [NameOrderKey] of [value], built with [collator].
 *
 * [collator] is required rather than defaulted because two keys are only comparable when they came
 * from the same collator, and a default would quietly let a caller build them from two different
 * ones.
 */
fun nameOrderKey(value: String, collator: Collator): NameOrderKey {
    val parts = ArrayList<NameOrderKey.Part>()
    var index = 0
    while (index < value.length) {
        val isDigit = value[index].isDigit()
        var end = index
        while (end < value.length && value[end].isDigit() == isDigit) end++
        val run = value.substring(index, end)
        parts += if (isDigit) {
            NameOrderKey.Part.Number(value = digitValue(run), digits = run.length)
        } else {
            NameOrderKey.Part.Text(collator.getCollationKey(run))
        }
        index = end
    }
    return NameOrderKey(parts)
}

/**
 * A digit run as a number.
 *
 * Read digit by digit rather than through `toLong` so that a run written in Arabic-Indic numerals
 * (`٢`) orders by the same value as the same number written in ASCII. An absurdly long run
 * saturates rather than overflowing to a negative number that would sort first.
 */
private fun digitValue(run: String): Long {
    var value = 0L
    for (character in run) {
        val digit = Character.getNumericValue(character)
        if (digit < 0 || digit > 9) return Long.MAX_VALUE
        if (value > (Long.MAX_VALUE - digit) / 10) return Long.MAX_VALUE
        value = value * 10 + digit
    }
    return value
}
