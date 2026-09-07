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

    /**
     * The regression this file exists for.
     *
     * 180 ASCII characters is 180 bytes: comfortably inside the 255-byte wire field, so a
     * byte-only check passes it straight through. The character cap then has to do the work, and
     * the earlier implementation applied it with `take(120)` -- which cut from the end, where the
     * extension is. A received PDF arrived with no extension at all and nothing would open it.
     */
    @Test
    fun `a 180 character ASCII name keeps its extension and both limits`() {
        val raw = "a".repeat(176) + ".pdf"
        assertEquals(180, raw.length)

        val safe = FilenameSanitizer.sanitizeToByteLimit(raw, 255)

        assertTrue("extension lost: '$safe'", safe.endsWith(".pdf"))
        assertTrue("over the character cap: ${safe.length}", safe.length <= FilenameSanitizer.MAX_LENGTH)
        assertTrue("over the byte cap", safe.toByteArray(Charsets.UTF_8).size <= 255)
        // The stem is cut, not the extension: everything before the dot is still the original run.
        assertEquals("a".repeat(FilenameSanitizer.MAX_LENGTH - 4), safe.substringBeforeLast('.'))
    }

    @Test
    fun `the character cap applies even when the byte cap is generous`() {
        val safe = FilenameSanitizer.sanitizeToByteLimit("b".repeat(400) + ".jpg", Int.MAX_VALUE)
        assertEquals(FilenameSanitizer.MAX_LENGTH, safe.length)
        assertTrue(safe.endsWith(".jpg"))
    }

    @Test
    fun `the byte cap applies even when the character cap is generous`() {
        // 40 CJK characters: 40 chars, 120 bytes. Under the character cap, over a 64-byte field.
        val safe = FilenameSanitizer.sanitizeToByteLimit("\u6f22".repeat(40) + ".txt", 64)
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 64)
        assertTrue(safe.length <= FilenameSanitizer.MAX_LENGTH)
        assertTrue(safe.endsWith(".txt"))
    }

    @Test
    fun `sanitize preserves the extension when it truncates`() {
        // sanitize() shares the same truncation, so the character-capped path keeps the type hint
        // too. Its documented length behaviour is unchanged.
        val safe = FilenameSanitizer.sanitize("c".repeat(500) + ".jpg")
        assertEquals(FilenameSanitizer.MAX_LENGTH, safe.length)
        assertTrue(safe.endsWith(".jpg"))
    }

    @Test
    fun `a long name with no extension is still capped`() {
        val safe = FilenameSanitizer.sanitizeToByteLimit("d".repeat(300), 255)
        assertEquals(FilenameSanitizer.MAX_LENGTH, safe.length)
        assertFalse(safe.contains('.'))
    }

    @Test
    fun `a suspiciously long extension is not treated as one`() {
        // 40 characters after the final dot is a dotted name, not a type hint. Reserving it would
        // spend a third of the budget on something no viewer will use.
        val raw = "e".repeat(150) + "." + "f".repeat(40)
        val safe = FilenameSanitizer.sanitizeToByteLimit(raw, 255)
        assertEquals(FilenameSanitizer.MAX_LENGTH, safe.length)
        assertTrue("should have kept the readable stem", safe.startsWith("e".repeat(100)))
    }

    @Test
    fun `a path traversal attempt longer than the cap is still one component`() {
        val raw = "../".repeat(40) + "g".repeat(200) + ".bin"
        val safe = FilenameSanitizer.sanitizeToByteLimit(raw, 255)
        assertFalse(safe.contains('/'))
        assertFalse(safe.contains(".."))
        assertTrue(safe.endsWith(".bin"))
        assertTrue(safe.length <= FilenameSanitizer.MAX_LENGTH)
    }

    @Test
    fun `an all-extension pathological name still fits`() {
        val safe = FilenameSanitizer.sanitizeToByteLimit("." + "x".repeat(400), 32)
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 32)
        assertFalse(safe.isEmpty())
    }
}
