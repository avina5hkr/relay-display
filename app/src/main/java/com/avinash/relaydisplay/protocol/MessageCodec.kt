package com.avinash.relaydisplay.protocol

import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.domain.model.PresentCommandType
import com.avinash.relaydisplay.domain.model.PresentationPhase
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Encodes and decodes [RelayMessage] to and from the plaintext record that goes inside a frame.
 *
 * Record layout: `type:u16 | messageId:16 bytes | TLV body`.
 *
 * The encoding is deterministic (see [Tlv]) so `encode(decode(bytes)) == bytes` for every valid
 * input. `MessageCodecGoldenTest` pins that for one instance of each message type.
 */
object MessageCodec {

    private const val RECORD_HEADER = 2 + 16

    /** Public keys are uncompressed EC P-256 points inside an X.509 SPKI wrapper; ~91 bytes. */
    private const val MAX_KEY_BYTES = 256
    private const val MAX_SIGNATURE_BYTES = 256
    private const val MAX_NONCE_BYTES = 64
    private const val MAX_DEVICE_ID_BYTES = 64
    private const val MAX_CAPABILITIES_BYTES = 512
    private const val MAX_DETAIL_BYTES = 512
    private const val MAX_CSD_BYTES = 4096
    private const val MAX_MIRROR_FRAME_BYTES = 192 * 1024

    /**
     * The largest single fragment on the wire.
     *
     * 64 KiB keeps a fragment far below the field cap and the record cap, so a fragment can never
     * be the thing that trips PAYLOAD_TOO_LARGE. Sixteen of these is a 1 MiB frame, which is
     * larger than any keyframe this encoder produces at 720p.
     */
    const val MAX_MIRROR_FRAGMENT_BYTES = 64 * 1024

    /** Bounds reassembly memory: 16 x 64 KiB. A frame needing more is dropped, not buffered. */
    const val MAX_MIRROR_FRAGMENTS = 16
    private const val SHA256_BYTES = 32

    fun encode(message: RelayMessage): ByteArray {
        val body = encodeBody(message)
        val out = ByteArrayOutputStream(RECORD_HEADER + body.size)
        out.write((message.type.code ushr 8) and 0xFF)
        out.write(message.type.code and 0xFF)
        out.write(uuidToBytes(message.id))
        out.write(body)
        return out.toByteArray()
    }

    fun decode(record: ByteArray): RelayMessage {
        if (record.size < RECORD_HEADER) {
            protocolError(ProtocolErrorCode.MALFORMED_FRAME, "record shorter than header")
        }
        if (record.size > ContentLimits.MAX_CONTROL_PAYLOAD) {
            protocolError(ProtocolErrorCode.PAYLOAD_TOO_LARGE, "record ${record.size}B too large")
        }
        val typeCode = ((record[0].toInt() and 0xFF) shl 8) or (record[1].toInt() and 0xFF)
        val type = MessageType.fromCode(typeCode)
        val id = uuidFromBytes(record, 2)
        val tlv = TlvReader.parse(record, RECORD_HEADER, record.size - RECORD_HEADER)
        return decodeBody(type, id, tlv)
    }

    // -- body encoding ------------------------------------------------------------------------

