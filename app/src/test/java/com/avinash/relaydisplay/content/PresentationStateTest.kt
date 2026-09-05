package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.domain.model.PresentationId
import com.avinash.relaydisplay.domain.model.PresentationPhase
import com.avinash.relaydisplay.domain.model.PresentationSnapshot
import com.avinash.relaydisplay.domain.model.RemoteContentStatus
import com.avinash.relaydisplay.protocol.PresentationEnvelope
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Display's authoritative presentation state.
 *
 * These are the tests that pin the actual reported bug: the Display can close content itself, a
 * late message from an older presentation cannot resurrect it, and nothing from a previous
 * session can touch the current one.
 */
class PresentationControllerTest {

    private lateinit var controller: PresentationController
    private val session = "session-a"
    private val otherSession = "session-b"

    @Before
    fun setUp() {
        controller = PresentationController()
        controller.onSessionStarted(session)
    }

    private fun envelope(revision: Long, id: UUID = UUID.randomUUID()) = PresentationEnvelope(id, revision)

    @Test
    fun `a fresh session starts with nothing showing`() {
        val state = controller.state.value
        assertEquals(PresentedContent.Waiting, state.content)
        assertEquals(PresentationPhase.NO_PRESENTATION, state.snapshot.phase)
        assertEquals(session, state.snapshot.sessionId)
        assertFalse(state.hasContent)
    }

    @Test
    fun `showing text sets the phase and identity`() {
        val snapshot = controller.showText(session, envelope(1), "hello")
        assertNotNull(snapshot)
        assertEquals(PresentationPhase.SHOWING_TEXT, snapshot!!.phase)
        assertEquals(1L, snapshot.revision)
        assertEquals(PresentedContent.Text("hello"), controller.state.value.content)
    }

    @Test
    fun `the display can close text locally`() {
        controller.showText(session, envelope(1), "hello")
        val closed = controller.dismissLocally()
        assertNotNull("a local close must produce a reportable snapshot", closed)
        assertEquals(PresentationPhase.NO_PRESENTATION, closed!!.phase)
        assertTrue("the close must outrank the content it closed", closed.revision > 1)
        assertEquals(PresentedContent.Waiting, controller.state.value.content)
    }

    @Test
    fun `closing an already closed presentation is a safe no-op`() {
        controller.showText(session, envelope(1), "hello")
        assertNotNull(controller.dismissLocally())
        assertNull("a second close changes nothing", controller.dismissLocally())
        assertNull(controller.dismissLocally())
        assertEquals(PresentedContent.Waiting, controller.state.value.content)
    }

    @Test
    fun `a local close while disconnected still frees the screen`() {
        controller.showText(session, envelope(1), "hello")
        // A dropped link does not clear content by itself; the user still gets their screen back.
        controller.onSessionEnded()
        val closed = controller.dismissLocally()
        assertNotNull(closed)
        assertEquals(PresentedContent.Waiting, controller.state.value.content)
    }

    @Test
    fun `text A then text B cannot revert to A`() {
        val a = envelope(1)
        val b = envelope(2)
        controller.showText(session, a, "A")
        controller.showText(session, b, "B")
        assertEquals(PresentedContent.Text("B"), controller.state.value.content)

        // A duplicate of the older message arrives late.
        assertNull("the older revision must be refused", controller.showText(session, a, "A"))
        assertEquals(PresentedContent.Text("B"), controller.state.value.content)
    }

    @Test
    fun `a message from a previous session is ignored`() {
        controller.showText(session, envelope(1), "current")
        assertNull(controller.showText(otherSession, envelope(99), "from the past"))
        assertEquals(PresentedContent.Text("current"), controller.state.value.content)
    }

    @Test
    fun `a dismissal for a previous session is ignored`() {
        controller.showText(session, envelope(1), "current")
        assertNull(controller.dismissRemote(otherSession, 99))
        assertEquals(PresentedContent.Text("current"), controller.state.value.content)
    }

    @Test
    fun `a remote dismissal closes and reports`() {
        controller.showText(session, envelope(1), "hello")
        val snapshot = controller.dismissRemote(session, 5)
        assertNotNull(snapshot)
        assertEquals(PresentationPhase.NO_PRESENTATION, snapshot!!.phase)
        assertEquals(PresentedContent.Waiting, controller.state.value.content)
    }

    @Test
    fun `a repeated remote dismissal still reports state`() {
        controller.showText(session, envelope(1), "hello")
        controller.dismissRemote(session, 5)
        // The retry finds nothing to close but must still hand back something to report.
        val again = controller.dismissRemote(session, 6)
        assertNotNull("a retried dismissal must be answerable", again)
        assertEquals(PresentationPhase.NO_PRESENTATION, again!!.phase)
    }

