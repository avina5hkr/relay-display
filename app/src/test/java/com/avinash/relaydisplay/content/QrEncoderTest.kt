package com.avinash.relaydisplay.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrEncoderTest {

    private fun success(payload: String): QrEncodeResult.Success =
        QrEncoder.encode(payload) as QrEncodeResult.Success

    private fun failure(payload: String): QrEncodeError =
        (QrEncoder.encode(payload) as QrEncodeResult.Failure).error

    @Test
    fun `encodes a short payload`() {
        val result = success("hello")
        assertTrue(result.matrix.size >= 21)
        assertTrue(result.matrix.darkModuleCount() > 0)
        assertNull(result.warning)
    }

    @Test
    fun `the matrix is square and includes the quiet zone`() {
        val matrix = success("hello").matrix
        // Version 1 is 21 modules plus four on each side.
        assertEquals(21 + 2 * QrEncoder.QUIET_ZONE_MODULES, matrix.size)
    }

    @Test
    fun `the quiet zone is blank on every edge`() {
        val matrix = success("https://example.com/some/path").matrix
        val q = QrEncoder.QUIET_ZONE_MODULES
        for (i in 0 until matrix.size) {
            for (b in 0 until q) {
                assertTrue("top row $b not blank", !matrix[i, b])
                assertTrue("bottom row $b not blank", !matrix[i, matrix.size - 1 - b])
                assertTrue("left column $b not blank", !matrix[b, i])
                assertTrue("right column $b not blank", !matrix[matrix.size - 1 - b, i])
            }
        }
    }

    @Test
    fun `the finder pattern is where a decoder expects it`() {
        val matrix = success("hello").matrix
        val q = QrEncoder.QUIET_ZONE_MODULES
        // Top-left finder: a 7x7 ring, dark border, light inner ring, dark 3x3 core.
        for (i in 0 until 7) {
            assertTrue(matrix[q + i, q])
            assertTrue(matrix[q + i, q + 6])
            assertTrue(matrix[q, q + i])
            assertTrue(matrix[q + 6, q + i])
        }
        assertTrue(!matrix[q + 1, q + 1])
        assertTrue(matrix[q + 3, q + 3])
    }

    @Test
    fun `an empty payload is refused`() {
        assertEquals(QrEncodeError.EMPTY, failure(""))
    }

    @Test
    fun `an oversized payload is refused rather than made unscannable`() {
        assertEquals(QrEncodeError.TOO_LARGE, failure("x".repeat(QrEncoder.MAX_PAYLOAD_BYTES + 1)))
    }

    @Test
    fun `a payload at the exact limit still encodes`() {
        val result = QrEncoder.encode("x".repeat(QrEncoder.MAX_PAYLOAD_BYTES))
        assertTrue("expected success, got $result", result is QrEncodeResult.Success)
    }

    @Test
    fun `a dense payload encodes but warns`() {
        val result = success("y".repeat(QrEncoder.DENSE_PAYLOAD_BYTES + 50))
        assertEquals(QrWarning.DENSE, result.warning)
    }

    @Test
    fun `a longer payload needs a bigger grid`() {
        val small = success("hi").matrix.size
        val large = success("z".repeat(400)).matrix.size
        assertTrue("$large should exceed $small", large > small)
    }

    @Test
    fun `multibyte text is counted in bytes not characters`() {
        // Each of these is three UTF-8 bytes, so a character count would let far too much through.
        val payload = "中".repeat(QrEncoder.MAX_PAYLOAD_BYTES / 3 + 1)
        assertEquals(QrEncodeError.TOO_LARGE, failure(payload))
    }

    @Test
    fun `encoding is deterministic`() {
        val a = success("relaydisplay://pair?v=1").matrix
        val b = success("relaydisplay://pair?v=1").matrix
        assertEquals(a.size, b.size)
        for (y in 0 until a.size) {
            for (x in 0 until a.size) {
                assertEquals("differs at $x,$y", a[x, y], b[x, y])
            }
        }
    }

    @Test
    fun `a pairing uri encodes comfortably`() {
        val uri = "relaydisplay://pair?v=1&id=display-1&fp=" + "ab".repeat(32) +
            "&h=192.168.43.1&p=41234&t=" + "cd".repeat(16) + "&exp=1700000000000&n=K6%20Power"
        val result = success(uri)
        assertNotNull(result.matrix)
        assertNull("a pairing code must never be flagged as hard to scan", result.warning)
    }
}
