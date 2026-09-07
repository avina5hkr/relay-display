package com.avinash.relaydisplay.protocol

import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.domain.model.PresentCommandType
import com.avinash.relaydisplay.domain.model.PresentationPhase
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCodecTest {

    private val id = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    private val transferId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    /** One instance of every message type; the round-trip test walks this list. */
    private fun samples(): List<RelayMessage> = listOf(
        Hello(id, 1, 0, "dev-a", "S22 Ultra", DeviceRole.CONTROLLER, ByteArray(64) { 1 }, ByteArray(64) { 2 }, ByteArray(16) { 3 }, setOf(Capabilities.QR, Capabilities.TEXT), true),
        HelloAck(id, 1, 0, "dev-b", "K6 Power", DeviceRole.DISPLAY, ByteArray(64) { 4 }, ByteArray(64) { 5 }, ByteArray(16) { 6 }, setOf(Capabilities.IMAGE), false),
        AuthConfirm(id, ByteArray(70) { 7 }, ByteArray(32) { 8 }),
        AuthConfirm(id, ByteArray(70) { 7 }, null),
        AuthResult(id, true, ProtocolErrorCode.UNKNOWN, false, ByteArray(70) { 9 }),
        AuthResult(id, false, ProtocolErrorCode.AUTH_FAILED, false, null),
        SasConfirm(id, true),
        Ping(id, 1234567890123L),
        Pong(id, 1234567890123L),
        Ack(id, transferId, true, ProtocolErrorCode.UNKNOWN),
        ErrorMessage(id, ProtocolErrorCode.TOO_LARGE, "file exceeds limit"),
        Bye(id, "user disconnected"),
        ShowText(id, "hello world"),
        ShowText(id, "with envelope", PresentationEnvelope(transferId, 7)),
        ShowQr(id, "https://example.com", "scan me"),
        ShowQr(id, "plain", null),
        ShowQr(id, "enveloped", "cap", PresentationEnvelope(transferId, 1)),
        ShowLink(id, "https://example.com/a", "Example"),
        ShowLink(id, "https://example.com/b", null, PresentationEnvelope(transferId, 2)),
        PresentCommand(id, PresentCommandType.SET_BRIGHTNESS, -1),
        PresentCommand(id, PresentCommandType.SET_FIT_MODE, FitMode.FILL.wireCode),
        ContentOffer(id, transferId, ContentKind.IMAGE, 4096L, "image/jpeg", "photo.jpg", ByteArray(32) { 0x5A }),
        ContentAccept(id, transferId),
        ContentReject(id, transferId, ProtocolErrorCode.TOO_LARGE),
        TransferStart(id, transferId, 4096L, 65536),
        TransferChunk(id, transferId, 3L, ByteArray(1024) { it.toByte() }),
        TransferComplete(id, transferId, ByteArray(32) { 0x5A }),
        TransferCancel(id, transferId, ProtocolErrorCode.CANCELLED),
        ShowFile(id, transferId, ContentKind.PDF, FitMode.FIT),
        ShowFile(id, transferId, ContentKind.IMAGE, FitMode.FILL, PresentationEnvelope(transferId, 9)),
        PresentationStateMessage(id, "abc123", transferId, 4, PresentationPhase.SHOWING_TEXT, null),
        PresentationStateMessage(id, "abc123", transferId, 5, PresentationPhase.SHOWING_PDF, transferId),
        PresentationDismiss(id, "abc123", transferId, 6),
        PresentationSyncRequest(id, "abc123"),
        PdfPageCommand(id, transferId, 12),
        MirrorStart(id, 1280, 720, 24, 2_500_000, "video/avc"),
        MirrorConfig(id, 1280, 720, 90, ByteArray(20) { 1 }, ByteArray(10) { 2 }),
        MirrorFrame(id, 987654L, true, ByteArray(2048) { it.toByte() }),
        MirrorStop(id, "projection revoked"),
        MirrorKeyframeRequest(id),
        FileBatchOffer(
            id = id,
            batchId = transferId,
            senderName = "Avi's phone",
            files = listOf(
                FileManifestEntry("report.pdf", "application/pdf", 1_234L),
                // A non-ASCII name and an empty MIME type in the same sample: both are ordinary,
                // and both are where a hand-rolled manifest encoding goes wrong.
                FileManifestEntry("\u6f22\u5b57 notes.txt", "", 0L),
            ),
        ),
        FileBatchAccept(id, transferId),
        FileBatchReject(id, transferId, ProtocolErrorCode.PERMISSION_DENIED),
    )

    @Test
    fun `every message type round trips`() {
        for (m in samples()) {
            val decoded = MessageCodec.decode(MessageCodec.encode(m))
            assertEquals("round trip failed for ${m.type}", m, decoded)
            assertEquals(m.type, decoded.type)
            assertEquals(m.id, decoded.id)
        }
    }

    @Test
    fun `every message type has a sample`() {
        val covered = samples().map { it.type }.toSet()
        val missing = MessageType.entries.toSet() - covered
        assertTrue("no round-trip sample for $missing", missing.isEmpty())
    }

    @Test
    fun `encoding is deterministic`() {
        for (m in samples()) {
            assertArrayEquals("unstable encoding for ${m.type}", MessageCodec.encode(m), MessageCodec.encode(m))
        }
    }

    @Test
    fun `golden encoding of a ping`() {
        val bytes = MessageCodec.encode(Ping(id, 0x0102030405060708L))
        val expected = byteArrayOf(
            0x00, 0x11, // MessageType.PING
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, // message id high
            0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
            0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(), // message id low
            0x01, 0x00, 0x00, 0x00, 0x08, // tag 1, length 8
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `golden encoding of a show text`() {
        val bytes = MessageCodec.encode(ShowText(id, "hi"))
        val expected = byteArrayOf(
            0x00, 0x30,
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
            0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
            0x01, 0x00, 0x00, 0x00, 0x02,
            'h'.code.toByte(), 'i'.code.toByte(),
        )
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `unknown message type is rejected`() {
        val bytes = MessageCodec.encode(Ping(id, 1))
        bytes[0] = 0x7F
        bytes[1] = 0x7F
        expectProtocolError(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE) { MessageCodec.decode(bytes) }
    }

    @Test
    fun `record shorter than its header is rejected`() {
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { MessageCodec.decode(ByteArray(10)) }
    }

    @Test
    fun `a record above the control limit is rejected before decoding`() {
        val huge = ByteArray(ContentLimits.MAX_CONTROL_PAYLOAD + 1)
        huge[0] = 0
        huge[1] = 0x11
        expectProtocolError(ProtocolErrorCode.PAYLOAD_TOO_LARGE) { MessageCodec.decode(huge) }
    }

    @Test
    fun `an oversized text payload is rejected`() {
        // Build the record by hand so the writer's own limits do not hide the decoder's.
        val body = TlvWriter().putBytes(1, ByteArray(ContentLimits.MAX_TEXT_BYTES + 1) { 'a'.code.toByte() }).toByteArray()
        val record = byteArrayOf(0x00, 0x30) + MessageCodec.uuidToBytes(id) + body
        expectProtocolError(ProtocolErrorCode.PAYLOAD_TOO_LARGE) { MessageCodec.decode(record) }
    }

    @Test
    fun `an oversized chunk is rejected`() {
        val body = TlvWriter()
            .putBytes(1, MessageCodec.uuidToBytes(transferId))
            .putI64(2, 0)
            .putBytes(3, ByteArray(ContentLimits.CHUNK_BYTES + 1))
            .toByteArray()
        val record = byteArrayOf(0x00, 0x44) + MessageCodec.uuidToBytes(id) + body
        expectProtocolError(ProtocolErrorCode.PAYLOAD_TOO_LARGE) { MessageCodec.decode(record) }
    }

    @Test
    fun `a missing required field is rejected`() {
        val record = byteArrayOf(0x00, 0x11) + MessageCodec.uuidToBytes(id)
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { MessageCodec.decode(record) }
    }

    @Test
    fun `an unknown role code is rejected`() {
        val body = TlvWriter()
            .putU8(1, 1).putU8(2, 0).putString(3, "x").putString(4, "y").putU8(5, 99)
            .putBytes(6, ByteArray(4)).putBytes(7, ByteArray(4)).putBytes(8, ByteArray(4))
            .putString(9, "").putBool(10, false)
            .toByteArray()
        val record = byteArrayOf(0x00, 0x01) + MessageCodec.uuidToBytes(id) + body
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { MessageCodec.decode(record) }
    }

    @Test
    fun `an unknown present command is rejected`() {
        val body = TlvWriter().putU8(1, 99).putU16(2, 128).toByteArray()
        val record = byteArrayOf(0x00, 0x33) + MessageCodec.uuidToBytes(id) + body
        expectProtocolError(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE) { MessageCodec.decode(record) }
    }

    @Test
    fun `an sha256 of the wrong length is rejected`() {
        val body = TlvWriter().putBytes(1, MessageCodec.uuidToBytes(transferId)).putBytes(2, ByteArray(16)).toByteArray()
        val record = byteArrayOf(0x00, 0x45) + MessageCodec.uuidToBytes(id) + body
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { MessageCodec.decode(record) }
    }

    @Test
    fun `capabilities encode in a stable order`() {
        assertEquals("a,b,c", MessageCodec.encodeCapabilities(setOf("c", "a", "b")))
        assertEquals(setOf("a", "b"), MessageCodec.decodeCapabilities("a,b"))
        assertEquals(emptySet<String>(), MessageCodec.decodeCapabilities(""))
    }

    @Test
    fun `optional fields survive being absent`() {
        val decoded = MessageCodec.decode(MessageCodec.encode(ShowQr(id, "p", null))) as ShowQr
        assertNull(decoded.caption)
    }

    @Test
    fun `brightness sentinel survives the round trip`() {
        val decoded = MessageCodec.decode(
            MessageCodec.encode(PresentCommand(id, PresentCommandType.SET_BRIGHTNESS, -1)),
        ) as PresentCommand
        assertEquals(-1, decoded.intArg)
    }

    @Test
    fun `message types have unique codes`() {
        val codes = MessageType.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `a presentation envelope survives the round trip`() {
        val decoded = MessageCodec.decode(
            MessageCodec.encode(ShowText(id, "hi", PresentationEnvelope(transferId, 42))),
        ) as ShowText
        assertEquals(transferId, decoded.envelope!!.presentationId)
        assertEquals(42L, decoded.envelope!!.revision)
    }

    @Test
    fun `an absent envelope stays absent`() {
        val decoded = MessageCodec.decode(MessageCodec.encode(ShowText(id, "hi"))) as ShowText
        assertNull("no envelope must not be invented", decoded.envelope)
    }

    @Test
    fun `a half present envelope is rejected rather than guessed at`() {
        // Presentation id without a revision: a peer must not silently assume revision 0.
        val body = TlvWriter()
            .putString(1, "hi")
            .putBytes(20, MessageCodec.uuidToBytes(transferId))
            .toByteArray()
        val record = byteArrayOf(0x00, 0x30) + MessageCodec.uuidToBytes(id) + body
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { MessageCodec.decode(record) }
    }

    @Test
    fun `a negative revision is rejected`() {
        val body = TlvWriter()
            .putString(1, "abc123")
            .putBytes(2, MessageCodec.uuidToBytes(transferId))
            .putI64(3, -1)
            .toByteArray()
        val record = byteArrayOf(0x00, 0x35) + MessageCodec.uuidToBytes(id) + body
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { MessageCodec.decode(record) }
    }

    @Test
    fun `an unknown presentation phase is rejected`() {
        val body = TlvWriter()
            .putString(1, "abc123")
            .putBytes(2, MessageCodec.uuidToBytes(transferId))
            .putI64(3, 1)
            .putU8(4, 99)
            .toByteArray()
        val record = byteArrayOf(0x00, 0x34) + MessageCodec.uuidToBytes(id) + body
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { MessageCodec.decode(record) }
    }
}