    @Test
    fun `the revision clock never goes backwards across both writers`() {
        controller.showText(session, envelope(10), "remote wins the clock")
        val local = controller.dismissLocally()
        assertTrue("a local close after revision 10 must exceed it", local!!.revision > 10)

        controller.showText(session, envelope(local.revision + 1), "next")
        val second = controller.dismissLocally()
        assertTrue(second!!.revision > local.revision)
    }

    @Test
    fun `starting a new session drops everything from the old one`() {
        controller.showText(session, envelope(5), "old")
        controller.onSessionStarted(otherSession)
        assertEquals(PresentedContent.Waiting, controller.state.value.content)
        assertEquals(otherSession, controller.state.value.snapshot.sessionId)
        // Revision 1 is valid again because it is a different session.
        assertNotNull(controller.showText(otherSession, envelope(1), "new"))
    }

    @Test
    fun `text then mirroring ends in mirroring`() {
        controller.showText(session, envelope(1), "hello")
        val mirror = controller.showMirror(session, envelope(2))
        assertEquals(PresentationPhase.MIRRORING, mirror!!.phase)
        assertEquals(PresentedContent.Mirror, controller.state.value.content)
    }

    @Test
    fun `a link with a refused scheme never reaches the screen`() {
        assertNull(controller.showLink(session, envelope(1), "javascript:alert(1)", null))
        assertEquals(PresentedContent.Waiting, controller.state.value.content)
    }

    @Test
    fun `copyable text is offered only where it means something`() {
        assertEquals("hello", PresentedContent.Text("hello").copyableText)
        assertEquals("payload", PresentedContent.Qr("payload", null).copyableText)
        assertEquals("https://example.com", PresentedContent.Link("https://example.com", null, "example.com").copyableText)
        assertNull(PresentedContent.Mirror.copyableText)
        assertNull(PresentedContent.Waiting.copyableText)
        assertNull(
            PresentedContent.Image(UUID.randomUUID(), java.io.File("/tmp/x"), "x.png").copyableText,
        )
    }

    @Test
    fun `content phase always matches what is on screen`() {
        controller.showText(session, envelope(1), "t")
        assertEquals(controller.state.value.content.phase, controller.snapshot().phase)
        controller.showQr(session, envelope(2), "q", null)
        assertEquals(controller.state.value.content.phase, controller.snapshot().phase)
        controller.dismissLocally()
        assertEquals(controller.state.value.content.phase, controller.snapshot().phase)
    }
}

/**
 * The Controller's belief about the remote screen.
 *
 * The bug these tests pin: the old code claimed content was displayed the moment it was sent.
 * Nothing here reaches DISPLAYED without a matching report from the Display.
 */
class RemotePresentationTrackerTest {

    private lateinit var tracker: RemotePresentationTracker
    private val session = "session-a"

    @Before
    fun setUp() {
        tracker = RemotePresentationTracker()
        tracker.onSessionStarted(session)
    }

    private fun report(revision: Long, phase: PresentationPhase, id: UUID) =
        PresentationSnapshot(PresentationId(session, id, revision), phase)

    @Test
    fun `sending is not displaying`() {
        val envelope = tracker.beginOutgoing(OutgoingKind.TEXT)!!
        assertEquals(RemoteContentStatus.SENDING, tracker.state.value.status)
        tracker.markSent()
        assertEquals(
            "sent is not the same as rendered",
            RemoteContentStatus.SENT,
            tracker.state.value.status,
        )
        assertFalse(tracker.state.value.displayIsShowingContent)
        assertNotNull(envelope)
    }

    @Test
    fun `only the display's report reaches displayed`() {
        val envelope = tracker.beginOutgoing(OutgoingKind.TEXT)!!
        tracker.markSent()
        assertTrue(
            tracker.onRemoteState(
                report(envelope.revision, PresentationPhase.SHOWING_TEXT, envelope.presentationId),
            ),
        )
        assertEquals(RemoteContentStatus.DISPLAYED, tracker.state.value.status)
        assertTrue(tracker.state.value.canDismissRemotely)
    }

    @Test
    fun `the display closing it is reflected immediately`() {
        val envelope = tracker.beginOutgoing(OutgoingKind.TEXT)!!
        tracker.markSent()
        tracker.onRemoteState(report(envelope.revision, PresentationPhase.SHOWING_TEXT, envelope.presentationId))

        tracker.onRemoteState(
            report(envelope.revision + 1, PresentationPhase.NO_PRESENTATION, envelope.presentationId),
        )
        assertEquals(RemoteContentStatus.DISPLAY_CLOSED, tracker.state.value.status)
        assertFalse(
            "the controller must stop claiming the content is up",
            tracker.state.value.displayIsShowingContent,
        )
        assertFalse(tracker.state.value.canDismissRemotely)
    }

