package com.avinash.relaydisplay.mirroring

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * The video settings a mirroring session runs at.
 *
 * Conservative on purpose. The receiving phone is 2016-era hardware whose AVC decoder is
 * comfortable around 720p; pushing 1080p60 at it produces dropped frames and heat rather than a
 * better picture. Everything here is negotiated down, never up.
 */
data class MirrorProfile(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitRate: Int,
    val codecMime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
) {
    companion object {
        /** The starting point: roughly 720p, 24 fps, 2.5 Mbps. */
        fun default(sourceWidth: Int, sourceHeight: Int): MirrorProfile {
            val (width, height) = fitWithin(sourceWidth, sourceHeight, TARGET_LONG_EDGE)
            return MirrorProfile(width, height, DEFAULT_FRAME_RATE, DEFAULT_BIT_RATE)
        }

        const val TARGET_LONG_EDGE = 1280
        const val DEFAULT_FRAME_RATE = 24
        const val DEFAULT_BIT_RATE = 2_500_000
        const val MIN_BIT_RATE = 800_000
        const val MIN_FRAME_RATE = 12

        /**
         * Scales to fit a long edge, preserving aspect ratio and rounding to even numbers.
         *
         * Even dimensions are not cosmetic: H.264 chroma subsampling needs them, and an odd
         * width makes some hardware encoders fail to configure at all.
         */
        fun fitWithin(width: Int, height: Int, longEdge: Int): Pair<Int, Int> {
            if (width <= 0 || height <= 0) return longEdge to (longEdge * 9 / 16).roundedEven()
            val scale = longEdge.toFloat() / maxOf(width, height)
            if (scale >= 1f) return width.roundedEven() to height.roundedEven()
            return (width * scale).toInt().roundedEven() to (height * scale).toInt().roundedEven()
        }

        private fun Int.roundedEven(): Int = maxOf(2, this - (this % 2))

        /** Whether this device has any hardware AVC encoder at all. */
        fun hasAvcEncoder(): Boolean = findCodec(MediaFormat.MIMETYPE_VIDEO_AVC, encoder = true) != null

        /** Whether this device can decode what we would send. */
        fun hasAvcDecoder(): Boolean = findCodec(MediaFormat.MIMETYPE_VIDEO_AVC, encoder = false) != null

        /**
         * Clamps a profile to what the local encoder actually supports.
         *
         * Returns null when there is no usable encoder, which the UI reports as a hardware
         * limitation instead of failing silently at start time.
         */
        fun clampToDeviceCapabilities(profile: MirrorProfile): MirrorProfile? {
            val info = findCodec(profile.codecMime, encoder = true) ?: return null
            return try {
                val caps = info.getCapabilitiesForType(profile.codecMime)
                val video = caps.videoCapabilities ?: return null
                val width = video.supportedWidths.clamp(profile.width)
                val heights = video.getSupportedHeightsFor(width)
                val height = heights.clamp(profile.height)
                val frameRate = video.getSupportedFrameRatesFor(width, height)
                    .let { range -> profile.frameRate.coerceIn(range.lower.toInt(), range.upper.toInt()) }
                val bitRate = video.bitrateRange.clamp(profile.bitRate)
                profile.copy(
                    width = width,
                    height = height,
                    frameRate = maxOf(MIN_FRAME_RATE, frameRate),
                    bitRate = maxOf(MIN_BIT_RATE, bitRate),
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun android.util.Range<Int>.clamp(value: Int): Int = value.coerceIn(lower, upper)

        private fun findCodec(mime: String, encoder: Boolean): MediaCodecInfo? =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder == encoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
    }
}

/**
 * Steps a profile down when the link or the receiver cannot keep up.
 *
 * Bitrate first, then frame rate, then resolution: that is the order that costs the viewer the
 * least. Resolution is the last thing to give up because a sharp, slightly jerky screen is far
 * more readable than a smooth blurry one, which is the whole point of relaying a screen.
 */
object MirrorDegradation {

    fun stepDown(profile: MirrorProfile): MirrorProfile? = when {
        profile.bitRate > MirrorProfile.MIN_BIT_RATE ->
            profile.copy(bitRate = maxOf(MirrorProfile.MIN_BIT_RATE, (profile.bitRate * 0.7).toInt()))

        profile.frameRate > MirrorProfile.MIN_FRAME_RATE ->
            profile.copy(frameRate = maxOf(MirrorProfile.MIN_FRAME_RATE, profile.frameRate - 6))

        maxOf(profile.width, profile.height) > 640 -> {
            val (width, height) = MirrorProfile.fitWithin(profile.width, profile.height, 854)
            profile.copy(width = width, height = height)
        }

        // Already at the floor; there is nothing left to give.
        else -> null
    }
}
