package com.avinash.relaydisplay.ui.controller

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avinash.relaydisplay.app.AppContainer
import com.avinash.relaydisplay.content.ContentRouter
import com.avinash.relaydisplay.content.FileBatchState
import com.avinash.relaydisplay.content.FileTransferPolicy
import com.avinash.relaydisplay.protocol.Capabilities
import com.avinash.relaydisplay.content.ContentSource
import com.avinash.relaydisplay.content.ContentSourceError
import com.avinash.relaydisplay.content.ContentSourceResult
import com.avinash.relaydisplay.content.QrEncodeError
import com.avinash.relaydisplay.content.QrEncodeResult
import com.avinash.relaydisplay.content.QrEncoder
import com.avinash.relaydisplay.content.QrWarning
import com.avinash.relaydisplay.content.RemotePresentationState
import com.avinash.relaydisplay.content.SendState
import com.avinash.relaydisplay.content.UriContentSource
import com.avinash.relaydisplay.content.UrlValidation
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.domain.model.BRIGHTNESS_SYSTEM_DEFAULT
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.domain.model.PresentCommandType
import com.avinash.relaydisplay.domain.model.RemoteContentStatus
import com.avinash.relaydisplay.network.session.ConnectionState
import com.avinash.relaydisplay.network.session.SessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SendUiState(
    val connection: ConnectionState = ConnectionState.Idle,
    val sendState: SendState = SendState.Idle,
    /** What the Display has actually reported. Never inferred from what we sent. */
    val remote: RemotePresentationState = RemotePresentationState(),
    val draft: String = "",
    val qrWarning: String? = null,
    val qrError: String? = null,
    val linkError: String? = null,
    val fileError: String? = null,
    val notice: String? = null,
    val defaultFitMode: FitMode = FitMode.DEFAULT,
    /** Files chosen but not yet offered. The review list. */
    val picked: List<PickedFile> = emptyList(),
    /** The multi-file send in flight, or null. */
    val fileBatch: FileBatchState? = null,
    val batchError: String? = null,
) {
    val connected: Boolean get() = connection.isConnected

    /**
     * Whether the other phone announced generic file transfer.
     *
     * Drives whether the Files action is offered at all, so the refusal is visible before the
     * user picks twenty files rather than after.
     */
    val peerSupportsFiles: Boolean
        get() = (connection as? ConnectionState.Connected)
            ?.peer
            ?.capabilities
            ?.contains(Capabilities.FILE_V2) == true

    val canSendPicked: Boolean get() = connected && picked.isNotEmpty() && fileBatch == null

    /** Total of the selection, or null when any provider would not report a size. */
    val pickedTotalBytes: Long?
        get() = if (picked.any { it.sizeBytes == FileTransferPolicy.SIZE_UNKNOWN }) {
            null
        } else {
            picked.sumOf { it.sizeBytes }
        }
    val canSend: Boolean get() = connected && draft.isNotBlank()

    /** Only offered when the Display says it actually has something on screen. */
    val canCloseOnDisplay: Boolean get() = connected && remote.canDismissRemotely

    /**
     * The one status line about the remote screen.
     *
     * Every branch reflects something the Display told us, except SENDING/SENT which are
     * explicitly worded as *our* side of the exchange.
     */
    val remoteStatusText: String?
        get() = when (remote.status) {
            RemoteContentStatus.IDLE -> null
            RemoteContentStatus.SENDING -> "Sending..."
            RemoteContentStatus.SENT -> "Sent. Waiting for the display to show it."
            RemoteContentStatus.DISPLAYED -> "Showing on the display."
            RemoteContentStatus.DISPLAY_CLOSED -> "The display closed it."
            RemoteContentStatus.DISCONNECTED -> "Disconnected before the display confirmed."
            RemoteContentStatus.FAILED -> remote.errorReason ?: "The display could not show it."
        }
}

