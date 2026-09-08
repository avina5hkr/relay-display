package com.avinash.relaydisplay

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.avinash.relaydisplay.content.ContentCache
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the FileProvider can actually produce a URI for a file the cache wrote.
 *
 * This exists because it could not, and nothing noticed. `file_provider_paths.xml` declared
 * `path="ready/"`, which resolves to `<cacheDir>/ready/`, while `ContentCache` writes to
 * `<cacheDir>/relay_cache/ready/` — the container directory was missing from the declaration. The
 * two facts live in different files, in different languages, and neither the compiler nor lint
 * relates them. So the first real `getUriForFile` call threw
 * `IllegalArgumentException: Failed to find configured root that contains ...` and took the app
 * down, on a device, on the first tap of "Open".
 *
 * A unit test cannot catch this: it needs a real `FileProvider` reading the real manifest and the
 * real XML. Hence instrumentation.
 */
@RunWith(AndroidJUnit4::class)
class FileProviderPathTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val authority: String get() = "${context.packageName}.fileprovider"

    private val readyDir: File
        get() = File(File(context.cacheDir, ContentCache.DIRECTORY_NAME), ContentCache.RECEIVED_DIR)

    private val written = mutableListOf<File>()

    @After
    fun tearDown() {
        written.forEach { it.delete() }
    }

    private fun writeReadyFile(name: String): File {
        readyDir.mkdirs()
        val file = File(readyDir, name)
        file.writeBytes(ByteArray(16) { 4 })
        written += file
        return file
    }

    @Test
    fun aFileTheCacheWroteCanBeSharedThroughTheProvider() {
        val file = writeReadyFile("${UUID.randomUUID()}.pdf")
        val uri = try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (e: IllegalArgumentException) {
            fail(
                "file_provider_paths.xml does not cover the cache's received-files directory. " +
                    "It must be \"${ContentCache.DIRECTORY_NAME}/${ContentCache.RECEIVED_DIR}/\" " +
                    "under cache-path. Underlying error: ${e.message}",
            )
            return
        }
        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        // Never a file:// URI: that throws FileUriExposedException on API 24+ and hands out a
        // path with no scoping or expiry.
        assertTrue("expected a content URI, got $uri", uri.toString().startsWith("content://"))
    }

    @Test
    fun theSharedUriActuallyReadsBackTheRightBytes() {
        // Proves the grant resolves to the file, not merely that a URI could be built.
        val file = writeReadyFile("${UUID.randomUUID()}.bin")
        val uri = FileProvider.getUriForFile(context, authority, file)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals(16, bytes.size)
        assertTrue(bytes.all { it == 4.toByte() })
    }

    @Test
    fun aPartialIsNotExposedThroughTheProvider() {
        // incoming/ is deliberately absent from the declared paths: a partial has not passed its
        // hash check, so its bytes are whatever the peer sent.
        val incoming = File(File(context.cacheDir, ContentCache.DIRECTORY_NAME), ContentCache.INCOMING_DIR)
        incoming.mkdirs()
        val partial = File(incoming, "${UUID.randomUUID()}.part")
        partial.writeBytes(ByteArray(8))
        written += partial
        try {
            val uri = FileProvider.getUriForFile(context, authority, partial)
            fail("a partial must not be shareable, but got $uri")
        } catch (e: IllegalArgumentException) {
            // Correct: no configured root contains incoming/.
        }
    }

    @Test
    fun aPresentationPayloadIsNotExposedThroughTheProvider() {
        // Images and PDFs shown on screen are not the received-files feature, and the user never
        // asked to keep or share them.
        val dir = File(File(context.cacheDir, ContentCache.DIRECTORY_NAME), ContentCache.PRESENTATION_DIR)
        dir.mkdirs()
        val payload = File(dir, "${UUID.randomUUID()}.jpg")
        payload.writeBytes(ByteArray(8))
        written += payload
        try {
            val uri = FileProvider.getUriForFile(context, authority, payload)
            fail("a presentation payload must not be shareable, but got $uri")
        } catch (e: IllegalArgumentException) {
            // Correct.
        }
    }

    @Test
    fun anOutboundSpoolFileIsNotExposedThroughTheProvider() {
        // Spool files are copies of the user's own documents waiting to be sent. Nothing outside
        // this process has any reason to read one.
        val dir = File(File(context.cacheDir, ContentCache.DIRECTORY_NAME), "spool")
        dir.mkdirs()
        val spooled = File(dir, "${UUID.randomUUID()}.spool")
        spooled.writeBytes(ByteArray(8))
        written += spooled
        try {
            val uri = FileProvider.getUriForFile(context, authority, spooled)
            fail("a spool file must not be shareable, but got $uri")
        } catch (e: IllegalArgumentException) {
            // Correct.
        }
    }

    @Test
    fun theWholeCacheIsNotExposed() {
        // A provider rooted at the cache itself would turn any traversal that got past the
        // sanitiser into a readable URI for arbitrary app-private data.
        val loose = File(context.cacheDir, "not-a-received-file.txt")
        loose.writeText("x")
        written += loose
        try {
            val uri = FileProvider.getUriForFile(context, authority, loose)
            fail("the cache root must not be exposed, but got $uri")
        } catch (e: IllegalArgumentException) {
            // Correct.
        }
    }
}
