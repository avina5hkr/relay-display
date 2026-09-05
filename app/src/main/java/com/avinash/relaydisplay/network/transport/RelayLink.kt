package com.avinash.relaydisplay.network.transport

import com.avinash.relaydisplay.network.session.Endpoint
import com.avinash.relaydisplay.protocol.Frame
import java.io.Closeable

/**
 * One bidirectional framed connection to a peer.
 *
 * Reads block. That is deliberate: the session loop runs on [kotlinx.coroutines.Dispatchers.IO]
 * and cancellation closes the link, which unblocks the read. A non-blocking selector loop would
 * buy nothing for a single connection and cost a lot of correctness.
 *
 * Every implementation must make [close] idempotent, because it is called from the reader, from
 * the writer, and from cancellation.
 */
interface RelayLink : Closeable {
    val remote: Endpoint

    /** Returns null at a clean end of stream. Throws for a malformed or truncated frame. */
    fun readFrame(): Frame?

    fun writeFrame(frame: Frame)

    val isClosed: Boolean
}

/** Dials out to a peer. */
interface RelayDialer {
    /** @throws java.io.IOException when the peer does not answer within [timeoutMs]. */
    fun connect(endpoint: Endpoint, timeoutMs: Int): RelayLink
}

/** Accepts one peer at a time. */
interface RelayListener : Closeable {
    /** The port actually bound. Chosen by the OS unless a specific port was requested. */
    val port: Int

    /** Blocks until a peer connects, or throws when the listener is closed. */
    fun accept(): RelayLink

    val isClosed: Boolean
}
