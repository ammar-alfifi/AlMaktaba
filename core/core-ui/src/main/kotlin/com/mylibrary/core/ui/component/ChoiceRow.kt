package com.mylibrary.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
            SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
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
 * Two options, and only two, are laid out side by side.
 *
 * Three labels in a third of a phone's width is where English started being clipped; the chips
 * above are the answer to that, not a wider segmented row.
 */
private const val MAX_SEGMENTED_OPTIONS = 2
