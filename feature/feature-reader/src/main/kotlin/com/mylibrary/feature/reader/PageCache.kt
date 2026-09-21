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

    /**
     * Identifies a rendered page.
     *
     * The background colour is part of the identity, not an afterthought: a page composited onto
     * white and the same page composited onto another colour are different images, and a key that
     * omitted it would hand back the first one for the second request. That is a latent bug today —
     * every render currently uses the same white — but it becomes a visible one the moment a
     * reading theme or a night mode varies the background.
     */
    data class Key(
        val documentId: String,
        val pageIndex: Int,
        val widthPx: Int,
        val heightPx: Int,
        val backgroundColorArgb: Int,
    )

    private data class Entry(val image: ImageBitmap, val byteSize: Int)

    private val entries = LinkedHashMap<Key, Entry>(INITIAL_CAPACITY, 0.75f, true)

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
     * Drops every page of one document, for a reader that has more than one open.
     *
     * A volume of a series is left behind when the reader crosses the seam into the next one, and
     * its pages are the ones to give up first: they are not going to be asked for again soon, and
     * leaving them in the budget would evict the pages of the book the reader is actually reading.
     *
     * The accounting goes through the same counter as [trimToBudget] and for the reason documented
     * there — this is the second place an entry is dropped, and it is the one that would silently
     * corrupt the counter if it removed from the map without subtracting.
     */
    fun evict(documentId: String) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.documentId == documentId) {
                totalBytes -= entry.value.byteSize
                iterator.remove()
            }
        }
    }

    /**
     * Evicts least-recently-used entries until both budgets are met.
     *
     * **This is the only place an entry is ever dropped, and the only place [totalBytes] is ever
     * decremented**, which is not a stylistic point. Eviction used to be split between here and
     * `LinkedHashMap.removeEldestEntry`, which fires *inside* the map's own `put` — and that path
     * removed the entry without touching the counter. The counter therefore drifted permanently
     * upwards by the size of every entry evicted that way, and once the drift alone exceeded the
     * budget this loop emptied the map on every insert while the counter stayed over: the cache
     * silently stopped caching, and every page re-rendered from the engine on every frame.
     *
     * `LinkedHashMap` only considers eviction on insertion, so this also runs after a `put` that
     * removed an existing key — a cache that had grown by a large page and then lost it would
     * otherwise never shrink back.
     */
    private fun trimToBudget() {
        val iterator = entries.entries.iterator()
        while ((totalBytes > maxBytes || entries.size > MAX_ENTRIES) && iterator.hasNext()) {
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
