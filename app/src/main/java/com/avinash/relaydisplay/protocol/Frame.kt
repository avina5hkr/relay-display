package com.avinash.relaydisplay.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * One wire frame.
 *
 * Layout (big-endian, 20-byte header then payload):
 * ```
 *  0  4  magic       0x524C5931 "RLY1"
 *  4  1  versionMajor
 *  5  1  versionMinor
 *  6  1  flags       bit0 = ENCRYPTED
 *  7  1  reserved    must be 0
 *  8  8  sequence    per-direction record counter, big-endian unsigned
 * 16  4  payloadLen  0 .. MAX_FRAME_PAYLOAD
 * ```
 * For encrypted frames the payload is AES-GCM ciphertext||tag and the 20 header bytes are the
 * AAD, which binds the sequence number and version into the authentication tag.
 */
data class Frame(
    val versionMajor: Int,
    val versionMinor: Int,
    val flags: Int,
    val sequence: Long,
    val payload: ByteArray,
) {
    val isEncrypted: Boolean get() = (flags and ProtocolConstants.FLAG_ENCRYPTED) != 0

    /** The exact header bytes for this frame; also the AAD for encrypted frames. */
    fun header(): ByteArray = FrameCodec.encodeHeader(versionMajor, versionMinor, flags, sequence, payload.size)

    fun encode(): ByteArray = header() + payload

    // Data class equality on a ByteArray member would compare identity; spell it out.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return versionMajor == other.versionMajor &&
            versionMinor == other.versionMinor &&
            flags == other.flags &&
            sequence == other.sequence &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = versionMajor
        r = 31 * r + versionMinor
        r = 31 * r + flags
        r = 31 * r + sequence.hashCode()
        r = 31 * r + payload.contentHashCode()
        return r
    }

    override fun toString(): String =
        "Frame(v=$versionMajor.$versionMinor, flags=$flags, seq=$sequence, payload=${payload.size}B)"
}

object FrameCodec {

    fun encodeHeader(
        versionMajor: Int,
        versionMinor: Int,
        flags: Int,
        sequence: Long,
        payloadLength: Int,
    ): ByteArray {
        require(payloadLength in 0..ProtocolConstants.MAX_FRAME_PAYLOAD) { "payload length out of range" }
        val h = ByteArray(ProtocolConstants.HEADER_SIZE)
        writeInt(h, 0, ProtocolConstants.MAGIC)
        h[4] = versionMajor.toByte()
        h[5] = versionMinor.toByte()
        h[6] = flags.toByte()
        h[7] = 0
        writeLong(h, 8, sequence)
        writeInt(h, 16, payloadLength)
        return h
    }

    fun encode(frame: Frame): ByteArray = frame.encode()

    /**
     * Validate a header and return the announced payload length.
     *
     * This is the single place where a peer-supplied length is checked, and it is checked
     * *before* any buffer is allocated for that length.
     */
    fun parseHeader(header: ByteArray, offset: Int = 0): FrameHeader {
        if (header.size - offset < ProtocolConstants.HEADER_SIZE) {
            protocolError(ProtocolErrorCode.MALFORMED_FRAME, "short header")
        }
        val magic = readInt(header, offset)
        if (magic != ProtocolConstants.MAGIC) {
            protocolError(ProtocolErrorCode.BAD_MAGIC, "bad magic")
        }
        val major = header[offset + 4].toInt() and 0xFF
        val minor = header[offset + 5].toInt() and 0xFF
        if (major != ProtocolConstants.VERSION_MAJOR) {
            protocolError(ProtocolErrorCode.UNSUPPORTED_VERSION, "major version $major unsupported")
        }
        val flags = header[offset + 6].toInt() and 0xFF
        if ((flags and ProtocolConstants.FLAG_ENCRYPTED.inv() and 0xFF) != 0) {
            protocolError(ProtocolErrorCode.MALFORMED_FRAME, "unknown frame flags")
        }
        if (header[offset + 7].toInt() != 0) {
            protocolError(ProtocolErrorCode.MALFORMED_FRAME, "reserved byte not zero")
        }
        val sequence = readLong(header, offset + 8)
        if (sequence < 0) {
            protocolError(ProtocolErrorCode.MALFORMED_FRAME, "sequence overflow")
        }
        val length = readInt(header, offset + 16)
        if (length < 0 || length > ProtocolConstants.MAX_FRAME_PAYLOAD) {
            protocolError(
                ProtocolErrorCode.PAYLOAD_TOO_LARGE,
                "declared payload $length exceeds ${ProtocolConstants.MAX_FRAME_PAYLOAD}",
            )
        }
        return FrameHeader(major, minor, flags, sequence, length)
    }

    /** Decode a complete frame that already sits in memory. Used by tests and by the loopback transport. */
    fun decode(bytes: ByteArray): Frame {
        val h = parseHeader(bytes)
        val total = ProtocolConstants.HEADER_SIZE + h.payloadLength
        if (bytes.size < total) protocolError(ProtocolErrorCode.MALFORMED_FRAME, "truncated frame")
        return Frame(
            versionMajor = h.versionMajor,
            versionMinor = h.versionMinor,
            flags = h.flags,
            sequence = h.sequence,
            payload = bytes.copyOfRange(ProtocolConstants.HEADER_SIZE, total),
        )
    }

    /**
     * Read exactly one frame from [input], tolerating a stream that returns fewer bytes than asked.
     *
     * Returns null on a clean end of stream at a frame boundary; that is a normal disconnect,
     * not an error.
     */
    fun readFrame(input: InputStream): Frame? {
        val header = ByteArray(ProtocolConstants.HEADER_SIZE)
        if (!readFullyOrEof(input, header)) return null
        val h = parseHeader(header)
        val payload = ByteArray(h.payloadLength)
        if (h.payloadLength > 0 && !readFullyOrEof(input, payload)) {
            protocolError(ProtocolErrorCode.MALFORMED_FRAME, "stream ended mid-payload")
        }
        return Frame(h.versionMajor, h.versionMinor, h.flags, h.sequence, payload)
    }

    fun writeFrame(output: OutputStream, frame: Frame) {
        output.write(frame.header())
        if (frame.payload.isNotEmpty()) output.write(frame.payload)
        output.flush()
    }

    /**
     * Fill [buf] completely. Returns false only if EOF happened before the first byte;
     * an EOF part-way through is a truncated frame and throws.
     */
    private fun readFullyOrEof(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("stream ended after $read of ${buf.size} bytes")
            }
            read += n
        }
        return true
    }

    internal fun writeInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    internal fun writeLong(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
    }

    internal fun readInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    internal fun readLong(b: ByteArray, at: Int): Long {
        var acc = 0L
        for (i in 0 until 8) acc = (acc shl 8) or (b[at + i].toLong() and 0xFF)
        return acc
    }
}

data class FrameHeader(
    val versionMajor: Int,
    val versionMinor: Int,
    val flags: Int,
    val sequence: Long,
    val payloadLength: Int,
)
