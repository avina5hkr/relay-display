package com.avinash.relaydisplay.security

import com.avinash.relaydisplay.protocol.Frame
import com.avinash.relaydisplay.protocol.ProtocolConstants
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecureChannelTest {

    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }
    private val prefix = byteArrayOf(1, 2, 3, 4)

    private fun pair(k: ByteArray = key, p: ByteArray = prefix) = SendCipher(k, p) to ReceiveCipher(k, p)

    @Test
    fun `seals and opens in order`() {
        val (send, receive) = pair()
        val messages = listOf("one", "two", "three").map { it.toByteArray() }
        for (m in messages) {
            assertArrayEquals(m, receive.open(send.seal(m)))
        }
        assertEquals(3L, send.nextSequence())
        assertEquals(3L, receive.expectedSequence())
    }

    @Test
    fun `sequence numbers appear in the frame header`() {
        val (send, _) = pair()
        assertEquals(0L, send.seal(byteArrayOf(1)).sequence)
        assertEquals(1L, send.seal(byteArrayOf(1)).sequence)
    }

    @Test
    fun `ciphertext differs for identical plaintext`() {
        val (send, _) = pair()
        val a = send.seal("same".toByteArray())
        val b = send.seal("same".toByteArray())
        assertNotEquals(a.payload.toList(), b.payload.toList())
    }

    @Test
    fun `a replayed record is rejected`() {
        val (send, receive) = pair()
        val first = send.seal("a".toByteArray())
        receive.open(first)
        expect(ProtocolErrorCode.REPLAY_DETECTED) { receive.open(first) }
    }

    @Test
    fun `a reordered record is rejected`() {
        val (send, receive) = pair()
        val first = send.seal("a".toByteArray())
        val second = send.seal("b".toByteArray())
        expect(ProtocolErrorCode.REPLAY_DETECTED) { receive.open(second) }
        // The channel state is unchanged, so the correct record still opens.
        assertArrayEquals("a".toByteArray(), receive.open(first))
    }

    @Test
    fun `a tampered ciphertext byte is rejected`() {
        val (send, receive) = pair()
        val sealed = send.seal("payload".toByteArray())
        sealed.payload[0] = (sealed.payload[0] + 1).toByte()
        expect(ProtocolErrorCode.DECRYPT_FAILED) { receive.open(sealed) }
    }

    @Test
    fun `a tampered authentication tag is rejected`() {
        val (send, receive) = pair()
        val sealed = send.seal("payload".toByteArray())
        val last = sealed.payload.size - 1
        sealed.payload[last] = (sealed.payload[last].toInt() xor 0xFF).toByte()
        expect(ProtocolErrorCode.DECRYPT_FAILED) { receive.open(sealed) }
    }

    @Test
    fun `renumbering a record to the sequence the receiver expects still fails`() {
        val (send, _) = pair()
        send.seal("first".toByteArray())
        val second = send.seal("second".toByteArray())
        assertEquals(1L, second.sequence)

        // Relabel the second record as record 0 so it slips past the ordering check. It still
        // fails, because the sequence is both part of the GCM nonce and covered by the AAD.
        val renumbered = Frame(second.versionMajor, second.versionMinor, second.flags, 0, second.payload)
        val (_, freshReceive) = pair()
        assertEquals(0L, freshReceive.expectedSequence())
        expect(ProtocolErrorCode.DECRYPT_FAILED) { freshReceive.open(renumbered) }
    }

    @Test
    fun `a record from the other direction does not open`() {
        val (send, _) = pair(key, prefix)
        val (_, receive) = pair(otherKey, prefix)
        expect(ProtocolErrorCode.DECRYPT_FAILED) { receive.open(send.seal("x".toByteArray())) }
    }

    @Test
    fun `the same key with a different nonce prefix does not open`() {
        val (send, _) = pair(key, byteArrayOf(1, 2, 3, 4))
        val (_, receive) = pair(key, byteArrayOf(9, 9, 9, 9))
        expect(ProtocolErrorCode.DECRYPT_FAILED) { receive.open(send.seal("x".toByteArray())) }
    }

    @Test
    fun `a plaintext frame after the handshake is rejected`() {
        val (_, receive) = pair()
        val plain = Frame(ProtocolConstants.VERSION_MAJOR, ProtocolConstants.VERSION_MINOR, 0, 0, byteArrayOf(1))
        expect(ProtocolErrorCode.NOT_AUTHENTICATED) { receive.open(plain) }
    }

    @Test
    fun `sealing more than a frame can hold is refused`() {
        val (send, _) = pair()
        expect(ProtocolErrorCode.PAYLOAD_TOO_LARGE) { send.seal(ByteArray(ProtocolConstants.MAX_FRAME_PAYLOAD)) }
    }

    @Test
    fun `an empty record round trips`() {
        val (send, receive) = pair()
        assertEquals(0, receive.open(send.seal(ByteArray(0))).size)
    }

    @Test
    fun `destroy wipes key material`() {
        val keys = SessionKeys(ByteArray(32) { 7 }, ByteArray(32) { 8 }, ByteArray(4) { 1 }, ByteArray(4) { 2 }, "123456", "session-abc")
        keys.destroy()
        assertTrue(keys.controllerToDisplayKey.all { it == 0.toByte() })
        assertTrue(keys.displayToControllerKey.all { it == 0.toByte() })
    }

    private inline fun expect(code: ProtocolErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected ProtocolException($code)")
        } catch (e: ProtocolException) {
            assertEquals(code, e.errorCode)
        }
    }
}
