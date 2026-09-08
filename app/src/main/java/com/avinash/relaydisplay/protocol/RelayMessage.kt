package com.avinash.relaydisplay.protocol

import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.domain.model.PresentCommandType
import com.avinash.relaydisplay.domain.model.PresentationPhase
import com.avinash.relaydisplay.domain.model.PresentationRotation
import java.util.UUID

/**
 * The plaintext message that sits inside a frame payload.
 *
 * Record layout: `type:u16 | messageId:16 bytes | TLV body`.
 * Every message carries an id so acknowledgements can reference it and so a command replayed
 * after a reconnect can be recognised as a duplicate instead of being applied twice.
 */
sealed interface RelayMessage {
    val type: MessageType
    val id: UUID
}

// --- handshake -----------------------------------------------------------------------------

data class Hello(
    override val id: UUID,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val deviceId: String,
    val deviceName: String,
    val role: DeviceRole,
    val identityPublicKey: ByteArray,
    val ephemeralPublicKey: ByteArray,
    val nonce: ByteArray,
    val capabilities: Set<String>,
    /**
     * True when this device cannot authenticate the peer from stored trust or a pairing token
     * and therefore needs the user to compare a short authentication string. Both sides OR
     * their flags, so a device that needs verification can never be talked out of it.
     */
    val requestSasConfirmation: Boolean,
) : RelayMessage {
    override val type get() = MessageType.HELLO
    override fun equals(other: Any?) = this === other || (other is Hello && messageEquals(this, other))
    override fun hashCode() = messageHash(this)
}

data class HelloAck(
    override val id: UUID,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val deviceId: String,
    val deviceName: String,
    val role: DeviceRole,
    val identityPublicKey: ByteArray,
    val ephemeralPublicKey: ByteArray,
    val nonce: ByteArray,
    val capabilities: Set<String>,
    /**
     * True when this device cannot authenticate the peer from stored trust or a pairing token
     * and therefore needs the user to compare a short authentication string. Both sides OR
     * their flags, so a device that needs verification can never be talked out of it.
     */
    val requestSasConfirmation: Boolean,
) : RelayMessage {
    override val type get() = MessageType.HELLO_ACK
    override fun equals(other: Any?) = this === other || (other is HelloAck && messageEquals(this, other))
    override fun hashCode() = messageHash(this)
}

/**
 * Proof that the sender holds the private half of the identity key it advertised, bound to the
 * handshake transcript. [pairingTokenProof] is present only during QR pairing.
 */
data class AuthConfirm(
    override val id: UUID,
    val transcriptSignature: ByteArray,
    val pairingTokenProof: ByteArray?,
) : RelayMessage {
    override val type get() = MessageType.AUTH_CONFIRM
    override fun equals(other: Any?) = this === other || (other is AuthConfirm && messageEquals(this, other))
    override fun hashCode() = messageHash(this)
}

/**
 * The responder's final word on the handshake, and simultaneously its own proof of identity:
 * [transcriptSignature] is present exactly when [accepted] is true.
 */
data class AuthResult(
    override val id: UUID,
    val accepted: Boolean,
    val errorCode: ProtocolErrorCode,
    val requiresSasConfirmation: Boolean,
    val transcriptSignature: ByteArray?,
) : RelayMessage {
    override val type get() = MessageType.AUTH_RESULT
    override fun equals(other: Any?) = this === other || (other is AuthResult && messageEquals(this, other))
    override fun hashCode() = messageHash(this)
}

// --- session housekeeping ------------------------------------------------------------------

data class SasConfirm(override val id: UUID, val confirmed: Boolean) : RelayMessage {
    override val type get() = MessageType.SAS_CONFIRM
}

data class Ping(override val id: UUID, val timestampMs: Long) : RelayMessage {
    override val type get() = MessageType.PING
}

data class Pong(override val id: UUID, val echoTimestampMs: Long) : RelayMessage {
    override val type get() = MessageType.PONG
}

data class Ack(override val id: UUID, val refId: UUID, val ok: Boolean, val errorCode: ProtocolErrorCode) : RelayMessage {
    override val type get() = MessageType.ACK
}

