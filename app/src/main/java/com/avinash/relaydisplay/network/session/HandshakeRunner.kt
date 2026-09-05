package com.avinash.relaydisplay.network.session

import com.avinash.relaydisplay.network.transport.SecureConnection
import com.avinash.relaydisplay.protocol.AuthConfirm
import com.avinash.relaydisplay.protocol.AuthResult
import com.avinash.relaydisplay.protocol.Hello
import com.avinash.relaydisplay.protocol.HelloAck
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.ProtocolException
import com.avinash.relaydisplay.security.HandshakeConfig
import com.avinash.relaydisplay.security.HandshakeOutcome
import com.avinash.relaydisplay.security.InitiatorHandshake
import com.avinash.relaydisplay.security.ReceiveCipher
import com.avinash.relaydisplay.security.ResponderHandshake
import com.avinash.relaydisplay.security.SendCipher
import com.avinash.relaydisplay.security.SessionKeys
import com.avinash.relaydisplay.domain.model.DeviceRole

/**
 * Drives one handshake over a [SecureConnection] and leaves it sealed on success.
 *
 * Every failure path here closes nothing and throws: the caller owns the connection and is the
 * one place that decides whether to retry, so cleanup stays in one spot.
 *
 * The blocking reads are intentional; the caller runs this inside a `withTimeout` on an IO
 * dispatcher, and a timeout closes the link, which unblocks the read.
 */
object HandshakeRunner {

    /** Controller side. */
    fun runInitiator(connection: SecureConnection, config: HandshakeConfig): HandshakeOutcome {
        val handshake = InitiatorHandshake(config)
        connection.write(handshake.createHello())

        val ack = expect<HelloAck>(connection, "HELLO_ACK")
        val confirm = handshake.onHelloAck(ack)
        connection.write(confirm)

        val result = expect<AuthResult>(connection, "AUTH_RESULT")
        val outcome = handshake.onAuthResult(result)
        connection.activate(
            send = sendCipherFor(config.role, outcome.keys),
            receive = receiveCipherFor(config.role, outcome.keys),
        )
        return outcome
    }

    /** Display side. */
    fun runResponder(connection: SecureConnection, config: HandshakeConfig): HandshakeOutcome {
        val handshake = ResponderHandshake(config)

        val hello = expect<Hello>(connection, "HELLO")
        connection.write(handshake.onHello(hello))

        val confirm = expect<AuthConfirm>(connection, "AUTH_CONFIRM")
        val (result, outcome) = handshake.onAuthConfirm(confirm)
        // Tell the peer why before hanging up, so its UI can say something useful.
        connection.write(result)
        if (outcome == null) {
            throw ProtocolException(result.errorCode, "handshake rejected")
        }
        connection.activate(
            send = sendCipherFor(config.role, outcome.keys),
            receive = receiveCipherFor(config.role, outcome.keys),
        )
        return outcome
    }

    /**
     * Which of the two directional keys this device writes with.
     *
     * Keyed off role rather than initiator/responder so the mapping stays correct if a display
     * ever dials out to a controller.
     */
    private fun sendCipherFor(role: DeviceRole, keys: SessionKeys): SendCipher = when (role) {
        DeviceRole.CONTROLLER -> SendCipher(keys.controllerToDisplayKey, keys.controllerToDisplayNoncePrefix)
        DeviceRole.DISPLAY -> SendCipher(keys.displayToControllerKey, keys.displayToControllerNoncePrefix)
    }

    private fun receiveCipherFor(role: DeviceRole, keys: SessionKeys): ReceiveCipher = when (role) {
        DeviceRole.CONTROLLER -> ReceiveCipher(keys.displayToControllerKey, keys.displayToControllerNoncePrefix)
        DeviceRole.DISPLAY -> ReceiveCipher(keys.controllerToDisplayKey, keys.controllerToDisplayNoncePrefix)
    }

    /**
     * Reads the next message and insists it is the one the handshake expects.
     *
     * A peer that sends anything else at this point is either broken or probing; either way the
     * handshake ends rather than trying to interpret it.
     */
    private inline fun <reified T> expect(connection: SecureConnection, what: String): T {
        val message = connection.read()
            ?: throw ProtocolException(ProtocolErrorCode.MALFORMED_FRAME, "peer closed before $what")
        return message as? T
            ?: throw ProtocolException(
                ProtocolErrorCode.MALFORMED_FRAME,
                "expected $what, got ${message.type}",
            )
    }
}
