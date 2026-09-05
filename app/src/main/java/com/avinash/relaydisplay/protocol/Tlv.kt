package com.avinash.relaydisplay.protocol

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * A deterministic tag/length/value body codec.
 *
 * Encoding rules (these are what make the encoding canonical, so golden tests are meaningful):
 *  - Fields are `tag:u8 | length:u32be | value[length]`.
 *  - Tags MUST be written in strictly ascending order and MUST NOT repeat.
 *  - A decoder that sees an out-of-order or repeated tag rejects the whole body.
 *
 * The strict ordering means one logical message has exactly one valid byte encoding, which
 * matters because the handshake transcript hashes encoded bytes.
 */
object Tlv {
    /** Refuse absurd field counts before we build a map for a peer. */
    const val MAX_FIELDS: Int = 64
}

class TlvWriter(expectedSize: Int = 64) {
    private val out = ByteArrayOutputStream(expectedSize)
    private var lastTag = -1
    private var fields = 0

    private fun beginField(tag: Int, length: Int) {
        require(tag in 0..255) { "tag out of range" }
        require(tag > lastTag) { "TLV tags must be written in ascending order (got $tag after $lastTag)" }
        require(length >= 0) { "negative length" }
        if (++fields > Tlv.MAX_FIELDS) throw IllegalStateException("too many TLV fields")
        lastTag = tag
        out.write(tag)
        out.write((length ushr 24) and 0xFF)
        out.write((length ushr 16) and 0xFF)
        out.write((length ushr 8) and 0xFF)
        out.write(length and 0xFF)
    }

    fun putBytes(tag: Int, value: ByteArray): TlvWriter {
        beginField(tag, value.size)
        out.write(value, 0, value.size)
        return this
    }

    fun putString(tag: Int, value: String): TlvWriter = putBytes(tag, value.toByteArray(StandardCharsets.UTF_8))

    fun putU8(tag: Int, value: Int): TlvWriter {
        require(value in 0..255) { "u8 out of range" }
        beginField(tag, 1)
        out.write(value)
        return this
    }

    fun putBool(tag: Int, value: Boolean): TlvWriter = putU8(tag, if (value) 1 else 0)

    fun putU16(tag: Int, value: Int): TlvWriter {
        require(value in 0..0xFFFF) { "u16 out of range" }
        beginField(tag, 2)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
        return this
    }

    fun putU32(tag: Int, value: Long): TlvWriter {
        require(value in 0..0xFFFF_FFFFL) { "u32 out of range" }
        beginField(tag, 4)
        for (shift in intArrayOf(24, 16, 8, 0)) out.write(((value ushr shift) and 0xFF).toInt())
        return this
    }

    fun putI64(tag: Int, value: Long): TlvWriter {
        beginField(tag, 8)
        for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) out.write(((value ushr shift) and 0xFF).toInt())
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * Parsed TLV body. Construction validates structure; accessors validate types and ranges.
 *
 * All failures raise [ProtocolException] so a hostile peer can only ever produce a clean
 * protocol error, never an [IndexOutOfBoundsException] escaping to a coroutine handler.
 */
class TlvReader private constructor(private val fields: Map<Int, ByteArray>) {

    fun has(tag: Int): Boolean = fields.containsKey(tag)

    fun optBytes(tag: Int, max: Int): ByteArray? {
        val v = fields[tag] ?: return null
        if (v.size > max) protocolError(ProtocolErrorCode.PAYLOAD_TOO_LARGE, "field $tag is ${v.size}B > ${max}B")
        return v
    }

    fun bytes(tag: Int, max: Int): ByteArray =
        optBytes(tag, max) ?: protocolError(ProtocolErrorCode.MALFORMED_FRAME, "missing field $tag")

    fun optString(tag: Int, maxBytes: Int): String? {
        val raw = optBytes(tag, maxBytes) ?: return null
        return decodeUtf8Strict(tag, raw)
    }

    fun string(tag: Int, maxBytes: Int): String =
        optString(tag, maxBytes) ?: protocolError(ProtocolErrorCode.MALFORMED_FRAME, "missing field $tag")

    fun optU8(tag: Int): Int? {
        val v = fields[tag] ?: return null
        if (v.size != 1) protocolError(ProtocolErrorCode.MALFORMED_FRAME, "field $tag is not u8")
        return v[0].toInt() and 0xFF
    }