data class ErrorMessage(override val id: UUID, val errorCode: ProtocolErrorCode, val detail: String) : RelayMessage {
    override val type get() = MessageType.ERROR
}

data class Bye(override val id: UUID, val reason: String) : RelayMessage {
    override val type get() = MessageType.BYE
}


/**
 * Presentation identity carried alongside content.
 *
 * Optional on the wire so a peer running an older build still parses the message; absent means
 * "this sender does not track presentations", and the receiver assigns its own identity.
 */
data class PresentationEnvelope(val presentationId: UUID, val revision: Long)

/** Content messages that name the presentation they establish. */
sealed interface PresentationScoped {
    val envelope: PresentationEnvelope?
}

// --- simple content ------------------------------------------------------------------------

data class ShowText(
    override val id: UUID,
    val text: String,
    override val envelope: PresentationEnvelope? = null,
) : RelayMessage, PresentationScoped {
    override val type get() = MessageType.SHOW_TEXT
}

data class ShowQr(
    override val id: UUID,
    val payload: String,
    val caption: String?,
    override val envelope: PresentationEnvelope? = null,
) : RelayMessage, PresentationScoped {
    override val type get() = MessageType.SHOW_QR
}

data class ShowLink(
    override val id: UUID,
    val url: String,
    val title: String?,
    override val envelope: PresentationEnvelope? = null,
) : RelayMessage, PresentationScoped {
    override val type get() = MessageType.SHOW_LINK
}

data class PresentCommand(
    override val id: UUID,
    val command: PresentCommandType,
    val intArg: Int,
) : RelayMessage {
    override val type get() = MessageType.PRESENT_COMMAND

    val fitMode: FitMode? get() = FitMode.fromWire(intArg)
    val rotation: PresentationRotation? get() = PresentationRotation.fromWire(intArg)
}


// --- presentation state reconciliation ---

/**
 * The Display's authoritative report of what it is showing.
 *
 * Carries no content, only identity and phase, so it is safe to send freely and safe to log in
 * redacted form.
 */
data class PresentationStateMessage(
    override val id: UUID,
    val sessionId: String,
    val presentationId: UUID,
    val revision: Long,
    val phase: PresentationPhase,
    val transferId: UUID?,
) : RelayMessage {
    override val type get() = MessageType.PRESENTATION_STATE
}

/**
 * Close the named presentation.
 *
 * Idempotent by construction: closing an already-closed presentation is a no-op that still gets
 * a state report back, so a retry after a dropped acknowledgement is harmless.
 */
data class PresentationDismiss(
    override val id: UUID,
    val sessionId: String,
    val presentationId: UUID,
    val revision: Long,
) : RelayMessage {
    override val type get() = MessageType.PRESENTATION_DISMISS
}

/** Asks the peer to report its presentation state. Sent after a reconnect. */
data class PresentationSyncRequest(
    override val id: UUID,
    val sessionId: String,
) : RelayMessage {
    override val type get() = MessageType.PRESENTATION_SYNC_REQUEST
}

// --- file transfer -------------------------------------------------------------------------

data class ContentOffer(
    override val id: UUID,
    val transferId: UUID,
    val kind: ContentKind,
    val sizeBytes: Long,
    val mimeType: String,
    val displayName: String,
    val sha256: ByteArray,
    /**
     * Which accepted batch this file belongs to, for [ContentKind.FILE] only.
     *
     * Null for image and PDF presentation transfers, which are not batched and are not gated on a
     * consent dialog. For a generic file it is mandatory: the receiver matches the offer against
     * the manifest the user approved, and an offer that names no batch cannot be matched against
     * anything and is refused.
     */
    val batchId: UUID? = null,
    /**
     * Position in the accepted manifest, for [ContentKind.FILE] only.
     *
     * Not needed to find the entry -- the transfer id does that -- but it pins the *order* the
     * sender promised, which is the order the receiver's progress counts against. A mismatch means
     * the two sides disagree about the batch, so it is refused rather than reordered.
     */
    val manifestIndex: Int? = null,
) : RelayMessage {
    override val type get() = MessageType.CONTENT_OFFER
    override fun equals(other: Any?) = this === other || (other is ContentOffer && messageEquals(this, other))
    override fun hashCode() = messageHash(this)
}

