package com.avinash.relaydisplay.security

import com.avinash.relaydisplay.protocol.Frame
import com.avinash.relaydisplay.protocol.FrameCodec
import com.avinash.relaydisplay.protocol.ProtocolConstants
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.ProtocolException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Directional AES-256-GCM record keys derived from one handshake.
 *
 * Keys are directional so the two peers never encrypt under the same key, and each direction
 * carries its own nonce prefix, so a reflected record can never authenticate.
 */
class SessionKeys(
    val controllerToDisplayKey: ByteArray,
    val displayToControllerKey: ByteArray,
    val controllerToDisplayNoncePrefix: ByteArray,
    val displayToControllerNoncePrefix: ByteArray,
    /** Short authentication string shown to the user on both devices during SAS pairing. */
    val shortAuthString: String,
    /**
     * Identifier both peers derive for this connection.
     *
     * Derived from the same key schedule as the record keys, so the two sides agree without an
     * extra round trip and a third party that did not complete the handshake cannot guess it.
     * Used to fence off events belonging to a previous session.
     */
    val sessionId: String,
) {
    init {
        require(controllerToDisplayKey.size == 32 && displayToControllerKey.size == 32) { "keys must be 256-bit" }
        require(controllerToDisplayNoncePrefix.size == ProtocolConstants.NONCE_PREFIX_BYTES) { "bad nonce prefix" }
        require(displayToControllerNoncePrefix.size == ProtocolConstants.NONCE_PREFIX_BYTES) { "bad nonce prefix" }
    }

    /** Best-effort wipe. Call when a session ends so keys do not linger in a heap dump. */
    fun destroy() {
        controllerToDisplayKey.fill(0)
        displayToControllerKey.fill(0)
        controllerToDisplayNoncePrefix.fill(0)
        displayToControllerNoncePrefix.fill(0)
    }
}

/**
 * Seals plaintext records into frames.
 *
 * The sequence number lives in the frame header, is the tail of the GCM nonce, and is covered by
 * the AAD. That combination means a record cannot be replayed, reordered or moved to another
 * session without the tag failing.
 */
class SendCipher(key: ByteArray, private val noncePrefix: ByteArray) {
    private val secretKey = SecretKeySpec(key, "AES")
    private var sequence: Long = 0

    fun nextSequence(): Long = sequence

    fun seal(plaintext: ByteArray): Frame {
        val seq = sequence
        if (seq == Long.MAX_VALUE) {
            // Never wrap: a repeated nonce would break GCM entirely. Force a rekey by failing.
            throw ProtocolException(ProtocolErrorCode.INTERNAL, "record sequence exhausted")
        }
        val cipherTextLength = plaintext.size + ProtocolConstants.GCM_TAG_BYTES
        if (cipherTextLength > ProtocolConstants.MAX_FRAME_PAYLOAD) {
            throw ProtocolException(ProtocolErrorCode.PAYLOAD_TOO_LARGE, "record too large to seal")
        }
        val header = FrameCodec.encodeHeader(
            ProtocolConstants.VERSION_MAJOR,
            ProtocolConstants.VERSION_MINOR,
            ProtocolConstants.FLAG_ENCRYPTED,
            seq,
            cipherTextLength,
        )
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce(noncePrefix, seq)))
        cipher.updateAAD(header)
        val sealed = cipher.doFinal(plaintext)
        sequence = seq + 1
        return Frame(
            ProtocolConstants.VERSION_MAJOR,
            ProtocolConstants.VERSION_MINOR,
            ProtocolConstants.FLAG_ENCRYPTED,
            seq,
            sealed,
        )
    }
}

/** Opens sealed frames, enforcing that sequence numbers arrive exactly in order. */
class ReceiveCipher(key: ByteArray, private val noncePrefix: ByteArray) {
    private val secretKey = SecretKeySpec(key, "AES")
    private var expectedSequence: Long = 0

    fun expectedSequence(): Long = expectedSequence

    fun open(frame: Frame): ByteArray {
        if (!frame.isEncrypted) {
            throw ProtocolException(ProtocolErrorCode.NOT_AUTHENTICATED, "plaintext frame after handshake")
        }
        // TCP is ordered, so anything other than the next sequence is a replay or an injection.
        if (frame.sequence != expectedSequence) {
            throw ProtocolException(
                ProtocolErrorCode.REPLAY_DETECTED,
                "expected record ${expectedSequence}, got ${frame.sequence}",
            )
        }
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce(noncePrefix, frame.sequence)))
            cipher.updateAAD(frame.header())
            cipher.doFinal(frame.payload)
        } catch (e: GeneralSecurityException) {
            throw ProtocolException(ProtocolErrorCode.DECRYPT_FAILED, "record authentication failed", e)
        }
        expectedSequence += 1
        return plaintext
    }
}

private const val TRANSFORM = "AES/GCM/NoPadding"
private const val TAG_BITS = ProtocolConstants.GCM_TAG_BYTES * 8

private fun nonce(prefix: ByteArray, sequence: Long): ByteArray {
    val n = ByteArray(ProtocolConstants.NONCE_BYTES)
    System.arraycopy(prefix, 0, n, 0, ProtocolConstants.NONCE_PREFIX_BYTES)
    FrameCodec.writeLong(n, ProtocolConstants.NONCE_PREFIX_BYTES, sequence)
    return n
}
