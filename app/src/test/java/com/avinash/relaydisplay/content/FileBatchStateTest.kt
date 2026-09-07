package com.avinash.relaydisplay.content

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-file batch state machine.
 *
 * These exist because the alternative -- a handful of booleans -- has sixteen combinations of
 * which four are meaningful, and the impossible ones are exactly where a stuck progress bar or a
 * transfer that reports success after being cancelled comes from.
 */
class FileBatchStateTest {

    private fun meta(name: String, size: Long) =
        FileTransferPolicy.validateFileMetadata(name, "text/plain", size)
            as FileTransferPolicy.MetadataVerdict.Valid

    private fun batch(vararg sizes: Long): FileBatchState =
        FileBatchState.of(UUID.randomUUID(), sizes.mapIndexed { i, s -> meta("f$i.txt", s) })

    // -- initial state -----------------------------------------------------------------------

    @Test
    fun `a new batch is waiting and not settled`() {
        val b = batch(100, 200)
        assertEquals(2, b.total)
        assertFalse(b.settled)
        assertTrue(b.files.all { it.phase == FilePhase.Waiting })
        assertNull(b.activeIndex)
        assertEquals(0, b.completedCount)
    }

    @Test
    fun `known sizes give a total and a percentage`() {
        val b = batch(100, 300)
        assertEquals(400L, b.totalBytes)
        assertEquals(0, b.percent)
    }

    @Test
    fun `any unknown size makes the total and percentage indeterminate`() {
        // Reporting a confident percentage that is wrong is worse than reporting none, so the
        // whole batch goes indeterminate as soon as one file's size is unknown.
        val b = batch(100, FileTransferPolicy.SIZE_UNKNOWN)
        assertNull(b.totalBytes)
        assertNull(b.percent)
    }

    // -- progress ----------------------------------------------------------------------------

    @Test
    fun `progress accumulates across files`() {
        var b = batch(100, 100)
        b = b.withProgress(0, 100).withPhase(0, FilePhase.Complete)
        b = b.withPhase(1, FilePhase.Sending).withProgress(1, 50)
        assertEquals(150L, b.bytesTransferred)
        assertEquals(75, b.percent)
        assertEquals(1, b.activeIndex)
    }

    @Test
    fun `a percentage never exceeds one hundred`() {
        // A peer that acknowledges more bytes than it was promised must not produce 143%.
        var b = batch(100)
        b = b.withProgress(0, 143)
        assertEquals(100, b.percent)
    }

    @Test
    fun `an empty file reports complete rather than dividing by zero`() {
        val b = batch(0)
        assertEquals(100, b.files[0].percent)
        assertEquals(100, b.percent)
    }

    @Test
    fun `a single file with unknown size has no percentage`() {
        val b = batch(FileTransferPolicy.SIZE_UNKNOWN)
        assertNull(b.files[0].percent)
    }

    // -- settling ----------------------------------------------------------------------------

    @Test
    fun `a batch settles only when every file is terminal`() {
        var b = batch(10, 10)
        b = b.withPhase(0, FilePhase.Complete)
        assertFalse("one file still in flight", b.settled)
        b = b.withPhase(1, FilePhase.Failed("link dropped", retryable = true))
        assertTrue(b.settled)
        assertEquals(1, b.completedCount)
        assertEquals(1, b.failedCount)
    }

    @Test
    fun `verifying is not terminal`() {
        // Bytes are sent but the digest has not been confirmed. Calling this done would report
        // success for a file that might still fail its integrity check.
        val b = batch(10).withPhase(0, FilePhase.Verifying)
        assertFalse(b.settled)
    }

    // -- cancellation ------------------------------------------------------------------------

    @Test
    fun `cancelling marks unfinished files cancelled and keeps finished ones`() {
        var b = batch(10, 10, 10)
        b = b.withPhase(0, FilePhase.Complete).withPhase(1, FilePhase.Sending)
        b = b.cancelAll()
        assertTrue(b.cancelled)
        // Already verified and written on the far side; cancelling later does not un-send it.
        assertEquals(FilePhase.Complete, b.files[0].phase)
        assertEquals(FilePhase.Cancelled, b.files[1].phase)
        assertEquals(FilePhase.Cancelled, b.files[2].phase)
        assertTrue(b.settled)
    }

