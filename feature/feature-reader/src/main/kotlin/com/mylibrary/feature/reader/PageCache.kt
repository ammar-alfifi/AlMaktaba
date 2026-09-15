package com.mylibrary.feature.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * A byte-bounded cache of rendered pages.
 *
 * Bounding by *bytes* rather than by entry count is the whole point. Ten pages of a comic scan and
 * ten pages of a text-light PDF differ by two orders of magnitude in memory, so a cache that holds
 * "the last 10 pages" can be 20 MB on one book and 800 MB on another — and the second one is an
 * out-of-memory crash while scrolling. Sizing the budget in bytes means the cache holds fewer pages
 * of a heavy book and more of a light one, which is exactly the right behaviour.
 *
 * Eviction is least-recently-used: the reader pre-loads the pages either side of the current one, so
 * the pages being evicted are the ones furthest from where the user is looking.
 *
 * This class is not thread-safe. The reader touches it only from the main dispatcher, and rendering
 * itself is serialised on the document by the ViewModel — so a lock here would add contention
 * without adding safety.
 */
class PageCache(private val maxBytes: Int) {

    /** Identifies a rendered page: the same page at a different zoom is a different entry. */
    data class Key(
        val documentId: String,
        val pageIndex: Int,
        val widthPx: Int,
        val heightPx: Int,
    )

    private data class Entry(val image: ImageBitmap, val byteSize: Int)

    private val entries = object : LinkedHashMap<Key, Entry>(INITIAL_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>): Boolean =
            size > MAX_ENTRIES || totalBytes > maxBytes
    }

    private var totalBytes: Int = 0

    val sizeBytes: Int get() = totalBytes

    val count: Int get() = entries.size

    operator fun get(key: Key): ImageBitmap? = entries[key]?.image

    fun put(key: Key, image: ImageBitmap) {
        // An entry larger than the whole budget would evict everything and then itself, leaving the
        // cache empty and thrashing; refusing to store it keeps everything else resident.
        val byteSize = image.width * image.height * BYTES_PER_PIXEL
        if (byteSize > maxBytes) return

        entries.remove(key)?.let { totalBytes -= it.byteSize }
        entries[key] = Entry(image, byteSize)
        totalBytes += byteSize
        trimToBudget()
    }

    /** Drops every page of a document, used when the reader closes it. */
    fun clear() {
        entries.clear()
        totalBytes = 0
    }

    /**
     * Evicts least-recently-used entries until the budget is met.
     *
     * `LinkedHashMap` only evicts on insertion, so a cache that grew by a large page and then had
     * that page removed would never shrink back without this.
     */
    private fun trimToBudget() {
        val iterator = entries.entries.iterator()
        while (totalBytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            totalBytes -= entry.value.byteSize
            iterator.remove()
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val MAX_ENTRIES = 24
        const val BYTES_PER_PIXEL = 4
    }
}

/**
 * Wraps a raw ARGB pixel buffer as a Compose [ImageBitmap].
 *
 * This is the single unavoidable copy between the domain and the screen: `PageImage` carries an
 * `IntArray` because `:core:core-domain` is a plain JVM module, and `Bitmap` is a platform type. It
 * happens once per page render, at the size the screen actually needs, and the result is what the
 * cache above measures and bounds.
 */
fun com.mylibrary.core.domain.model.PageImage.toImageBitmap(): ImageBitmap =
    android.graphics.Bitmap
        .createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
