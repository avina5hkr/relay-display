package com.avinash.relaydisplay.network.session

import com.avinash.relaydisplay.domain.model.OperatingMode

/**
 * The single source of truth for how the connection state may change.
 *
 * Written as a pure reducer so every transition, including the awkward ones (pause during a
 * handshake, link loss while already reconnecting, a stop that races a failure), is covered by
 * `ConnectionStateMachineTest` instead of by hope.
 */
object ConnectionStateMachine {

    fun initialState(mode: OperatingMode): ConnectionState =
        if (mode == OperatingMode.PAUSED) ConnectionState.Paused else ConnectionState.Idle

    fun reduce(
        state: ConnectionState,
        event: ConnectionEvent,
        context: ConnectionContext,
    ): ConnectionState = when (event) {
        // Pause and hard reset win from every state; they are how the user stops the radio.
        ConnectionEvent.UserPaused -> ConnectionState.Paused
        ConnectionEvent.HardReset -> if (context.mode == OperatingMode.PAUSED) {
            ConnectionState.Paused
        } else {
            ConnectionState.Idle
        }

        ConnectionEvent.UserResumed ->
            if (state is ConnectionState.Paused) ConnectionState.Idle else state

        // While paused, nothing else may move the machine. This is what makes Pause honest.
        else -> if (state is ConnectionState.Paused) state else reduceActive(state, event, context)
    }

    private fun reduceActive(
        state: ConnectionState,
        event: ConnectionEvent,
        context: ConnectionContext,
    ): ConnectionState = when (event) {
        ConnectionEvent.SessionRequested -> when (state) {
            is ConnectionState.Connected -> state
            is ConnectionState.Idle,
            is ConnectionState.Failed,
            is ConnectionState.Stopping,
            -> ConnectionState.Preparing
            else -> state
        }

        ConnectionEvent.SessionReleased -> ConnectionState.Stopping

        ConnectionEvent.PreparationComplete ->
            if (state is ConnectionState.Preparing) ConnectionState.Discovering else state

        is ConnectionEvent.EndpointAvailable -> when (state) {
            is ConnectionState.Preparing,
            is ConnectionState.Discovering,
            is ConnectionState.Reconnecting,
            -> ConnectionState.Connecting(event.endpoint)
            else -> state
        }

        ConnectionEvent.TransportConnected ->
            if (state is ConnectionState.Connecting) ConnectionState.Authenticating else state

        is ConnectionEvent.PairingProgress -> when (state) {
            is ConnectionState.Authenticating,
            is ConnectionState.Pairing,
            is ConnectionState.Discovering,
            is ConnectionState.Connecting,
            -> ConnectionState.Pairing(event.stage, event.shortAuthString)
            else -> state
        }

        is ConnectionEvent.Authenticated -> ConnectionState.Connected(
            peer = event.peer,
            endpoint = event.endpoint,
            sinceElapsedMs = 0,
        )

        is ConnectionEvent.LinkLost -> when {
            // A drop the user did not ask for, while a session is wanted, means we try again.
            context.sessionIntended && context.autoReconnect && context.mode != OperatingMode.PAUSED ->
                ConnectionState.Reconnecting(attempt = 0, nextAttemptInMs = 0, lastError = event.error)
            event.error != null -> ConnectionState.Failed(event.error)
            else -> ConnectionState.Idle
        }

        ConnectionEvent.PeerDisconnected -> when (state) {
            // Straight back to waiting, not to Idle: the listener is still bound and the phone is
            // still advertising, so "waiting for the other phone" is the honest answer.
            is ConnectionState.Connected -> ConnectionState.Pairing(PairingStage.AwaitingPeer)
            else -> state
        }

        is ConnectionEvent.RetryScheduled ->
            if (context.sessionIntended) {
                ConnectionState.Reconnecting(event.attempt, event.delayMs, event.lastError)
            } else {
                ConnectionState.Idle
            }

        ConnectionEvent.RetryNow -> when (state) {
            is ConnectionState.Reconnecting -> ConnectionState.Discovering
            else -> state
        }

        is ConnectionEvent.Failure ->
            if (event.error.recoverable && context.sessionIntended && context.autoReconnect) {
                ConnectionState.Reconnecting(attempt = 0, nextAttemptInMs = 0, lastError = event.error)
            } else {
                ConnectionState.Failed(event.error)
            }

        ConnectionEvent.StopRequested -> ConnectionState.Stopping

        ConnectionEvent.StopComplete -> ConnectionState.Idle

        // Handled by reduce(); listed so the when stays exhaustive.
        ConnectionEvent.UserPaused,
        ConnectionEvent.UserResumed,
        ConnectionEvent.HardReset,
        -> state
    }
}
