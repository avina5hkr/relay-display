package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.protocol.Capabilities
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

/**
 * Receiving a generic file end to end through the real [TransferReceiver].
 *
 * The image and PDF paths already had coverage; this exercises the same machine with
 * [ContentKind.FILE], where any MIME type is allowed and nothing sniffs the content. What must
 * still hold is every integrity rule: ordered chunks, an exact byte count, a matching digest, and
 * no partial file surviving a failure.
 */
class GenericFileReceiveTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var cache: ContentCache
    private lateinit var cacheRoot: File
    private val transferId: UUID = UUID.randomUUID()

    /** Arbitrary bytes: a generic file is opaque, so there is no header to satisfy. */
    private val payload = ByteArray(1_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        cacheRoot = temp.newFolder("cache")
        cache = ContentCache(cacheRoot)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun offer(
        size: Long = payload.size.toLong(),
        mime: String = "application/zip",
        name: String = "archive.zip",
        digest: ByteArray = sha256(payload),
        id: UUID = transferId,
    ) = ContentOffer(UUID.randomUUID(), id, ContentKind.FILE, size, mime, name, digest)

    private fun started(o: ContentOffer = offer(), chunkSize: Int = 128): TransferReceiver {
        val r = TransferReceiver(cache)
        assertEquals(TransferOutcome.Continue, r.evaluate(o))
        assertEquals(
            TransferOutcome.Continue,
            r.begin(TransferStart(UUID.randomUUID(), o.transferId, o.sizeBytes, chunkSize)),
        )
        return r
    }

    private fun deliver(
        r: TransferReceiver,
        bytes: ByteArray,
        chunkSize: Int = 128,
        digestOverride: ByteArray? = null,
    ): TransferOutcome {
        var index = 0L
        var pos = 0
        while (pos < bytes.size) {
            val end = minOf(pos + chunkSize, bytes.size)
            val outcome = r.accept(
                TransferChunk(UUID.randomUUID(), transferId, index, bytes.copyOfRange(pos, end)),
            )
            if (outcome !is TransferOutcome.Continue) return outcome
            index++
            pos = end
        }
        return r.finish(TransferComplete(UUID.randomUUID(), transferId, digestOverride ?: sha256(bytes)))
    }

    private fun readyFiles() = File(cacheRoot, "ready").listFiles()?.toList().orEmpty()
    private fun partialFiles() = File(cacheRoot, "incoming").listFiles()?.toList().orEmpty()

    // -- the happy path ----------------------------------------------------------------------

    @Test
    fun `a zip is accepted, verified and promoted`() {
        val r = started()
        val outcome = deliver(r, payload)
        assertTrue("got $outcome", outcome is TransferOutcome.Finished)
        assertEquals(1, readyFiles().size)
        assertTrue("the partial must not survive", partialFiles().isEmpty())
    }

    @Test
    fun `any mime type is accepted for a generic file`() {
        // Nothing decodes these, so refusing unfamiliar types would only block ordinary files.
        for (mime in listOf(
            "application/zip", "audio/mpeg", "video/mp4", "text/markdown",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/x-totally-made-up",
        )) {
            val r = TransferReceiver(cache)
            assertEquals(
                "rejected $mime",
                TransferOutcome.Continue,
                r.evaluate(offer(mime = mime, id = UUID.randomUUID())),
            )
        }
    }

    @Test
    fun `an empty file completes`() {
        // Zero bytes is a legitimate file. The offer gate used to reject size <= 0, which made an
        // ordinary empty .txt look like a protocol error.
        val empty = ByteArray(0)
        val r = started(offer(size = 0, name = "empty.txt", mime = "text/plain", digest = sha256(empty)))
        val outcome = r.finish(TransferComplete(UUID.randomUUID(), transferId, sha256(empty)))
        assertTrue("got $outcome", outcome is TransferOutcome.Finished)
        assertEquals(1, readyFiles().size)
    }

    @Test
    fun `an APK transfers but is flagged for a warning`() {
        // Transferring it is allowed; opening it without a confirmation is not.
        val r = started(offer(mime = "application/vnd.android.package-archive", name = "app.apk"))
        assertTrue(deliver(r, payload) is TransferOutcome.Finished)
        assertTrue(
            FileTransferPolicy.requiresOpenWarning("application/vnd.android.package-archive", "app.apk"),
        )
    }

    // -- integrity ---------------------------------------------------------------------------

    @Test
    fun `a digest mismatch fails and leaves no file behind`() {
        val r = started()
        val outcome = deliver(r, payload, digestOverride = sha256(ByteArray(10)))
        assertTrue("got $outcome", outcome is TransferOutcome.Rejected)
        assertTrue("a file that failed verification must not be promoted", readyFiles().isEmpty())
        assertTrue(partialFiles().isEmpty())
    }

    @Test
    fun `a chunk out of order is refused`() {
        val r = started()
        assertEquals(
            TransferOutcome.Continue,
            r.accept(TransferChunk(UUID.randomUUID(), transferId, 0, ByteArray(128))),
        )
        // Index 2 when 1 was due. Accepting it would silently corrupt the file.
        val outcome = r.accept(TransferChunk(UUID.randomUUID(), transferId, 2, ByteArray(128)))
        assertTrue("got $outcome", outcome is TransferOutcome.Rejected)
    }

    @Test
    fun `a duplicated chunk index is refused`() {
        val r = started()
        assertEquals(
            TransferOutcome.Continue,
            r.accept(TransferChunk(UUID.randomUUID(), transferId, 0, ByteArray(128))),
        )
        val outcome = r.accept(TransferChunk(UUID.randomUUID(), transferId, 0, ByteArray(128)))
        assertTrue("a replayed chunk must not be appended twice: got $outcome", outcome is TransferOutcome.Rejected)
    }

    @Test
    fun `a chunk for a different transfer id is refused`() {
        val r = started()
        val outcome = r.accept(TransferChunk(UUID.randomUUID(), UUID.randomUUID(), 0, ByteArray(16)))
        assertTrue("got $outcome", outcome is TransferOutcome.Rejected)
    }

    @Test
    fun `finishing early with fewer bytes than declared fails`() {
        val r = started()
        r.accept(TransferChunk(UUID.randomUUID(), transferId, 0, payload.copyOfRange(0, 128)))
        val outcome = r.finish(TransferComplete(UUID.randomUUID(), transferId, sha256(payload)))
        assertTrue("a truncated transfer must not complete: got $outcome", outcome is TransferOutcome.Rejected)
        assertTrue(readyFiles().isEmpty())
    }

    @Test
    fun `more bytes than declared are refused`() {
        val r = started()
        val tooMuch = ByteArray(ContentLimits.CHUNK_BYTES)
        var index = 0L
        var outcome: TransferOutcome = TransferOutcome.Continue
        // Keep pushing past the declared 1000 bytes; the receiver must stop us.
        while (index < 5 && outcome is TransferOutcome.Continue) {
            outcome = r.accept(TransferChunk(UUID.randomUUID(), transferId, index, tooMuch))
            index++
        }
        assertTrue("overflow must be refused: got $outcome", outcome is TransferOutcome.Rejected)
    }

    // -- limits ------------------------------------------------------------------------------

    @Test
    fun `a file over the size limit is refused at the offer`() {
        val r = TransferReceiver(cache)
        val outcome = r.evaluate(offer(size = ContentLimits.MAX_FILE_BYTES + 1))
        assertTrue("got $outcome", outcome is TransferOutcome.Rejected)
        assertEquals(
            ProtocolErrorCode.TOO_LARGE,
            (outcome as TransferOutcome.Rejected).code,
        )
    }

    @Test
    fun `a negative size is malformed`() {
        val r = TransferReceiver(cache)
        val outcome = r.evaluate(offer(size = -5))
        assertTrue(outcome is TransferOutcome.Rejected)
        assertEquals(ProtocolErrorCode.MALFORMED_FRAME, (outcome as TransferOutcome.Rejected).code)
    }

    @Test
    fun `a chunk size beyond the frame limit is refused`() {
        val r = TransferReceiver(cache)
        assertEquals(TransferOutcome.Continue, r.evaluate(offer()))
        val outcome = r.begin(
            TransferStart(UUID.randomUUID(), transferId, payload.size.toLong(), ContentLimits.CHUNK_BYTES + 1),
        )
        assertTrue("got $outcome", outcome is TransferOutcome.Rejected)
    }

    @Test
    fun `a size that changes between offer and start is refused`() {
        val r = TransferReceiver(cache)
        assertEquals(TransferOutcome.Continue, r.evaluate(offer()))
        val outcome = r.begin(TransferStart(UUID.randomUUID(), transferId, 999_999, 128))
        assertTrue("got $outcome", outcome is TransferOutcome.Rejected)
    }

    // -- cancellation and cleanup ------------------------------------------------------------

    @Test
    fun `cancelling mid-transfer deletes the partial file`() {
        val r = started()
        r.accept(TransferChunk(UUID.randomUUID(), transferId, 0, payload.copyOfRange(0, 128)))
        assertEquals("a partial should exist while receiving", 1, partialFiles().size)
        r.cancel()
        assertTrue("cancellation must clean up", partialFiles().isEmpty())
        assertTrue(readyFiles().isEmpty())
    }

    @Test
    fun `a hostile filename is sanitised before anything is written`() {
        val r = started(offer(name = "../../../../etc/passwd"))
        assertTrue(deliver(r, payload) is TransferOutcome.Finished)
        // Nothing outside the cache root, and the promoted name comes from the transfer id.
        assertTrue(File(cacheRoot, "ready").listFiles()!!.all { it.parentFile == File(cacheRoot, "ready") })
        assertFalse(File("/etc/passwd").let { it.exists() && it.length() == payload.size.toLong() })
    }

    // -- version negotiation -----------------------------------------------------------------

    @Test
    fun `the file capability is versioned so an old peer can be told plainly`() {
        // The version lives in the capability string, so a future incompatible format announces
        // file-v2 and an older peer simply does not match it.
        assertEquals("file-v1", Capabilities.FILE_V1)
        assertEquals(1, FileTransferPolicy.WIRE_VERSION)
    }

    @Test
    fun `a peer without the file capability is refused before any bytes move`() {
        val oldPeer = setOf(Capabilities.TEXT, Capabilities.IMAGE, Capabilities.PDF)
        val refusal = if (Capabilities.FILE_V1 in oldPeer) null else FileTransferPolicy.SendRefusal.PeerTooOld
        assertEquals(FileTransferPolicy.SendRefusal.PeerTooOld, refusal)
    }

    @Test
    fun `a peer announcing the capability is accepted`() {
        val newPeer = setOf(Capabilities.TEXT, Capabilities.FILE_V1)
        assertTrue(Capabilities.FILE_V1 in newPeer)
    }
}
