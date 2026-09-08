package com.avinash.relaydisplay.ui.display

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avinash.relaydisplay.app.AppContainer
import com.avinash.relaydisplay.data.peers.TrustedPeerRepository
import com.avinash.relaydisplay.data.settings.RelaySettings
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.domain.model.OperatingMode
import com.avinash.relaydisplay.domain.model.TrustedPeer
import com.avinash.relaydisplay.network.session.ConnectionState
import com.avinash.relaydisplay.network.session.SessionCoordinator
import com.avinash.relaydisplay.platform.AndroidPlatformCapabilities
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.avinash.relaydisplay.content.ContentRouter
import com.avinash.relaydisplay.content.IncomingBatch
import com.avinash.relaydisplay.content.ReceivedFile
import com.avinash.relaydisplay.content.FileExporter
import com.avinash.relaydisplay.content.SaveState
import kotlinx.coroutines.flow.MutableStateFlow

data class DisplayUiState(
    val settings: RelaySettings = RelaySettings(),
    val connection: ConnectionState = ConnectionState.Idle,
    val trustedPeer: TrustedPeer? = null,
    val localAddress: String? = null,
    val networkLabel: String = "",
    /** A batch of files the controller has offered, awaiting this user's decision. */
    val incomingBatch: IncomingBatch? = null,
    /** Files that arrived complete and verified, newest first. */
    val receivedFiles: List<ReceivedFile> = emptyList(),
    /** Progress of a Save As, which runs off the main thread. */
    val saveState: SaveState = SaveState.Idle,
) {
    val paused: Boolean get() = settings.operatingMode == OperatingMode.PAUSED
    val available: Boolean get() = connection.isActive || connection.isConnected
}

class DisplayViewModel(
    private val settingsRepository: SettingsRepository,
    trustedPeerRepository: TrustedPeerRepository,
    private val sessionCoordinator: SessionCoordinator,
    private val platform: AndroidPlatformCapabilities,
    private val router: ContentRouter,
    private val resolver: android.content.ContentResolver,
    /**
     * Injected so the copy can be driven by a test dispatcher.
     *
     * The reason this class owns Save As at all is that the Compose callback used to do the copy
     * itself, on the main thread, for files up to 50 MiB.
     */
    private val exporter: FileExporter,
) : ViewModel() {

    private val saveState = MutableStateFlow<SaveState>(SaveState.Idle)

    val uiState: StateFlow<DisplayUiState> = combine(
        settingsRepository.settings,
        sessionCoordinator.state,
        trustedPeerRepository.trustedPeer,
        router.incomingBatch,
        router.receivedFiles,
        saveState,
    ) { values ->
        DisplayUiState(
            settings = values[0] as RelaySettings,
            connection = values[1] as ConnectionState,
            trustedPeer = values[2] as TrustedPeer?,
            localAddress = AndroidPlatformCapabilities.localIpv4Address(),
            networkLabel = platform.activeTransportLabel(),
            incomingBatch = values[3] as IncomingBatch?,
            receivedFiles = @Suppress("UNCHECKED_CAST") (values[4] as List<ReceivedFile>),
            saveState = values[5] as SaveState,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DisplayUiState())

    init {
        // The list is on disk, so it survives a restart and has to be read back rather than
        // assumed empty.
        router.refreshReceivedFiles()
    }

    /**
     * Copies a received file to a destination the user picked.
     *
     * Returns immediately; the work runs in [viewModelScope] on the exporter's IO dispatcher, and
     * progress is reported through [DisplayUiState.saveState]. A second request while one is
     * running is ignored rather than queued, so a double tap cannot start two copies into the
     * same document.
     */
    fun saveReceivedFile(file: ReceivedFile, destination: android.net.Uri) {
        if (saveState.value is SaveState.Saving) return
        saveState.value = SaveState.Saving(file.transferId, file.displayName)
        viewModelScope.launch {
            val result = try {
                exporter.export(
                    source = file.file.inputStream(),
                    expectedBytes = file.sizeBytes,
                    openDestination = { resolver.openOutputStream(destination) },
                    onCleanupDestination = {
                        // Best effort: a provider may refuse, and a failed cleanup must not turn
                        // into a second error on top of the one being reported.
                        runCatching { resolver.delete(destination, null, null) }
                    },
                )
            } catch (e: java.io.FileNotFoundException) {
                // The received file itself has gone: evicted, expired, or deleted from another
                // screen while the picker was open.
                FileExporter.ExportResult.Failed(FileExporter.ExportError.SOURCE_UNAVAILABLE)
            }
            saveState.value = when (result) {
                is FileExporter.ExportResult.Completed ->
                    SaveState.Completed(file.transferId, file.displayName)
                is FileExporter.ExportResult.Failed -> SaveState.Failed(
                    file.transferId,
                    file.displayName,
                    when (result.error) {
                        FileExporter.ExportError.DESTINATION_UNAVAILABLE ->
                            "That location could not be written to."
                        FileExporter.ExportError.WRITE_FAILED -> "That file could not be saved."
                        FileExporter.ExportError.INCOMPLETE ->
                            "Only part of the file was written, so it was not saved."
                        FileExporter.ExportError.SOURCE_UNAVAILABLE ->
                            "That file is no longer available on this phone."
                    },
                )
            }
        }
    }

    /** Dismisses a finished save message. */
    fun clearSaveState() {
        if (saveState.value !is SaveState.Saving) saveState.value = SaveState.Idle
    }

    fun acceptIncomingBatch() = router.acceptIncomingBatch()
    fun rejectIncomingBatch() = router.rejectIncomingBatch()
    fun deleteReceivedFile(transferId: java.util.UUID) = router.deleteReceivedFile(transferId)
    fun refreshReceivedFiles() = router.refreshReceivedFiles()

    fun makeAvailable() = sessionCoordinator.requestSession()
    fun stopBeingAvailable() = sessionCoordinator.releaseSession()

    fun setOperatingMode(mode: OperatingMode) {
        viewModelScope.launch {
            settingsRepository.setOperatingMode(mode)
            sessionCoordinator.onOperatingModeChanged(mode)
        }
    }

    companion object {
        fun create(
            container: AppContainer,
            resolver: android.content.ContentResolver,
        ) = DisplayViewModel(
            settingsRepository = container.settingsRepository,
            trustedPeerRepository = container.trustedPeerRepository,
            sessionCoordinator = container.sessionCoordinator,
            platform = container.platformCapabilities,
            router = container.contentRouter,
            resolver = resolver,
            exporter = FileExporter(io = kotlinx.coroutines.Dispatchers.IO),
        )
    }
}
