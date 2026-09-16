package com.mylibrary.core.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The app's spacing scale.
 *
 * Every screen used to write its own numbers — 16 here, 20 in the reader's sheet, 32 in an empty
 * state — so the same concept (the gap between a card's edge and its text) came out at three
 * different widths depending on which file you were in. Naming the steps is what stops that: a
 * screen that needs "the usual padding" has somewhere to point, and a reviewer can see that two
 * rows disagree instead of having to measure them.
 *
 * A four-pixel base rather than Material's eight: the app is Arabic-first, and Arabic stacked
 * labels and diacritics need a step finer than 8dp to group a caption with its field without
 * appearing to belong to the one above.
 */
object Spacing {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val XLarge = 24.dp
    val Huge = 32.dp

    /**
     * How much room a scrolling list must leave at its end when a FAB floats over it.
     *
     * The FAB is 56dp tall and sits 16dp from the bottom edge, so a list whose last item is padded
     * by anything less than this has its final row — a book, in this app — sitting underneath it.
     */
    val FabClearance = 96.dp

    /**
     * The widest a column of text is allowed to grow.
     *
     * Line length is a reading property, not a window property: past roughly this width the eye
     * loses the start of the next line on the way back. On a tablet or a desktop-sized window a
     * paragraph set edge to edge is measurably harder to read than the same paragraph in a column.
     */
    val MaxContentWidth = 560.dp
}
