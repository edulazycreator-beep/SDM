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
        assertTrue(Urls.isValidDownloadUrl("ftp://example.com/a") && Urls.isValidDownloadUrl("sftp://u@h.example/x.zip"))
        assertFalse(Urls.isValidDownloadUrl("file:///etc/passwd") || Urls.isValidDownloadUrl("sftps://h/x"))
        assertEquals(listOf("https://a.example/x", "ftp://b.example/y.iso"), Urls.extract("see https://a.example/x and ftp://b.example/y.iso"))
        val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Ubuntu+24.04+ISO&tr=udp%3A%2F%2Ftracker.example%3A80"
        assertEquals(listOf(magnet), Urls.extract("get it: $magnet"))
        assertTrue(Urls.isMagnet(magnet) && !Urls.isMagnet("magnet:?dn=nothing"))
        assertEquals("Ubuntu 24.04 ISO", Urls.magnetName(magnet))
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