    @Test
    fun `cancelling twice is idempotent`() {
        val once = batch(10, 10).cancelAll()
        assertEquals(once, once.cancelAll())
    }

    @Test
    fun `a rejected batch is settled with nothing complete`() {
        val b = batch(10, 10).let { s ->
            s.copy(files = s.files.map { it.copy(phase = FilePhase.Rejected) })
        }
        assertTrue(b.settled)
        assertEquals(2, b.rejectedCount)
        assertEquals(0, b.completedCount)
        assertFalse("a rejection is not retryable", b.canRetry)
    }

    // -- retry -------------------------------------------------------------------------------

    @Test
    fun `only retryable failures are offered for retry`() {
        var b = batch(10, 10, 10)
        b = b.withPhase(0, FilePhase.Complete)
        b = b.withPhase(1, FilePhase.Failed("link dropped", retryable = true))
        b = b.withPhase(2, FilePhase.Failed("provider closed the stream", retryable = false))
        assertTrue(b.canRetry)
        // A button that always fails is worse than no button, so index 2 is excluded.
        assertEquals(listOf(1), b.retryableIndices)
    }

    @Test
    fun `retry is not offered before the batch settles`() {
        var b = batch(10, 10)
        b = b.withPhase(0, FilePhase.Failed("link dropped", retryable = true))
        b = b.withPhase(1, FilePhase.Sending)
        assertFalse(b.canRetry)
    }

    @Test
    fun `a fully successful batch offers no retry`() {
        var b = batch(10, 10)
        b = b.withPhase(0, FilePhase.Complete).withPhase(1, FilePhase.Complete)
        assertTrue(b.settled)
        assertFalse(b.canRetry)
        assertTrue(b.retryableIndices.isEmpty())
    }

    // -- disconnect and retry-from-start -----------------------------------------------------

    @Test
    fun `a disconnect mid-file marks it interrupted and retryable, not complete`() {
        var b = batch(1_000, 1_000)
        b = b.withPhase(0, FilePhase.Complete).withProgress(0, 1_000)
        b = b.withPhase(1, FilePhase.Sending).withProgress(1, 400)
        // The link drops here.
        b = b.withPhase(1, FilePhase.Failed("disconnected", retryable = true))
        assertTrue(b.settled)
        assertEquals(1, b.completedCount)
        assertEquals(listOf(1), b.retryableIndices)
    }

    @Test
    fun `retry-from-start resets progress for the retried file only`() {
        // This implementation restarts a file from byte zero. Carrying the old byte count over
        // would make the progress bar start part-full and then never reach its own total, and
        // would be the first step towards appending to stale partial data.
        var b = batch(1_000, 1_000)
        b = b.withPhase(0, FilePhase.Complete).withProgress(0, 1_000)
        b = b.withPhase(1, FilePhase.Failed("disconnected", retryable = true)).withProgress(1, 400)

        for (index in b.retryableIndices) {
            b = b.withProgress(index, 0).withPhase(index, FilePhase.Waiting)
        }
        assertEquals(0L, b.files[1].bytesTransferred)
        assertEquals(FilePhase.Waiting, b.files[1].phase)
        assertEquals(1_000L, b.files[0].bytesTransferred)
        assertEquals(FilePhase.Complete, b.files[0].phase)
        assertFalse(b.settled)
    }

    // -- identity ----------------------------------------------------------------------------

    @Test
    fun `two batches have distinct ids`() {
        assertFalse(batch(1).batchId == batch(1).batchId)
    }

    @Test
    fun `state transitions leave other files untouched`() {
        val b = batch(10, 10, 10).withPhase(1, FilePhase.Sending)
        assertEquals(FilePhase.Waiting, b.files[0].phase)
        assertEquals(FilePhase.Sending, b.files[1].phase)
        assertEquals(FilePhase.Waiting, b.files[2].phase)
    }

    @Test
    fun `sanitised names carry into the batch`() {
        val b = FileBatchState.of(UUID.randomUUID(), listOf(meta("../../etc/passwd", 10)))
        assertEquals("passwd", b.files[0].name)
    }
}