    fun u8(tag: Int): Int = optU8(tag) ?: protocolError(ProtocolErrorCode.MALFORMED_FRAME, "missing field $tag")

    fun optBool(tag: Int): Boolean? = optU8(tag)?.let {
        when (it) {
            0 -> false
            1 -> true
            else -> protocolError(ProtocolErrorCode.MALFORMED_FRAME, "field $tag is not a bool")
        }
    }

    fun bool(tag: Int): Boolean = optBool(tag) ?: protocolError(ProtocolErrorCode.MALFORMED_FRAME, "missing field $tag")

    fun optU16(tag: Int): Int? {
        val v = fields[tag] ?: return null
        if (v.size != 2) protocolError(ProtocolErrorCode.MALFORMED_FRAME, "field $tag is not u16")
        return ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)
    }

    fun u16(tag: Int): Int = optU16(tag) ?: protocolError(ProtocolErrorCode.MALFORMED_FRAME, "missing field $tag")

    fun optU32(tag: Int): Long? {
        val v = fields[tag] ?: return null
        if (v.size != 4) protocolError(ProtocolErrorCode.MALFORMED_FRAME, "field $tag is not u32")
        var acc = 0L
        for (b in v) acc = (acc shl 8) or (b.toLong() and 0xFF)
        return acc
    }

    fun u32(tag: Int): Long = optU32(tag) ?: protocolError(ProtocolErrorCode.MALFORMED_FRAME, "missing field $tag")

    fun optI64(tag: Int): Long? {
        val v = fields[tag] ?: return null
        if (v.size != 8) protocolError(ProtocolErrorCode.MALFORMED_FRAME, "field $tag is not i64")
        var acc = 0L
        for (b in v) acc = (acc shl 8) or (b.toLong() and 0xFF)
        return acc
    }

    fun i64(tag: Int): Long = optI64(tag) ?: protocolError(ProtocolErrorCode.MALFORMED_FRAME, "missing field $tag")

    private fun decodeUtf8Strict(tag: Int, raw: ByteArray): String {
        val decoded = String(raw, StandardCharsets.UTF_8)
        // Round-trip check rejects malformed UTF-8 (which String() would silently replace with U+FFFD).
        if (!decoded.toByteArray(StandardCharsets.UTF_8).contentEquals(raw)) {
            protocolError(ProtocolErrorCode.MALFORMED_FRAME, "field $tag is not valid UTF-8")
        }
        return decoded
    }

    companion object {
        fun parse(body: ByteArray, offset: Int = 0, length: Int = body.size - offset): TlvReader {
            if (offset < 0 || length < 0 || offset + length > body.size) {
                protocolError(ProtocolErrorCode.MALFORMED_FRAME, "TLV bounds out of range")
            }
            val fields = LinkedHashMap<Int, ByteArray>()
            var pos = offset
            val end = offset + length
            var lastTag = -1
            while (pos < end) {
                if (end - pos < 5) protocolError(ProtocolErrorCode.MALFORMED_FRAME, "truncated TLV header")
                val tag = body[pos].toInt() and 0xFF
                val len = readU32(body, pos + 1)
                pos += 5
                if (len < 0 || len > (end - pos)) {
                    protocolError(ProtocolErrorCode.MALFORMED_FRAME, "TLV field $tag length $len exceeds body")
                }
                if (tag <= lastTag) {
                    protocolError(ProtocolErrorCode.MALFORMED_FRAME, "TLV tag $tag out of order or repeated")
                }
                if (fields.size >= Tlv.MAX_FIELDS) {
                    protocolError(ProtocolErrorCode.MALFORMED_FRAME, "too many TLV fields")
                }
                lastTag = tag
                fields[tag] = body.copyOfRange(pos, pos + len)
                pos += len
            }
            return TlvReader(fields)
        }

        private fun readU32(b: ByteArray, at: Int): Int {
            val v = ((b[at].toLong() and 0xFF) shl 24) or
                ((b[at + 1].toLong() and 0xFF) shl 16) or
                ((b[at + 2].toLong() and 0xFF) shl 8) or
                (b[at + 3].toLong() and 0xFF)
            // Anything with the top bit set is hostile (would become a negative Int); reject before use.
            if (v > Int.MAX_VALUE) protocolError(ProtocolErrorCode.PAYLOAD_TOO_LARGE, "TLV length field too large")
            return v.toInt()
        }
    }
}
