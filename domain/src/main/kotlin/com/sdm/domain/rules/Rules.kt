package com.sdm.domain.rules

import com.sdm.core.common.FileCategory
import com.sdm.core.common.FileNames
import com.sdm.domain.NetworkPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Conditions of the rule engine (spec section 14). */
@Serializable
sealed interface RuleCondition {
    val label: String

    @Serializable @SerialName("size_above")
    data class SizeAbove(val bytes: Long) : RuleCondition { override val label get() = "File size exceeds ${com.sdm.core.common.Formatters.bytes(bytes)}" }

    @Serializable @SerialName("extension_in")
    data class ExtensionIn(val extensions: List<String>) : RuleCondition { override val label get() = "File extension is ${extensions.joinToString(", ") { it.uppercase() }}" }

    @Serializable @SerialName("category_is")
    data class CategoryIs(val category: FileCategory) : RuleCondition { override val label get() = "File type is ${category.label}" }

    @Serializable @SerialName("host_is")
    data class HostIs(val host: String) : RuleCondition { override val label get() = "Source host is $host" }

    @Serializable @SerialName("on_metered")
    data object OnMeteredNetwork : RuleCondition { override val label get() = "Connected to mobile data" }

    @Serializable @SerialName("on_unmetered")
    data object OnUnmeteredNetwork : RuleCondition { override val label get() = "Connected to Wi-Fi" }

    @Serializable @SerialName("battery_below")
    data class BatteryBelow(val percent: Int) : RuleCondition { override val label get() = "Battery below $percent% (not charging)" }

    @Serializable @SerialName("storage_below")
    data class StorageBelow(val bytes: Long) : RuleCondition { override val label get() = "Free storage below ${com.sdm.core.common.Formatters.bytes(bytes)}" }

    @Serializable @SerialName("download_failed")
    data object DownloadFailed : RuleCondition { override val label get() = "A download fails" }
}

@Serializable
sealed interface RuleAction {
    val label: String

    @Serializable @SerialName("ask")
    data object AskConfirmation : RuleAction { override val label get() = "Ask for confirmation" }

    @Serializable @SerialName("save_to_category")
    data class SaveToCategoryFolder(val category: FileCategory) : RuleAction { override val label get() = "Save to ${category.folder} folder" }

    @Serializable @SerialName("save_to_folder")
    data class SaveToFolder(val treeUri: String, val folderLabel: String) : RuleAction { override val label get() = "Save to $folderLabel" }

    @Serializable @SerialName("network_policy")
    data class SetNetworkPolicy(val policy: NetworkPolicy) : RuleAction { override val label get() = "Network: ${policy.name.lowercase().replace('_', ' ')}" }

    @Serializable @SerialName("speed_limit")
    data class SetSpeedLimit(val bytesPerSecond: Long) : RuleAction { override val label get() = "Limit speed to ${com.sdm.core.common.Formatters.speed(bytesPerSecond)}" }

    @Serializable @SerialName("connections")
    data class SetConnections(val connections: Int) : RuleAction { override val label get() = "Use $connections connections" }

    @Serializable @SerialName("pause_all")
    data object PauseDownloads : RuleAction { override val label get() = "Pause downloads" }

    @Serializable @SerialName("resume_all")
    data object ResumeDownloads : RuleAction { override val label get() = "Resume queued downloads" }

    @Serializable @SerialName("notify")
    data class Notify(val message: String) : RuleAction { override val label get() = "Notify: $message" }

    @Serializable @SerialName("retry")
    data object RetryWithPolicy : RuleAction { override val label get() = "Retry with the retry policy" }
}

data class Rule(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val condition: RuleCondition,
    val action: RuleAction,
)

/** Facts about a new download, used for enqueue-time rules. */
data class DownloadFacts(val fileName: String, val mimeType: String?, val sizeBytes: Long?, val url: String) {
    val extension: String get() = FileNames.extension(fileName)
    val category: FileCategory get() = FileCategory.of(fileName, mimeType)
    val host: String? get() = com.sdm.core.common.Urls.host(url)
}

/** Facts about the device, used for environment rules. */
data class EnvironmentFacts(
    val connected: Boolean,
    val metered: Boolean,
    val batteryPercent: Int,
    val charging: Boolean,
    val freeStorageBytes: Long?,
)

/** What enqueue-time rules decided for one download. First matching rule wins per action kind. */
data class EnqueueDecision(
    val askConfirmation: Boolean = false,
    val category: FileCategory? = null,
    val treeUri: String? = null,
    val networkPolicy: NetworkPolicy? = null,
    val speedLimitBps: Long? = null,
    val connections: Int? = null,
    val appliedRules: List<String> = emptyList(),
)

enum class EnvironmentCommand { PAUSE_ALL, RESUME_ALL }

data class EnvironmentDecision(val command: EnvironmentCommand?, val notifications: List<String>, val appliedRules: List<String>)

object RuleEngine {
    private fun ordered(rules: List<Rule>) = rules.filter { it.enabled }.sortedWith(compareBy({ it.priority }, { it.id }))

