package com.avinash.relaydisplay.content

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the confirmation dialog is told, and what the policy lets through to it.
 *
 * The dialog is the consent step for generic file transfer: being paired establishes which phone
 * is on the other end, not that its user's request is wanted right now. Everything below is about
 * that prompt being able to state the truth -- a real count, a real total, and a warning when the
 * batch contains something that should not be opened casually.
 */
class IncomingBatchTest {

    private fun file(name: String, mime: String = "application/pdf", size: Long = 1_000L) =
        IncomingFile(name, mime, size)

    private fun batch(vararg files: IncomingFile) =
        IncomingBatch(UUID.randomUUID(), "Controller", files.toList())

    @Test
    fun `the total is the sum of the entries`() {
        assertEquals(1_500L, batch(file("a", size = 500), file("b", size = 1_000)).totalBytes)
    }

    @Test
    fun `a batch of empty files totals zero rather than failing`() {
        val b = batch(file("a.txt", "text/plain", 0), file("b.txt", "text/plain", 0))
        assertEquals(0L, b.totalBytes)
        assertEquals(2, b.count)
    }

    @Test
    fun `a maximum batch of maximum files cannot overflow the total`() {
        // Every entry has already been bounded by validateFileMetadata, so the worst legal case is
        // MAX_FILES_PER_BATCH * MAX_FILE_BYTES. That must stay a positive, sane number: a wrapped
        // total would render as a negative size and pass a naive limit check.
        val worst = batch(
            *Array(FileTransferPolicy.MAX_FILES_PER_BATCH) {
                file("f$it", size = FileTransferPolicy.MAX_FILE_BYTES)
            },
        )
        assertTrue("total overflowed: ${worst.totalBytes}", worst.totalBytes > 0)
        assertEquals(
            FileTransferPolicy.MAX_FILES_PER_BATCH * FileTransferPolicy.MAX_FILE_BYTES,
            worst.totalBytes,
        )
    }

    // -- the executable warning ---------------------------------------------------------------

    @Test
    fun `an apk in the batch raises the warning`() {
        assertTrue(batch(file("ok.pdf"), file("app.apk", "application/vnd.android.package-archive")).containsExecutable)
    }

    @Test
    fun `an apk disguised by its mime type still raises the warning`() {
        // A sender can declare anything. The extension is checked too, because either signal can
        // be wrong on its own and being cautious costs one extra confirmation.
        assertTrue(batch(file("invoice.apk", "text/plain")).containsExecutable)
    }

    @Test
    fun `an executable mime type with an innocent name still raises the warning`() {
        assertTrue(batch(file("holiday-photo", "application/vnd.android.package-archive")).containsExecutable)
    }

    @Test
    fun `an ordinary batch raises no warning`() {
        assertFalse(batch(file("a.pdf"), file("b.txt", "text/plain"), file("c.jpg", "image/jpeg")).containsExecutable)
    }

    @Test
    fun `a name that merely contains apk is not treated as executable`() {
        // "apkdesign.pdf" has no executable extension: the check is on the final segment, not a
        // substring, or ordinary files would trip the warning and it would stop meaning anything.
        assertFalse(batch(file("apkdesign.pdf")).containsExecutable)
    }

    // -- what reaches the dialog at all -------------------------------------------------------

    @Test
    fun `a hostile name is sanitised before it can reach a screen`() {
        val verdict = FileTransferPolicy.validateFileMetadata(
            "../../../data/data/com.avinash.relaydisplay/x.pdf",
            "application/pdf",
            10,
        )
        assertTrue(verdict is FileTransferPolicy.MetadataVerdict.Valid)
        val name = (verdict as FileTransferPolicy.MetadataVerdict.Valid).safeName
        assertFalse(name.contains('/'))
        assertFalse(name.contains(".."))
        assertEquals("x.pdf", name)
    }

    @Test
    fun `a batch over the file limit is refused before any prompt`() {
        val many = List(FileTransferPolicy.MAX_FILES_PER_BATCH + 1) {
            FileTransferPolicy.MetadataVerdict.Valid("f$it", "application/pdf", 1)
        }
        assertTrue(FileTransferPolicy.validateBatch(many) is FileTransferPolicy.BatchVerdict.Invalid)
    }

    @Test
    fun `a batch over the byte limit is refused before any prompt`() {
        // Each file is legal on its own; the batch is not.
        val perFile = FileTransferPolicy.MAX_FILE_BYTES
        val count = (FileTransferPolicy.MAX_BATCH_BYTES / perFile).toInt() + 1
        val files = List(count) { FileTransferPolicy.MetadataVerdict.Valid("f$it", "application/pdf", perFile) }
        assertTrue(FileTransferPolicy.validateBatch(files) is FileTransferPolicy.BatchVerdict.Invalid)
    }

    @Test
    fun `an empty batch is refused`() {
        assertTrue(FileTransferPolicy.validateBatch(emptyList()) is FileTransferPolicy.BatchVerdict.Invalid)
    }

    @Test
    fun `a batch that would overflow a naive total is refused, not accepted`() {
        // Two Long.MAX_VALUE entries sum to -2 with wrapping arithmetic, which is less than the
        // limit and would sail through. validateBatch accumulates with an early exit instead.
        val files = listOf(
            FileTransferPolicy.MetadataVerdict.Valid("a", "application/pdf", Long.MAX_VALUE),
            FileTransferPolicy.MetadataVerdict.Valid("b", "application/pdf", Long.MAX_VALUE),
        )
        assertTrue(FileTransferPolicy.validateBatch(files) is FileTransferPolicy.BatchVerdict.Invalid)
    }
}
