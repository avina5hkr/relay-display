package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.protocol.ContentOffer
import com.avinash.relaydisplay.protocol.FileManifestEntry
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import java.util.UUID

/**
 * The manifest a user actually approved, and what has happened to each file in it since.
 *
 * This replaces four loose fields on the router -- a `batchAccepted` boolean, an `acceptedBatchId`
 * that was assigned and never read, and two counters. That shape could not answer the only
 * question that matters when a file offer arrives: *is this one of the files I agreed to?* Once
 * the boolean was true any generic file was accepted, so a controller could display one manifest
 * and send different files, and if it never sent the final `SHOW_FILE` the counters never advanced
 * and consent never expired at all.
 *
 * Consent is therefore held as the manifest itself, keyed by transfer id, with an explicit state
 * per entry. An offer is matched against its entry before anything is opened or written, and every
 * entry can be consumed exactly once.
 *
 * Deliberately pure: no Android, no IO, no coroutines, so the matching rules are unit-testable in
 * milliseconds. Not thread-safe by itself; the router confines it to one dispatcher.
 */
class AcceptedBatch(
    val batchId: UUID,
    entries: List<FileManifestEntry>,
    /** When consent was given, for the inactivity deadline. */
    val acceptedAtMs: Long,
) {
    /** Manifest order is meaningful, so this is insertion-ordered and indices are positions. */
    private val entries: List<FileManifestEntry> = entries.toList()
    private val indexOf: Map<UUID, Int> = entries.withIndex().associate { (i, e) -> e.transferId to i }
    private val states: MutableList<EntryState> = MutableList(entries.size) { EntryState.PENDING }

    val size: Int get() = entries.size

    /** True once no entry can still make progress, so the batch is finished either way. */
    val settled: Boolean get() = states.none { it == EntryState.PENDING || it == EntryState.RECEIVING }

    val receivedCount: Int get() = states.count { it == EntryState.RECEIVED }
    val failedCount: Int get() = states.count { it == EntryState.FAILED }

    /** The transfer currently being received, if any. At most one: the protocol allows one. */
    val receivingTransferId: UUID?
        get() = states.indexOfFirst { it == EntryState.RECEIVING }
            .takeIf { it >= 0 }
            ?.let { entries[it].transferId }

    fun stateOf(transferId: UUID): EntryState? = indexOf[transferId]?.let { states[it] }

    /**
     * Whether an incoming offer is one of the approved files.
     *
     * Checks are ordered cheapest-first and every one of them is a refusal, not a warning. The
     * result is returned rather than thrown so the caller can reject with a specific code before
     * a partial file exists.
     */
    fun match(offer: ContentOffer): OfferVerdict {
        if (offer.batchId == null) {
            return OfferVerdict.Refused(ProtocolErrorCode.PERMISSION_DENIED, "offer names no batch")
        }
        if (offer.batchId != batchId) {
            return OfferVerdict.Refused(ProtocolErrorCode.PERMISSION_DENIED, "offer names another batch")
        }
        val index = indexOf[offer.transferId]
            ?: return OfferVerdict.Refused(ProtocolErrorCode.PERMISSION_DENIED, "transfer not in the accepted manifest")

        when (states[index]) {
            EntryState.PENDING -> Unit
            // A repeat of a file already written, or of one in flight. Either is a replay: consent
            // was for one copy of each entry.
            EntryState.RECEIVING ->
                return OfferVerdict.Refused(ProtocolErrorCode.BUSY, "that transfer is already running")
            EntryState.RECEIVED, EntryState.FAILED, EntryState.CANCELLED ->
                return OfferVerdict.Refused(ProtocolErrorCode.PERMISSION_DENIED, "that transfer already finished")
        }

        val entry = entries[index]
        // The index is not needed to find the entry -- the transfer id did that -- but it pins the
        // order the sender promised, which is the order progress is counted against.
        if (offer.manifestIndex != null && offer.manifestIndex != index) {
            return OfferVerdict.Refused(ProtocolErrorCode.MALFORMED_FRAME, "manifest index disagrees")
        }
        if (offer.sizeBytes != entry.sizeBytes) {
            return OfferVerdict.Refused(ProtocolErrorCode.MALFORMED_FRAME, "size differs from the accepted entry")
        }
        if (!offer.sha256.contentEquals(entry.sha256)) {
            return OfferVerdict.Refused(ProtocolErrorCode.MALFORMED_FRAME, "digest differs from the accepted entry")
        }
        if (normalizeMime(offer.mimeType) != normalizeMime(entry.mimeType)) {
            return OfferVerdict.Refused(ProtocolErrorCode.UNSUPPORTED_FORMAT, "type differs from the accepted entry")
        }
        // One normalization policy, applied to both sides of the comparison rather than trusting
        // either to arrive already normalized. Comparing raw wire strings would let a name that
        // sanitises to the approved one -- differing only in a stripped control character, say --
        // read as a different file, and comparing unsanitised names would compare values the user
        // was never shown.
        if (normalizeName(offer.displayName) != normalizeName(entry.displayName)) {
            return OfferVerdict.Refused(ProtocolErrorCode.MALFORMED_FRAME, "name differs from the accepted entry")
        }
        return OfferVerdict.Accepted(entry, index)
    }

    /** Marks an entry in flight. Only legal from [EntryState.PENDING]. */
    fun markReceiving(transferId: UUID): Boolean = transition(transferId, EntryState.RECEIVING) {
        it == EntryState.PENDING
    }

    fun markReceived(transferId: UUID): Boolean = transition(transferId, EntryState.RECEIVED) {
        it == EntryState.RECEIVING
    }

    fun markFailed(transferId: UUID): Boolean = transition(transferId, EntryState.FAILED) {
        it == EntryState.PENDING || it == EntryState.RECEIVING
    }

    /**
     * Cancels everything not already finished.
     *
     * Idempotent, and does not un-receive a file that already passed its digest check: those bytes
     * are written and verified, and pretending otherwise would delete a file the user was told
     * they had.
     */
    fun cancelUnfinished() {
        for (i in states.indices) {
            if (states[i] == EntryState.PENDING || states[i] == EntryState.RECEIVING) {
                states[i] = EntryState.CANCELLED
            }
        }
    }

    private inline fun transition(
        transferId: UUID,
        to: EntryState,
        allowed: (EntryState) -> Boolean,
    ): Boolean {
        val index = indexOf[transferId] ?: return false
        if (!allowed(states[index])) return false
        states[index] = to
        return true
    }

    enum class EntryState { PENDING, RECEIVING, RECEIVED, FAILED, CANCELLED }

    sealed interface OfferVerdict {
        /** The offer is the approved file at [index]. */
        data class Accepted(val entry: FileManifestEntry, val index: Int) : OfferVerdict

        /**
         * [reason] is for the local diagnostic log only.
         *
         * Deliberately free of peer-supplied content: no filename, no MIME type, no digest, no
         * size. A rejection reason ends up in a log the user may share, and "name differs from the
         * accepted entry" is as useful for debugging as the name itself without putting the name
         * there.
         */
        data class Refused(val code: ProtocolErrorCode, val reason: String) : OfferVerdict
    }

    companion object {
        /**
         * The single filename normalization used on both sides of every comparison.
         *
         * The sanitiser is the app's existing one, so the compared form is exactly the form that
         * would be written to disk and shown to the user.
         */
        fun normalizeName(raw: String): String =
            FilenameSanitizer.sanitizeToByteLimit(raw, com.avinash.relaydisplay.protocol.ContentLimits.MAX_FILENAME_BYTES)

        /** Types are compared case- and whitespace-insensitively, with a blank meaning the fallback. */
        fun normalizeMime(raw: String): String =
            raw.trim().lowercase().ifEmpty { FileTransferPolicy.FALLBACK_MIME }
    }
}
