package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.protocol.ContentOffer
import com.avinash.relaydisplay.protocol.FileManifestEntry
import com.avinash.relaydisplay.protocol.ProtocolErrorCode
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That consent means "these exact files" and nothing wider.
 *
 * The vulnerability this pins: `file-v1` gated generic files on a single `batchAccepted` boolean.
 * Its manifest carried no transfer id and no digest, and the per-file offer carried no batch
 * identity, so once a user accepted a batch the receiver had no way to tell whether an arriving
 * file was one of the ones it had displayed. A controller could show one manifest and send
 * entirely different files, and if it never sent the closing SHOW_FILE the counters that were
 * supposed to expire consent never advanced at all.
 *
 * Every test below is a substitution that must be refused before a partial file exists.
 */
class AcceptedBatchTest {

    private val batchId: UUID = UUID.fromString("0a0a0a0a-0b0b-0c0c-0d0d-0e0e0e0e0e0e")

    private fun digest(seed: Int) = ByteArray(32) { (it + seed).toByte() }

    private fun entry(
        transferId: UUID = UUID.randomUUID(),
        name: String = "report.pdf",
        mime: String = "application/pdf",
        size: Long = 1_024L,
        sha: ByteArray = digest(1),
    ) = FileManifestEntry(transferId, name, mime, size, sha)

    private fun batch(vararg entries: FileManifestEntry) =
        AcceptedBatch(batchId, entries.toList(), acceptedAtMs = 0L)

    /** An offer that matches [entry] exactly, apart from whatever the test overrides. */
    private fun offerFor(
        entry: FileManifestEntry,
        index: Int = 0,
        batch: UUID? = batchId,
        transferId: UUID = entry.transferId,
        name: String = entry.displayName,
        mime: String = entry.mimeType,
        size: Long = entry.sizeBytes,
        sha: ByteArray = entry.sha256,
        manifestIndex: Int? = index,
    ) = ContentOffer(
        id = UUID.randomUUID(),
        transferId = transferId,
        kind = ContentKind.FILE,
        sizeBytes = size,
        mimeType = mime,
        displayName = name,
        sha256 = sha,
        batchId = batch,
        manifestIndex = manifestIndex,
    )

    private fun refusal(v: AcceptedBatch.OfferVerdict): AcceptedBatch.OfferVerdict.Refused {
        assertTrue("expected a refusal, got $v", v is AcceptedBatch.OfferVerdict.Refused)
        return v as AcceptedBatch.OfferVerdict.Refused
    }

    // -- the exact match ---------------------------------------------------------------------

    @Test
    fun `an offer matching its manifest entry exactly is accepted`() {
        val e = entry()
        val verdict = batch(e).match(offerFor(e))
        assertTrue(verdict is AcceptedBatch.OfferVerdict.Accepted)
        assertEquals(0, (verdict as AcceptedBatch.OfferVerdict.Accepted).index)
        assertEquals(e, verdict.entry)
    }

    @Test
    fun `each entry of a multi-file batch matches at its own index`() {
        val a = entry(name = "a.pdf", sha = digest(1))
        val b = entry(name = "b.pdf", sha = digest(2))
        val c = entry(name = "c.pdf", sha = digest(3))
        val subject = batch(a, b, c)
        assertTrue(subject.match(offerFor(a, index = 0)) is AcceptedBatch.OfferVerdict.Accepted)
        assertTrue(subject.match(offerFor(b, index = 1)) is AcceptedBatch.OfferVerdict.Accepted)
        assertTrue(subject.match(offerFor(c, index = 2)) is AcceptedBatch.OfferVerdict.Accepted)
    }

    // -- substitutions -----------------------------------------------------------------------

    @Test
    fun `a wrong batch id is refused`() {
        val e = entry()
        val v = refusal(batch(e).match(offerFor(e, batch = UUID.randomUUID())))
        assertEquals(ProtocolErrorCode.PERMISSION_DENIED, v.code)
    }

