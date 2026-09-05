package com.avinash.relaydisplay.security

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA-256 (RFC 5869), built from the platform's HMAC.
 *
 * This is a standard construction over a standard primitive, not a new one: `HkdfTest` pins it
 * against the RFC 5869 appendix A test vectors so a regression here is caught immediately.
 */
object Hkdf {
    private const val HMAC = "HmacSHA256"
    const val HASH_LEN = 32

    /** RFC 5869 section 2.2. A null or empty salt becomes HASH_LEN zero bytes, per the RFC. */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(effectiveSalt, HMAC))
        return mac.doFinal(ikm)
    }

    /** RFC 5869 section 2.3. */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length >= 0) { "negative length" }
        require(length <= 255 * HASH_LEN) { "HKDF output limited to ${255 * HASH_LEN} bytes" }
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(prk, HMAC))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun derive(salt: ByteArray?, ikm: ByteArray, info: String, length: Int): ByteArray =
        expand(extract(salt, ikm), info.toByteArray(StandardCharsets.UTF_8), length)
}

/** Constant-time comparison; used everywhere a secret or a MAC is compared. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
