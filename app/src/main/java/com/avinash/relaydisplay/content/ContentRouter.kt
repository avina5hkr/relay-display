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
        // Off the main thread: this runs during application startup and touches the filesystem.
        scope.launch(Dispatchers.IO) { cache.sweepPartials() }

        // Bind presentation identity to the live session. Every session gets a fresh id, so a
        // message from a previous one can never mutate current state.
        scope.launch {
            engine.activeSession.collect { session ->
                val role = settingsRepository.settings.first { it.loaded }.role
                if (session == null) {
                    sessionId = PresentationState.NO_SESSION
                    presentation.onSessionEnded()
                    remote.onSessionEnded()
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

            is ContentOffer -> onOffer(session, message)
            is TransferStart -> onTransferStart(session, message)
            is TransferChunk -> onChunk(session, message)
            is TransferComplete -> onTransferComplete(session, message)
            is TransferCancel -> {
                diagnostics.info("RD/Presentation", "sender cancelled the transfer")
                abortInbound("cancelled by the sender")
            }

            is ShowFile -> applyContent(session, message.id, message.envelope) {
                showReceivedFile(currentSession, message)
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
            session.trySend(TransferCancel(UUID.randomUUID(), start.transferId, outcome.code))
            abortInbound(outcome.reason)
        }
    }

    private fun onChunk(session: RelaySession, chunk: TransferChunk) {
        val receiver = inbound ?: return
        when (val outcome = receiver.accept(chunk)) {
            is TransferOutcome.Rejected -> {
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
     * Sends a file and shows it, start to finish.
     *
     * Streams in bounded chunks and uses the session's suspending send, so a slow display slows
     * the sender down instead of filling this phone's heap.
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
            try {
                val digest = withContext(Dispatchers.IO) { source.computeSha256() }

                val accepted = CompletableDeferred<Boolean>()
                pendingAccepts[transferId] = accepted
                session.send(
                    ContentOffer(
                        id = UUID.randomUUID(),
                        transferId = transferId,
                        kind = source.kind,
                        sizeBytes = source.sizeBytes,
                        mimeType = source.mimeType,
                        displayName = source.displayName,
                        sha256 = digest,
                    ),
                )
                val ok = withTimeoutOrNull(OFFER_TIMEOUT_MS) { accepted.await() } ?: false
                pendingAccepts.remove(transferId)
                if (!ok) {
                    _sendState.value = SendState.Failed(source.displayName, "the display refused the file")
                    return@launch
                }

                session.send(
                    TransferStart(UUID.randomUUID(), transferId, source.sizeBytes, ContentLimits.CHUNK_BYTES),
                )
                _sendState.value = SendState.Sending(source.displayName, 0, source.sizeBytes)

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
                            // Suspending send: this is where backpressure actually happens.
                            session.send(
                                TransferChunk(UUID.randomUUID(), transferId, index, buffer.copyOf(read)),
                            )
                            index++
                            sent += read
                            _sendState.value = SendState.Sending(source.displayName, sent, source.sizeBytes)
                        }
                    }
                }

                if (sent != source.sizeBytes) {
                    // The provider gave fewer bytes than it advertised; the display would reject
                    // this anyway, so stop here with a clear reason.
                    session.trySend(
                        TransferCancel(UUID.randomUUID(), transferId, ProtocolErrorCode.DECODE_FAILED),
                    )
                    _sendState.value = SendState.Failed(source.displayName, "the file changed while sending")
                    return@launch
                }

                val acked = CompletableDeferred<Boolean>()
                val completeMessage = TransferComplete(UUID.randomUUID(), transferId, digest)
                pendingAcks[completeMessage.id] = acked
                session.send(completeMessage)
                val verified = withTimeoutOrNull(COMPLETE_TIMEOUT_MS) { acked.await() } ?: false
                pendingAcks.remove(completeMessage.id)

                if (!verified) {
                    _sendState.value = SendState.Failed(source.displayName, "the display could not verify the file")
                    return@launch
                }

                session.send(ShowFile(UUID.randomUUID(), transferId, source.kind, fitMode))
                _sendState.value = SendState.Complete(source.displayName)
                diagnostics.info("router", "sent ${source.kind} (${source.sizeBytes}B)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                session.trySend(TransferCancel(UUID.randomUUID(), transferId, ProtocolErrorCode.CANCELLED))
                _sendState.value = SendState.Failed(
                    source.displayName,
                    if (outboundCancelled) "cancelled" else "could not read the file",
                )
            } finally {
                pendingAccepts.remove(transferId)
            }
        }
    }

    fun clearSendState() {
        _sendState.value = SendState.Idle
    }

    private companion object {
        const val OFFER_TIMEOUT_MS = 15_000L
        const val COMPLETE_TIMEOUT_MS = 30_000L
    }
}