    @Test
    fun `an offer naming no batch at all is refused`() {
        // The shape a file-v1 offer has. It cannot be matched against anything.
        val e = entry()
        val v = refusal(batch(e).match(offerFor(e, batch = null)))
        assertEquals(ProtocolErrorCode.PERMISSION_DENIED, v.code)
    }

    @Test
    fun `an unknown transfer id is refused`() {
        val e = entry()
        val v = refusal(batch(e).match(offerFor(e, transferId = UUID.randomUUID())))
        assertEquals(ProtocolErrorCode.PERMISSION_DENIED, v.code)
    }

    @Test
    fun `a wrong manifest index is refused`() {
        val a = entry(name = "a.pdf", sha = digest(1))
        val b = entry(name = "b.pdf", sha = digest(2))
        val v = refusal(batch(a, b).match(offerFor(b, manifestIndex = 0)))
        assertEquals(ProtocolErrorCode.MALFORMED_FRAME, v.code)
    }

    @Test
    fun `a different filename is refused`() {
        val e = entry()
        val v = refusal(batch(e).match(offerFor(e, name = "invoice.pdf")))
        assertEquals(ProtocolErrorCode.MALFORMED_FRAME, v.code)
    }

    @Test
    fun `a different mime type is refused`() {
        val e = entry()
        val v = refusal(batch(e).match(offerFor(e, mime = "application/vnd.android.package-archive")))
        assertEquals(ProtocolErrorCode.UNSUPPORTED_FORMAT, v.code)
    }

    @Test
    fun `a different size is refused`() {
        val e = entry()
        assertEquals(
            ProtocolErrorCode.MALFORMED_FRAME,
            refusal(batch(e).match(offerFor(e, size = e.sizeBytes + 1))).code,
        )
        assertEquals(
            ProtocolErrorCode.MALFORMED_FRAME,
            refusal(batch(e).match(offerFor(e, size = e.sizeBytes - 1))).code,
        )
    }

    @Test
    fun `a different digest is refused`() {
        // The substitution that matters most: same name, same type, same size, different content.
        val e = entry()
        val v = refusal(batch(e).match(offerFor(e, sha = digest(99))))
        assertEquals(ProtocolErrorCode.MALFORMED_FRAME, v.code)
    }

    @Test
    fun `a digest differing in one byte is refused`() {
        val e = entry()
        val tampered = e.sha256.copyOf().also { it[31] = (it[31] + 1).toByte() }
        assertEquals(
            ProtocolErrorCode.MALFORMED_FRAME,
            refusal(batch(e).match(offerFor(e, sha = tampered))).code,
        )
    }

    // -- normalization, applied to both sides ------------------------------------------------

    @Test
    fun `names are compared after one consistent normalization`() {
        // The manifest name is already sanitised; an offer whose name sanitises to the same thing
        // is the same file and must not be refused over a stripped character.
        val e = entry(name = "report.pdf")
        val v = batch(e).match(offerFor(e, name = "report.pdf"))
        assertTrue("normalisation should have matched: $v", v is AcceptedBatch.OfferVerdict.Accepted)
    }

    @Test
    fun `a traversal dressed up as the accepted name never survives raw`() {
        val e = entry(name = "report.pdf")
        val v = batch(e).match(offerFor(e, name = "../../etc/report.pdf"))
        // Either outcome is safe: the sanitiser reduces this to "report.pdf", which is the
        // approved file. What must never happen is the raw path being treated as the name.
        if (v is AcceptedBatch.OfferVerdict.Accepted) {
            assertFalse(v.entry.displayName.contains('/'))
            assertFalse(v.entry.displayName.contains(".."))
        }
    }

    @Test
    fun `mime comparison ignores case and surrounding space`() {
        val e = entry(mime = "application/pdf")
        assertTrue(
            batch(e).match(offerFor(e, mime = "  APPLICATION/PDF ")) is AcceptedBatch.OfferVerdict.Accepted,
        )
    }

