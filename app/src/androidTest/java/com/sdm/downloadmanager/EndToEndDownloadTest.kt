package com.sdm.downloadmanager

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real download through the whole stack on a device: add dialog -> link inspection -> rules ->
 * queue -> engine (redirect, ranges, several connections) -> MediaStore -> verification -> COMPLETED.
 * Uses a public release asset of this repository (served via a redirect to a CDN that supports ranges).
 */
@RunWith(AndroidJUnit4::class)
class EndToEndDownloadTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun downloadsARealFileToCompletion() {
        rule.waitUntil(15_000) { exists("Skip") || exists("Smart Download Manager") }
        if (exists("Skip")) rule.onAllNodesWithText("Skip").onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Downloads").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithContentDescription("Downloads").onFirst().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Add download").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithContentDescription("Add download").onFirst().performClick()
        rule.waitUntil(5_000) { exists("Add download") }
        rule.onNode(hasSetTextAction()).performTextInput(URL)
        rule.onAllNodesWithText("Next").onFirst().performClick()
        rule.waitUntil(30_000) { exists("Download file") }
        val confirm = listOf("Download anyway", "Confirm download", "Download").first { label ->
            rule.onAllNodesWithText(label, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithText(confirm, substring = false).onFirst().performClick()
        // "· avg <speed>" is only shown for completed downloads (the filter chip also says "Completed").
        rule.waitUntil(180_000) { exists("· avg ") }
    }

    private companion object {
        const val URL = "https://github.com/edulazycreator-beep/SDM/releases/download/build-7/SDM-release-build7.apk"
    }
}
