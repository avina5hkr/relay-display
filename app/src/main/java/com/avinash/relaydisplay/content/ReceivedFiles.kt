package com.avinash.relaydisplay.content

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * A verified file sitting in the cache, ready to be opened, saved or shared.
 *
 * [file] is always inside the cache's `ready/` directory, which is the only directory the
 * FileProvider will expose. The name here is the *display* name; the file on disk is named from
 * the transfer id, so nothing the peer sent influences a path.
 */
data class ReceivedFile(
    val transferId: UUID,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val file: File,
    val receivedAtMs: Long,
) {
    /** Whether opening this needs a warning first. See [FileTransferPolicy.requiresOpenWarning]. */
    val needsOpenWarning: Boolean get() = FileTransferPolicy.requiresOpenWarning(mimeType, displayName)
}

/**
 * The sidecar that remembers a received file's display name, type and size.
 *
 * Needed because the file on disk is deliberately named `<transferId>.<ext>`: that is what stops a
 * hostile display name from steering a write, but it also means the real name is not recoverable
 * from the filesystem. Without a sidecar the received-files list would be empty after a restart
 * while the files themselves sat there unreachable.
 *
 * The format is three lines -- name, MIME type, size -- and is written next to the file as
 * `<transferId>.meta`. Deliberately not JSON: there is no parser to get wrong, and a sanitised
 * name cannot contain a newline because [FilenameSanitizer] drops every control character, so the
 * line structure cannot be broken by the values inside it. Every field is still re-validated on
 * read, because a file in a cache directory is not a trusted store.
 */
internal object ReceivedFileMetadata {

    const val SUFFIX = ".meta"

    /** Refuses to write a name that could break the line format. Belt and braces over clean(). */
    fun write(target: File, displayName: String, mimeType: String, sizeBytes: Long) {
        val safeName = FilenameSanitizer.sanitizeToByteLimit(displayName, MAX_NAME_BYTES)
        val safeMime = mimeType.filter { it.code in 0x20..0x7E }.take(MAX_MIME_CHARS)
        target.writeText("$safeName\n$safeMime\n$sizeBytes\n")
    }

    /** Returns null for anything missing, malformed, oversized or inconsistent. */
    fun read(source: File, payload: File): ParsedMetadata? {
        if (!source.isFile || source.length() > MAX_FILE_BYTES) return null
        val lines = try {
            source.readLines()
        } catch (e: IOException) {
            return null
        }
        if (lines.size < 3) return null

        val name = FilenameSanitizer.sanitizeToByteLimit(lines[0], MAX_NAME_BYTES)
        val mime = lines[1].filter { it.code in 0x20..0x7E }.take(MAX_MIME_CHARS)
            .ifEmpty { FileTransferPolicy.FALLBACK_MIME }
        // The recorded size is a claim; the file's own length is the fact. Trusting the sidecar
        // would let a stale or edited one misreport what is about to be shared.
        val actual = payload.length()
        val recorded = lines[2].trim().toLongOrNull() ?: return null
        if (recorded != actual) return null

        return ParsedMetadata(name, mime, actual)
    }

    data class ParsedMetadata(val displayName: String, val mimeType: String, val sizeBytes: Long)

    private const val MAX_NAME_BYTES = 255
    private const val MAX_MIME_CHARS = 128

    /** A sidecar is three short lines. Anything larger is not one. */
    private const val MAX_FILE_BYTES = 4L * 1024L
}
