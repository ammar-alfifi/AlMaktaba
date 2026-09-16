package com.mylibrary.core.ui.format

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mylibrary.core.common.formatBytes
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.LibraryItem
import com.mylibrary.core.ui.R
import java.util.Locale

/**
 * User-facing formatting for book metadata and progress.
 *
 * Kept in `:core:core-ui` rather than in the domain so that every screen renders a book the same
 * way. All of it follows the *app's* language rather than the device's — which takes more than
 * passing a locale, because the platform formatters want a `Context` and the only one on hand is
 * the device's.
 */

/**
 * The language the app is currently being read in.
 *
 * Read from [LocalConfiguration], which `MyLibraryApp` replaces with the localised one. Nothing here
 * touches `LocalContext`: it is deliberately left pointing at the Activity, because replacing it
 * breaks `hiltViewModel()` — see the note on `MyLibraryLocalized` for the crash that caused.
 */
@Composable
fun appLocale(): Locale = LocalConfiguration.current.locales.let { locales ->
    if (locales.size() > 0) locales[0] else Locale.getDefault()
}

/**
 * A context scoped to the app's own language, for the platform formatters that need one.
 *
 * `DateUtils` and friends read the locale off the `Context` they are handed, so a device set to
 * Arabic and an app set to English produced "Added قبل ٣ أيام" — the app's own text in one language
 * and its dates in another. Rebuilding the resources is cheap (the configuration object is already
 * in hand) and deliberately happens on a *derived* context; the Activity context the rest of the
 * tree uses is untouched.
 */
@Composable
private fun localizedContext(): Context {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        context.createConfigurationContext(Configuration(configuration))
    }
}

/**
 * "3 days ago" / "قبل ٣ أيام", relative to now, in the app's language.
 *
 * The `Context` overload is the one that takes a locale at all — every other variant of
 * `getRelativeTimeSpanString` formats against the process default, which is the device's language
 * and not the app's. It resolves below a minute and above a week with the same abbreviated wording
 * the previous call asked for.
 */
@Composable
fun relativeTime(epochMillis: Long): String {
    val context = localizedContext()
    return DateUtils.getRelativeTimeSpanString(context, epochMillis).toString()
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
