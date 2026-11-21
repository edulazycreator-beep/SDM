package com.sdm.core.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class I18nTest {
    @After fun reset() { I18n.override = null }

    @Test fun englishIsUnchanged() {
        I18n.override = "en"
        assertEquals("Pause all", I18n.tr("Pause all"))
    }

    @Test fun translatesExactTextsPatternsAndParts() {
        I18n.override = "id"
        assertEquals("Jeda semua", I18n.tr("Pause all"))
        assertEquals("3 unduhan ditambahkan", I18n.tr("Added 3 download(s)"))
        // Values that are known words are translated too.
        assertEquals("Tema Gelap · Lega", I18n.tr("Dark theme · Comfortable"))
        // Joined parts are translated piece by piece.
        assertEquals("Setelan situs · 12 diblokir", I18n.tr("Site settings · 12 blocked"))
        // Unknown text (file names, titles) stays as it is.
        assertEquals("holiday-video.mp4", I18n.tr("holiday-video.mp4"))
        // A value containing "{}" is inserted as it is.
        assertEquals("Hapus \"a{}b.zip\"?", I18n.tr("Delete \"a{}b.zip\"?"))
        // Android reports Indonesian as "in".
        I18n.override = "in"
        assertEquals("Lanjutkan", I18n.tr("Resume"))
    }
}
