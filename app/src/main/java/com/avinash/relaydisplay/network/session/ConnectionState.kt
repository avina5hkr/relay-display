package com.avinash.relaydisplay.network.session

import com.avinash.relaydisplay.domain.model.OperatingMode

/** Where a peer can be reached. Kept separate from any socket so it is safe to persist and log. */
data class Endpoint(val host: String, val port: Int) {
    /** Redacted form for diagnostics that the user might share. */
    fun redacted(): String = "***:$port"

    override fun toString(): String = "$host:$port"
}

/** The peer of an established session, as far as the UI needs to know. */
data class PeerSummary(
    val peerId: String,
    val displayName: String,
    val fingerprintShort: String,
    val capabilities: Set<String>,
    val protocolMinor: Int,
)

/** Why a session ended or failed to start, in terms the UI can act on. */
sealed interface RelayError {
    val recoverable: Boolean

    /** The user has not granted something we need; the UI offers the matching request. */
    data class PermissionRequired(val permission: String) : RelayError {
        override val recoverable = true
    }

    /** Android 17+ denied LAN access. Distinct from a plain permission so the copy can explain. */
    data object LocalNetworkBlocked : RelayError {
        override val recoverable = true
    }

    data object NoNetwork : RelayError {
        override val recoverable = true
    }

    data object PeerNotFound : RelayError {
        override val recoverable = true
    }

    data class ConnectFailed(val reason: String) : RelayError {
        override val recoverable = true
    }

    data class HandshakeFailed(val reason: String) : RelayError {
        override val recoverable = true
    }

    /** Trust check failed. Not auto-retried: repeating it would just repeat the rejection. */
    data class Untrusted(val reason: String) : RelayError {
        override val recoverable = false
    }

    data class ProtocolFailure(val reason: String) : RelayError {
        override val recoverable = true
    }

    data class Internal(val reason: String) : RelayError {
        override val recoverable = false
    }
}

/** How far along pairing is, for the pairing screen. */
enum class PairingStage { AwaitingPeer, ExchangingKeys, AwaitingUserConfirmation, Finalising }

/**
 * The real state of the connection. The UI renders exactly this and never infers a connected
 * state from discovery alone.
 */
sealed interface ConnectionState {
    /** Mode is PAUSED. Nothing is running and nothing will start until the user resumes. */
    data object Paused : ConnectionState

    /** Idle and ready. On Demand sits here until the user taps Connect. */
    data object Idle : ConnectionState

    /** Checking permissions and capabilities before touching the network. */
    data object Preparing : ConnectionState

    /** Display: listening and advertising. Controller: browsing for the display. */
    data object Discovering : ConnectionState

    data class Pairing(val stage: PairingStage, val shortAuthString: String? = null) : ConnectionState

    data class Connecting(val endpoint: Endpoint?) : ConnectionState

    data object Authenticating : ConnectionState

    data class Connected(val peer: PeerSummary, val endpoint: Endpoint?, val sinceElapsedMs: Long) : ConnectionState

    data class Reconnecting(val attempt: Int, val nextAttemptInMs: Long, val lastError: RelayError?) : ConnectionState

    data class Failed(val error: RelayError) : ConnectionState

    /** Tearing resources down. Terminal transitions go through here so cleanup is observable. */
    data object Stopping : ConnectionState

    val isActive: Boolean
        get() = this is Connecting || this is Authenticating || this is Connected ||
            this is Pairing || this is Discovering || this is Preparing

    val isConnected: Boolean get() = this is Connected
}

/** Inputs to the state machine. */
sealed interface ConnectionEvent {
    data object UserPaused : ConnectionEvent
    data object UserResumed : ConnectionEvent

    /** User tapped Connect / Make available, or Always Ready started up. */
    data object SessionRequested : ConnectionEvent

    /** User tapped Disconnect. Suppresses automatic reconnection until requested again. */
    data object SessionReleased : ConnectionEvent

    data object PreparationComplete : ConnectionEvent
    data class EndpointAvailable(val endpoint: Endpoint) : ConnectionEvent
    data object TransportConnected : ConnectionEvent
    data class PairingProgress(val stage: PairingStage, val shortAuthString: String? = null) : ConnectionEvent
    data class Authenticated(val peer: PeerSummary, val endpoint: Endpoint?) : ConnectionEvent
    data class LinkLost(val error: RelayError?) : ConnectionEvent

    /**
     * A session ended while this device stays available and listening.
     *
     * Deliberately not [LinkLost]. Link loss means the connection this device wanted is gone and
     * it should reconnect or give up; a Display whose listener is still bound has lost nothing to
     * retry -- it is simply waiting for the next caller again. Without this event the Display's
     * accept loop went straight back to `accept()` and the published state stayed `Connected`,
     * so the waiting screen claimed a peer that had hung up.
     */
    data object PeerDisconnected : ConnectionEvent
    data class RetryScheduled(val attempt: Int, val delayMs: Long, val lastError: RelayError?) : ConnectionEvent
    data object RetryNow : ConnectionEvent
    data class Failure(val error: RelayError) : ConnectionEvent
    data object StopRequested : ConnectionEvent
    data object StopComplete : ConnectionEvent

    /** Role changed, peer forgotten, or a permission was revoked: drop everything. */
    data object HardReset : ConnectionEvent
}

/** Everything outside the state that affects a transition. */
data class ConnectionContext(
    val mode: OperatingMode,
    val autoReconnect: Boolean,
    /** True while the user (or Always Ready) wants a session. Reconnect only happens when set. */
    val sessionIntended: Boolean,
)
