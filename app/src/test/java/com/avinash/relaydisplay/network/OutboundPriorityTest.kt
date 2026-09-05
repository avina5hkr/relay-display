package com.avinash.relaydisplay.network

import com.avinash.relaydisplay.network.session.TrafficClass
import com.avinash.relaydisplay.network.session.trafficClass
import com.avinash.relaydisplay.protocol.Ack
import com.avinash.relaydisplay.protocol.Bye
import com.avinash.relaydisplay.protocol.ContentOffer
import com.avinash.relaydisplay.protocol.ErrorMessage
import com.avinash.relaydisplay.protocol.MirrorConfig
import com.avinash.relaydisplay.protocol.MirrorFrame
import com.avinash.relaydisplay.protocol.MirrorKeyframeRequest
import com.avinash.relaydisplay.protocol.MirrorStart
import com.avinash.relaydisplay.protocol.MirrorStop
import com.avinash.relaydisplay.protocol.Ping
import com.avinash.relaydisplay.protocol.Pong
import com.avinash.relaydisplay.protocol.PresentationDismiss
import com.avinash.relaydisplay.protocol.PresentationStateMessage
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.ShowText
import com.avinash.relaydisplay.protocol.TransferChunk
import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.domain.model.PresentationPhase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Traffic classification, which is what stops a mirror from killing its own session.
 *
 * The failure this guards against was observed on hardware: mirroring died roughly a minute after
 * it started. Every outbound message shared one 8-slot queue, ~24 video frames a second kept it
 * full, and `heartbeatLoop` closed the whole session the first time a `Ping` could not be queued.
 * The rule these tests encode is simple: video is the only thing allowed to be dropped.
 */
class OutboundPriorityTest {

    private fun id() = UUID.randomUUID()

    @Test
    fun `liveness and teardown are control`() {
        assertEquals(TrafficClass.CONTROL, Ping(id(), 0L).trafficClass())
        assertEquals(TrafficClass.CONTROL, Pong(id(), 0L).trafficClass())
        assertEquals(TrafficClass.CONTROL, Bye(id(), "done").trafficClass())
        assertEquals(TrafficClass.CONTROL, Ack(id(), id(), true, ProtocolErrorCode.INTERNAL).trafficClass())
        assertEquals(
            TrafficClass.CONTROL,
            ErrorMessage(id(), ProtocolErrorCode.INTERNAL, "INTERNAL").trafficClass(),
        )
    }

    @Test
    fun `everything about a mirror except its frames is control`() {
        // Dropping any of these is how a phone ends up capturing its screen for nobody, or
        // showing a frozen image while the sender believes it is streaming.
        assertEquals(TrafficClass.CONTROL, MirrorStart(id(), 1280, 720, 24, 2_000_000, "video/avc").trafficClass())
        assertEquals(
            TrafficClass.CONTROL,
            MirrorConfig(id(), 1280, 720, 0, ByteArray(4), ByteArray(4)).trafficClass(),
        )
        assertEquals(TrafficClass.CONTROL, MirrorStop(id(), "user").trafficClass())
        assertEquals(TrafficClass.CONTROL, MirrorKeyframeRequest(id()).trafficClass())
    }

    @Test
    fun `only video frames are lossy`() {
        assertEquals(TrafficClass.MEDIA, MirrorFrame(id(), 0L, true, ByteArray(64)).trafficClass())
    }

    @Test
    fun `transfer bytes are bulk but transfer control is not`() {
        // A dropped chunk is a corrupt file, so bulk is backpressured rather than lossy -- but it
        // still must not share a queue with the heartbeat.
        assertEquals(TrafficClass.BULK, TransferChunk(id(), id(), 0, ByteArray(16)).trafficClass())
        assertEquals(
            TrafficClass.CONTROL,
            ContentOffer(id(), id(), ContentKind.IMAGE, 10, "image/png", "a.png", ByteArray(32)).trafficClass(),
        )
    }

    @Test
    fun `presentation state and content are control`() {
        assertEquals(TrafficClass.CONTROL, ShowText(id(), "hello", null).trafficClass())
        assertEquals(
            TrafficClass.CONTROL,
            PresentationStateMessage(id(), "s", id(), 1, PresentationPhase.SHOWING_TEXT, null).trafficClass(),
        )
        assertEquals(TrafficClass.CONTROL, PresentationDismiss(id(), "s", id(), 2).trafficClass())
    }

    @Test
    fun `video is never classified with control`() {
        // The single assertion that would have caught the original defect.
        val video = MirrorFrame(id(), 0L, false, ByteArray(1024)).trafficClass()
        val ping = Ping(id(), 0L).trafficClass()
        assertNotEquals("video and heartbeat must not share a queue", ping, video)
    }
}
