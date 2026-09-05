package com.avinash.relaydisplay.domain.model

import com.avinash.relaydisplay.protocol.TlvReader
import com.avinash.relaydisplay.protocol.TlvWriter
import com.avinash.relaydisplay.security.Fingerprints

/**
 * A peer the user has explicitly paired with.
 *
 * Everything here is safe to persist: a public key, a hash of it, a friendly name and a hint
 * about where the peer was last reachable. Session keys and pairing tokens are deliberately
 * absent -- see docs/SECURITY.md.
 */
data class TrustedPeer(
    val peerId: String,
    val displayName: String,
    /** SHA-256 of [identityPublicKey]. Pinned; a peer presenting anything else is rejected. */
    val fingerprint: ByteArray,
    val identityPublicKey: ByteArray,
    val role: DeviceRole,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val capabilities: Set<String>,
    val lastHost: String? = null,
    val lastPort: Int = 0,
    /**
     * The local IPv4 prefix we were on when this endpoint last worked, for example "192.168.43.".
     * Used only to decide whether the fast-path reconnect is worth a short attempt. Cheap to
     * obtain, needs no permission, and reveals far less than an SSID would.
     */
    val lastSubnet: String? = null,
    val pairedAtEpochMs: Long = 0,
    val lastConnectedEpochMs: Long = 0,
) {
    val shortFingerprint: String get() = Fingerprints.format(fingerprint)

    val hasEndpoint: Boolean get() = !lastHost.isNullOrEmpty() && lastPort in 1..65535

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedPeer) return false
        return peerId == other.peerId &&
            displayName == other.displayName &&
            fingerprint.contentEquals(other.fingerprint) &&
            identityPublicKey.contentEquals(other.identityPublicKey) &&
            role == other.role &&
            protocolMajor == other.protocolMajor &&
            protocolMinor == other.protocolMinor &&
            capabilities == other.capabilities &&
            lastHost == other.lastHost &&
            lastPort == other.lastPort &&
            lastSubnet == other.lastSubnet &&
            pairedAtEpochMs == other.pairedAtEpochMs &&
            lastConnectedEpochMs == other.lastConnectedEpochMs
    }

    override fun hashCode(): Int {
        var r = peerId.hashCode()
        r = 31 * r + fingerprint.contentHashCode()
        r = 31 * r + identityPublicKey.contentHashCode()
        r = 31 * r + lastPort
        return r
    }

    override fun toString(): String =
        "TrustedPeer(id=$peerId, name=$displayName, fp=$shortFingerprint, endpoint=${if (hasEndpoint) "set" else "none"})"
}

/**
 * Serialises a [TrustedPeer] to bytes for storage.
 *
 * Uses the same deterministic TLV encoding as the wire protocol, with an explicit schema
 * version so a future field addition can migrate instead of crashing. Decoding returns null
 * for anything it does not understand, and the caller treats that as "no trusted peer" rather
 * than as a fatal error.
 */
object TrustedPeerCodec {
    const val SCHEMA_VERSION = 1

    private const val TAG_SCHEMA = 1
    private const val TAG_PEER_ID = 2
    private const val TAG_NAME = 3
    private const val TAG_FINGERPRINT = 4
    private const val TAG_IDENTITY_KEY = 5
    private const val TAG_ROLE = 6
    private const val TAG_PROTO_MAJOR = 7
    private const val TAG_PROTO_MINOR = 8
    private const val TAG_CAPABILITIES = 9
    private const val TAG_HOST = 10
    private const val TAG_PORT = 11
    private const val TAG_SUBNET = 12
    private const val TAG_PAIRED_AT = 13
    private const val TAG_LAST_CONNECTED = 14

    private const val MAX_ID = 64
    private const val MAX_NAME = 64
    private const val MAX_KEY = 256
    private const val MAX_HOST = 64
    private const val MAX_CAPS = 512

    fun encode(peer: TrustedPeer): ByteArray = TlvWriter()
        .putU8(TAG_SCHEMA, SCHEMA_VERSION)
        .putString(TAG_PEER_ID, peer.peerId)
        .putString(TAG_NAME, peer.displayName)
        .putBytes(TAG_FINGERPRINT, peer.fingerprint)
        .putBytes(TAG_IDENTITY_KEY, peer.identityPublicKey)
        .putU8(TAG_ROLE, peer.role.wireCode)
        .putU8(TAG_PROTO_MAJOR, peer.protocolMajor)
        .putU8(TAG_PROTO_MINOR, peer.protocolMinor)
        .putString(TAG_CAPABILITIES, peer.capabilities.sorted().joinToString(","))
        .putString(TAG_HOST, peer.lastHost.orEmpty())
        .putU32(TAG_PORT, peer.lastPort.toLong())
        .putString(TAG_SUBNET, peer.lastSubnet.orEmpty())
        .putI64(TAG_PAIRED_AT, peer.pairedAtEpochMs)
        .putI64(TAG_LAST_CONNECTED, peer.lastConnectedEpochMs)
        .toByteArray()

    /** Returns null for corrupt, truncated or unknown-schema records. Never throws. */
    fun decode(bytes: ByteArray): TrustedPeer? {
        return try {
            val t = TlvReader.parse(bytes)
            // A record written by a newer build is "no peer" rather than a guess at its shape.
            if (t.u8(TAG_SCHEMA) != SCHEMA_VERSION) return null
            val role = DeviceRole.fromWire(t.u8(TAG_ROLE)) ?: return null
            TrustedPeer(
                peerId = t.string(TAG_PEER_ID, MAX_ID),
                displayName = t.string(TAG_NAME, MAX_NAME),
                fingerprint = t.bytes(TAG_FINGERPRINT, 64),
                identityPublicKey = t.bytes(TAG_IDENTITY_KEY, MAX_KEY),
                role = role,
                protocolMajor = t.u8(TAG_PROTO_MAJOR),
                protocolMinor = t.u8(TAG_PROTO_MINOR),
                capabilities = t.string(TAG_CAPABILITIES, MAX_CAPS)
                    .split(',').filter { it.isNotEmpty() }.toSet(),
                lastHost = t.string(TAG_HOST, MAX_HOST).ifEmpty { null },
                lastPort = t.u32(TAG_PORT).toInt(),
                lastSubnet = t.string(TAG_SUBNET, MAX_HOST).ifEmpty { null },
                pairedAtEpochMs = t.i64(TAG_PAIRED_AT),
                lastConnectedEpochMs = t.i64(TAG_LAST_CONNECTED),
            )
        } catch (e: com.avinash.relaydisplay.protocol.ProtocolException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