    private fun encodeBody(m: RelayMessage): ByteArray {
        val w = TlvWriter()
        when (m) {
            is Hello -> {
                w.putU8(1, m.protocolMajor)
                w.putU8(2, m.protocolMinor)
                w.putString(3, m.deviceId)
                w.putString(4, m.deviceName)
                w.putU8(5, m.role.wireCode)
                w.putBytes(6, m.identityPublicKey)
                w.putBytes(7, m.ephemeralPublicKey)
                w.putBytes(8, m.nonce)
                w.putString(9, encodeCapabilities(m.capabilities))
                w.putBool(10, m.requestSasConfirmation)
            }
            is HelloAck -> {
                w.putU8(1, m.protocolMajor)
                w.putU8(2, m.protocolMinor)
                w.putString(3, m.deviceId)
                w.putString(4, m.deviceName)
                w.putU8(5, m.role.wireCode)
                w.putBytes(6, m.identityPublicKey)
                w.putBytes(7, m.ephemeralPublicKey)
                w.putBytes(8, m.nonce)
                w.putString(9, encodeCapabilities(m.capabilities))
                w.putBool(10, m.requestSasConfirmation)
            }
            is AuthConfirm -> {
                w.putBytes(1, m.transcriptSignature)
                m.pairingTokenProof?.let { w.putBytes(2, it) }
            }
            is AuthResult -> {
                w.putBool(1, m.accepted)
                w.putU16(2, m.errorCode.code)
                w.putBool(3, m.requiresSasConfirmation)
                m.transcriptSignature?.let { w.putBytes(4, it) }
            }
            is SasConfirm -> w.putBool(1, m.confirmed)
            is Ping -> w.putI64(1, m.timestampMs)
            is Pong -> w.putI64(1, m.echoTimestampMs)
            is Ack -> {
                w.putBytes(1, uuidToBytes(m.refId))
                w.putBool(2, m.ok)
                w.putU16(3, m.errorCode.code)
            }
            is ErrorMessage -> {
                w.putU16(1, m.errorCode.code)
                w.putString(2, m.detail)
            }
            is Bye -> w.putString(1, m.reason)
            is ShowText -> {
                w.putString(1, m.text)
                w.putEnvelope(m.envelope)
            }
            is ShowQr -> {
                w.putString(1, m.payload)
                m.caption?.let { w.putString(2, it) }
                w.putEnvelope(m.envelope)
            }
            is ShowLink -> {
                w.putString(1, m.url)
                m.title?.let { w.putString(2, it) }
                w.putEnvelope(m.envelope)
            }
            is PresentCommand -> {
                w.putU8(1, m.command.wireCode)
                // Offset by 128 so the sentinel -1 (system brightness) survives a u8 field.
                w.putU16(2, m.intArg + 128)
            }
            is ContentOffer -> {
                w.putBytes(1, uuidToBytes(m.transferId))
                w.putU8(2, m.kind.wireCode)
                w.putI64(3, m.sizeBytes)
                w.putString(4, m.mimeType)
                w.putString(5, m.displayName)
                w.putBytes(6, m.sha256)
                // Tags 7 and 8 are written only for a batched generic file. Absent for image and
                // PDF presentation transfers, which have no batch to bind to -- which is also why
                // adding them did not change the encoding of any existing message.
                m.batchId?.let { w.putBytes(7, uuidToBytes(it)) }
                m.manifestIndex?.let { w.putU8(8, it) }
            }
            is FileBatchOffer -> {
                w.putBytes(1, uuidToBytes(m.batchId))
                // Tag 2 was the sender's name in file-v1 and is deliberately unused: the consent
                // dialog takes the peer's identity from the authenticated session instead.
                w.putU8(3, m.files.size)
                w.putBytes(4, encodeManifest(m.files))
            }
            is FileBatchAccept -> w.putBytes(1, uuidToBytes(m.batchId))
            is FileBatchReject -> {
                w.putBytes(1, uuidToBytes(m.batchId))
                w.putU16(2, m.errorCode.code)
            }
            is FileBatchCancel -> {
                w.putBytes(1, uuidToBytes(m.batchId))
                w.putU16(2, m.errorCode.code)
            }
            is ContentAccept -> w.putBytes(1, uuidToBytes(m.transferId))
            is ContentReject -> {
                w.putBytes(1, uuidToBytes(m.transferId))
                w.putU16(2, m.errorCode.code)
            }
            is TransferStart -> {
                w.putBytes(1, uuidToBytes(m.transferId))
                w.putI64(2, m.totalBytes)
                w.putU32(3, m.chunkSize.toLong())
            }
            is TransferChunk -> {
                w.putBytes(1, uuidToBytes(m.transferId))
                w.putI64(2, m.index)
                w.putBytes(3, m.data)
            }
            is TransferComplete -> {
                w.putBytes(1, uuidToBytes(m.transferId))
                w.putBytes(2, m.sha256)
            }
            is TransferCancel -> {
                w.putBytes(1, uuidToBytes(m.transferId))
                w.putU16(2, m.errorCode.code)
            }
            is ShowFile -> {
                w.putBytes(1, uuidToBytes(m.transferId))
                w.putU8(2, m.kind.wireCode)
                w.putU8(3, m.fitMode.wireCode)
                w.putEnvelope(m.envelope)
            }
            is PdfPageCommand -> {
                w.putBytes(1, uuidToBytes(m.transferId))
                w.putU32(2, m.pageIndex.toLong())
            }
            is MirrorStart -> {
                w.putU16(1, m.width)
                w.putU16(2, m.height)
                w.putU8(3, m.frameRate)
                w.putU32(4, m.bitRate.toLong())
                w.putString(5, m.codecMime)
            }
            is MirrorConfig -> {
                w.putU16(1, m.width)
                w.putU16(2, m.height)
                w.putU16(3, m.rotationDegrees)
                w.putBytes(4, m.csd0)
                w.putBytes(5, m.csd1)
            }
            is MirrorFrame -> {
                w.putI64(1, m.presentationTimeUs)
                w.putBool(2, m.keyFrame)
                w.putBytes(3, m.data)
                w.putI64(4, m.frameSequence)
                w.putI64(5, m.fragmentIndex.toLong())
                w.putI64(6, m.fragmentCount.toLong())
            }
            is MirrorStop -> w.putString(1, m.reason)
            is PresentationStateMessage -> {
                w.putString(1, m.sessionId)
                w.putBytes(2, uuidToBytes(m.presentationId))
                w.putI64(3, m.revision)
                w.putU8(4, m.phase.wireCode)
                m.transferId?.let { w.putBytes(5, uuidToBytes(it)) }
            }
            is PresentationDismiss -> {
                w.putString(1, m.sessionId)
                w.putBytes(2, uuidToBytes(m.presentationId))
                w.putI64(3, m.revision)
            }
            is PresentationSyncRequest -> w.putString(1, m.sessionId)
            is MirrorKeyframeRequest -> Unit
        }
        return w.toByteArray()
    }

