package com.avinash.relaydisplay.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avinash.relaydisplay.app.AppContainer
import com.avinash.relaydisplay.app.RoleSwitchCoordinator
import com.avinash.relaydisplay.app.RoleSwitchState
import com.avinash.relaydisplay.data.peers.TrustedPeerRepository
import com.avinash.relaydisplay.data.settings.RelaySettings
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.domain.model.FitMode
import com.avinash.relaydisplay.domain.model.OperatingMode
import com.avinash.relaydisplay.domain.model.TrustedPeer
import com.avinash.relaydisplay.network.session.SessionCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: RelaySettings = RelaySettings(),
    val trustedPeer: TrustedPeer? = null,
    val roleSwitch: RoleSwitchState = RoleSwitchState.Idle,
) {
    /** Every role control is disabled while a switch runs, which is the double-tap guard. */
    val roleControlsEnabled: Boolean get() = roleSwitch !is RoleSwitchState.Switching
}

/**
 * Settings screen state and actions.
 *
 * Every action that changes role or mode goes through [SessionCoordinator] first, so runtime
 * resources are torn down before the persisted value moves. That ordering is what keeps a
 * paused device from leaving a listening socket behind.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val trustedPeerRepository: TrustedPeerRepository,
    private val sessionCoordinator: SessionCoordinator,
    private val roleSwitchCoordinator: RoleSwitchCoordinator,
    private val diagnostics: DiagnosticsLog,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        trustedPeerRepository.trustedPeer,
        roleSwitchCoordinator.state,
    ) { settings, peer, switch ->
        SettingsUiState(settings, peer, switch)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /**
     * Confirmed role change.
     *
     * Order matters: stop everything, drop role-incompatible session state, then persist. The
     * trusted peer survives, because pairing is between two installations rather than two roles,
     * and re-pairing after every role change would be pointless friction.
     */
    fun changeRole(newRole: DeviceRole) {
        // Delegated in full: ordering, the bounded shutdown wait and the double-tap guard all
        // live in the coordinator so there is exactly one implementation of the sequence.
        viewModelScope.launch { roleSwitchCoordinator.switchTo(newRole) }
    }

    fun acknowledgeRoleSwitch() = roleSwitchCoordinator.acknowledge()

    fun setOperatingMode(mode: OperatingMode) {
        viewModelScope.launch {
            diagnostics.info("settings", "operating mode -> ${mode.storageValue}")
            // Persist first so a process death mid-change cannot resurrect the old mode, then
            // let the coordinator bring runtime state in line with it.
            settingsRepository.setOperatingMode(mode)
            sessionCoordinator.onOperatingModeChanged(mode)
        }
    }

    fun setDeviceName(name: String) = viewModelScope.launch { settingsRepository.setLocalDeviceName(name) }
    fun setKeepScreenAwake(value: Boolean) = viewModelScope.launch { settingsRepository.setKeepScreenAwake(value) }
    fun setImmersive(value: Boolean) = viewModelScope.launch { settingsRepository.setImmersiveByDefault(value) }
    fun setFitMode(value: FitMode) = viewModelScope.launch { settingsRepository.setDefaultFitMode(value) }
    fun setBrightness(value: Int) = viewModelScope.launch { settingsRepository.setWindowBrightness(value) }
    fun setReconnect(value: Boolean) = viewModelScope.launch { settingsRepository.setReconnectAutomatically(value) }
    fun setBlankOnDisconnect(value: Boolean) = viewModelScope.launch { settingsRepository.setBlankOnDisconnect(value) }
    fun setAllowTrustedLinkOpen(value: Boolean) = viewModelScope.launch {
        settingsRepository.setAllowTrustedLinkOpen(value)
    }

    /** Deletes trust material and drops any live session immediately. */
    fun forgetPeer() {
        viewModelScope.launch {
            diagnostics.warn("settings", "forgetting trusted peer")
            sessionCoordinator.hardReset(reason = "peer forgotten")
            trustedPeerRepository.forget()
        }
    }

    companion object {
        fun create(container: AppContainer) = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            trustedPeerRepository = container.trustedPeerRepository,
            sessionCoordinator = container.sessionCoordinator,
            roleSwitchCoordinator = container.roleSwitchCoordinator,
            diagnostics = container.diagnostics,
        )
    }
}
