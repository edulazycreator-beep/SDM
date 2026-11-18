package com.sdm.domain

import com.sdm.core.common.FileCategory
import com.sdm.domain.rules.DownloadFacts
import com.sdm.domain.rules.EnvironmentCommand
import com.sdm.domain.rules.EnvironmentFacts
import com.sdm.domain.rules.Rule
import com.sdm.domain.rules.RuleAction
import com.sdm.domain.rules.RuleCondition
import com.sdm.domain.rules.RuleEngine
import com.sdm.domain.scheduler.Conditions
import com.sdm.domain.scheduler.Schedule
import com.sdm.domain.scheduler.ScheduleProfile
import com.sdm.domain.scheduler.SchedulePolicy
import com.sdm.domain.scheduler.ScheduleVerdict
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RulesScheduleTest {
    private val rules = RuleEngine.defaults().mapIndexed { i, r -> r.copy(id = i + 1L) }
    private val wifi = EnvironmentFacts(connected = true, metered = false, batteryPercent = 80, charging = false, freeStorageBytes = 10L shl 30)

    @Test fun bigFileAsksConfirmation() {
        val d = RuleEngine.onEnqueue(rules, DownloadFacts("movie.mkv", null, 2L shl 30, "https://h/movie.mkv"))
        assertTrue(d.askConfirmation)
        assertEquals(listOf("Confirm files over 1 GB"), d.appliedRules)
    }

    @Test fun apkGoesToApplications() {
        val d = RuleEngine.onEnqueue(rules, DownloadFacts("app.apk", null, 1000, "https://h/app.apk"))
        assertEquals(FileCategory.APPLICATION, d.category)
        assertFalse(d.askConfirmation)
    }

    @Test fun firstMatchingRuleWinsPerAction() {
        val custom = listOf(
            Rule(1, "a", true, 1, RuleCondition.ExtensionIn(listOf("mp4")), RuleAction.SaveToCategoryFolder(FileCategory.DOCUMENT)),
            Rule(2, "b", true, 2, RuleCondition.ExtensionIn(listOf("mp4")), RuleAction.SaveToCategoryFolder(FileCategory.VIDEO)),
        )
        assertEquals(FileCategory.DOCUMENT, RuleEngine.onEnqueue(custom, DownloadFacts("x.mp4", null, 1, "https://h/x.mp4")).category)
        assertEquals(FileCategory.VIDEO, RuleEngine.onEnqueue(custom.map { if (it.id == 1L) it.copy(enabled = false) else it }, DownloadFacts("x.mp4", null, 1, "https://h/x.mp4")).category)
    }

    @Test fun environmentRules() {
        assertEquals(EnvironmentCommand.RESUME_ALL, RuleEngine.onEnvironment(rules, wifi).command)
        val metered = wifi.copy(metered = true)
        assertNull(RuleEngine.onEnvironment(rules, metered).command) // pause-on-mobile is disabled by default
        val enabled = rules.map { if (it.condition == RuleCondition.OnMeteredNetwork) it.copy(enabled = true) else it }
        assertEquals(EnvironmentCommand.PAUSE_ALL, RuleEngine.onEnvironment(enabled, metered).command)
        assertTrue(RuleEngine.onEnvironment(rules, wifi.copy(freeStorageBytes = 1)).notifications.isNotEmpty())
    }

    @Test fun rulesSerializeRoundTrip() {
        val json = Json
        val c: RuleCondition = RuleCondition.SizeAbove(42)
        val text = json.encodeToString(RuleCondition.serializer(), c)
        assertEquals(c, json.decodeFromString(RuleCondition.serializer(), text))
        val a: RuleAction = RuleAction.PauseDownloads
        assertEquals(a, json.decodeFromString(RuleAction.serializer(), json.encodeToString(RuleAction.serializer(), a)))
    }

    private val zone = ZoneId.of("UTC")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 18, h, m, 0, 0, zone).toInstant().toEpochMilli() // a Wednesday
    private val any = Conditions(connected = true, unmetered = true, charging = true, batteryPercent = 90)

    @Test fun nightWindowCrossesMidnightCorrectly() {
        val night = Schedule(ScheduleProfile.NIGHT, startMinute = 23 * 60, endMinute = 6 * 60)
        assertTrue(SchedulePolicy.evaluate(night, at(23, 30), any, zone) is ScheduleVerdict.Allowed)
        assertTrue(SchedulePolicy.evaluate(night, at(2), any, zone) is ScheduleVerdict.Allowed)
        val blocked = SchedulePolicy.evaluate(night, at(12), any, zone)
        assertTrue(blocked is ScheduleVerdict.Blocked)
        assertEquals(at(23), (blocked as ScheduleVerdict.Blocked).nextWindowStart)
    }

    @Test fun wifiOnlyBlocksOnMobile() {
        val v = SchedulePolicy.evaluate(SchedulePolicy.preset(ScheduleProfile.WIFI_ONLY), at(12), any.copy(unmetered = false), zone)
        assertTrue(v is ScheduleVerdict.Blocked)
    }

    @Test fun manualStartTime() {
        val s = Schedule(ScheduleProfile.MANUAL, startAt = at(20), stopAt = at(22))
        assertEquals(at(20), (SchedulePolicy.evaluate(s, at(19), any, zone) as ScheduleVerdict.Blocked).nextWindowStart)
        assertTrue(SchedulePolicy.evaluate(s, at(21), any, zone) is ScheduleVerdict.Allowed)
        assertTrue(SchedulePolicy.evaluate(s, at(23), any, zone) is ScheduleVerdict.Blocked)
    }

    @Test fun daysMaskRespected() {
        val weekends = Schedule(ScheduleProfile.CUSTOM, startMinute = 0, endMinute = 0, daysMask = 0b1100000)
        assertFalse(SchedulePolicy.inWindow(weekends, at(12), zone))
    }
}
