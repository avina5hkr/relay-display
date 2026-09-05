package com.avinash.relaydisplay.security

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * The long-lived identity of one installation.
 *
 * Implementations never expose the private key. On Android it lives in the hardware-backed
 * keystore; the test double keeps it in memory. See docs/SECURITY.md.
 */
interface DeviceIdentity {
    /** X.509 SubjectPublicKeyInfo encoding of the public half. Safe to send and to persist. */
    val publicKeyEncoded: ByteArray

    /** SHA-256 over [publicKeyEncoded]. This is what gets pinned for a trusted peer. */
    val fingerprint: ByteArray

    fun sign(data: ByteArray): ByteArray
}

/**
 * EC P-256 primitives.
 *
 * P-256 rather than X25519 because `XDH` only exists from API 33 and the companion device is
 * API 23 hardware; `EC`/`ECDH`/`SHA256withECDSA` are all available from API 23.
 */
object HandshakeCrypto {
    const val CURVE = "secp256r1"
    const val KEY_ALGORITHM = "EC"
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    const val AGREEMENT_ALGORITHM = "ECDH"

    fun generateEphemeralKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        gen.initialize(ECGenParameterSpec(CURVE), SecureRandom())
        return gen.generateKeyPair()
    }

    fun decodePublicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(encoded))

    /** Raw ECDH shared secret. Never used directly as a key; always fed through HKDF. */
    fun agree(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance(AGREEMENT_ALGORITHM)
        ka.init(privateKey)
        ka.doPhase(peerPublicKey, true)
        return ka.generateSecret()
    }

    fun verify(peerPublicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(peerPublicKey)
            update(data)
            verify(signature)
        }
    } catch (e: java.security.GeneralSecurityException) {
        // A malformed signature from a hostile peer is a verification failure, not a crash.
        false
    }

    fun fingerprintOf(publicKeyEncoded: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded)
}

/** In-memory identity. Used by unit/integration tests and never by the shipping app. */
class SoftwareDeviceIdentity(private val keyPair: KeyPair) : DeviceIdentity {
    override val publicKeyEncoded: ByteArray = keyPair.public.encoded
    override val fingerprint: ByteArray = HandshakeCrypto.fingerprintOf(publicKeyEncoded)

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(HandshakeCrypto.SIGNATURE_ALGORITHM).run {
            initSign(keyPair.private)
            update(data)
            sign()
        }

    companion object {
        fun generate(): SoftwareDeviceIdentity = SoftwareDeviceIdentity(HandshakeCrypto.generateEphemeralKeyPair())
    }
}

/** Human-readable fingerprint rendering. Groups of four uppercase hex, for reading aloud. */
object Fingerprints {
    fun format(fingerprint: ByteArray, groups: Int = 4): String {
        val hex = fingerprint.joinToString("") { "%02X".format(it) }
        val take = minOf(hex.length, groups * 4)
        return hex.substring(0, take).chunked(4).joinToString(" ")
    }

    fun toHex(fingerprint: ByteArray): String = fingerprint.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.isEmpty()) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
