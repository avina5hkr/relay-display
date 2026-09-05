package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.domain.model.PresentationId
import com.avinash.relaydisplay.domain.model.PresentationPhase
import com.avinash.relaydisplay.domain.model.PresentationSnapshot
import com.avinash.relaydisplay.domain.model.RemoteContentStatus
import com.avinash.relaydisplay.protocol.PresentationEnvelope
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the Controller believes the Display is showing, and how sure it is.
 *
 * [status] is deliberately separate from [remote]: the Controller may have *sent* something the
 * Display has not *rendered*, and the UI must be able to say which.
 */
data class RemotePresentationState(
    val sessionId: String = PresentationState.NO_SESSION,
    /** The last state the Display actually reported. Never guessed. */
    val remote: PresentationSnapshot = PresentationSnapshot.idle(PresentationState.NO_SESSION),
    /** The presentation this Controller most recently originated, if any. */
    val outgoing: PresentationId? = null,
    val outgoingKind: OutgoingKind? = null,
    val status: RemoteContentStatus = RemoteContentStatus.IDLE,
    val errorReason: String? = null,
) {
    /** True only when the Display has confirmed it is rendering content right now. */
    val displayIsShowingContent: Boolean get() = remote.phase.isShowingContent

    /** Whether a "close it over there" action is worth offering. */
    val canDismissRemotely: Boolean get() = displayIsShowingContent
}

/** What the Controller sent, for wording the status line. */
enum class OutgoingKind { TEXT, QR, LINK, IMAGE, PDF, MIRROR }

/**
 * The Controller's authoritative record of the remote display.
 *
 * The bug this class exists to kill: the old code set a local string ("Sent as text.") the moment
 * it handed a message to the transport, and nothing ever corrected it. Here nothing reaches
 * [RemoteContentStatus.DISPLAYED] until the Display reports that exact presentation as rendered,
 * and any report from an older session or an older revision is dropped.
 */
class RemotePresentationTracker {

    private val _state = MutableStateFlow(RemotePresentationState())
    val state: StateFlow<RemotePresentationState> = _state.asStateFlow()

    private val lock = Any()
    private var nextRevision: Long = 1

    /** Binds to a new session and forgets everything about the previous one. */
    fun onSessionStarted(sessionId: String) {
        synchronized(lock) {
            nextRevision = 1
            _state.value = RemotePresentationState(
                sessionId = sessionId,
                remote = PresentationSnapshot.idle(sessionId),
            )
        }
    }

    /**
     * Marks the session gone.
     *
     * Anything outstanding becomes [RemoteContentStatus.DISCONNECTED] rather than staying on
     * "Displayed", because once the link is down this device genuinely does not know.
     */
    fun onSessionEnded() {
        synchronized(lock) {
            val current = _state.value
            _state.value = current.copy(
                remote = PresentationSnapshot.idle(current.sessionId),
                status = if (current.status == RemoteContentStatus.IDLE) {
                    RemoteContentStatus.IDLE
                } else {
                    RemoteContentStatus.DISCONNECTED
                },
            )
        }
    }

    /** Allocates identity for something about to be sent, and moves the status to Sending. */
    fun beginOutgoing(kind: OutgoingKind): PresentationEnvelope? = synchronized(lock) {
        val sessionId = _state.value.sessionId
        if (sessionId == PresentationState.NO_SESSION) return null
        val revision = nextRevision++
        val presentationId = UUID.randomUUID()
        _state.value = _state.value.copy(
            outgoing = PresentationId(sessionId, presentationId, revision),
            outgoingKind = kind,
            status = RemoteContentStatus.SENDING,
            errorReason = null,
        )
        return PresentationEnvelope(presentationId, revision)
    }

    /** The transport accepted the message. Still not "displayed". */
    fun markSent() {
        synchronized(lock) {
            if (_state.value.status == RemoteContentStatus.SENDING) {
                _state.value = _state.value.copy(status = RemoteContentStatus.SENT)
            }
        }
    }

    fun markFailed(reason: String) {
        synchronized(lock) {
            _state.value = _state.value.copy(
                status = RemoteContentStatus.FAILED,
                errorReason = reason,
            )
        }
    }

    /**
     * Applies a state report from the Display.
     *
     * Returns false when the report was stale and nothing changed. Both rejection rules live in
     * [PresentationSnapshot.shouldAccept]: wrong session, or a revision that is not newer.
     */
    fun onRemoteState(report: PresentationSnapshot): Boolean = synchronized(lock) {
        val current = _state.value
        if (report.sessionId != current.sessionId) return false
        if (!current.remote.shouldAccept(report)) return false

        if (report.revision >= nextRevision) nextRevision = report.revision + 1

        val status = when {
            report.phase == PresentationPhase.PRESENTATION_ERROR -> RemoteContentStatus.FAILED
            report.phase.isShowingContent -> {
                // Only claim "Displayed" when the Display is showing the presentation we sent.
                val outgoing = current.outgoing
                if (outgoing != null && outgoing.presentationId == report.id.presentationId) {
                    RemoteContentStatus.DISPLAYED
                } else {
                    // Something else is on screen; report honestly rather than claiming ours is.
                    RemoteContentStatus.DISPLAY_CLOSED
                }
            }
            current.status == RemoteContentStatus.IDLE -> RemoteContentStatus.IDLE
            else -> RemoteContentStatus.DISPLAY_CLOSED
        }

        _state.value = current.copy(remote = report, status = status)
        return true
    }

    /** Identity to stamp on a dismissal this Controller originates. */
    fun allocateDismissRevision(): Long = synchronized(lock) { nextRevision++ }

    /**
     * Reconciles after a reconnect.
     *
     * Deterministic and safety-first: if the Display reports nothing showing, the Controller
     * adopts that, full stop. Stale local belief never resurrects content, and never restarts
     * mirroring -- that always needs a fresh explicit user action and fresh system consent.
     */
    fun reconcile(report: PresentationSnapshot) {
        synchronized(lock) {
            val current = _state.value
            if (report.sessionId != current.sessionId) return
            if (report.revision >= nextRevision) nextRevision = report.revision + 1
            val status = if (report.phase.isShowingContent) {
                val outgoing = current.outgoing
                if (outgoing != null && outgoing.presentationId == report.id.presentationId) {
                    RemoteContentStatus.DISPLAYED
                } else {
                    RemoteContentStatus.DISPLAY_CLOSED
                }
            } else {
                if (current.status == RemoteContentStatus.IDLE) {
                    RemoteContentStatus.IDLE
                } else {
                    RemoteContentStatus.DISPLAY_CLOSED
                }
            }
            _state.value = current.copy(remote = report, status = status)
        }
    }

    fun clearStatus() {
        synchronized(lock) {
            _state.value = _state.value.copy(status = RemoteContentStatus.IDLE, errorReason = null)
        }
    }
}
