package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.protocol.ContentLimits

/**
 * Every limit and rule that governs a generic file transfer, in one place and with no Android
 * dependency so it can be exercised in milliseconds on the JVM.
 *
 * The rules exist because the peer is authenticated, not trusted. Pairing proves the other phone
 * is the one you paired with; it proves nothing about whether the app on it is behaving, and a
 * compromised or buggy peer can send whatever bytes it likes. Every value below is therefore
 * checked against a declared bound before anything is allocated, opened or written.
 */
object FileTransferPolicy {

    /**
     * Wire version for generic file transfer.
     *
     * Announced as the `file-v1` capability. A peer that does not announce it gets an explicit
     * refusal rather than a stream of messages it cannot parse: see [SendRefusal.PeerTooOld].
     * Bumping this means bumping the capability string, so an old peer keeps failing cleanly
     * instead of half-understanding a newer format.
     */
    const val WIRE_VERSION = 1

    /** Largest batch the sender will offer and the receiver will accept. */
    const val MAX_FILES_PER_BATCH = 20

    /**
     * Largest single file.
     *
     * Shares [ContentLimits.MAX_FILE_BYTES] with image and PDF transfer rather than inventing a
     * second limit: one number to reason about, and the receiver's free-space check already
     * assumes it.
     */
    const val MAX_FILE_BYTES = ContentLimits.MAX_FILE_BYTES

    /** Largest total across one batch. Bounds what a single accept can cost the receiver. */
    const val MAX_BATCH_BYTES = 200L * 1024L * 1024L

    /**
     * Most bytes the receiver will hold in completed-but-unclaimed files.
     *
     * Without this, repeated accepted batches fill the cache partition even though each one was
     * individually within limits.
     */
    const val MAX_PENDING_BYTES = 400L * 1024L * 1024L

    /** Most completed-but-unclaimed files retained. */
    const val MAX_PENDING_FILES = 60

    /** How long a partial or unclaimed file survives before the cache sweeps it. */
    const val PENDING_EXPIRY_MS = 24L * 60L * 60L * 1000L

    /** Silence on an in-flight transfer that means the peer is gone rather than slow. */
    const val INACTIVITY_TIMEOUT_MS = 30_000L

    /** MIME reported when the document provider will not say. */
    const val FALLBACK_MIME = "application/octet-stream"

    /**
     * Types that must never be opened without a warning.
     *
     * The app never installs or executes anything: "open" always goes through an Android chooser
     * and the user picks the handler. These are the types where handing bytes to that chooser is
     * still worth a deliberate confirmation, because the obvious handler is an installer.
     */
    private val EXECUTABLE_MIMES = setOf(
        "application/vnd.android.package-archive",
        "application/x-executable",
        "application/x-sharedlib",
        "application/x-msdownload",
        "application/x-msdos-program",
        "application/x-dosexec",
        "application/x-elf",
        "application/x-mach-binary",
    )

    private val EXECUTABLE_EXTENSIONS = setOf(
        "apk", "apex", "dex", "exe", "msi", "bat", "cmd", "com", "scr", "dll", "so", "sh", "bin",
    )

    /**
     * Whether opening this file warrants a warning first.
     *
     * Checks the declared MIME *and* the extension, because either can be wrong on its own: a
     * provider may report `application/octet-stream` for an APK, and a sender may declare
     * `text/plain` for something named `payload.apk`. Being wrong in the cautious direction costs
     * one extra confirmation.
     */
    fun requiresOpenWarning(mimeType: String?, fileName: String): Boolean {
        val mime = mimeType?.trim()?.lowercase()
        if (mime != null && mime in EXECUTABLE_MIMES) return true
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && extension in EXECUTABLE_EXTENSIONS
    }

    /** Size unknown: a content provider is not obliged to report one. */
    const val SIZE_UNKNOWN = -1L

    /**
     * Validates one file's metadata before any byte is accepted.
     *
     * [declaredSize] may be [SIZE_UNKNOWN]; a provider that cannot stat its own stream is normal,
     * and refusing those would rule out a lot of ordinary content URIs. What is not allowed is a
     * *negative* size that is not the sentinel, or one above the limit.
     */
    fun validateFileMetadata(
        rawName: String,
        mimeType: String?,
        declaredSize: Long,
    ): MetadataVerdict {
        if (declaredSize != SIZE_UNKNOWN && declaredSize < 0) {
            return MetadataVerdict.Invalid("size is negative")
        }
        if (declaredSize > MAX_FILE_BYTES) {
            return MetadataVerdict.Invalid("file is larger than the ${MAX_FILE_BYTES / (1024 * 1024)} MiB limit")
        }
        val mime = (mimeType?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_MIME)
        if (mime.toByteArray(Charsets.UTF_8).size > ContentLimits.MAX_MIME_BYTES) {
            return MetadataVerdict.Invalid("MIME type is too long")
        }
        if (mime.any { it.code < 0x20 || it.code == 0x7F }) {
            return MetadataVerdict.Invalid("MIME type contains control characters")
        }
        // The sanitiser never fails; it always yields a usable name. Its output is what travels
        // and what is written, never the raw name.
        val safeName = FilenameSanitizer.sanitizeToByteLimit(rawName, ContentLimits.MAX_FILENAME_BYTES)
        return MetadataVerdict.Valid(safeName, mime, declaredSize)
    }

    /** Validates the shape of a whole batch. */
    fun validateBatch(files: List<MetadataVerdict.Valid>): BatchVerdict {
        if (files.isEmpty()) return BatchVerdict.Invalid("no files selected")
        if (files.size > MAX_FILES_PER_BATCH) {
            return BatchVerdict.Invalid("more than $MAX_FILES_PER_BATCH files in one batch")
        }
        // Only known sizes contribute. A batch of unknown-size files cannot be pre-checked
        // against the total, and is bounded per-file during streaming instead.
        val knownTotal = files.filter { it.sizeBytes != SIZE_UNKNOWN }.sumOf { it.sizeBytes }
        if (knownTotal > MAX_BATCH_BYTES) {
            return BatchVerdict.Invalid("batch is larger than the ${MAX_BATCH_BYTES / (1024 * 1024)} MiB limit")
        }
        return BatchVerdict.Valid(files, knownTotal)
    }

    /** Generic files accept any MIME type; images and PDFs keep their existing narrow rules. */
    fun acceptsAnyMime(kind: ContentKind): Boolean = kind == ContentKind.FILE

    sealed interface MetadataVerdict {
        data class Valid(val safeName: String, val mimeType: String, val sizeBytes: Long) : MetadataVerdict
        data class Invalid(val reason: String) : MetadataVerdict
    }

    sealed interface BatchVerdict {
        data class Valid(val files: List<MetadataVerdict.Valid>, val knownTotalBytes: Long) : BatchVerdict
        data class Invalid(val reason: String) : BatchVerdict
    }

    /** Why a send cannot start. Separated from validation so the UI can say something useful. */
    sealed interface SendRefusal {
        /** The peer did not announce `file-v1`. */
        data object PeerTooOld : SendRefusal
        data object NotConnected : SendRefusal
        data class Rejected(val reason: String) : SendRefusal
    }
}
