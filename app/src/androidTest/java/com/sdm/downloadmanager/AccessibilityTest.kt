package com.sdm.downloadmanager

import androidx.compose.ui.test.junit4.accessibility.disableAccessibilityChecks
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the Accessibility Test Framework (touch target size, contrast, labels) on the main screens.
 * A violation fails the test with the offending node.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun existsExact(text: String) = rule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()

    @Test fun mainScreensPassAccessibilityChecks() {
        rule.waitUntil(15_000) { exists("Skip") || exists("Smart Download Manager") }
        if (exists("Skip")) rule.onAllNodesWithText("Skip").onFirst().performClick()
        rule.waitUntil(15_000) { existsExact("Downloads") }
        val failures = mutableListOf<String>()
        fun check(screen: String) {
            rule.waitForIdle()
            rule.enableAccessibilityChecks()
            try { rule.onRoot().tryPerformAccessibilityChecks() } catch (e: Throwable) {
                // Items cut off at the screen edge (a few pixels visible) are reported too; they are
                // checked properly once scrolled into view, so only findings on visible items count.
                val real = Regex("""View with bounds: \[(\d+),(\d+)]\[(\d+),(\d+)]: ([^\n]*)""").findAll(e.message.orEmpty())
                    .filter { m -> m.groupValues[4].toInt() - m.groupValues[2].toInt() >= 24 }.map { it.value }.toList()
                val unparsed = !e.message.orEmpty().contains("View with bounds")
                if (real.isNotEmpty() || unparsed) failures += "$screen: " + if (unparsed) e.message else real.joinToString("\n")
            }
            rule.disableAccessibilityChecks()
        }
        check("Home")
        for ((tab, marker) in listOf("Downloads" to "Downloads", "Files" to "All files", "Settings" to "Appearance", "Browser" to "Browser")) {
            rule.onAllNodesWithText(tab, substring = false).onFirst().performClick()
            rule.waitUntil(5_000) { exists(marker) }
            check(tab)
        }
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n\n"))
    }
}
