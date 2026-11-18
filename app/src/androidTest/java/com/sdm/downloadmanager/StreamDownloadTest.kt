package com.sdm.downloadmanager

import android.content.ContentUris
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real HLS stream through the whole stack: pasted .m3u8 link -> stream inspection (master playlist,
 * quality chip) -> segment download -> remux of MPEG-TS (H.264 + AAC) into MP4 -> MediaStore.
 * The fixture is a 6-second, 3-segment stream committed under testdata/hls.
 */
@RunWith(AndroidJUnit4::class)
class StreamDownloadTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun downloadsAnHlsStreamAsMp4() {
        rule.waitUntil(15_000) { exists("Skip") || exists("Smart Download Manager") }
        if (exists("Skip")) rule.onAllNodesWithText("Skip").onFirst().performClick()
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Downloads", substring = false).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Downloads", substring = false).onFirst().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Add download").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithContentDescription("Add download").onFirst().performClick()
        rule.waitUntil(5_000) { exists("Add download") }
        rule.onNode(hasSetTextAction()).performTextInput(URL)
        rule.onAllNodesWithText("Next").onFirst().performClick()
        rule.waitUntil(30_000) { exists("HLS video stream") }
        assertTrue("quality option listed; dialog shows: ${screenText()}", exists("180p"))
        val confirm = listOf("Download anyway", "Confirm download", "Download").first { label ->
            rule.onAllNodesWithText(label, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText(confirm, substring = false).onFirst().performClick()

        var uri: Uri? = null
        runCatching { rule.waitUntil(180_000) { uri = findOutput(); uri != null } }
            .onFailure { throw AssertionError("no MP4 after 3 min; screen shows: ${screenText()}", it) }
        val output = uri ?: error("stream output not found")
        val ex = MediaExtractor()
        try {
            ex.setDataSource(InstrumentationRegistry.getInstrumentation().targetContext, output, null)
            val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            assertTrue("video track in $mimes", mimes.any { it == MediaFormat.MIMETYPE_VIDEO_AVC })
            assertTrue("audio track in $mimes", mimes.any { it == MediaFormat.MIMETYPE_AUDIO_AAC })
            val video = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
            val durationUs = ex.getTrackFormat(video).getLong(MediaFormat.KEY_DURATION)
            assertTrue("duration $durationUs us", durationUs in 5_000_000L..7_000_000L)
            assertEquals(320, ex.getTrackFormat(video).getInteger(MediaFormat.KEY_WIDTH))
        } finally {
            ex.release()
        }
    }

    private fun screenText(): String =
        rule.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }.joinToString(" | ")

    /** The finished, non-pending MP4 (MediaStore hides pending items). */
    private fun findOutput(): Uri? {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("sdm-test-stream%.mp4"), "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return null
    }

    private companion object {
        const val URL = "https://raw.githubusercontent.com/edulazycreator-beep/SDM/develop/testdata/hls/sdm-test-stream.m3u8"
    }
}
