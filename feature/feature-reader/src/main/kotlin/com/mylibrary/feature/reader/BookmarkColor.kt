package com.mylibrary.feature.reader

/**
 * The colours a bookmark can be highlighted in.
 *
 * A highlight is a bookmark that carries a colour, which is why there is one palette here and not a
 * second "highlight" concept beside the bookmark. The four are chosen to sit legibly behind dark text
 * on a light page and to stay distinguishable from each other; a bookmark with no colour is the plain
 * bookmark the reader has always placed.
 *
 * The stored value is the ARGB integer itself rather than the enum's name, because that is what
 * `Bookmark.colorArgb` has always held — a bookmark made by an older build, or one whose colour is
 * no longer in this list, still renders in its own colour and simply matches no chip.
 */
internal enum class BookmarkColor(val argb: Int) {
    YELLOW(0xFFFFF176.toInt()),
    GREEN(0xFFA5D6A7.toInt()),
    BLUE(0xFF90CAF9.toInt()),
    PINK(0xFFF48FB1.toInt()),
    ;

    companion object {
        /** The palette entry matching a stored colour, or `null` for a plain bookmark. */
        fun of(argb: Int?): BookmarkColor? =
            argb?.let { value -> entries.firstOrNull { it.argb == value } }
    }
}
