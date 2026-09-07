package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.domain.model.PresentCommandType
import com.avinash.relaydisplay.domain.model.PresentationId
import com.avinash.relaydisplay.domain.model.PresentationPhase
import com.avinash.relaydisplay.domain.model.PresentationSnapshot
import com.avinash.relaydisplay.mirroring.MirrorController
import com.avinash.relaydisplay.network.session.SessionHost
import com.avinash.relaydisplay.network.session.RelaySession
import com.avinash.relaydisplay.protocol.Ack
import com.avinash.relaydisplay.protocol.Capabilities
import com.avinash.relaydisplay.protocol.FileBatchAccept
import com.avinash.relaydisplay.protocol.FileBatchOffer
import com.avinash.relaydisplay.protocol.FileBatchReject
import com.avinash.relaydisplay.protocol.FileManifestEntry
import com.avinash.relaydisplay.protocol.ContentAccept
import com.avinash.relaydisplay.protocol.ContentLimits
import com.avinash.relaydisplay.protocol.ContentOffer
import com.avinash.relaydisplay.protocol.ContentReject
import com.avinash.relaydisplay.protocol.ErrorMessage
import com.avinash.relaydisplay.protocol.MirrorConfig
import com.avinash.relaydisplay.protocol.MirrorFrame
import com.avinash.relaydisplay.protocol.MirrorKeyframeRequest
import com.avinash.relaydisplay.protocol.MirrorStart
import com.avinash.relaydisplay.protocol.MirrorStop
import com.avinash.relaydisplay.protocol.PdfPageCommand
import com.avinash.relaydisplay.protocol.PresentationDismiss
import com.avinash.relaydisplay.protocol.PresentationEnvelope
import com.avinash.relaydisplay.protocol.PresentationStateMessage
import com.avinash.relaydisplay.protocol.PresentationSyncRequest
import com.avinash.relaydisplay.protocol.PresentCommand
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.RelayMessage
import com.avinash.relaydisplay.protocol.ShowFile
import com.avinash.relaydisplay.protocol.ShowLink
import com.avinash.relaydisplay.protocol.ShowQr
import com.avinash.relaydisplay.protocol.ShowText
import com.avinash.relaydisplay.protocol.TransferCancel
import com.avinash.relaydisplay.protocol.TransferChunk
import com.avinash.relaydisplay.protocol.TransferComplete
import com.avinash.relaydisplay.protocol.TransferStart
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Progress of an outbound transfer, for the controller's UI. */
sealed interface SendState {
    data object Idle : SendState
    data class Preparing(val displayName: String) : SendState
    data class Sending(val displayName: String, val sentBytes: Long, val totalBytes: Long) : SendState {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }
    data class Complete(val displayName: String) : SendState
    data class Failed(val displayName: String, val reason: String) : SendState
}

/** Progress of an inbound transfer, for the display's UI. */
sealed interface ReceiveState {
    data object Idle : ReceiveState
    data class Receiving(val displayName: String, val receivedBytes: Long, val totalBytes: Long) : ReceiveState
    data class Failed(val reason: String) : ReceiveState
}

/**
 * Routes application messages for whichever role this device is playing.
 *
 * Lives for the whole process and subscribes once to [RelayEngine.messages], so a screen coming
 * and going never adds or drops a handler. All the "what does this message mean" logic is here;
 * the session below only knows about frames.
 */
