package com.avinash.relaydisplay.network.session

import com.avinash.relaydisplay.network.transport.SecureConnection
import com.avinash.relaydisplay.protocol.Ack
import com.avinash.relaydisplay.protocol.Bye
import com.avinash.relaydisplay.protocol.ErrorMessage
import com.avinash.relaydisplay.protocol.Ping
import com.avinash.relaydisplay.protocol.Pong
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.ProtocolException
import com.avinash.relaydisplay.protocol.ProtocolTimings
import com.avinash.relaydisplay.protocol.RelayMessage
import com.avinash.relaydisplay.security.HandshakeOutcome
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the session tells the rest of the app. Implementations must not block. */
interface SessionListener {
    fun onMessage(message: RelayMessage)

    /** Called exactly once, whatever ends the session. [error] is null for a clean close. */
    fun onClosed(error: RelayError?)
}

/**
 * One live, authenticated connection.
 *
 * Structure:
 *  - a reader coroutine owns [SecureConnection.read] and is the only thing that decodes,
 *  - a writer coroutine drains an outbound channel so writes are ordered and never concurrent,
 *  - a heartbeat coroutine pings on a fixed interval and gives up on a peer that stops answering.
 *
 * Everything hangs off one [Job], so cancelling the session cancels all three and closes the
 * connection exactly once. [close] is idempotent and safe from any of them.
 */
class RelaySession(
    private val connection: SecureConnection,
    val outcome: HandshakeOutcome,
    private val listener: SessionListener,
    parentScope: CoroutineScope,
    private val heartbeatIntervalMs: Long = ProtocolTimings.HEARTBEAT_INTERVAL_MS,
    private val deadPeerThresholdMs: Long = ProtocolTimings.DEAD_PEER_THRESHOLD_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val job = Job(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val outbound = Channel<RelayMessage>(capacity = OUTBOUND_QUEUE)
    private val closed = AtomicBoolean(false)

    /**
     * Ids of commands already applied.
     *
     * A reconnect can legitimately replay a command the peer is not sure landed. Applying it
     * twice must be harmless, and for commands that are not naturally idempotent this set is
     * what makes it so. Bounded, because a peer could otherwise grow it without limit.
     */
    private val appliedCommands = object : LinkedHashMap<UUID, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Boolean>?): Boolean =
            size > MAX_REMEMBERED_COMMANDS
    }

    @Volatile
    private var lastInboundAtMs: Long = nowMs()

    val remote get() = connection.remote

    fun start() {
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) { writeLoop() }
        scope.launch { heartbeatLoop() }
    }

    /**
     * Queues a message.
     *
     * Returns false when the queue is full, which is the backpressure signal: the caller (a bulk
     * transfer) must wait rather than buffer more in memory.
     */
    fun trySend(message: RelayMessage): Boolean = outbound.trySend(message).isSuccess

    /** Suspends until the message is queued. Used by bulk transfers to apply backpressure. */
    suspend fun send(message: RelayMessage) {
        try {
            outbound.send(message)
        } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
            throw IOException("session closed", e)
        }
    }

    /**
     * True the first time this id is seen.
     *
     * Callers apply a command only when this returns true, and acknowledge either way, so a
     * replay after reconnect is acknowledged without being applied a second time.
     */
    fun claimCommand(id: UUID): Boolean = synchronized(appliedCommands) {
        appliedCommands.put(id, true) == null
    }

    private suspend fun readLoop() {
        var failure: RelayError? = null
        try {
            while (scope.isActive) {
                val message = connection.read()
                if (message == null) {
                    // Clean end of stream: the peer hung up or the link was closed under us.
                    break
                }
                lastInboundAtMs = nowMs()
                when (message) {
                    is Ping -> trySend(Pong(UUID.randomUUID(), message.timestampMs))
                    is Pong -> Unit // Liveness only; lastInboundAtMs above is the whole point.
                    else -> listener.onMessage(message)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProtocolException) {
            // A hostile or broken peer. Tell it why, then drop it.
            failure = when (e.errorCode) {
                ProtocolErrorCode.NOT_AUTHENTICATED, ProtocolErrorCode.AUTH_FAILED ->
                    RelayError.Untrusted(e.errorCode.name)
                else -> RelayError.ProtocolFailure(e.errorCode.name)
            }
            runCatching { connection.write(ErrorMessage(UUID.randomUUID(), e.errorCode, e.errorCode.name)) }
        } catch (e: IOException) {
            failure = RelayError.ConnectFailed(e.javaClass.simpleName)
        } finally {
            closeInternal(failure)
        }
    }

    private suspend fun writeLoop() {
        try {
            for (message in outbound) {
                withContext(Dispatchers.IO) { connection.write(message) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            closeInternal(RelayError.ConnectFailed(e.javaClass.simpleName))
        } catch (e: ProtocolException) {
            closeInternal(RelayError.ProtocolFailure(e.errorCode.name))
        }
    }

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            delay(heartbeatIntervalMs)
            if (!scope.isActive) return
            val silentFor = nowMs() - lastInboundAtMs
            if (silentFor > deadPeerThresholdMs) {
                closeInternal(RelayError.ConnectFailed("peer silent for ${silentFor}ms"))
                return
            }
            // A ping that cannot even be queued means the writer is wedged; treat it as dead.
            if (!trySend(Ping(UUID.randomUUID(), nowMs()))) {
                closeInternal(RelayError.ConnectFailed("outbound queue full"))
                return
            }
        }
    }

    /** Sends a courtesy goodbye, then closes. Best effort: a dead link just closes. */
    fun closeGracefully(reason: String) {
        runCatching { connection.write(Bye(UUID.randomUUID(), reason.take(64))) }
        closeInternal(null)
    }

    fun close() = closeInternal(null)

    private fun closeInternal(error: RelayError?) {
        if (!closed.compareAndSet(false, true)) return
        outbound.close()
        connection.close()
        outcome.keys.destroy()
        job.cancel()
        listener.onClosed(error)
    }

    /** Convenience for acknowledging a command the peer asked us to confirm. */
    fun acknowledge(refId: UUID, ok: Boolean, code: ProtocolErrorCode = ProtocolErrorCode.UNKNOWN) {
        trySend(Ack(UUID.randomUUID(), refId, ok, code))
    }

    private companion object {
        /**
         * Deep enough that a burst of transfer chunks does not stall on every send, shallow
         * enough that a slow peer produces backpressure instead of unbounded memory growth.
         */
        const val OUTBOUND_QUEUE = 8
        const val MAX_REMEMBERED_COMMANDS = 256
    }
}