    // -- body decoding ------------------------------------------------------------------------

    private fun decodeBody(type: MessageType, id: UUID, t: TlvReader): RelayMessage = when (type) {
        MessageType.HELLO -> Hello(
            id = id,
            protocolMajor = t.u8(1),
            protocolMinor = t.u8(2),
            deviceId = t.string(3, MAX_DEVICE_ID_BYTES),
            deviceName = t.string(4, ContentLimits.MAX_DEVICE_NAME_BYTES),
            role = wireRole(t.u8(5)),
            identityPublicKey = t.bytes(6, MAX_KEY_BYTES),
            ephemeralPublicKey = t.bytes(7, MAX_KEY_BYTES),
            nonce = t.bytes(8, MAX_NONCE_BYTES),
            capabilities = decodeCapabilities(t.string(9, MAX_CAPABILITIES_BYTES)),
            requestSasConfirmation = t.bool(10),
        )
        MessageType.HELLO_ACK -> HelloAck(
            id = id,
            protocolMajor = t.u8(1),
            protocolMinor = t.u8(2),
            deviceId = t.string(3, MAX_DEVICE_ID_BYTES),
            deviceName = t.string(4, ContentLimits.MAX_DEVICE_NAME_BYTES),
            role = wireRole(t.u8(5)),
            identityPublicKey = t.bytes(6, MAX_KEY_BYTES),
            ephemeralPublicKey = t.bytes(7, MAX_KEY_BYTES),
            nonce = t.bytes(8, MAX_NONCE_BYTES),
            capabilities = decodeCapabilities(t.string(9, MAX_CAPABILITIES_BYTES)),
            requestSasConfirmation = t.bool(10),
        )
        MessageType.AUTH_CONFIRM -> AuthConfirm(
            id = id,
            transcriptSignature = t.bytes(1, MAX_SIGNATURE_BYTES),
            pairingTokenProof = t.optBytes(2, MAX_SIGNATURE_BYTES),
        )
        MessageType.AUTH_RESULT -> AuthResult(
            id = id,
            accepted = t.bool(1),
            errorCode = ProtocolErrorCode.fromCode(t.u16(2)),
            requiresSasConfirmation = t.bool(3),
            transcriptSignature = t.optBytes(4, MAX_SIGNATURE_BYTES),
        )
        MessageType.SAS_CONFIRM -> SasConfirm(id, t.bool(1))
        MessageType.PING -> Ping(id, t.i64(1))
        MessageType.PONG -> Pong(id, t.i64(1))
        MessageType.ACK -> Ack(
            id = id,
            refId = uuidFromBytes(t.bytes(1, 16), 0),
            ok = t.bool(2),
            errorCode = ProtocolErrorCode.fromCode(t.u16(3)),
        )
        MessageType.ERROR -> ErrorMessage(id, ProtocolErrorCode.fromCode(t.u16(1)), t.string(2, MAX_DETAIL_BYTES))
        MessageType.BYE -> Bye(id, t.string(1, MAX_DETAIL_BYTES))
        MessageType.SHOW_TEXT -> ShowText(
            id = id,
            text = t.string(1, ContentLimits.MAX_TEXT_BYTES),
            envelope = t.readEnvelope(),
        )
        MessageType.SHOW_QR -> ShowQr(
            id = id,
            payload = t.string(1, ContentLimits.MAX_TEXT_BYTES),
            caption = t.optString(2, ContentLimits.MAX_TEXT_BYTES),
            envelope = t.readEnvelope(),
        )
        MessageType.SHOW_LINK -> ShowLink(
            id = id,
            url = t.string(1, ContentLimits.MAX_TEXT_BYTES),
            title = t.optString(2, ContentLimits.MAX_TEXT_BYTES),
            envelope = t.readEnvelope(),
        )
        MessageType.PRESENT_COMMAND -> PresentCommand(
            id = id,
            command = PresentCommandType.fromWire(t.u8(1))
                ?: protocolError(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE, "unknown present command"),
            intArg = t.u16(2) - 128,
        )
        MessageType.CONTENT_OFFER -> {
            val manifestIndex = t.optU8(8)
            if (manifestIndex != null && manifestIndex >= ContentLimits.MAX_FILES_PER_BATCH) {
                protocolError(ProtocolErrorCode.MALFORMED_FRAME, "manifest index out of range")
            }
            ContentOffer(
                id = id,
                transferId = uuidFromBytes(t.bytes(1, 16), 0),
                kind = ContentKind.fromWire(t.u8(2))
                    ?: protocolError(ProtocolErrorCode.UNSUPPORTED_FORMAT, "unknown content kind"),
                sizeBytes = t.i64(3),
                mimeType = t.string(4, ContentLimits.MAX_MIME_BYTES),
                displayName = t.string(5, ContentLimits.MAX_FILENAME_BYTES),
                sha256 = exactly(t.bytes(6, SHA256_BYTES), SHA256_BYTES, "sha256"),
                // Optional: present for a batched generic file, absent for image and PDF.
                batchId = t.optBytes(7, 16)?.let { uuidFromBytes(exactly(it, 16, "batchId"), 0) },
                manifestIndex = manifestIndex,
            )
        }
        MessageType.FILE_BATCH_OFFER -> {
            val declaredCount = t.u8(3)
            if (declaredCount < 1 || declaredCount > ContentLimits.MAX_FILES_PER_BATCH) {
                protocolError(ProtocolErrorCode.MALFORMED_FRAME, "batch file count out of range")
            }
            val files = decodeManifest(t.bytes(4, MAX_MANIFEST_BYTES), declaredCount)
            FileBatchOffer(
                id = id,
                batchId = uuidFromBytes(t.bytes(1, 16), 0),
                files = files,
            )
        }
        MessageType.FILE_BATCH_ACCEPT -> FileBatchAccept(id, uuidFromBytes(t.bytes(1, 16), 0))
        MessageType.FILE_BATCH_REJECT -> FileBatchReject(
            id = id,
            batchId = uuidFromBytes(t.bytes(1, 16), 0),
            errorCode = ProtocolErrorCode.fromCode(t.u16(2)),
        )
        MessageType.FILE_BATCH_CANCEL -> FileBatchCancel(
            id = id,
            batchId = uuidFromBytes(t.bytes(1, 16), 0),
            errorCode = ProtocolErrorCode.fromCode(t.u16(2)),
        )
        MessageType.CONTENT_ACCEPT -> ContentAccept(id, uuidFromBytes(t.bytes(1, 16), 0))
        MessageType.CONTENT_REJECT -> ContentReject(
            id = id,
            transferId = uuidFromBytes(t.bytes(1, 16), 0),
            errorCode = ProtocolErrorCode.fromCode(t.u16(2)),
        )
        MessageType.TRANSFER_START -> TransferStart(
            id = id,
            transferId = uuidFromBytes(t.bytes(1, 16), 0),
            totalBytes = t.i64(2),
            chunkSize = t.u32(3).toInt(),
        )
        MessageType.TRANSFER_CHUNK -> TransferChunk(
            id = id,
            transferId = uuidFromBytes(t.bytes(1, 16), 0),
            index = t.i64(2),
            data = t.bytes(3, ContentLimits.CHUNK_BYTES),
        )
        MessageType.TRANSFER_COMPLETE -> TransferComplete(
            id = id,
            transferId = uuidFromBytes(t.bytes(1, 16), 0),
            sha256 = exactly(t.bytes(2, SHA256_BYTES), SHA256_BYTES, "sha256"),
        )
        MessageType.TRANSFER_CANCEL -> TransferCancel(
            id = id,
            transferId = uuidFromBytes(t.bytes(1, 16), 0),
            errorCode = ProtocolErrorCode.fromCode(t.u16(2)),
        )
        MessageType.SHOW_FILE -> ShowFile(
            id = id,
            transferId = uuidFromBytes(t.bytes(1, 16), 0),
            kind = ContentKind.fromWire(t.u8(2))
                ?: protocolError(ProtocolErrorCode.UNSUPPORTED_FORMAT, "unknown content kind"),
            fitMode = FitMode.fromWire(t.u8(3))
                ?: protocolError(ProtocolErrorCode.MALFORMED_FRAME, "unknown fit mode"),
            envelope = t.readEnvelope(),
        )
        MessageType.PDF_PAGE_COMMAND -> PdfPageCommand(
            id = id,
            transferId = uuidFromBytes(t.bytes(1, 16), 0),
            pageIndex = t.u32(2).toInt(),
        )
        MessageType.MIRROR_START -> MirrorStart(
            id = id,
            width = t.u16(1),
            height = t.u16(2),
            frameRate = t.u8(3),
            bitRate = t.u32(4).toInt(),
            codecMime = t.string(5, MAX_MIME_LEN),
        )
        MessageType.MIRROR_CONFIG -> MirrorConfig(
            id = id,
            width = t.u16(1),
            height = t.u16(2),
            rotationDegrees = t.u16(3),
            csd0 = t.bytes(4, MAX_CSD_BYTES),
            csd1 = t.bytes(5, MAX_CSD_BYTES),
        )
        MessageType.MIRROR_FRAME -> MirrorFrame(
            id = id,
            presentationTimeUs = t.i64(1),
            keyFrame = t.bool(2),
            // Each fragment is capped, not the whole frame; MAX_MIRROR_FRAGMENT_BYTES leaves
            // headroom under the field cap for the rest of the record.
            data = t.bytes(3, MAX_MIRROR_FRAGMENT_BYTES),
            frameSequence = t.optI64(4) ?: 0,
            fragmentIndex = (t.optI64(5) ?: 0).toInt(),
            fragmentCount = (t.optI64(6) ?: 1).toInt(),
        )
        MessageType.MIRROR_STOP -> MirrorStop(id, t.string(1, MAX_DETAIL_BYTES))
        MessageType.PRESENTATION_STATE -> PresentationStateMessage(
            id = id,
            sessionId = t.string(1, MAX_SESSION_ID_BYTES),
            presentationId = uuidFromBytes(t.bytes(2, 16), 0),
            revision = requireNonNegative(t.i64(3), "revision"),
            phase = PresentationPhase.fromWire(t.u8(4))
                ?: protocolError(ProtocolErrorCode.MALFORMED_FRAME, "unknown presentation phase"),
            transferId = t.optBytes(5, 16)?.let { uuidFromBytes(it, 0) },
        )
        MessageType.PRESENTATION_DISMISS -> PresentationDismiss(
            id = id,
            sessionId = t.string(1, MAX_SESSION_ID_BYTES),
            presentationId = uuidFromBytes(t.bytes(2, 16), 0),
            revision = requireNonNegative(t.i64(3), "revision"),
        )
        MessageType.PRESENTATION_SYNC_REQUEST -> PresentationSyncRequest(
            id = id,
            sessionId = t.string(1, MAX_SESSION_ID_BYTES),
        )
        MessageType.MIRROR_KEYFRAME_REQUEST -> MirrorKeyframeRequest(id)
    }

