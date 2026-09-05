package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.domain.model.BRIGHTNESS_SYSTEM_DEFAULT
import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.domain.model.PresentCommandType
import com.avinash.relaydisplay.domain.model.PresentationId
import com.avinash.relaydisplay.domain.model.PresentationPhase
import com.avinash.relaydisplay.domain.model.PresentationRotation
import com.avinash.relaydisplay.domain.model.PresentationSnapshot
import com.avinash.relaydisplay.protocol.PresentationEnvelope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the companion display is showing right now. */
sealed interface PresentedContent {
    data object Waiting : PresentedContent
    data object Blank : PresentedContent
    data class Text(val text: String) : PresentedContent
    data class Qr(val payload: String, val caption: String?) : PresentedContent
    data class Link(val url: String, val title: String?, val host: String?) : PresentedContent
    data class Image(val transferId: UUID, val file: File, val displayName: String) : PresentedContent
    data class Pdf(
        val transferId: UUID,
        val file: File,
        val displayName: String,
        val pageIndex: Int,
        val pageCount: Int,
    ) : PresentedContent
    data object Mirror : PresentedContent

    /** The phase this content corresponds to, so the wire report and the screen cannot diverge. */
    val phase: PresentationPhase
        get() = when (this) {
            Waiting -> PresentationPhase.NO_PRESENTATION
            Blank -> PresentationPhase.NO_PRESENTATION
            is Text -> PresentationPhase.SHOWING_TEXT
            is Qr -> PresentationPhase.SHOWING_QR
            is Link -> PresentationPhase.SHOWING_LINK
            is Image -> PresentationPhase.SHOWING_IMAGE
            is Pdf -> PresentationPhase.SHOWING_PDF
            Mirror -> PresentationPhase.MIRRORING
        }

    /** Text that Copy should place on the clipboard, or null when Copy is meaningless here. */
    val copyableText: String?
        get() = when (this) {
            is Text -> text
            is Qr -> payload
            is Link -> url
            else -> null
        }
}

/** Presentation knobs the controller can change without sending new content. */
data class PresentationOptions(
    val fitMode: FitMode = FitMode.DEFAULT,
    val brightness: Int = BRIGHTNESS_SYSTEM_DEFAULT,
    val immersive: Boolean = true,
    val rotation: PresentationRotation = PresentationRotation.DEG_0,
    val keepScreenAwake: Boolean = true,
)

data class PresentationState(
    val content: PresentedContent = PresentedContent.Waiting,
    val options: PresentationOptions = PresentationOptions(),
    val disconnectedOverlay: Boolean = false,
    /** Identity of what is on screen. Always consistent with [content]. */
    val snapshot: PresentationSnapshot = PresentationSnapshot.idle(NO_SESSION),
) {
    val hasContent: Boolean get() = content != PresentedContent.Waiting

    companion object {
        /** Used before any session exists. Nothing from a real session can ever match it. */
        const val NO_SESSION = ""
    }
}

/**
 * The single authoritative owner of what this display is showing.
 *
 * Every mutation goes through here and every mutation produces a new [PresentationSnapshot] with
 * a strictly higher revision, which is what lets both devices agree on ordering.
 *
 * Revisions use a Lamport clock: whenever a message arrives carrying revision R, the local
 * counter jumps to `max(local, R + 1)`. That means the Controller assigning revisions and the
 * Display assigning one for a local close can never collide or go backwards, without either side
 * having to be the sole allocator.
 *
 * Application-scoped, so a rotation or a trip through recents does not lose what is on screen.
 */
class PresentationController {

    private val _state = MutableStateFlow(PresentationState())
    val state: StateFlow<PresentationState> = _state.asStateFlow()

    private val lock = Any()

    /** Lamport clock for this session. Reset when a new session begins. */
    private var nextRevision: Long = 1

    private var contentBeforeBlank: PresentedContent = PresentedContent.Waiting

    // -- session lifecycle -----------------------------------------------------------------

    /**
     * Binds this controller to a new authenticated session.
     *
     * Everything from the previous session is dropped: a stale acknowledgement arriving after
     * this point carries the old session id and is rejected by [shouldAccept].
     */
    fun onSessionStarted(sessionId: String) {
        synchronized(lock) {
            nextRevision = 1
            contentBeforeBlank = PresentedContent.Waiting
            _state.value = PresentationState(
                content = PresentedContent.Waiting,
                options = _state.value.options,
                disconnectedOverlay = false,
                snapshot = PresentationSnapshot.idle(sessionId),
            )
        }
    }

