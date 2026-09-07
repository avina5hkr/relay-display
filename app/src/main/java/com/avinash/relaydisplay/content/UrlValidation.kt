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

    /**
     * Longest run of characters after the final dot still treated as an extension.
     *
     * Anything longer is far more likely to be a dotted name than a type hint, and reserving 40
     * characters of a 120-character budget for it would eat the part a person actually reads.
     */
    private const val MAX_EXTENSION_LENGTH = 16

    private const val FALLBACK = "received-file"

    /**
     * Sanitises and caps at [MAX_LENGTH] characters, keeping the extension.
     *
     * Names arriving from a peer or a `ContentResolver` are untrusted, so the result is always
     * a single path component with no control characters.
     */
    fun sanitize(raw: String): String = fit(clean(raw), MAX_LENGTH, Int.MAX_VALUE)

    /**
     * Sanitises, then caps by **bytes** as well as characters.
     *
     * The two limits are not interchangeable, which is the whole reason this exists: the wire
     * field and most filesystems bound bytes, and one emoji or CJK character is three to four
     * bytes, so a 120-character name can be 480 bytes and overflow a 255-byte field. Equally, a
     * 180-character ASCII name is only 180 bytes and passes the byte check while still being
     * too long for the character cap.
     *
     * Both bounds therefore apply to every result, and neither is allowed to cost the extension.
     * An earlier version returned early when the byte check passed and then applied `take(120)`,
     * which silently truncated exactly that 180-character ASCII case and threw the extension
     * away; the regression test in ReceivedFilenameTest pins the fix.
     */
    fun sanitizeToByteLimit(raw: String, maxBytes: Int): String =
        fit(clean(raw), MAX_LENGTH, maxBytes)

    /**
     * Sanitises without any length cap.
     *
     * Split out because both public entry points must start from the untruncated name: truncating
     * first and looking for an extension afterwards can only ever find one that survived by luck.
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
     * Brings an already-cleaned name inside **both** budgets, preserving the extension.
     *
     * The single place either limit is applied, so they cannot drift apart. Order matters: the
     * extension is reserved out of both budgets first, the stem gets whatever is left, and the
     * extension is dropped only when it genuinely cannot fit.
     */
    private fun fit(cleaned: String, maxChars: Int, maxBytes: Int): String {
        if (cleaned.length <= maxChars && cleaned.utf8Size() <= maxBytes) return cleaned

        val extension = cleaned.substringAfterLast('.', "")
        val suffix = if (extension.isNotEmpty() && extension.length <= MAX_EXTENSION_LENGTH) {
            ".$extension"
        } else {
            ""
        }
        // dropLast on an empty suffix is a no-op, so this covers the no-extension case too.
        val stem = cleaned.dropLast(suffix.length)

        // Keep the extension if a non-empty stem can still be placed in front of it. A name that
        // is nothing but an extension is not worth returning.
        if (suffix.isNotEmpty()) {
            val kept = truncate(stem, maxChars - suffix.length, maxBytes - suffix.utf8Size())
            if (kept.isNotEmpty()) return kept + suffix
        }

        // The extension does not fit, or there was none. Salvage as much of the name as the
        // budgets allow.
        val bare = truncate(cleaned, maxChars, maxBytes)
        return bare.ifEmpty { truncate(FALLBACK, maxChars, maxBytes) }
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    /**
     * Truncates to whole code points within a character budget and a byte budget at once.
     *
     * Walking code points rather than `Char`s is what keeps the result re-encodable: dropping a
     * single UTF-16 code unit splits a surrogate pair and leaves an unpaired surrogate, which no
     * longer round-trips through UTF-8. `charCount` is charged against maxChars for the same
     * reason -- an astral character costs two `Char`s of any downstream `length` check.
     */
    private fun truncate(value: String, maxChars: Int, maxBytes: Int): String {
        if (maxChars <= 0 || maxBytes <= 0) return ""
        if (value.length <= maxChars && value.utf8Size() <= maxBytes) return value
        val kept = StringBuilder()
        var usedBytes = 0
        var usedChars = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val piece = value.substring(index, index + charCount)
            val width = piece.utf8Size()
            if (usedBytes + width > maxBytes || usedChars + charCount > maxChars) break
            kept.append(piece)
            usedBytes += width
            usedChars += charCount
            index += charCount
        }
        // A trailing space or dot would be re-trimmed by any later clean() and confuses shells.
        return kept.toString().trimEnd().trimEnd('.')
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
