package com.avinash.relaydisplay.security

import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.protocol.AuthConfirm
import com.avinash.relaydisplay.protocol.AuthResult
import com.avinash.relaydisplay.protocol.Hello
import com.avinash.relaydisplay.protocol.HelloAck
import com.avinash.relaydisplay.protocol.MessageCodec
import com.avinash.relaydisplay.protocol.ProtocolConstants
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.ProtocolException
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The authenticated key exchange, as a pair of small step-driven engines.
 *
 * Shape (I = initiator/controller, R = responder/display):
 * ```
 * I -> R  HELLO        ephemeral pub, identity pub, nonce, caps, needs-SAS
 * R -> I  HELLO_ACK    ephemeral pub, identity pub, nonce, caps, needs-SAS
 * I -> R  AUTH_CONFIRM ECDSA(transcript, I identity) [+ HMAC(pairing token, transcript)]
 * R -> I  AUTH_RESULT  accepted + ECDSA(transcript, R identity)
 * ```
 * Both sides then derive directional AES-GCM keys from ECDH(ephemeral) salted with the
 * transcript hash. Because the transcript covers both identity keys and both ephemeral keys,
 * a machine in the middle cannot make the two sides agree on a transcript it can sign.
 *
 * Peer authenticity comes from one of, in order of preference:
 *  1. a pinned fingerprint from a previous successful pairing,
 *  2. a single-use pairing token carried in a freshly scanned QR code,
 *  3. a six digit short authentication string that the user compares on both screens.
 *
 * See docs/SECURITY.md for the threat model.
 */
object HandshakeContext {
    private const val TRANSCRIPT_LABEL = "RLY1-transcript"
    const val INITIATOR_SIGNATURE_CONTEXT = "RLY1-auth-initiator"
    const val RESPONDER_SIGNATURE_CONTEXT = "RLY1-auth-responder"
    const val TOKEN_PROOF_CONTEXT = "RLY1-token-proof"

    private const val KEY_BYTES = 32

    /** 128 bits of session identity: plenty to make a collision across reconnects impossible. */
    private const val SESSION_ID_BYTES = 16

    /** SHA-256 over the exact encoded bytes of HELLO then HELLO_ACK. */
    fun transcript(hello: Hello, ack: HelloAck): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(TRANSCRIPT_LABEL.toByteArray(StandardCharsets.UTF_8))
        digest.update(MessageCodec.encode(hello))
        digest.update(MessageCodec.encode(ack))
        return digest.digest()
    }

    fun signaturePayload(context: String, transcript: ByteArray): ByteArray =
        context.toByteArray(StandardCharsets.UTF_8) + transcript

    fun tokenProof(pairingToken: ByteArray, transcript: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairingToken, "HmacSHA256"))
        mac.update(TOKEN_PROOF_CONTEXT.toByteArray(StandardCharsets.UTF_8))
        mac.update(transcript)
        return mac.doFinal()
    }

    fun deriveSessionKeys(sharedSecret: ByteArray, transcript: ByteArray): SessionKeys {
        val prk = Hkdf.extract(salt = transcript, ikm = sharedSecret)
        fun expand(info: String, len: Int) = Hkdf.expand(prk, info.toByteArray(StandardCharsets.UTF_8), len)
        return SessionKeys(
            controllerToDisplayKey = expand("RLY1 c2d key", KEY_BYTES),
            displayToControllerKey = expand("RLY1 d2c key", KEY_BYTES),
            controllerToDisplayNoncePrefix = expand("RLY1 c2d iv", ProtocolConstants.NONCE_PREFIX_BYTES),
            displayToControllerNoncePrefix = expand("RLY1 d2c iv", ProtocolConstants.NONCE_PREFIX_BYTES),
            shortAuthString = sasFrom(expand("RLY1 sas", 4)),
            sessionId = expand("RLY1 session id", SESSION_ID_BYTES)
                .joinToString("") { "%02x".format(it) },
        )
    }

    /** Six decimal digits derived from the transcript-bound key material. */
    internal fun sasFrom(bytes: ByteArray): String {
        var acc = 0L
        for (b in bytes) acc = (acc shl 8) or (b.toLong() and 0xFF)
        return "%06d".format(acc % 1_000_000L)
    }
}