    /** Marks the session gone. Content may stay on screen; its identity no longer matches a peer. */
    fun onSessionEnded() {
        synchronized(lock) {
            _state.value = _state.value.copy(disconnectedOverlay = true)
        }
    }

    fun snapshot(): PresentationSnapshot = _state.value.snapshot

    /** The revision to stamp on something originated locally. Advances the clock. */
    fun allocateRevision(): Long = synchronized(lock) { nextRevision++ }

    /** Folds a peer-supplied revision into the clock so the next local one is strictly higher. */
    private fun observeRevision(revision: Long) {
        if (revision >= nextRevision) nextRevision = revision + 1
    }

    // -- applying remote content -----------------------------------------------------------

    /**
     * Shows content the peer sent, if its identity is current.
     *
     * Returns the snapshot now in force, or null when the message was stale and nothing changed.
     * A null return is not an error: it means an older presentation arrived late and was
     * correctly ignored.
     */
    fun applyRemoteContent(
        sessionId: String,
        envelope: PresentationEnvelope?,
        content: PresentedContent,
        transferId: UUID? = null,
    ): PresentationSnapshot? = synchronized(lock) {
        val current = _state.value.snapshot
        if (current.sessionId != sessionId) return null

        // A peer that sends no envelope is not tracking presentations; assign identity locally so
        // the state machine still has something monotonic to reason about.
        val candidateRevision = envelope?.revision ?: nextRevision
        val candidateId = envelope?.presentationId ?: UUID.randomUUID()

        if (candidateRevision <= current.revision) return null
        observeRevision(candidateRevision)

        contentBeforeBlank = content
        val snapshot = PresentationSnapshot(
            id = PresentationId(sessionId, candidateId, candidateRevision),
            phase = content.phase,
            transferId = transferId,
        )
        _state.value = _state.value.copy(
            content = content,
            disconnectedOverlay = false,
            snapshot = snapshot,
        )
        return snapshot
    }

    /**
     * Closes whatever is showing, on this device's own initiative.
     *
     * Idempotent: closing an already-closed presentation returns null and changes nothing, so a
     * double tap or a retry after a lost acknowledgement is harmless.
     */
    fun dismissLocally(): PresentationSnapshot? = synchronized(lock) {
        val current = _state.value
        if (!current.hasContent) return null
        val revision = nextRevision++
        val snapshot = PresentationSnapshot(
            id = PresentationId(current.snapshot.sessionId, current.snapshot.id.presentationId, revision),
            phase = PresentationPhase.NO_PRESENTATION,
        )
        contentBeforeBlank = PresentedContent.Waiting
        _state.value = current.copy(
            content = PresentedContent.Waiting,
            disconnectedOverlay = false,
            snapshot = snapshot,
        )
        return snapshot
    }

    /**
     * Closes at the peer's request.
     *
     * Returns the resulting snapshot, including when nothing was showing, because the peer needs
     * a report either way -- that is what makes a retried dismissal safe.
     */
    fun dismissRemote(sessionId: String, revision: Long): PresentationSnapshot? = synchronized(lock) {
        val current = _state.value
        if (current.snapshot.sessionId != sessionId) return null
        observeRevision(revision)
        if (!current.hasContent) return current.snapshot
        val effective = maxOf(revision, nextRevision++)
        observeRevision(effective)
        val snapshot = PresentationSnapshot(
            id = PresentationId(sessionId, current.snapshot.id.presentationId, effective),
            phase = PresentationPhase.NO_PRESENTATION,
        )
        contentBeforeBlank = PresentedContent.Waiting
        _state.value = current.copy(
            content = PresentedContent.Waiting,
            disconnectedOverlay = false,
            snapshot = snapshot,
        )
        return snapshot
    }

    // -- convenience wrappers used by the router -------------------------------------------

    fun showText(sessionId: String, envelope: PresentationEnvelope?, text: String): PresentationSnapshot? =
        applyRemoteContent(sessionId, envelope, PresentedContent.Text(text))

    fun showQr(
        sessionId: String,
        envelope: PresentationEnvelope?,
        payload: String,
        caption: String?,
    ): PresentationSnapshot? = applyRemoteContent(sessionId, envelope, PresentedContent.Qr(payload, caption))

