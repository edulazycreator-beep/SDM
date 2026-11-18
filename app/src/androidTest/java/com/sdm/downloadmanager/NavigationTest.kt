package com.sdm.downloadmanager

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun exists(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun visitsAllTopLevelSectionsAndBack() {
        rule.waitUntil(15_000) { exists("Skip") || exists("Smart Download Manager") }
        if (exists("Skip")) rule.onAllNodesWithText("Skip").onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Downloads").fetchSemanticsNodes().isNotEmpty() }

        rule.onAllNodesWithContentDescription("Downloads").onFirst().performClick()
        rule.waitUntil(5_000) { exists("No downloads here") || exists("Downloads") }

        rule.onAllNodesWithContentDescription("Files").onFirst().performClick()
        rule.waitUntil(5_000) { exists("All files") }

        rule.onAllNodesWithContentDescription("Settings").onFirst().performClick()
        rule.waitUntil(5_000) { exists("Appearance") }
        rule.onAllNodesWithText("Appearance").onFirst().performClick()
        rule.waitUntil(5_000) { exists("AMOLED black") }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { exists("Smart rules") }

        rule.onAllNodesWithContentDescription("Browser").onFirst().performClick()
        rule.waitUntil(5_000) { exists("Search or enter address") }

        rule.onAllNodesWithContentDescription("Home").onFirst().performClick()
        rule.waitUntil(5_000) { exists("Quick actions") || exists("Active") }
    }

    @Test fun survivesRecreation() {
        rule.waitUntil(15_000) { exists("Skip") || exists("Smart Download Manager") }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(15_000) { exists("Skip") || exists("Smart Download Manager") }
    }
}