    private const val MAX_MIME_LEN = ContentLimits.MAX_MIME_BYTES

    /** A session id is 16 bytes rendered as hex; refuse anything that could not be one. */
    private const val MAX_SESSION_ID_BYTES = 64

    /** Envelope tags sit above every content tag so they stay in ascending order. */
    private const val TAG_PRESENTATION_ID = 20
    private const val TAG_PRESENTATION_REVISION = 21

    private fun TlvWriter.putEnvelope(envelope: PresentationEnvelope?): TlvWriter {
        if (envelope == null) return this
        putBytes(TAG_PRESENTATION_ID, uuidToBytes(envelope.presentationId))
        putI64(TAG_PRESENTATION_REVISION, envelope.revision)
        return this
    }

    /**
     * Both envelope fields or neither. A half-present envelope is a malformed message rather
     * than something to guess at.
     */
    private fun TlvReader.readEnvelope(): PresentationEnvelope? {
        val idBytes = optBytes(TAG_PRESENTATION_ID, 16)
        val revision = optI64(TAG_PRESENTATION_REVISION)
        if (idBytes == null && revision == null) return null
        if (idBytes == null || revision == null) {
            protocolError(ProtocolErrorCode.MALFORMED_FRAME, "incomplete presentation envelope")
        }
        return PresentationEnvelope(uuidFromBytes(idBytes, 0), requireNonNegative(revision, "revision"))
    }

