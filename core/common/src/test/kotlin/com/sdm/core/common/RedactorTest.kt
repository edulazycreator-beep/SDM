package com.sdm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    @Test fun masksSensitiveHeaders() {
        val out = Redactor.headers(mapOf("Cookie" to "a=b", "User-Agent" to "SDM", "Authorization" to "Bearer x"))
        assertEquals("[REDACTED]", out["Cookie"])
        assertEquals("[REDACTED]", out["Authorization"])
        assertEquals("SDM", out["User-Agent"])
    }

    @Test fun masksTokenQueryParameters() {
        val out = Redactor.url("https://cdn.example.com/f.zip?token=abc123&name=f.zip&X-Amz-Signature=zz#top")
        assertEquals("https://cdn.example.com/f.zip?token=[REDACTED]&name=f.zip&X-Amz-Signature=[REDACTED]#top", out)
    }

    @Test fun stripsUserInfo() {
        assertEquals("https://[REDACTED]@host/a", Redactor.url("https://user:pw@host/a"))
    }

    @Test fun redactsFreeText() {
        val text = "GET https://h/x?sig=1\nCookie: session=secret\nAuthorization: Bearer abc.def"
        val out = Redactor.text(text)
        assertFalse(out.contains("secret"))
        assertFalse(out.contains("abc.def"))
        assertTrue(out.contains("sig=[REDACTED]"))
    }
}
