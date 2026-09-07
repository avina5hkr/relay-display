package com.avinash.relaydisplay.content

import com.avinash.relaydisplay.protocol.ContentLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filenames arriving from the peer, which are untrusted input.
 *
 * A name is chosen by whatever is on the other phone and is then used to label a file and,
 * potentially, to pick where it is written. Every one of these cases is a way that could go wrong
 * if the sanitiser were bypassed or weakened.
 */
class ReceivedFilenameTest {

    private fun sanitize(raw: String) = FilenameSanitizer.sanitize(raw)

    // -- path traversal ----------------------------------------------------------------------

    @Test
    fun `relative traversal is reduced to its last segment`() {
        assertEquals("passwd", sanitize("../../etc/passwd"))
        assertEquals("passwd", sanitize("../../../../../../etc/passwd"))
    }

    @Test
    fun `absolute paths lose their directories`() {
        assertEquals("shadow", sanitize("/etc/shadow"))
        assertEquals("hosts", sanitize("/system/etc/hosts"))
    }

    @Test
    fun `windows style traversal is handled too`() {
        // Both separators are stripped: a name may have been produced on any platform.
        assertEquals("system.ini", sanitize("..\\..\\windows\\system.ini"))
        assertEquals("evil.txt", sanitize("C:\\Users\\me\\evil.txt"))
    }

    @Test
    fun `a name that is only traversal becomes the fallback`() {
        // "../.." reduces to "..", which trims to empty; empty must never become a real path.
        assertFalse(sanitize("../..").contains(".."))
        assertFalse(sanitize("../..").isEmpty())
        assertFalse(sanitize("/////").isEmpty())
    }

    @Test
    fun `no sanitised name can contain a separator`() {
        val hostile = listOf(
            "../../etc/passwd", "/etc/shadow", "..\\..\\win.ini", "a/b/c", "a\\b\\c",
            "....//....//x", "dir/../../x",
        )
        for (name in hostile) {
            val safe = sanitize(name)
            assertFalse("'$safe' from '$name' still has a slash", safe.contains('/'))
            assertFalse("'$safe' from '$name' still has a backslash", safe.contains('\\'))
        }
    }

    // -- control and hostile characters ------------------------------------------------------

    @Test
    fun `control characters are dropped`() {
        val nasty = "re" + Char(0) + "port" + Char(9) + Char(10) + ".txt"
        val safe = sanitize(nasty)
        assertFalse(safe.any { it.code < 0x20 || it.code == 0x7F })
    }

    @Test
    fun `filesystem-hostile characters become underscores`() {
        val safe = sanitize("q?:*<>|\"name.txt")
        for (c in listOf('?', ':', '*', '<', '>', '|', '"')) {
            assertFalse("'$c' survived in '$safe'", safe.contains(c))
        }
    }

    @Test
    fun `windows device names are defused`() {
        // Harmless on Android, but a received name may be shared onward to a desktop.
        assertTrue(sanitize("CON").startsWith("received-file"))
        assertTrue(sanitize("nul.txt").startsWith("received-file"))
    }

    // -- ordinary names must survive ---------------------------------------------------------

    @Test
    fun `spaces and unicode are preserved`() {
        // Mangling these would be a bug, not a defence: they are perfectly ordinary filenames.
        assertEquals("my holiday photo.jpg", sanitize("my holiday photo.jpg"))
        assertEquals("契約書.pdf", sanitize("契約書.pdf"))
        assertEquals("Отчёт 2026.docx", sanitize("Отчёт 2026.docx"))
        assertEquals("notes – draft.md", sanitize("notes – draft.md"))
    }

    @Test
    fun `an emoji name survives`() {
        assertEquals("trip 🏖 photos.zip", sanitize("trip 🏖 photos.zip"))
    }

    // -- byte-limit truncation ---------------------------------------------------------------

    @Test
    fun `a long ascii name is truncated within the byte budget`() {
        val long = "a".repeat(500) + ".txt"
        val safe = FilenameSanitizer.sanitizeToByteLimit(long, ContentLimits.MAX_FILENAME_BYTES)
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= ContentLimits.MAX_FILENAME_BYTES)
        assertTrue("the extension is the only hint about the type", safe.endsWith(".txt"))
    }

    @Test
    fun `a multibyte name is truncated by bytes not characters`() {
        // The character cap alone was not enough: 120 CJK characters is 360 bytes and would
        // overflow a 255-byte field.
        val cjk = "図".repeat(200) + ".pdf"
        val safe = FilenameSanitizer.sanitizeToByteLimit(cjk, ContentLimits.MAX_FILENAME_BYTES)
        assertTrue(
            "got ${safe.toByteArray(Charsets.UTF_8).size} bytes",
            safe.toByteArray(Charsets.UTF_8).size <= ContentLimits.MAX_FILENAME_BYTES,
        )
        assertTrue(safe.endsWith(".pdf"))
    }

    @Test
    fun `truncation never splits a character`() {
        // A byte-wise cut would leave a broken surrogate pair or half a UTF-8 sequence.
        for (limit in 8..40) {
            val safe = FilenameSanitizer.sanitizeToByteLimit("🏖".repeat(50) + ".zip", limit)
            assertEquals(
                "limit $limit produced an unencodable name",
                safe,
                String(safe.toByteArray(Charsets.UTF_8), Charsets.UTF_8),
            )
            assertTrue(safe.toByteArray(Charsets.UTF_8).size <= limit)
        }
    }

    @Test
    fun `a short name is returned unchanged`() {
        assertEquals("a.txt", FilenameSanitizer.sanitizeToByteLimit("a.txt", 255))
    }

    @Test
    fun `an all-extension pathological name still fits`() {
        val safe = FilenameSanitizer.sanitizeToByteLimit("." + "x".repeat(400), 32)
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 32)
        assertFalse(safe.isEmpty())
    }
}
