package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.protocol.ContentLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metadata and limit rules for generic file transfer.
 *
 * The peer is authenticated, not trusted: pairing proves which phone is talking, not that the
 * software on it is behaving. Every value it sends is bounded before anything is allocated or
 * written, and these tests are what stop a tidy-up from removing one of those bounds unnoticed.
 */
class FileTransferPolicyTest {

    private fun valid(name: String, mime: String?, size: Long) =
        FileTransferPolicy.validateFileMetadata(name, mime, size)
            as FileTransferPolicy.MetadataVerdict.Valid

    private fun invalid(name: String, mime: String?, size: Long) =
        FileTransferPolicy.validateFileMetadata(name, mime, size)
            as FileTransferPolicy.MetadataVerdict.Invalid

    @Test
    fun `a known size is accepted`() {
        assertEquals(1_024L, valid("a.txt", "text/plain", 1_024).sizeBytes)
    }

    @Test
    fun `an empty file is accepted`() {
        // Refusing zero bytes made an ordinary empty .txt look like a protocol error.
        assertEquals(0L, valid("empty.txt", "text/plain", 0).sizeBytes)
    }

    @Test
    fun `unknown size is accepted and preserved as the sentinel`() {
        // A content provider is not obliged to report a size, and refusing those would rule out
        // a lot of perfectly ordinary URIs.
        assertEquals(
            FileTransferPolicy.SIZE_UNKNOWN,
            valid("stream.bin", null, FileTransferPolicy.SIZE_UNKNOWN).sizeBytes,
        )
    }

    @Test
    fun `a negative size that is not the sentinel is rejected`() {
        assertTrue(invalid("a.txt", "text/plain", -2).reason.contains("negative"))
    }

    @Test
    fun `a file over the per-file limit is rejected`() {
        val over = FileTransferPolicy.MAX_FILE_BYTES + 1
        assertTrue(invalid("big.bin", "application/octet-stream", over).reason.contains("larger than"))
    }

    @Test
    fun `a file exactly at the limit is accepted`() {
        assertEquals(
            FileTransferPolicy.MAX_FILE_BYTES,
            valid("edge.bin", "application/octet-stream", FileTransferPolicy.MAX_FILE_BYTES).sizeBytes,
        )
    }

    @Test
    fun `a missing or blank MIME falls back to octet-stream`() {
        assertEquals(FileTransferPolicy.FALLBACK_MIME, valid("a.bin", null, 1).mimeType)
        assertEquals(FileTransferPolicy.FALLBACK_MIME, valid("a.bin", "   ", 1).mimeType)
    }

    @Test
    fun `an over-long MIME is rejected`() {
        val long = "application/" + "x".repeat(ContentLimits.MAX_MIME_BYTES)
        assertTrue(invalid("a.bin", long, 1).reason.contains("too long"))
    }

    @Test
    fun `a MIME containing control characters is rejected`() {
        // Char(1) rather than a literal byte, so the source file stays printable.
        val withControl = "text/pl" + Char(1) + "ain"
        assertTrue(invalid("a.bin", withControl, 1).reason.contains("control"))
    }

    private fun files(n: Int, size: Long = 1_024) =
        (1..n).map { valid("f$it.txt", "text/plain", size) }

    @Test
    fun `an empty batch is rejected`() {
        val v = FileTransferPolicy.validateBatch(emptyList()) as FileTransferPolicy.BatchVerdict.Invalid
        assertTrue(v.reason.contains("no files"))
    }

    @Test
    fun `a batch at the file-count limit is accepted`() {
        assertTrue(
            FileTransferPolicy.validateBatch(files(FileTransferPolicy.MAX_FILES_PER_BATCH))
                is FileTransferPolicy.BatchVerdict.Valid,
        )
    }

    @Test
    fun `a batch over the file-count limit is rejected`() {
        val v = FileTransferPolicy.validateBatch(files(FileTransferPolicy.MAX_FILES_PER_BATCH + 1))
            as FileTransferPolicy.BatchVerdict.Invalid
        assertTrue(v.reason.contains("more than"))
    }

    @Test
    fun `a batch over the total-byte limit is rejected`() {
        // Each file is individually legal; the total is not. Checking only per-file would let a
        // batch cost the receiver far more than any single limit suggests.
        val v = FileTransferPolicy.validateBatch(files(5, FileTransferPolicy.MAX_BATCH_BYTES / 4))
            as FileTransferPolicy.BatchVerdict.Invalid
        assertTrue(v.reason.contains("larger than"))
    }

    @Test
    fun `unknown sizes do not contribute to the batch total`() {
        // Otherwise the sentinel would be summed as -1 per file and quietly shrink the total.
        val mixed = listOf(
            valid("a.txt", "text/plain", 1_000),
            valid("b.bin", null, FileTransferPolicy.SIZE_UNKNOWN),
        )
        val v = FileTransferPolicy.validateBatch(mixed) as FileTransferPolicy.BatchVerdict.Valid
        assertEquals(1_000L, v.knownTotalBytes)
    }

    @Test
    fun `an APK requires a warning by MIME and by extension`() {
        assertTrue(FileTransferPolicy.requiresOpenWarning("application/vnd.android.package-archive", "x.bin"))
        // A provider reporting octet-stream for an APK must not slip past a MIME-only check.
        assertTrue(FileTransferPolicy.requiresOpenWarning("application/octet-stream", "app.apk"))
    }

    @Test
    fun `a mismatched declaration still warns`() {
        // Declared text, named .exe. Being cautious here costs one confirmation.
        assertTrue(FileTransferPolicy.requiresOpenWarning("text/plain", "totally-safe.exe"))
    }

    @Test
    fun `ordinary documents do not warn`() {
        assertFalse(FileTransferPolicy.requiresOpenWarning("application/pdf", "report.pdf"))
        assertFalse(FileTransferPolicy.requiresOpenWarning("text/plain", "notes.txt"))
        assertFalse(FileTransferPolicy.requiresOpenWarning("image/jpeg", "photo.jpg"))
        assertFalse(FileTransferPolicy.requiresOpenWarning(null, "archive.zip"))
    }

    @Test
    fun `the warning check is case-insensitive`() {
        assertTrue(FileTransferPolicy.requiresOpenWarning(null, "APP.APK"))
        assertTrue(FileTransferPolicy.requiresOpenWarning("APPLICATION/VND.ANDROID.PACKAGE-ARCHIVE", "x"))
    }

    @Test
    fun `only generic files accept any MIME`() {
        assertTrue(FileTransferPolicy.acceptsAnyMime(ContentKind.FILE))
        assertFalse(FileTransferPolicy.acceptsAnyMime(ContentKind.IMAGE))
        assertFalse(FileTransferPolicy.acceptsAnyMime(ContentKind.PDF))
    }

    @Test
    fun `image and pdf keep their narrow type rules`() {
        // The generic-file change must not have loosened the kinds that actually get decoded.
        assertTrue(MimeSupport.isSupported(ContentKind.IMAGE, "image/png"))
        assertFalse(MimeSupport.isSupported(ContentKind.IMAGE, "application/zip"))
        assertTrue(MimeSupport.isSupported(ContentKind.PDF, "application/pdf"))
        assertFalse(MimeSupport.isSupported(ContentKind.PDF, "text/plain"))
        assertTrue(MimeSupport.isSupported(ContentKind.FILE, "anything/at-all"))
    }
}
