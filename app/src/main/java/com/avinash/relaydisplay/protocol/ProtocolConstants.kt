package com.avinash.relaydisplay.protocol

/**
 * Wire-protocol constants. See docs/PROTOCOL.md.
 *
 * Every value here is a hard limit that MUST be checked before any allocation is made on
 * behalf of a peer. Nothing in this file may be relaxed at runtime by a peer-supplied value.
 */
object ProtocolConstants {
    /** ASCII "RLY1". Present at the start of every frame. */
    const val MAGIC: Int = 0x524C5931

    const val VERSION_MAJOR: Int = 1
    const val VERSION_MINOR: Int = 0

    /** Fixed frame header size in bytes: magic(4) ver(2) flags(1) reserved(1) seq(8) len(4). */
    const val HEADER_SIZE: Int = 20

    const val FLAG_ENCRYPTED: Int = 0x01

    /** Maximum bytes of frame payload we will ever allocate for a peer. */
    const val MAX_FRAME_PAYLOAD: Int = 256 * 1024

    /** AES-GCM authentication tag length in bytes. */
    const val GCM_TAG_BYTES: Int = 16

    /** Nonce = 4-byte per-direction random prefix || 8-byte big-endian record sequence. */
    const val NONCE_BYTES: Int = 12
    const val NONCE_PREFIX_BYTES: Int = 4
}

/** Application-level size limits, configurable in one place and documented in docs/PROTOCOL.md. */
object ContentLimits {
    /** Largest control payload (non-bulk message body) we will decode. */
    const val MAX_CONTROL_PAYLOAD: Int = 256 * 1024

    /** Largest text / URL / QR payload accepted from a peer or offered to one. */
    const val MAX_TEXT_BYTES: Int = 64 * 1024

    /** Largest single file transfer. */
    const val MAX_FILE_BYTES: Long = 50L * 1024L * 1024L

    /** Largest sanitized display filename. */
    const val MAX_FILENAME_BYTES: Int = 255

    /** Bulk transfer chunk size. Must leave room for TLV + GCM overhead inside a frame. */
    const val CHUNK_BYTES: Int = 64 * 1024

    /** Largest MIME type string accepted. */
    const val MAX_MIME_BYTES: Int = 128

    /** Largest friendly device name. */
    const val MAX_DEVICE_NAME_BYTES: Int = 64

    /** Only this many bulk transfers may be in flight at once (backpressure on the display). */
    const val MAX_CONCURRENT_TRANSFERS: Int = 1
}

/** Timing budgets. Documented so they can be tuned from real measurements. */
object ProtocolTimings {
    const val HEARTBEAT_INTERVAL_MS: Long = 15_000
    const val DEAD_PEER_THRESHOLD_MS: Long = 45_000
    const val CONNECT_TIMEOUT_MS: Int = 5_000
    const val FAST_PATH_CONNECT_TIMEOUT_MS: Int = 1_500
    const val HANDSHAKE_TIMEOUT_MS: Long = 10_000
    const val NSD_RESOLVE_TIMEOUT_MS: Long = 8_000
    const val TRANSFER_INACTIVITY_TIMEOUT_MS: Long = 30_000
    const val PAIRING_TOKEN_TTL_MS: Long = 120_000
    const val ON_DEMAND_IDLE_TIMEOUT_MS: Long = 5 * 60_000
}
