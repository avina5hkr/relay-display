package com.avinash.relaydisplay.security

import com.avinash.relaydisplay.protocol.ProtocolTimings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingUriTest {

    private val now = 1_700_000_000_000L
    private val fingerprint = ByteArray(32) { it.toByte() }
    private val token = ByteArray(16) { (it * 7 + 1).toByte() }

    private fun code(
        peerId: String = "display-1",
        fp: ByteArray = fingerprint,
        host: String = "192.168.1.42",
        port: Int = 41234,
        tok: ByteArray = token,
        expires: Long = now + 60_000,
        name: String = "K6 Power",
    ) = PairingUri.build(peerId, fp, host, port, tok, expires, name)

    private fun parse(raw: String, at: Long = now) = PairingUri.parse(raw, at)

    private fun valid(raw: String, at: Long = now): PairingPayload =
        (parse(raw, at) as PairingUriResult.Valid).payload

    private fun error(raw: String, at: Long = now): PairingUriError =
        (parse(raw, at) as PairingUriResult.Invalid).error

    @Test
    fun `round trips a well formed code`() {
        val payload = valid(code())
        assertEquals(1, payload.version)
        assertEquals("display-1", payload.peerId)
        assertArrayEquals(fingerprint, payload.fingerprint)
        assertEquals("192.168.1.42", payload.host)
        assertEquals(41234, payload.port)
        assertArrayEquals(token, payload.token)
        assertEquals(now + 60_000, payload.expiresAtEpochMs)
        assertEquals("K6 Power", payload.displayName)
    }

    @Test
    fun `rejects a code from another scheme`() {
        assertEquals(PairingUriError.NOT_A_RELAY_CODE, error("https://example.com/?v=1"))
        assertEquals(PairingUriError.NOT_A_RELAY_CODE, error("relaydisplay://open?v=1"))
        assertEquals(PairingUriError.NOT_A_RELAY_CODE, error("just some scanned text"))
    }

    @Test
    fun `rejects an unsupported version`() {
        assertEquals(PairingUriError.UNSUPPORTED_VERSION, error(code().replace("v=1", "v=2")))
    }

    @Test
    fun `rejects an expired code`() {
        assertEquals(PairingUriError.EXPIRED, error(code(expires = now - 1), at = now))
        assertEquals(PairingUriError.EXPIRED, error(code(expires = now), at = now))
    }

    @Test
    fun `rejects a code that claims to live too long`() {
        val far = now + ProtocolTimings.PAIRING_TOKEN_TTL_MS + 60_000
        assertEquals(PairingUriError.TTL_TOO_LONG, error(code(expires = far)))
    }

    @Test
    fun `rejects a short token`() {
        assertEquals(PairingUriError.WEAK_TOKEN, error(code(tok = ByteArray(8) { it.toByte() })))
    }

    @Test
    fun `rejects a token with no entropy`() {
        assertEquals(PairingUriError.WEAK_TOKEN, error(code(tok = ByteArray(16))))
        assertEquals(PairingUriError.WEAK_TOKEN, error(code(tok = ByteArray(16) { 0x41 })))
    }

    @Test
    fun `rejects a wrong length fingerprint`() {
        assertEquals(PairingUriError.BAD_FINGERPRINT, error(code(fp = ByteArray(16))))
    }

    @Test
    fun `rejects a non hex fingerprint`() {
        val bad = code().replace(Regex("fp=[0-9a-f]{64}"), "fp=" + "z".repeat(64))
        assertEquals(PairingUriError.BAD_FINGERPRINT, error(bad))
    }

    @Test
    fun `rejects a hostname so a scan never triggers a dns lookup`() {
        assertEquals(PairingUriError.BAD_ADDRESS, error(code(host = "evil.example.com")))
        assertEquals(PairingUriError.BAD_ADDRESS, error(code(host = "localhost")))
    }

    @Test
    fun `rejects malformed addresses`() {
        for (host in listOf("999.1.1.1", "1.2.3", "1.2.3.4.5", "", "1.2.3.-1", "01.2.3.4", "1.2.3.256")) {
            assertFalse("accepted $host", PairingUri.isIpv4Literal(host))
        }
    }

    @Test
    fun `accepts ordinary private addresses`() {
        for (host in listOf("192.168.1.1", "10.0.0.1", "172.16.5.9", "192.168.43.1", "0.0.0.0", "255.255.255.255")) {
            assertTrue("rejected $host", PairingUri.isIpv4Literal(host))
        }
    }

    @Test
    fun `rejects a port outside the legal range`() {
        assertEquals(PairingUriError.BAD_ADDRESS, error(code(port = 70000)))
        assertEquals(PairingUriError.BAD_ADDRESS, error(code(port = 0)))
        assertEquals(PairingUriError.BAD_ADDRESS, error(code().replace("p=41234", "p=abc")))
    }

    @Test
    fun `rejects a repeated parameter`() {
        // Appending a second fingerprint must not let a reader that takes the first be fooled.
        assertEquals(PairingUriError.MALFORMED, error(code() + "&fp=" + "aa".repeat(32)))
    }

    @Test
    fun `rejects a missing parameter`() {
        val stripped = code().replace(Regex("&exp=[0-9]+"), "")
        assertEquals(PairingUriError.MALFORMED, error(stripped))
    }

    @Test
    fun `rejects an absurdly long code before parsing it`() {
        assertEquals(PairingUriError.MALFORMED, error(code() + "&x=" + "a".repeat(1000)))
    }

    @Test
    fun `rejects a peer id with unsafe characters`() {
        assertEquals(PairingUriError.MALFORMED, error(code(peerId = "../../etc/passwd")))
        assertEquals(PairingUriError.MALFORMED, error(code(peerId = "a b")))
    }

    @Test
    fun `sanitises the display name`() {
        assertEquals("Bad name", valid(code(name = "Bad <name>")).displayName)
        assertEquals("Unnamed device", valid(code(name = " ")).displayName)
    }

    @Test
    fun `names with spaces survive encoding`() {
        assertEquals("Living Room TV", valid(code(name = "Living Room TV")).displayName)
    }

    @Test
    fun `a fresh token has full width and varies`() {
        val a = PairingUri.newToken()
        val b = PairingUri.newToken()
        assertEquals(16, a.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `isExpired uses the supplied clock`() {
        val payload = valid(code(expires = now + 1000))
        assertFalse(payload.isExpired(now))
        assertTrue(payload.isExpired(now + 1000))
        assertTrue(payload.isExpired(now + 5000))
    }

    @Test
    fun `toString never leaks the token or the fingerprint`() {
        val text = valid(code()).toString()
        assertFalse(text.contains(Fingerprints.toHex(token)))
        assertFalse(text.contains(Fingerprints.toHex(fingerprint)))
    }
}