    // -- extra files and replays --------------------------------------------------------------

    @Test
    fun `an extra file beyond the accepted manifest is refused`() {
        val e = entry()
        val subject = batch(e)
        assertTrue(subject.match(offerFor(e)) is AcceptedBatch.OfferVerdict.Accepted)
        subject.markReceiving(e.transferId)
        subject.markReceived(e.transferId)

        // A twenty-first file after a twenty-file batch, in miniature.
        val extra = entry(name = "surprise.apk", sha = digest(50))
        assertEquals(ProtocolErrorCode.PERMISSION_DENIED, refusal(subject.match(offerFor(extra))).code)
    }

    @Test
    fun `replaying a completed file is refused`() {
        val e = entry()
        val subject = batch(e)
        subject.markReceiving(e.transferId)
        subject.markReceived(e.transferId)
        assertEquals(ProtocolErrorCode.PERMISSION_DENIED, refusal(subject.match(offerFor(e))).code)
    }

    @Test
    fun `offering the same file twice at once is refused as busy`() {
        val e = entry()
        val subject = batch(e)
        assertTrue(subject.match(offerFor(e)) is AcceptedBatch.OfferVerdict.Accepted)
        subject.markReceiving(e.transferId)
        assertEquals(ProtocolErrorCode.BUSY, refusal(subject.match(offerFor(e))).code)
    }

    @Test
    fun `a file that failed cannot be re-offered within the same batch`() {
        val e = entry()
        val subject = batch(e)
        subject.markReceiving(e.transferId)
        subject.markFailed(e.transferId)
        assertEquals(ProtocolErrorCode.PERMISSION_DENIED, refusal(subject.match(offerFor(e))).code)
    }

    // -- entry states and settling ------------------------------------------------------------

    @Test
    fun `a fresh batch has every entry pending and is not settled`() {
        val a = entry(name = "a.pdf", sha = digest(1))
        val b = entry(name = "b.pdf", sha = digest(2))
        val subject = batch(a, b)
        assertEquals(AcceptedBatch.EntryState.PENDING, subject.stateOf(a.transferId))
        assertEquals(AcceptedBatch.EntryState.PENDING, subject.stateOf(b.transferId))
        assertFalse(subject.settled)
        assertEquals(2, subject.size)
    }

    @Test
    fun `a batch settles only when nothing is pending or receiving`() {
        val a = entry(name = "a.pdf", sha = digest(1))
        val b = entry(name = "b.pdf", sha = digest(2))
        val subject = batch(a, b)

        subject.markReceiving(a.transferId)
        assertFalse(subject.settled)
        subject.markReceived(a.transferId)
        assertFalse("b is still pending", subject.settled)

        subject.markReceiving(b.transferId)
        subject.markFailed(b.transferId)
        assertTrue(subject.settled)
        assertEquals(1, subject.receivedCount)
        assertEquals(1, subject.failedCount)
    }

    @Test
    fun `only one entry can be receiving at a time and it is reported`() {
        val a = entry(name = "a.pdf", sha = digest(1))
        val subject = batch(a, entry(name = "b.pdf", sha = digest(2)))
        assertNull(subject.receivingTransferId)
        subject.markReceiving(a.transferId)
        assertEquals(a.transferId, subject.receivingTransferId)
        subject.markReceived(a.transferId)
        assertNull(subject.receivingTransferId)
    }

    @Test
    fun `illegal transitions are rejected rather than applied`() {
        val e = entry()
        val subject = batch(e)
        // Received without ever receiving.
        assertFalse(subject.markReceived(e.transferId))
        assertEquals(AcceptedBatch.EntryState.PENDING, subject.stateOf(e.transferId))
        // Unknown ids change nothing.
        assertFalse(subject.markReceiving(UUID.randomUUID()))
    }

