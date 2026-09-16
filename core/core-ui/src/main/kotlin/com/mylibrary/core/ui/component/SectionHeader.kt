package com.mylibrary.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mylibrary.core.ui.theme.Spacing

/**
 * The title above a group of related rows.
 *
 * Three screens had grown three versions of this: the settings screen used a small primary-coloured
 * label indented to clear the card beneath it, the search screen used the same style with different
 * padding, and the book details screen used a medium title in the default colour with none. Which
 * one a section got depended on which file it was written in, so the same visual rank read as
 * three different ranks.
 *
 * This is the settings screen's version, which is the one that survived contact with a card: the
 * text is a *label* for the group rather than a heading of its own, so it takes the primary colour
 * and sits outside the card's surface instead of on it.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(
            start = Spacing.Large,
            end = Spacing.Large,
            bottom = Spacing.Small,
        ),
    )
}

/**
 * A titled group: [SectionHeader] followed by the rows it introduces.
 *
 * A header outside the content rather than as its first row: a card is only as tall as its rows, so
 * a header inside one would drag the card's background behind it and turn a section separator into
 * something that looks like a setting the user can press.
 */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        SectionHeader(title)
        content()
    }
}
