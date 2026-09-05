package com.avinash.relaydisplay.content

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Renders one PDF page at a time.
 *
 * `PdfRenderer` has existed since API 21, so it works on both target phones. The rule here is
 * that exactly one page is rasterised at a time plus a tiny neighbour cache: rendering a whole
 * document would allocate hundreds of megabytes on a phone that does not have them.
 *
 * A password-protected or corrupt document surfaces as [OpenResult.Failed] rather than an
 * exception escaping into a coroutine.
 */
class PdfPageSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    private var cachedIndex: Int = -1
    private var cached: Bitmap? = null

    /**
     * Renders [index] at up to [maxWidth] x [maxHeight], preserving aspect ratio.
     *
     * The previous page's bitmap is recycled before the new one is created, so peak memory is
     * one page rather than one per page visited.
     */
    fun render(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (index < 0 || index >= pageCount) return null
        cached?.let { if (cachedIndex == index && !it.isRecycled) return it }

        return try {
            renderer.openPage(index).use { page ->
                val scale = minOf(
                    maxWidth.toFloat() / page.width,
                    maxHeight.toFloat() / page.height,
                ).coerceAtMost(MAX_SCALE)
                val width = (page.width * scale).toInt().coerceIn(1, maxWidth)
                val height = (page.height * scale).toInt().coerceIn(1, maxHeight)

                val bitmap = androidx.core.graphics.createBitmap(width, height)
                // PdfRenderer draws onto a transparent bitmap; a page with no background would
                // otherwise render as black text on black.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                cached?.takeIf { it != bitmap && !it.isRecycled }?.recycle()
                cached = bitmap
                cachedIndex = index
                bitmap
            }
        } catch (e: IllegalStateException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: SecurityException) {
            // A password-protected document throws here on open page.
            null
        }
    }

    override fun close() {
        cached?.takeIf { !it.isRecycled }?.recycle()
        cached = null
        try {
            renderer.close()
        } catch (e: IllegalStateException) {
            // Already closed.
        }
        try {
            descriptor.close()
        } catch (e: IOException) {
            // Already closed.
        }
    }

    sealed interface OpenResult {
        data class Opened(val source: PdfPageSource) : OpenResult

        /** [reason] is a short static string, never the document's own content. */
        data class Failed(val reason: String) : OpenResult
    }

    companion object {
        /** Never upscale a page past this; a blurry enlargement is worse than a small page. */
        private const val MAX_SCALE = 4f

        fun open(file: File): OpenResult {
            var descriptor: ParcelFileDescriptor? = null
            return try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(descriptor)
                if (renderer.pageCount <= 0) {
                    renderer.close()
                    descriptor.close()
                    OpenResult.Failed("The document has no pages")
                } else {
                    OpenResult.Opened(PdfPageSource(descriptor, renderer))
                }
            } catch (e: SecurityException) {
                descriptor?.closeQuietly()
                OpenResult.Failed("This document is password protected")
            } catch (e: IOException) {
                descriptor?.closeQuietly()
                OpenResult.Failed("This document could not be opened")
            } catch (e: IllegalArgumentException) {
                descriptor?.closeQuietly()
                OpenResult.Failed("This document could not be opened")
            }
        }

        /** Page count without keeping a renderer open. Returns 0 for anything unreadable. */
        fun pageCount(file: File): Int = when (val result = open(file)) {
            is OpenResult.Opened -> result.source.use { it.pageCount }
            is OpenResult.Failed -> 0
        }

        private fun ParcelFileDescriptor.closeQuietly() {
            try {
                close()
            } catch (e: IOException) {
                // Best effort.
            }
        }
    }
}
