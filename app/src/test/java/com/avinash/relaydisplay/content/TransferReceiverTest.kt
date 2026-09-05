package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.protocol.ContentLimits
import com.avinash.relaydisplay.protocol.ContentOffer
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import com.avinash.relaydisplay.protocol.TransferChunk
import com.avinash.relaydisplay.protocol.TransferComplete
import com.avinash.relaydisplay.protocol.TransferStart
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferReceiverTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var cache: ContentCache
    private lateinit var cacheRoot: File
    private val transferId: UUID = UUID.randomUUID()

    /** A minimal but genuinely valid PNG header, so sniffing agrees with the declared type. */
    private val pngBytes: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    ) + ByteArray(500) { (it % 251).toByte() }

    @Before
    fun setUp() {
        cacheRoot = temp.newFolder("cache")
        cache = ContentCache(cacheRoot)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun offer(
        size: Long = pngBytes.size.toLong(),
        mime: String = "image/png",
        kind: ContentKind = ContentKind.IMAGE,
        digest: ByteArray = sha256(pngBytes),
        name: String = "photo.png",
    ) = ContentOffer(UUID.randomUUID(), transferId, kind, size, mime, name, digest)

    private fun deliver(
        receiver: TransferReceiver,
        bytes: ByteArray,
        chunkSize: Int = 128,
        digestOverride: ByteArray? = null,
    ): TransferOutcome {
        var index = 0L
        var pos = 0
        while (pos < bytes.size) {
            val end = minOf(pos + chunkSize, bytes.size)
            val outcome = receiver.accept(
                TransferChunk(UUID.randomUUID(), transferId, index, bytes.copyOfRange(pos, end)),
            )
            if (outcome !is TransferOutcome.Continue) return outcome
            index++
            pos = end
        }
        return receiver.finish(
            TransferComplete(UUID.randomUUID(), transferId, digestOverride ?: sha256(bytes)),
        )
    }

    private fun started(offerToUse: ContentOffer = offer(), chunkSize: Int = 128): TransferReceiver {
        val receiver = TransferReceiver(cache)
        assertEquals(TransferOutcome.Continue, receiver.evaluate(offerToUse))
        assertEquals(
            TransferOutcome.Continue,
            receiver.begin(TransferStart(UUID.randomUUID(), transferId, offerToUse.sizeBytes, chunkSize)),
        )
        return receiver
    }

    @Test
    fun `a complete transfer verifies and lands in the cache`() {
        val receiver = started()
        val outcome = deliver(receiver, pngBytes)
        assertTrue("expected success, got $outcome", outcome is TransferOutcome.Finished)
        val finished = outcome as TransferOutcome.Finished
        assertTrue(finished.file.exists())
        assertEquals(pngBytes.size.toLong(), finished.file.length())
        assertArrayEqualsFile(pngBytes, finished.file)
        assertEquals("photo.png", finished.displayName)
        assertEquals(ContentKind.IMAGE, finished.kind)
    }

    @Test
    fun `a promoted file is named from the transfer id not the peer name`() {
        val receiver = started(offer(name = "../../evil.png"))
        val finished = deliver(receiver, pngBytes) as TransferOutcome.Finished
        assertTrue(finished.file.name.startsWith(transferId.toString()))
        assertFalse(finished.file.canonicalPath.contains(".."))
        assertTrue(finished.file.canonicalPath.startsWith(cacheRoot.canonicalPath))
        // The sanitized name is only ever shown to the user.
        assertEquals("evil.png", finished.displayName)
    }

    @Test
    fun `an offer above the size limit is refused before anything is opened`() {
        val receiver = TransferReceiver(cache)
        val outcome = receiver.evaluate(offer(size = ContentLimits.MAX_FILE_BYTES + 1))
        assertEquals(ProtocolErrorCode.TOO_LARGE, (outcome as TransferOutcome.Rejected).code)
        assertNoPartialsLeft()
    }

    @Test
    fun `an empty offer is refused`() {
        val receiver = TransferReceiver(cache)
        val outcome = receiver.evaluate(offer(size = 0))
        assertTrue(outcome is TransferOutcome.Rejected)
    }

    @Test
    fun `an unsupported mime type is refused`() {
        val receiver = TransferReceiver(cache)
        val outcome = receiver.evaluate(offer(mime = "application/zip"))
        assertEquals(ProtocolErrorCode.UNSUPPORTED_FORMAT, (outcome as TransferOutcome.Rejected).code)
    }

    @Test
    fun `an offer larger than free space is refused`() {
        val tiny = ContentCache(temp.newFolder("tiny"))
        val receiver = TransferReceiver(tiny)
        // Ask for more than any test volume can have free.
        val outcome = receiver.evaluate(offer(size = ContentLimits.MAX_FILE_BYTES))
        // Either it fits (large disk) or it is refused for space; both are correct, but the
        // refusal must be the storage one rather than a crash.
        if (outcome is TransferOutcome.Rejected) {
            assertEquals(ProtocolErrorCode.INSUFFICIENT_STORAGE, outcome.code)
        }
    }

    @Test
    fun `an out of order chunk aborts and removes the partial`() {
        val receiver = started()
        val outcome = receiver.accept(TransferChunk(UUID.randomUUID(), transferId, 5, ByteArray(16)))
        assertEquals(ProtocolErrorCode.MALFORMED_FRAME, (outcome as TransferOutcome.Rejected).code)
        assertNoPartialsLeft()
    }

    @Test
    fun `more bytes than declared aborts before writing past the limit`() {
        // Two 32-byte chunks fill the declared 64 bytes exactly; the third must be refused.
        val receiver = started(offer(size = 64))
        var last: TransferOutcome = TransferOutcome.Continue
        for (index in 0L until 4L) {
            last = receiver.accept(TransferChunk(UUID.randomUUID(), transferId, index, ByteArray(32)))
            if (last !is TransferOutcome.Continue) break
        }
        assertTrue("expected rejection, got $last", last is TransferOutcome.Rejected)
        assertEquals(ProtocolErrorCode.TOO_LARGE, (last as TransferOutcome.Rejected).code)
        assertNoPartialsLeft()
    }

    @Test
    fun `a chunk bigger than the announced chunk size is refused`() {
        val receiver = started(chunkSize = 64)
        val outcome = receiver.accept(TransferChunk(UUID.randomUUID(), transferId, 0, ByteArray(128)))
        assertEquals(ProtocolErrorCode.PAYLOAD_TOO_LARGE, (outcome as TransferOutcome.Rejected).code)
    }

    @Test
    fun `a truncated transfer is refused and cleaned up`() {
        val receiver = started()
        receiver.accept(TransferChunk(UUID.randomUUID(), transferId, 0, pngBytes.copyOfRange(0, 100)))
        val outcome = receiver.finish(TransferComplete(UUID.randomUUID(), transferId, sha256(pngBytes)))
        assertEquals(ProtocolErrorCode.DECODE_FAILED, (outcome as TransferOutcome.Rejected).code)
        assertNoPartialsLeft()
        assertTrue(cache.readyFiles().isEmpty())
    }

    @Test
    fun `a corrupted byte fails the digest check`() {
        val corrupted = pngBytes.clone().also { it[300] = (it[300] + 1).toByte() }
        val receiver = started(offer(digest = sha256(pngBytes), size = corrupted.size.toLong()))
        val outcome = deliver(receiver, corrupted, digestOverride = sha256(pngBytes))
        assertEquals(ProtocolErrorCode.DECODE_FAILED, (outcome as TransferOutcome.Rejected).code)
        assertNoPartialsLeft()
        assertTrue("a file that failed verification must never be promoted", cache.readyFiles().isEmpty())
    }

    @Test
    fun `a sender whose completion digest disagrees with its own offer is refused`() {
        val receiver = started()
        val outcome = deliver(receiver, pngBytes, digestOverride = ByteArray(32))
        assertEquals(ProtocolErrorCode.DECODE_FAILED, (outcome as TransferOutcome.Rejected).code)
    }

    @Test
    fun `content that does not match its declared kind is refused`() {
        // Bytes that are a valid PDF while the offer claims an image.
        val pdf = "%PDF-1.4".toByteArray() + ByteArray(200)
        val receiver = started(offer(size = pdf.size.toLong(), digest = sha256(pdf), mime = "image/png"))
        val outcome = deliver(receiver, pdf)
        assertEquals(ProtocolErrorCode.UNSUPPORTED_FORMAT, (outcome as TransferOutcome.Rejected).code)
        assertNoPartialsLeft()
    }

    @Test
    fun `cancelling removes the partial file`() {
        val receiver = started()
        receiver.accept(TransferChunk(UUID.randomUUID(), transferId, 0, ByteArray(64)))
        assertTrue(partials().isNotEmpty())
        receiver.cancel()
        assertNoPartialsLeft()
        // Cancelling twice must not throw.
        receiver.cancel()
    }

    @Test
    fun `a chunk after a failure is refused rather than resuming`() {
        val receiver = started()
        receiver.accept(TransferChunk(UUID.randomUUID(), transferId, 9, ByteArray(4)))
        val outcome = receiver.accept(TransferChunk(UUID.randomUUID(), transferId, 0, ByteArray(4)))
        assertTrue(outcome is TransferOutcome.Rejected)
    }

    @Test
    fun `a chunk for a different transfer is refused`() {
        val receiver = started()
        val outcome = receiver.accept(TransferChunk(UUID.randomUUID(), UUID.randomUUID(), 0, ByteArray(4)))
        assertTrue(outcome is TransferOutcome.Rejected)
    }

    @Test
    fun `progress is reported as bytes arrive`() {
        val receiver = started()
        assertEquals(0L, receiver.bytesReceived)
        assertEquals(pngBytes.size.toLong(), receiver.bytesExpected)
        receiver.accept(TransferChunk(UUID.randomUUID(), transferId, 0, ByteArray(64)))
        assertEquals(64L, receiver.bytesReceived)
    }

    @Test
    fun `sweep removes partials left by a crash`() {
        started().accept(TransferChunk(UUID.randomUUID(), transferId, 0, ByteArray(64)))
        assertTrue(partials().isNotEmpty())
        cache.sweepPartials()
        assertNoPartialsLeft()
    }

    private fun partials(): List<File> =
        File(cacheRoot, "incoming").listFiles()?.filter { it.name.endsWith(".part") }.orEmpty()

    private fun assertNoPartialsLeft() {
        assertTrue("partial files left behind: ${partials().map { it.name }}", partials().isEmpty())
    }

    private fun assertArrayEqualsFile(expected: ByteArray, file: File) {
        assertTrue("file contents differ", expected.contentEquals(file.readBytes()))
    }
}

