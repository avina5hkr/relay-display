package com.avinash.relaydisplay.protocol

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameCodecTest {

    private fun frame(payload: ByteArray, seq: Long = 0, flags: Int = 0) =
        Frame(ProtocolConstants.VERSION_MAJOR, ProtocolConstants.VERSION_MINOR, flags, seq, payload)

    @Test
    fun `header layout is stable`() {
        val encoded = frame(byteArrayOf(1, 2, 3), seq = 0x0102030405060708L, flags = 1).encode()
        val expected = byteArrayOf(
            0x52, 0x4C, 0x59, 0x31, // "RLY1"
            1, 0, // version 1.0
            1, // flags: encrypted
            0, // reserved
            1, 2, 3, 4, 5, 6, 7, 8, // sequence
            0, 0, 0, 3, // payload length
            1, 2, 3,
        )
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun `round trips`() {
        val f = frame(ByteArray(1000) { it.toByte() }, seq = 42)
        assertEquals(f, FrameCodec.decode(f.encode()))
    }

    @Test
    fun `rejects bad magic`() {
        val bytes = frame(byteArrayOf()).encode()
        bytes[0] = 0
        expectProtocolError(ProtocolErrorCode.BAD_MAGIC) { FrameCodec.parseHeader(bytes) }
    }

    @Test
    fun `rejects an unsupported major version`() {
        val bytes = frame(byteArrayOf()).encode()
        bytes[4] = 9
        expectProtocolError(ProtocolErrorCode.UNSUPPORTED_VERSION) { FrameCodec.parseHeader(bytes) }
    }

    @Test
    fun `accepts an unknown minor version`() {
        val bytes = frame(byteArrayOf()).encode()
        bytes[5] = 7
        assertEquals(7, FrameCodec.parseHeader(bytes).versionMinor)
    }

    @Test
    fun `rejects unknown flag bits`() {
        val bytes = frame(byteArrayOf()).encode()
        bytes[6] = 0x40
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { FrameCodec.parseHeader(bytes) }
    }

    @Test
    fun `rejects a non-zero reserved byte`() {
        val bytes = frame(byteArrayOf()).encode()
        bytes[7] = 1
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { FrameCodec.parseHeader(bytes) }
    }

    @Test
    fun `rejects a negative declared length before allocating`() {
        val bytes = frame(byteArrayOf()).encode()
        FrameCodec.writeInt(bytes, 16, -1)
        expectProtocolError(ProtocolErrorCode.PAYLOAD_TOO_LARGE) { FrameCodec.parseHeader(bytes) }
    }

    @Test
    fun `rejects a length above the cap before allocating`() {
        val bytes = frame(byteArrayOf()).encode()
        FrameCodec.writeInt(bytes, 16, ProtocolConstants.MAX_FRAME_PAYLOAD + 1)
        expectProtocolError(ProtocolErrorCode.PAYLOAD_TOO_LARGE) { FrameCodec.parseHeader(bytes) }
    }

    @Test
    fun `rejects a negative sequence`() {
        val bytes = frame(byteArrayOf()).encode()
        FrameCodec.writeLong(bytes, 8, -1L)
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) { FrameCodec.parseHeader(bytes) }
    }

    @Test
    fun `reassembles a frame delivered one byte at a time`() {
        val original = frame(ByteArray(300) { (it * 7).toByte() }, seq = 5)
        val read = FrameCodec.readFrame(DribbleStream(original.encode(), chunk = 1))
        assertEquals(original, read)
    }

    @Test
    fun `reads several frames from a single buffer`() {
        val a = frame(byteArrayOf(1), seq = 0)
        val b = frame(byteArrayOf(2, 2), seq = 1)
        val c = frame(ByteArray(0), seq = 2)
        val stream = ByteArrayInputStream(a.encode() + b.encode() + c.encode())
        assertEquals(a, FrameCodec.readFrame(stream))
        assertEquals(b, FrameCodec.readFrame(stream))
        assertEquals(c, FrameCodec.readFrame(stream))
        assertNull("clean EOF at a frame boundary is not an error", FrameCodec.readFrame(stream))
    }

    @Test
    fun `a stream ending mid payload is a protocol error`() {
        val truncated = frame(ByteArray(100)).encode().copyOfRange(0, ProtocolConstants.HEADER_SIZE + 10)
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) {
            try {
                FrameCodec.readFrame(ByteArrayInputStream(truncated))
            } catch (e: java.io.EOFException) {
                throw ProtocolException(ProtocolErrorCode.MALFORMED_FRAME, "truncated", e)
            }
        }
    }

    @Test
    fun `a stream ending mid header is a protocol error`() {
        val truncated = frame(ByteArray(4)).encode().copyOfRange(0, 5)
        expectProtocolError(ProtocolErrorCode.MALFORMED_FRAME) {
            try {
                FrameCodec.readFrame(ByteArrayInputStream(truncated))
            } catch (e: java.io.EOFException) {
                throw ProtocolException(ProtocolErrorCode.MALFORMED_FRAME, "truncated", e)
            }
        }
    }

    @Test
    fun `a zero length payload is legal`() {
        val f = frame(ByteArray(0), seq = 9)
        assertEquals(f, FrameCodec.readFrame(ByteArrayInputStream(f.encode())))
    }
}

/** An InputStream that never returns more than [chunk] bytes, like a real socket under load. */
private class DribbleStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
    private var pos = 0
    override fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xFF
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(chunk, len, data.size - pos)
        System.arraycopy(data, pos, b, off, n)
        pos += n
        return n
    }
}
