package com.avinash.relaydisplay.content

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Save As, off the main thread and honest about failure.
 *
 * The copy used to run inside the `CreateDocument` result callback, which is the main thread, for
 * files up to 50 MiB. On the API 24 phone that is seconds of frozen UI and a plausible ANR, and
 * nothing could show progress because the thread that would draw it was the thread copying.
 *
 * The dispatcher and both streams are injected, so all of this runs on the JVM with no Android
 * and no `ContentResolver`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileExporterTest {

    private fun exporter(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        FileExporter(io = dispatcher, bufferBytes = 1024)

    /** A stream that fails after a set number of bytes. */
    private class FailingOutput(private val failAfter: Int) : OutputStream() {
        var written = 0
            private set

        override fun write(b: Int) {
            if (written >= failAfter) throw IOException("no space")
            written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written + len > failAfter) throw IOException("no space")
            written += len
        }
    }

    // -- the happy path ------------------------------------------------------------------------

    @Test
    fun `a file is copied byte for byte`() = runTest {
        val payload = ByteArray(5000) { (it % 251).toByte() }
        val sink = ByteArrayOutputStream()

        val result = exporter(StandardTestDispatcher(testScheduler)).export(
            source = ByteArrayInputStream(payload),
            expectedBytes = payload.size.toLong(),
            openDestination = { sink },
        )

        assertEquals(FileExporter.ExportResult.Completed(5000L), result)
        assertArrayEquals(payload, sink.toByteArray())
    }

    @Test
    fun `an empty file is copied successfully`() = runTest {
        val sink = ByteArrayOutputStream()
        val result = exporter(StandardTestDispatcher(testScheduler)).export(
            source = ByteArrayInputStream(ByteArray(0)),
            expectedBytes = 0L,
            openDestination = { sink },
        )
        assertEquals(FileExporter.ExportResult.Completed(0L), result)
    }

    @Test
    fun `the copy runs on the injected dispatcher, not the caller`() = runTest {
        // The whole reason this class exists. Recording the thread proves the IO left the caller.
        val callerThread = Thread.currentThread().name
        var copyThread: String? = null
        val probe = object : InputStream() {
            private val inner = ByteArrayInputStream(ByteArray(2048))
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                copyThread = Thread.currentThread().name
                return inner.read(b, off, len)
            }
        }

        exporter(Dispatchers.IO).export(
            source = probe,
            expectedBytes = 2048L,
            openDestination = { ByteArrayOutputStream() },
        )

        assertTrue("the copy never ran", copyThread != null)
        assertFalse(
            "the copy ran on the calling thread ($copyThread)",
            copyThread == callerThread,
        )
    }

    @Test
    fun `the source is never held in memory whole`() = runTest {
        // A 4 MiB source through a 1 KiB buffer: if it were read whole, the largest single read
        // would be the file size.
        var largestRead = 0
        val probe = object : InputStream() {
            private var remaining = 4 * 1024 * 1024
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(len, remaining)
                largestRead = maxOf(largestRead, n)
                remaining -= n
                return n
            }
        }

        exporter(StandardTestDispatcher(testScheduler)).export(
            source = probe,
            expectedBytes = (4 * 1024 * 1024).toLong(),
            openDestination = { ByteArrayOutputStream() },
        )
        assertEquals(1024, largestRead)
    }

    // -- failure paths -------------------------------------------------------------------------

    @Test
    fun `a destination that cannot be opened is reported, not crashed`() = runTest {
        val result = exporter(StandardTestDispatcher(testScheduler)).export(
            source = ByteArrayInputStream(ByteArray(16)),
            expectedBytes = 16L,
            // What a ContentResolver returns when the provider refuses.
            openDestination = { null },
        )
        assertEquals(
            FileExporter.ExportResult.Failed(FileExporter.ExportError.DESTINATION_UNAVAILABLE),
            result,
        )
    }

    @Test
    fun `a write failure part way through is a failure, and cleanup is requested`() = runTest {
        var cleaned = false
        val result = exporter(StandardTestDispatcher(testScheduler)).export(
            source = ByteArrayInputStream(ByteArray(8192)),
            expectedBytes = 8192L,
            openDestination = { FailingOutput(failAfter = 2048) },
            onCleanupDestination = { cleaned = true },
        )
        assertEquals(
            FileExporter.ExportResult.Failed(FileExporter.ExportError.WRITE_FAILED),
            result,
        )
        // A truncated file sitting where the user asked for a whole one is misleading, so removal
        // is requested. Doing it is the caller's job: only it can delete through the resolver.
        assertTrue("a half-written destination must be cleaned up", cleaned)
    }

    @Test
    fun `a short copy is never reported as success`() = runTest {
        // The source ends early. Claiming success would hand the user a truncated file they would
        // only discover when something later failed to open it.
        var cleaned = false
        val result = exporter(StandardTestDispatcher(testScheduler)).export(
            source = ByteArrayInputStream(ByteArray(100)),
            expectedBytes = 5000L,
            openDestination = { ByteArrayOutputStream() },
            onCleanupDestination = { cleaned = true },
        )
        assertEquals(
            FileExporter.ExportResult.Failed(FileExporter.ExportError.INCOMPLETE),
            result,
        )
        assertTrue(cleaned)
    }

    @Test
    fun `a security failure on the destination is reported`() = runTest {
        val result = exporter(StandardTestDispatcher(testScheduler)).export(
            source = ByteArrayInputStream(ByteArray(16)),
            expectedBytes = 16L,
            openDestination = { throw SecurityException("grant gone") },
        )
        assertEquals(
            FileExporter.ExportResult.Failed(FileExporter.ExportError.DESTINATION_UNAVAILABLE),
            result,
        )
    }

    @Test
    fun `an unknown expected size skips the completeness check`() = runTest {
        // Negative means "do not check": there is nothing to compare against.
        val result = exporter(StandardTestDispatcher(testScheduler)).export(
            source = ByteArrayInputStream(ByteArray(64)),
            expectedBytes = -1L,
            openDestination = { ByteArrayOutputStream() },
        )
        assertEquals(FileExporter.ExportResult.Completed(64L), result)
    }

    @Test
    fun `both streams are closed even when the write fails`() = runTest {
        var sourceClosed = false
        var sinkClosed = false
        val source = object : InputStream() {
            private val inner = ByteArrayInputStream(ByteArray(8192))
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun close() {
                sourceClosed = true
            }
        }
        val sink = object : OutputStream() {
            override fun write(b: Int) = throw IOException("no space")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("no space")
            override fun close() {
                sinkClosed = true
            }
        }

        exporter(StandardTestDispatcher(testScheduler)).export(
            source = source,
            expectedBytes = 8192L,
            openDestination = { sink },
        )
        assertTrue("the source stream leaked", sourceClosed)
        assertTrue("the destination stream leaked", sinkClosed)
    }

    @Test
    fun `zero byte reads do not end the copy early`() = runTest {
        val payload = ByteArray(3000) { 9 }
        val sink = ByteArrayOutputStream()
        val source = object : InputStream() {
            private val inner = ByteArrayInputStream(payload)
            private var stall = 0
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                stall++
                if (stall % 2 == 1) return 0
                return inner.read(b, off, len)
            }
        }

        val result = exporter(StandardTestDispatcher(testScheduler)).export(
            source = source,
            expectedBytes = 3000L,
            openDestination = { sink },
        )
        assertEquals(FileExporter.ExportResult.Completed(3000L), result)
        assertArrayEquals(payload, sink.toByteArray())
    }

    @Test
    fun `cancelling mid copy requests cleanup and propagates`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                started.complete(Unit)
                Thread.sleep(20)
                return len
            }
        }

        // A real dispatcher: the copy loop is blocking, so on runTest's single-threaded scheduler
        // it would starve this coroutine instead of being cancellable.
        val job = async(Dispatchers.Default) {
            exporter(Dispatchers.IO).export(
                source = endless,
                expectedBytes = -1L,
                openDestination = { ByteArrayOutputStream() },
                onCleanupDestination = { cleaned = true },
            )
        }
        started.await()
        job.cancel()

        var cancelled = false
        try {
            job.await()
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue("a cancelled save must clean up its destination", cleaned)
    }
}
