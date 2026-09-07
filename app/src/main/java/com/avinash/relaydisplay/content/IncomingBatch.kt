package com.avinash.relaydisplay.content

import java.util.UUID

/**
 * A set of files the peer has offered, waiting on this user's decision.
 *
 * Exists because being paired is not the same as consenting to receive. Pairing proves which phone
 * is on the other end; it says nothing about whether its user meant to push twenty files at this
 * one right now. So a generic file transfer stops here until somebody answers, and
 * `ContentRouter.onOffer` refuses any file offer that does not have an accepted batch behind it.
 *
 * Every field has already been through [FileTransferPolicy.validateFileMetadata], so the names are
 * sanitised and the sizes are within limits before any of this reaches a screen.
 */
data class IncomingBatch(
    val batchId: UUID,
    /** The sender's own name for itself, sanitised. A label, never used as a path or an identity. */
    val senderName: String,
    val files: List<IncomingFile>,
) {
    val count: Int get() = files.size

    /**
     * Total size of the batch.
     *
     * Always a real number: sizes are measured on the sending side before the offer goes out, so
     * there is no unknown-size case to represent here. Summed over at most
     * [FileTransferPolicy.MAX_FILES_PER_BATCH] entries, each already bounded by
     * [FileTransferPolicy.MAX_FILE_BYTES], so it cannot overflow.
     */
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** True when anything in the batch would need a warning before opening. */
    val containsExecutable: Boolean get() = files.any { it.needsOpenWarning }
}

/** One entry in an [IncomingBatch]. */
data class IncomingFile(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
    val needsOpenWarning: Boolean
        get() = FileTransferPolicy.requiresOpenWarning(mimeType, displayName)
}