/** One file in the review list, before anything is offered. */
data class PickedFile(
    val displayName: String,
    val mimeType: String,
    /** May be [FileTransferPolicy.SIZE_UNKNOWN]: the real size is measured at send time. */
    val sizeBytes: Long,
)

/**
 * The controller's send actions.
 *
 * Every action is disabled unless there is an authenticated live session, so the UI can never
 * offer a button that would silently do nothing.
 */
class SendViewModel(
    private val router: ContentRouter,
    private val coordinator: SessionCoordinator,
    settingsRepository: SettingsRepository,
    private val resolver: ContentResolver,
    private val diagnostics: DiagnosticsLog,
) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val messages = MutableStateFlow(Messages())
    private val picked = MutableStateFlow<List<PickedFile>>(emptyList())

    /**
     * The sources behind [picked], in the same order.
     *
     * Kept out of the UI state deliberately: a `ContentSource` holds a resolver and a URI, which
     * is machinery, not something a screen should be handed. The two lists are only ever mutated
     * together, which is why the removal helper below rebuilds both.
     */
    private var pickedSources: List<ContentSource> = emptyList()

    private data class Messages(
        val qrWarning: String? = null,
        val qrError: String? = null,
        val linkError: String? = null,
        val fileError: String? = null,
        val notice: String? = null,
        val batchError: String? = null,
    )

    val uiState: StateFlow<SendUiState> = combine(
        coordinator.state,
        router.sendState,
        draft,
        messages,
        settingsRepository.settings,
        router.remotePresentation,
        picked,
        router.fileBatch,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val connection = values[0] as ConnectionState
        @Suppress("UNCHECKED_CAST")
        val sendState = values[1] as SendState
        val text = values[2] as String
        val msg = values[3] as Messages
        val settings = values[4] as com.avinash.relaydisplay.data.settings.RelaySettings
        val remote = values[5] as RemotePresentationState
        @Suppress("UNCHECKED_CAST")
        val pickedFiles = values[6] as List<PickedFile>
        val batch = values[7] as FileBatchState?
        SendUiState(
            connection = connection,
            sendState = sendState,
            remote = remote,
            draft = text,
            qrWarning = msg.qrWarning,
            qrError = msg.qrError,
            linkError = msg.linkError,
            fileError = msg.fileError,
            notice = msg.notice,
            defaultFitMode = settings.defaultFitMode,
            picked = pickedFiles,
            fileBatch = batch,
            batchError = msg.batchError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SendUiState())

    fun setDraft(text: String) {
        draft.value = text.take(MAX_DRAFT_CHARS)
        // Re-check as they type so the warning appears before they tap Send, not after.
        messages.value = messages.value.copy(qrWarning = qrWarningFor(draft.value), qrError = null)
    }

    private fun qrWarningFor(text: String): String? {
        if (text.isBlank()) return null
        return when (val result = QrEncoder.encode(text)) {
            is QrEncodeResult.Success ->
                if (result.warning == QrWarning.DENSE) {
                    "This is long for a QR code. It will still work, but it may take the other " +
                        "camera a moment to lock on."
                } else {
                    null
                }
            is QrEncodeResult.Failure -> null
        }
    }

    fun sendAsText() {
        val text = draft.value
        if (text.isBlank()) return
        // No optimistic "Sent as text." here: the status line comes from the Display's report.
        if (!router.sendText(text)) {
            messages.value = messages.value.copy(notice = "Not connected.")
        } else {
            messages.value = Messages()
        }
    }

    fun sendAsQr() {
        val text = draft.value
        if (text.isBlank()) return
        when (val result = QrEncoder.encode(text)) {
            is QrEncodeResult.Failure -> {
                messages.value = messages.value.copy(
                    qrError = when (result.error) {
                        QrEncodeError.TOO_LARGE ->
                            "Too long for a QR code that would actually scan. Send it as text, " +
                                "or shorten it."
                        QrEncodeError.EMPTY -> "Nothing to encode."
                        QrEncodeError.ENCODER_FAILED -> "This text could not be turned into a QR code."
                    },
                )
            }
            is QrEncodeResult.Success -> {
                if (!router.sendQr(text, caption = null)) {
                    messages.value = messages.value.copy(notice = "Not connected.")
                } else {
                    messages.value = Messages()
                }
            }
        }
    }

    fun sendAsLink() {
        val url = draft.value.trim()
        when (UrlValidation.check(url)) {
            UrlValidation.Verdict.ALLOWED, UrlValidation.Verdict.ALLOWED_INSECURE -> {
                if (!router.sendLink(url, title = null)) {
                    messages.value = messages.value.copy(notice = "Not connected.")
                } else {
                    messages.value = Messages(
                        notice = if (url.startsWith("http://")) {
                            "That link is not encrypted (http)."
                        } else {
                            null
                        },
                    )
                }
            }
            UrlValidation.Verdict.REJECTED_SCHEME ->
                messages.value = messages.value.copy(
                    linkError = "Only web links (https, or http) can be sent.",
                )
            UrlValidation.Verdict.REJECTED_TOO_LONG ->
                messages.value = messages.value.copy(linkError = "That link is too long.")
            UrlValidation.Verdict.REJECTED_MALFORMED ->
                messages.value = messages.value.copy(
                    linkError = "That does not look like a web address.",
                )
        }
    }

    /** Sends a file the user picked, or explains exactly why it cannot be sent. */
    fun sendPickedFile(uri: Uri, fitMode: FitMode) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { UriContentSource.from(resolver, uri) }
            when (result) {
                is ContentSourceResult.Ready -> {
                    messages.value = Messages()
                    router.sendFile(result.source, fitMode)
                }
                is ContentSourceResult.Failed -> {
                    diagnostics.info("send", "picked file rejected: ${result.error}")
                    messages.value = messages.value.copy(
                        fileError = when (result.error) {
                            ContentSourceError.UNSUPPORTED_TYPE ->
                                "Only images and PDF documents can be sent."
                            ContentSourceError.TOO_LARGE ->
                                "That file is larger than the 50 MB limit."
                            ContentSourceError.EMPTY ->
                                "That file appears to be empty, or its app did not report a size."
                            ContentSourceError.UNREADABLE ->
                                "That file could not be read. Try picking it again."
                        },
                    )
                }
            }
        }
    }

    // -- generic files ---------------------------------------------------------------------

    /**
     * Resolves what the user picked into a review list.
     *
     * Nothing is sent here. The user sees names, types and sizes and confirms, because a
     * multi-select picker makes it very easy to select more, or other, files than intended.
     */
    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                uris.map { it to UriContentSource.forGenericFile(resolver, it) }
            }
            val sources = pickedSources.toMutableList()
            val display = picked.value.toMutableList()
            var rejected = 0
            var tooLarge = 0

            for ((_, result) in resolved) {
                when (result) {
                    is ContentSourceResult.Ready -> {
                        if (sources.size >= FileTransferPolicy.MAX_FILES_PER_BATCH) {
                            rejected++
                            continue
                        }
                        sources += result.source
                        display += PickedFile(
                            displayName = result.source.displayName,
                            mimeType = result.source.mimeType,
                            sizeBytes = result.source.sizeBytes,
                        )
                    }
                    is ContentSourceResult.Failed -> {
                        if (result.error == ContentSourceError.TOO_LARGE) tooLarge++ else rejected++
                    }
                }
            }

            pickedSources = sources
            picked.value = display
            messages.value = messages.value.copy(
                batchError = when {
                    tooLarge > 0 && rejected > 0 ->
                        "$tooLarge file(s) are over the 50 MB limit and $rejected could not be read " +
                            "or would exceed ${FileTransferPolicy.MAX_FILES_PER_BATCH} files."
                    tooLarge > 0 -> "$tooLarge file(s) are larger than the 50 MB limit."
                    rejected > 0 ->
                        "$rejected file(s) could not be read, or would take the batch over " +
                            "${FileTransferPolicy.MAX_FILES_PER_BATCH} files."
                    else -> null
                },
            )
        }
    }

    /** Drops one file from the review list. */
    fun removePickedFile(index: Int) {
        if (index !in pickedSources.indices) return
        pickedSources = pickedSources.filterIndexed { i, _ -> i != index }
        picked.value = picked.value.filterIndexed { i, _ -> i != index }
        messages.value = messages.value.copy(batchError = null)
    }

    /** Abandons the whole selection without sending it. */
    fun clearPickedFiles() {
        pickedSources = emptyList()
        picked.value = emptyList()
        messages.value = messages.value.copy(batchError = null)
    }

    /** Offers the selection to the other phone. */
    fun sendPickedFiles() {
        val sources = pickedSources
        if (sources.isEmpty()) return
        val refusal = router.sendFileBatch(sources)
        if (refusal == null) {
            // The review list has served its purpose; progress is reported from the batch state.
            pickedSources = emptyList()
            picked.value = emptyList()
            messages.value = Messages()
            return
        }
        diagnostics.info("send", "batch refused: $refusal")
        messages.value = messages.value.copy(
            batchError = when (refusal) {
                FileTransferPolicy.SendRefusal.PeerTooOld ->
                    "The other phone is running an older version of Relay Display that cannot " +
                        "receive files. Update it on both phones and try again."
                FileTransferPolicy.SendRefusal.NotConnected -> "Not connected."
                is FileTransferPolicy.SendRefusal.Rejected -> refusal.reason
            },
        )
    }

    fun cancelFileBatch() = router.cancelFileBatch()

    fun retryFileBatch() {
        val refusal = router.retryFileBatch()
        if (refusal != null) {
            messages.value = messages.value.copy(
                batchError = when (refusal) {
                    FileTransferPolicy.SendRefusal.PeerTooOld -> "The other phone cannot receive files."
                    FileTransferPolicy.SendRefusal.NotConnected -> "Not connected."
                    is FileTransferPolicy.SendRefusal.Rejected -> refusal.reason
                },
            )
        }
    }

    fun clearFileBatch() {
        router.clearFileBatch()
        messages.value = messages.value.copy(batchError = null)
    }

    fun cancelTransfer() {
        router.cancelOutboundTransfer()
    }

    fun clearTransferState() {
        router.clearSendState()
        messages.value = Messages()
    }

    fun blankDisplay() = presentCommand(PresentCommandType.BLANK, "Display blanked.")

    /**
     * Closes whatever the Display is showing.
     *
     * Goes through the dismissal protocol rather than a presentation command, so the Display
     * answers with an authoritative state report and this side does not have to guess.
     */
    fun closeOnDisplay() {
        messages.value = if (router.dismissRemotely()) {
            Messages()
        } else {
            messages.value.copy(notice = "Not connected.")
        }
    }
    fun setFitMode(mode: FitMode) = presentCommand(PresentCommandType.SET_FIT_MODE, "Scaling changed.", mode.wireCode)
    fun setImmersive(on: Boolean) =
        presentCommand(PresentCommandType.SET_IMMERSIVE, if (on) "Full screen on." else "Full screen off.", if (on) 1 else 0)
    fun setBrightness(percent: Int) = presentCommand(
        PresentCommandType.SET_BRIGHTNESS,
        if (percent == BRIGHTNESS_SYSTEM_DEFAULT) "Using the display's own brightness." else "Brightness set.",
        percent,
    )

    private fun presentCommand(command: PresentCommandType, success: String, arg: Int = 0) {
        messages.value = if (router.sendPresentCommand(command, arg)) {
            Messages(notice = success)
        } else {
            messages.value.copy(notice = "Not connected.")
        }
    }

    companion object {
        const val MAX_DRAFT_CHARS = 4000

        fun create(container: AppContainer, resolver: ContentResolver) = SendViewModel(
            router = container.contentRouter,
            coordinator = container.sessionCoordinator,
            settingsRepository = container.settingsRepository,
            resolver = resolver,
            diagnostics = container.diagnostics,
        )
    }
}