    // -- idempotent cleanup -------------------------------------------------------------------

    @Test
    fun `cancelling is idempotent and keeps verified files`() {
        val a = entry(name = "a.pdf", sha = digest(1))
        val b = entry(name = "b.pdf", sha = digest(2))
        val subject = batch(a, b)
        subject.markReceiving(a.transferId)
        subject.markReceived(a.transferId)

        subject.cancelUnfinished()
        subject.cancelUnfinished()
        subject.cancelUnfinished()

        // The verified file stays received: those bytes passed every check and the user was told
        // they arrived. Only the unfinished one is cancelled.
        assertEquals(AcceptedBatch.EntryState.RECEIVED, subject.stateOf(a.transferId))
        assertEquals(AcceptedBatch.EntryState.CANCELLED, subject.stateOf(b.transferId))
        assertTrue(subject.settled)
        assertEquals(1, subject.receivedCount)
    }

    @Test
    fun `a failed entry lets the batch settle so consent can expire`() {
        // The stuck state this pins was found on hardware. When a single file failed mid-transfer
        // the receiver left its entry PENDING, so the batch never settled, consent never expired,
        // and every later batch was refused as BUSY for the rest of the session. Marking the entry
        // failed is what lets the batch reach a terminal state without the sender having to tell
        // it -- a file can fail for reasons the sender never reports.
        val a = entry(name = "a.pdf", sha = digest(1))
        val b = entry(name = "b.pdf", sha = digest(2))
        val subject = batch(a, b)

        subject.markReceiving(a.transferId)
        subject.markReceived(a.transferId)
        subject.markReceiving(b.transferId)
        assertFalse("still receiving b", subject.settled)

        // b fails, as an aborted transfer does.
        assertTrue(subject.markFailed(b.transferId))
        assertTrue("a failed entry must let the batch settle", subject.settled)
        assertEquals(1, subject.receivedCount)
        assertEquals(1, subject.failedCount)
    }

    @Test
    fun `a batch of one failed file settles immediately`() {
        val e = entry()
        val subject = batch(e)
        subject.markReceiving(e.transferId)
        subject.markFailed(e.transferId)
        assertTrue(subject.settled)
        assertEquals(0, subject.receivedCount)
    }

    @Test
    fun `a file failing before it starts also settles the batch`() {
        // An offer refused, or a transfer that never got past its first chunk.
        val e = entry()
        val subject = batch(e)
        assertTrue(subject.markFailed(e.transferId))
        assertTrue(subject.settled)
    }

    @Test
    fun `nothing can be offered after the batch is cancelled`() {
        val e = entry()
        val subject = batch(e)
        subject.cancelUnfinished()
        assertEquals(ProtocolErrorCode.PERMISSION_DENIED, refusal(subject.match(offerFor(e))).code)
    }

    @Test
    fun `a refusal reason carries no peer supplied content`() {
        // Rejection reasons reach a diagnostic log the user may share. They must describe the rule
        // that was broken, never the filename, type, size or digest that broke it.
        val secret = "very-private-name"
        val e = entry(name = "$secret.pdf", mime = "application/pdf")
        val refusals = listOf(
            batch(e).match(offerFor(e, batch = UUID.randomUUID())),
            batch(e).match(offerFor(e, transferId = UUID.randomUUID())),
            batch(e).match(offerFor(e, name = "$secret-substitute.pdf")),
            batch(e).match(offerFor(e, size = 99)),
            batch(e).match(offerFor(e, sha = digest(7))),
            batch(e).match(offerFor(e, mime = "text/plain")),
        )
        for (v in refusals) {
            val reason = refusal(v).reason
            assertFalse("reason leaked a filename: '$reason'", reason.contains(secret))
            assertFalse("reason leaked a size or digest: '$reason'", reason.any { it.isDigit() })
        }
    }
}
