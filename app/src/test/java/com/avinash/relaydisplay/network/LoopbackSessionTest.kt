package com.avinash.relaydisplay.network

import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.network.session.HandshakeRunner
import com.avinash.relaydisplay.network.session.RelayError
import com.avinash.relaydisplay.network.session.RelaySession
import com.avinash.relaydisplay.network.session.SessionListener
import com.avinash.relaydisplay.network.transport.LoopbackPair
import com.avinash.relaydisplay.network.transport.SecureConnection
import com.avinash.relaydisplay.protocol.Capabilities
import com.avinash.relaydisplay.protocol.Frame
import com.avinash.relaydisplay.protocol.ProtocolConstants
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.ProtocolException
import com.avinash.relaydisplay.protocol.RelayMessage
import com.avinash.relaydisplay.protocol.ShowText
import com.avinash.relaydisplay.security.HandshakeConfig
import com.avinash.relaydisplay.security.HandshakeOutcome
import com.avinash.relaydisplay.security.PairingUri
import com.avinash.relaydisplay.security.SoftwareDeviceIdentity
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * End-to-end protocol tests over the loopback transport.
 *
 * This is the substitute for owning two phones: everything from the handshake to record
 * encryption to disconnect handling runs for real, on real threads, with only the socket
 * replaced.
 */
class LoopbackSessionTest {

    private val controllerIdentity = SoftwareDeviceIdentity.generate()
    private val displayIdentity = SoftwareDeviceIdentity.generate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controllerConfig(pinned: ByteArray? = null, token: ByteArray? = null) = HandshakeConfig(
        role = DeviceRole.CONTROLLER,
        deviceId = "controller-1",
        deviceName = "S22 Ultra",
        identity = controllerIdentity,
        capabilities = setOf(Capabilities.TEXT, Capabilities.QR),
        pinnedPeerFingerprint = pinned,
        pairingToken = token,
    )

    private fun displayConfig(pinned: ByteArray? = null, token: ByteArray? = null) = HandshakeConfig(
        role = DeviceRole.DISPLAY,
        deviceId = "display-1",
        deviceName = "K6 Power",
        identity = displayIdentity,
        capabilities = setOf(Capabilities.TEXT, Capabilities.QR, Capabilities.IMAGE),
        pinnedPeerFingerprint = pinned,
        pairingToken = token,
    )

    private class Recorder : SessionListener {
        val messages = LinkedBlockingQueue<RelayMessage>()
        val closedError = AtomicReference<RelayError?>()
        val closedLatch = CountDownLatch(1)
        var closeCount = 0
            private set

        override fun onMessage(message: RelayMessage) {
            messages.add(message)
        }

        override fun onClosed(error: RelayError?) {
            closeCount++
            closedError.set(error)
            closedLatch.countDown()
        }

        fun nextMessage(timeoutMs: Long = 5_000): RelayMessage? =
            messages.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Runs both handshake halves concurrently, as they really run. */
    private fun handshakePair(
        controller: HandshakeConfig = controllerConfig(),
        display: HandshakeConfig = displayConfig(),
    ): Triple<SecureConnection, SecureConnection, Pair<HandshakeOutcome, HandshakeOutcome>> {
        val pair = LoopbackPair()
        val controllerConn = SecureConnection(pair.clientSide)
        val displayConn = SecureConnection(pair.serverSide)

        val displayOutcome = AtomicReference<HandshakeOutcome>()
        val displayFailure = AtomicReference<Throwable>()
        val responder = thread {
            try {
                displayOutcome.set(HandshakeRunner.runResponder(displayConn, display))
            } catch (e: Throwable) {
                displayFailure.set(e)
            }
        }
        val controllerOutcome = try {
            HandshakeRunner.runInitiator(controllerConn, controller)
        } finally {
            responder.join(10_000)
        }
        displayFailure.get()?.let { throw it }
        return Triple(controllerConn, displayConn, controllerOutcome to displayOutcome.get())
    }

    @Test
    fun `a full handshake secures both connections`() {
        val (controllerConn, displayConn, outcomes) = handshakePair()
        assertTrue(controllerConn.isSecure)
        assertTrue(displayConn.isSecure)
        assertEquals(outcomes.first.keys.shortAuthString, outcomes.second.keys.shortAuthString)
        controllerConn.close()
        displayConn.close()
    }

