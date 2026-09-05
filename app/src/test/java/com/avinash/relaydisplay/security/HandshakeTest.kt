package com.avinash.relaydisplay.security

import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.protocol.Capabilities
import com.avinash.relaydisplay.protocol.MessageCodec
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.ProtocolException
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HandshakeTest {

    private val controllerIdentity = SoftwareDeviceIdentity.generate()
    private val displayIdentity = SoftwareDeviceIdentity.generate()
    private val attackerIdentity = SoftwareDeviceIdentity.generate()

    private fun controllerConfig(
        pinned: ByteArray? = null,
        token: ByteArray? = null,
        identity: DeviceIdentity = controllerIdentity,
    ) = HandshakeConfig(
        role = DeviceRole.CONTROLLER,
        deviceId = "controller-1",
        deviceName = "S22 Ultra",
        identity = identity,
        capabilities = setOf(Capabilities.TEXT, Capabilities.QR),
        pinnedPeerFingerprint = pinned,
        pairingToken = token,
    )

    private fun displayConfig(
        pinned: ByteArray? = null,
        token: ByteArray? = null,
        identity: DeviceIdentity = displayIdentity,
    ) = HandshakeConfig(
        role = DeviceRole.DISPLAY,
        deviceId = "display-1",
        deviceName = "K6 Power",
        identity = identity,
        capabilities = setOf(Capabilities.TEXT, Capabilities.QR, Capabilities.IMAGE),
        pinnedPeerFingerprint = pinned,
        pairingToken = token,
    )

    /** Runs a full handshake and returns both outcomes. */
    private fun run(
        initiatorConfig: HandshakeConfig,
        responderConfig: HandshakeConfig,
    ): Pair<HandshakeOutcome, HandshakeOutcome> {
        val i = InitiatorHandshake(initiatorConfig)
        val r = ResponderHandshake(responderConfig)
        val hello = i.createHello()
        val ack = r.onHello(decodeRoundTrip(hello))
        val confirm = i.onHelloAck(decodeRoundTrip(ack))
        val (result, responderOutcome) = r.onAuthConfirm(decodeRoundTrip(confirm))
        val initiatorOutcome = i.onAuthResult(decodeRoundTrip(result))
        return initiatorOutcome to (responderOutcome ?: fail("responder rejected") as Nothing)
    }

    /** Force everything through the codec so the test exercises the real wire representation. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : com.avinash.relaydisplay.protocol.RelayMessage> decodeRoundTrip(m: T): T =
        MessageCodec.decode(MessageCodec.encode(m)) as T

    @Test
    fun `first pairing succeeds and both sides derive the same keys`() {
        val (ctrl, disp) = run(controllerConfig(), displayConfig())

        assertArrayEquals(ctrl.keys.controllerToDisplayKey, disp.keys.controllerToDisplayKey)
        assertArrayEquals(ctrl.keys.displayToControllerKey, disp.keys.displayToControllerKey)
        assertArrayEquals(ctrl.keys.controllerToDisplayNoncePrefix, disp.keys.controllerToDisplayNoncePrefix)
        assertEquals(ctrl.keys.shortAuthString, disp.keys.shortAuthString)
    }

    @Test
    fun `derived directional keys differ from each other`() {
        val (ctrl, _) = run(controllerConfig(), displayConfig())
        assertFalse(ctrl.keys.controllerToDisplayKey.contentEquals(ctrl.keys.displayToControllerKey))
        assertFalse(
            ctrl.keys.controllerToDisplayNoncePrefix.contentEquals(ctrl.keys.displayToControllerNoncePrefix),
        )
    }

    @Test
    fun `records encrypted by one side open on the other`() {
        val (ctrl, disp) = run(controllerConfig(), displayConfig())
        val send = SendCipher(ctrl.keys.controllerToDisplayKey, ctrl.keys.controllerToDisplayNoncePrefix)
        val receive = ReceiveCipher(disp.keys.controllerToDisplayKey, disp.keys.controllerToDisplayNoncePrefix)
        assertArrayEquals("hello".toByteArray(), receive.open(send.seal("hello".toByteArray())))
    }

    @Test
    fun `peer identity and capabilities are reported`() {
        val (ctrl, disp) = run(controllerConfig(), displayConfig())
        assertEquals("display-1", ctrl.peerDeviceId)
        assertEquals("K6 Power", ctrl.peerDeviceName)
        assertEquals(DeviceRole.DISPLAY, ctrl.peerRole)
        assertArrayEquals(displayIdentity.fingerprint, ctrl.peerFingerprint)
        assertTrue(ctrl.peerCapabilities.contains(Capabilities.IMAGE))

        assertEquals("controller-1", disp.peerDeviceId)
        assertEquals(DeviceRole.CONTROLLER, disp.peerRole)
        assertArrayEquals(controllerIdentity.fingerprint, disp.peerFingerprint)
    }

    @Test
    fun `an unpaired handshake demands a short authentication string`() {
        val (ctrl, disp) = run(controllerConfig(), displayConfig())
        assertTrue(ctrl.requiresSasConfirmation)
        assertTrue(disp.requiresSasConfirmation)
        assertEquals(6, ctrl.keys.shortAuthString.length)
        assertTrue(ctrl.keys.shortAuthString.all { it.isDigit() })
    }

    @Test
    fun `two already trusted peers skip the short authentication string`() {
        val (ctrl, disp) = run(
            controllerConfig(pinned = displayIdentity.fingerprint),
            displayConfig(pinned = controllerIdentity.fingerprint),
        )
        assertFalse(ctrl.requiresSasConfirmation)
        assertFalse(disp.requiresSasConfirmation)
    }

    @Test
    fun `one side forgetting the peer forces the string back on for both`() {
        // The display forgot the controller; the controller must not be able to skip verification.
        val (ctrl, disp) = run(controllerConfig(pinned = displayIdentity.fingerprint), displayConfig())
        assertTrue(ctrl.requiresSasConfirmation)
        assertTrue(disp.requiresSasConfirmation)
    }

    @Test
    fun `the short authentication string changes between sessions`() {
        val a = run(controllerConfig(), displayConfig()).first.keys.shortAuthString
        val b = run(controllerConfig(), displayConfig()).first.keys.shortAuthString
        // Ephemeral keys are fresh every time, so a repeat would mean the derivation ignores them.
        assertFalse("SAS must not repeat across sessions", a == b)
    }

    @Test
    fun `qr pairing with a valid token skips the short authentication string`() {
        val token = randomToken()
        val (ctrl, disp) = run(
            controllerConfig(pinned = displayIdentity.fingerprint, token = token),
            displayConfig(token = token),
        )
        assertFalse(ctrl.requiresSasConfirmation)
        assertFalse(disp.requiresSasConfirmation)
        assertTrue(disp.authenticatedByPairingToken)
    }

    @Test
    fun `qr pairing with the wrong token is rejected`() {
        val i = InitiatorHandshake(controllerConfig(token = randomToken()))
        val r = ResponderHandshake(displayConfig(token = randomToken()))
        val ack = r.onHello(i.createHello())
        val confirm = i.onHelloAck(ack)
        val (result, outcome) = r.onAuthConfirm(confirm)
        assertFalse(result.accepted)
        assertEquals(ProtocolErrorCode.AUTH_FAILED, result.errorCode)
        assertNull(outcome)
    }

    @Test
    fun `a missing token proof when one was expected is rejected`() {
        val i = InitiatorHandshake(controllerConfig(token = null))
        val r = ResponderHandshake(displayConfig(token = randomToken()))
        val ack = r.onHello(i.createHello())
        val (result, outcome) = r.onAuthConfirm(i.onHelloAck(ack))
        assertFalse(result.accepted)
        assertNull(outcome)
    }

    @Test
    fun `a token proof nobody issued is rejected`() {
        val i = InitiatorHandshake(controllerConfig(token = randomToken()))
        val r = ResponderHandshake(displayConfig(token = null))
        val ack = r.onHello(i.createHello())
        val (result, outcome) = r.onAuthConfirm(i.onHelloAck(ack))
        assertFalse(result.accepted)
        assertNull(outcome)
    }

    @Test
    fun `a token cannot be replayed into a second session`() {
        val token = randomToken()
        val first = InitiatorHandshake(controllerConfig(token = token))
        val firstResponder = ResponderHandshake(displayConfig(token = token))
        val firstConfirm = first.onHelloAck(firstResponder.onHello(first.createHello()))

        // A second, independent session has its own transcript, so the captured proof is useless.
        val second = InitiatorHandshake(controllerConfig(token = token))
        val secondResponder = ResponderHandshake(displayConfig(token = token))
        second.onHelloAck(secondResponder.onHello(second.createHello()))
        val forged = firstConfirm.copy(transcriptSignature = firstConfirm.transcriptSignature)
        val (result, _) = secondResponder.onAuthConfirm(forged)
        assertFalse("a proof bound to another transcript must not verify", result.accepted)
    }

    @Test
    fun `a peer whose identity does not match the pin is rejected`() {
        val i = InitiatorHandshake(controllerConfig(pinned = attackerIdentity.fingerprint))
        val r = ResponderHandshake(displayConfig())
        val ack = r.onHello(i.createHello())
        try {
            i.onHelloAck(ack)
            fail("expected the pinned fingerprint mismatch to abort")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.AUTH_FAILED, e.errorCode)
        }
    }

    @Test
    fun `a responder rejects an initiator whose identity does not match the pin`() {
        val i = InitiatorHandshake(controllerConfig())
        val r = ResponderHandshake(displayConfig(pinned = attackerIdentity.fingerprint))
        try {
            r.onHello(i.createHello())
            fail("expected the pinned fingerprint mismatch to abort")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.AUTH_FAILED, e.errorCode)
        }
    }

    @Test
    fun `a forged signature is rejected`() {
        val i = InitiatorHandshake(controllerConfig())
        val r = ResponderHandshake(displayConfig())
        val ack = r.onHello(i.createHello())
        val confirm = i.onHelloAck(ack)
        val tampered = confirm.copy(transcriptSignature = confirm.transcriptSignature.clone().also { it[10] = (it[10] + 1).toByte() })
        val (result, outcome) = r.onAuthConfirm(tampered)
        assertFalse(result.accepted)
        assertEquals(ProtocolErrorCode.AUTH_FAILED, result.errorCode)
        assertNull(outcome)
    }

    @Test
    fun `garbage in place of a signature is rejected without crashing`() {
        val i = InitiatorHandshake(controllerConfig())
        val r = ResponderHandshake(displayConfig())
        val ack = r.onHello(i.createHello())
        val confirm = i.onHelloAck(ack)
        val (result, _) = r.onAuthConfirm(confirm.copy(transcriptSignature = ByteArray(70) { 0x41 }))
        assertFalse(result.accepted)
    }

    @Test
    fun `an initiator that claims to have signed but did not is rejected by the responder`() {
        // The attacker forwards the real controller's identity key but signs with its own.
        val i = InitiatorHandshake(controllerConfig(identity = FakeIdentity(controllerIdentity, attackerIdentity)))
        val r = ResponderHandshake(displayConfig())
        val ack = r.onHello(i.createHello())
        val (result, outcome) = r.onAuthConfirm(i.onHelloAck(ack))
        assertFalse(result.accepted)
        assertNull(outcome)
    }

    @Test
    fun `a responder that accepts without proving identity is rejected by the initiator`() {
        val i = InitiatorHandshake(controllerConfig())
        val r = ResponderHandshake(displayConfig())
        val ack = r.onHello(i.createHello())
        val confirm = i.onHelloAck(ack)
        val (result, _) = r.onAuthConfirm(confirm)
        try {
            i.onAuthResult(result.copy(transcriptSignature = null))
            fail("expected the missing responder proof to abort")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.AUTH_FAILED, e.errorCode)
        }
    }

    @Test
    fun `a responder signature over the wrong context does not verify`() {
        val i = InitiatorHandshake(controllerConfig())
        val r = ResponderHandshake(displayConfig())
        val ack = r.onHello(i.createHello())
        val confirm = i.onHelloAck(ack)
        val (result, _) = r.onAuthConfirm(confirm)
        // The initiator's own signature is over the initiator context; reusing it must not pass.
        try {
            i.onAuthResult(result.copy(transcriptSignature = confirm.transcriptSignature))
            fail("expected context separation to reject a reflected signature")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.AUTH_FAILED, e.errorCode)
        }
    }

    @Test
    fun `a rejected handshake reports the reason to the initiator`() {
        val i = InitiatorHandshake(controllerConfig())
        val r = ResponderHandshake(displayConfig())
        val ack = r.onHello(i.createHello())
        i.onHelloAck(ack)
        try {
            i.onAuthResult(
                com.avinash.relaydisplay.protocol.AuthResult(
                    java.util.UUID.randomUUID(), false, ProtocolErrorCode.RATE_LIMITED, false, null,
                ),
            )
            fail("expected rejection")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.RATE_LIMITED, e.errorCode)
        }
    }

    @Test
    fun `two devices with the same role refuse each other`() {
        val i = InitiatorHandshake(controllerConfig())
        val r = ResponderHandshake(
            HandshakeConfig(DeviceRole.CONTROLLER, "other", "Other", displayIdentity, emptySet()),
        )
        try {
            r.onHello(i.createHello())
            fail("expected same-role rejection")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.AUTH_FAILED, e.errorCode)
        }
    }

    @Test
    fun `a malformed ephemeral key is rejected without crashing`() {
        val i = InitiatorHandshake(controllerConfig())
        val r = ResponderHandshake(displayConfig())
        val hello = i.createHello()
        try {
            r.onHello(hello.copy(ephemeralPublicKey = ByteArray(64) { 0x41 }))
            fail("expected a bad EC key to abort")
        } catch (e: ProtocolException) {
            assertEquals(ProtocolErrorCode.AUTH_FAILED, e.errorCode)
        }
    }

    @Test
    fun `a machine in the middle cannot make both sides agree on one string`() {
        // The attacker terminates the controller's handshake and runs its own with the display.
        // It must present *some* identity key to each side; with nothing pinned it presents its
        // own, so the last line of defence is that the two transcripts differ.
        val controllerSide = InitiatorHandshake(controllerConfig())
        val attackerFacingController = ResponderHandshake(displayConfig(identity = attackerIdentity))
        val attackerFacingDisplay = InitiatorHandshake(controllerConfig(identity = attackerIdentity))
        val displaySide = ResponderHandshake(displayConfig())

        val hello = controllerSide.createHello()
        val attackerAck = attackerFacingController.onHello(hello)
        val controllerConfirm = controllerSide.onHelloAck(attackerAck)
        val (leftResult, leftOutcome) = attackerFacingController.onAuthConfirm(controllerConfirm)
        val controllerOutcome = controllerSide.onAuthResult(leftResult)

        val attackerHello = attackerFacingDisplay.createHello()
        val displayAck = displaySide.onHello(attackerHello)
        val attackerConfirm = attackerFacingDisplay.onHelloAck(displayAck)
        val (rightResult, displayOutcome) = displaySide.onAuthConfirm(attackerConfirm)
        attackerFacingDisplay.onAuthResult(rightResult)

        assertNotNull(leftOutcome)
        assertNotNull(displayOutcome)
        // Both legs demand user verification, and the two strings do not match, so the user aborts.
        assertTrue(controllerOutcome.requiresSasConfirmation)
        assertTrue(displayOutcome!!.requiresSasConfirmation)
        assertFalse(
            "a relayed handshake must not produce a matching short authentication string",
            controllerOutcome.keys.shortAuthString == displayOutcome.keys.shortAuthString,
        )
        // And the controller is looking at the attacker's fingerprint, not the display's.
        assertFalse(controllerOutcome.peerFingerprint.contentEquals(displayIdentity.fingerprint))
    }

    private fun randomToken(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /** Advertises one identity's public key but signs with another's. */
    private class FakeIdentity(advertised: DeviceIdentity, private val signer: DeviceIdentity) : DeviceIdentity {
        override val publicKeyEncoded = advertised.publicKeyEncoded
        override val fingerprint = advertised.fingerprint
        override fun sign(data: ByteArray) = signer.sign(data)
    }
}
