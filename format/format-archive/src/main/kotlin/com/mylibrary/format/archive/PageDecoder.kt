package com.mylibrary.format.archive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.mylibrary.core.domain.model.PageImage
import com.mylibrary.core.domain.model.PageRenderRequest
import com.mylibrary.core.domain.model.PageSize

/**
 * Turns one page's encoded bytes into ARGB pixels, at the size the caller actually needs.
 *
 * **Why downsampling is not optional here.** Comic scans are commonly 2000–4000 px on the long
 * edge, and a phone or tablet viewport is 800–1600 px. On Android every pixel costs 4 bytes of heap
 * as ARGB_8888, so a single 4000×6000 scan *decoded at full size* is a 96 MB allocation — several
 * times the per-app heap of a mid-range device. Decoding at full size to show it in a 1080 px
 * viewport is the classic comic-reader `OutOfMemoryError`, and it happens on the second or third
 * page turn, when the reader already holds a page or two in its cache.
 *
 * `BitmapFactory` can do the reduction for free, but only at decode time, and only in powers of two
 * (`inSampleSize` is applied as a right shift of both dimensions in the native decoder). So the
 * image is decoded twice: once with `inJustDecodeBounds` — which parses the header and allocates no
 * pixels at all, and is what [bounds] returns — and once for real with an `inSampleSize` computed
 * from that header. The extra header pass costs microseconds; skipping it costs an allocation the
 * size of the page.
 *
 * The decoder also applies the caller's background colour to images with transparency, because
 * [PageImage] is straight ARGB and a comic scanned as transparent PNG must not come out with black
 * or undefined pixels behind it once the reader composites it.
 *
 * Every failure raises [PageReadException], the single signal the document turns into
 * `AppError.CorruptDocument` for that page alone.
 */
internal object PageDecoder {

    /**
     * Ceiling on the sample factor, purely to keep the doubling below out of `Int` overflow.
     *
     * 2^16 already reduces a 2^31-pixel-wide image to 32768 px, so it never binds in practice.
     */
    private const val MAX_SAMPLE_SIZE = 1 shl 16

    /**
     * The image's intrinsic size, read from its header without allocating a bitmap.
     *
     * This is the size the reader lays out against, not the size a render produces: rendering a page
     * downsamples it to whatever the viewport asked for.
     */
    fun bounds(bytes: ByteArray): PageSize {
        requireDecodable(bytes)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) {
            throw PageReadException("Archive entry is not a decodable image")
        }
        return PageSize(width, height)
    }

    /**
     * Decodes the page, scaled down as far as the requested viewport allows.
     *
     * The returned [PageImage]'s dimensions are the *decoded* ones (aspect ratio preserved), which
     * is what the reader needs to know when it scales the page onto the screen.
     */
    fun decode(bytes: ByteArray, request: PageRenderRequest): PageImage {
        val source = bounds(bytes)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                width = source.width,
                height = source.height,
                targetWidthPx = request.targetWidthPx,
                targetHeightPx = request.targetHeightPx,
            )
            // Ask for ARGB_8888 explicitly: it is what PageImage requires, and asking up front
            // avoids a second full-size bitmap when the platform's default differs.
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // No density scaling. decodeByteArray carries no density, but leaving this implicit
            // would make the decoded size depend on a platform default we do not control.
            inScaled = false
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw PageReadException("The image could not be decoded")

        // `composed` is the bitmap the pixels are copied from; it is `decoded` itself when no
        // background has to be painted behind it, so the common opaque-JPEG case allocates nothing
        // extra. Both are recycled on every path, including the failure paths.
        var composed: Bitmap? = null
        try {
            composed = if (decoded.hasAlpha()) {
                paintOnBackground(decoded, request.backgroundColorArgb)
            } else {
                decoded
            }
            val pixels = IntArray(composed.width * composed.height)
            composed.getPixels(pixels, 0, composed.width, 0, 0, composed.width, composed.height)
            return PageImage(pixels, composed.width, composed.height)
        } finally {
            if (composed != null && composed !== decoded) composed.recycle()
            decoded.recycle()
        }
    }

    /**
     * The largest power of two that still decodes to at least the requested viewport.
     *
     * The guarantee this gives, in both dimensions: the decoded image is never *smaller* than the
     * target, and the dimension that binds the choice is never more than twice it — halving once
     * more would have dropped below the target, and it was not chosen. The other dimension can
     * exceed twice its target when the page's aspect ratio differs a lot from the viewport's (a
     * double-page spread rendered into a portrait viewport, say). That is inherent to preserving the
     * aspect ratio at all, and it is bounded by the page's own shape rather than by the scan's
     * resolution, which is the point: the allocation scales with the viewport, not with the file.
     *
     * A non-positive target means "the caller has no viewport yet" and decodes at full size, since
     * guessing a factor would silently lose detail.
     */
    fun calculateInSampleSize(width: Int, height: Int, targetWidthPx: Int, targetHeightPx: Int): Int {
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return 1
        var sampleSize = 1
        while (sampleSize < MAX_SAMPLE_SIZE &&
            width / (sampleSize * 2) >= targetWidthPx &&
            height / (sampleSize * 2) >= targetHeightPx
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Draws [source] over an opaque rectangle of [backgroundArgb].
     *
     * Only called for images that actually carry transparency. Drawing on the canvas is how the
     * alpha gets resolved: a straight copy of the pixels would leave the reader to composite them,
     * and `PageImage` has no background of its own.
     */
    private fun paintOnBackground(source: Bitmap, backgroundArgb: Int): Bitmap {
        val composed = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composed)
        canvas.drawColor(backgroundArgb)
        canvas.drawBitmap(source, 0f, 0f, null)
        return composed
    }

    /** Rejects the inputs `BitmapFactory` answers with an exception rather than a `null` bitmap. */
    private fun requireDecodable(bytes: ByteArray) {
        if (bytes.isEmpty()) throw PageReadException("Archive entry is empty")
    }
}