    @Test
    fun `text relays end to end over the encrypted session`() {
        val (controllerConn, displayConn, outcomes) = handshakePair()
        val controllerEvents = Recorder()
        val displayEvents = Recorder()
        val controllerSession = RelaySession(controllerConn, outcomes.first, controllerEvents, scope)
        val displaySession = RelaySession(displayConn, outcomes.second, displayEvents, scope)
        controllerSession.start()
        displaySession.start()

        controllerSession.trySend(ShowText(UUID.randomUUID(), "hello display"))

        val received = displayEvents.nextMessage()
        assertTrue("expected ShowText, got $received", received is ShowText)
        assertEquals("hello display", (received as ShowText).text)

        controllerSession.close()
        displaySession.close()
    }

    @Test
    fun `closing one side reports a clean disconnect on the other`() {
        val (controllerConn, displayConn, outcomes) = handshakePair()
        val controllerEvents = Recorder()
        val displayEvents = Recorder()
        RelaySession(controllerConn, outcomes.first, controllerEvents, scope).start()
        val displaySession = RelaySession(displayConn, outcomes.second, displayEvents, scope)
        displaySession.start()

        displaySession.close()

        assertTrue(controllerEvents.closedLatch.await(5, TimeUnit.SECONDS))
        // The peer hanging up is not an error condition; it is how a session normally ends.
        assertNull(controllerEvents.closedError.get())
    }

    @Test
    fun `onClosed fires exactly once however many things go wrong`() {
        val (controllerConn, displayConn, outcomes) = handshakePair()
        val events = Recorder()
        val session = RelaySession(controllerConn, outcomes.first, events, scope)
        session.start()
        displayConn.close()
        assertTrue(events.closedLatch.await(5, TimeUnit.SECONDS))
        session.close()
        session.close()
        Thread.sleep(200)
        assertEquals(1, events.closeCount)
    }

    @Test
    fun `a corrupted authentication tag ends the session as a protocol failure`() {
        val pair = LoopbackPair()
        val controllerConn = SecureConnection(pair.clientSide)
        val displayConn = SecureConnection(pair.serverSide)
        val displayOutcome = AtomicReference<HandshakeOutcome>()
        val responder = thread { displayOutcome.set(HandshakeRunner.runResponder(displayConn, displayConfig())) }
        val controllerOutcome = HandshakeRunner.runInitiator(controllerConn, controllerConfig())
        responder.join(10_000)

        // From here on, flip a byte of every record the controller sends.
        pair.clientSide.corrupt = { frame ->
            Frame(frame.versionMajor, frame.versionMinor, frame.flags, frame.sequence, frame.payload.clone().also { it[0] = (it[0] + 1).toByte() })
        }

        val displayEvents = Recorder()
        val displaySession = RelaySession(displayConn, displayOutcome.get(), displayEvents, scope)
        displaySession.start()
        val controllerSession = RelaySession(controllerConn, controllerOutcome, Recorder(), scope)
        controllerSession.start()
        controllerSession.trySend(ShowText(UUID.randomUUID(), "tampered"))

        assertTrue("session should have ended", displayEvents.closedLatch.await(5, TimeUnit.SECONDS))
        val error = displayEvents.closedError.get()
        assertNotNull(error)
        assertTrue("unexpected error $error", error is RelayError.ProtocolFailure)
        assertNull("no message may be delivered from a failed tag", displayEvents.messages.poll())
    }

    @Test
    fun `a disconnect during the handshake fails cleanly on the other side`() {
        val pair = LoopbackPair()
        val controllerConn = SecureConnection(pair.clientSide)
        // The display accepts the connection and then vanishes before replying.
        pair.serverSide.close()
        try {
            HandshakeRunner.runInitiator(controllerConn, controllerConfig())
            fail("expected the handshake to fail")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.MALFORMED_FRAME, e.errorCode)
        }
    }

