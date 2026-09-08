package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.domain.model.ContentKind
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spooling: read the picked source exactly once, and send those bytes.
 *
 * The previous design opened each source twice, once to measure and digest it and once to
 * transmit. That is fine for a local file and wrong for a `ContentResolver` in general. A provider
 * may hand back a one-shot stream, regenerate different bytes, or be gone entirely the second
 * time, and the failure modes ranged from a confusing digest mismatch to sending bytes that no
 * longer matched what the user was shown and the receiver had agreed to.
 *
 * Every source below is one of those shapes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSpoolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun target(name: String = "out.spool") = File(temp.newFolder("spool-${UUID.randomUUID()}"), name)

    /** A source whose stream behaviour is entirely under the test's control. */
    private class ScriptedSource(
        private val streams: List<() -> InputStream>,
        override val sizeBytes: Long = -1L,
    ) : ContentSource {
        override val mimeType = "application/octet-stream"
        override val displayName = "thing.bin"
        override val kind = ContentKind.FILE
        var opens = 0
            private set

        override fun openStream(): InputStream {
            val index = opens
            opens++
            val factory = streams.getOrNull(index)
                ?: throw IOException("the provider will not open this again")
            return factory()
        }
    }

    // -- the happy path ------------------------------------------------------------------------

    @Test
    fun `spooling copies measures and digests in one pass`() = runTest {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val out = target()
        val source = ScriptedSource(listOf({ ByteArrayInputStream(bytes) }))

        val result = source.spoolTo(out) as SpoolResult.Ready

        assertEquals(1, source.opens)
        assertEquals(5000L, result.sizeBytes)
        assertArrayEquals(sha256(bytes), result.sha256)
        // The bytes on disk are the bytes described, which is the whole point.
        assertArrayEquals(bytes, out.readBytes())
    }

    @Test
    fun `a zero byte source spools successfully`() {
        // An empty file is a real file, and its digest is the digest of nothing.
        runTest {
            val out = target()
            val result = ScriptedSource(listOf({ ByteArrayInputStream(ByteArray(0)) })).spoolTo(out)
            result as SpoolResult.Ready
            assertEquals(0L, result.sizeBytes)
            assertArrayEquals(sha256(ByteArray(0)), result.sha256)
            assertTrue(out.exists())
            assertEquals(0L, out.length())
        }
    }

    @Test
    fun `progress is reported as bytes are copied`() = runTest {
        val seen = mutableListOf<Long>()
        val out = target()
        ScriptedSource(listOf({ ByteArrayInputStream(ByteArray(200_000)) }))
            .spoolTo(out, bufferBytes = 16_384) { seen += it }
        assertTrue("expected several progress callbacks, got ${seen.size}", seen.size > 3)
        assertEquals(200_000L, seen.last())
        // Monotonic: a progress bar that goes backwards is worse than none.
        assertEquals(seen.sorted(), seen)
    }

    // -- the hostile source shapes -------------------------------------------------------------

    @Test
    fun `a source that can only be opened once still works`() {
        runTest {
            val bytes = ByteArray(4096) { 7 }
            val source = ScriptedSource(listOf({ ByteArrayInputStream(bytes) }))
            val result = source.spoolTo(target()) as SpoolResult.Ready
            assertEquals(1, source.opens)
            assertEquals(4096L, result.sizeBytes)
            // Proof the old design would have broken here: a second open throws.
            var threw = false
            try {
                source.openStream()
            } catch (e: IOException) {
                threw = true
            }
            assertTrue("the fixture must refuse a second open", threw)
        }
    }

    @Test
    fun `a source that changes on its second open cannot affect what was spooled`() = runTest {
        val first = ByteArray(1024) { 1 }
        val second = ByteArray(1024) { 2 }
        val source = ScriptedSource(
            listOf({ ByteArrayInputStream(first) }, { ByteArrayInputStream(second) }),
        )
        val out = target()
        val result = source.spoolTo(out) as SpoolResult.Ready

        // The digest describes the first read, and the spool file holds the first read. Nothing
        // reopens the source, so the second version is unreachable and the transfer stays
        // self-consistent.
        assertArrayEquals(sha256(first), result.sha256)
        assertArrayEquals(first, out.readBytes())
        assertEquals(1, source.opens)
    }

    @Test
    fun `a source that exceeds the limit while reading is refused and leaves nothing behind`() =
        runTest {
            // Declares 10 bytes, delivers a megabyte: the limit has to be enforced during the
            // read, not from the declaration.
            val source = ScriptedSource(
                listOf({ ByteArrayInputStream(ByteArray(1024 * 1024)) }),
                sizeBytes = 10L,
            )
            val out = target()
            val result = source.spoolTo(out, maxBytes = 4096)
            assertEquals(SpoolResult.Failed(ContentSourceError.TOO_LARGE), result)
            assertFalse("a refused spool must not survive", out.exists())
        }

    @Test
    fun `a source exactly at the limit is accepted`() = runTest {
        val out = target()
        val result = ScriptedSource(listOf({ ByteArrayInputStream(ByteArray(4096)) }))
            .spoolTo(out, maxBytes = 4096)
        assertEquals(4096L, (result as SpoolResult.Ready).sizeBytes)
    }

    @Test
    fun `a source that throws part way through is reported and leaves nothing behind`() = runTest {
        val out = target()
        val source = ScriptedSource(
            listOf({
                object : InputStream() {
                    private var served = 0
                    override fun read(): Int = throw IOException("not used")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (served >= 8192) throw IOException("the provider gave up")
                        served += len
                        return len
                    }
                }
            }),
        )
        val result = source.spoolTo(out)
        assertEquals(SpoolResult.Failed(ContentSourceError.UNREADABLE), result)
        assertFalse("a failed spool must not survive", out.exists())
    }

    @Test
    fun `zero byte reads are not mistaken for end of stream`() = runTest {
        // A provider may legitimately return 0 without being finished. Treating that as EOF would
        // silently truncate the file, and the receiver would reject a short transfer.
        val payload = ByteArray(3000) { (it % 97).toByte() }
        val out = target()
        val source = ScriptedSource(
            listOf({
                object : InputStream() {
                    private val inner = ByteArrayInputStream(payload)
                    private var stall = 0
                    override fun read(): Int = inner.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        // Every other call returns nothing without being at the end.
                        stall++
                        if (stall % 2 == 1) return 0
                        return inner.read(b, off, len)
                    }
                }
            }),
        )
        val result = source.spoolTo(out) as SpoolResult.Ready
        assertEquals(3000L, result.sizeBytes)
        assertArrayEquals(payload, out.readBytes())
    }

    @Test
    fun `a security failure on open is reported as unreadable`() = runTest {
        val out = target()
        val source = ScriptedSource(listOf({ throw SecurityException("grant expired") }))
        assertEquals(SpoolResult.Failed(ContentSourceError.UNREADABLE), source.spoolTo(out))
        assertFalse(out.exists())
    }

    @Test
    fun `cancelling during preparation deletes the partial spool file`() = runTest {
        val started = CompletableDeferred<Unit>()
        val out = target()
        val source = ScriptedSource(
            listOf({
                object : InputStream() {
                    override fun read(): Int = 0
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        started.complete(Unit)
                        // Paced rather than a tight loop, so cancellation lands promptly and the
                        // test does not write megabytes of scratch data first.
                        Thread.sleep(20)
                        return len
                    }
                }
            }),
        )

        // A real dispatcher on purpose: spoolTo's copy loop is blocking and never suspends, so
        // running it on runTest's single-threaded scheduler would starve this coroutine and the
        // test would time out rather than exercise cancellation.
        val job = async(Dispatchers.Default) { source.spoolTo(out, maxBytes = 64L * 1024 * 1024) }
        started.await()
        job.cancel()

        var cancelled = false
        try {
            job.await()
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue("the spool should have been cancelled", cancelled)
        // A cancelled preparation must not leave a copy of the user's document in the cache.
        assertFalse("a cancelled spool must not survive", out.exists())
    }

    // -- the spool directory -------------------------------------------------------------------

    @Test
    fun `the spool names files by transfer id and sweeps them all`() {
        val root = temp.newFolder("cache")
        val spool = SourceSpool(root)
        val a = spool.fileFor(UUID.randomUUID()).also { it.writeBytes(ByteArray(4)) }
        val b = spool.fileFor(UUID.randomUUID()).also { it.writeBytes(ByteArray(4)) }

        assertTrue(a.exists() && b.exists())
        assertEquals(2, spool.deleteAll())
        assertFalse(a.exists())
        assertFalse(b.exists())
        // Idempotent: a second sweep finds nothing and does not fail.
        assertEquals(0, spool.deleteAll())
    }

    @Test
    fun `the spool only deletes its own files`() {
        val root = temp.newFolder("cache2")
        val spool = SourceSpool(root)
        spool.fileFor(UUID.randomUUID())
        val outsider = File(root, "not-mine.txt").also { it.writeText("keep me") }

        spool.delete(outsider)
        assertTrue("a file outside the spool must not be deleted", outsider.exists())
    }
}
