package com.mylibrary.core.common

import java.util.Locale

/**
 * Splits a file name into a stem and a lowercase extension.
 *
 * Handles the awkward real-world cases: no extension, a leading-dot name such as `.hidden`,
 * multiple dots (`book.epub.bak`) and a trailing dot.
 */
fun fileNameParts(fileName: String): Pair<String, String> {
    val trimmed = fileName.substringAfterLast('/').substringAfterLast('\\')
    val lastDot = trimmed.lastIndexOf('.')
    return when {
        lastDot <= 0 || lastDot == trimmed.length - 1 -> trimmed to ""
        else -> trimmed.substring(0, lastDot) to trimmed.substring(lastDot + 1).lowercase(Locale.ROOT)
    }
}

/** The display name without its extension, falling back to the whole name. */
fun fileStem(fileName: String): String = fileNameParts(fileName).first.ifBlank { fileName }

/**
 * A sort key that orders embedded numbers numerically rather than lexicographically.
 *
 * Comic archives name pages `page2.png`, `page10.png`; a plain string sort would put page 10 before
 * page 2 and scramble the reading order. This key makes `page2` sort before `page10`.
 *
 * Digits are compared as numbers, and text runs are compared case-insensitively, so the function is
 * a total order suitable for `sortedWith(compareBy { naturalSortKey(it) })`.
 */
fun naturalSortKey(value: String): String = buildString(value.length + 8) {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char.isDigit()) {
            var end = index
            while (end < value.length && value[end].isDigit()) end++
            val digits = value.substring(index, end).trimStart('0')
            // Left-pad so that a shorter number still compares less than a longer one, and prefix
            // with a marker that sorts digit runs before letters.
            append('')
            append("0".repeat((12 - digits.length).coerceAtLeast(0)))
            append(digits.ifEmpty { "0" })
            index = end
        } else {
            append(char.lowercaseChar())
            index++
        }
    }
}

/** Formats a byte count for display, e.g. `1.4 MB`. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    // Whole numbers read better without a trailing ".0".
    val rounded = kotlin.math.round(value * 10) / 10
    val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    return "$text ${units[unitIndex]}"
}
