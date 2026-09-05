package com.avinash.relaydisplay.mirroring

import android.media.projection.MediaProjection
import android.view.Surface
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.network.session.SessionHost
import com.avinash.relaydisplay.protocol.MirrorConfig
import com.avinash.relaydisplay.protocol.MessageCodec
import com.avinash.relaydisplay.network.session.RelaySession
import com.avinash.relaydisplay.protocol.MirrorFrame
import com.avinash.relaydisplay.protocol.MirrorStart
import com.avinash.relaydisplay.protocol.MirrorStop
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
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
 *
 * That is right for frames and wrong for everything else this class sends. `MirrorStart`,
 * `MirrorConfig` and `MirrorStop` currently take the same lossy path, so a busy link can drop the
 * messages that start, configure or stop a mirror. They share one bounded queue with the
 * heartbeat, which is why a mirror can also take the whole session down; see
 * `RelaySession.heartbeatLoop`.
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

    /**
     * Watches the session that capture was started against.
     *
     * The invariant: **there is never an active capture without the exact authenticated session
     * that started it.** Capture used to read `engine.activeSession.value` once and hold that
     * reference forever, so when the session died the encoder kept running, the MediaProjection
     * stayed held and Android's recording indicator stayed lit -- for a viewer that was gone.
     * Observed on hardware: session ended 03:28:10, and at 03:31:56 the phone was still capturing
     * and had discarded 11,520 frames for nobody.
     *
     * A reconnect deliberately does **not** rebind capture to the new session. Resuming silently
     * would mean the user's screen starts flowing to a session they never granted consent for.
     * Stopping and asking again is the safe choice.
     */
    private var sessionWatch: Job? = null

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
                // KNOWN DEFECT: codec configuration must arrive before any frame, but this is
                // trySend on the same 8-slot queue the video shares, so under pressure it is
                // silently dropped and the display never configures its decoder. A comment here
                // previously claimed this used "the suspending path"; it never did. Reliable,
                // acknowledged mirror negotiation is the fix -- see docs/IMPLEMENTATION_STATUS.md.
                session.trySend(
                    MirrorConfig(UUID.randomUUID(), width, height, rotationDegrees = 0, csd0 = csd0, csd1 = csd1),
                )
            }

            override fun onFrame(frame: EncodedFrame) {
                val accepted = sendFragmented(session, frame)
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
            sessionWatch?.cancel()
            sessionWatch = null
            return@withLock false
        }

        encoder = newEncoder

        // From here on, capture is bound to this exact session object. Identity, not equality:
        // a reconnect produces a different RelaySession and must stop the old capture.
        sessionWatch = scope.launch {
            engine.activeSession.collect { current ->
                if (current !== session) {
                    diagnostics.warn("mirror", "session ended or changed; stopping capture")
                    // NonCancellable because stopLocked cancels this very job; without it the
                    // teardown would abort partway and leave the projection held.
                    withContext(NonCancellable) { stop("session ended") }
                }
            }
        }

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
        sessionWatch?.cancel()
        sessionWatch = null
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

    private var assemblySequence: Long = -1
    private var assembly: Array<ByteArray?>? = null
    private var assemblyBytes: Int = 0

    /**
     * Reassembles a frame from its fragments before handing it to the decoder.
     *
     * Only one frame is ever in flight: a fragment for a newer frame abandons whatever was
     * partially assembled, because a late fragment of a superseded frame is worth nothing and
     * holding several partial frames is unbounded memory for no benefit. An incomplete frame is
     * simply never submitted.
     */
    fun onMirrorFrame(frame: MirrorFrame) {
        if (frame.fragmentCount <= 1) {
            assembly = null
            decoder?.submit(frame.data, frame.presentationTimeUs, frame.keyFrame)
            return
        }
        if (frame.fragmentCount > MessageCodec.MAX_MIRROR_FRAGMENTS ||
            frame.fragmentIndex !in 0 until frame.fragmentCount
        ) {
            diagnostics.warn("mirror", "discarding a fragment with an implausible index or count")
            assembly = null
            return
        }

        if (frame.frameSequence != assemblySequence) {
            assemblySequence = frame.frameSequence
            assembly = arrayOfNulls(frame.fragmentCount)
            assemblyBytes = 0
        }
        val parts = assembly ?: return
        if (parts.size != frame.fragmentCount) {
            assembly = null
            return
        }
        if (parts[frame.fragmentIndex] == null) {
            parts[frame.fragmentIndex] = frame.data
            assemblyBytes += frame.data.size
        }
        if (parts.any { it == null }) return

        val whole = ByteArray(assemblyBytes)
        var at = 0
        for (part in parts) {
            part!!.copyInto(whole, at)
            at += part.size
        }
        assembly = null
        decoder?.submit(whole, frame.presentationTimeUs, frame.keyFrame)
    }

    fun onMirrorStopped() {
        releaseDecoder()
        pendingConfig = null
        _state.value = MirrorState.Idle
    }

    private val frameSequence = AtomicLong(0)

    /**
     * Splits one encoded frame across as many records as it needs.
     *
     * A full-screen keyframe -- the thing an app switch produces -- is routinely larger than the
     * protocol's per-field cap. Sending it whole made the receiver reject the record with
     * PAYLOAD_TOO_LARGE and drop the entire session, which is the "mirror dies when I open another
     * app" failure.
     *
     * A frame too large even to fragment is dropped rather than sent, because no single frame is
     * worth a session. Returns false only when a fragment could not be queued, which the caller
     * counts as a dropped frame exactly as before.
     */
    private fun sendFragmented(session: RelaySession, frame: EncodedFrame): Boolean {
        val limit = MessageCodec.MAX_MIRROR_FRAGMENT_BYTES
        val total = frame.data.size
        val count = (total + limit - 1) / limit

        if (count > MessageCodec.MAX_MIRROR_FRAGMENTS) {
            // Bounded by construction: a frame this large is a codec anomaly, not something to
            // buffer. Drop it and ask for a fresh keyframe so the display can resynchronise.
            diagnostics.warn("mirror", "frame of ${total}B exceeds the fragment budget; dropped")
            encoder?.requestKeyFrame()
            return false
        }
        if (count <= 1) {
            return session.trySend(
                MirrorFrame(
                    UUID.randomUUID(), frame.presentationTimeUs, frame.keyFrame, frame.data,
                    frameSequence = frameSequence.incrementAndGet(), fragmentIndex = 0, fragmentCount = 1,
                ),
            )
        }

        val sequence = frameSequence.incrementAndGet()
        for (index in 0 until count) {
            val from = index * limit
            val slice = frame.data.copyOfRange(from, minOf(from + limit, total))
            val queued = session.trySend(
                MirrorFrame(
                    UUID.randomUUID(), frame.presentationTimeUs, frame.keyFrame, slice,
                    frameSequence = sequence, fragmentIndex = index, fragmentCount = count,
                ),
            )
            // A partial frame is useless, so stop as soon as one fragment cannot be queued rather
            // than filling the queue with fragments the receiver will discard anyway.
            if (!queued) return false
        }
        return true
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
