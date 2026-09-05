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

data class DisplayUiState(
    val settings: RelaySettings = RelaySettings(),
    val connection: ConnectionState = ConnectionState.Idle,
    val trustedPeer: TrustedPeer? = null,
    val localAddress: String? = null,
    val networkLabel: String = "",
) {
    val paused: Boolean get() = settings.operatingMode == OperatingMode.PAUSED
    val available: Boolean get() = connection.isActive || connection.isConnected
}

class DisplayViewModel(
    private val settingsRepository: SettingsRepository,
    trustedPeerRepository: TrustedPeerRepository,
    private val sessionCoordinator: SessionCoordinator,
    private val platform: AndroidPlatformCapabilities,
) : ViewModel() {

    val uiState: StateFlow<DisplayUiState> = combine(
        settingsRepository.settings,
        sessionCoordinator.state,
        trustedPeerRepository.trustedPeer,
    ) { settings, connection, peer ->
        DisplayUiState(
            settings = settings,
            connection = connection,
            trustedPeer = peer,
            localAddress = AndroidPlatformCapabilities.localIpv4Address(),
            networkLabel = platform.activeTransportLabel(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DisplayUiState())

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
        )
    }
}