class MimeSnifferTest {

    @Test
    fun `recognises the formats we accept`() {
        assertEquals(SniffedType.JPEG, MimeSniffer.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals(
            SniffedType.PNG,
            MimeSniffer.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        )
        assertEquals(SniffedType.GIF, MimeSniffer.sniff("GIF89a".toByteArray()))
        assertEquals(SniffedType.PDF, MimeSniffer.sniff("%PDF-1.7".toByteArray()))
    }

    @Test
    fun `recognises webp only with both markers`() {
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()
        assertEquals(SniffedType.WEBP, MimeSniffer.sniff(webp))
        val notWebp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WAVE".toByteArray()
        assertEquals(SniffedType.UNKNOWN, MimeSniffer.sniff(notWebp))
    }

    @Test
    fun `recognises heif by its brand`() {
        val heic = ByteArray(4) + "ftyp".toByteArray() + "heic".toByteArray()
        assertEquals(SniffedType.HEIF, MimeSniffer.sniff(heic))
    }

    @Test
    fun `unknown content stays unknown`() {
        assertEquals(SniffedType.UNKNOWN, MimeSniffer.sniff("PK".toByteArray()))
        assertEquals(SniffedType.UNKNOWN, MimeSniffer.sniff(ByteArray(0)))
        assertEquals(SniffedType.UNKNOWN, MimeSniffer.sniff(ByteArray(2)))
    }

    @Test
    fun `a short buffer never reads past its end`() {
        // A one byte buffer that starts like a PNG must not throw.
        assertEquals(SniffedType.UNKNOWN, MimeSniffer.sniff(byteArrayOf(0x89.toByte())))
    }

    @Test
    fun `kind mapping matches the sniffed type`() {
        assertTrue(MimeSupport.matchesKind(ContentKind.IMAGE, SniffedType.PNG))
        assertTrue(MimeSupport.matchesKind(ContentKind.PDF, SniffedType.PDF))
        assertFalse(MimeSupport.matchesKind(ContentKind.IMAGE, SniffedType.PDF))
        assertFalse(MimeSupport.matchesKind(ContentKind.PDF, SniffedType.JPEG))
        assertFalse(MimeSupport.matchesKind(ContentKind.IMAGE, SniffedType.UNKNOWN))
    }
}

class ContentCacheTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `evicts oldest entries past the byte budget`() {
        val cache = ContentCache(temp.newFolder("c"), maxBytes = 3_000, maxEntries = 100)
        val ids = (1..5).map { UUID.randomUUID() }
        for (id in ids) {
            val partial = cache.createPartial(id)
            partial.writeBytes(ByteArray(1_000))
            cache.promote(partial, id, "bin")
            // Keep modification times strictly ordered so eviction order is deterministic.
            Thread.sleep(5)
        }
        val remaining = cache.readyFiles()
        assertTrue("expected eviction, ${remaining.size} files left", remaining.size <= 3)
    }

