package com.mylibrary.core.data.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mylibrary.core.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Writes book covers into the app's cache directory.
 *
 * Covers go to disk rather than into memory or into the database for one reason: the library grid
 * shows every book at once, so a cover has to be loadable, cacheable and evictable like any other
 * image. Coil then handles downsampling and memory caching, and Android can clear `cacheDir` under
 * storage pressure without touching the user's library.
 *
 * Files are named by a hash of the book URI rather than by the book id, because the id is not known
 * when the cover is extracted during import, and because a re-import of the same file should reuse
 * the same cover file instead of accumulating copies.
 */
@Singleton
class CoverWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Decodes [bytes] and writes a downscaled JPEG cover, returning its absolute path.
     *
     * The image is decoded with `inSampleSize` rather than decoded-then-scaled. Archive covers are
     * routinely 3000px scans, and decoding one at full size just to shrink it is the classic way a
     * comic reader runs out of memory while scrolling a shelf.
     *
     * @return the file's path, or `null` when [bytes] are not a decodable image.
     */
    suspend fun writeCover(bookUri: String, bytes: ByteArray): String? =
        withContext(dispatchers.io) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_COVER_WIDTH_PX)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@withContext null

            try {
                writeCoverBitmap(bookUri, bitmap)
            } finally {
                bitmap.recycle()
            }
        }

    /** Writes an already-decoded bitmap, downscaling it first if it is wider than the cover size. */
    suspend fun writeCover(bookUri: String, bitmap: Bitmap): String? = withContext(dispatchers.io) {
        val scaled = if (bitmap.width > MAX_COVER_WIDTH_PX) {
            val ratio = MAX_COVER_WIDTH_PX.toFloat() / bitmap.width
            Bitmap.createScaledBitmap(
                bitmap,
                MAX_COVER_WIDTH_PX,
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        try {
            writeCoverBitmap(bookUri, scaled)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Deletes the cached cover for [bookUri], if any. */
    suspend fun deleteCover(bookUri: String) = withContext(dispatchers.io) {
        coverFile(bookUri).delete()
        Unit
    }

    private fun writeCoverBitmap(bookUri: String, bitmap: Bitmap): String? = try {
        val file = coverFile(bookUri)
        file.parentFile?.mkdirs()
        // Write to a temporary file and rename, so an interrupted write cannot leave a truncated
        // JPEG that Coil would then decode into a half-drawn cover.
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, output)
        }
        if (temporary.renameTo(file)) file.absolutePath else temporary.absolutePath
    } catch (io: java.io.IOException) {
        null
    }

    private fun coverFile(bookUri: String): File =
        File(File(context.cacheDir, COVER_DIRECTORY), "${bookUri.hashCode().toUInt()}.jpg")

    /**
     * The largest power of two that still leaves the image at least [targetWidth] wide.
     *
     * A power of two is required: `BitmapFactory` ignores anything else, and silently ignoring it
     * would mean a 3000px scan being decoded at full size.
     */
    private fun sampleSizeFor(width: Int, height: Int, targetWidth: Int): Int {
        var sampleSize = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth / 2 >= targetWidth && currentHeight / 2 >= 1) {
            currentWidth /= 2
            currentHeight /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    private companion object {
        const val COVER_DIRECTORY = "covers"

        /** 600px is comfortably above the largest grid cell on any phone or tablet. */
        const val MAX_COVER_WIDTH_PX = 600
        const val COVER_QUALITY = 88
    }
}
