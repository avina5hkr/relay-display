package com.avinash.relaydisplay.content

/**
 * Scheme policy for links that arrive from a peer or from a share intent.
 *
 * A received link is data until the local user taps it. This object decides which links may even
 * be offered: `file:`, `content:`, `intent:` and `javascript:` are refused outright, because a
 * paired phone talking one of those into another app is the whole shape of the attack this
 * feature would otherwise create.
 */
object UrlValidation {

    const val MAX_URL_LENGTH = 2048

    private val ALLOWED = setOf("https", "http")

    /**
     * Schemes that are never acceptable from a peer, listed explicitly so the reason is on the
     * record rather than implied by the allow list.
     */
    private val EXPLICITLY_DANGEROUS = setOf(
        "file", "content", "intent", "javascript", "data", "jar", "android-app", "market",
    )

    enum class Verdict {
        /** Safe to show and to offer to open. */
        ALLOWED,

        /** Allowed, but plaintext: worth a warning next to the open button. */
        ALLOWED_INSECURE,

        REJECTED_SCHEME,
        REJECTED_MALFORMED,
        REJECTED_TOO_LONG,
    }

    fun check(raw: String): Verdict {
        val url = raw.trim()
        if (url.isEmpty()) return Verdict.REJECTED_MALFORMED
        if (url.length > MAX_URL_LENGTH) return Verdict.REJECTED_TOO_LONG

        val schemeEnd = url.indexOf(':')
        if (schemeEnd <= 0) return Verdict.REJECTED_MALFORMED
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) {
            return Verdict.REJECTED_MALFORMED
        }
        if (scheme in EXPLICITLY_DANGEROUS) return Verdict.REJECTED_SCHEME
        if (scheme !in ALLOWED) return Verdict.REJECTED_SCHEME

        // Require the authority form; "https:evil" is not a link a browser should be handed.
        if (!url.regionMatches(schemeEnd, "://", 0, 3, ignoreCase = false)) {
            return Verdict.REJECTED_MALFORMED
        }
        val afterScheme = url.substring(schemeEnd + 3)
        if (afterScheme.isEmpty()) return Verdict.REJECTED_MALFORMED

        val host = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        if (host.isEmpty()) return Verdict.REJECTED_MALFORMED
        // A control character or whitespace inside the authority is how a URL gets rendered as
        // one host and resolved as another.
        if (host.any { it.isWhitespace() || it.code < 0x20 }) return Verdict.REJECTED_MALFORMED

        return if (scheme == "https") Verdict.ALLOWED else Verdict.ALLOWED_INSECURE
    }

    fun isAcceptable(raw: String): Boolean =
        check(raw).let { it == Verdict.ALLOWED || it == Verdict.ALLOWED_INSECURE }

    /**
     * The host, for showing next to a link the user is being asked to trust.
     *
     * Shown instead of the full URL, because a long path is exactly where a misleading link
     * hides what it actually points at.
     */
    fun hostOf(raw: String): String? {
        if (!isAcceptable(raw)) return null
        val afterScheme = raw.trim().substringAfter("://", "")
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        // Strip any userinfo: "https://example.com@evil.test" points at evil.test.
        val host = authority.substringAfterLast('@')
        return host.substringBefore(':').ifEmpty { null }
    }
}

/**
 * Filename handling for received files.
 *
 * Nothing a peer sends is ever used to build a local path. The sanitized name exists purely to
 * show the user what they are looking at; the file itself is stored under a name this device
 * generates.
 */
object FilenameSanitizer {

    const val MAX_LENGTH = 120
    private const val FALLBACK = "received-file"

    fun sanitize(raw: String): String = clean(raw).take(MAX_LENGTH)

    /**
     * Sanitises without any length cap.
     *
     * Split out because [sanitize] truncates by characters and drops the extension when it does,
     * so a byte-limited caller must start from the untruncated name or it can never preserve one.
     */
    private fun clean(raw: String): String {
        // Take the last segment first, so "../../etc/passwd" reduces to "passwd" before anything
        // else looks at it.
        val lastSegment = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (c in lastSegment) {
                when {
                    c.code < 0x20 || c.code == 0x7F -> Unit
                    c in FORBIDDEN -> append('_')
                    else -> append(c)
                }
            }
        }.trim().trim('.')

        if (cleaned.isEmpty()) return FALLBACK
        if (cleaned in RESERVED || cleaned.substringBefore('.').uppercase() in RESERVED) {
            return "$FALLBACK-$cleaned"
        }
        return cleaned
    }

    /**
     * Sanitises, then truncates to a **byte** budget rather than a character count.
     *
     * [sanitize] caps at [MAX_LENGTH] characters, which is not the same thing: the wire field and
     * most filesystems bound bytes, and one emoji or CJK character is three to four bytes, so a
     * 120-character name can be 480 bytes and overflow a 255-byte field.
     *
     * Two details that are easy to get wrong and are covered by tests:
     *  - truncation walks **code points**, not `Char`s. Dropping UTF-16 code units splits
     *    surrogate pairs and produces a string that cannot be re-encoded.
     *  - the extension is preserved where it fits, because it is the only hint about what the
     *    file is once the stem has been cut.
     */
    fun sanitizeToByteLimit(raw: String, maxBytes: Int): String {
        val cleaned = clean(raw)
        if (cleaned.utf8Size() <= maxBytes) return cleaned.take(MAX_LENGTH).ifEmpty { FALLBACK }

        val extension = cleaned.substringAfterLast('.', "")
        val suffix = if (extension.isNotEmpty() && extension.length <= 16) ".$extension" else ""
        val stem = if (suffix.isEmpty()) cleaned else cleaned.dropLast(suffix.length)

        // Try to keep the extension. If even that does not fit, fall through to a bare stem.
        val withSuffix = truncateToBytes(stem, maxBytes - suffix.utf8Size()) + suffix
        if (withSuffix.utf8Size() <= maxBytes && withSuffix != suffix) return withSuffix

        return truncateToBytes(cleaned, maxBytes).ifEmpty { FALLBACK.take(maxBytes) }
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    /** Keeps whole code points only, so the result always re-encodes cleanly. */
    private fun truncateToBytes(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (value.utf8Size() <= maxBytes) return value
        val kept = StringBuilder()
        var used = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val piece = value.substring(index, index + charCount)
            val width = piece.utf8Size()
            if (used + width > maxBytes) break
            kept.append(piece)
            used += width
            index += charCount
        }
        return kept.toString()
    }

    /**
     * Characters replaced with an underscore. Control characters are not listed: they are
     * dropped entirely above. Spaces are deliberately kept, because a filename with spaces in
     * it is perfectly ordinary and mangling it would only confuse the person reading it.
     */
    private val FORBIDDEN = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** Windows device names; harmless on Android but this name may be shared onward. */
    private val RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
}
