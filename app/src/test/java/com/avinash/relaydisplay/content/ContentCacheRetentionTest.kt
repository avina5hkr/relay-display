package com.avinash.relaydisplay.content

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Retention: what the cache keeps, what it throws away, and whether its budgets agree with the
 * limits the sender validates against.
 *
 * The last part is why this file exists. The byte and entry budgets used to be private constants
 * inside ContentCache, and the entry budget (12) was smaller than one legal batch (20 files), so
 * accepting a full batch silently deleted the first eight files of it. Nothing failed; the files
 * were simply gone. These tests pin the relationship rather than the numbers.
 */
class ContentCacheRetentionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private var now: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        root = temp.newFolder("relay_cache")
    }

    private fun cache(
        maxBytes: Long = FileTransferPolicy.MAX_PENDING_BYTES,
        maxEntries: Int = FileTransferPolicy.MAX_PENDING_FILES,
    ) = ContentCache(root, maxBytes, maxEntries, { Long.MAX_VALUE }, { now })

    /** Promotes one generic file of [size] bytes, as the receiver does on completion. */
    private fun store(
        cache: ContentCache,
        name: String,
        size: Int = 8,
        mime: String = "application/pdf",
        id: UUID = UUID.randomUUID(),
    ): File? {
        val partial = cache.createPartial(id)
        partial.writeBytes(ByteArray(size) { 7 })
        return cache.promote(
            partial,
            id,
            name.substringAfterLast('.', ""),
            ContentCache.PromotedMetadata(displayName = name, mimeType = mime),
        )?.also { it.setLastModified(now) }
    }

    private fun payloads() = File(root, "ready").listFiles()
        ?.filterNot { it.name.endsWith(".meta") }
        .orEmpty()

    // -- the invariant that was broken --------------------------------------------------------

    @Test
    fun `the retention budget can hold one whole legal batch`() {
        assertTrue(
            "retention is smaller than a batch the sender would happily offer",
            FileTransferPolicy.retentionInvariantsHold(),
        )
        assertTrue(FileTransferPolicy.MAX_PENDING_FILES >= FileTransferPolicy.MAX_FILES_PER_BATCH)
        assertTrue(FileTransferPolicy.MAX_PENDING_BYTES >= FileTransferPolicy.MAX_BATCH_BYTES)
    }

    @Test
    fun `the cache enforces exactly the documented budget`() {
        // Not "some budget": the numbers the docs quote and the sender validates against.
        assertEquals(FileTransferPolicy.MAX_PENDING_BYTES, ContentCache.DEFAULT_MAX_BYTES)
        assertEquals(FileTransferPolicy.MAX_PENDING_FILES, ContentCache.DEFAULT_MAX_ENTRIES)
    }

    @Test
    fun `a full batch survives being received`() {
        val cache = cache()
        repeat(FileTransferPolicy.MAX_FILES_PER_BATCH) { store(cache, "file$it.pdf") }
        // The regression: with a 12-entry budget this was 12, and eight received files were gone
        // before the user could open any of them.
        assertEquals(FileTransferPolicy.MAX_FILES_PER_BATCH, payloads().size)
        assertEquals(FileTransferPolicy.MAX_FILES_PER_BATCH, cache.receivedFiles().size)
    }

    // -- metadata --------------------------------------------------------------------------

    @Test
    fun `a received file keeps its name and type`() {
        val cache = cache()
        store(cache, "Quarterly Report.pdf", size = 32)
        val received = cache.receivedFiles().single()
        assertEquals("Quarterly Report.pdf", received.displayName)
        assertEquals("application/pdf", received.mimeType)
        assertEquals(32L, received.sizeBytes)
    }

    @Test
    fun `metadata survives a new cache instance`() {
        // The whole point of the sidecar: the file on disk is named after a transfer id, so
        // without it a restart leaves a list of unidentifiable files.
        store(cache(), "notes.txt", mime = "text/plain")
        assertEquals("notes.txt", cache().receivedFiles().single().displayName)
    }

    @Test
    fun `a file whose sidecar is missing is still listed`() {
        val cache = cache()
        store(cache, "orphan.bin")
        File(root, "ready").listFiles()?.first { it.name.endsWith(".meta") }?.delete()
        // Losing a label is not a reason to hide a file the user was told they received.
        val received = cache.receivedFiles()
        assertEquals(1, received.size)
        assertEquals(FileTransferPolicy.FALLBACK_MIME, received.single().mimeType)
    }

    @Test
    fun `a sidecar whose recorded size disagrees with the file is ignored`() {
        val cache = cache()
        store(cache, "tampered.pdf", size = 16)
        val sidecar = File(root, "ready").listFiles()!!.first { it.name.endsWith(".meta") }
        sidecar.writeText("tampered.pdf\napplication/pdf\n999999\n")
        // The file's own length is the fact; the sidecar is a claim. Trusting the claim would let
        // a stale sidecar misreport what is about to be shared.
        assertEquals(FileTransferPolicy.FALLBACK_MIME, cache.receivedFiles().single().mimeType)
    }

    @Test
    fun `a sidecar cannot smuggle a path through the display name`() {
        val cache = cache()
        store(cache, "ok.pdf")
        val sidecar = File(root, "ready").listFiles()!!.first { it.name.endsWith(".meta") }
        val payload = File(root, "ready").listFiles()!!.first { !it.name.endsWith(".meta") }
        sidecar.writeText("../../../etc/passwd\ntext/plain\n${payload.length()}\n")
        val name = cache.receivedFiles().single().displayName
        assertFalse("a path survived into the display name: '$name'", name.contains('/'))
        assertFalse(name.contains(".."))
    }

    @Test
    fun `sidecars do not count against the entry budget`() {
        // Otherwise half the budget goes on three-line text files and the effective file count is
        // half what policy advertises.
        val cache = cache(maxEntries = 4)
        repeat(4) { store(cache, "f$it.pdf") }
        assertEquals(4, payloads().size)
    }

    // -- eviction and expiry ------------------------------------------------------------------

    @Test
    fun `eviction removes the oldest first and takes its sidecar with it`() {
        val cache = cache(maxEntries = 2)
        store(cache, "oldest.pdf")
        now += 1_000
        store(cache, "middle.pdf")
        now += 1_000
        store(cache, "newest.pdf")

        val kept = cache.receivedFiles().map { it.displayName }
        assertEquals(listOf("newest.pdf", "middle.pdf"), kept)
        // No orphan metadata left describing a file that is gone.
        val sidecars = File(root, "ready").listFiles()!!.count { it.name.endsWith(".meta") }
        assertEquals(2, sidecars)
    }

    @Test
    fun `eviction respects the byte budget`() {
        val cache = cache(maxBytes = 100)
        store(cache, "a.bin", size = 60)
        now += 1_000
        store(cache, "b.bin", size = 60)
        assertEquals(1, payloads().size)
        assertEquals("b.bin", cache.receivedFiles().single().displayName)
    }

    @Test
    fun `expiry deletes files past the retention window`() {
        val cache = cache()
        store(cache, "stale.pdf")
        assertEquals(1, payloads().size)

        now += FileTransferPolicy.PENDING_EXPIRY_MS + 1
        assertEquals(1, cache.sweepExpired())
        assertEquals(0, payloads().size)
        assertTrue(File(root, "ready").listFiles()!!.none { it.name.endsWith(".meta") })
    }

    @Test
    fun `expiry keeps files inside the window`() {
        val cache = cache()
        store(cache, "fresh.pdf")
        now += FileTransferPolicy.PENDING_EXPIRY_MS - 1_000
        assertEquals(0, cache.sweepExpired())
        assertEquals(1, payloads().size)
    }

    @Test
    fun `partials are swept and never promoted`() {
        val cache = cache()
        val id = UUID.randomUUID()
        cache.createPartial(id).writeBytes(ByteArray(4))
        assertEquals(1, File(root, "incoming").listFiles()!!.size)
        cache.sweepPartials()
        // An unverified partial has whatever bytes the peer sent and failed no check yet, so it is
        // never worth keeping across a restart.
        assertEquals(0, File(root, "incoming").listFiles()!!.size)
        assertEquals(0, payloads().size)
    }

    // -- deletion --------------------------------------------------------------------------

    @Test
    fun `deleting a received file removes its bytes and its sidecar`() {
        val cache = cache()
        val id = UUID.randomUUID()
        store(cache, "gone.pdf", id = id)
        assertTrue(cache.deleteReceived(id))
        assertEquals(0, File(root, "ready").listFiles()!!.size)
        assertTrue(cache.receivedFiles().isEmpty())
    }

    @Test
    fun `deleting an unknown id is harmless`() {
        val cache = cache()
        store(cache, "kept.pdf")
        assertFalse(cache.deleteReceived(UUID.randomUUID()))
        assertEquals(1, payloads().size)
    }

    @Test
    fun `a generic file keeps an extension even when its bytes are unrecognisable`() {
        // MimeSniffer identifies almost nothing, so without a name-derived fallback a received
        // file is stored with no extension and saving it out produces an extensionless file.
        val cache = cache()
        val promoted = store(cache, "archive.zip")
        assertNotNull(promoted)
        assertEquals("zip", promoted!!.extension)
    }

    @Test
    fun `promote without metadata writes no sidecar`() {
        // Images and PDFs are shown immediately and their labels live in the presentation state.
        val cache = cache()
        val id = UUID.randomUUID()
        val partial = cache.createPartial(id)
        partial.writeBytes(ByteArray(8))
        cache.promote(partial, id, "jpg")
        assertTrue(File(root, "ready").listFiles()!!.none { it.name.endsWith(".meta") })
        assertNull(
            "a file with no sidecar should not claim a recorded name",
            cache.receivedFiles().singleOrNull()?.displayName?.takeIf { it.contains("jpeg") },
        )
    }
}
