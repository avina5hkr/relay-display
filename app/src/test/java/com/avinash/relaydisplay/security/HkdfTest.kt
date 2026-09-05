package com.avinash.relaydisplay.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 5869 appendix A vectors. If these break, every session key is suspect. */
class HkdfTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    @Test
    fun `rfc5869 test case 1 basic sha256`() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk)
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            Hkdf.expand(prk, info, 42),
        )
    }

    @Test
    fun `rfc5869 test case 2 longer inputs`() {
        val ikm = hex((0..0x4f).joinToString("") { "%02x".format(it) })
        val salt = hex((0x60..0xaf).joinToString("") { "%02x".format(it) })
        val info = hex((0xb0..0xff).joinToString("") { "%02x".format(it) })
        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(hex("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244"), prk)
        assertArrayEquals(
            hex(
                "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                    "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                    "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            ),
            Hkdf.expand(prk, info, 82),
        )
    }

    @Test
    fun `rfc5869 test case 3 empty salt and info`() {
        val ikm = hex("0b".repeat(22))
        val prk = Hkdf.extract(ByteArray(0), ikm)
        assertArrayEquals(hex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"), prk)
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            Hkdf.expand(prk, ByteArray(0), 42),
        )
    }

    @Test
    fun `a null salt behaves like an empty salt`() {
        val ikm = hex("0b".repeat(22))
        assertArrayEquals(Hkdf.extract(ByteArray(0), ikm), Hkdf.extract(null, ikm))
    }

    @Test
    fun `derive is a shorthand for extract then expand`() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        assertArrayEquals(
            Hkdf.expand(Hkdf.extract(salt, ikm), "ctx".toByteArray(), 32),
            Hkdf.derive(salt, ikm, "ctx", 32),
        )
    }

    @Test
    fun `different info yields independent output`() {
        val prk = Hkdf.extract(byteArrayOf(1), byteArrayOf(2))
        assertFalse(
            Hkdf.expand(prk, "a".toByteArray(), 32).contentEquals(Hkdf.expand(prk, "b".toByteArray(), 32)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `expand refuses more than 255 blocks`() {
        Hkdf.expand(ByteArray(32), ByteArray(0), 255 * 32 + 1)
    }

    @Test
    fun `constant time equals matches contentEquals`() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun `expand of zero length is empty`() {
        assertEquals(0, Hkdf.expand(ByteArray(32), ByteArray(0), 0).size)
    }
}
