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

    /**
     * Largest batch the sender will offer and the receiver will accept.
     *
     * Shares [ContentLimits.MAX_FILES_PER_BATCH] with the codec, which needs the same number to
     * bound a batch manifest before parsing it.
     */
    const val MAX_FILES_PER_BATCH = ContentLimits.MAX_FILES_PER_BATCH

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
     * Most bytes the receiver retains in completed-but-unclaimed files.
     *
     * Without this, repeated accepted batches fill the cache partition even though each one was
     * individually within limits.
     *
     * Must be at least [MAX_BATCH_BYTES], or accepting a batch that passed validation would evict
     * part of that same batch before the user could open any of it. Two batches' worth is the
     * chosen headroom. [ContentCache] takes its byte budget from here so the two cannot drift;
     * [retentionInvariantsHold] pins the relationship and a unit test asserts it.
     */
    const val MAX_PENDING_BYTES = 2L * MAX_BATCH_BYTES

    /**
     * Most completed-but-unclaimed files retained.
     *
     * Must be at least [MAX_FILES_PER_BATCH] for the same reason. This limit previously sat at 12
     * inside [ContentCache] while a batch could legally carry 20 files, so accepting a full batch
     * silently deleted the first 8 files of it.
     */
    const val MAX_PENDING_FILES = 3 * MAX_FILES_PER_BATCH

    /**
     * How long a received or partial file survives before the cache sweeps it.
     *
     * Enforced by [ContentCache.sweepExpired], called on the same startup pass that clears
     * partials. Received files live in the app cache directory, which the system may reclaim at
     * any time anyway; this bounds how long they sit there when it does not.
     */
    const val PENDING_EXPIRY_MS = 24L * 60L * 60L * 1000L

    /**
     * How long an unanswered consent prompt survives on the receiving phone.
     *
     * Longer than the sender's own decision timeout on purpose, so in the normal case the sender
     * gives up first and says so. This is the backstop for when that message never arrives -- a
     * lost cancel, a force-stopped controller, a dropped link -- because a prompt that can never
     * be answered would block every later batch as BUSY for the life of the session.
     */
    const val CONSENT_EXPIRY_MS = 150_000L

    /**
     * Whether the retention budget can actually hold one whole valid batch.
     *
     * A guard against someone tuning one number without the other. Checked by a unit test rather
     * than at runtime, because it is a property of the constants above and cannot vary.
     */
    fun retentionInvariantsHold(): Boolean =
        MAX_PENDING_BYTES >= MAX_BATCH_BYTES && MAX_PENDING_FILES >= MAX_FILES_PER_BATCH

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

    /**
     * Size unknown: a content provider is not obliged to report one.
     *
     * **Local only. This value never travels on the wire.** It describes the window between
     * picking a file and preparing it: the picker shows "Unknown size" in the review list, and
     * [ContentSource.prepare] then measures the stream and replaces it with the real length before
     * anything is offered. A receiver therefore always sees a real size, and any negative size
     * arriving from a peer is a malformed frame.
     *
     * That split is what resolved a genuine contradiction. The picker used to refuse a file whose
     * size was unreported (`size <= 0`, which also refused legitimately empty files) while the
     * protocol documentation claimed `-1` was supported and the receiver rejected every negative
     * value. All three can now be true at once because "unknown" stops existing at the boundary.
     */
    const val SIZE_UNKNOWN = -1L

    /**
     * Validates one file's metadata before any byte is accepted.
     *
     * [declaredSize] may be [SIZE_UNKNOWN] when this is called on the *sending* side for a freshly
     * picked file: a provider that cannot stat its own stream is normal, and refusing those would
     * rule out a lot of ordinary content URIs. What is not allowed is a *negative* size that is not
     * the sentinel, or one above the limit.
     *
     * On the *receiving* side the sentinel can never appear, because the decoder rejects any
     * negative size before a frame becomes a message. Sizes there are always measured values.
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
        // Accumulated with an early exit rather than summed, so the running total can never
        // overflow: sumOf over attacker-influenced longs wraps silently and a wrapped negative
        // total would pass the comparison below. Every element is already <= MAX_FILE_BYTES, so
        // this only matters if that ever stops being true, which is exactly when it would bite.
        var knownTotal = 0L
        for (file in files) {
            if (file.sizeBytes == SIZE_UNKNOWN) continue
            if (file.sizeBytes > MAX_BATCH_BYTES - knownTotal) {
                return BatchVerdict.Invalid(
                    "batch is larger than the ${MAX_BATCH_BYTES / (1024 * 1024)} MiB limit",
                )
            }
            knownTotal += file.sizeBytes
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
