package com.avinash.relaydisplay.domain.model

import java.util.UUID

/**
 * What a display is doing with content, as a single authoritative value.
 *
 * This is the vocabulary both devices speak. The Display owns the truth; the Controller holds a
 * copy that is only ever updated by an acknowledged report from the Display, never by optimism
 * about what it just sent.
 */
enum class PresentationPhase(val wireCode: Int) {
    NO_PRESENTATION(0),
    PREPARING_CONTENT(1),
    TRANSFERRING(2),
    SHOWING_TEXT(3),
    SHOWING_QR(4),
    SHOWING_LINK(5),
    SHOWING_IMAGE(6),
    SHOWING_PDF(7),
    MIRRORING(8),
    CLOSING(9),
    PRESENTATION_ERROR(10),
    ;

    /** True while something is actually on screen that the user can see and close. */
    val isShowingContent: Boolean
        get() = this == SHOWING_TEXT || this == SHOWING_QR || this == SHOWING_LINK ||
            this == SHOWING_IMAGE || this == SHOWING_PDF || this == MIRRORING

    companion object {
        fun fromWire(code: Int): PresentationPhase? = entries.firstOrNull { it.wireCode == code }
    }
}

/**
 * Identity of one presentation within one session.
 *
 * Three fields, and each one exists to reject a specific class of stale event:
 *
 * - [sessionId] rejects anything from a previous connection. Both peers derive the same value
 *   from the handshake transcript, so it costs no extra round trip and cannot be forged by a
 *   third party that did not complete the handshake.
 * - [presentationId] identifies one piece of content, so an acknowledgement for text A cannot be
 *   applied to text B.
 * - [revision] increases monotonically within a session, so a delayed report about an older
 *   presentation can never overwrite a newer one -- which is the actual bug behind "the
 *   Controller says it is showing something the Display already closed".
 */
data class PresentationId(
    val sessionId: String,
    val presentationId: UUID,
    val revision: Long,
) {
    init {
        require(revision >= 0) { "revision must not be negative" }
    }

    /** Safe for logs: neither half is secret, but neither is fully printed either. */
    fun shortLabel(): String = "${sessionId.take(6)}/${presentationId.toString().take(8)}#$revision"

    companion object {
        /** The identity used before any content has been shown in a session. */
        fun initial(sessionId: String): PresentationId =
            PresentationId(sessionId, ZERO_UUID, 0)

        val ZERO_UUID: UUID = UUID(0, 0)
    }
}

/**
 * The complete, reportable presentation state of a display.
 *
 * This is what travels in a `PRESENTATION_STATE` message and what both sides store. It is
 * deliberately small and contains no content: a peer learns *that* text is showing, never what
 * the text says.
 */
data class PresentationSnapshot(
    val id: PresentationId,
    val phase: PresentationPhase,
    /** Set only for file-backed content, so a transfer can be correlated. */
    val transferId: UUID? = null,
) {
    val sessionId: String get() = id.sessionId
    val revision: Long get() = id.revision

    /**
     * Whether [candidate] should replace this snapshot.
     *
     * The whole stale-event defence in one function:
     * - a different session is always rejected, because it belongs to a connection that is gone;
     * - an equal or older revision is rejected, because a newer state is already in force.
     */
    fun shouldAccept(candidate: PresentationSnapshot): Boolean = when {
        candidate.sessionId != sessionId -> false
        candidate.revision <= revision -> false
        else -> true
    }

    companion object {
        fun idle(sessionId: String): PresentationSnapshot =
            PresentationSnapshot(PresentationId.initial(sessionId), PresentationPhase.NO_PRESENTATION)
    }
}

/**
 * How far a piece of content the Controller sent has actually got.
 *
 * The distinction the product needs: "I put it on the wire" is not "the other phone drew it",
 * and neither is "the other phone still has it on screen".
 */
enum class RemoteContentStatus {
    /** Nothing sent in this session. */
    IDLE,

    /** Handed to the transport; the Display has not confirmed anything yet. */
    SENDING,

    /** The Display acknowledged receipt but has not reported it as rendered. */
    SENT,

    /** The Display reported it is rendering this exact presentation. */
    DISPLAYED,

    /** The Display reported it closed this presentation, from either end. */
    DISPLAY_CLOSED,

    /** The session ended while this presentation was outstanding. */
    DISCONNECTED,

    /** The Display reported it could not render this content. */
    FAILED,
}
