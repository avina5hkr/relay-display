package com.avinash.relaydisplay.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avinash.relaydisplay.app.AppContainer
import com.avinash.relaydisplay.data.settings.RelaySettings
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.network.discovery.DiscoveredDisplay
import com.avinash.relaydisplay.network.session.ConnectTarget
import com.avinash.relaydisplay.network.session.ConnectionState
import com.avinash.relaydisplay.network.session.Endpoint
import com.avinash.relaydisplay.network.session.PairingOffer
import com.avinash.relaydisplay.network.session.RelayEngine
import com.avinash.relaydisplay.network.session.SessionCoordinator
import com.avinash.relaydisplay.platform.AndroidPlatformCapabilities
import com.avinash.relaydisplay.security.PairingUri
import com.avinash.relaydisplay.security.PairingUriError
import com.avinash.relaydisplay.security.PairingUriResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PairingUiState(
    val settings: RelaySettings = RelaySettings(),
    val connection: ConnectionState = ConnectionState.Idle,
    val pairingOffer: PairingOffer? = null,
    val discovered: List<DiscoveredDisplay> = emptyList(),
    val localAddress: String? = null,
    val listeningPort: Int? = null,
    val scanError: String? = null,
    val manualError: String? = null,
    val identityAvailable: Boolean = true,
    /** Set only when the network search itself could not run. */
    val browseError: String? = null,
)

private data class Extras(
    val port: Int?,
    val scanError: String?,
    val manualError: String?,
    val browseError: String?,
)

/**
 * Drives both halves of pairing: the display offering a code, and the controller consuming one.
 *
 * The scanned payload never reaches anything except [PairingUri.parse]; a code that fails
 * validation produces a message and nothing else happens.
 */
class PairingViewModel(
    private val engine: RelayEngine,
    private val coordinator: SessionCoordinator,
    settingsRepository: SettingsRepository,
    private val container: AppContainer,
    private val diagnostics: DiagnosticsLog,
) : ViewModel() {

    private val scanError = MutableStateFlow<String?>(null)

    /**
     * Browse only while the pairing screen is on screen.
     *
     * mDNS costs a multicast lock and steady radio wakeups, so it is not something to leave
     * running for the life of the process. The screen retains it and releases it on dispose,
     * which is also why this is not done in `init`: this ViewModel outlives the screen.
     */
    fun startBrowsing() = engine.retainBrowse()

    fun stopBrowsing() = engine.releaseBrowse()
    private val manualError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PairingUiState> = combine(
        settingsRepository.settings,
        coordinator.state,
        engine.pairingCode,
        engine.discovered,
        combine(engine.listeningPort, scanError, manualError, engine.browseError) { port, scan, manual, browse ->
            Extras(port, scan, manual, browse)
        },
    ) { settings, connection, offer, discovered, extras ->
        PairingUiState(
            settings = settings,
            connection = connection,
            pairingOffer = offer,
            discovered = discovered,
            localAddress = AndroidPlatformCapabilities.localIpv4Address(),
            listeningPort = extras.port,
            scanError = extras.scanError,
            manualError = extras.manualError,
            browseError = extras.browseError,
            identityAvailable = container.identity != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PairingUiState())

    /** Display: start listening if needed, then mint a code. */
    fun showPairingCode() {
        viewModelScope.launch {
            coordinator.requestSession()
            // The listener comes up asynchronously; the screen re-mints once a port exists.
            engine.offerPairingCode()
        }
    }

    fun refreshPairingCode() {
        engine.offerPairingCode()
    }

    fun withdrawPairingCode() {
        engine.withdrawPairingCode()
    }

    /** Controller: act on a scanned code. Returns true when the code was accepted. */
    fun onScanned(raw: String): Boolean {
        return when (val result = PairingUri.parse(raw, System.currentTimeMillis())) {
            is PairingUriResult.Valid -> {
                scanError.value = null
                diagnostics.info("pairing", "accepted a pairing code")
                engine.useTarget(ConnectTarget.Paired(result.payload))
                coordinator.requestSession()
                true
            }
            is PairingUriResult.Invalid -> {
                scanError.value = describe(result.error)
                diagnostics.warn("pairing", "rejected a pairing code: ${result.error}")
                false
            }
        }
    }

    /** Controller: connect to a display found by discovery. Pairing then needs the six digits. */
    fun connectToDiscovered(display: DiscoveredDisplay) {
        engine.useTarget(ConnectTarget.Manual(display.endpoint))
        coordinator.requestSession()
    }

    /** Controller: the Advanced fallback when discovery does not work on a network. */
    fun connectManually(host: String, port: String) {
        val trimmedHost = host.trim()
        val parsedPort = port.trim().toIntOrNull()
        if (!PairingUri.isIpv4Literal(trimmedHost)) {
            manualError.value = "Enter the display's IPv4 address, for example 192.168.1.42"
            return
        }
        if (parsedPort == null || parsedPort !in 1..65535) {
            manualError.value = "Enter the port shown on the display, between 1 and 65535"
            return
        }
        manualError.value = null
        engine.useTarget(ConnectTarget.Manual(Endpoint(trimmedHost, parsedPort)))
        coordinator.requestSession()
    }

    fun confirmShortAuthString(matches: Boolean) {
        engine.confirmShortAuthString(matches)
    }

    fun cancel() {
        coordinator.releaseSession()
    }

    private fun describe(error: PairingUriError): String = when (error) {
        PairingUriError.NOT_A_RELAY_CODE -> "That is not a RelayDisplay pairing code."
        PairingUriError.UNSUPPORTED_VERSION -> "That code was made by a newer version of the app."
        PairingUriError.MALFORMED -> "That code is damaged. Show a fresh one on the display."
        PairingUriError.BAD_FINGERPRINT -> "That code has an invalid device identity."
        PairingUriError.BAD_ADDRESS -> "That code has an invalid address."
        PairingUriError.WEAK_TOKEN -> "That code is not secure enough to use."
        PairingUriError.EXPIRED -> "That code has expired. Show a fresh one on the display."
        PairingUriError.TTL_TOO_LONG -> "That code claims to last too long, so it was refused."
    }

    companion object {
        fun create(container: AppContainer) = PairingViewModel(
            engine = container.engine,
            coordinator = container.sessionCoordinator,
            settingsRepository = container.settingsRepository,
            container = container,
            diagnostics = container.diagnostics,
        )
    }
}