    private fun requireNonNegative(value: Long, what: String): Long {
        if (value < 0) protocolError(ProtocolErrorCode.MALFORMED_FRAME, "$what must not be negative")
        return value
    }

    private fun wireRole(code: Int): DeviceRole =
        DeviceRole.fromWire(code) ?: protocolError(ProtocolErrorCode.MALFORMED_FRAME, "unknown role code")

    private fun exactly(value: ByteArray, size: Int, what: String): ByteArray {
        if (value.size != size) protocolError(ProtocolErrorCode.MALFORMED_FRAME, "$what must be ${size}B")
        return value
    }

    /**
     * Capabilities travel as a comma separated ASCII set. Encoding sorts them so the transcript
     * hash is stable regardless of the order the sender happened to build the set in.
     */
    internal fun encodeCapabilities(caps: Set<String>): String = caps.sorted().joinToString(",")

    internal fun decodeCapabilities(raw: String): Set<String> =
        if (raw.isEmpty()) emptySet() else raw.split(',').filter { it.isNotEmpty() }.toSet()

    fun uuidToBytes(uuid: UUID): ByteArray {
        val b = ByteArray(16)
        FrameCodec.writeLong(b, 0, uuid.mostSignificantBits)
        FrameCodec.writeLong(b, 8, uuid.leastSignificantBits)
        return b
    }

