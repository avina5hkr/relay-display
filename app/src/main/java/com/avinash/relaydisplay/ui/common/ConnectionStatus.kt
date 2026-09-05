package com.avinash.relaydisplay.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.avinash.relaydisplay.network.session.ConnectionState
import com.avinash.relaydisplay.network.session.PairingStage
import com.avinash.relaydisplay.network.session.RelayError

/**
 * User-facing wording for the connection state.
 *
 * Two rules: never say "Connected" for anything short of an authenticated live session, and
 * always pair a problem with something the user can do about it.
 */
object ConnectionStatusText {

    fun headline(state: ConnectionState): String = when (state) {
        ConnectionState.Paused -> "Paused"
        ConnectionState.Idle -> "Not connected"
        ConnectionState.Preparing -> "Getting ready"
        ConnectionState.Discovering -> "Looking for your other phone"
        is ConnectionState.Pairing -> when (state.stage) {
            PairingStage.AwaitingPeer -> "Waiting for the other phone"
            PairingStage.ExchangingKeys -> "Exchanging keys"
            PairingStage.AwaitingUserConfirmation -> "Check the code on both phones"
            PairingStage.Finalising -> "Finishing pairing"
        }
        is ConnectionState.Connecting -> "Connecting"
        ConnectionState.Authenticating -> "Verifying the other phone"
        is ConnectionState.Connected -> "Connected to ${state.peer.displayName}"
        is ConnectionState.Reconnecting ->
            if (state.attempt == 0) "Reconnecting" else "Reconnecting (attempt ${state.attempt})"
        is ConnectionState.Failed -> errorHeadline(state.error)
        ConnectionState.Stopping -> "Stopping"
    }

    fun detail(state: ConnectionState): String? = when (state) {
        ConnectionState.Paused ->
            "Nothing is running. No discovery, no connection, no battery use from RelayDisplay."
        ConnectionState.Idle -> "Tap Connect when you want to use the other phone."
        is ConnectionState.Reconnecting -> state.lastError?.let { errorDetail(it) }
            ?: "The link dropped. Trying again shortly."
        is ConnectionState.Failed -> errorDetail(state.error)
        is ConnectionState.Pairing ->
            if (state.stage == PairingStage.AwaitingUserConfirmation) {
                "Both phones must show the same six digits. If they differ, cancel: something is " +
                    "sitting between them."
            } else {
                null
            }
        else -> null
    }

    private fun errorHeadline(error: RelayError): String = when (error) {
        is RelayError.PermissionRequired -> "Permission needed"
        RelayError.LocalNetworkBlocked -> "Local network access is off"
        RelayError.NoNetwork -> "No usable network"
        RelayError.PeerNotFound -> "Could not find the other phone"
        is RelayError.ConnectFailed -> "Could not connect"
        is RelayError.HandshakeFailed -> "Could not verify the other phone"
        is RelayError.Untrusted -> "This device is not the one you paired with"
        is RelayError.ProtocolFailure -> "The connection ended unexpectedly"
        is RelayError.Internal -> "Something went wrong"
    }

    private fun errorDetail(error: RelayError): String = when (error) {
        is RelayError.PermissionRequired -> "RelayDisplay needs this to reach your other phone."
        RelayError.LocalNetworkBlocked ->
            "Android blocks local network access until you allow it. Tap below to grant it, or " +
                "turn it on in Settings, Apps, RelayDisplay."
        RelayError.NoNetwork ->
            "Connect both phones to the same Wi-Fi, or turn on the hotspot on one of them."
        RelayError.PeerNotFound ->
            "Make sure the other phone is on the same network and RelayDisplay is open on it. " +
                "You can also scan its pairing code."
        is RelayError.ConnectFailed ->
            "The other phone did not answer. It may have moved to a different network."
        is RelayError.HandshakeFailed -> "Try again, and pair once more if it keeps failing."
        is RelayError.Untrusted ->
            "Its identity does not match the device you paired with. Nothing was accepted. " +
                "Only pair again if you are sure why this changed."
        is RelayError.ProtocolFailure -> "Reconnecting usually fixes this."
        is RelayError.Internal -> "Please report this from the Diagnostics screen."
    }

    @Composable
    fun indicatorColor(state: ConnectionState): Color = when (state) {
        is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
        ConnectionState.Paused, ConnectionState.Idle -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.tertiary
    }
}