data class ContentAccept(override val id: UUID, val transferId: UUID) : RelayMessage {
    override val type get() = MessageType.CONTENT_ACCEPT
}

data class ContentReject(override val id: UUID, val transferId: UUID, val errorCode: ProtocolErrorCode) : RelayMessage {
    override val type get() = MessageType.CONTENT_REJECT
}

data class TransferStart(
    override val id: UUID,
    val transferId: UUID,
    val totalBytes: Long,
    val chunkSize: Int,
) : RelayMessage {
    override val type get() = MessageType.TRANSFER_START
}

data class TransferChunk(
    override val id: UUID,
    val transferId: UUID,
    val index: Long,
    val data: ByteArray,
) : RelayMessage {
    override val type get() = MessageType.TRANSFER_CHUNK
    override fun equals(other: Any?) = this === other || (other is TransferChunk && messageEquals(this, other))
    override fun hashCode() = messageHash(this)
}

data class TransferComplete(
    override val id: UUID,
    val transferId: UUID,
    val sha256: ByteArray,
) : RelayMessage {
    override val type get() = MessageType.TRANSFER_COMPLETE
    override fun equals(other: Any?) = this === other || (other is TransferComplete && messageEquals(this, other))
    override fun hashCode() = messageHash(this)
}

data class TransferCancel(
    override val id: UUID,
    val transferId: UUID,
    val errorCode: ProtocolErrorCode,
) : RelayMessage {
    override val type get() = MessageType.TRANSFER_CANCEL
}

data class ShowFile(
    override val id: UUID,
    val transferId: UUID,
    val kind: ContentKind,
    val fitMode: FitMode,
    override val envelope: PresentationEnvelope? = null,
) : RelayMessage, PresentationScoped {
    override val type get() = MessageType.SHOW_FILE
}

/**
 * One file's metadata inside a [FileBatchOffer], and the thing consent is given to.
 *
 * Every field here is part of the binding, not decoration. The `file-v1` manifest carried only a
 * name, a type and a size, and the per-file `CONTENT_OFFER` that followed carried no batch
 * identity at all, so once a batch was accepted the receiver had no way to tell whether the file
 * arriving was one it had agreed to. A controller could show one manifest and send different
 * files. Pairing authenticates the peer; it does not make everything the peer later says true.
 *
 * The transfer id is therefore allocated *before* the offer goes out and travels in the manifest,
 * and the digest travels with it, so the receiver can match an incoming offer against the exact
 * entry the user approved and refuse anything else before a byte is written.
 */
data class FileManifestEntry(
    /**
     * Allocated by the sender before the batch is offered, so consent names a specific transfer.
     */
    val transferId: UUID,
    val displayName: String,
    val mimeType: String,
    /**
     * Measured, never declared.
     *
     * The sender spools each file to app-private storage before offering, so this is the real
     * length of the bytes that will be sent. There is deliberately no "unknown" sentinel on the
     * wire: an unknown size is resolved on the sending side, so the receiver can always show a
     * real total and check it against its own limits.
     */
    val sizeBytes: Long,
    /** SHA-256 of the exact bytes that will be sent. Compared against the per-file offer. */
    val sha256: ByteArray,
) {
    // ByteArray in a data class: the generated equals compares identity, which is never what a
    // caller means for a digest.
    override fun equals(other: Any?): Boolean = this === other || (
        other is FileManifestEntry &&
            transferId == other.transferId &&
            displayName == other.displayName &&
            mimeType == other.mimeType &&
            sizeBytes == other.sizeBytes &&
            sha256.contentEquals(other.sha256)
        )

    override fun hashCode(): Int {
        var result = transferId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + sha256.contentHashCode()
        return result
    }
}

/**
 * Controller -> Display: the whole selection, for a single confirmation.
 *
 * Deliberately carries **no sender name**. `file-v1` included one and the dialog displayed it,
 * which meant the identity the user was shown came from inside a message the peer composed. The
 * Display already knows who it is talking to -- the handshake established it -- so the consent UI
 * uses the authenticated peer name from the session and this message cannot influence it.
 */
