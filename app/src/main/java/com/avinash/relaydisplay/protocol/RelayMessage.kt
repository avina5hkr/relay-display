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
 * One file's metadata inside a [FileBatchOffer].
 *
 * No transfer id: ids are allocated per file when that file's own CONTENT_OFFER goes out. This is
 * a manifest for the confirmation dialog, not a transfer handle.
 */
data class FileManifestEntry(
    val displayName: String,
    val mimeType: String,
    /**
     * Measured, never declared.
     *
     * The sender runs a preparation pass before offering, so this is the real length of the bytes
     * that will arrive. There is deliberately no "unknown" sentinel on the wire: an unknown size
     * is resolved on the sending side, so the receiver can always show a real total and check it
     * against its own limits.
     */
    val sizeBytes: Long,
)

/**
 * Controller -> Display: the whole selection, for a single confirmation.
 *
 * Carries the sender's own name so the Display can say who is asking without having to correlate
 * against connection state at dialog time.
 */
data class FileBatchOffer(
    override val id: UUID,
    val batchId: UUID,
    val senderName: String,
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
