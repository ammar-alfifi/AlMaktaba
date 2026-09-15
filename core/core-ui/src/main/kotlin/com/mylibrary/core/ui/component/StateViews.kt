package com.mylibrary.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mylibrary.core.common.AppError
import com.mylibrary.core.ui.R

/**
 * The shared "nothing here yet" view.
 *
 * Takes an icon and a pair of strings rather than a screen-specific type so every empty state in
 * the app is laid out identically — the library, search results, bookmarks and highlights all use
 * this, and a user moving between them should not see three different spacings.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (action != null) {
            Box(modifier = Modifier.padding(top = 24.dp)) { action() }
        }
    }
}

/** A centred progress indicator that fills its container. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * The shared failure view.
 *
 * It maps an [AppError] to a localized sentence here rather than at each call site, so a decoder
 * adding a new error case produces a compile error in exactly one place instead of silently
 * showing a blank screen somewhere.
 */
@Composable
fun ErrorState(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.ui_error_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = error.messageForUser(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth(fraction = 0.6f),
            ) {
                Text(text = stringResource(R.string.ui_retry))
            }
        }
    }
}

/**
 * The one place an [AppError] becomes user-visible text.
 *
 * `@Composable` and returning a `String` rather than a resource id so that the `PasswordRequired`
 * case can vary its wording with its `wrongPassword` flag, which a plain `@StringRes` could not.
 */
@Composable
fun AppError.messageForUser(): String = when (this) {
    is AppError.FileAccess -> stringResource(R.string.ui_error_file_access)
    is AppError.CorruptDocument -> stringResource(R.string.ui_error_corrupt)
    is AppError.UnsupportedFormat -> stringResource(R.string.ui_error_unsupported)
    is AppError.DecoderUnavailable -> stringResource(R.string.ui_error_decoder_unavailable, format)
    is AppError.PasswordRequired -> stringResource(R.string.ui_error_password)
    is AppError.Protected -> stringResource(R.string.ui_error_protected)
    is AppError.OutOfMemory -> stringResource(R.string.ui_error_out_of_memory)
    is AppError.EmptyDocument -> stringResource(R.string.ui_error_empty_document)
    is AppError.Unexpected -> stringResource(R.string.ui_error_unexpected)
}
