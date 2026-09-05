package com.avinash.relaydisplay.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * Decodes a received image at roughly the size it will actually be drawn.
 *
 * The companion phone is the constraint here: a 12 megapixel photo is 48 MB as an ARGB_8888
 * bitmap, which is more than that device's whole heap. So bounds are read first, an integer
 * sample size is chosen, and only then is a bitmap allocated -- never the full-resolution one.
 *
 * `ImageDecoder` is deliberately not used: it needs API 28, and the compatible path here works
 * everywhere from API 23 up with no behavioural difference that matters for still images.
 */
object ImageDecoding {

    /** Refuse anything whose declared dimensions could not be a real photo. */
    const val MAX_DIMENSION = 20_000

    data class Decoded(val bitmap: Bitmap, val rotationDegrees: Int)

    /**
     * Reads just the header. Returns null when the file is not a decodable image, which is what
     * a bitmap bomb with absurd declared dimensions also produces.
     */
    fun readBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            // Even bounds decoding can fail on a hostile file; treat it as undecodable.
            return null
        }
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return null
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) return null
        return width to height
    }

    /**
     * The largest power-of-two sample size that still fills [targetWidth] x [targetHeight].
     *
     * Powers of two are what BitmapFactory actually honours, so computing anything else would
     * silently round and waste memory.
     */
    fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    /**
     * Decodes to approximately the target size, honouring EXIF orientation.
     *
     * Returns null rather than throwing for a corrupt file: the caller shows an error instead of
     * the display crashing on a bad photo.
     */
    fun decode(file: File, targetWidth: Int, targetHeight: Int): Decoded? {
        val (sourceWidth, sourceHeight) = readBounds(file) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(sourceWidth, sourceHeight, targetWidth, targetHeight)
            // RGB_565 halves the memory and is indistinguishable for photos on this hardware.
            // ARGB_8888 is kept for anything that might have transparency.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        val rotation = try {
            exifRotation(file)
        } catch (e: IOException) {
            0
        }
        return Decoded(bitmap, rotation)
    }

    /** Applies EXIF rotation, recycling the source so two full bitmaps never coexist. */
    fun applyRotation(decoded: Decoded): Bitmap {
        if (decoded.rotationDegrees == 0) return decoded.bitmap
        val matrix = Matrix().apply { postRotate(decoded.rotationDegrees.toFloat()) }
        return try {
            val rotated = Bitmap.createBitmap(
                decoded.bitmap, 0, 0, decoded.bitmap.width, decoded.bitmap.height, matrix, true,
            )
            if (rotated != decoded.bitmap) decoded.bitmap.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            // Better an un-rotated photo than none at all.
            decoded.bitmap
        }
    }

    private fun exifRotation(file: File): Int {
        val exif = ExifInterface(file.absolutePath)
        return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }
}