/** Everything a handshake needs to know about the local device. */
data class HandshakeConfig(
    val role: DeviceRole,
    val deviceId: String,
    val deviceName: String,
    val identity: DeviceIdentity,
    val capabilities: Set<String>,
    /** Fingerprint of the peer we already trust, if any. When set, a mismatch aborts. */
    val pinnedPeerFingerprint: ByteArray? = null,
    /** Single-use pairing token from a QR code. Initiator proves it; responder verifies it. */
    val pairingToken: ByteArray? = null,
    val random: SecureRandom = SecureRandom(),
) {
    /** SAS is needed unless stored trust or a pairing token can authenticate the peer. */
    val requestsSas: Boolean get() = pinnedPeerFingerprint == null && pairingToken == null
}

/** What a completed handshake established. */
class HandshakeOutcome(
    val peerDeviceId: String,
    val peerDeviceName: String,
    val peerRole: DeviceRole,
    val peerIdentityPublicKey: ByteArray,
    val peerFingerprint: ByteArray,
    val peerCapabilities: Set<String>,
    val negotiatedMinorVersion: Int,
    val keys: SessionKeys,
    /** True when the user must compare [SessionKeys.shortAuthString] on both devices. */
    val requiresSasConfirmation: Boolean,
    /** True when the peer authenticated with a single-use pairing token from our QR code. */
    val authenticatedByPairingToken: Boolean,
) {
    /** The identifier both peers agreed on for this connection. */
    val sessionId: String get() = keys.sessionId
}

private const val NONCE_BYTES = 16

private fun freshNonce(random: SecureRandom) = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

private fun validatePeerCommon(
    config: HandshakeConfig,
    protocolMajor: Int,
    peerRole: DeviceRole,
    identityPublicKey: ByteArray,
): PublicKey {
    if (protocolMajor != ProtocolConstants.VERSION_MAJOR) {
        throw ProtocolException(ProtocolErrorCode.UNSUPPORTED_VERSION, "peer speaks major $protocolMajor")
    }
    if (peerRole == config.role) {
        // Two controllers or two displays have nothing to say to each other; refuse early.
        throw ProtocolException(ProtocolErrorCode.AUTH_FAILED, "peer has the same role")
    }
    val pinned = config.pinnedPeerFingerprint
    val actual = HandshakeCrypto.fingerprintOf(identityPublicKey)
    if (pinned != null && !constantTimeEquals(pinned, actual)) {
        throw ProtocolException(ProtocolErrorCode.AUTH_FAILED, "peer identity does not match pinned fingerprint")
    }
    return try {
        HandshakeCrypto.decodePublicKey(identityPublicKey)
    } catch (e: java.security.GeneralSecurityException) {
        throw ProtocolException(ProtocolErrorCode.AUTH_FAILED, "peer identity key is not a valid EC key", e)
    }
}

private fun decodeEphemeral(encoded: ByteArray): PublicKey = try {
    HandshakeCrypto.decodePublicKey(encoded)
} catch (e: java.security.GeneralSecurityException) {
    throw ProtocolException(ProtocolErrorCode.AUTH_FAILED, "peer ephemeral key is not a valid EC key", e)
}

/** Drives the controller side. Not thread safe; owned by one session coroutine. */
class InitiatorHandshake(private val config: HandshakeConfig) {
    private val ephemeral: KeyPair = HandshakeCrypto.generateEphemeralKeyPair()
    private var hello: Hello? = null
    private var transcript: ByteArray? = null
    private var keys: SessionKeys? = null
    private var peerAck: HelloAck? = null

