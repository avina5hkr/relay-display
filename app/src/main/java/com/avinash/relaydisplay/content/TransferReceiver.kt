package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.protocol.ContentLimits
import com.avinash.relaydisplay.protocol.ContentOffer
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.TransferChunk
import com.avinash.relaydisplay.protocol.TransferComplete
import com.avinash.relaydisplay.protocol.TransferStart
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** The result of a step in a transfer. */
sealed interface TransferOutcome {
    data object Continue : TransferOutcome
    data class Finished(val file: File, val displayName: String, val kind: ContentKind) : TransferOutcome
    data class Rejected(val code: ProtocolErrorCode, val reason: String) : TransferOutcome
}

/**
 * Receives one file, streamed to disk and verified before anything reads it.
 *
 * Invariants:
 *  - the whole file is never held in memory; chunks go straight to a temp file,
 *  - the announced size is checked against the limits *and* against free space before a single
 *    byte is accepted,
 *  - the running total and the SHA-256 are both verified before the file is promoted, so a
 *    truncated or altered transfer never reaches a decoder,
 *  - every failure path deletes the partial file.
 *
 * One instance handles one transfer. The router keeps at most
 * [ContentLimits.MAX_CONCURRENT_TRANSFERS] alive at a time.
 */
class TransferReceiver(private val cache: ContentCache) {

    private enum class Stage { OFFERED, RECEIVING, DONE, FAILED }

    private var stage = Stage.OFFERED

    private var transferId: UUID? = null
    private var expectedBytes: Long = 0
    private var expectedDigest: ByteArray = ByteArray(0)
    private var declaredMime: String = ""
    private var displayName: String = ""
    private var kind: ContentKind = ContentKind.IMAGE
    private var chunkSize: Int = ContentLimits.CHUNK_BYTES

    private var partial: File? = null
    private var sink: BufferedOutputStream? = null
    private val digest = MessageDigest.getInstance("SHA-256")
    private var receivedBytes: Long = 0
    private var nextChunkIndex: Long = 0

    val bytesReceived: Long get() = receivedBytes
    val bytesExpected: Long get() = expectedBytes

    /** Decides whether an offer can be accepted at all. Nothing is opened until it is. */
    fun evaluate(offer: ContentOffer): TransferOutcome {
        if (offer.sizeBytes <= 0) {
            return TransferOutcome.Rejected(ProtocolErrorCode.MALFORMED_FRAME, "empty transfer")
        }
        if (offer.sizeBytes > ContentLimits.MAX_FILE_BYTES) {
            return TransferOutcome.Rejected(ProtocolErrorCode.TOO_LARGE, "over the size limit")
        }
        if (!MimeSupport.isSupported(offer.kind, offer.mimeType)) {
            return TransferOutcome.Rejected(ProtocolErrorCode.UNSUPPORTED_FORMAT, "unsupported type")
        }
        // Ask for the whole file plus headroom, so accepting never fills the volume.
        if (cache.usableSpaceBytes() < offer.sizeBytes + ContentCache.STORAGE_HEADROOM_BYTES) {
            return TransferOutcome.Rejected(ProtocolErrorCode.INSUFFICIENT_STORAGE, "not enough space")
        }

        transferId = offer.transferId
        expectedBytes = offer.sizeBytes
        expectedDigest = offer.sha256
        declaredMime = offer.mimeType
        displayName = FilenameSanitizer.sanitize(offer.displayName)
        kind = offer.kind
        return TransferOutcome.Continue
    }

