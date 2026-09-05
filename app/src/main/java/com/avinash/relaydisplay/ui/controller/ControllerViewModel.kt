package com.avinash.relaydisplay.ui.controller

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

data class ControllerUiState(
    val settings: RelaySettings = RelaySettings(),
    val connection: ConnectionState = ConnectionState.Idle,
    val trustedPeer: TrustedPeer? = null,
    val networkLabel: String = "",
    val localAddress: String? = null,
) {
    val paused: Boolean get() = settings.operatingMode == OperatingMode.PAUSED
    val paired: Boolean get() = trustedPeer != null
    val connected: Boolean get() = connection.isConnected

    /** Content actions are only offered on a live authenticated session. */
    val canSendContent: Boolean get() = connected
}

class ControllerViewModel(
    private val settingsRepository: SettingsRepository,
    trustedPeerRepository: TrustedPeerRepository,
    private val sessionCoordinator: SessionCoordinator,
    private val platform: AndroidPlatformCapabilities,
) : ViewModel() {

    val uiState: StateFlow<ControllerUiState> = combine(
        settingsRepository.settings,
        sessionCoordinator.state,
        trustedPeerRepository.trustedPeer,
    ) { settings, connection, peer ->
        ControllerUiState(
            settings = settings,
            connection = connection,
            trustedPeer = peer,
            networkLabel = platform.activeTransportLabel(),
            localAddress = AndroidPlatformCapabilities.localIpv4Address(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControllerUiState())

    fun connect() = sessionCoordinator.requestSession()
    fun disconnect() = sessionCoordinator.releaseSession()
    fun reconnectNow() = sessionCoordinator.reconnectNow()

    fun setOperatingMode(mode: OperatingMode) {
        viewModelScope.launch {
            settingsRepository.setOperatingMode(mode)
            sessionCoordinator.onOperatingModeChanged(mode)
        }
    }

    companion object {
        fun create(container: AppContainer) = ControllerViewModel(
            settingsRepository = container.settingsRepository,
            trustedPeerRepository = container.trustedPeerRepository,
            sessionCoordinator = container.sessionCoordinator,
            platform = container.platformCapabilities,
        )
    }
}
