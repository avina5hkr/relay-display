package com.avinash.relaydisplay.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TlvTest {

    @Test
    fun `round trips every scalar type`() {
        val bytes = TlvWriter()
            .putU8(1, 200)
            .putU16(2, 60000)
            .putU32(3, 4_000_000_000L)
            .putI64(4, -12345L)
            .putBool(5, true)
            .putString(6, "héllo ✓")
            .putBytes(7, byteArrayOf(1, 2, 3))
            .toByteArray()

        val r = TlvReader.parse(bytes)
        assertEquals(200, r.u8(1))
        assertEquals(60000, r.u16(2))
        assertEquals(4_000_000_000L, r.u32(3))
        assertEquals(-12345L, r.i64(4))
        assertTrue(r.bool(5))
        assertEquals("héllo ✓", r.string(6, 64))
        assertArrayEquals(byteArrayOf(1, 2, 3), r.bytes(7, 8))
    }

    @Test
    fun `encoding is deterministic`() {
        fun build() = TlvWriter().putU8(1, 7).putString(3, "x").putI64(9, 42).toByteArray()
        assertArrayEquals(build(), build())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writer rejects descending tags`() {
        TlvWriter().putU8(5, 1).putU8(2, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writer rejects repeated tags`() {
        TlvWriter().putU8(5, 1).putU8(5, 1)
    }

    @Test
    fun `reader rejects repeated tags`() {
        // Hand-build a body with tag 4 twice; the writer would never produce this.
        val body = byteArrayOf(4, 0, 0, 0, 1, 9, 4, 0, 0, 0, 1, 9)
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { TlvReader.parse(body) }
    }

    @Test
    fun `reader rejects out of order tags`() {
        val body = byteArrayOf(7, 0, 0, 0, 1, 9, 2, 0, 0, 0, 1, 9)
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { TlvReader.parse(body) }
    }

    @Test
    fun `reader rejects a truncated field header`() {
        val body = byteArrayOf(1, 0, 0)
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { TlvReader.parse(body) }
    }

    @Test
    fun `reader rejects a length that runs past the body`() {
        val body = byteArrayOf(1, 0, 0, 0, 99, 5)
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { TlvReader.parse(body) }
    }

    @Test
    fun `reader rejects a negative-looking huge length before allocating`() {
        // 0xFFFFFFFF would be -1 as a signed Int and could become a huge allocation.
        val body = byteArrayOf(1, -1, -1, -1, -1)
        expectProtocolError(ProtocolErrorCode.PAYLOAD_TOO_LARGE) { TlvReader.parse(body) }
    }

    @Test
    fun `reader enforces per-field maximums`() {
        val bytes = TlvWriter().putBytes(1, ByteArray(100)).toByteArray()
        expectProtocolError(ProtocolErrorCode.PAYLOAD_TOO_LARGE) { TlvReader.parse(bytes).bytes(1, 50) }
    }

    @Test
    fun `reader rejects malformed utf8`() {
        // 0x80 alone is a continuation byte with no lead byte.
        val bytes = TlvWriter().putBytes(1, byteArrayOf(0x80.toByte())).toByteArray()
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { TlvReader.parse(bytes).string(1, 16) }
    }

    @Test
    fun `optional fields are absent rather than fatal`() {
        val r = TlvReader.parse(TlvWriter().putU8(1, 1).toByteArray())
        assertNull(r.optString(2, 16))
        assertNull(r.optBytes(2, 16))
        assertNull(r.optU16(2))
        assertTrue(r.has(1))
    }

    @Test
    fun `wrong width for a scalar is rejected`() {
        val bytes = TlvWriter().putBytes(1, byteArrayOf(1, 2, 3)).toByteArray()
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { TlvReader.parse(bytes).u16(1) }
    }

    @Test
    fun `too many fields is rejected`() {
        val w = TlvWriter()
        repeat(Tlv.MAX_FIELDS) { w.putU8(it, 1) }
        // One more than the cap must fail at write time too.
        try {
            w.putU8(Tlv.MAX_FIELDS, 1)
            fail("expected the writer to refuse more than ${Tlv.MAX_FIELDS} fields")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("too many"))
        }
    }
}

internal inline fun expectProtocolError(code: ProtocolErrorCode, block: () -> Unit) {
    try {
        block()
        fail("expected ProtocolException($code)")
    } catch (e: ProtocolException) {
        assertEquals("wrong error code: ${e.message}", code, e.errorCode)
    }
}
