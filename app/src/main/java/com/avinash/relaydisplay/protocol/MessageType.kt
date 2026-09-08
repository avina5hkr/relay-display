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

    // --- generic file transfer (capability `file-v1`) ---
    //
    // A batch wraps the per-file CONTENT_OFFER/TRANSFER_* exchange above rather than replacing it:
    // one confirmation for the whole selection, then each file streams through the machinery that
    // already existed and is already tested. A peer that does not announce `file-v1` never sees
    // any of these three.
    /** Controller -> Display: "here is what I would like to send", for one confirmation. */
    FILE_BATCH_OFFER(0x0049, true),

    /** Display -> Controller: the user accepted the whole batch. */
    FILE_BATCH_ACCEPT(0x004A, true),

    /** Display -> Controller: the user rejected it, or it broke a limit. */
    FILE_BATCH_REJECT(0x004B, true),

    /**
     * Either direction: this batch is terminated.
     *
     * The terminal event `file-v1` lacked. Without it a controller-side cancellation was invisible
     * to the Display, which kept its consent and its partial file and then refused the next batch
     * as BUSY.
     */
    FILE_BATCH_CANCEL(0x004C, true),

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