    fun uuidFromBytes(b: ByteArray, offset: Int): UUID {
        if (b.size - offset < 16) protocolError(ProtocolErrorCode.MALFORMED_FRAME, "short uuid")
        return UUID(FrameCodec.readLong(b, offset), FrameCodec.readLong(b, offset + 8))
    }

    @Suppress("unused")
    private fun utf8(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    /**
     * Ceiling on the encoded manifest.
     *
     * MAX_FILES_PER_BATCH entries, each at worst a full-length name, a full-length MIME type, the
     * two length prefixes and an eight-byte size. Bounded before a single byte is parsed, so a
     * hostile length prefix cannot make the decoder allocate.
     */
    private val MAX_MANIFEST_BYTES: Int =
        ContentLimits.MAX_FILES_PER_BATCH *
            (16 + 2 + ContentLimits.MAX_FILENAME_BYTES + 2 + ContentLimits.MAX_MIME_BYTES + 8 + SHA256_BYTES)

    /**
     * Packs the file list into one TLV field.
     *
     * The TLV layer keys fields by tag in a map, so it has no repeated fields and a list needs a
     * nested encoding. Each entry is
     * `transferId:16 | u16 nameLen | name | u16 mimeLen | mime | i64 size | sha256:32`,
     * in the order the files will be sent -- that order is part of the meaning, because the
     * receiver's "3 of 7" counts against it and the per-file offer restates it as a manifest index.
     */
    private fun encodeManifest(files: List<FileManifestEntry>): ByteArray {
        val out = java.io.ByteArrayOutputStream(files.size * 160)
        for (file in files) {
            val name = file.displayName.toByteArray(StandardCharsets.UTF_8)
            val mime = file.mimeType.toByteArray(StandardCharsets.UTF_8)
            // These are our own values and already validated, so a violation here is a bug on
            // this side, not a malformed peer frame.
            require(name.size <= ContentLimits.MAX_FILENAME_BYTES) { "name too long to encode" }
            require(mime.size <= ContentLimits.MAX_MIME_BYTES) { "mime too long to encode" }
            require(file.sha256.size == SHA256_BYTES) { "digest must be $SHA256_BYTES bytes" }
            out.write(uuidToBytes(file.transferId))
            out.write((name.size ushr 8) and 0xFF)
            out.write(name.size and 0xFF)
            out.write(name)
            out.write((mime.size ushr 8) and 0xFF)
            out.write(mime.size and 0xFF)
            out.write(mime)
            for (shift in 56 downTo 0 step 8) {
                out.write(((file.sizeBytes ushr shift) and 0xFF).toInt())
            }
            out.write(file.sha256)
        }
        return out.toByteArray()
    }

    /**
     * Unpacks a manifest sent by a peer.
     *
     * Everything here is untrusted. Every length is checked against both the remaining buffer and
     * the per-field limit before it is used, the entry count must match what the header declared,
     * and trailing bytes are a hard error rather than something to ignore -- a decoder that
     * tolerates junk it does not understand is how one side's idea of a frame drifts from the
     * other's.
     */
    private fun decodeManifest(raw: ByteArray, expectedCount: Int): List<FileManifestEntry> {
        val files = ArrayList<FileManifestEntry>(expectedCount)
        var offset = 0

        fun need(count: Int) {
            if (count < 0 || offset + count > raw.size) {
                protocolError(ProtocolErrorCode.MALFORMED_FRAME, "truncated batch manifest")
            }
        }

        fun readU16(): Int {
            need(2)
            val value = ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
            offset += 2
            return value
        }

        fun readText(maxBytes: Int, what: String): String {
            val length = readU16()
            if (length > maxBytes) {
                protocolError(ProtocolErrorCode.MALFORMED_FRAME, "batch $what too long")
            }
            need(length)
            val text = String(raw, offset, length, StandardCharsets.UTF_8)
            offset += length
            return text
        }

        val seenIds = HashSet<java.util.UUID>(expectedCount)
        repeat(expectedCount) {
            need(16)
            val transferId = uuidFromBytes(raw.copyOfRange(offset, offset + 16), 0)
            offset += 16
            // A manifest that names the same transfer twice cannot be matched unambiguously, and
            // an entry the receiver cannot tell apart from another is exactly what the binding is
            // for. Refused here so nothing downstream has to defend against a duplicate key.
            if (!seenIds.add(transferId)) {
                protocolError(ProtocolErrorCode.MALFORMED_FRAME, "duplicate transfer id in manifest")
            }
            val name = readText(ContentLimits.MAX_FILENAME_BYTES, "name")
            val mime = readText(ContentLimits.MAX_MIME_BYTES, "mime type")
            need(8)
            var size = 0L
            for (i in 0 until 8) {
                size = (size shl 8) or (raw[offset + i].toLong() and 0xFF)
            }
            offset += 8
            // A negative size cannot be legitimate: sizes are measured, and the sentinel for
            // "unknown" never travels. Bounding it here means nothing downstream has to.
            if (size < 0 || size > ContentLimits.MAX_FILE_BYTES) {
                protocolError(ProtocolErrorCode.MALFORMED_FRAME, "batch entry size out of range")
            }
            need(SHA256_BYTES)
            val digest = raw.copyOfRange(offset, offset + SHA256_BYTES)
            offset += SHA256_BYTES
            files.add(
                FileManifestEntry(
                    transferId = transferId,
                    displayName = name,
                    mimeType = mime,
                    sizeBytes = size,
                    sha256 = digest,
                ),
            )
        }
        if (offset != raw.size) {
            protocolError(ProtocolErrorCode.MALFORMED_FRAME, "trailing bytes in batch manifest")
        }
        return files
    }
}

/** Capability tokens advertised in [Hello]. Unknown tokens from a peer are ignored, not fatal. */
object Capabilities {
    const val TEXT = "text"
    const val QR = "qr"
    const val LINK = "link"
    const val IMAGE = "image"
    const val PDF = "pdf"

    /**
     * Generic file transfer, wire version 1.
     *
     * Versioned in the string itself, so a future incompatible format announces `file-v2` and an
     * older peer simply does not match it. That is what lets the sender say "this needs a newer
     * Relay Display" instead of streaming a format the receiver will mis-parse.
     */
    const val FILE_V1 = "file-v1"

    /**
     * Generic file transfer, wire version 2. The only version this build offers.
     *
     * v2 exists because v1 was not safe: its manifest carried no transfer id and no digest, and
     * the per-file offer carried no batch identity, so an accepted batch could be followed by
     * entirely different files. That is a protocol-shaped hole, not something a receiver could
     * patch around, so the fix is a new version rather than stricter checks on the old one.
     *
     * This build advertises **only** `file-v2`. Advertising both would mean either honouring v1's
     * unbound offers -- the vulnerability -- or accepting a capability it refuses to act on. A
     * peer that announces only [FILE_V1] is therefore treated as too old and told so, which is
     * the same path an unversioned peer already took. No release has shipped v1, so nothing in
     * the field is broken by this.
     */
    const val FILE_V2 = "file-v2"
    const val MIRROR_RECEIVE = "mirror-rx"
    const val MIRROR_SEND = "mirror-tx"
}
