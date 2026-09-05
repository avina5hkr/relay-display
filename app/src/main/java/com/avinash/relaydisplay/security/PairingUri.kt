package com.avinash.relaydisplay.security

import com.avinash.relaydisplay.protocol.ProtocolTimings
import java.security.SecureRandom

/**
 * The payload inside a pairing QR code.
 *
 * Shape: `relaydisplay://pair?v=1&id=..&fp=..&h=..&p=..&t=..&exp=..&n=..`
 *
 * Two properties matter more than the syntax:
 *  - It is *data*, never a command. Nothing in here is ever handed to an Intent, a browser or a
 *    file API. It only ever produces a fingerprint to pin, an address to dial and a token to
 *    prove knowledge of.
 *  - It is validated field by field before any of it is used, because a QR code is attacker
 *    controlled input: anyone can print one and leave it lying around.
 */
data class PairingPayload(
    val version: Int,
    val peerId: String,
    val fingerprint: ByteArray,
    val host: String,
    val port: Int,
    val token: ByteArray,
    val expiresAtEpochMs: Long,
    val displayName: String,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtEpochMs

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingPayload) return false
        return version == other.version && peerId == other.peerId &&
            fingerprint.contentEquals(other.fingerprint) && host == other.host &&
            port == other.port && token.contentEquals(other.token) &&
            expiresAtEpochMs == other.expiresAtEpochMs && displayName == other.displayName
    }

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + peerId.hashCode()
        r = 31 * r + fingerprint.contentHashCode()
        r = 31 * r + token.contentHashCode()
        return r
    }

    /** Never logs the token or the full fingerprint. */
    override fun toString(): String = "PairingPayload(v=$version, id=$peerId, port=$port)"
}

/** Why a scanned code was refused. Each maps to a specific message on the pairing screen. */
enum class PairingUriError {
    NOT_A_RELAY_CODE,
    UNSUPPORTED_VERSION,
    MALFORMED,
    BAD_FINGERPRINT,
    BAD_ADDRESS,
    WEAK_TOKEN,
    EXPIRED,
    TTL_TOO_LONG,
}

sealed interface PairingUriResult {
    data class Valid(val payload: PairingPayload) : PairingUriResult
    data class Invalid(val error: PairingUriError) : PairingUriResult
}

object PairingUri {

    const val SCHEME = "relaydisplay"
    const val HOST = "pair"
    const val VERSION = 1

    /** 128 bits, as the spec requires. Single use, short lived. */
    const val TOKEN_BYTES = 16
    private const val MIN_TOKEN_BYTES = 16
    private const val MAX_TOKEN_BYTES = 64
    private const val FINGERPRINT_HEX_LENGTH = 64
    private const val MAX_PEER_ID_LENGTH = 64
    private const val MAX_NAME_LENGTH = 32
    private const val MAX_URI_LENGTH = 512

    fun newToken(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }

    fun build(
        peerId: String,
        fingerprint: ByteArray,
        host: String,
        port: Int,
        token: ByteArray,
        expiresAtEpochMs: Long,
        displayName: String,
    ): String = buildString {
        append(SCHEME).append("://").append(HOST)
        append("?v=").append(VERSION)
        append("&id=").append(percentEncode(peerId))
        append("&fp=").append(Fingerprints.toHex(fingerprint))
        append("&h=").append(host)
        append("&p=").append(port)
        append("&t=").append(Fingerprints.toHex(token))
        append("&exp=").append(expiresAtEpochMs)
        append("&n=").append(percentEncode(displayName))
    }

