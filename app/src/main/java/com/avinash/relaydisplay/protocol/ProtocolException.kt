package com.avinash.relaydisplay.protocol

/** Machine-readable protocol error codes. Sent on the wire in [MessageType.ERROR]. */
enum class ProtocolErrorCode(val code: Int) {
    UNKNOWN(0),
    BAD_MAGIC(1),
    UNSUPPORTED_VERSION(2),
    MALFORMED_FRAME(3),
    PAYLOAD_TOO_LARGE(4),
    UNKNOWN_MESSAGE_TYPE(5),
    NOT_AUTHENTICATED(6),
    AUTH_FAILED(7),
    REPLAY_DETECTED(8),
    DECRYPT_FAILED(9),
    UNSUPPORTED_FORMAT(10),
    TOO_LARGE(11),
    INSUFFICIENT_STORAGE(12),
    DECODE_FAILED(13),
    PERMISSION_DENIED(14),
    TIMEOUT(15),
    BUSY(16),
    CANCELLED(17),
    RATE_LIMITED(18),
    INTERNAL(19);

    companion object {
        fun fromCode(code: Int): ProtocolErrorCode = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Thrown for any peer-caused protocol violation.
 *
 * Never carries peer content: [message] is a short static reason plus bounded numeric detail so
 * it is always safe to log. See docs/SECURITY.md "Logging".
 */
class ProtocolException(
    val errorCode: ProtocolErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun protocolError(code: ProtocolErrorCode, reason: String): Nothing =
    throw ProtocolException(code, reason)
