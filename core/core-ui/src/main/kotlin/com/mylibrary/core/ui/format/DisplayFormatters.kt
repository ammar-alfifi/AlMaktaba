package com.mylibrary.core.ui.format

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mylibrary.core.common.formatBytes
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.LibraryItem
import com.mylibrary.core.ui.R

/**
 * User-facing formatting for book metadata and progress.
 *
 * Kept in `:core:core-ui` rather than in the domain so that every screen renders a book the same
 * way. All of it is locale-aware: [relativeTime] delegates to `DateUtils`, which produces
 * "قبل ٣ أيام" under an Arabic locale and "3 days ago" under an English one without this file
 * containing a single translated string.
 */

/** "3 days ago" / "قبل ٣ أيام", relative to now. */
@Composable
fun relativeTime(epochMillis: Long): String {
    val context = LocalContext.current
    return DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

/**
 * The progress caption under a book: "Page 12 of 300", a percentage, or "Not started".
 *
 * A finished book says so explicitly instead of showing 100%, because "100%" reads as a progress
 * bar that happens to be full while "Finished" reads as a state the user has completed.
 */
@Composable
fun progressLabel(item: LibraryItem): String {
    val position = item.position ?: return stringResource(R.string.ui_unread)
    if (item.isFinished) return stringResource(R.string.ui_finished)
    return stringResource(R.string.ui_percent_read, (position.percent * 100).toInt())
}

/** A one-line summary of a book's size and format, used on the details screen. */
@Composable
fun bookMetaLine(book: Book): String = buildString {
    append(book.format.displayName)
    if (book.sizeBytes > 0) {
        append(" · ")
        append(formatBytes(book.sizeBytes))
    }
    val count = book.contentCount
    if (count != null && count > 0) {
        append(" · ")
        append(count)
    }
}
