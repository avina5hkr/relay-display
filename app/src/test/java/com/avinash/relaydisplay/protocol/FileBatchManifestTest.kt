package com.avinash.relaydisplay.protocol

import com.avinash.relaydisplay.protocol.MessageCodec.encode
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The batch manifest is a hand-rolled encoding nested inside a TLV field, parsed from bytes a peer
 * sent. That combination is worth its own test file: the TLV layer's own bounds do not reach
 * inside the field, so every length in here is checked by code written for this purpose.
 *
 * Frames are corrupted by editing an encoded valid one, so each case differs from something known
 * to work in exactly one way.
 */
class FileBatchManifestTest {

    private val batchId: UUID = UUID.fromString("6bb1d0a8-1f13-4a1b-9c0e-9f6b1d2f7a44")
    private val messageId: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private fun offer(vararg files: FileManifestEntry) = FileBatchOffer(
        id = messageId,
        batchId = batchId,
        files = files.toList(),
    )

    private var nextId = 0

    private fun entry(
        name: String,
        mime: String = "application/pdf",
        size: Long = 10L,
        transferId: UUID = UUID.nameUUIDFromBytes("entry-${nextId++}".toByteArray()),
        digest: ByteArray = ByteArray(32) { it.toByte() },
    ) = FileManifestEntry(
        transferId = transferId,
        displayName = name,
        mimeType = mime,
        sizeBytes = size,
        sha256 = digest,
    )

    private fun decodeFails(bytes: ByteArray, why: String) {
        try {
            MessageCodec.decode(bytes)
            fail("accepted a frame that $why")
        } catch (e: ProtocolException) {
            // The expected outcome: refused as a protocol violation.
            assertTrue(
                "unhelpful code for '$why': ${e.errorCode}",
                e.errorCode == ProtocolErrorCode.MALFORMED_FRAME ||
                    e.errorCode == ProtocolErrorCode.UNSUPPORTED_FORMAT,
            )
        }
    }

    // -- the happy path ----------------------------------------------------------------------

    @Test
    fun `a manifest round trips with order preserved`() {
        // Order is meaning, not presentation: the receiver's "3 of 7" counts against it.
        val original = offer(entry("a.pdf"), entry("b.txt", "text/plain", 0L), entry("c.zip", "application/zip", 4096L))
        val decoded = MessageCodec.decode(encode(original)) as FileBatchOffer
        assertEquals(original, decoded)
        assertEquals(listOf("a.pdf", "b.txt", "c.zip"), decoded.files.map { it.displayName })
    }

    @Test
    fun `a full batch round trips`() {
        val full = offer(*Array(ContentLimits.MAX_FILES_PER_BATCH) { entry("file$it.bin") })
        val decoded = MessageCodec.decode(encode(full)) as FileBatchOffer
        assertEquals(ContentLimits.MAX_FILES_PER_BATCH, decoded.files.size)
        assertEquals(full, decoded)
    }

    @Test
    fun `multi byte names survive intact`() {
        // The length prefixes are byte counts, not character counts. Getting that wrong truncates
        // mid-character and the name comes back mangled rather than rejected.
        val names = listOf("漢字.pdf", "été.txt", "📄 report.pdf")
        val decoded = MessageCodec.decode(encode(offer(*names.map { entry(it) }.toTypedArray())))
        assertEquals(names, (decoded as FileBatchOffer).files.map { it.displayName })
    }

    @Test
    fun `an empty mime type survives as empty`() {
        val decoded = MessageCodec.decode(encode(offer(entry("x.bin", mime = "")))) as FileBatchOffer
        assertEquals("", decoded.files[0].mimeType)
    }

    @Test
    fun `the totals are computed from the entries`() {
        val decoded = MessageCodec.decode(
            encode(offer(entry("a", size = 100), entry("b", size = 250))),
        ) as FileBatchOffer
        assertEquals(350L, decoded.totalBytes)
    }

    // -- what a hostile or broken peer sends -------------------------------------------------

