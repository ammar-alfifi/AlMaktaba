package com.mylibrary.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

/**
 * The reading surface as a screen reader sees it.
 *
 * The reader's page turns are gestures on a surface, which a screen reader cannot perform by
 * touching the screen — so the same three things the tap zones do are offered as accessibility
 * actions instead: a semantic click reveals the controls (what the middle tap does), and two custom
 * actions turn the page. Turning is expressed as the previous and next *unit*, the reader's own
 * vocabulary, rather than by replicating the pager's entry arithmetic: a unit always exists to move
 * to, which is the honest action to offer when the reader cannot see the page under the finger.
 *
 * Applied once, around the whole reading surface, rather than on each page: one node is one thing
 * for a screen reader to land on, and per-page nodes would multiply with every page the wrapper
 * pre-measures.
 */
@Composable
internal fun Modifier.readerAccessibilityActions(
    onIntent: (ReaderIntent) -> Unit,
): Modifier {
    val previousLabel = stringResource(R.string.cd_previous_page)
    val nextLabel = stringResource(R.string.cd_next_page)
    val toggleLabel = stringResource(R.string.cd_toggle_controls)

    return semantics {
        onClick(label = toggleLabel) {
            onIntent(ReaderIntent.ToggleChrome)
            true
        }
        customActions = listOf(
            CustomAccessibilityAction(previousLabel) {
                onIntent(ReaderIntent.PreviousUnit)
                true
            },
            CustomAccessibilityAction(nextLabel) {
                onIntent(ReaderIntent.NextUnit)
                true
            },
        )
    }
}