class ContentRouter(
    private val engine: SessionHost,
    private val presentation: PresentationController,
    private val settingsRepository: SettingsRepository,
    /**
     * A provider, not the cache itself: resolving it touches `Context.getCacheDir()`, and this
     * router is constructed on the main thread during application startup.
     */
    private val cacheProvider: () -> ContentCache,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val pdfPageCounter: (java.io.File) -> Int = { 1 },
    private val mirrorController: MirrorController? = null,
    private val remote: RemotePresentationTracker = RemotePresentationTracker(),
) {
    /** What the Controller believes the Display is showing. Observed by the controller UI. */
    val remotePresentation: StateFlow<RemotePresentationState> = remote.state

    /** The session this router is currently bound to, or [PresentationState.NO_SESSION]. */
    @Volatile
    private var sessionId: String = PresentationState.NO_SESSION
    private val cache: ContentCache get() = cacheProvider()

    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private val _receiveState = MutableStateFlow<ReceiveState>(ReceiveState.Idle)
    val receiveState: StateFlow<ReceiveState> = _receiveState.asStateFlow()

    /** At most one inbound transfer at a time; a second offer is rejected with BUSY. */
    private var inbound: TransferReceiver? = null
    private var inboundTransferId: UUID? = null

    /** Waiters for the controller side of a transfer handshake, keyed by transfer id. */
    private val pendingAccepts = mutableMapOf<UUID, CompletableDeferred<Boolean>>()
    private val pendingAcks = mutableMapOf<UUID, CompletableDeferred<Boolean>>()

    @Volatile
    private var outboundCancelled = false

    init {
        scope.launch {
            settingsRepository.settings.collect { localName = it.localDeviceName }
        }

        // Off the main thread: this runs during application startup and touches the filesystem.
        scope.launch(Dispatchers.IO) {
            cache.sweepPartials()
            // Enforces FileTransferPolicy.PENDING_EXPIRY_MS. Same pass, same dispatcher: both are
            // directory scans and neither belongs on the main thread.
            cache.sweepExpired()
        }

        // Bind presentation identity to the live session. Every session gets a fresh id, so a
        // message from a previous one can never mutate current state.
        scope.launch {
            engine.activeSession.collect { session ->
                val role = settingsRepository.settings.first { it.loaded }.role
                if (session == null) {
                    sessionId = PresentationState.NO_SESSION
                    presentation.onSessionEnded()
                    remote.onSessionEnded()
                    // Consent does not outlive the connection it was given on, and a prompt for a
                    // peer that has gone away must not stay on screen offering to accept.
                    _incomingBatch.value = null
                    clearBatchAcceptance()
                    // A partial from a dead session can never be completed: its sender is gone and
                    // the protocol has no resume. Delete it now rather than waiting for expiry.
                    abortInboundQuietly("the connection ended")
                    // The sending side of the same problem. Without this a batch interrupted by a
                    // disconnect, a role switch or the user pressing Stop keeps its last phase
                    // forever, so the Send screen shows "sending 3 of 7" against a connection
                    // that no longer exists and the progress bar never moves again.
                    failBatchOnSessionEnd()
                    diagnostics.info("RD/Session", "session ended")
                    return@collect
                }
                val id = session.outcome.sessionId
                sessionId = id
                diagnostics.info("RD/Session", "session started ${id.take(6)} role=${role?.storageValue}")
                when (role) {
                    DeviceRole.DISPLAY -> presentation.onSessionStarted(id)
                    DeviceRole.CONTROLLER -> {
                        remote.onSessionStarted(id)
                        // Ask what is actually on screen rather than assuming it is blank.
                        session.trySend(PresentationSyncRequest(UUID.randomUUID(), id))
                    }
                    null -> Unit
                }
            }
        }
        scope.launch {
            engine.messages.collect { message ->
                val role = settingsRepository.settings.first { it.loaded }.role ?: return@collect
                try {
                    handle(role, message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    diagnostics.warn("router", "handling ${message.type} failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun handle(role: DeviceRole, message: RelayMessage) {
        when (role) {
            DeviceRole.DISPLAY -> handleAsDisplay(message)
            DeviceRole.CONTROLLER -> handleAsController(message)
        }
    }

    // -- display side ---------------------------------------------------------------------

    private suspend fun handleAsDisplay(message: RelayMessage) {
        val session = engine.activeSession.value ?: return
        val currentSession = sessionId
        when (message) {
            is ShowText -> applyContent(session, message.id, message.envelope) {
                presentation.showText(currentSession, message.envelope, message.text)
            }

            is ShowQr -> applyContent(session, message.id, message.envelope) {
                presentation.showQr(currentSession, message.envelope, message.payload, message.caption)
            }

            is ShowLink -> applyContent(session, message.id, message.envelope) {
                // Refused rather than shown when the scheme is not one we would ever offer to open.
                presentation.showLink(currentSession, message.envelope, message.url, message.title)
            }

            is PresentCommand -> applyOnce(session, message.id) {
                presentation.applyCommand(message.command, message.intArg).also { reportState(session) }
            }

            // The Controller asked us to close. Idempotent: a repeat still gets a state report,
            // which is what makes a retry after a lost acknowledgement safe.
            is PresentationDismiss -> {
                val snapshot = presentation.dismissRemote(message.sessionId, message.revision)
                if (snapshot == null) {
                    diagnostics.debug("RD/Presentation", "dismiss for a stale session ignored")
                } else {
                    diagnostics.info("RD/Presentation", "dismissed remotely -> ${snapshot.id.shortLabel()}")
                }
                reportState(session)
                session.acknowledge(message.id, ok = true)
            }

            is PresentationSyncRequest -> {
                diagnostics.debug("RD/Presentation", "sync requested")
                reportState(session)
            }

            is FileBatchOffer -> onBatchOffer(session, message)
            is ContentOffer -> onOffer(session, message)
            is TransferStart -> onTransferStart(session, message)
            is TransferChunk -> onChunk(session, message)
            is TransferComplete -> onTransferComplete(session, message)
            is TransferCancel -> {
                diagnostics.info("RD/Presentation", "sender cancelled the transfer")
                abortInbound("cancelled by the sender")
            }

            is ShowFile -> {
                if (message.kind == ContentKind.FILE) {
                    // Not a presentation: nothing is drawn for a generic file. This is the point
                    // at which it becomes visible in the received-files list.
                    onGenericFileStored(message.transferId)
                } else {
                    applyContent(session, message.id, message.envelope) {
                        showReceivedFile(currentSession, message)
                    }
                }
            }

            is PdfPageCommand -> applyOnce(session, message.id) {
                presentation.setPdfPage(message.pageIndex)
                true
            }

            // Mirroring: the display decodes what the controller captures.
            is MirrorConfig -> mirrorController?.onMirrorConfig(message)
            is MirrorFrame -> mirrorController?.onMirrorFrame(message)
            is MirrorStop -> {
                mirrorController?.onMirrorStopped()
                presentation.dismissRemote(currentSession, presentation.allocateRevision())
                reportState(session)
            }
            is MirrorStart -> applyContent(session, message.id, envelope = null) {
                presentation.showMirror(currentSession, null)
            }

            is ErrorMessage -> diagnostics.warn("RD/Protocol", "peer reported ${message.errorCode}")
            else -> Unit
        }
    }

    /**
     * Applies content and reports the resulting state.
     *
     * A null result from [apply] means the content was stale or unusable. Either way the peer
     * gets an acknowledgement and an authoritative state report, so its idea of the display
     * cannot drift from what is really on screen.
     */
    private fun applyContent(
        session: RelaySession,
        messageId: UUID,
        envelope: PresentationEnvelope?,
        apply: () -> PresentationSnapshot?,
    ) {
        if (!session.claimCommand(messageId)) {
            diagnostics.debug("RD/Presentation", "duplicate content message ignored")
            session.acknowledge(messageId, ok = true)
            reportState(session)
            return
        }
        val snapshot = apply()
        if (snapshot == null) {
            diagnostics.info(
                "RD/Presentation",
                "content rejected (stale or unsupported) rev=${envelope?.revision ?: -1}",
            )
            session.acknowledge(messageId, ok = false, code = ProtocolErrorCode.UNSUPPORTED_FORMAT)
        } else {
            diagnostics.info("RD/Presentation", "showing ${snapshot.phase} ${snapshot.id.shortLabel()}")
            session.acknowledge(messageId, ok = true)
        }
        reportState(session)
    }

    /** Sends this display's authoritative presentation state to the peer. */
    private fun reportState(session: RelaySession) {
        val snapshot = presentation.snapshot()
        if (snapshot.sessionId == PresentationState.NO_SESSION) return
        session.trySend(
            PresentationStateMessage(
                id = UUID.randomUUID(),
                sessionId = snapshot.sessionId,
                presentationId = snapshot.id.presentationId,
                revision = snapshot.revision,
                phase = snapshot.phase,
                transferId = snapshot.transferId,
            ),
        )
    }

    /**
     * Closes what is on screen at this device's own request, and tells the peer.
     *
     * The local close happens first and unconditionally: if the link is down the user still gets
     * their screen back immediately, and the peer finds out at the next reconciliation.
     */
    fun dismissLocally(): Boolean {
        val snapshot = presentation.dismissLocally() ?: return false
        diagnostics.info("RD/Presentation", "closed locally -> ${snapshot.id.shortLabel()}")
        engine.activeSession.value?.let { reportState(it) }
        return true
    }

    /**
     * Applies a command exactly once and acknowledges it either way.
     *
     * A reconnect can replay a command the controller is not sure landed; acknowledging without
     * re-applying is what makes that safe.
     */
    private fun applyOnce(session: RelaySession, id: UUID, apply: () -> Boolean) {
        if (!session.claimCommand(id)) {
            diagnostics.debug("router", "duplicate command ignored")
            session.acknowledge(id, ok = true)
            return
        }
        val ok = apply()
        session.acknowledge(id, ok, if (ok) ProtocolErrorCode.UNKNOWN else ProtocolErrorCode.UNSUPPORTED_FORMAT)
    }

    private fun onOffer(session: RelaySession, offer: ContentOffer) {
        if (inbound != null) {
            session.trySend(ContentReject(UUID.randomUUID(), offer.transferId, ProtocolErrorCode.BUSY))
            return
        }
        // A generic file needs the user's consent, and that consent is given once for a whole
        // batch. Without an accepted batch behind it there is nothing to consent to, so the offer
        // is refused rather than written to disk: this is what stops a paired-but-misbehaving peer
        // pushing files onto the phone with no interaction at all. Images and PDFs are unaffected;
        // they are a presentation the user is already watching.
        if (offer.kind == ContentKind.FILE && !batchAccepted) {
            diagnostics.info("router", "file offer without an accepted batch refused")
            session.trySend(
                ContentReject(UUID.randomUUID(), offer.transferId, ProtocolErrorCode.PERMISSION_DENIED),
            )
            return
        }
        val receiver = TransferReceiver(cache)
        when (val outcome = receiver.evaluate(offer)) {
            is TransferOutcome.Rejected -> {
                diagnostics.info("router", "offer rejected: ${outcome.reason}")
                session.trySend(ContentReject(UUID.randomUUID(), offer.transferId, outcome.code))
                _receiveState.value = ReceiveState.Failed(outcome.reason)
            }
            else -> {
                inbound = receiver
                inboundTransferId = offer.transferId
                _receiveState.value = ReceiveState.Receiving(
                    displayName = FilenameSanitizer.sanitize(offer.displayName),
                    receivedBytes = 0,
                    totalBytes = offer.sizeBytes,
                )
                session.trySend(ContentAccept(UUID.randomUUID(), offer.transferId))
            }
        }
    }

    private fun onTransferStart(session: RelaySession, start: TransferStart) {
        val receiver = inbound ?: return
        val outcome = receiver.begin(start)
        if (outcome is TransferOutcome.Rejected) {
            // Logged, because the sender only learns "the transfer was cancelled" and this side is
            // the only one that knows which rule was broken. Diagnosing a failed transfer without
            // this meant guessing.
            diagnostics.warn("router", "transfer start refused: ${outcome.reason} (${outcome.code})")
            session.trySend(TransferCancel(UUID.randomUUID(), start.transferId, outcome.code))
            abortInbound(outcome.reason)
        }
    }

    private fun onChunk(session: RelaySession, chunk: TransferChunk) {
        val receiver = inbound ?: return
        when (val outcome = receiver.accept(chunk)) {
            is TransferOutcome.Rejected -> {
                diagnostics.warn(
                    "router",
                    "chunk refused at ${receiver.bytesReceived}/${receiver.bytesExpected}B: " +
                        "${outcome.reason} (${outcome.code})",
                )
                session.trySend(TransferCancel(UUID.randomUUID(), chunk.transferId, outcome.code))
                abortInbound(outcome.reason)
            }
            else -> {
                val current = _receiveState.value
                if (current is ReceiveState.Receiving) {
                    _receiveState.value = current.copy(receivedBytes = receiver.bytesReceived)
                }
            }
        }
    }

    private fun onTransferComplete(session: RelaySession, complete: TransferComplete) {
        val receiver = inbound ?: return
        when (val outcome = receiver.finish(complete)) {
            is TransferOutcome.Finished -> {
                completed[complete.transferId] = outcome
                inbound = null
                inboundTransferId = null
                _receiveState.value = ReceiveState.Idle
                session.acknowledge(complete.id, ok = true)
                diagnostics.info("router", "received ${outcome.kind} (${outcome.file.length()}B)")
            }
            is TransferOutcome.Rejected -> {
                // The sender only ever sees "the display could not verify the file". This side is
                // the only one that knows whether that was a byte-count mismatch, a digest
                // mismatch or a storage failure, so it has to say.
                diagnostics.warn(
                    "router",
                    "verification failed after ${receiver.bytesReceived}/${receiver.bytesExpected}B: " +
                        "${outcome.reason} (${outcome.code})",
                )
                session.acknowledge(complete.id, ok = false, code = outcome.code)
                abortInbound(outcome.reason)
            }
            else -> Unit
        }
    }

    /** Files that finished verification, waiting for the controller to ask for them to be shown. */
    private val completed = object : LinkedHashMap<UUID, TransferOutcome.Finished>(8, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, TransferOutcome.Finished>?): Boolean =
            size > ContentLimits.MAX_CONCURRENT_TRANSFERS + 3
    }

    private fun showReceivedFile(currentSession: String, message: ShowFile): PresentationSnapshot? {
        val finished = completed[message.transferId] ?: return null
        presentation.updateOptions { it.copy(fitMode = message.fitMode) }
        return when (message.kind) {
            // A generic file is not a presentation: nothing is drawn on the companion's screen
            // for it. It lands in the received-files list instead, which is why there is no
            // presentation snapshot to return here.
            ContentKind.FILE -> null
            ContentKind.IMAGE -> presentation.showImage(
                currentSession, message.envelope, message.transferId, finished.file, finished.displayName,
            )
            ContentKind.PDF -> {
                val pages = pdfPageCounter(finished.file)
                if (pages <= 0) {
                    null
                } else {
                    presentation.showPdf(
                        currentSession, message.envelope, message.transferId,
                        finished.file, finished.displayName, pages,
                    )
                }
            }
        }
    }

    // -- generic file batches (display side) ----------------------------------------------

    private val _incomingBatch = MutableStateFlow<IncomingBatch?>(null)

    /** A batch awaiting this user's decision, or null. Observed by the display's UI. */
    val incomingBatch: StateFlow<IncomingBatch?> = _incomingBatch.asStateFlow()

    private val _receivedFiles = MutableStateFlow<List<ReceivedFile>>(emptyList())

    /** Files that arrived complete and verified, newest first. */
    val receivedFiles: StateFlow<List<ReceivedFile>> = _receivedFiles.asStateFlow()

    /**
     * Whether a batch offer has been accepted and its files may now be received.
     *
     * A single flag rather than a set of batch ids because the protocol allows one transfer at a
     * time: there is never a second accepted batch to keep track of. Cleared when the batch
     * settles, when the user rejects, and whenever the session ends, so consent never outlives
     * the connection it was given on.
     */
    @Volatile
    private var batchAccepted = false

    private var acceptedBatchId: UUID? = null
    private var expectedBatchFiles = 0
    private var receivedBatchFiles = 0

    /** Re-reads the received-files list from disk. Cheap: a directory listing. */
    fun refreshReceivedFiles() {
        scope.launch(Dispatchers.IO) {
            _receivedFiles.value = cache.receivedFiles()
        }
    }

    private fun onBatchOffer(session: RelaySession, offer: FileBatchOffer) {
        // Decided but unanswered, or a transfer already running: one at a time.
        if (_incomingBatch.value != null || batchAccepted || inbound != null) {
            session.trySend(FileBatchReject(UUID.randomUUID(), offer.batchId, ProtocolErrorCode.BUSY))
            return
        }

        val verdicts = offer.files.map {
            FileTransferPolicy.validateFileMetadata(it.displayName, it.mimeType, it.sizeBytes)
        }
        val invalid = verdicts.filterIsInstance<FileTransferPolicy.MetadataVerdict.Invalid>()
        if (invalid.isNotEmpty()) {
            diagnostics.info("router", "batch refused: ${invalid.first().reason}")
            session.trySend(
                FileBatchReject(UUID.randomUUID(), offer.batchId, ProtocolErrorCode.UNSUPPORTED_FORMAT),
            )
            return
        }
        val valid = verdicts.filterIsInstance<FileTransferPolicy.MetadataVerdict.Valid>()
        when (val verdict = FileTransferPolicy.validateBatch(valid)) {
            is FileTransferPolicy.BatchVerdict.Invalid -> {
                diagnostics.info("router", "batch refused: ${verdict.reason}")
                session.trySend(
                    FileBatchReject(UUID.randomUUID(), offer.batchId, ProtocolErrorCode.TOO_LARGE),
                )
                return
            }
            is FileTransferPolicy.BatchVerdict.Valid -> {
                // Space for the whole batch plus headroom, checked before the user is asked, so
                // an accept cannot be followed by a storage refusal half way through.
                val needed = verdict.knownTotalBytes
                if (cache.usableSpaceBytes() - ContentCache.STORAGE_HEADROOM_BYTES < needed) {
                    session.trySend(
                        FileBatchReject(
                            UUID.randomUUID(),
                            offer.batchId,
                            ProtocolErrorCode.INSUFFICIENT_STORAGE,
                        ),
                    )
                    _receiveState.value = ReceiveState.Failed("not enough space for those files")
                    return
                }
                _incomingBatch.value = IncomingBatch(
                    batchId = offer.batchId,
                    senderName = FilenameSanitizer.sanitize(offer.senderName),
                    files = valid.map {
                        IncomingFile(
                            displayName = it.safeName,
                            mimeType = it.mimeType,
                            sizeBytes = it.sizeBytes,
                        )
                    },
                )
                diagnostics.info("router", "batch offered: ${valid.size} files")
            }
        }
    }

    /**
     * A generic file finished arriving and was stored.
     *
     * Also the point where batch consent expires: once every file the batch promised has landed,
     * the acceptance is spent. Leaving it set would let a peer send a twenty-first file after a
     * twenty-file batch without asking again.
     */
    private fun onGenericFileStored(transferId: UUID) {
        if (completed[transferId] == null) return
        receivedBatchFiles++
        if (expectedBatchFiles in 1..receivedBatchFiles) {
            diagnostics.info("router", "batch complete: $receivedBatchFiles files")
            clearBatchAcceptance()
        }
        refreshReceivedFiles()
    }

    /** The user accepted the whole batch. */
    fun acceptIncomingBatch() {
        val pending = _incomingBatch.value ?: return
        val session = engine.activeSession.value
        if (session == null) {
            _incomingBatch.value = null
            return
        }
        batchAccepted = true
        acceptedBatchId = pending.batchId
        expectedBatchFiles = pending.files.size
        receivedBatchFiles = 0
        _incomingBatch.value = null
        session.trySend(FileBatchAccept(UUID.randomUUID(), pending.batchId))
        diagnostics.info("router", "batch accepted: ${pending.files.size} files")
    }

    /**
     * The user rejected the batch, or dismissed the prompt.
     *
     * Dismissing is a rejection on purpose: the alternative is leaving the sender waiting on a
     * decision that will never come, and a prompt that does nothing when you press back is worse
     * than one that defaults to "no".
     */
    fun rejectIncomingBatch() {
        val pending = _incomingBatch.value ?: return
        _incomingBatch.value = null
        clearBatchAcceptance()
        engine.activeSession.value?.trySend(
            FileBatchReject(UUID.randomUUID(), pending.batchId, ProtocolErrorCode.PERMISSION_DENIED),
        )
        diagnostics.info("router", "batch rejected by the user")
    }

    private fun clearBatchAcceptance() {
        batchAccepted = false
        acceptedBatchId = null
        expectedBatchFiles = 0
        receivedBatchFiles = 0
    }

    /**
     * Deletes one received file.
     *
     * Removes the bytes and the sidecar; there is no trash to recover it from, which is why the UI
     * confirms first.
     */
    fun deleteReceivedFile(transferId: UUID) {
        scope.launch(Dispatchers.IO) {
            cache.deleteReceived(transferId)
            _receivedFiles.value = cache.receivedFiles()
        }
    }

    /**
     * Drops any in-flight inbound transfer without reporting a failure the user did not cause.
     *
     * Used on a clean session end, where "the connection ended" is already visible in the
     * connection state and a second error banner would only add noise. Still deletes the partial:
     * [TransferReceiver.cancel] removes it, and an unverified partial is never worth keeping.
     */
    private fun abortInboundQuietly(reason: String) {
        if (inbound == null) return
        diagnostics.info("router", "inbound transfer dropped: $reason")
        inbound?.cancel()
        inbound = null
        inboundTransferId = null
        _receiveState.value = ReceiveState.Idle
    }

    private fun abortInbound(reason: String) {
        inbound?.cancel()
        inbound = null
        inboundTransferId = null
        _receiveState.value = ReceiveState.Failed(reason)
    }

    // -- controller side ------------------------------------------------------------------

    private fun handleAsController(message: RelayMessage) {
        when (message) {
            is ContentAccept -> pendingAccepts.remove(message.transferId)?.complete(true)
            is ContentReject -> pendingAccepts.remove(message.transferId)?.complete(false)

            // The user on the other phone answered a batch offer.
            is FileBatchAccept -> pendingBatchDecisions.remove(message.batchId)?.complete(true)
            is FileBatchReject -> pendingBatchDecisions.remove(message.batchId)?.complete(false)
            is TransferCancel -> {
                pendingAccepts.remove(message.transferId)?.complete(false)
                outboundCancelled = true
                _sendState.value = SendState.Failed("transfer", message.errorCode.name)
            }
            is Ack -> pendingAcks.remove(message.refId)?.complete(message.ok)

            // The Display's authoritative report. This is the only thing allowed to move the
            // Controller's belief about the remote screen.
            is PresentationStateMessage -> {
                val report = PresentationSnapshot(
                    id = PresentationId(message.sessionId, message.presentationId, message.revision),
                    phase = message.phase,
                    transferId = message.transferId,
                )
                val accepted = remote.onRemoteState(report)
                diagnostics.debug(
                    "RD/Presentation",
                    if (accepted) {
                        "remote -> ${message.phase} ${report.id.shortLabel()}"
                    } else {
                        "stale remote report dropped rev=${message.revision}"
                    },
                )
                if (accepted) stopMirrorIfDisplayMovedOn(message.phase)
            }

            // The Display closed something and told us. Same path as any other report.
            is PresentationDismiss -> {
                val report = PresentationSnapshot(
                    id = PresentationId(message.sessionId, message.presentationId, message.revision),
                    phase = PresentationPhase.NO_PRESENTATION,
                )
                if (remote.onRemoteState(report)) {
                    stopMirrorIfDisplayMovedOn(PresentationPhase.NO_PRESENTATION)
                }
            }

            // The Display asked what we think; a Controller shows nothing, so answer plainly.
            is PresentationSyncRequest -> Unit

            // The display lost sync and needs a fresh keyframe to start decoding again.
            is MirrorKeyframeRequest -> mirrorController?.onKeyFrameRequested()
            is ErrorMessage -> diagnostics.warn("RD/Protocol", "display reported ${message.errorCode}")
            else -> Unit
        }
    }

    fun sendText(text: String): Boolean = sendPresentation(OutgoingKind.TEXT) { session, envelope ->
        val bounded = text.take(ContentLimits.MAX_TEXT_BYTES / 4)
        session.trySend(ShowText(UUID.randomUUID(), bounded, envelope))
    }

    fun sendQr(payload: String, caption: String?): Boolean =
        sendPresentation(OutgoingKind.QR) { session, envelope ->
            session.trySend(ShowQr(UUID.randomUUID(), payload, caption, envelope))
        }

    fun sendLink(url: String, title: String?): Boolean {
        if (!UrlValidation.isAcceptable(url)) return false
        return sendPresentation(OutgoingKind.LINK) { session, envelope ->
            session.trySend(ShowLink(UUID.randomUUID(), url, title, envelope))
        }
    }

    /**
     * Sends content under a freshly allocated presentation identity.
     *
     * The status only reaches "Sent" here. It reaches "Displayed" when, and only when, the
     * Display reports that same presentation id as rendered.
     */
    /**
     * Stops capturing when the Display is no longer showing the mirror.
     *
     * Found on hardware: closing the mirror on the Display returned it to its waiting screen, but
     * the Controller went on saying "Sharing at 596x1280, 24 fps" with the system recording
     * indicator still lit and `dumpsys media_projection` still listing an active capture. Nothing
     * connected the Display's report to the encoder, so the phone kept capturing the user's screen
     * for a viewer that had closed it.
     *
     * The trigger is any accepted phase that is not [PresentationPhase.MIRRORING], not just a
     * dismissal: the Display shows one thing at a time, so sending it text also ends the mirror,
     * and continuing to capture for something nobody is watching is the same problem either way.
     * Stopping sends MIRROR_STOP, which the Display treats as idempotent.
     */
    private fun stopMirrorIfDisplayMovedOn(phase: PresentationPhase) {
        if (phase == PresentationPhase.MIRRORING) return
        val controller = mirrorController ?: return
        if (!controller.isCapturing) return
        diagnostics.info("RD/Mirror", "display is $phase; stopping capture")
        // stop() suspends and this handler does not, so it goes on the router's own scope. The
        // service registered an onStopped callback, so this also tears down the foreground
        // service and releases the MediaProjection -- and with it the recording indicator.
        scope.launch { controller.stop("display closed the mirror") }
    }

    private fun sendPresentation(
        kind: OutgoingKind,
        send: (RelaySession, PresentationEnvelope?) -> Boolean,
    ): Boolean {
        val session = engine.activeSession.value ?: return false
        val envelope = remote.beginOutgoing(kind)
        val accepted = send(session, envelope)
        if (accepted) {
            remote.markSent()
            diagnostics.info(
                "RD/Presentation",
                "sent $kind rev=${envelope?.revision ?: -1}",
            )
        } else {
            remote.markFailed("the link is busy")
        }
        return accepted
    }

    /**
     * Asks the Display to close what it is showing.
     *
     * Named for what it does on the other device. Idempotent, and the Controller does not assume
     * success -- the status only changes when the Display reports back.
     */
    fun dismissRemotely(): Boolean {
        val session = engine.activeSession.value ?: return false
        val current = remote.state.value
        if (current.sessionId == PresentationState.NO_SESSION) return false
        val target = current.remote.id.presentationId
        val sent = session.trySend(
            PresentationDismiss(
                id = UUID.randomUUID(),
                sessionId = current.sessionId,
                presentationId = target,
                revision = remote.allocateDismissRevision(),
            ),
        )
        if (sent) diagnostics.info("RD/Presentation", "asked display to close")
        return sent
    }

    fun sendPresentCommand(command: PresentCommandType, intArg: Int = 0): Boolean =
        sendSimple { session -> session.trySend(PresentCommand(UUID.randomUUID(), command, intArg)) }

    fun sendPdfPage(transferId: UUID, pageIndex: Int): Boolean =
        sendSimple { session -> session.trySend(PdfPageCommand(UUID.randomUUID(), transferId, pageIndex)) }

    private inline fun sendSimple(block: (RelaySession) -> Boolean): Boolean {
        val session = engine.activeSession.value ?: return false
        return block(session)
    }

    fun cancelOutboundTransfer() {
        outboundCancelled = true
    }

    /**
     * Streams one file that the peer has already agreed to receive.
     *
     * Extracted so the presentation path ([sendFile]) and the generic batch path
     * ([sendFileBatch]) share one implementation of the actual protocol exchange. They differ
     * only in what they show the user, which is what the callbacks are for; duplicating the
     * chunk loop would mean fixing every streaming bug twice.
     *
     * [sizeBytes] and [digest] must come from [prepare]: they describe the bytes that will be
     * read, so a provider's inaccurate metadata cannot turn into a mid-transfer mismatch.
     */
    private suspend fun streamOneFile(
        session: RelaySession,
        source: ContentSource,
        sizeBytes: Long,
        digest: ByteArray,
        transferId: UUID,
        onProgress: (Long) -> Unit,
        onVerifying: () -> Unit,
    ): OneFileResult {
        val accepted = CompletableDeferred<Boolean>()
        pendingAccepts[transferId] = accepted
        try {
            session.send(
                ContentOffer(
                    id = UUID.randomUUID(),
                    transferId = transferId,
                    kind = source.kind,
                    sizeBytes = sizeBytes,
                    mimeType = source.mimeType,
                    displayName = source.displayName,
                    sha256 = digest,
                ),
            )
            val ok = withTimeoutOrNull(OFFER_TIMEOUT_MS) { accepted.await() } ?: false
            if (!ok) return OneFileResult.RejectedByPeer

            session.send(TransferStart(UUID.randomUUID(), transferId, sizeBytes, ContentLimits.CHUNK_BYTES))

            var sent = 0L
            var index = 0L
            withContext(Dispatchers.IO) {
                source.openStream().use { stream ->
                    val buffer = ByteArray(ContentLimits.CHUNK_BYTES)
                    while (true) {
                        if (outboundCancelled) throw IOException("cancelled")
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        // Suspending send: this is where backpressure actually happens. The
                        // chunk goes out on the BULK queue, so a slow display slows this loop
                        // instead of filling the heap, and never delays a heartbeat.
                        session.send(
                            TransferChunk(UUID.randomUUID(), transferId, index, buffer.copyOf(read)),
                        )
                        index++
                        sent += read
                        onProgress(sent)
                    }
                }
            }

            if (sent != sizeBytes) {
                // The stream changed between the preparation pass and this one.
                session.trySend(
                    TransferCancel(UUID.randomUUID(), transferId, ProtocolErrorCode.DECODE_FAILED),
                )
                return OneFileResult.Failed("the file changed while sending", retryable = true)
            }

            onVerifying()
            val acked = CompletableDeferred<Boolean>()
            val completeMessage = TransferComplete(UUID.randomUUID(), transferId, digest)
            pendingAcks[completeMessage.id] = acked
            session.send(completeMessage)
            val verified = withTimeoutOrNull(COMPLETE_TIMEOUT_MS) { acked.await() } ?: false
            pendingAcks.remove(completeMessage.id)

            return if (verified) {
                OneFileResult.Sent
            } else {
                OneFileResult.Failed("the display could not verify the file", retryable = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            session.trySend(TransferCancel(UUID.randomUUID(), transferId, ProtocolErrorCode.CANCELLED))
            return if (outboundCancelled) {
                OneFileResult.Failed("cancelled", retryable = true)
            } else {
                OneFileResult.Failed("could not read the file", retryable = true)
            }
        } finally {
            pendingAccepts.remove(transferId)
        }
    }

    private sealed interface OneFileResult {
        data object Sent : OneFileResult
        data object RejectedByPeer : OneFileResult
        data class Failed(val reason: String, val retryable: Boolean) : OneFileResult
    }

    /**
     * Sends a file and shows it on the Display, start to finish.
     *
     * The presentation path: images and PDFs, one at a time, ending in a SHOW_FILE. Generic files
     * go through [sendFileBatch] instead, which asks the user on the other phone first.
     */
    fun sendFile(source: ContentSource, fitMode: FitMode) {
        scope.launch {
            val transferId = UUID.randomUUID()
            outboundCancelled = false
            _sendState.value = SendState.Preparing(source.displayName)
            val session = engine.activeSession.value
            if (session == null) {
                _sendState.value = SendState.Failed(source.displayName, "not connected")
                return@launch
            }

            // Measured, not declared: see prepare().
            val prepared = withContext(Dispatchers.IO) { source.prepare() }
            if (prepared is PrepareResult.Failed) {
                _sendState.value = SendState.Failed(
                    source.displayName,
                    when (prepared.error) {
                        ContentSourceError.TOO_LARGE -> "that file is larger than the 50 MB limit"
                        else -> "could not read the file"
                    },
                )
                return@launch
            }
            val ready = prepared as PrepareResult.Ready
            _sendState.value = SendState.Sending(source.displayName, 0, ready.sizeBytes)

            val result = streamOneFile(
                session = session,
                source = source,
                sizeBytes = ready.sizeBytes,
                digest = ready.sha256,
                transferId = transferId,
                onProgress = { sent ->
                    _sendState.value = SendState.Sending(source.displayName, sent, ready.sizeBytes)
                },
                onVerifying = {},
            )

            when (result) {
                OneFileResult.Sent -> {
                    session.send(ShowFile(UUID.randomUUID(), transferId, source.kind, fitMode))
                    _sendState.value = SendState.Complete(source.displayName)
                    diagnostics.info("router", "sent ${source.kind} (${ready.sizeBytes}B)")
                }
                OneFileResult.RejectedByPeer ->
                    _sendState.value = SendState.Failed(source.displayName, "the display refused the file")
                is OneFileResult.Failed ->
                    _sendState.value = SendState.Failed(source.displayName, result.reason)
            }
        }
    }

    // -- generic file batches (controller side) -------------------------------------------

    private val _fileBatch = MutableStateFlow<FileBatchState?>(null)

    /** The current multi-file send, or null when there is none. Observed by the send screen. */
    val fileBatch: StateFlow<FileBatchState?> = _fileBatch.asStateFlow()

    /** The sources behind [_fileBatch], kept so a retry can re-read only the files that failed. */
    private var batchSources: List<ContentSource> = emptyList()

    @Volatile
    private var batchJob: kotlinx.coroutines.Job? = null

    /**
     * Offers a set of files to the peer and, if the user there accepts, sends them in order.
     *
     * Returns null when the send started, or the reason it could not. Refusing here rather than
     * starting and failing is the point: a peer without `file-v1` would otherwise receive a
     * FILE_BATCH_OFFER it cannot parse and drop the session over it.
     */
    fun sendFileBatch(sources: List<ContentSource>): FileTransferPolicy.SendRefusal? {
        val session = engine.activeSession.value ?: return FileTransferPolicy.SendRefusal.NotConnected
        if (!session.outcome.peerCapabilities.contains(Capabilities.FILE_V1)) {
            return FileTransferPolicy.SendRefusal.PeerTooOld
        }
        if (batchJob?.isActive == true) {
            return FileTransferPolicy.SendRefusal.Rejected("a batch is already being sent")
        }
        if (sources.isEmpty()) return FileTransferPolicy.SendRefusal.Rejected("no files selected")
        if (sources.size > FileTransferPolicy.MAX_FILES_PER_BATCH) {
            return FileTransferPolicy.SendRefusal.Rejected(
                "at most ${FileTransferPolicy.MAX_FILES_PER_BATCH} files at a time",
            )
        }

        outboundCancelled = false
        batchSources = sources
        val batchId = UUID.randomUUID()
        _fileBatch.value = FileBatchState(
            batchId = batchId,
            files = sources.map {
                FileProgress(name = it.displayName, mimeType = it.mimeType, declaredSize = it.sizeBytes)
            },
        )
        batchJob = scope.launch { runBatch(session, batchId, sources.indices.toList()) }
        return null
    }

    /**
     * Retries only the files that failed retryably.
     *
     * "Retry" means retry the batch, not resend it: a file the peer already verified and wrote is
     * not sent again. Each retried file restarts from its first byte, because the protocol has no
     * resume and pretending otherwise would corrupt a file rather than continue it.
     */
    fun retryFileBatch(): FileTransferPolicy.SendRefusal? {
        val current = _fileBatch.value ?: return FileTransferPolicy.SendRefusal.Rejected("nothing to retry")
        if (!current.canRetry) return FileTransferPolicy.SendRefusal.Rejected("nothing to retry")
        val session = engine.activeSession.value ?: return FileTransferPolicy.SendRefusal.NotConnected
        if (batchJob?.isActive == true) {
            return FileTransferPolicy.SendRefusal.Rejected("a batch is already being sent")
        }

        val indices = current.retryableIndices
        outboundCancelled = false
        // A fresh batch id: this is a new offer, and reusing the old one would make the peer's
        // duplicate suppression treat it as a repeat of a decision already made.
        val batchId = UUID.randomUUID()
        _fileBatch.value = current.copy(
            batchId = batchId,
            cancelled = false,
            files = current.files.mapIndexed { index, file ->
                if (index in indices) {
                    file.copy(phase = FilePhase.Waiting, bytesTransferred = 0)
                } else {
                    file
                }
            },
        )
        batchJob = scope.launch { runBatch(session, batchId, indices) }
        return null
    }

    /**
     * Marks an interrupted batch failed when the session ends.
     *
     * Retryable, and deliberately so: the link dropping is exactly the case a retry button is for.
     * Files the peer already verified stay complete -- a disconnect does not un-send what is
     * already written on the other phone -- and a retry re-offers only the unfinished ones, each
     * from its first byte, because the protocol has no resume.
     *
     * Reconnecting needs no re-pairing: trust is stored per peer and survives the session, so the
     * retry works as soon as the connection is back.
     */
    private fun failBatchOnSessionEnd() {
        batchJob?.cancel()
        pendingBatchDecisions.values.forEach { it.complete(false) }
        pendingBatchDecisions.clear()
        val current = _fileBatch.value ?: return
        if (current.settled) return
        _fileBatch.value = current.copy(
            files = current.files.map { file ->
                if (file.phase.terminal) {
                    file
                } else {
                    file.copy(phase = FilePhase.Failed("the connection ended", retryable = true))
                }
            },
        )
    }

    /** Cancels the batch in flight. Files already verified by the peer stay sent. */
    fun cancelFileBatch() {
        outboundCancelled = true
        batchJob?.cancel()
        _fileBatch.value = _fileBatch.value?.cancelAll()
    }

    fun clearFileBatch() {
        if (batchJob?.isActive == true) return
        _fileBatch.value = null
        batchSources = emptyList()
    }

    /**
     * The batch driver: prepare, offer, wait for the user on the other phone, then stream in order.
     *
     * Strictly sequential. The protocol allows one bulk transfer at a time
     * ([ContentLimits.MAX_CONCURRENT_TRANSFERS]) and the receiver enforces it by rejecting a
     * second offer with BUSY, so concurrency here would produce rejections, not speed.
     */
    private suspend fun runBatch(session: RelaySession, batchId: UUID, indices: List<Int>) {
        fun update(block: (FileBatchState) -> FileBatchState) {
            _fileBatch.value = _fileBatch.value?.let(block)
        }

        // 1. Measure and digest every file first, so the offer carries real sizes and the user on
        //    the other phone is shown a total that will not change.
        val prepared = LinkedHashMap<Int, PrepareResult.Ready>()
        for (index in indices) {
            if (outboundCancelled) return
            val source = batchSources.getOrNull(index) ?: continue
            when (val result = withContext(Dispatchers.IO) { source.prepare() }) {
                is PrepareResult.Ready -> {
                    prepared[index] = result
                    // Replace the provider's guess with the measured length.
                    update {
                        it.copy(
                            files = it.files.mapIndexed { i, f ->
                                if (i == index) f.copy(declaredSize = result.sizeBytes) else f
                            },
                        )
                    }
                }
                is PrepareResult.Failed -> update {
                    it.withPhase(
                        index,
                        FilePhase.Failed(
                            reason = when (result.error) {
                                ContentSourceError.TOO_LARGE -> "larger than the 50 MB limit"
                                else -> "could not be read"
                            },
                            // Not retryable: re-reading gives the same answer. The user needs to
                            // pick a different file, and a retry button that cannot work is worse
                            // than no button.
                            retryable = false,
                        ),
                    )
                }
            }
        }
        if (prepared.isEmpty()) {
            diagnostics.info("router", "batch had no readable files")
            return
        }

        // 2. One confirmation for the whole selection.
        val manifest = prepared.map { (index, ready) ->
            val source = batchSources[index]
            FileManifestEntry(
                displayName = source.displayName,
                mimeType = source.mimeType,
                sizeBytes = ready.sizeBytes,
            )
        }
        val decision = CompletableDeferred<Boolean>()
        pendingBatchDecisions[batchId] = decision
        try {
            session.send(
                FileBatchOffer(
                    id = UUID.randomUUID(),
                    batchId = batchId,
                    senderName = localDeviceName(),
                    files = manifest,
                ),
            )
            val accepted = withTimeoutOrNull(BATCH_DECISION_TIMEOUT_MS) { decision.await() } ?: false
            if (!accepted) {
                update { state ->
                    state.copy(
                        files = state.files.mapIndexed { i, f ->
                            if (i in prepared.keys && !f.phase.terminal) f.copy(phase = FilePhase.Rejected) else f
                        },
                    )
                }
                diagnostics.info("router", "batch declined by the display")
                return
            }
        } finally {
            pendingBatchDecisions.remove(batchId)
        }

        update { state ->
            state.copy(
                files = state.files.mapIndexed { i, f ->
                    if (i in prepared.keys && !f.phase.terminal) f.copy(phase = FilePhase.Accepted) else f
                },
            )
        }

        // 3. Stream them in order.
        for ((index, ready) in prepared) {
            if (outboundCancelled) {
                update { it.cancelAll() }
                return
            }
            val source = batchSources[index]
            val transferId = UUID.randomUUID()
            update { it.withPhase(index, FilePhase.Sending).withProgress(index, 0) }

            val result = streamOneFile(
                session = session,
                source = source,
                sizeBytes = ready.sizeBytes,
                digest = ready.sha256,
                transferId = transferId,
                onProgress = { sent -> update { it.withProgress(index, sent) } },
                onVerifying = { update { it.withPhase(index, FilePhase.Verifying) } },
            )

            when (result) {
                OneFileResult.Sent -> {
                    // Tells the Display to file it away. A generic file draws nothing on screen;
                    // this is what moves it into the received-files list over there.
                    session.send(
                        ShowFile(UUID.randomUUID(), transferId, ContentKind.FILE, FitMode.DEFAULT),
                    )
                    update { it.withPhase(index, FilePhase.Complete).withProgress(index, ready.sizeBytes) }
                }
                OneFileResult.RejectedByPeer ->
                    update { it.withPhase(index, FilePhase.Rejected) }
                is OneFileResult.Failed -> {
                    update {
                        it.withPhase(index, FilePhase.Failed(result.reason, result.retryable))
                    }
                    // A cancellation stops the batch; one unreadable file does not.
                    if (outboundCancelled) {
                        update { it.cancelAll() }
                        return
                    }
                }
            }
        }
        val settled = _fileBatch.value
        diagnostics.info(
            "router",
            "batch done: ${settled?.completedCount}/${settled?.total} sent",
        )
    }

    /** Waiters for the Display's decision on a batch, keyed by batch id. */
    private val pendingBatchDecisions = mutableMapOf<UUID, CompletableDeferred<Boolean>>()

    /**
     * Our own name, as the peer should display it.
     *
     * Cached from settings rather than read on demand: `settings` is a DataStore-backed Flow, so
     * reading it at send time would mean suspending on disk in the middle of the batch handshake.
     */
    @Volatile
    private var localName: String = ""

    private fun localDeviceName(): String = localName.ifBlank { "The other phone" }

    fun clearSendState() {
        _sendState.value = SendState.Idle
    }

    private companion object {
        const val OFFER_TIMEOUT_MS = 15_000L
        const val COMPLETE_TIMEOUT_MS = 30_000L

        /**
         * How long to wait for the user on the other phone to answer a batch offer.
         *
         * Much longer than the protocol timeouts either side of it, because this one is waiting on
         * a person picking up a phone, not on a network round trip.
         */
        const val BATCH_DECISION_TIMEOUT_MS = 120_000L
    }
}
