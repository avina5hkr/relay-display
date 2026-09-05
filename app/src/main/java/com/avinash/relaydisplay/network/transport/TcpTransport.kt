package com.avinash.relaydisplay.network.transport

import com.avinash.relaydisplay.network.session.Endpoint
import com.avinash.relaydisplay.protocol.Frame
import com.avinash.relaydisplay.protocol.FrameCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** A [RelayLink] over a TCP socket. */
class TcpRelayLink(private val socket: Socket) : RelayLink {

    private val closed = AtomicBoolean(false)

    // Buffered so a frame header and its payload usually arrive in one syscall, and so a chunk
    // write does not turn into dozens of small packets.
    private val input = BufferedInputStream(socket.getInputStream(), BUFFER_BYTES)
    private val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES)

    override val remote: Endpoint =
        Endpoint(socket.inetAddress?.hostAddress ?: "unknown", socket.port)

    override val isClosed: Boolean get() = closed.get() || socket.isClosed

    override fun readFrame(): Frame? = FrameCodec.readFrame(input)

    override fun writeFrame(frame: Frame) {
        FrameCodec.writeFrame(output, frame)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Close the socket rather than the streams: it is what unblocks a thread parked in read().
        try {
            socket.close()
        } catch (e: IOException) {
            // Already gone; nothing to recover.
        }
    }

    companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

class TcpDialer : RelayDialer {
    override fun connect(endpoint: Endpoint, timeoutMs: Int): RelayLink {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            TcpRelayLink(socket)
        } catch (e: IOException) {
            socket.closeQuietly()
            throw e
        } catch (e: IllegalArgumentException) {
            // An out-of-range port from a hostile pairing payload lands here.
            socket.closeQuietly()
            throw IOException("invalid endpoint", e)
        }
    }
}

/**
 * A TCP listener on an OS-chosen port.
 *
 * Binding to port 0 and then advertising the port the OS actually gave us avoids the whole class
 * of "our fixed port was taken" failures, and means two RelayDisplay installs on one network
 * never collide.
 */
class TcpListener(requestedPort: Int = 0, backlog: Int = 2) : RelayListener {

    private val server = ServerSocket(requestedPort, backlog)
    private val closed = AtomicBoolean(false)

    override val port: Int get() = server.localPort

    override val isClosed: Boolean get() = closed.get() || server.isClosed

    override fun accept(): RelayLink {
        val socket = server.accept()
        socket.tcpNoDelay = true
        return TcpRelayLink(socket)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            server.close()
        } catch (e: IOException) {
            // Already closed.
        }
    }
}

internal fun Socket.closeQuietly() {
    try {
        close()
    } catch (e: IOException) {
        // Best effort.
    } catch (e: SocketException) {
        // Best effort.
    }
}
