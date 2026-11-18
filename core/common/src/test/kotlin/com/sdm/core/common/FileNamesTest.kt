package com.sdm.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test fun blocksPathTraversal() {
        val name = FileNames.sanitize("../../etc/passwd")
        assertFalse(name.contains("/"))
        assertFalse(name.startsWith("."))
    }

    @Test fun prefersRfc5987FileName() {
        assertEquals("résumé final.pdf", FileNames.fromContentDisposition("attachment; filename=\"x.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9%20final.pdf"))
        assertEquals("a b.zip", FileNames.fromContentDisposition("attachment; filename=\"a b.zip\""))
    }

    @Test fun resolvesFromUrlAndMime() {
        assertEquals("archive.zip", FileNames.resolve("https://h/dl/archive.zip?x=1", null, null))
        assertEquals("report.pdf", FileNames.resolve("https://h/report", null, "application/pdf"))
        assertEquals("h", FileNames.resolve("https://h/", null, null))
    }

    @Test fun numbersCollisions() = assertEquals("a (2).zip", FileNames.numbered("a.zip", 2))

    @Test fun urlValidation() {
        assertTrue(Urls.isValidHttpUrl("https://example.com/a.zip"))
        assertFalse(Urls.isValidHttpUrl("ftp://example.com/a"))
        assertFalse(Urls.isValidHttpUrl("javascript:alert(1)"))
        assertEquals(listOf("https://a.com/x.zip", "http://b.org/y"), Urls.extract("get https://a.com/x.zip, then http://b.org/y. https://a.com/x.zip"))
    }

    @Test fun categories() {
        assertEquals(FileCategory.APPLICATION, FileCategory.of("app.apk"))
        assertEquals(FileCategory.VIDEO, FileCategory.of("clip", "video/mp4"))
        assertEquals(FileCategory.OTHER, FileCategory.of("data.bin"))
    }

    @Test fun formatting() {
        assertEquals("1.5 KB", Formatters.bytes(1536))
        assertEquals("1h 1m", Formatters.duration(3660))
        assertEquals(50, Formatters.percent(5, 10))
    }
}
