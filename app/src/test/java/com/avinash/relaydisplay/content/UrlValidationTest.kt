package com.avinash.relaydisplay.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidationTest {

    @Test
    fun `https is allowed`() {
        assertEquals(UrlValidation.Verdict.ALLOWED, UrlValidation.check("https://example.com"))
        assertEquals(UrlValidation.Verdict.ALLOWED, UrlValidation.check("https://example.com/path?a=b#c"))
    }

    @Test
    fun `http is allowed but flagged`() {
        assertEquals(UrlValidation.Verdict.ALLOWED_INSECURE, UrlValidation.check("http://example.com"))
    }

    @Test
    fun `dangerous schemes are rejected`() {
        for (url in listOf(
            "file:///sdcard/secret.txt",
            "content://com.android.contacts/data/1",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "jar:file:///a.jar!/b",
            "market://details?id=com.example",
            "android-app://com.example",
        )) {
            assertEquals("accepted $url", UrlValidation.Verdict.REJECTED_SCHEME, UrlValidation.check(url))
            assertFalse(UrlValidation.isAcceptable(url))
        }
    }

    @Test
    fun `unknown custom schemes are rejected`() {
        assertEquals(UrlValidation.Verdict.REJECTED_SCHEME, UrlValidation.check("myapp://do-something"))
        assertEquals(UrlValidation.Verdict.REJECTED_SCHEME, UrlValidation.check("tel:+15551234"))
        assertEquals(UrlValidation.Verdict.REJECTED_SCHEME, UrlValidation.check("sms:+15551234"))
    }

    @Test
    fun `scheme matching is case insensitive`() {
        assertEquals(UrlValidation.Verdict.ALLOWED, UrlValidation.check("HTTPS://example.com"))
        assertEquals(UrlValidation.Verdict.REJECTED_SCHEME, UrlValidation.check("JavaScript:alert(1)"))
    }

    @Test
    fun `malformed urls are rejected`() {
        for (url in listOf("", "   ", "example.com", "https:", "https:/example.com", "https://", "://x")) {
            assertFalse("accepted '$url'", UrlValidation.isAcceptable(url))
        }
    }

    @Test
    fun `a scheme without an authority is rejected`() {
        assertEquals(UrlValidation.Verdict.REJECTED_MALFORMED, UrlValidation.check("https:evil"))
    }

    @Test
    fun `whitespace or control characters in the host are rejected`() {
        assertEquals(UrlValidation.Verdict.REJECTED_MALFORMED, UrlValidation.check("https://exa mple.com"))
        assertEquals(UrlValidation.Verdict.REJECTED_MALFORMED, UrlValidation.check("https://exa\tmple.com"))
        assertEquals(UrlValidation.Verdict.REJECTED_MALFORMED, UrlValidation.check("https://exa\u0000mple.com"))
        assertEquals(UrlValidation.Verdict.REJECTED_MALFORMED, UrlValidation.check("https://exa\nmple.com"))
    }

    @Test
    fun `an over long url is rejected before parsing`() {
        val url = "https://example.com/" + "a".repeat(UrlValidation.MAX_URL_LENGTH)
        assertEquals(UrlValidation.Verdict.REJECTED_TOO_LONG, UrlValidation.check(url))
    }

    @Test
    fun `host extraction ignores userinfo`() {
        // The part before the @ is userinfo; the real host is what comes after it.
        assertEquals("evil.test", UrlValidation.hostOf("https://example.com@evil.test/path"))
    }

    @Test
    fun `host extraction strips the port and path`() {
        assertEquals("example.com", UrlValidation.hostOf("https://example.com:8443/a/b?c=d"))
        assertEquals("example.com", UrlValidation.hostOf("https://example.com"))
    }

    @Test
    fun `host extraction refuses a url it would not allow`() {
        assertNull(UrlValidation.hostOf("javascript:alert(1)"))
        assertNull(UrlValidation.hostOf("nonsense"))
    }
}

class FilenameSanitizerTest {

    @Test
    fun `keeps an ordinary name`() {
        assertEquals("photo.jpg", FilenameSanitizer.sanitize("photo.jpg"))
        assertEquals("My_Report-2024.pdf", FilenameSanitizer.sanitize("My_Report-2024.pdf"))
    }

    @Test
    fun `strips path traversal`() {
        assertEquals("passwd", FilenameSanitizer.sanitize("../../etc/passwd"))
        assertEquals("passwd", FilenameSanitizer.sanitize("/etc/passwd"))
        assertEquals("file.txt", FilenameSanitizer.sanitize("..\\..\\windows\\file.txt"))
    }

    @Test
    fun `a name that is only dots falls back`() {
        assertEquals("received-file", FilenameSanitizer.sanitize(".."))
        assertEquals("received-file", FilenameSanitizer.sanitize("."))
        assertEquals("received-file", FilenameSanitizer.sanitize("/"))
        assertEquals("received-file", FilenameSanitizer.sanitize(""))
    }

    @Test
    fun `replaces characters that break a path or a shell`() {
        assertEquals("a_b_c.txt", FilenameSanitizer.sanitize("a:b*c.txt"))
        assertEquals("q_mark_.png", FilenameSanitizer.sanitize("q?mark?.png"))
    }

    @Test
    fun `drops control characters but keeps spaces`() {
        assertEquals("my report.pdf", FilenameSanitizer.sanitize("my report.pdf"))
        assertEquals("name.txt", FilenameSanitizer.sanitize("na\u0000me\u0007.txt"))
        assertEquals("ab.txt", FilenameSanitizer.sanitize("a\u007Fb.txt"))
    }

    @Test
    fun `defuses reserved device names`() {
        assertTrue(FilenameSanitizer.sanitize("CON").startsWith("received-file"))
        assertTrue(FilenameSanitizer.sanitize("NUL.txt").startsWith("received-file"))
    }

    @Test
    fun `bounds the length`() {
        val long = "a".repeat(500) + ".jpg"
        assertEquals(FilenameSanitizer.MAX_LENGTH, FilenameSanitizer.sanitize(long).length)
    }

    @Test
    fun `never returns an empty name`() {
        for (input in listOf("///", "\u0000\u0001", "   ", "***")) {
            assertTrue("empty for '$input'", FilenameSanitizer.sanitize(input).isNotEmpty())
        }
    }
}
