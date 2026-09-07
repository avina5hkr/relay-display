package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.domain.model.ContentKind
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preparation pass: measure and digest a source before anything is offered.
 *
 * This is what resolved the unknown-size contradiction. A `ContentResolver` is not obliged to
 * report a size, and the two halves of the app disagreed about what to do: the picker refused
 * anything without a length (`size <= 0`, which also refused legitimately empty files), while the
 * protocol and receiver were documented as supporting an unknown one. Measuring settles it without
 * a policy argument, and costs nothing -- the sender already had to read the whole stream once to
 * compute the SHA-256 the offer carries.
 */
class ContentPreparationTest {

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** A source that lies about its length, the way a real provider sometimes does. */
    private class LyingSource(
        private val actual: ByteArray,
        override val sizeBytes: Long,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : ContentSource {
        override val mimeType = "application/octet-stream"
        override val displayName = "thing.bin"
        override val kind = ContentKind.FILE
        var opens = 0
            private set

        override fun openStream(): InputStream {
            opens++
            val underlying = ByteArrayInputStream(actual)
            return object : InputStream() {
                private var read = 0
                override fun read(): Int {
                    if (read >= failAfter) throw IOException("provider gave up")
                    read++
                    return underlying.read()
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (read >= failAfter) throw IOException("provider gave up")
                    val n = underlying.read(b, off, len)
                    if (n > 0) read += n
                    return n
                }
            }
        }
    }

    // -- measurement ------------------------------------------------------------------------

    @Test
    fun `the measured size and digest describe the same bytes`() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val result = ByteArrayContentSource(bytes, "application/zip", "a.zip", ContentKind.FILE).prepare()
        assertTrue(result is PrepareResult.Ready)
        result as PrepareResult.Ready
        assertEquals(5000L, result.sizeBytes)
        assertArrayEquals(sha256(bytes), result.sha256)
    }

    @Test
    fun `an unknown declared size is resolved by measuring`() {
        // The case that used to be refused outright at the picker.
        val bytes = ByteArray(777) { 3 }
        val source = LyingSource(bytes, sizeBytes = FileTransferPolicy.SIZE_UNKNOWN)
        val result = source.prepare() as PrepareResult.Ready
        assertEquals(777L, result.sizeBytes)
        assertArrayEquals(sha256(bytes), result.sha256)
    }

    @Test
    fun `an over-reported size is corrected rather than trusted`() {
        // A provider claiming 10 MB for a 100-byte stream used to end in "the file changed while
        // sending" after the transfer had already started.
        val bytes = ByteArray(100) { 1 }
        val result = LyingSource(bytes, sizeBytes = 10L * 1024 * 1024).prepare() as PrepareResult.Ready
        assertEquals(100L, result.sizeBytes)
    }

    @Test
    fun `an under-reported size is corrected rather than trusted`() {
        val bytes = ByteArray(4096) { 2 }
        val result = LyingSource(bytes, sizeBytes = 10L).prepare() as PrepareResult.Ready
        assertEquals(4096L, result.sizeBytes)
    }

    @Test
    fun `an empty file prepares successfully`() {
        // Zero bytes is a real file. `size <= 0` used to refuse it as if it were an error.
        val result = ByteArrayContentSource(ByteArray(0), "text/plain", "e.txt", ContentKind.FILE)
            .prepare() as PrepareResult.Ready
        assertEquals(0L, result.sizeBytes)
        assertArrayEquals(sha256(ByteArray(0)), result.sha256)
    }

    @Test
    fun `preparation reads the source exactly once`() {
        // The pass replaces the separate digest read; it must not add a third traversal of what
        // may be a network-backed provider.
        val source = LyingSource(ByteArray(2048), sizeBytes = 2048)
        source.prepare()
        assertEquals(1, source.opens)
    }

    // -- bounds -----------------------------------------------------------------------------

    @Test
    fun `a stream over the limit is refused`() {
        val result = ByteArrayContentSource(
            ByteArray(2048),
            "application/octet-stream",
            "big.bin",
            ContentKind.FILE,
        ).prepare(maxBytes = 1024)
        assertEquals(PrepareResult.Failed(ContentSourceError.TOO_LARGE), result)
    }

    @Test
    fun `the limit is enforced during the read, not from the declared size`() {
        // A provider that under-reports must not be able to make this read an unbounded amount.
        // 1 MiB of content behind a 10-byte claim, with a 1 KiB budget.
        val source = LyingSource(ByteArray(1024 * 1024), sizeBytes = 10L)
        assertEquals(
            PrepareResult.Failed(ContentSourceError.TOO_LARGE),
            source.prepare(maxBytes = 1024),
        )
    }

    @Test
    fun `a stream exactly at the limit is accepted`() {
        val result = ByteArrayContentSource(
            ByteArray(1024),
            "application/octet-stream",
            "exact.bin",
            ContentKind.FILE,
        ).prepare(maxBytes = 1024)
        assertEquals(1024L, (result as PrepareResult.Ready).sizeBytes)
    }

    @Test
    fun `a read failure is reported as unreadable`() {
        val source = LyingSource(ByteArray(4096), sizeBytes = 4096, failAfter = 100)
        assertEquals(PrepareResult.Failed(ContentSourceError.UNREADABLE), source.prepare())
    }

    // -- the result type --------------------------------------------------------------------

    @Test
    fun `two results over the same bytes are equal`() {
        // Ready holds a ByteArray, so a generated equals would compare by identity -- never what
        // a caller means for a digest.
        val bytes = ByteArray(64) { it.toByte() }
        val a = ByteArrayContentSource(bytes, "x/y", "a", ContentKind.FILE).prepare()
        val b = ByteArrayContentSource(bytes, "x/y", "a", ContentKind.FILE).prepare()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `results over different bytes are not equal`() {
        val a = ByteArrayContentSource(ByteArray(8) { 1 }, "x/y", "a", ContentKind.FILE).prepare()
        val b = ByteArrayContentSource(ByteArray(8) { 2 }, "x/y", "a", ContentKind.FILE).prepare()
        assertTrue(a != b)
    }
}
