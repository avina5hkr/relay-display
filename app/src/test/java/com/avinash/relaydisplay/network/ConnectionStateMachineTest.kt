package com.avinash.relaydisplay.network

import com.avinash.relaydisplay.domain.model.OperatingMode
import com.avinash.relaydisplay.network.session.ConnectionContext
import com.avinash.relaydisplay.network.session.ConnectionEvent
import com.avinash.relaydisplay.network.session.ConnectionState
import com.avinash.relaydisplay.network.session.ConnectionStateMachine
import com.avinash.relaydisplay.network.session.Endpoint
import com.avinash.relaydisplay.network.session.PairingStage
import com.avinash.relaydisplay.network.session.PeerSummary
import com.avinash.relaydisplay.network.session.RelayError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateMachineTest {

    private val endpoint = Endpoint("192.168.1.5", 41234)
    private val peer = PeerSummary("display-1", "K6 Power", "AAAA BBBB", setOf("qr"), 0)

    private fun ctx(
        mode: OperatingMode = OperatingMode.ON_DEMAND,
        autoReconnect: Boolean = true,
        intended: Boolean = true,
    ) = ConnectionContext(mode, autoReconnect, intended)

    private fun reduce(state: ConnectionState, event: ConnectionEvent, context: ConnectionContext = ctx()) =
        ConnectionStateMachine.reduce(state, event, context)

    @Test
    fun `initial state follows the persisted mode`() {
        assertEquals(ConnectionState.Paused, ConnectionStateMachine.initialState(OperatingMode.PAUSED))
        assertEquals(ConnectionState.Idle, ConnectionStateMachine.initialState(OperatingMode.ON_DEMAND))
        assertEquals(ConnectionState.Idle, ConnectionStateMachine.initialState(OperatingMode.ALWAYS_READY))
    }

    @Test
    fun `the happy path walks idle to connected`() {
        var s: ConnectionState = ConnectionState.Idle
        s = reduce(s, ConnectionEvent.SessionRequested)
        assertEquals(ConnectionState.Preparing, s)
        s = reduce(s, ConnectionEvent.PreparationComplete)
        assertEquals(ConnectionState.Discovering, s)
        s = reduce(s, ConnectionEvent.EndpointAvailable(endpoint))
        assertEquals(ConnectionState.Connecting(endpoint), s)
        s = reduce(s, ConnectionEvent.TransportConnected)
        assertEquals(ConnectionState.Authenticating, s)
        s = reduce(s, ConnectionEvent.Authenticated(peer, endpoint))
        assertTrue(s is ConnectionState.Connected)
        assertEquals(peer, (s as ConnectionState.Connected).peer)
    }

    @Test
    fun `pairing is entered from authenticating and surfaces the string`() {
        val s = reduce(
            ConnectionState.Authenticating,
            ConnectionEvent.PairingProgress(PairingStage.AwaitingUserConfirmation, "123456"),
        )
        assertEquals(ConnectionState.Pairing(PairingStage.AwaitingUserConfirmation, "123456"), s)
    }

    @Test
    fun `pause wins from every state`() {
        val states = listOf(
            ConnectionState.Idle,
            ConnectionState.Preparing,
            ConnectionState.Discovering,
            ConnectionState.Connecting(endpoint),
            ConnectionState.Authenticating,
            ConnectionState.Pairing(PairingStage.ExchangingKeys),
            ConnectionState.Connected(peer, endpoint, 0),
            ConnectionState.Reconnecting(2, 4000, null),
            ConnectionState.Failed(RelayError.NoNetwork),
            ConnectionState.Stopping,
        )
        for (s in states) {
            assertEquals("pause failed from $s", ConnectionState.Paused, reduce(s, ConnectionEvent.UserPaused))
        }
    }

    @Test
    fun `nothing but resume moves the machine while paused`() {
        val paused: ConnectionState = ConnectionState.Paused
        val ignored = listOf(
            ConnectionEvent.SessionRequested,
            ConnectionEvent.PreparationComplete,
            ConnectionEvent.EndpointAvailable(endpoint),
            ConnectionEvent.TransportConnected,
            ConnectionEvent.Authenticated(peer, endpoint),
            ConnectionEvent.RetryNow,
            ConnectionEvent.LinkLost(null),
            ConnectionEvent.Failure(RelayError.NoNetwork),
            ConnectionEvent.StopComplete,
        )
        for (e in ignored) {
            assertEquals("paused must ignore $e", ConnectionState.Paused, reduce(paused, e))
        }
        assertEquals(ConnectionState.Idle, reduce(paused, ConnectionEvent.UserResumed))
    }

    @Test
    fun `a paused context keeps a hard reset paused`() {
        assertEquals(
            ConnectionState.Paused,
            reduce(ConnectionState.Connected(peer, endpoint, 0), ConnectionEvent.HardReset, ctx(mode = OperatingMode.PAUSED)),
        )
    }

    @Test
    fun `a hard reset drops any live state back to idle`() {
        assertEquals(
            ConnectionState.Idle,
            reduce(ConnectionState.Connected(peer, endpoint, 0), ConnectionEvent.HardReset),
        )
    }

    @Test
    fun `an unexpected drop starts reconnecting when a session is wanted`() {
        val s = reduce(ConnectionState.Connected(peer, endpoint, 0), ConnectionEvent.LinkLost(null))
        assertTrue(s is ConnectionState.Reconnecting)
    }

    @Test
    fun `an unexpected drop goes idle when the user does not want a session`() {
        val s = reduce(
            ConnectionState.Connected(peer, endpoint, 0),
            ConnectionEvent.LinkLost(null),
            ctx(intended = false),
        )
        assertEquals(ConnectionState.Idle, s)
    }

    @Test
    fun `a drop with auto reconnect off reports the error instead of retrying`() {
        val s = reduce(
            ConnectionState.Connected(peer, endpoint, 0),
            ConnectionEvent.LinkLost(RelayError.ConnectFailed("reset")),
            ctx(autoReconnect = false),
        )
        assertTrue(s is ConnectionState.Failed)
    }

    @Test
    fun `an unrecoverable failure is never retried`() {
        val s = reduce(
            ConnectionState.Authenticating,
            ConnectionEvent.Failure(RelayError.Untrusted("fingerprint mismatch")),
        )
        assertEquals(ConnectionState.Failed(RelayError.Untrusted("fingerprint mismatch")), s)
    }

    @Test
    fun `a recoverable failure retries when a session is wanted`() {
        val s = reduce(ConnectionState.Connecting(endpoint), ConnectionEvent.Failure(RelayError.PeerNotFound))
        assertTrue(s is ConnectionState.Reconnecting)
    }

    @Test
    fun `a scheduled retry carries the attempt count and delay`() {
        val s = reduce(
            ConnectionState.Reconnecting(0, 0, null),
            ConnectionEvent.RetryScheduled(3, 8000, RelayError.NoNetwork),
        )
        assertEquals(ConnectionState.Reconnecting(3, 8000, RelayError.NoNetwork), s)
    }

    @Test
    fun `a retry scheduled after the user gave up lands on idle`() {
        val s = reduce(
            ConnectionState.Reconnecting(1, 1000, null),
            ConnectionEvent.RetryScheduled(2, 2000, null),
            ctx(intended = false),
        )
        assertEquals(ConnectionState.Idle, s)
    }

    @Test
    fun `retry now resumes discovery`() {
        assertEquals(
            ConnectionState.Discovering,
            reduce(ConnectionState.Reconnecting(1, 1000, null), ConnectionEvent.RetryNow),
        )
    }

    @Test
    fun `retry now is ignored outside reconnecting`() {
        assertEquals(ConnectionState.Idle, reduce(ConnectionState.Idle, ConnectionEvent.RetryNow))
    }

    @Test
    fun `releasing a session stops rather than retrying`() {
        assertEquals(
            ConnectionState.Stopping,
            reduce(ConnectionState.Connected(peer, endpoint, 0), ConnectionEvent.SessionReleased),
        )
        assertEquals(ConnectionState.Idle, reduce(ConnectionState.Stopping, ConnectionEvent.StopComplete))
    }

    @Test
    fun `requesting a session while connected changes nothing`() {
        val connected = ConnectionState.Connected(peer, endpoint, 0)
        assertEquals(connected, reduce(connected, ConnectionEvent.SessionRequested))
    }

    @Test
    fun `requesting a session after a failure tries again`() {
        assertEquals(
            ConnectionState.Preparing,
            reduce(ConnectionState.Failed(RelayError.PeerNotFound), ConnectionEvent.SessionRequested),
        )
    }

    @Test
    fun `out of order events do not corrupt the state`() {
        // Transport connected while still discovering is a stale callback, not a transition.
        assertEquals(
            ConnectionState.Discovering,
            reduce(ConnectionState.Discovering, ConnectionEvent.TransportConnected),
        )
        assertEquals(
            ConnectionState.Idle,
            reduce(ConnectionState.Idle, ConnectionEvent.PreparationComplete),
        )
    }

    @Test
    fun `reconnecting can jump straight to connecting when an endpoint is already known`() {
        assertEquals(
            ConnectionState.Connecting(endpoint),
            reduce(ConnectionState.Reconnecting(2, 4000, null), ConnectionEvent.EndpointAvailable(endpoint)),
        )
    }

    @Test
    fun `isActive and isConnected describe the state honestly`() {
        assertTrue(ConnectionState.Connected(peer, endpoint, 0).isConnected)
        assertTrue(ConnectionState.Discovering.isActive)
        assertTrue(!ConnectionState.Discovering.isConnected)
        assertTrue(!ConnectionState.Idle.isActive)
        assertTrue(!ConnectionState.Paused.isActive)
        assertTrue(!ConnectionState.Reconnecting(1, 1, null).isConnected)
    }

    // -- a Display whose peer hangs up while it keeps listening ---------------------------

    @Test
    fun `peer disconnecting returns a connected display to waiting`() {
        val connected = ConnectionState.Connected(peer, endpoint, sinceElapsedMs = 0)
        // The listener is still bound, so this is "waiting for the other phone", not Idle and not
        // Reconnecting -- a Display has nothing to reconnect to.
        assertEquals(
            ConnectionState.Pairing(PairingStage.AwaitingPeer),
            reduce(connected, ConnectionEvent.PeerDisconnected),
        )
    }

    @Test
    fun `peer disconnecting does not disturb a state that was never connected`() {
        for (state in listOf(
            ConnectionState.Idle,
            ConnectionState.Preparing,
            ConnectionState.Discovering,
            ConnectionState.Pairing(PairingStage.AwaitingPeer),
            ConnectionState.Pairing(PairingStage.ExchangingKeys),
            ConnectionState.Reconnecting(attempt = 2, nextAttemptInMs = 4_000, lastError = null),
        )) {
            assertEquals(
                "$state must be left alone",
                state,
                reduce(state, ConnectionEvent.PeerDisconnected),
            )
        }
    }

    @Test
    fun `peer disconnecting cannot wake a paused device`() {
        assertEquals(
            ConnectionState.Paused,
            reduce(ConnectionState.Paused, ConnectionEvent.PeerDisconnected, ctx(mode = OperatingMode.PAUSED)),
        )
    }

    @Test
    fun `a display accepting a second caller runs the same transitions as the first`() {
        // The regression this pins: the accept loop looped without dispatching anything, so the
        // machine stayed Connected, the next session's PairingProgress was swallowed by the
        // Connected branch, and the screen went on naming a peer that had already hung up.
        var s: ConnectionState = ConnectionState.Connected(peer, endpoint, sinceElapsedMs = 0)
        s = reduce(s, ConnectionEvent.PeerDisconnected)
        s = reduce(s, ConnectionEvent.TransportConnected)
        s = reduce(s, ConnectionEvent.PairingProgress(PairingStage.ExchangingKeys))
        assertEquals(ConnectionState.Pairing(PairingStage.ExchangingKeys), s)
    }
}
