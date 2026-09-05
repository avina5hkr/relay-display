package com.avinash.relaydisplay.mirroring

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes the mirrored stream straight onto a Surface.
 *
 * Frames are never turned into Bitmaps: `releaseOutputBuffer(index, true)` hands the decoded
 * frame to the compositor without it ever entering the app's heap, which is the difference
 * between smooth playback and an unusable slideshow on the older phone.
 *
 * The jitter buffer is small and drops rather than grows. When the decoder falls behind, the
 * right answer for a live mirror is to skip ahead to the newest data, not to accumulate latency
 * and memory.
 */
class MirrorDecoder(
    private val surface: Surface,
    private val onError: (String) -> Unit,
    private val onKeyFrameNeeded: () -> Unit,
) : Closeable {

    private var codec: MediaCodec? = null
    private val closed = AtomicBoolean(false)

    /** Frames waiting for a free input buffer. Bounded; oldest non-key frames are dropped first. */
    private val pending = ArrayDeque<PendingFrame>()
    private val availableInputs = ArrayDeque<Int>()
    private val lock = Any()

    /** True until the first keyframe after a (re)start; everything before it is undecodable. */
    private var waitingForKeyFrame = true

    private data class PendingFrame(val data: ByteArray, val presentationTimeUs: Long, val keyFrame: Boolean)

    /** Configures the decoder from the sender's codec-specific data. */
    fun configure(mime: String, width: Int, height: Int, csd0: ByteArray, csd1: ByteArray): Boolean {
        if (closed.get()) return false
        release()
        return try {
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                if (csd0.isNotEmpty()) setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                if (csd1.isNotEmpty()) setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
                // Ask for the lowest latency the decoder will give us; ignored where unsupported.
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.setCallback(callback)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            waitingForKeyFrame = true
            true
        } catch (e: MediaCodec.CodecException) {
            onError("This phone cannot decode the video being sent")
            false
        } catch (e: IllegalArgumentException) {
            onError("The video format is not supported on this phone")
            false
        } catch (e: IllegalStateException) {
            onError("The video decoder could not start")
            false
        }
    }

    /** Queues one access unit. Returns false when it was dropped. */
    fun submit(data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean): Boolean {
        if (closed.get() || codec == null) return false
        synchronized(lock) {
            // Everything before the first keyframe would decode to garbage.
            if (waitingForKeyFrame && !keyFrame) return false
            if (keyFrame) waitingForKeyFrame = false

            if (pending.size >= MAX_PENDING_FRAMES) {
                // Behind. Drop the oldest non-key frame; if the buffer is all keyframes, drop the
                // oldest outright. Either way memory stays bounded and latency stops growing.
                val victim = pending.firstOrNull { !it.keyFrame }
                if (victim != null) pending.remove(victim) else pending.pollFirst()
            }
            pending.addLast(PendingFrame(data, presentationTimeUs, keyFrame))
            drain()
        }
        return true
    }

    /** Called after a reconnect: the decoder needs a fresh keyframe before anything makes sense. */
    fun resetForNewStream() {
        synchronized(lock) {
            pending.clear()
            waitingForKeyFrame = true
        }
        onKeyFrameNeeded()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            synchronized(lock) {
                availableInputs.addLast(index)
                drain()
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                // true means "render to the Surface"; the frame never touches this app's heap.
                codec.releaseOutputBuffer(index, true)
            } catch (e: IllegalStateException) {
                // Released underneath us.
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            onError("The video decoder stopped")
            close()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // The Surface handles resizing; nothing to do.
        }
    }

    /** Must be called with [lock] held. */
    private fun drain() {
        val decoder = codec ?: return
        while (availableInputs.isNotEmpty() && pending.isNotEmpty()) {
            val index = availableInputs.pollFirst() ?: return
            val frame = pending.pollFirst() ?: return
            try {
                val buffer = decoder.getInputBuffer(index)
                if (buffer == null) {
                    continue
                }
                buffer.clear()
                if (frame.data.size > buffer.capacity()) {
                    // A frame larger than the codec's own input buffer cannot be decoded; drop it
                    // and ask for a fresh keyframe rather than corrupting the stream.
                    decoder.queueInputBuffer(index, 0, 0, frame.presentationTimeUs, 0)
                    waitingForKeyFrame = true
                    onKeyFrameNeeded()
                    continue
                }
                buffer.put(frame.data)
                decoder.queueInputBuffer(index, 0, frame.data.size, frame.presentationTimeUs, 0)
            } catch (e: IllegalStateException) {
                return
            }
        }
    }

    private fun release() {
        val decoder = codec ?: return
        codec = null
        synchronized(lock) {
            pending.clear()
            availableInputs.clear()
        }
        try {
            decoder.stop()
        } catch (e: IllegalStateException) {
            // Never started.
        }
        try {
            decoder.release()
        } catch (e: IllegalStateException) {
            // Already released.
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        release()
    }

    private companion object {
        /**
         * Three frames at 24 fps is about 125 ms of slack: enough to ride out a scheduling
         * hiccup, short enough that the mirror still feels live.
         */
        const val MAX_PENDING_FRAMES = 3
    }
}
