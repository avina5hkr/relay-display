package com.avinash.relaydisplay.mirroring

import android.media.projection.MediaProjection
import android.view.Surface
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.network.session.SessionHost
import com.avinash.relaydisplay.protocol.MirrorConfig
import com.avinash.relaydisplay.protocol.MirrorFrame
import com.avinash.relaydisplay.protocol.MirrorStart
import com.avinash.relaydisplay.protocol.MirrorStop
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What mirroring is doing, for the UI. */
sealed interface MirrorState {
    data object Idle : MirrorState
    data object Starting : MirrorState
    data class Active(val profile: MirrorProfile, val droppedFrames: Long) : MirrorState
    data class Unavailable(val reason: String) : MirrorState
}

/**
 * Owns screen capture on the controller and the decoder on the display.
 *
 * One object for both directions because the two halves share a lifetime: the same disconnect,
 * role change or pause has to end whichever half this device is running.
 *
 * Frames are sent with `trySend`, never the suspending send. A mirror that falls behind must
 * drop frames, not queue them: queueing would add latency without ever catching up, and would
 * grow memory while doing it.
 */
class MirrorController(
    private val engine: SessionHost,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()

    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    /**
     * Whether this phone is capturing its own screen right now.
     *
     * `Starting` counts: the consent has been granted and the projection may already exist, so a
     * request to stop arriving in that window must not be ignored.
     */
    val isCapturing: Boolean
        get() = _state.value.let { it is MirrorState.Active || it is MirrorState.Starting }

    // -- sender ---------------------------------------------------------------------------

    private var encoder: ScreenEncoder? = null
    private val droppedFrames = AtomicLong(0)

    @Volatile
    private var onStoppedCallback: (() -> Unit)? = null

    suspend fun start(
        projection: MediaProjection,
        profile: MirrorProfile,
        densityDpi: Int,
        onStopped: () -> Unit,
    ): Boolean = lock.withLock {
        if (encoder != null) return@withLock true

        val session = engine.activeSession.value
        if (session == null) {
            _state.value = MirrorState.Unavailable("Not connected to the display")
            projection.stop()
            return@withLock false
        }
        if (!MirrorProfile.hasAvcEncoder()) {
            _state.value = MirrorState.Unavailable("This phone has no H.264 encoder")
            projection.stop()
            return@withLock false
        }

        _state.value = MirrorState.Starting
        onStoppedCallback = onStopped
        droppedFrames.set(0)

        val listener = object : ScreenEncoderListener {
            override fun onFormat(width: Int, height: Int, csd0: ByteArray, csd1: ByteArray) {
                // Codec configuration must arrive before any frame, so this one is sent with the
                // suspending path via the session's queue rather than dropped under pressure.
                session.trySend(
                    MirrorConfig(UUID.randomUUID(), width, height, rotationDegrees = 0, csd0 = csd0, csd1 = csd1),
                )
            }

            override fun onFrame(frame: EncodedFrame) {
                val accepted = session.trySend(
                    MirrorFrame(UUID.randomUUID(), frame.presentationTimeUs, frame.keyFrame, frame.data),
                )
                if (!accepted) {
                    // The link is the bottleneck. Dropping here is the whole strategy.
                    val total = droppedFrames.incrementAndGet()
                    if (total % DROP_LOG_INTERVAL == 0L) {
                        diagnostics.debug("mirror", "dropped $total frames to keep up")
                    }
                }
                val current = _state.value
                if (current is MirrorState.Active) {
                    _state.value = current.copy(droppedFrames = droppedFrames.get())
                }
            }

            override fun onError(reason: String) {
                diagnostics.warn("mirror", "encoder: $reason")
                _state.value = MirrorState.Unavailable(reason)
                scope.launch { stop(reason) }
            }
        }

        val newEncoder = ScreenEncoder(projection, listener)
        val started = newEncoder.start(profile, densityDpi)
        if (!started) {
            newEncoder.close()
            onStoppedCallback = null
            return@withLock false
        }

        encoder = newEncoder
        val effective = newEncoder.profile ?: profile
        session.trySend(
            MirrorStart(
                UUID.randomUUID(),
                effective.width,
                effective.height,
                effective.frameRate,
                effective.bitRate,
                effective.codecMime,
            ),
        )
        _state.value = MirrorState.Active(effective, 0)
        diagnostics.info("mirror", "started ${effective.width}x${effective.height}@${effective.frameRate}")
        true
    }

    /** Sends a fresh keyframe when the display asks, after a loss or a reconnect. */
    fun onKeyFrameRequested() {
        encoder?.requestKeyFrame()
    }

    suspend fun stop(reason: String) {
        lock.withLock { stopLocked(reason) }
    }

    /** For teardown paths that cannot suspend, such as `Service.onDestroy`. */
    fun stopBlocking(reason: String) {
        runBlocking { stop(reason) }
    }

    private fun stopLocked(reason: String) {
        val current = encoder ?: run {
            releaseDecoder()
            _state.value = MirrorState.Idle
            return
        }
        encoder = null
        current.close()
        engine.activeSession.value?.trySend(MirrorStop(UUID.randomUUID(), reason.take(64)))
        releaseDecoder()
        _state.value = MirrorState.Idle
        diagnostics.info("mirror", "stopped: $reason")
        onStoppedCallback?.invoke()
        onStoppedCallback = null
    }

    // -- receiver -------------------------------------------------------------------------

    private var decoder: MirrorDecoder? = null

    @Volatile
    private var pendingConfig: MirrorConfig? = null

    /**
     * Attaches the display's Surface.
     *
     * The Surface arrives from a SurfaceView whose lifetime is the composition's, so a decoder is
     * built here and torn down in [detachSurface]; a decoder outliving its Surface is a crash.
     */
    fun attachSurface(surface: Surface) {
        if (!MirrorProfile.hasAvcDecoder()) {
            _state.value = MirrorState.Unavailable("This phone cannot decode H.264 video")
            return
        }
        releaseDecoder()
        decoder = MirrorDecoder(
            surface = surface,
            onError = { reason ->
                diagnostics.warn("mirror", "decoder: $reason")
                _state.value = MirrorState.Unavailable(reason)
            },
            onKeyFrameNeeded = { requestKeyFrameFromPeer() },
        )
        // A config that arrived before the Surface existed is applied now.
        pendingConfig?.let { applyConfig(it) }
    }

    fun detachSurface() {
        releaseDecoder()
    }

    fun onMirrorConfig(config: MirrorConfig) {
        pendingConfig = config
        applyConfig(config)
    }

    private fun applyConfig(config: MirrorConfig) {
        val active = decoder ?: return
        val ok = active.configure(
            mime = android.media.MediaFormat.MIMETYPE_VIDEO_AVC,
            width = config.width,
            height = config.height,
            csd0 = config.csd0,
            csd1 = config.csd1,
        )
        if (ok) {
            _state.value = MirrorState.Active(
                MirrorProfile(config.width, config.height, MirrorProfile.DEFAULT_FRAME_RATE, 0),
                0,
            )
            requestKeyFrameFromPeer()
        }
    }

    fun onMirrorFrame(frame: MirrorFrame) {
        decoder?.submit(frame.data, frame.presentationTimeUs, frame.keyFrame)
    }

    fun onMirrorStopped() {
        releaseDecoder()
        pendingConfig = null
        _state.value = MirrorState.Idle
    }

    private fun requestKeyFrameFromPeer() {
        engine.activeSession.value?.trySend(
            com.avinash.relaydisplay.protocol.MirrorKeyframeRequest(UUID.randomUUID()),
        )
    }

    private fun releaseDecoder() {
        decoder?.close()
        decoder = null
    }

    private companion object {
        const val DROP_LOG_INTERVAL = 60L
    }
}