    @Test
    fun `a truncated manifest is refused`() {
        val bytes = encode(offer(entry("report.pdf"), entry("second.pdf")))
        // Chop the tail: the declared count now promises more than the buffer holds.
        decodeFails(bytes.copyOf(bytes.size - 6), "was truncated mid-manifest")
    }

    @Test
    fun `trailing bytes are refused rather than ignored`() {
        // A decoder that tolerates junk it does not understand is how the two sides' idea of a
        // frame drifts apart, so this is a hard error.
        val bytes = encode(offer(entry("report.pdf")))
        decodeFails(bytes + byteArrayOf(0, 0, 0), "had trailing bytes after the manifest")
    }

    @Test
    fun `an empty batch is refused`() {
        // Nothing to confirm, so there is nothing legitimate this can mean.
        decodeFails(encode(offer()), "declared no files")
    }

    @Test
    fun `a batch over the file limit is refused`() {
        val tooMany = offer(*Array(ContentLimits.MAX_FILES_PER_BATCH + 1) { entry("f$it.bin") })
        decodeFails(encode(tooMany), "exceeded the per-batch file limit")
    }

    @Test
    fun `a negative size is refused`() {
        // Sizes are measured before an offer goes out, so no sentinel for "unknown" ever travels
        // and a negative value cannot be legitimate. Bounding it here means nothing downstream
        // has to defend against one.
        decodeFails(encode(offer(entry("x.bin", size = -1L))), "declared a negative size")
    }

    @Test
    fun `a size over the per-file limit is refused`() {
        decodeFails(
            encode(offer(entry("x.bin", size = ContentLimits.MAX_FILE_BYTES + 1))),
            "declared a size over the per-file limit",
        )
    }

    @Test
    fun `a size of Long MAX_VALUE is refused rather than overflowing a total`() {
        // Two of these would wrap a naive sum to a negative number and sail past a limit check.
        decodeFails(
            encode(offer(entry("a", size = Long.MAX_VALUE), entry("b", size = Long.MAX_VALUE))),
            "declared sizes that would overflow a total",
        )
    }

    @Test
    fun `a name length prefix longer than the field is refused`() {
        val bytes = encode(offer(entry("report.pdf")))
        // Find the name inside the encoded frame and inflate the u16 length that precedes it.
        val needle = "report.pdf".toByteArray(Charsets.UTF_8)
        val at = bytes.indices.first { i ->
            i + needle.size <= bytes.size && bytes.copyOfRange(i, i + needle.size).contentEquals(needle)
        }
        val tampered = bytes.copyOf()
        tampered[at - 2] = 0x7F
        tampered[at - 1] = 0xFF.toByte()
        decodeFails(tampered, "declared a name longer than the manifest")
    }

    @Test
    fun `a manifest naming the same transfer twice is refused`() {
        // An entry the receiver cannot tell apart from another is exactly what the binding exists
        // to prevent, so an ambiguous manifest is refused before it can be shown to anyone.
        val shared = UUID.fromString("33333333-3333-3333-3333-333333333333")
        decodeFails(
            encode(offer(entry("a.pdf", transferId = shared), entry("b.pdf", transferId = shared))),
            "named the same transfer id twice",
        )
    }

    @Test
    fun `a digest survives the round trip byte for byte`() {
        val digest = ByteArray(32) { (255 - it).toByte() }
        val decoded = MessageCodec.decode(encode(offer(entry("x.bin", digest = digest)))) as FileBatchOffer
        assertTrue(decoded.files[0].sha256.contentEquals(digest))
    }

    @Test
    fun `a transfer id survives the round trip`() {
        val tid = UUID.fromString("44444444-5555-6666-7777-888888888888")
        val decoded = MessageCodec.decode(encode(offer(entry("x.bin", transferId = tid)))) as FileBatchOffer
        assertEquals(tid, decoded.files[0].transferId)
    }

    @Test
    fun `encoding is stable across calls`() {
        // The golden test relies on this; a map iteration order leaking into the manifest would
        // make the whole encoding non-deterministic.
        val sample = offer(entry("a.pdf"), entry("b.pdf"))
        assertTrue(encode(sample).contentEquals(encode(sample)))
    }
}
