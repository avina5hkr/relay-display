package com.avinash.relaydisplay.network.transport

import com.avinash.relaydisplay.protocol.Frame
import com.avinash.relaydisplay.protocol.MessageCodec
import com.avinash.relaydisplay.protocol.ProtocolConstants
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.ProtocolException
import com.avinash.relaydisplay.protocol.RelayMessage
import com.avinash.relaydisplay.security.ReceiveCipher
import com.avinash.relaydisplay.security.SendCipher
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A [RelayLink] plus the rule about what may travel over it at each stage.
 *
 * Before [activate] only handshake messages are allowed, and they travel in the clear because
 * there is no key yet. After [activate] every record is sealed, and a plaintext frame is a
 * protocol violation rather than something to tolerate: accepting one would be a downgrade an
 * attacker could force by stripping the encrypted flag.
 *
 * Writes are serialised by a lock. Reads are single threaded by construction (one reader
 * coroutine owns the session), which is also what the receive cipher's sequence check assumes.
 */
class SecureConnection(private val link: RelayLink) : Closeable {

    private val writeLock = ReentrantLock()

    @Volatile
    private var sendCipher: SendCipher? = null

    @Volatile
    private var receiveCipher: ReceiveCipher? = null

    val remote get() = link.remote

    val isSecure: Boolean get() = sendCipher != null && receiveCipher != null

    val isClosed: Boolean get() = link.isClosed

    /** Switches the connection to sealed records. Not reversible for the life of the link. */
    fun activate(send: SendCipher, receive: ReceiveCipher) {
        check(sendCipher == null) { "connection already secured" }
        sendCipher = send
        receiveCipher = receive
    }

    fun write(message: RelayMessage) {
        val record = MessageCodec.encode(message)
        val cipher = sendCipher
        if (cipher == null) {
            if (message.type.requiresSecureSession) {
                throw ProtocolException(
                    ProtocolErrorCode.NOT_AUTHENTICATED,
                    "${message.type} may not be sent before the session is secured",
                )
            }
            writeLock.withLock {
                link.writeFrame(
                    Frame(
                        ProtocolConstants.VERSION_MAJOR,
                        ProtocolConstants.VERSION_MINOR,
                        0,
                        0,
                        record,
                    ),
                )
            }
        } else {
            // Sealing bumps the cipher's sequence, so it has to happen under the same lock that
            // orders the writes; otherwise two threads could emit records out of sequence order.
            writeLock.withLock { link.writeFrame(cipher.seal(record)) }
        }
    }

    /** Returns null at a clean end of stream. */
    fun read(): RelayMessage? {
        val frame = link.readFrame() ?: return null
        val cipher = receiveCipher
        val record = if (cipher != null) {
            cipher.open(frame)
        } else {
            if (frame.isEncrypted) {
                throw ProtocolException(
                    ProtocolErrorCode.NOT_AUTHENTICATED,
                    "encrypted frame before any key was agreed",
                )
            }
            frame.payload
        }
        val message = MessageCodec.decode(record)
        if (cipher == null && message.type.requiresSecureSession) {
            throw ProtocolException(
                ProtocolErrorCode.NOT_AUTHENTICATED,
                "${message.type} arrived before the session was secured",
            )
        }
        return message
    }

    override fun close() {
        link.close()
    }
}