    /**
     * Parses and fully validates a scanned code.
     *
     * [nowMs] is passed in rather than read from the clock so expiry is testable, and
     * [maxTtlMs] caps how far in the future a code may claim to be valid: a code that says it
     * lives for a year is not a code we issued.
     */
    fun parse(
        raw: String,
        nowMs: Long,
        maxTtlMs: Long = ProtocolTimings.PAIRING_TOKEN_TTL_MS,
    ): PairingUriResult {
        if (raw.length > MAX_URI_LENGTH) return PairingUriResult.Invalid(PairingUriError.MALFORMED)

        val prefix = "$SCHEME://$HOST?"
        if (!raw.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
            return PairingUriResult.Invalid(PairingUriError.NOT_A_RELAY_CODE)
        }
        val uri = parseQuery(raw.substring(prefix.length))
            ?: return PairingUriResult.Invalid(PairingUriError.MALFORMED)

        val version = uri.getQueryParameter("v")?.toIntOrNull()
            ?: return PairingUriResult.Invalid(PairingUriError.MALFORMED)
        if (version != VERSION) return PairingUriResult.Invalid(PairingUriError.UNSUPPORTED_VERSION)

        val peerId = uri.getQueryParameter("id").orEmpty()
        if (peerId.isEmpty() || peerId.length > MAX_PEER_ID_LENGTH || !peerId.all(::isSafeIdChar)) {
            return PairingUriResult.Invalid(PairingUriError.MALFORMED)
        }

        val fpHex = uri.getQueryParameter("fp").orEmpty()
        if (fpHex.length != FINGERPRINT_HEX_LENGTH) {
            return PairingUriResult.Invalid(PairingUriError.BAD_FINGERPRINT)
        }
        val fingerprint = Fingerprints.fromHex(fpHex)
            ?: return PairingUriResult.Invalid(PairingUriError.BAD_FINGERPRINT)

        val host = uri.getQueryParameter("h").orEmpty()
        // Only a numeric IPv4 literal. A hostname would mean a DNS lookup driven by a scanned
        // code, which is an outbound request to a name an attacker chose.
        if (!isIpv4Literal(host)) return PairingUriResult.Invalid(PairingUriError.BAD_ADDRESS)

        val port = uri.getQueryParameter("p")?.toIntOrNull()
            ?: return PairingUriResult.Invalid(PairingUriError.BAD_ADDRESS)
        if (port !in 1..65535) return PairingUriResult.Invalid(PairingUriError.BAD_ADDRESS)

        val token = uri.getQueryParameter("t")?.let(Fingerprints::fromHex)
            ?: return PairingUriResult.Invalid(PairingUriError.MALFORMED)
        if (token.size !in MIN_TOKEN_BYTES..MAX_TOKEN_BYTES) {
            return PairingUriResult.Invalid(PairingUriError.WEAK_TOKEN)
        }
        if (token.all { it == token[0] }) {
            // All-identical bytes is not something a CSPRNG produces; it is a hand-written code.
            return PairingUriResult.Invalid(PairingUriError.WEAK_TOKEN)
        }

        val expires = uri.getQueryParameter("exp")?.toLongOrNull()
            ?: return PairingUriResult.Invalid(PairingUriError.MALFORMED)
        if (expires <= nowMs) return PairingUriResult.Invalid(PairingUriError.EXPIRED)
        if (expires - nowMs > maxTtlMs) return PairingUriResult.Invalid(PairingUriError.TTL_TOO_LONG)

        val name = uri.getQueryParameter("n").orEmpty().take(MAX_NAME_LENGTH)

        return PairingUriResult.Valid(
            PairingPayload(version, peerId, fingerprint, host, port, token, expires, sanitizeName(name)),
        )
    }

    private fun isSafeIdChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-' || c == '_'

    /** Strips anything that could smuggle formatting into a label the user is asked to trust. */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim()
        return cleaned.ifEmpty { "Unnamed device" }
    }

    internal fun isIpv4Literal(value: String): Boolean {
        if (value.isEmpty() || value.length > 15) return false
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
                // Reject "01" style octets so one address has exactly one spelling.
                (part.length == 1 || part[0] != '0') &&
                part.toInt() in 0..255
        }
    }

    /**
     * A deliberately small query-string parser.
     *
     * Hand-written rather than android.net.Uri for two reasons: it makes the whole class unit
     * testable on the JVM, and it means the exact grammar accepted here is visible instead of
     * inherited from a permissive platform parser. Repeated keys are rejected rather than
     * silently resolved, so a code cannot smuggle a second "fp=" past a reader that only sees
     * the first.
     */
    internal fun parseQuery(query: String): QueryParams? {
        if (query.isEmpty()) return null
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) return null
            val eq = pair.indexOf('=')
            if (eq <= 0 || eq == pair.length - 1) return null
            val key = pair.substring(0, eq)
            val value = percentDecode(pair.substring(eq + 1)) ?: return null
            if (out.put(key, value) != null) return null
        }
        return QueryParams(out)
    }

    internal class QueryParams(private val values: Map<String, String>) {
        fun getQueryParameter(name: String): String? = values[name]
    }

    private fun percentEncode(value: String): String = buildString {
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~') {
                append(c)
            } else {
                append('%').append("%02X".format(b))
            }
        }
    }

    private fun percentDecode(value: String): String? {
        if ('%' !in value) return value
        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                out.write(c.code)
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
