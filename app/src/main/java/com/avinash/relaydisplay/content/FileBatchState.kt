package com.avinash.relaydisplay.content

import java.util.UUID

/**
 * The state of one multi-file send, as an explicit machine rather than a set of booleans.
 *
 * Booleans were the alternative and they do not survive this problem: "sending && !cancelled &&
 * !failed && acked" has sixteen combinations of which four are meaningful, and the impossible
 * ones are exactly where a stuck progress bar or a transfer that reports success after a
 * cancellation comes from. A sealed phase per file plus a batch rollup makes the illegal states
 * unrepresentable.
 *
 * Deliberately pure: no Android types, no IO, no coroutines. The receiver's stream lifecycle and
 * the sender's stream lifecycle each own their own resources; this only tracks what happened.
 */
data class FileBatchState(
    val batchId: UUID,
    val files: List<FileProgress>,
    val cancelled: Boolean = false,
) {
    /** Index of the file currently moving, or null when none is. */
    val activeIndex: Int? get() = files.indexOfFirst { it.phase is FilePhase.Sending }.takeIf { it >= 0 }

    val total: Int get() = files.size
    val completedCount: Int get() = files.count { it.phase is FilePhase.Complete }
    val failedCount: Int get() = files.count { it.phase is FilePhase.Failed }
    val rejectedCount: Int get() = files.count { it.phase is FilePhase.Rejected }

    /** True once no file can still make progress. */
    val settled: Boolean get() = files.all { it.phase.terminal }

    /** Total bytes moved so far, across every file. */
    val bytesTransferred: Long get() = files.sumOf { it.bytesTransferred }

    /**
     * Total bytes expected, or null when any file's size is unknown.
     *
     * Null is the signal for an indeterminate progress bar. Treating unknown as zero would show a
     * percentage that is confidently wrong, which is worse than showing none.
     */
    val totalBytes: Long?
        get() = if (files.any { it.declaredSize == FileTransferPolicy.SIZE_UNKNOWN }) {
            null
        } else {
            files.sumOf { it.declaredSize }
        }

    /** Overall percentage, or null when it cannot honestly be computed. */
    val percent: Int?
        get() = totalBytes?.let { total ->
            if (total <= 0L) 100 else ((bytesTransferred * 100) / total).toInt().coerceIn(0, 100)
        }

    /** Files a retry should attempt: the ones that failed retryably, and nothing else. */
    val retryableIndices: List<Int>
        get() = files.mapIndexedNotNull { index, file ->
            index.takeIf { (file.phase as? FilePhase.Failed)?.retryable == true }
        }

    val canRetry: Boolean get() = settled && retryableIndices.isNotEmpty()

    fun withPhase(index: Int, phase: FilePhase): FileBatchState =
        copy(files = files.mapIndexed { i, f -> if (i == index) f.copy(phase = phase) else f })

    fun withProgress(index: Int, bytes: Long): FileBatchState =
        copy(files = files.mapIndexed { i, f -> if (i == index) f.copy(bytesTransferred = bytes) else f })

    /**
     * Cancels everything not already finished.
     *
     * Files that already completed stay completed. A cancellation part-way through a batch does
     * not retroactively un-send what the peer has already verified and written.
     */
    fun cancelAll(): FileBatchState = copy(
        cancelled = true,
        files = files.map { if (it.phase.terminal) it else it.copy(phase = FilePhase.Cancelled) },
    )

    companion object {
        fun of(batchId: UUID, files: List<FileTransferPolicy.MetadataVerdict.Valid>): FileBatchState =
            FileBatchState(
                batchId = batchId,
                files = files.map {
                    FileProgress(
                        name = it.safeName,
                        mimeType = it.mimeType,
                        declaredSize = it.sizeBytes,
                    )
                },
            )
    }
}

/** One file inside a batch. */
data class FileProgress(
    val name: String,
    val mimeType: String,
    val declaredSize: Long,
    val bytesTransferred: Long = 0,
    val phase: FilePhase = FilePhase.Waiting,
) {
    /** Percentage for this file, or null when the size is unknown. */
    val percent: Int?
        get() = if (declaredSize == FileTransferPolicy.SIZE_UNKNOWN) {
            null
        } else if (declaredSize <= 0L) {
            100
        } else {
            ((bytesTransferred * 100) / declaredSize).toInt().coerceIn(0, 100)
        }
}

/**
 * What is happening to one file.
 *
 * `terminal` is on the phase rather than computed by the caller, so a new phase cannot be added
 * without deciding whether it ends the file's life.
 */
sealed interface FilePhase {
    val terminal: Boolean

    /** Offered, waiting for the peer to accept or reject the batch. */
    data object Waiting : FilePhase {
        override val terminal = false
    }

    /** The peer accepted; this file has not started yet. */
    data object Accepted : FilePhase {
        override val terminal = false
    }

    data object Sending : FilePhase {
        override val terminal = false
    }

    /** All bytes sent; waiting on the receiver's digest check. */
    data object Verifying : FilePhase {
        override val terminal = false
    }

    data object Complete : FilePhase {
        override val terminal = true
    }

    /** The peer declined this file, or the whole batch. */
    data object Rejected : FilePhase {
        override val terminal = true
    }

    data object Cancelled : FilePhase {
        override val terminal = true
    }

    /**
     * [retryable] separates "the link dropped" from "this file is not sendable".
     *
     * A disconnect is worth a retry button; a file the provider will no longer open, or one the
     * peer refused on policy grounds, is not, and offering a button that always fails is worse
     * than offering none.
     */
    data class Failed(val reason: String, val retryable: Boolean) : FilePhase {
        override val terminal = true
    }
}