    @Test
    fun `a duplicate dismissal report is harmless`() {
        val envelope = tracker.beginOutgoing(OutgoingKind.TEXT)!!
        tracker.markSent()
        tracker.onRemoteState(report(envelope.revision, PresentationPhase.SHOWING_TEXT, envelope.presentationId))
        assertTrue(tracker.onRemoteState(report(5, PresentationPhase.NO_PRESENTATION, envelope.presentationId)))
        assertFalse(
            "the same revision must not be applied twice",
            tracker.onRemoteState(report(5, PresentationPhase.NO_PRESENTATION, envelope.presentationId)),
        )
        assertEquals(RemoteContentStatus.DISPLAY_CLOSED, tracker.state.value.status)
    }

    @Test
    fun `an older report cannot revive closed content`() {
        val envelope = tracker.beginOutgoing(OutgoingKind.TEXT)!!
        tracker.markSent()
        tracker.onRemoteState(report(2, PresentationPhase.SHOWING_TEXT, envelope.presentationId))
        tracker.onRemoteState(report(3, PresentationPhase.NO_PRESENTATION, envelope.presentationId))

        // A delayed "still showing" from before the close arrives now.
        assertFalse(tracker.onRemoteState(report(2, PresentationPhase.SHOWING_TEXT, envelope.presentationId)))
        assertFalse(tracker.state.value.displayIsShowingContent)
    }

    @Test
    fun `a report from a previous session is ignored`() {
        val envelope = tracker.beginOutgoing(OutgoingKind.TEXT)!!
        tracker.markSent()
        val alien = PresentationSnapshot(
            PresentationId("session-old", envelope.presentationId, 999),
            PresentationPhase.SHOWING_TEXT,
        )
        assertFalse(tracker.onRemoteState(alien))
        assertFalse(tracker.state.value.displayIsShowingContent)
    }

    @Test
    fun `a report about someone else's presentation is not claimed as ours`() {
        val envelope = tracker.beginOutgoing(OutgoingKind.TEXT)!!
        tracker.markSent()
        // The display is showing something with a different presentation id.
        tracker.onRemoteState(report(9, PresentationPhase.SHOWING_QR, UUID.randomUUID()))
        assertEquals(
            "our content is not the one on screen",
            RemoteContentStatus.DISPLAY_CLOSED,
            tracker.state.value.status,
        )
        assertNotNull(envelope)
    }

    @Test
    fun `a session ending marks outstanding content as disconnected`() {
        val envelope = tracker.beginOutgoing(OutgoingKind.TEXT)!!
        tracker.markSent()
        tracker.onRemoteState(report(envelope.revision, PresentationPhase.SHOWING_TEXT, envelope.presentationId))
        tracker.onSessionEnded()
        assertEquals(RemoteContentStatus.DISCONNECTED, tracker.state.value.status)
        assertFalse(tracker.state.value.displayIsShowingContent)
    }

    @Test
    fun `reconciliation trusts the display saying nothing is showing`() {
        val envelope = tracker.beginOutgoing(OutgoingKind.MIRROR)!!
        tracker.markSent()
        tracker.onRemoteState(report(envelope.revision, PresentationPhase.MIRRORING, envelope.presentationId))
        assertTrue(tracker.state.value.displayIsShowingContent)

        // After a reconnect the display reports a clean screen. Safety-first policy: believe it.
        tracker.reconcile(report(100, PresentationPhase.NO_PRESENTATION, envelope.presentationId))
        assertFalse(
            "stale local belief must never resurrect mirroring",
            tracker.state.value.displayIsShowingContent,
        )
        assertEquals(RemoteContentStatus.DISPLAY_CLOSED, tracker.state.value.status)
    }

    @Test
    fun `text A then text B cannot revert to A on the controller either`() {
        val a = tracker.beginOutgoing(OutgoingKind.TEXT)!!
        tracker.markSent()
        tracker.onRemoteState(report(a.revision, PresentationPhase.SHOWING_TEXT, a.presentationId))
        val b = tracker.beginOutgoing(OutgoingKind.TEXT)!!
        tracker.markSent()
        tracker.onRemoteState(report(b.revision, PresentationPhase.SHOWING_TEXT, b.presentationId))
        assertEquals(RemoteContentStatus.DISPLAYED, tracker.state.value.status)

        // A's acknowledgement arrives late.
        assertFalse(tracker.onRemoteState(report(a.revision, PresentationPhase.SHOWING_TEXT, a.presentationId)))
        assertEquals(b.presentationId, tracker.state.value.remote.id.presentationId)
    }

    @Test
    fun `revisions issued by the controller always increase`() {
        val first = tracker.beginOutgoing(OutgoingKind.TEXT)!!
        // The display reports a much higher revision, e.g. after closing things itself.
        tracker.onRemoteState(report(50, PresentationPhase.NO_PRESENTATION, first.presentationId))
        val second = tracker.beginOutgoing(OutgoingKind.QR)!!
        assertTrue("the clock must jump past the peer's", second.revision > 50)
    }

    @Test
    fun `no outgoing identity without a session`() {
        val fresh = RemotePresentationTracker()
        assertNull("nothing can be sent before a session exists", fresh.beginOutgoing(OutgoingKind.TEXT))
    }
}