    /** Opens the temp file. Called after the offer was accepted and the sender has started. */
    fun begin(start: TransferStart): TransferOutcome {
        if (stage != Stage.OFFERED || start.transferId != transferId) {
            return fail(ProtocolErrorCode.MALFORMED_FRAME, "transfer start out of order")
        }
        if (start.totalBytes != expectedBytes) {
            return fail(ProtocolErrorCode.MALFORMED_FRAME, "size changed after the offer")
        }
        if (start.chunkSize !in 1..ContentLimits.CHUNK_BYTES) {
            return fail(ProtocolErrorCode.MALFORMED_FRAME, "chunk size out of range")
        }
        chunkSize = start.chunkSize
        return try {
            val file = cache.createPartial(start.transferId)
            partial = file
            sink = BufferedOutputStream(FileOutputStream(file), BUFFER_BYTES)
            stage = Stage.RECEIVING
            TransferOutcome.Continue
        } catch (e: IOException) {
            fail(ProtocolErrorCode.INSUFFICIENT_STORAGE, "could not open the cache file")
        }
    }

    fun accept(chunk: TransferChunk): TransferOutcome {
        if (stage != Stage.RECEIVING || chunk.transferId != transferId) {
            return fail(ProtocolErrorCode.MALFORMED_FRAME, "chunk for an unknown transfer")
        }
        // Chunks must arrive in order. On one ordered TCP stream anything else is a bug or an
        // injection attempt, and accepting it would mean seeking, which invalidates the digest.
        if (chunk.index != nextChunkIndex) {
            return fail(ProtocolErrorCode.MALFORMED_FRAME, "chunk ${chunk.index} out of order")
        }
        if (chunk.data.size > chunkSize) {
            return fail(ProtocolErrorCode.PAYLOAD_TOO_LARGE, "chunk larger than announced")
        }
        if (receivedBytes + chunk.data.size > expectedBytes) {
            return fail(ProtocolErrorCode.TOO_LARGE, "more bytes than the offer declared")
        }
        return try {
            sink?.write(chunk.data) ?: return fail(ProtocolErrorCode.INTERNAL, "no open sink")
            digest.update(chunk.data)
            receivedBytes += chunk.data.size
            nextChunkIndex++
            TransferOutcome.Continue
        } catch (e: IOException) {
            fail(ProtocolErrorCode.INSUFFICIENT_STORAGE, "write failed")
        }
    }

    /** Verifies length and digest, then atomically promotes the file. */
    fun finish(complete: TransferComplete): TransferOutcome {
        if (stage != Stage.RECEIVING || complete.transferId != transferId) {
            return fail(ProtocolErrorCode.MALFORMED_FRAME, "completion for an unknown transfer")
        }
        val file = partial ?: return fail(ProtocolErrorCode.INTERNAL, "no partial file")
        try {
            sink?.flush()
            sink?.close()
        } catch (e: IOException) {
            return fail(ProtocolErrorCode.INSUFFICIENT_STORAGE, "could not flush")
        }
        sink = null

        if (receivedBytes != expectedBytes) {
            return fail(ProtocolErrorCode.DECODE_FAILED, "received $receivedBytes of $expectedBytes")
        }
        val actual = digest.digest()
        if (!actual.contentEquals(expectedDigest) || !actual.contentEquals(complete.sha256)) {
            // Either the sender's own claim disagrees, or the bytes changed on the way.
            return fail(ProtocolErrorCode.DECODE_FAILED, "digest mismatch")
        }
        // Trust the bytes, not the label: a file whose contents do not match its declared type
        // is refused before any decoder is pointed at it.
        val sniffed = MimeSniffer.sniff(file)
        if (!MimeSupport.matchesKind(kind, sniffed)) {
            return fail(ProtocolErrorCode.UNSUPPORTED_FORMAT, "content does not match its type")
        }

        val promoted = cache.promote(file, complete.transferId, MimeSupport.extensionFor(sniffed))
            ?: return fail(ProtocolErrorCode.INSUFFICIENT_STORAGE, "could not store the file")
        partial = null
        stage = Stage.DONE
        return TransferOutcome.Finished(promoted, displayName, kind)
    }

    /** Cancels and removes the partial file. Safe to call more than once. */
    fun cancel() {
        if (stage == Stage.DONE) return
        stage = Stage.FAILED
        try {
            sink?.close()
        } catch (e: IOException) {
            // Nothing to do; the file is about to be deleted.
        }
        sink = null
        partial?.delete()
        partial = null
    }

