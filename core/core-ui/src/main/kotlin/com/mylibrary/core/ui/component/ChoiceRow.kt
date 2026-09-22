package com.mylibrary.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mylibrary.core.ui.theme.Spacing

/**
 * One choice out of a small fixed set — the control every settings screen in the app is built from.
 *
 * **Why this exists.** The app used `SingleChoiceSegmentedButtonRow` everywhere, and that component
 * gives every option an equal share of one line and never scrolls: a label that does not fit is
 * wrapped to two lines (so the row stops reading as a control) or clipped. That is not a
 * hypothetical — it is what happens to *English* labels, which are consistently longer than their
 * Arabic counterparts: "System language" (15 characters) where Arabic has لغة النظام (10),
 * "Left to right" where Arabic has من اليسار, "Sans serif" in a row of four. The Arabic interface
 * looked right and the English one was cramped, which is exactly backwards from what a screenshot
 * review catches.
 *
 * So the shape of the control is derived from the number of options rather than assumed:
 *
 *  - **Two options** share the width as a segmented row. Two labels always fit a phone, and for a
 *    binary choice the joined-halves shape is the clearest thing Material 3 has.
 *  - **Three or more** become a horizontally scrolling row of chips. Nothing is clipped at any
 *    length in either language, the selected option is just as legible, and the row costs one swipe
 *    in the rare case a language's labels overflow — which is a far better failure than a truncated
 *    word.
 *
 * This replaces two independent implementations of the same control (one in the settings screen,
 * one in the reader's panel) that had already drifted apart in padding and label style.
 *
 * @param label the content of each option. A slot rather than a `String` so a caller can set the
 *   text in the typeface the option *is* — which is how the font picker shows what it is offering.
 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    // labelLarge for both shapes: it is what a segmented button uses, so a two-option row and a
    // five-option row of the same setting do not change size when an option is added.
    ProvideTextStyle(value = MaterialTheme.typography.labelLarge) {
        if (options.size <= MAX_SEGMENTED_OPTIONS) {
            val layoutDirection = LocalLayoutDirection.current
            SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        // Resolved here rather than through `SegmentedButtonDefaults.itemShape`.
                        // That reads the base shape's start/end corners and mirrors them for the
                        // layout direction, and on this Material version the mirror is applied to
                        // one side of the control but not the other: the selected fill is drawn
                        // with the corners of the opposite direction, so in the English interface
                        // the selected half's rounding was reversed — flat where the outline is
                        // rounded and rounded where it is flat. Building the shape from the row's
                        // own direction makes the fill and the outline agree, in both languages.
                        shape = segmentedItemShape(
                            index = index,
                            count = options.size,
                            base = SegmentedButtonDefaults.baseShape,
                            layoutDirection = layoutDirection,
                        ),
                        label = { label(option) },
                    )
                }
            }
        } else {
            LazyRow(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                // No content padding: the row lives inside a card or a sheet that already insets
                // its content, and adding more here is how the chips stopped lining up with the
                // title above them.
            ) {
                items(items = options) { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        label = { label(option) },
                    )
                }
            }
        }
    }
}

/**
 * The shape of one button in a segmented row, with the row's own layout direction applied.
 *
 * The two outer buttons keep the base shape's rounded corners on the *outside* and are squared off
 * at the join; anything strictly between two neighbours is a plain rectangle.
 *
 * **The mirroring is done here, and it is why the parameter exists.** `RoundedCornerShape` keeps
 * `start`/`end` semantics and swaps them when it builds an outline for a right-to-left layout, so a
 * shape with the rounding on `topStart` lands on the left in an English row and on the right in an
 * Arabic one — which is right for the container, and was wrong for the selected fill: the fill was
 * resolved in the opposite direction to the outline, so in the English interface the selected half
 * was rounded at the join and square on the outside, the reverse of the shape around it. Choosing
 * the corner by the row's direction, rather than by name, makes the two agree: the rounding is
 * always placed on the outside of the row and squared at the join, in both languages.
 *
 * A middle button is a plain rectangle, which the same "outside only" rule produces on its own —
 * neither edge of it is an outside one.
 */
internal fun segmentedItemShape(
    index: Int,
    count: Int,
    base: CornerBasedShape,
    layoutDirection: LayoutDirection,
): CornerBasedShape {
    if (count <= 1) return base

    val roundedStart = index == 0
    val roundedEnd = index == count - 1
    val corner = base.topStart
    val square = CornerSize(0.dp)

    // `base.copy` preserves whatever corner type the palette's shape uses, which a fresh
    // `RoundedCornerShape` would not: an absolute cut or rounded corner shape survives.
    return when (layoutDirection) {
        LayoutDirection.Ltr -> base.copy(
            topStart = if (roundedStart) corner else square,
            bottomStart = if (roundedStart) corner else square,
            topEnd = if (roundedEnd) corner else square,
            bottomEnd = if (roundedEnd) corner else square,
        )

        // Mirrored: the row's *first* button is on the right, so its rounding is named `End`. When
        // the shape is resolved for an RTL row those corners are drawn on the right — and they are
        // resolved for the row's direction rather than the shape's own, which is what stops the
        // fill from taking the opposite side to the outline.
        LayoutDirection.Rtl -> base.copy(
            topStart = if (roundedEnd) corner else square,
            bottomStart = if (roundedEnd) corner else square,
            topEnd = if (roundedStart) corner else square,
            bottomEnd = if (roundedStart) corner else square,
        )
    }
}

/**
 * Two options, and only two, are laid out side by side.
 *
 * Three labels in a third of a phone's width is where English started being clipped; the chips
 * above are the answer to that, not a wider segmented row.
 */
private const val MAX_SEGMENTED_OPTIONS = 2

