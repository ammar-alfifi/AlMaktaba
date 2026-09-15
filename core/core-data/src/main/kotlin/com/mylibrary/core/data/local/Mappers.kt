package com.mylibrary.core.data.local

import com.mylibrary.core.data.local.entity.BookEntity
import com.mylibrary.core.data.local.entity.BookmarkEntity
import com.mylibrary.core.data.local.entity.ReadingPositionEntity
import com.mylibrary.core.domain.model.Book
import com.mylibrary.core.domain.model.BookFormat
import com.mylibrary.core.domain.model.Bookmark
import com.mylibrary.core.domain.model.ReadingLocator
import com.mylibrary.core.domain.model.ReadingPosition

/**
 * Translation between Room rows and domain models.
 *
 * Mappers live here, at the boundary, so the domain types never learn about columns and the
 * entities never learn about behaviour. Unknown values are handled by *degrading* rather than
 * throwing: a `format` string written by a future version of the app should show up as an
 * unsupported book, not crash the library screen on launch.
 */

internal object LocatorTypes {
    const val PAGED = "PAGED"
    const val REFLOWABLE = "REFLOWABLE"
}

internal fun BookEntity.toDomain(): Book = Book(
    id = id,
    title = title,
    author = author,
    uri = uri,
    format = BookFormat.entries.firstOrNull { it.name == format } ?: BookFormat.TXT,
    sizeBytes = sizeBytes,
    coverPath = coverPath,
    contentCount = contentCount,
    language = language,
    isFavorite = isFavorite,
    addedAt = addedAt,
    lastOpenedAt = lastOpenedAt,
)

internal fun Book.toEntity(): BookEntity = BookEntity(
    id = id,
    title = title,
    author = author,
    uri = uri,
    format = format.name,
    sizeBytes = sizeBytes,
    coverPath = coverPath,
    contentCount = contentCount,
    language = language,
    isFavorite = isFavorite,
    addedAt = addedAt,
    lastOpenedAt = lastOpenedAt,
)

internal fun ReadingPositionEntity.toDomain(): ReadingPosition = ReadingPosition(
    bookId = bookId,
    locator = toLocator(locatorType, locatorIndex, locatorOffset),
    percent = percent,
    updatedAt = updatedAt,
    excerpt = excerpt,
)

internal fun ReadingPosition.toEntity(): ReadingPositionEntity = ReadingPositionEntity(
    bookId = bookId,
    locatorType = locator.typeName(),
    locatorIndex = locator.primaryIndex(),
    locatorOffset = locator.secondaryOffset(),
    percent = percent,
    updatedAt = updatedAt,
    excerpt = excerpt,
)

internal fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    bookId = bookId,
    locator = toLocator(locatorType, locatorIndex, locatorOffset),
    label = label,
    excerpt = excerpt,
    note = note,
    colorArgb = colorArgb,
    createdAt = createdAt,
)

internal fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    bookId = bookId,
    locatorType = locator.typeName(),
    locatorIndex = locator.primaryIndex(),
    locatorOffset = locator.secondaryOffset(),
    label = label,
    excerpt = excerpt,
    note = note,
    colorArgb = colorArgb,
    createdAt = createdAt,
)

/**
 * Rebuilds a locator from its three columns.
 *
 * An unrecognised type falls back to the beginning of the document. That is the right failure for a
 * reading position: it costs the reader their place, but it opens the book, which is strictly
 * better than crashing or refusing to load a library row.
 */
private fun toLocator(type: String, index: Int, offset: Int): ReadingLocator = when (type) {
    LocatorTypes.PAGED -> ReadingLocator.Paged(index.coerceAtLeast(0))
    LocatorTypes.REFLOWABLE -> ReadingLocator.Reflowable(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    else -> ReadingLocator.Paged(0)
}

private fun ReadingLocator.typeName(): String = when (this) {
    is ReadingLocator.Paged -> LocatorTypes.PAGED
    is ReadingLocator.Reflowable -> LocatorTypes.REFLOWABLE
}

private fun ReadingLocator.primaryIndex(): Int = when (this) {
    is ReadingLocator.Paged -> pageIndex
    is ReadingLocator.Reflowable -> chapterIndex
}

private fun ReadingLocator.secondaryOffset(): Int = when (this) {
    is ReadingLocator.Paged -> 0
    is ReadingLocator.Reflowable -> charOffset
}