    fun createHello(): Hello {
        check(hello == null) { "hello already created" }
        return Hello(
            id = UUID.randomUUID(),
            protocolMajor = ProtocolConstants.VERSION_MAJOR,
            protocolMinor = ProtocolConstants.VERSION_MINOR,
            deviceId = config.deviceId,
            deviceName = config.deviceName,
            role = config.role,
            identityPublicKey = config.identity.publicKeyEncoded,
            ephemeralPublicKey = ephemeral.public.encoded,
            nonce = freshNonce(config.random),
            capabilities = config.capabilities,
            requestSasConfirmation = config.requestsSas,
        ).also { hello = it }
    }

    /** Validates the peer, derives keys, and returns our identity proof. */
    fun onHelloAck(ack: HelloAck): AuthConfirm {
        val sentHello = checkNotNull(hello) { "hello not sent" }
        validatePeerCommon(config, ack.protocolMajor, ack.role, ack.identityPublicKey)
        val peerEphemeral = decodeEphemeral(ack.ephemeralPublicKey)
        val tx = HandshakeContext.transcript(sentHello, ack)
        val shared = HandshakeCrypto.agree(ephemeral.private, peerEphemeral)
        transcript = tx
        keys = HandshakeContext.deriveSessionKeys(shared, tx)
        shared.fill(0)
        peerAck = ack
        return AuthConfirm(
            id = UUID.randomUUID(),
            transcriptSignature = config.identity.sign(
                HandshakeContext.signaturePayload(HandshakeContext.INITIATOR_SIGNATURE_CONTEXT, tx),
            ),
            pairingTokenProof = config.pairingToken?.let { HandshakeContext.tokenProof(it, tx) },
        )
    }

    fun onAuthResult(result: AuthResult): HandshakeOutcome {
        val tx = checkNotNull(transcript) { "keys not derived" }
        val sessionKeys = checkNotNull(keys)
        val ack = checkNotNull(peerAck)
        val sentHello = checkNotNull(hello)
        if (!result.accepted) {
            throw ProtocolException(
                if (result.errorCode == ProtocolErrorCode.UNKNOWN) ProtocolErrorCode.AUTH_FAILED else result.errorCode,
                "peer rejected the handshake",
            )
        }
        val signature = result.transcriptSignature
            ?: throw ProtocolException(ProtocolErrorCode.AUTH_FAILED, "peer accepted without proving identity")
        val peerKey = HandshakeCrypto.decodePublicKey(ack.identityPublicKey)
        val payload = HandshakeContext.signaturePayload(HandshakeContext.RESPONDER_SIGNATURE_CONTEXT, tx)
        if (!HandshakeCrypto.verify(peerKey, payload, signature)) {
            throw ProtocolException(ProtocolErrorCode.AUTH_FAILED, "peer identity signature invalid")
        }
        return HandshakeOutcome(
            peerDeviceId = ack.deviceId,
            peerDeviceName = ack.deviceName,
            peerRole = ack.role,
            peerIdentityPublicKey = ack.identityPublicKey,
            peerFingerprint = HandshakeCrypto.fingerprintOf(ack.identityPublicKey),
            peerCapabilities = ack.capabilities,
            negotiatedMinorVersion = minOf(sentHello.protocolMinor, ack.protocolMinor),
            keys = sessionKeys,
            requiresSasConfirmation = sentHello.requestSasConfirmation || ack.requestSasConfirmation,
            authenticatedByPairingToken = config.pairingToken != null,
        )
    }
}

/** Drives the display side. Not thread safe; owned by one session coroutine. */
class ResponderHandshake(private val config: HandshakeConfig) {
    private val ephemeral: KeyPair = HandshakeCrypto.generateEphemeralKeyPair()
    private var peerHello: Hello? = null
    private var ack: HelloAck? = null
    private var transcript: ByteArray? = null
    private var keys: SessionKeys? = null