    @Test
    fun `an unpaired peer cannot send a command before the handshake`() {
        val pair = LoopbackPair()
        val displayConn = SecureConnection(pair.serverSide)
        val attacker = pair.clientSide

        // Skip the handshake entirely and inject a plaintext SHOW_TEXT record.
        val record = com.avinash.relaydisplay.protocol.MessageCodec.encode(ShowText(UUID.randomUUID(), "malicious"))
        attacker.writeFrame(Frame(ProtocolConstants.VERSION_MAJOR, ProtocolConstants.VERSION_MINOR, 0, 0, record))

        try {
            displayConn.read()
            fail("a command before the handshake must be refused")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.NOT_AUTHENTICATED, e.errorCode)
        }
    }

    @Test
    fun `an encrypted frame before any key is agreed is refused`() {
        val pair = LoopbackPair()
        val displayConn = SecureConnection(pair.serverSide)
        pair.clientSide.writeFrame(
            Frame(
                ProtocolConstants.VERSION_MAJOR,
                ProtocolConstants.VERSION_MINOR,
                ProtocolConstants.FLAG_ENCRYPTED,
                0,
                ByteArray(32),
            ),
        )
        try {
            displayConn.read()
            fail("expected refusal")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.NOT_AUTHENTICATED, e.errorCode)
        }
    }

    @Test
    fun `a peer speaking an unsupported major version is refused`() {
        val pair = LoopbackPair()
        val displayConn = SecureConnection(pair.serverSide)
        pair.clientSide.writeFrame(Frame(99, 0, 0, 0, ByteArray(20)))
        try {
            displayConn.read()
            fail("expected refusal")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.UNSUPPORTED_VERSION, e.errorCode)
        }
    }

    @Test
    fun `a duplicate command id is claimed only once`() {
        val (controllerConn, displayConn, outcomes) = handshakePair()
        val session = RelaySession(displayConn, outcomes.second, Recorder(), scope)
        val id = UUID.randomUUID()
        assertTrue("first sighting must be claimable", session.claimCommand(id))
        assertFalse("a replay must not be applied twice", session.claimCommand(id))
        assertTrue(session.claimCommand(UUID.randomUUID()))
        session.close()
        controllerConn.close()
    }

    @Test
    fun `a slow reader produces backpressure instead of unbounded buffering`() {
        val (controllerConn, displayConn, outcomes) = handshakePair()
        // Never start the display session, so nothing drains the pipe.
        val session = RelaySession(controllerConn, outcomes.first, Recorder(), scope)
        session.start()

        // The bounded outbound queue plus the bounded pipe means trySend eventually refuses
        // rather than growing the heap.
        var accepted = 0
        val big = "x".repeat(32 * 1024)
        repeat(500) {
            if (session.trySend(ShowText(UUID.randomUUID(), big))) accepted++
        }
        assertTrue("queue should have filled, accepted=$accepted", accepted < 500)

        session.close()
        displayConn.close()
    }

    @Test
    fun `qr pairing over the wire needs no user confirmation`() {
        val token = PairingUri.newToken()
        val (controllerConn, displayConn, outcomes) = handshakePair(
            controller = controllerConfig(pinned = displayIdentity.fingerprint, token = token),
            display = displayConfig(token = token),
        )
        assertFalse(outcomes.first.requiresSasConfirmation)
        assertTrue(outcomes.second.authenticatedByPairingToken)
        controllerConn.close()
        displayConn.close()
    }

    @Test
    fun `a peer presenting the wrong identity is rejected over the wire`() {
        val pair = LoopbackPair()
        val controllerConn = SecureConnection(pair.clientSide)
        val displayConn = SecureConnection(pair.serverSide)
        val failure = AtomicReference<Throwable>()
        val responder = thread {
            runCatching { HandshakeRunner.runResponder(displayConn, displayConfig()) }
                .onFailure { failure.set(it) }
        }
        try {
            // The controller expects a different display than the one answering.
            HandshakeRunner.runInitiator(
                controllerConn,
                controllerConfig(pinned = ByteArray(32) { 0x11 }),
            )
            fail("expected the pin mismatch to abort")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.AUTH_FAILED, e.errorCode)
        } finally {
            controllerConn.close()
            displayConn.close()
            responder.join(5_000)
        }
    }

    @Test
    fun `heartbeat closes a session whose peer went silent`() {
        val (controllerConn, displayConn, outcomes) = handshakePair()
        val events = Recorder()
        var fakeNow = 0L
        val session = RelaySession(
            connection = controllerConn,
            outcome = outcomes.first,
            listener = events,
            parentScope = scope,
            heartbeatIntervalMs = 50,
            deadPeerThresholdMs = 150,
            nowMs = { fakeNow },
        )
        session.start()
        // Advance the session's notion of time past the dead-peer threshold without any inbound
        // traffic; the real display side is deliberately never started.
        Thread.sleep(120)
        fakeNow = 10_000
        assertTrue("silent peer should be dropped", events.closedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(events.closedError.get() is RelayError.ConnectFailed)
        displayConn.close()
    }
}
