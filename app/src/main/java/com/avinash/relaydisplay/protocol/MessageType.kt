package com.avinash.relaydisplay.protocol

/**
 * Every message that can travel inside a frame.
 *
 * Codes are permanent: never renumber an existing entry, only append. Unknown codes received
 * from a peer are rejected with [ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE] rather than ignored,
 * so a downgrade or a confused peer is visible instead of silent.
 */
enum class MessageType(val code: Int, val requiresSecureSession: Boolean) {
    // --- handshake (plaintext, pre-session) ---
    HELLO(0x0001, false),
    HELLO_ACK(0x0002, false),
    AUTH_CONFIRM(0x0003, false),
    AUTH_RESULT(0x0004, false),

    // --- session housekeeping ---
    SAS_CONFIRM(0x0010, true),
    PING(0x0011, true),
    PONG(0x0012, true),
    ACK(0x0013, true),
    ERROR(0x0014, true),
    BYE(0x0015, true),

    // --- simple content ---
    SHOW_TEXT(0x0030, true),
    SHOW_QR(0x0031, true),
    SHOW_LINK(0x0032, true),
    PRESENT_COMMAND(0x0033, true),

    // --- presentation state reconciliation ---
    /** Display -> Controller: the authoritative "here is what I am showing". */
    PRESENTATION_STATE(0x0034, true),

    /** Either side: close the named presentation. Idempotent. */
    PRESENTATION_DISMISS(0x0035, true),

    /** Either side, after a reconnect: "send me your current presentation state". */
    PRESENTATION_SYNC_REQUEST(0x0036, true),

    // --- file transfer ---
    CONTENT_OFFER(0x0040, true),
    CONTENT_ACCEPT(0x0041, true),
    CONTENT_REJECT(0x0042, true),
    TRANSFER_START(0x0043, true),
    TRANSFER_CHUNK(0x0044, true),
    TRANSFER_COMPLETE(0x0045, true),
    TRANSFER_CANCEL(0x0046, true),
    SHOW_FILE(0x0047, true),
    PDF_PAGE_COMMAND(0x0048, true),

    // --- mirroring ---
    MIRROR_START(0x0050, true),
    MIRROR_CONFIG(0x0051, true),
    MIRROR_FRAME(0x0052, true),
    MIRROR_STOP(0x0053, true),
    MIRROR_KEYFRAME_REQUEST(0x0054, true),
    ;

    companion object {
        private val byCode: Map<Int, MessageType> = entries.associateBy { it.code }

        fun fromCode(code: Int): MessageType =
            byCode[code] ?: protocolError(
                ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE,
                "unknown message type 0x${code.toString(16)}",
            )

        fun fromCodeOrNull(code: Int): MessageType? = byCode[code]
    }
}
