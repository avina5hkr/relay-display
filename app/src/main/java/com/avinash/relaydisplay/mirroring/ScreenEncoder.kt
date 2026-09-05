package com.avinash.relaydisplay.mirroring

import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** One encoded video access unit, ready to travel. */
data class EncodedFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val keyFrame: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is EncodedFrame && data.contentEquals(other.data) &&
            presentationTimeUs == other.presentationTimeUs && keyFrame == other.keyFrame)

    override fun hashCode(): Int = data.contentHashCode() * 31 + presentationTimeUs.hashCode()
}

/** What the encoder tells its owner. Callbacks arrive on the codec's own thread. */
interface ScreenEncoderListener {
    /** Codec-specific data (SPS/PPS). Must reach the decoder before any frame does. */
    fun onFormat(width: Int, height: Int, csd0: ByteArray, csd1: ByteArray)

    fun onFrame(frame: EncodedFrame)

    fun onError(reason: String)
}

/**
 * Captures the screen into a hardware H.264 encoder.
 *
 * The pipeline is MediaProjection -> VirtualDisplay -> encoder input Surface -> encoded buffers.
 * Nothing is ever copied through a Bitmap: the frames go from the compositor to the encoder on
 * the GPU side, which is the only way this is affordable on a phone.
 *
 * Ownership is strict. [start] either brings up every piece or cleans up what it managed; [close]
 * is idempotent and tears down the encoder, the virtual display and the projection, in that
 * order, so the system capture indicator disappears when the user expects it to.
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val listener: ScreenEncoderListener,
) : Closeable {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private val closed = AtomicBoolean(false)
    private var formatSent = false

    @Volatile
    var profile: MirrorProfile? = null
        private set

    /**
     * Registered before the virtual display exists, because the platform requires a callback to
     * be in place and because a user revoking capture must tear this down immediately.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            listener.onError("Screen sharing was stopped")
            close()
        }
    }

    fun start(requested: MirrorProfile, densityDpi: Int): Boolean {
        val clamped = MirrorProfile.clampToDeviceCapabilities(requested)
        if (clamped == null) {
            listener.onError("This phone has no usable H.264 encoder")
            return false
        }
        profile = clamped

        return try {
            projection.registerCallback(projectionCallback, null)

            val format = MediaFormat.createVideoFormat(clamped.codecMime, clamped.width, clamped.height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, clamped.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, clamped.frameRate)
                // A keyframe every two seconds bounds how long a reconnecting receiver stares at
                // a blank surface, without spending much bitrate.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SECONDS)
                if (Build.VERSION.SDK_INT >= 23) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
            }

            val encoder = MediaCodec.createEncoderByType(clamped.codecMime)
            encoder.setCallback(codecCallback)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()

            codec = encoder
            inputSurface = surface

            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                clamped.width,
                clamped.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )
            if (virtualDisplay == null) {
                listener.onError("The screen could not be captured")
                close()
                false
            } else {
                true
            }
        } catch (e: MediaCodec.CodecException) {
            listener.onError("The video encoder failed to start")
            close()
            false
        } catch (e: IllegalStateException) {
            listener.onError("Screen sharing could not start")
            close()
            false
        } catch (e: SecurityException) {
            listener.onError("Permission to capture the screen was withdrawn")
            close()
            false
        }
    }

    /** Asks the encoder for an immediate keyframe, after a loss or a reconnect. */
    fun requestKeyFrame() {
        if (Build.VERSION.SDK_INT < 19) return
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            // The codec is gone; the next start will produce a keyframe anyway.
        }
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Input comes from the Surface, never from buffers.
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig) {
                        val keyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        listener.onFrame(
                            EncodedFrame(
                                data = buffer.copyRange(info.offset, info.size),
                                presentationTimeUs = info.presentationTimeUs,
                                keyFrame = keyFrame,
                            ),
                        )
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // The codec was released underneath us; nothing to release.
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            listener.onError("The video encoder stopped")
            close()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (formatSent) return
            val csd0 = format.getByteBuffer("csd-0")?.let { it.copyRange(0, it.remaining()) } ?: ByteArray(0)
            val csd1 = format.getByteBuffer("csd-1")?.let { it.copyRange(0, it.remaining()) } ?: ByteArray(0)
            formatSent = true
            listener.onFormat(
                format.getIntegerOrDefault(MediaFormat.KEY_WIDTH, profile?.width ?: 0),
                format.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, profile?.height ?: 0),
                csd0,
                csd1,
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Order matters: stop producing, then stop consuming, then release the projection, so
        // the system capture indicator goes away as soon as the user has asked it to.
        try {
            virtualDisplay?.release()
        } catch (e: IllegalStateException) {
            // Already released.
        }
        virtualDisplay = null

        try {
            codec?.stop()
        } catch (e: IllegalStateException) {
            // Never started, or already stopped.
        }
        try {
            codec?.release()
        } catch (e: IllegalStateException) {
            // Already released.
        }
        codec = null

        inputSurface?.release()
        inputSurface = null

        try {
            projection.unregisterCallback(projectionCallback)
            projection.stop()
        } catch (e: IllegalStateException) {
            // Already stopped.
        }
    }

    private companion object {
        const val VIRTUAL_DISPLAY_NAME = "RelayDisplayMirror"
        const val KEYFRAME_INTERVAL_SECONDS = 2
    }
}

private fun ByteBuffer.copyRange(offset: Int, size: Int): ByteArray {
    val copy = ByteArray(size)
    val duplicate = duplicate()
    duplicate.position(offset)
    duplicate.get(copy, 0, size)
    return copy
}

private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
    if (containsKey(key)) getInteger(key) else fallback
