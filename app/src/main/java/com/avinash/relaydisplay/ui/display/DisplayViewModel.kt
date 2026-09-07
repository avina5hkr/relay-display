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
) : ViewModel() {

    val uiState: StateFlow<DisplayUiState> = combine(
        settingsRepository.settings,
        sessionCoordinator.state,
        trustedPeerRepository.trustedPeer,
        router.incomingBatch,
        router.receivedFiles,
    ) { values ->
        DisplayUiState(
            settings = values[0] as RelaySettings,
            connection = values[1] as ConnectionState,
            trustedPeer = values[2] as TrustedPeer?,
            localAddress = AndroidPlatformCapabilities.localIpv4Address(),
            networkLabel = platform.activeTransportLabel(),
            incomingBatch = values[3] as IncomingBatch?,
            receivedFiles = @Suppress("UNCHECKED_CAST") (values[4] as List<ReceivedFile>),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DisplayUiState())

    init {
        // The list is on disk, so it survives a restart and has to be read back rather than
        // assumed empty.
        router.refreshReceivedFiles()
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
        fun create(container: AppContainer) = DisplayViewModel(
            settingsRepository = container.settingsRepository,
            trustedPeerRepository = container.trustedPeerRepository,
            sessionCoordinator = container.sessionCoordinator,
            platform = container.platformCapabilities,
            router = container.contentRouter,
        )
    }
}