    private fun fail(code: ProtocolErrorCode, reason: String): TransferOutcome {
        cancel()
        return TransferOutcome.Rejected(code, reason)
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

/** Which MIME types each content kind will accept, and what a file should be called. */
object MimeSupport {

    private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heif", "image/heic")
    private val PDF_TYPES = setOf("application/pdf")

    fun isSupported(kind: ContentKind, mimeType: String): Boolean = when (kind) {
        ContentKind.IMAGE -> mimeType.lowercase() in IMAGE_TYPES
        ContentKind.PDF -> mimeType.lowercase() in PDF_TYPES
    }

    /** Whether sniffed content actually belongs to the kind the sender claimed. */
    fun matchesKind(kind: ContentKind, sniffed: SniffedType): Boolean = when (kind) {
        ContentKind.IMAGE -> sniffed in setOf(
            SniffedType.JPEG, SniffedType.PNG, SniffedType.WEBP, SniffedType.GIF, SniffedType.HEIF,
        )
        ContentKind.PDF -> sniffed == SniffedType.PDF
    }

    fun extensionFor(sniffed: SniffedType): String? = when (sniffed) {
        SniffedType.JPEG -> "jpg"
        SniffedType.PNG -> "png"
        SniffedType.WEBP -> "webp"
        SniffedType.GIF -> "gif"
        SniffedType.HEIF -> "heic"
        SniffedType.PDF -> "pdf"
        SniffedType.UNKNOWN -> null
    }

    fun kindForMime(mimeType: String?): ContentKind? {
        val type = mimeType?.lowercase() ?: return null
        return when {
            type in IMAGE_TYPES -> ContentKind.IMAGE
            type in PDF_TYPES -> ContentKind.PDF
            else -> null
        }
    }
}

enum class SniffedType { JPEG, PNG, WEBP, GIF, HEIF, PDF, UNKNOWN }

/**
 * Identifies a file from its leading bytes.
 *
 * The declared MIME type comes from the peer and is therefore a hint, not a fact. This is what
 * stops a PDF renderer or a bitmap decoder being pointed at something that is not what it says.
 */
object MimeSniffer {

    private const val HEADER_BYTES = 32

    fun sniff(file: File): SniffedType = try {
        val header = ByteArray(HEADER_BYTES)
        val read = file.inputStream().use { it.read(header) }
        if (read <= 0) SniffedType.UNKNOWN else sniff(header, read)
    } catch (e: IOException) {
        SniffedType.UNKNOWN
    }

    fun sniff(header: ByteArray, length: Int = header.size): SniffedType {
        fun at(index: Int): Int = if (index < length) header[index].toInt() and 0xFF else -1
        fun matches(offset: Int, vararg bytes: Int): Boolean =
            bytes.withIndex().all { (i, b) -> at(offset + i) == b }

        return when {
            matches(0, 0xFF, 0xD8, 0xFF) -> SniffedType.JPEG
            matches(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> SniffedType.PNG
            // "RIFF" .... "WEBP"
            matches(0, 0x52, 0x49, 0x46, 0x46) && matches(8, 0x57, 0x45, 0x42, 0x50) -> SniffedType.WEBP
            matches(0, 0x47, 0x49, 0x46, 0x38) -> SniffedType.GIF
            // "ftyp" at offset 4, then a HEIF brand.
            matches(4, 0x66, 0x74, 0x79, 0x70) && isHeifBrand(header, length) -> SniffedType.HEIF
            matches(0, 0x25, 0x50, 0x44, 0x46, 0x2D) -> SniffedType.PDF
            else -> SniffedType.UNKNOWN
        }
    }

    private fun isHeifBrand(header: ByteArray, length: Int): Boolean {
        if (length < 12) return false
        val brand = String(header, 8, 4, Charsets.US_ASCII)
        return brand in setOf("heic", "heix", "hevc", "heim", "heis", "hevm", "mif1", "msf1")
    }
}