data class FileBatchOffer(
    override val id: UUID,
    val batchId: UUID,
    val files: List<FileManifestEntry>,
) : RelayMessage {
    override val type get() = MessageType.FILE_BATCH_OFFER

    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

data class FileBatchAccept(override val id: UUID, val batchId: UUID) : RelayMessage {
    override val type get() = MessageType.FILE_BATCH_ACCEPT
}

data class FileBatchReject(
    override val id: UUID,
    val batchId: UUID,
    val errorCode: ProtocolErrorCode,
) : RelayMessage {
    override val type get() = MessageType.FILE_BATCH_REJECT
}

/**
 * Either direction: this batch is over, stop.
 *
 * The explicit terminal event the protocol was missing. Cancelling used to be a purely local act
 * on the controller -- cancel the coroutine, update local state -- so the Display kept its
 * accepted batch, its open partial and its "a transfer is in progress" state, and the next batch
 * was refused as BUSY until the session ended.
 *
 * Idempotent by construction: it names a batch rather than describing a transition, so a repeat,
 * a crossing cancel from the other side, or one arriving after the batch already ended is safe to
 * apply again.
 */
data class FileBatchCancel(
    override val id: UUID,
    val batchId: UUID,
    val errorCode: ProtocolErrorCode,
) : RelayMessage {
    override val type get() = MessageType.FILE_BATCH_CANCEL
}

data class PdfPageCommand(
    override val id: UUID,
    val transferId: UUID,
    val pageIndex: Int,
) : RelayMessage {
    override val type get() = MessageType.PDF_PAGE_COMMAND
}

// --- mirroring -----------------------------------------------------------------------------

data class MirrorStart(
    override val id: UUID,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitRate: Int,
    val codecMime: String,
) : RelayMessage {
    override val type get() = MessageType.MIRROR_START
}

data class MirrorConfig(
    override val id: UUID,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val csd0: ByteArray,
    val csd1: ByteArray,
) : RelayMessage {
    override val type get() = MessageType.MIRROR_CONFIG
    override fun equals(other: Any?) = this === other || (other is MirrorConfig && messageEquals(this, other))
    override fun hashCode() = messageHash(this)
}

/**
 * One fragment of an encoded video frame.
 *
 * A whole H.264 access unit does not fit in one record: a full-screen keyframe -- which is exactly
 * what an app switch produces -- routinely exceeds the 192 KiB field cap. Sending one anyway made
 * the receiver reject the record with PAYLOAD_TOO_LARGE, and that tore down the whole session.
 * Observed on hardware: pressing Home and opening another app killed the session within seconds.
 *
 * So frames are fragmented. [frameSequence] groups the fragments of one frame, [fragmentIndex] and
 * [fragmentCount] order and terminate it. A single-fragment frame is index 0 of count 1, which is
 * what every small P-frame is.
 */
data class MirrorFrame(
    override val id: UUID,
    val presentationTimeUs: Long,
    val keyFrame: Boolean,
    val data: ByteArray,
    val frameSequence: Long = 0,
    val fragmentIndex: Int = 0,
    val fragmentCount: Int = 1,
) : RelayMessage {
    override val type get() = MessageType.MIRROR_FRAME
    override fun equals(other: Any?) = this === other || (other is MirrorFrame && messageEquals(this, other))
    override fun hashCode() = messageHash(this)
}

data class MirrorStop(override val id: UUID, val reason: String) : RelayMessage {
    override val type get() = MessageType.MIRROR_STOP
}

data class MirrorKeyframeRequest(override val id: UUID) : RelayMessage {
    override val type get() = MessageType.MIRROR_KEYFRAME_REQUEST
}

// Data classes with ByteArray members need structural equality; compare their canonical encoding,
// which is well defined because MessageCodec is deterministic.
private fun messageEquals(a: RelayMessage, b: RelayMessage): Boolean =
    a.type == b.type && MessageCodec.encode(a).contentEquals(MessageCodec.encode(b))

private fun messageHash(m: RelayMessage): Int = MessageCodec.encode(m).contentHashCode()