    fun matches(c: RuleCondition, d: DownloadFacts?, e: EnvironmentFacts?): Boolean = when (c) {
        is RuleCondition.SizeAbove -> d?.sizeBytes != null && d.sizeBytes > c.bytes
        is RuleCondition.ExtensionIn -> d != null && d.extension in c.extensions.map { it.lowercase().trimStart('.') }
        is RuleCondition.CategoryIs -> d != null && d.category == c.category
        is RuleCondition.HostIs -> d?.host?.let { h -> h == c.host.lowercase() || h.endsWith("." + c.host.lowercase()) } ?: false
        RuleCondition.OnMeteredNetwork -> e != null && e.connected && e.metered
        RuleCondition.OnUnmeteredNetwork -> e != null && e.connected && !e.metered
        is RuleCondition.BatteryBelow -> e != null && !e.charging && e.batteryPercent in 0 until c.percent
        is RuleCondition.StorageBelow -> e?.freeStorageBytes != null && e.freeStorageBytes < c.bytes
        RuleCondition.DownloadFailed -> false // evaluated through onFailure()
    }

    fun onEnqueue(rules: List<Rule>, facts: DownloadFacts, env: EnvironmentFacts? = null): EnqueueDecision {
        var d = EnqueueDecision()
        for (r in ordered(rules)) {
            if (!matches(r.condition, facts, env)) continue
            val before = d
            d = when (val a = r.action) {
                RuleAction.AskConfirmation -> if (d.askConfirmation) d else d.copy(askConfirmation = true)
                is RuleAction.SaveToCategoryFolder -> if (d.category != null || d.treeUri != null) d else d.copy(category = a.category)
                is RuleAction.SaveToFolder -> if (d.category != null || d.treeUri != null) d else d.copy(treeUri = a.treeUri)
                is RuleAction.SetNetworkPolicy -> if (d.networkPolicy != null) d else d.copy(networkPolicy = a.policy)
                is RuleAction.SetSpeedLimit -> if (d.speedLimitBps != null) d else d.copy(speedLimitBps = a.bytesPerSecond)
                is RuleAction.SetConnections -> if (d.connections != null) d else d.copy(connections = a.connections)
                else -> d
            }
            if (d != before) d = d.copy(appliedRules = d.appliedRules + r.name)
        }
        return d
    }

    fun onEnvironment(rules: List<Rule>, env: EnvironmentFacts): EnvironmentDecision {
        var command: EnvironmentCommand? = null
        val notes = mutableListOf<String>()
        val applied = mutableListOf<String>()
        for (r in ordered(rules)) {
            if (r.condition is RuleCondition.DownloadFailed) continue
            if (!matches(r.condition, null, env)) continue
            when (val a = r.action) {
                RuleAction.PauseDownloads -> if (command == null) { command = EnvironmentCommand.PAUSE_ALL; applied += r.name }
                RuleAction.ResumeDownloads -> if (command == null) { command = EnvironmentCommand.RESUME_ALL; applied += r.name }
                is RuleAction.Notify -> { notes += a.message; applied += r.name }
                else -> Unit
            }
        }
        return EnvironmentDecision(command, notes, applied)
    }

    /** Rules attached to the failure condition: whether to retry and what to tell the user. */
    fun onFailure(rules: List<Rule>): Pair<Boolean, List<String>> {
        val matched = ordered(rules).filter { it.condition is RuleCondition.DownloadFailed }
        val retry = matched.any { it.action is RuleAction.RetryWithPolicy }
        return retry to matched.mapNotNull { (it.action as? RuleAction.Notify)?.message }
    }

    /** The spec's example rules, created on first launch. Environment pause rules start disabled. */
    fun defaults(): List<Rule> = listOf(
        Rule(0, "Confirm files over 1 GB", true, 10, RuleCondition.SizeAbove(1L shl 30), RuleAction.AskConfirmation),
        Rule(0, "Pause on mobile data", false, 20, RuleCondition.OnMeteredNetwork, RuleAction.PauseDownloads),
        Rule(0, "Resume on Wi-Fi", true, 30, RuleCondition.OnUnmeteredNetwork, RuleAction.ResumeDownloads),
        Rule(0, "APKs to Applications", true, 40, RuleCondition.ExtensionIn(listOf("apk")), RuleAction.SaveToCategoryFolder(FileCategory.APPLICATION)),
        Rule(0, "MP4 to Videos", true, 50, RuleCondition.ExtensionIn(listOf("mp4")), RuleAction.SaveToCategoryFolder(FileCategory.VIDEO)),
        Rule(0, "Retry failed downloads", true, 60, RuleCondition.DownloadFailed, RuleAction.RetryWithPolicy),
        Rule(0, "Warn when storage is low", true, 70, RuleCondition.StorageBelow(500L shl 20), RuleAction.Notify("Storage is almost full; downloads may fail.")),
        Rule(0, "Pause on low battery", false, 80, RuleCondition.BatteryBelow(15), RuleAction.PauseDownloads),
    )
}
