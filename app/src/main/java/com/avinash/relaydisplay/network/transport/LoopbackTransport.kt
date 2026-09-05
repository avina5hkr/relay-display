package com.avinash.relaydisplay.network.transport

import com.avinash.relaydisplay.network.session.Endpoint
import com.avinash.relaydisplay.protocol.Frame
import com.avinash.relaydisplay.protocol.FrameCodec
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A bounded byte pipe, deliberately not `PipedInputStream`.
 *
 * `PipedInputStream` remembers which *thread* wrote to it and throws "write end dead" once that
 * thread exits. Coroutines hop threads inside a dispatcher pool, so a piped stream fails
 * randomly under exactly the code this is meant to test. This implementation cares only about
 * whether the writer was closed.
 *
 * The fixed capacity is a feature: a writer that outruns the reader blocks, which is what makes
 * the backpressure tests meaningful.
 */
class BytePipe(private val capacity: Int = 64 * 1024) {

    private val buffer = ByteArray(capacity)
    private var head = 0
    private var size = 0
    private var writerClosed = false
    private var readerClosed = false
    private val lock = Object()

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            synchronized(lock) {
                while (size == 0) {
                    if (writerClosed) return -1
                    if (readerClosed) throw IOException("pipe closed")
                    lock.wait()
                }
                val n = minOf(len, size)
                for (i in 0 until n) {
                    b[off + i] = buffer[(head + i) % capacity]
                }
                head = (head + n) % capacity
                size -= n
                lock.notifyAll()
                return n
            }
        }

        override fun available(): Int = synchronized(lock) { size }

        override fun close() {
            synchronized(lock) {
                readerClosed = true
                lock.notifyAll()
            }
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var written = 0
            while (written < len) {
                synchronized(lock) {
                    while (size == capacity) {
                        if (readerClosed || writerClosed) throw IOException("pipe closed")
                        lock.wait()
                    }
                    if (writerClosed) throw IOException("pipe closed")
                    val n = minOf(len - written, capacity - size)
                    val tail = (head + size) % capacity
                    for (i in 0 until n) {
                        buffer[(tail + i) % capacity] = b[off + written + i]
                    }
                    size += n
                    written += n
                    lock.notifyAll()
                }
            }
        }

        override fun close() {
            synchronized(lock) {
                writerClosed = true
                lock.notifyAll()
            }
        }
    }

    fun closeBoth() {
        input.close()
        output.close()
    }
}

/**
 * Two in-process links joined back to back.
 *
 * This is what lets the protocol, handshake, transfer and reconnect logic be tested end to end
 * without two phones or even a socket. The fault-injection hooks live on [LoopbackLink] so
 * production transports carry none of it.
 */
class LoopbackPair(bufferBytes: Int = 256 * 1024) {
    private val aToB = BytePipe(bufferBytes)
    private val bToA = BytePipe(bufferBytes)

    val clientSide: LoopbackLink = LoopbackLink(bToA, aToB, Endpoint("loopback-server", 1))
    val serverSide: LoopbackLink = LoopbackLink(aToB, bToA, Endpoint("loopback-client", 2))

    fun closeBoth() {
        clientSide.close()
        serverSide.close()
    }
}

class LoopbackLink(
    private val readPipe: BytePipe,
    private val writePipe: BytePipe,
    override val remote: Endpoint,
) : RelayLink {

    private val closed = AtomicBoolean(false)

    /** Drop the link once this many frames have been written. -1 disables. */
    @Volatile
    var failAfterWrites: Int = -1

    /** Rewrites a frame on its way out, to corrupt a tag or a digest. */
    @Volatile
    var corrupt: ((Frame) -> Frame)? = null

    @Volatile
    var framesWritten: Int = 0
        private set

    override val isClosed: Boolean get() = closed.get()

    override fun readFrame(): Frame? {
        if (closed.get()) return null
        return try {
            FrameCodec.readFrame(readPipe.input)
        } catch (e: IOException) {
            // A closed pipe reads as an IOException; to the session that is a disconnect.
            null
        }
    }

    override fun writeFrame(frame: Frame) {
        if (closed.get()) throw IOException("link closed")
        val limit = failAfterWrites
        if (limit >= 0 && framesWritten >= limit) {
            close()
            throw IOException("injected link failure")
        }
        framesWritten++
        FrameCodec.writeFrame(writePipe.output, corrupt?.invoke(frame) ?: frame)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        writePipe.output.close()
        readPipe.input.close()
    }
}
