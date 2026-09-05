package com.avinash.relaydisplay.content

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A rendered QR code as a plain bit matrix.
 *
 * Deliberately not a Bitmap: the display draws these as exact rectangles on a Canvas, so a code
 * is always crisp at any size instead of being a small bitmap stretched with interpolation. That
 * matters a lot for whether a phone camera locks onto it on the first try.
 */
class QrMatrix(val size: Int, private val bits: BooleanArray) {
    init {
        require(size > 0 && bits.size == size * size) { "matrix must be square" }
    }

    /** True where a dark module should be painted. */
    operator fun get(x: Int, y: Int): Boolean = bits[y * size + x]

    fun darkModuleCount(): Int = bits.count { it }
}

/** Why a payload could not be turned into a scannable code. */
enum class QrEncodeError { EMPTY, TOO_LARGE, ENCODER_FAILED }

sealed interface QrEncodeResult {
    data class Success(val matrix: QrMatrix, val warning: QrWarning?) : QrEncodeResult
    data class Failure(val error: QrEncodeError) : QrEncodeResult
}

/** A code that will encode but may be awkward to scan in practice. */
enum class QrWarning {
    /** Dense enough that a small or dirty screen may not resolve the modules. */
    DENSE,
}

object QrEncoder {

    /** Four modules of white border, as the QR specification requires for reliable detection. */
    const val QUIET_ZONE_MODULES = 4

    /**
     * Hard cap on payload bytes.
     *
     * Far below the format's theoretical 2953 bytes: past roughly this size the module grid gets
     * so fine that a phone camera pointed at another phone's screen struggles, and offering the
     * user a code that will not scan is worse than telling them it is too long.
     */
    const val MAX_PAYLOAD_BYTES = 1200

    /** Above this, still encode but warn that it may be hard to scan. */
    const val DENSE_PAYLOAD_BYTES = 600

    fun encode(payload: String): QrEncodeResult {
        if (payload.isEmpty()) return QrEncodeResult.Failure(QrEncodeError.EMPTY)
        val byteLength = payload.toByteArray(Charsets.UTF_8).size
        if (byteLength > MAX_PAYLOAD_BYTES) return QrEncodeResult.Failure(QrEncodeError.TOO_LARGE)

        // More redundancy while the payload is short and the grid is coarse; step down as it
        // grows so the module count stays manageable rather than the code becoming unreadable.
        val correction = when {
            byteLength <= 120 -> ErrorCorrectionLevel.Q
            byteLength <= 400 -> ErrorCorrectionLevel.M
            else -> ErrorCorrectionLevel.L
        }

        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to correction,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        )

        return try {
            // Width and height of 1 asks ZXing for the natural module grid; it enlarges to the
            // smallest size that fits, which is exactly the un-scaled matrix we want.
            val encoded = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 1, 1, hints)
            val size = encoded.width
            val bits = BooleanArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    bits[y * size + x] = encoded.get(x, y)
                }
            }
            QrEncodeResult.Success(
                matrix = QrMatrix(size, bits),
                warning = if (byteLength > DENSE_PAYLOAD_BYTES) QrWarning.DENSE else null,
            )
        } catch (e: WriterException) {
            QrEncodeResult.Failure(QrEncodeError.ENCODER_FAILED)
        } catch (e: IllegalArgumentException) {
            QrEncodeResult.Failure(QrEncodeError.ENCODER_FAILED)
        }
    }
}
