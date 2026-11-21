package com.sdm.core.data.rules

import com.sdm.core.database.QueueDao
import com.sdm.core.database.QueueEntity
import com.sdm.core.database.RuleDao
import com.sdm.core.database.RuleEntity
import com.sdm.core.database.SdmDatabase
import com.sdm.domain.intelligence.QueueOrdering
import com.sdm.domain.rules.Rule
import com.sdm.domain.rules.RuleAction
import com.sdm.domain.rules.RuleCondition
import com.sdm.domain.rules.RuleEngine
import com.sdm.domain.scheduler.Schedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RulesRepository @Inject constructor(private val dao: RuleDao) {
    private val json = Json { ignoreUnknownKeys = true }

    val rules: Flow<List<Rule>> = dao.observeAll().map { list -> list.mapNotNull { it.toDomain() } }

    suspend fun current(): List<Rule> = dao.all().mapNotNull { it.toDomain() }

    suspend fun ensureDefaults() {
        if (dao.count() == 0) RuleEngine.defaults().forEach { save(it) }
    }

    suspend fun save(rule: Rule): Long = dao.upsert(RuleEntity(
        id = rule.id, name = rule.name.trim().ifBlank { "Rule" }, enabled = rule.enabled, priority = rule.priority,
        conditionJson = json.encodeToString(RuleCondition.serializer(), rule.condition),
        actionJson = json.encodeToString(RuleAction.serializer(), rule.action),
    ))

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun replaceAll(rules: List<Rule>) { dao.clear(); rules.forEach { save(it.copy(id = 0)) } }

    private fun RuleEntity.toDomain(): Rule? = runCatching {
        Rule(id, name, enabled, priority, json.decodeFromString(RuleCondition.serializer(), conditionJson), json.decodeFromString(RuleAction.serializer(), actionJson))
    }.getOrNull()
}

@Singleton
class QueueRepository @Inject constructor(private val dao: QueueDao) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class QueueConfig(val id: Long, val name: String, val maxConcurrent: Int, val ordering: QueueOrdering, val schedule: Schedule, val paused: Boolean)

    val queues: Flow<List<QueueConfig>> = dao.observeAll().map { l -> l.map { it.toConfig() } }

    suspend fun main(): QueueConfig = (dao.get(SdmDatabase.MAIN_QUEUE_ID) ?: QueueEntity(SdmDatabase.MAIN_QUEUE_ID, "Main").also { dao.upsert(it) }).toConfig()
    suspend fun get(id: Long): QueueConfig? = dao.get(id)?.toConfig()
    suspend fun all(): List<QueueConfig> = dao.all().map { it.toConfig() }

    suspend fun save(c: QueueConfig) = dao.upsert(QueueEntity(c.id, c.name, c.maxConcurrent, c.ordering.name, json.encodeToString(Schedule.serializer(), c.schedule), c.paused))

    private fun QueueEntity.toConfig() = QueueConfig(
        id, name, maxConcurrent,
        QueueOrdering.entries.firstOrNull { it.name == ordering } ?: QueueOrdering.MANUAL,
        scheduleJson?.let { runCatching { json.decodeFromString(Schedule.serializer(), it) }.getOrNull() } ?: Schedule(),
        paused,
    )
}