    /** Refuses a link whose scheme this app would never offer to open. */
    fun showLink(
        sessionId: String,
        envelope: PresentationEnvelope?,
        url: String,
        title: String?,
    ): PresentationSnapshot? {
        if (!UrlValidation.isAcceptable(url)) return null
        return applyRemoteContent(
            sessionId,
            envelope,
            PresentedContent.Link(url, title, UrlValidation.hostOf(url)),
        )
    }

    fun showImage(
        sessionId: String,
        envelope: PresentationEnvelope?,
        transferId: UUID,
        file: File,
        displayName: String,
    ): PresentationSnapshot? = applyRemoteContent(
        sessionId,
        envelope,
        PresentedContent.Image(transferId, file, displayName),
        transferId,
    )

    fun showPdf(
        sessionId: String,
        envelope: PresentationEnvelope?,
        transferId: UUID,
        file: File,
        displayName: String,
        pageCount: Int,
    ): PresentationSnapshot? = applyRemoteContent(
        sessionId,
        envelope,
        PresentedContent.Pdf(transferId, file, displayName, pageIndex = 0, pageCount = pageCount),
        transferId,
    )

    fun showMirror(sessionId: String, envelope: PresentationEnvelope?): PresentationSnapshot? =
        applyRemoteContent(sessionId, envelope, PresentedContent.Mirror)

    /** Page changes keep the same presentation; only the page index moves. */
    fun setPdfPage(pageIndex: Int) {
        synchronized(lock) {
            val current = _state.value.content
            if (current !is PresentedContent.Pdf) return
            val clamped = pageIndex.coerceIn(0, maxOf(0, current.pageCount - 1))
            if (clamped == current.pageIndex) return
            contentBeforeBlank = current.copy(pageIndex = clamped)
            _state.value = _state.value.copy(content = contentBeforeBlank)
        }
    }

    fun blank() {
        synchronized(lock) {
            val current = _state.value.content
            if (current == PresentedContent.Blank) return
            contentBeforeBlank = current
            _state.value = _state.value.copy(content = PresentedContent.Blank)
        }
    }

    fun unblank() {
        synchronized(lock) {
            if (_state.value.content == PresentedContent.Blank) {
                _state.value = _state.value.copy(content = contentBeforeBlank)
            }
        }
    }

    fun updateOptions(transform: (PresentationOptions) -> PresentationOptions) {
        synchronized(lock) {
            _state.value = _state.value.copy(options = transform(_state.value.options))
        }
    }

    fun setDisconnectedOverlay(visible: Boolean) {
        synchronized(lock) {
            _state.value = _state.value.copy(disconnectedOverlay = visible)
        }
    }

    /**
     * Applies a presentation command.
     *
     * Returns false for an argument that makes no sense, so the peer gets an explicit error
     * instead of the command being silently ignored. Every command is idempotent.
     */
    fun applyCommand(command: PresentCommandType, intArg: Int): Boolean = when (command) {
        PresentCommandType.BLANK -> {
            blank()
            true
        }
        PresentCommandType.SHOW_WAITING -> {
            dismissRemote(_state.value.snapshot.sessionId, allocateRevision())
            true
        }
        PresentCommandType.SET_FIT_MODE -> FitMode.fromWire(intArg)?.let { fit ->
            updateOptions { it.copy(fitMode = fit) }
            true
        } ?: false
        PresentCommandType.SET_BRIGHTNESS ->
            if (intArg != BRIGHTNESS_SYSTEM_DEFAULT && intArg !in 0..100) {
                false
            } else {
                updateOptions { it.copy(brightness = intArg) }
                true
            }
        PresentCommandType.SET_IMMERSIVE -> {
            updateOptions { it.copy(immersive = intArg != 0) }
            true
        }
        PresentCommandType.SET_ROTATION -> PresentationRotation.fromWire(intArg)?.let { rotation ->
            updateOptions { it.copy(rotation = rotation) }
            true
        } ?: false
        PresentCommandType.SET_KEEP_AWAKE -> {
            updateOptions { it.copy(keepScreenAwake = intArg != 0) }
            true
        }
    }

    fun currentFileKind(): ContentKind? = when (_state.value.content) {
        is PresentedContent.Image -> ContentKind.IMAGE
        is PresentedContent.Pdf -> ContentKind.PDF
        else -> null
    }
}