    fun onHello(hello: Hello): HelloAck {
        check(peerHello == null) { "hello already received" }
        validatePeerCommon(config, hello.protocolMajor, hello.role, hello.identityPublicKey)
        val peerEphemeral = decodeEphemeral(hello.ephemeralPublicKey)
        val reply = HelloAck(
            id = UUID.randomUUID(),
            protocolMajor = ProtocolConstants.VERSION_MAJOR,
            protocolMinor = ProtocolConstants.VERSION_MINOR,
            deviceId = config.deviceId,
            deviceName = config.deviceName,
            role = config.role,
            identityPublicKey = config.identity.publicKeyEncoded,
            ephemeralPublicKey = ephemeral.public.encoded,
            nonce = freshNonce(config.random),
            capabilities = config.capabilities,
            requestSasConfirmation = config.requestsSas,
        )
        val tx = HandshakeContext.transcript(hello, reply)
        val shared = HandshakeCrypto.agree(ephemeral.private, peerEphemeral)
        transcript = tx
        keys = HandshakeContext.deriveSessionKeys(shared, tx)
        shared.fill(0)
        peerHello = hello
        ack = reply
        return reply
    }

    /**
     * Verify the initiator, and answer.
     *
     * Returns the [AuthResult] to send plus the outcome when accepted. On rejection the caller
     * must send the result and then close: a rejected peer never reaches the session loop.
     */
    fun onAuthConfirm(confirm: AuthConfirm): Pair<AuthResult, HandshakeOutcome?> {
        val hello = checkNotNull(peerHello) { "hello not received" }
        val reply = checkNotNull(ack)
        val tx = checkNotNull(transcript)
        val sessionKeys = checkNotNull(keys)

        val peerKey = HandshakeCrypto.decodePublicKey(hello.identityPublicKey)
        val payload = HandshakeContext.signaturePayload(HandshakeContext.INITIATOR_SIGNATURE_CONTEXT, tx)
        if (!HandshakeCrypto.verify(peerKey, payload, confirm.transcriptSignature)) {
            return reject(ProtocolErrorCode.AUTH_FAILED)
        }

        var tokenAuthenticated = false
        val expectedToken = config.pairingToken
        if (expectedToken != null) {
            val proof = confirm.pairingTokenProof
            if (proof == null || !constantTimeEquals(HandshakeContext.tokenProof(expectedToken, tx), proof)) {
                // A pairing token was pending, so an unproven peer is a pairing attempt that failed.
                // Falling back to unauthenticated would be exactly the downgrade we must not allow.
                if (config.pinnedPeerFingerprint == null) return reject(ProtocolErrorCode.AUTH_FAILED)
            } else {
                tokenAuthenticated = true
            }
        } else if (confirm.pairingTokenProof != null && config.pinnedPeerFingerprint == null) {
            // Peer claims a token we never issued (or that already expired).
            return reject(ProtocolErrorCode.AUTH_FAILED)
        }

        val requiresSas = hello.requestSasConfirmation || reply.requestSasConfirmation
        val outcome = HandshakeOutcome(
            peerDeviceId = hello.deviceId,
            peerDeviceName = hello.deviceName,
            peerRole = hello.role,
            peerIdentityPublicKey = hello.identityPublicKey,
            peerFingerprint = HandshakeCrypto.fingerprintOf(hello.identityPublicKey),
            peerCapabilities = hello.capabilities,
            negotiatedMinorVersion = minOf(hello.protocolMinor, reply.protocolMinor),
            keys = sessionKeys,
            requiresSasConfirmation = requiresSas,
            authenticatedByPairingToken = tokenAuthenticated,
        )
        val result = AuthResult(
            id = UUID.randomUUID(),
            accepted = true,
            errorCode = ProtocolErrorCode.UNKNOWN,
            requiresSasConfirmation = requiresSas,
            transcriptSignature = config.identity.sign(
                HandshakeContext.signaturePayload(HandshakeContext.RESPONDER_SIGNATURE_CONTEXT, tx),
            ),
        )
        return result to outcome
    }

    private fun reject(code: ProtocolErrorCode): Pair<AuthResult, HandshakeOutcome?> =
        AuthResult(UUID.randomUUID(), accepted = false, errorCode = code, requiresSasConfirmation = false, transcriptSignature = null) to null
}