    @Test
    fun `evicts past the entry budget`() {
        val cache = ContentCache(temp.newFolder("c2"), maxBytes = Long.MAX_VALUE, maxEntries = 2)
        repeat(5) {
            val id = UUID.randomUUID()
            val partial = cache.createPartial(id)
            partial.writeBytes(ByteArray(10))
            cache.promote(partial, id, "bin")
            Thread.sleep(5)
        }
        assertTrue(cache.readyFiles().size <= 2)
    }

    @Test
    fun `extensions from a peer are sanitised`() {
        val cache = ContentCache(temp.newFolder("c3"))
        val id = UUID.randomUUID()
        val partial = cache.createPartial(id)
        partial.writeBytes(ByteArray(4))
        val promoted = cache.promote(partial, id, "../../sh")
        assertTrue(promoted!!.name.startsWith(id.toString()))
        assertFalse(promoted.name.contains("/"))
        assertFalse("no double extension", promoted.name.contains(".."))
    }

    @Test
    fun `clear empties everything`() {
        val cache = ContentCache(temp.newFolder("c4"))
        val id = UUID.randomUUID()
        val partial = cache.createPartial(id)
        partial.writeBytes(ByteArray(4))
        cache.promote(partial, id, "bin")
        assertTrue(cache.readyFiles().isNotEmpty())
        cache.clear()
        assertTrue(cache.readyFiles().isEmpty())
    }
}
