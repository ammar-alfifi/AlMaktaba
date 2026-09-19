package com.mylibrary.core.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.LibraryItem
import com.mylibrary.core.ui.R
import com.mylibrary.core.ui.format.progressLabel
import kotlin.math.absoluteValue

/** Book covers are consistently 2:3, the aspect ratio of a printed trade paperback. */
private const val COVER_ASPECT_RATIO = 2f / 3f

/**
 * A book cover, or a deterministic stand-in when the book has none.
 *
 * The placeholder is not a generic grey rectangle: its hue is derived from a hash of the title, so
 * a shelf of coverless EPUBs reads as a set of distinct spines rather than an undifferentiated
 * block. The colour is computed from the title rather than from the row id because the id changes
 * when a book is deleted and re-imported, and a cover that changes colour for no reason looks like
 * a bug.
 */
@Composable
fun BookCover(
    book: Book,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val coverUri = remember(book.coverPath) {
        book.coverPath?.let { path -> runCatching { Uri.fromFile(java.io.File(path)) }.getOrNull() }
    }
    // A cover the library cannot read falls back to the placeholder rather than to an empty frame.
    // The path is a *cache* file — Android may evict it, and the row keeps pointing at it — so a
    // shelf whose covers were cleared by the system has to degrade to the stand-ins it started with
    // instead of to blank spines. Reset whenever the path changes, because a re-extracted cover is
    // worth another attempt.
    var coverFailed by remember(book.coverPath) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .aspectRatio(COVER_ASPECT_RATIO)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (coverUri != null && !coverFailed) {
            AsyncImage(
                model = coverUri,
                contentDescription = contentDescription ?: stringResource(R.string.ui_cd_book_cover),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) coverFailed = true
                },
            )
        } else {
            CoverPlaceholder(book = book, modifier = Modifier.fillMaxSize())
        }

        if (book.isFavorite) {
            // Sits on the cover rather than in the card's text column so it is visible in a grid
            // cell of any size.
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .padding(3.dp)
                        .size(14.dp),
                )
            }
        }

        FormatBadge(
            text = book.format.displayName,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
        )
    }
}

/** A coloured stand-in cover showing the format and the first letter of the title. */
@Composable
private fun CoverPlaceholder(book: Book, modifier: Modifier = Modifier) {
    val hue = remember(book.title) { (book.title.hashCode().absoluteValue % 360).toFloat() }
    val start = Color.hsl(hue, 0.32f, 0.42f)
    val end = Color.hsl((hue + 28f) % 360f, 0.36f, 0.28f)

    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(start, end))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = book.title.trim().take(1),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The small `PDF` / `EPUB` chip drawn over a cover. */
@Composable
private fun FormatBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.62f),
        contentColor = Color.White,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/**
 * A book in the library grid: cover, then title, author and progress.
 *
 * Long-press is wired up alongside click because the context menu (favourite, details, delete) is
 * reachable that way on every Android surface; the same actions are also in the details screen, so
 * nothing is long-press-only.
 */
@Composable
fun BookGridCard(
    item: LibraryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val book = item.book
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 8.dp),
    ) {
        BookCover(book = book)
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        val author = book.author
        if (!author.isNullOrBlank()) {
            Text(
                text = author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ProgressRow(item = item, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * A book in the library list: a small cover beside its details.
 *
 * The cover is fixed-width so the text column of every row lines up, which is what makes a long
 * list scannable.
 */
@Composable
fun BookListRow(
    item: LibraryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val book = item.book
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BookCover(
            book = book,
            modifier = Modifier.width(52.dp),
            contentDescription = null,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val author = book.author
            if (!author.isNullOrBlank()) {
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProgressRow(item = item, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * The progress line shared by the grid card and the list row.
 *
 * A book that has never been opened shows "not started" rather than a 0% bar: an empty bar reads
 * as a loading indicator that has stalled, which is a worse lie than saying nothing.
 */
@Composable
private fun ProgressRow(item: LibraryItem, modifier: Modifier = Modifier) {
    val progress = item.progress
    Column(modifier = modifier.fillMaxWidth()) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = progressLabel(item),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 4.dp)
                .clearAndSetSemantics { contentDescription = "" },
        )
    }
}
